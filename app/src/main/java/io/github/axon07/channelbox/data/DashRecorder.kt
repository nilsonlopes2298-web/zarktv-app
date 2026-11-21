package io.github.axon07.channelbox.data

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Records a live DASH channel – including DRM-protected ones – **without decrypting anything**.
 *
 * The init and media segments are saved exactly as the server sends them (still encrypted), and a
 * static `manifest.mpd` describing them is written next to them, carrying the channel's
 * ContentProtection info. Playing a recording goes through the same DRM step as live TV: the
 * playlist's ClearKey key or the channel's licence server. If the licence server won't issue a
 * licence, the recording doesn't play.
 *
 * Layout: `<folder>/manifest.mpd`, `<folder>/v/…`, `<folder>/a1/…`
 */
internal class DashRecorder(
    private val folder: File,
    private val manifestUrl: String,
    /** Fetches a URL with the channel's headers; the second argument is an HTTP Range value or null. */
    private val fetch: suspend (url: String, range: String?) -> ByteArray,
    private val onProgress: suspend (bytes: Long, manifestPath: String) -> Unit,
) {
    private class Seg(val file: String, val tUs: Long, val dUs: Long)

    private class Track(
        val key: String,
        val type: Int,
        val repId: String,
        val format: Format,
        val ptoUs: Long,
        val init: String?,
    ) {
        val segs = ArrayList<Seg>()
        val end get() = segs.lastOrNull()?.let { it.tUs - ptoUs + it.dUs } ?: 0L
    }

    private class OutPeriod(val index: Int, val sourceKey: String, val startUs: Long) {
        val tracks = LinkedHashMap<String, Track>()
        fun endUs() = startUs + (tracks.values.maxOfOrNull { it.end } ?: 0L)
    }

    private class Candidate(val num: Long, val absUs: Long, val durUs: Long)

    private val periods = ArrayList<OutPeriod>()
    /** Absolute start (µs since epoch) of the recording; null until the first poll. */
    private var originUs: Long? = null
    /** Per track key: absolute start of the newest saved segment. */
    private val lastAbs = HashMap<String, Long>()
    private var bytes = 0L
    private var counter = 0
    private val manifestFile = File(folder, MANIFEST)

    val bytesWritten get() = bytes

    suspend fun run(until: () -> Boolean) {
        folder.mkdirs()
        var failures = 0
        while (until()) {
            currentCoroutineContext().ensureActive()
            var wait = 2_000L
            try {
                val manifest = DashManifestParser().parse(Uri.parse(manifestUrl), fetch(manifestUrl, null).inputStream())
                if (poll(manifest, until)) writeManifest()
                if (!manifest.dynamic) return // a finished (VOD) stream: everything is saved
                if (manifest.minUpdatePeriodMs > 0) wait = manifest.minUpdatePeriodMs.coerceIn(1_000L, 4_000L)
                failures = 0
            } catch (e: IOException) {
                if (++failures > 30) throw e
            }
            if (until()) delay(wait)
        }
    }

    /** Saves new segments. Returns true if any were added. */
    private suspend fun poll(manifest: DashManifest, until: () -> Boolean): Boolean {
        val nowUs = System.currentTimeMillis() * 1000
        val astUs = if (manifest.availabilityStartTimeMs == C.TIME_UNSET) 0L else manifest.availabilityStartTimeMs * 1000
        var added = false
        for (pi in 0 until manifest.periodCount) {
            val period = manifest.getPeriod(pi)
            val periodDurUs = manifest.getPeriodDurationUs(pi)
            val periodStartAbs = astUs + period.startMs * 1000
            val sourceKey = period.id ?: period.startMs.toString()

            // What to keep: the best video, and every audio language (up to 4).
            val picked = LinkedHashMap<String, Representation>()
            period.adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { it.representations }
                .maxWithOrNull(compareBy<Representation>({ it.format.height }, { it.format.bitrate }))
                ?.let { picked["v"] = it }
            period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }.take(4).forEachIndexed { i, set ->
                set.representations.maxByOrNull { it.format.bitrate }?.let { picked["a${i + 1}"] = it }
            }
            if (picked.isEmpty()) continue

            // Segments that are available now.
            val available = picked.mapValues { (_, rep) ->
                val index = rep.index ?: return@mapValues emptyList<Candidate>()
                val first = index.getFirstAvailableSegmentNum(periodDurUs, nowUs)
                val count = index.getAvailableSegmentCount(periodDurUs, nowUs)
                if (count == C.LENGTH_UNSET.toLong() || count <= 0) return@mapValues emptyList<Candidate>()
                // Without an explicit timeline the newest segment may not be ready yet.
                val last = first + count - 1 - if (index.isExplicit || !manifest.dynamic) 0 else 1
                (first..last).map { n ->
                    Candidate(n, periodStartAbs + index.getTimeUs(n), index.getDurationUs(n, periodDurUs))
                }
            }

            if (originUs == null) {
                // Start at the live edge: the newest video segment (or audio, for radio).
                val edgeTrack = available["v"]?.takeIf { it.isNotEmpty() } ?: available.values.firstOrNull { it.isNotEmpty() }
                    ?: continue
                val edge = if (manifest.dynamic) edgeTrack.last().absUs else edgeTrack.first().absUs
                // Remember everything older as already seen.
                available.forEach { (key, list) ->
                    list.lastOrNull { it.absUs + it.durUs <= edge + 100_000 }?.let { lastAbs[key] = it.absUs }
                }
                originUs = available.values.flatten().filter { it.absUs + it.durUs > edge + 100_000 }
                    .minOfOrNull { it.absUs } ?: edge
            }
            val origin = originUs!!

            val fresh = available.mapValues { (key, list) ->
                val after = lastAbs[key]
                list.filter { (after == null || it.absUs > after + 1_000) && it.absUs + it.durUs > origin + 100_000 }
            }
            val firstFresh = fresh.values.flatten().minOfOrNull { it.absUs } ?: continue

            // A new period (ad break, programme change) or a different stream quality: start a new output period.
            var out = periods.lastOrNull()
            val changed = out == null || out.sourceKey != sourceKey ||
                picked.any { (key, rep) -> out!!.tracks[key]?.let { it.repId != rep.format.id } ?: false }
            if (changed) {
                val start = maxOf(firstFresh - origin, out?.endUs() ?: 0L, 0L)
                out = OutPeriod(periods.size, sourceKey, start)
                periods += out
            }

            for ((key, list) in fresh) {
                if (list.isEmpty()) continue
                val rep = picked.getValue(key)
                val track = out!!.tracks[key] ?: newTrack(out, key, rep, periodStartAbs, origin) ?: continue
                for (c in list) {
                    currentCoroutineContext().ensureActive()
                    if (!until()) return added
                    val uri = rep.index?.getSegmentUrl(c.num) ?: continue
                    val name = "$key/%06d.m4s".format(Locale.US, ++counter)
                    try {
                        val data = fetch(uri.resolveUriString(rep.baseUrls.first().url), uri.range())
                        File(folder, name).writeBytes(data)
                        bytes += data.size
                    } catch (e: IOException) {
                        continue // skip the odd broken segment
                    } finally {
                        lastAbs[key] = c.absUs
                    }
                    track.segs += Seg(name, c.absUs - periodStartAbs + rep.presentationTimeOffsetUs, c.durUs)
                    added = true
                }
            }
        }
        return added
    }

    private suspend fun newTrack(out: OutPeriod, key: String, rep: Representation, periodStartAbs: Long, origin: Long): Track? {
        File(folder, key).mkdirs()
        val initUri = rep.initializationUri
        val init = initUri?.let {
            val name = "$key/init_${out.index}.mp4"
            val data = fetch(it.resolveUriString(rep.baseUrls.first().url), it.range())
            File(folder, name).writeBytes(data)
            bytes += data.size
            name
        }
        // Keeps the segments' own timestamps lined up with their place in the recording.
        val pto = rep.presentationTimeOffsetUs - periodStartAbs + origin + out.startUs
        return Track(key, rep.format.let { if (key == "v") C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO }, rep.format.id ?: key, rep.format, pto, init)
            .also { out.tracks[key] = it }
    }

    private fun androidx.media3.exoplayer.dash.manifest.RangedUri.range(): String? =
        if (start == 0L && length == C.LENGTH_UNSET.toLong()) null
        else if (length == C.LENGTH_UNSET.toLong()) "bytes=$start-"
        else "bytes=$start-${start + length - 1}"

    /** Rewrites manifest.mpd (atomically) so the recording can be played even while it's still going. */
    suspend fun writeManifest() {
        val live = periods.filter { p -> p.tracks.values.any { it.segs.isNotEmpty() } }
        if (live.isEmpty()) return
        val total = live.last().endUs()
        val xml = StringBuilder()
        xml.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        xml.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static" """)
        xml.append("""profiles="urn:mpeg:dash:profile:isoff-live:2011" minBufferTime="PT2S" mediaPresentationDuration="${dur(total)}">""").append('\n')
        live.forEachIndexed { i, p ->
            val next = live.getOrNull(i + 1)
            val length = (next?.startUs ?: p.endUs()) - p.startUs
            xml.append("""<Period id="p${p.index}" start="${dur(p.startUs)}" duration="${dur(length)}">""").append('\n')
            for (t in p.tracks.values) {
                if (t.segs.isEmpty()) continue
                val f = t.format
                val video = t.type == C.TRACK_TYPE_VIDEO
                xml.append("""<AdaptationSet contentType="${if (video) "video" else "audio"}" mimeType="${esc(f.containerMimeType ?: if (video) "video/mp4" else "audio/mp4")}"""")
                f.language?.takeIf { it != "und" }?.let { xml.append(""" lang="${esc(it)}"""") }
                xml.append(">\n")
                protection(f)?.let { xml.append(it) }
                xml.append("""<Representation id="${esc(t.key)}" bandwidth="${f.bitrate.coerceAtLeast(1)}"""")
                f.codecs?.let { xml.append(""" codecs="${esc(it)}"""") }
                if (video && f.width > 0) xml.append(""" width="${f.width}" height="${f.height}"""")
                if (video && f.frameRate > 0) xml.append(""" frameRate="${"%.3f".format(Locale.US, f.frameRate)}"""")
                if (!video && f.sampleRate > 0) xml.append(""" audioSamplingRate="${f.sampleRate}"""")
                xml.append(">\n")
                if (!video && f.channelCount > 0) {
                    xml.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${f.channelCount}"/>""").append('\n')
                }
                xml.append("""<SegmentList timescale="1000000" presentationTimeOffset="${t.ptoUs.coerceAtLeast(0)}">""").append('\n')
                t.init?.let { xml.append("""<Initialization sourceURL="$it"/>""").append('\n') }
                xml.append("<SegmentTimeline>\n")
                t.segs.forEach { s -> xml.append("""<S t="${s.tUs}" d="${s.dUs}"/>""").append('\n') }
                xml.append("</SegmentTimeline>\n")
                t.segs.forEach { s -> xml.append("""<SegmentURL media="${s.file}"/>""").append('\n') }
                xml.append("</SegmentList>\n</Representation>\n</AdaptationSet>\n")
            }
            xml.append("</Period>\n")
        }
        xml.append("</MPD>\n")
        val tmp = File(folder, "$MANIFEST.tmp")
        tmp.writeText(xml.toString())
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.delete()
            tmp.renameTo(manifestFile)
        }
        onProgress(bytes, manifestFile.absolutePath)
    }

    /** The channel's ContentProtection, copied as-is so playback asks for a licence the same way. */
    private fun protection(f: Format): String? {
        val drm = f.drmInitData ?: return null
        val sb = StringBuilder()
        sb.append("""<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="${esc(drm.schemeType ?: "cenc")}"/>""").append('\n')
        for (i in 0 until drm.schemeDataCount) {
            val d = drm.get(i)
            sb.append("""<ContentProtection schemeIdUri="urn:uuid:${d.uuid.toString().uppercase(Locale.US)}">""")
            d.data?.let { sb.append("<cenc:pssh>").append(Base64.encodeToString(it, Base64.NO_WRAP)).append("</cenc:pssh>") }
            sb.append("</ContentProtection>\n")
        }
        return sb.toString()
    }

    private fun dur(us: Long) = "PT%.3fS".format(Locale.US, us.coerceAtLeast(0) / 1_000_000.0)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val MANIFEST = "manifest.mpd"

        /** Total size of a DASH recording's folder. */
        fun sizeOf(manifest: File): Long =
            manifest.parentFile?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }
}
