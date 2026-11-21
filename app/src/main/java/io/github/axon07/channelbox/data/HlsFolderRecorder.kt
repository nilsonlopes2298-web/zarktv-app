package io.github.axon07.channelbox.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Records HLS channels whose audio comes from a separate playlist (#EXT-X-MEDIA TYPE=AUDIO).
 * Such channels have silent video segments, so saving only the video playlist gave recordings
 * without sound. Here the video and audio segments are both saved, with local playlists:
 *
 * `<folder>/index.m3u8`, `<folder>/video/playlist.m3u8 + segments`, `<folder>/audio/playlist.m3u8 + segments`
 *
 * AES-128 segments are decrypted (as for single-file recordings); DRM-protected segments are saved
 * as delivered and keep their key tags, so they play back through the channel's licence.
 */
internal class HlsFolderRecorder(
    private val folder: File,
    private val variant: Hls.Variant,
    private val audioUrl: String,
    private val audioAttrs: Map<String, String>,
    private val keepEncrypted: Boolean,
    private val fetch: suspend (url: String) -> ByteArray,
    private val decrypt: (data: ByteArray, key: ByteArray, iv: String?, sequence: Long) -> ByteArray,
    private val onProgress: suspend (bytes: Long, indexPath: String) -> Unit,
) {
    private class Entry(val file: String, val duration: Double, val discontinuity: Boolean, val map: String?, val keyLine: String?)

    private inner class Track(val name: String, val url: String) {
        val entries = ArrayList<Entry>()
        val seen = LinkedHashSet<String>()
        var started = false
        var mapSource: String? = null
        var mapFile: String? = null
        var maps = 0
        var target = 6
        var pendingDiscontinuity = false
    }

    private val tracks = listOf(Track("video", variant.uri), Track("audio", audioUrl))
    private val keys = HashMap<String, ByteArray>()
    private var bytes = 0L
    private val index = File(folder, INDEX)

    val bytesWritten get() = bytes

    suspend fun run(until: () -> Boolean) {
        tracks.forEach { File(folder, it.name).mkdirs() }
        writeMaster()
        var failures = 0
        while (until()) {
            currentCoroutineContext().ensureActive()
            var ended = true
            for (t in tracks) {
                try {
                    val media = Hls.parseMedia(fetch(t.url).decodeToString(), t.url)
                    t.target = media.targetDuration
                    poll(t, media, until)
                    if (!media.ended) ended = false
                    failures = 0
                } catch (e: IOException) {
                    ended = false
                    if (++failures > 30) throw e
                }
            }
            writePlaylists(done = false)
            onProgress(bytes, index.absolutePath)
            if (ended || !until()) return
            delay((tracks.first().target * 500L).coerceIn(1_000L, 6_000L))
        }
    }

    private suspend fun poll(t: Track, media: Hls.Media, until: () -> Boolean) {
        if (!t.started) {
            t.started = true
            // Start at the live edge (the last few segments) in both playlists so they line up.
            if (!media.ended) media.segments.dropLast(3).forEach { t.seen += it.uri }
        }
        for (segment in media.segments) {
            currentCoroutineContext().ensureActive()
            if (!until()) return
            if (segment.uri in t.seen) continue
            t.seen += segment.uri
            if (t.seen.size > 3000) t.seen.remove(t.seen.first())
            if (segment.discontinuity) t.pendingDiscontinuity = true
            // A new init segment (fMP4): save it next to the media segments.
            if (media.mapUri != null && media.mapUri != t.mapSource) {
                val name = "init_${++t.maps}.mp4"
                val data = fetch(media.mapUri)
                File(folder, "${t.name}/$name").writeBytes(data)
                bytes += data.size
                t.mapSource = media.mapUri
                t.mapFile = name
            }
            try {
                var data = fetch(segment.uri)
                val key = segment.key
                var keyLine: String? = null
                if (key != null) {
                    if (key.method == "AES-128" && key.uri != null && !keepEncrypted) {
                        val keyBytes = keys.getOrPut(key.uri) { fetch(key.uri) }
                        data = decrypt(data, keyBytes, key.iv, segment.sequence)
                    } else {
                        keyLine = key.line // DRM: saved still encrypted
                    }
                }
                val ext = segment.uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "ts")
                    .lowercase(Locale.US).takeIf { it.length in 2..4 } ?: "ts"
                val name = "%06d.%s".format(Locale.US, t.entries.size + 1, ext)
                File(folder, "${t.name}/$name").writeBytes(data)
                bytes += data.size
                t.entries += Entry(name, segment.duration.takeIf { it > 0 } ?: t.target.toDouble(), t.pendingDiscontinuity, t.mapFile, keyLine)
                t.pendingDiscontinuity = false
            } catch (e: IOException) {
                // Skip the odd broken segment; mark the gap so timestamps don't get confused.
                t.pendingDiscontinuity = true
            }
        }
    }

    private fun writeMaster() {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="${audioAttrs["NAME"] ?: "Audio"}",DEFAULT=YES,AUTOSELECT=YES""")
        audioAttrs["LANGUAGE"]?.let { sb.append(""",LANGUAGE="$it"""") }
        audioAttrs["CHANNELS"]?.let { sb.append(""",CHANNELS="$it"""") }
        sb.append(""",URI="audio/$PLAYLIST"""").append('\n')
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=${variant.bandwidth.coerceAtLeast(1)}")
        variant.attrs["CODECS"]?.let { sb.append(""",CODECS="$it"""") }
        variant.attrs["RESOLUTION"]?.let { sb.append(",RESOLUTION=$it") }
        variant.attrs["FRAME-RATE"]?.let { sb.append(",FRAME-RATE=$it") }
        sb.append(""",AUDIO="aud"""").append('\n')
        sb.append("video/$PLAYLIST\n")
        index.writeText(sb.toString())
    }

    /** Rewrites both media playlists (EVENT while recording, VOD when finished). */
    fun writePlaylists(done: Boolean) {
        for (t in tracks) {
            val target = kotlin.math.ceil(t.entries.maxOfOrNull { it.duration } ?: t.target.toDouble()).toInt().coerceAtLeast(1)
            val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n")
            sb.append("#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:0\n")
            sb.append("#EXT-X-PLAYLIST-TYPE:${if (done) "VOD" else "EVENT"}\n")
            var map: String? = null
            var key: String? = null
            t.entries.forEachIndexed { i, e ->
                if (e.discontinuity && i > 0) sb.append("#EXT-X-DISCONTINUITY\n")
                if (e.map != null && e.map != map) {
                    sb.append("""#EXT-X-MAP:URI="${e.map}"""").append('\n')
                    map = e.map
                }
                if (e.keyLine != key) {
                    sb.append(e.keyLine ?: "#EXT-X-KEY:METHOD=NONE").append('\n')
                    key = e.keyLine
                }
                sb.append("#EXTINF:%.3f,\n".format(Locale.US, e.duration)).append(e.file).append('\n')
            }
            if (done) sb.append("#EXT-X-ENDLIST\n")
            val out = File(folder, "${t.name}/$PLAYLIST")
            val tmp = File(folder, "${t.name}/$PLAYLIST.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(out)) {
                out.delete()
                tmp.renameTo(out)
            }
        }
    }

    companion object {
        const val INDEX = "index.m3u8"
        private const val PLAYLIST = "playlist.m3u8"
    }
}
