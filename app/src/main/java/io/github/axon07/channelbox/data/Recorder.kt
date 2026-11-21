package io.github.axon07.channelbox.data

import android.content.Context
import io.github.axon07.channelbox.system.RecordingWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Records live channels to files on the box.
 *
 * - MPEG-TS / plain streams: the bytes are saved as they arrive (reconnecting if the stream drops).
 * - HLS: the playlist is polled and each new segment is appended (AES-128 encrypted HLS is decrypted).
 * - DASH (including DRM-protected channels): the segments are saved exactly as they arrive – still
 *   encrypted – with a manifest next to them, and play back through the same DRM / licence step as
 *   live TV. Nothing is ever decrypted to disk. See [DashRecorder].
 * - Smooth Streaming can't be recorded yet.
 */
class Recorder(
    private val context: Context,
    private val db: AppDatabase,
    private val http: OkHttpClient,
    val storage: RecordingStorage,
) {
    private val dao = db.recordings()

    val recordings: Flow<List<RecordingEntity>> = dao.observeAll()

    fun directory(): File = storage.localDirectory()

    suspend fun schedule(channel: ChannelEntity, title: String, start: Long, stop: Long, programStart: Long?): Long {
        val id = dao.insert(
            RecordingEntity(
                channelId = channel.id,
                channelName = channel.name,
                title = title,
                streamUrl = channel.streamUrl,
                headers = channel.headers,
                drmType = channel.drmType,
                drmKey = channel.drmKey,
                mimeHint = channel.mimeHint,
                start = start,
                stop = stop,
                programStart = programStart,
            )
        )
        RecordingWorker.enqueue(context, id, (start - System.currentTimeMillis()).coerceAtLeast(0))
        return id
    }

    /** Stops a running recording (keeping what was recorded) or cancels a scheduled one. */
    suspend fun stop(recording: RecordingEntity) {
        RecordingWorker.cancel(context, recording.id)
        val r = dao.get(recording.id) ?: return
        if (r.status == RecStatus.SCHEDULED) dao.update(r.copy(status = RecStatus.CANCELLED))
    }

    suspend fun delete(recording: RecordingEntity) {
        RecordingWorker.cancel(context, recording.id)
        dao.get(recording.id)?.filePath?.let { storage.delete(it) }
        dao.delete(recording.id)
    }

    /** Recordings left "recording" by a crash or reboot get closed off. */
    suspend fun tidyUp() {
        val now = System.currentTimeMillis()
        dao.getAll().filter { it.status == RecStatus.RECORDING && it.stop < now }.forEach {
            val size = it.filePath?.takeIf { p -> p.startsWith("/") }?.let { p ->
                if (File(p).name in FOLDER_INDEXES) DashRecorder.sizeOf(File(p)) else File(p).length()
            } ?: it.sizeBytes
            dao.update(it.copy(status = if (size > 0) RecStatus.DONE else RecStatus.FAILED, sizeBytes = size))
        }
    }

    // ---- The actual recording, run by RecordingWorker -------------------------------------------

    suspend fun record(id: Long) = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext
        if (rec.status != RecStatus.SCHEDULED && rec.status != RecStatus.RECORDING) return@withContext
        if (System.currentTimeMillis() >= rec.stop) {
            dao.update(rec.copy(status = RecStatus.FAILED, error = "Missed – the box was off or asleep"))
            return@withContext
        }
        // Portal (Stalker) channels: ask the portal for a link now.
        val spec = try {
            StreamResolver.resolve(StreamSpecs.from(rec))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.update(rec.copy(status = RecStatus.FAILED, error = e.message ?: "Couldn't get a link from the portal"))
            return@withContext
        }
        if (spec.mime == StreamSpecs.MIME_SS) {
            dao.update(rec.copy(status = RecStatus.FAILED, error = "Smooth Streaming channels can't be recorded yet"))
            return@withContext
        }
        if (spec.mime == StreamSpecs.MIME_DASH) {
            recordDash(rec, spec)
            return@withContext
        }
        // HLS whose audio is a separate playlist: save both, or the recording has no sound.
        splitAudio(spec)?.let { s ->
            recordHlsFolder(rec, spec, s.variant, s.audioUrl, s.audioAttrs)
            return@withContext
        }

        val session = Session(rec.copy(status = RecStatus.RECORDING))
        dao.update(session.rec)
        try {
            capture(spec, session, until = { System.currentTimeMillis() < session.rec.stop }, fromLiveEdge = false)
            session.close(null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { session.close(null) } // stopped by the user: keep what we have
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { session.close(e.message ?: e.javaClass.simpleName) }
        }
    }

    /** DASH, protected or not: segments saved as delivered (never decrypted) plus a local manifest. */
    private suspend fun recordDash(start: RecordingEntity, spec: StreamSpec) {
        var rec = start.copy(status = RecStatus.RECORDING)
        dao.update(rec)
        val folder = storage.folderFor(fileName(rec))
        var lastUpdate = 0L
        val dash = DashRecorder(
            folder = folder,
            manifestUrl = spec.url,
            fetch = { url, range -> fetchBytes(spec, url, range) },
            onProgress = { bytes, path ->
                val now = System.currentTimeMillis()
                if (rec.filePath == null || now - lastUpdate > 10_000) {
                    lastUpdate = now
                    rec = rec.copy(filePath = path, sizeBytes = bytes)
                    dao.update(rec)
                }
            },
        )
        var problem: String? = null
        try {
            dash.run { System.currentTimeMillis() < rec.stop }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finishDash(rec, dash, null) } // stopped by the user: keep what we have
            throw e
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        }
        withContext(NonCancellable) { finishDash(rec, dash, problem) }
    }

    private suspend fun finishDash(current: RecordingEntity, dash: DashRecorder, error: String?) {
        val rec = dao.get(current.id) ?: current
        runCatching { dash.writeManifest() }
        val path = File(storage.folderFor(fileName(rec)), DashRecorder.MANIFEST)
        val saved = path.exists() && dash.bytesWritten > 0
        dao.update(
            rec.copy(
                status = when {
                    saved -> RecStatus.DONE
                    error != null -> RecStatus.FAILED
                    else -> RecStatus.CANCELLED
                },
                filePath = if (path.exists()) path.absolutePath else rec.filePath,
                sizeBytes = dash.bytesWritten,
                error = error,
            )
        )
    }

    private suspend fun recordHlsFolder(
        start: RecordingEntity,
        spec: StreamSpec,
        variant: Hls.Variant,
        audioUrl: String,
        audioAttrs: Map<String, String>,
    ) {
        var rec = start.copy(status = RecStatus.RECORDING)
        dao.update(rec)
        val folder = storage.folderFor(fileName(rec))
        var lastUpdate = 0L
        val hls = HlsFolderRecorder(
            folder = folder,
            variant = variant,
            audioUrl = audioUrl,
            audioAttrs = audioAttrs,
            keepEncrypted = spec.drm != null,
            fetch = { url -> fetchBytes(spec, url) },
            decrypt = { data, key, iv, seq -> decrypt(data, key, iv, seq) },
            onProgress = { bytes, path ->
                val now = System.currentTimeMillis()
                if (rec.filePath == null || now - lastUpdate > 10_000) {
                    lastUpdate = now
                    rec = rec.copy(filePath = path, sizeBytes = bytes)
                    dao.update(rec)
                }
            },
        )
        var problem: String? = null
        try {
            hls.run { System.currentTimeMillis() < rec.stop }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finishFolder(rec, File(folder, HlsFolderRecorder.INDEX), hls.bytesWritten, null) { hls.writePlaylists(done = true) } }
            throw e
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        }
        withContext(NonCancellable) { finishFolder(rec, File(folder, HlsFolderRecorder.INDEX), hls.bytesWritten, problem) { hls.writePlaylists(done = true) } }
    }

    private suspend fun finishFolder(current: RecordingEntity, index: File, bytes: Long, error: String?, close: () -> Unit) {
        val rec = dao.get(current.id) ?: current
        runCatching { close() }
        val saved = index.exists() && bytes > 0
        dao.update(
            rec.copy(
                status = when {
                    saved -> RecStatus.DONE
                    error != null -> RecStatus.FAILED
                    else -> RecStatus.CANCELLED
                },
                filePath = if (index.exists()) index.absolutePath else rec.filePath,
                sizeBytes = bytes,
                error = error,
            )
        )
    }

    /** An HLS channel whose sound comes from a separate audio playlist. */
    class SplitAudio(val variant: Hls.Variant, val audioUrl: String, val audioAttrs: Map<String, String>)

    /** Returns the video variant and audio playlist if [spec] is HLS with separate audio, else null. */
    suspend fun splitAudio(spec: StreamSpec): SplitAudio? {
        val hls = spec.mime == StreamSpecs.MIME_HLS || spec.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        if (!hls) return null
        val text = runCatching { fetchText(spec, spec.url) }.getOrNull() ?: return null
        if (!Hls.isMaster(text)) return null
        val variant = Hls.variants(text, spec.url).maxByOrNull { it.bandwidth } ?: return null
        val audio = Hls.audioRendition(text, spec.url, variant) ?: return null
        return SplitAudio(variant, audio.first, audio.second)
    }

    /** Saves video + audio playlists of [split] into [folder] (pause live TV uses this too). */
    internal fun folderRecorder(
        folder: File,
        spec: StreamSpec,
        split: SplitAudio,
        onProgress: suspend (bytes: Long, indexPath: String) -> Unit,
    ) = HlsFolderRecorder(
        folder = folder,
        variant = split.variant,
        audioUrl = split.audioUrl,
        audioAttrs = split.audioAttrs,
        keepEncrypted = spec.drm != null,
        fetch = { url -> fetchBytes(spec, url) },
        decrypt = { data, key, iv, seq -> decrypt(data, key, iv, seq) },
        onProgress = onProgress,
    )

    /** True if a channel with [spec] can be recorded (everything except Smooth Streaming). */
    fun canRecord(spec: StreamSpec): Boolean = spec.mime != StreamSpecs.MIME_SS

    /** Where captured stream bytes go: a recording file, or the pause-live-TV buffer. */
    interface CaptureTarget {
        /** Called once, with "ts" or "mp4", before the first [write]. */
        suspend fun open(extension: String)
        suspend fun write(bytes: ByteArray, length: Int = bytes.size)
    }

    /** True if [spec] can be captured (not DRM, not DASH / Smooth Streaming). */
    fun canCapture(spec: StreamSpec): Boolean =
        spec.drm == null && spec.mime != StreamSpecs.MIME_DASH && spec.mime != StreamSpecs.MIME_SS

    /**
     * Captures a live TS or HLS stream into [target] while [until] is true.
     * [fromLiveEdge]: for HLS, start at the newest segments instead of the whole playlist window.
     */
    suspend fun capture(spec: StreamSpec, target: CaptureTarget, until: () -> Boolean, fromLiveEdge: Boolean) {
        val playlist = if (spec.mime == StreamSpecs.MIME_HLS) fetchText(spec, spec.url) else probeHls(spec)
        if (playlist != null) recordHls(spec, playlist, target, until, fromLiveEdge)
        else recordProgressive(spec, target, until)
    }

    private inner class Session(var rec: RecordingEntity) : CaptureTarget {
        private var sink: RecordingSink? = null
        private var written = 0L
        private var lastUpdate = 0L

        /** Opens the file in the storage chosen in Settings (this box, USB, network share…). */
        override suspend fun open(extension: String) {
            val s = storage.open("${fileName(rec)}.$extension")
            sink = s
            rec = rec.copy(filePath = s.location)
            dao.update(rec)
        }

        override suspend fun write(bytes: ByteArray, length: Int) {
            val out = sink?.output ?: return
            withContext(Dispatchers.IO) { out.write(bytes, 0, length) }
            written += length
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 10_000) {
                lastUpdate = now
                rec = rec.copy(sizeBytes = written)
                dao.update(rec)
            }
        }

        suspend fun close(error: String?) {
            var problem = error
            val location = sink?.let { s ->
                try {
                    s.finish()
                } catch (e: Exception) {
                    problem = problem ?: e.message
                    s.location
                }
            }
            val status = when {
                written > 0 -> RecStatus.DONE
                problem != null -> RecStatus.FAILED
                else -> RecStatus.CANCELLED
            }
            rec = rec.copy(status = status, sizeBytes = written, error = problem, filePath = location ?: rec.filePath)
            dao.update(rec)
        }
    }

    private suspend fun recordProgressive(spec: StreamSpec, session: CaptureTarget, until: () -> Boolean) {
        session.open("ts")
        val buffer = ByteArray(64 * 1024)
        var failures = 0
        while (until()) {
            currentCoroutineContext().ensureActive()
            try {
                execute(spec, spec.url).use { response ->
                    if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
                    val input = response.body?.byteStream() ?: throw IOException("Empty response")
                    while (until()) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        session.write(buffer, n)
                        failures = 0
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > 20) throw e
            }
            if (until()) delay(2_000) // stream dropped: reconnect
        }
    }

    private suspend fun recordHls(
        spec: StreamSpec,
        firstText: String,
        session: CaptureTarget,
        until: () -> Boolean,
        fromLiveEdge: Boolean,
    ) {
        var playlistUrl = spec.url
        var text = firstText
        if (Hls.isMaster(text)) {
            playlistUrl = Hls.bestVariant(text, playlistUrl) ?: throw IOException("No playable stream in the playlist")
            text = fetchText(spec, playlistUrl)
        }
        var media = Hls.parseMedia(text, playlistUrl)
        session.open(if (media.mapUri != null) "mp4" else "ts")
        media.mapUri?.let { session.write(fetchBytes(spec, it)) }

        val seen = LinkedHashSet<String>()
        // Pausing live TV: skip what was already shown – keep only the newest segment or two.
        if (fromLiveEdge && !media.ended) media.segments.dropLast(2).forEach { seen += it.uri }
        val keys = HashMap<String, ByteArray>()
        var failures = 0
        while (true) {
            for (segment in media.segments) {
                currentCoroutineContext().ensureActive()
                if (!until()) return
                if (segment.uri in seen) continue
                seen += segment.uri
                if (seen.size > 3000) seen.remove(seen.first())
                try {
                    var data = fetchBytes(spec, segment.uri)
                    val key = segment.key
                    if (key != null && key.method != "NONE" && key.method != "AES-128" && spec.drm != null) {
                        // DRM (Widevine / PlayReady / ClearKey CENC): saved still encrypted, played back
                        // through the same licence step as live TV.
                    } else if (key != null && key.method != "NONE") {
                        if (key.method != "AES-128" || key.uri == null) {
                            throw IllegalStateException("Unsupported HLS encryption (${key.method})")
                        }
                        val keyBytes = keys.getOrPut(key.uri) { fetchBytes(spec, key.uri) }
                        data = decrypt(data, keyBytes, key.iv, segment.sequence)
                    }
                    session.write(data)
                    failures = 0
                } catch (e: IOException) {
                    if (++failures > 30) throw e // skip the odd broken segment
                }
            }
            if (media.ended || !until()) return
            delay((media.targetDuration * 500L).coerceIn(1_000L, 6_000L))
            try {
                media = Hls.parseMedia(fetchText(spec, playlistUrl), playlistUrl)
            } catch (e: IOException) {
                if (++failures > 30) throw e
                delay(2_000)
            }
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?, sequence: Long): ByteArray {
        val iv = ByteArray(16)
        val hex = ivHex?.removePrefix("0x")?.removePrefix("0X")
        if (hex != null && hex.length <= 32) {
            hex.padStart(32, '0').chunked(2).forEachIndexed { i, h -> iv[i] = h.toInt(16).toByte() }
        } else {
            for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Returns the playlist text if the URL serves HLS, or null for a plain/TS stream. */
    private suspend fun probeHls(spec: StreamSpec): String? = execute(spec, spec.url).use { response ->
        if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
        val body = response.body ?: return@use null
        val type = response.header("Content-Type").orEmpty().lowercase()
        val input = BufferedInputStream(body.byteStream())
        input.mark(16)
        val head = ByteArray(7)
        val n = input.read(head)
        input.reset()
        val looksHls = "mpegurl" in type || (n == 7 && String(head) == "#EXTM3U")
        if (looksHls) input.readBytes().decodeToString() else null
    }

    private suspend fun fetchText(spec: StreamSpec, url: String): String = fetchBytes(spec, url).decodeToString()

    private suspend fun fetchBytes(spec: StreamSpec, url: String, range: String? = null): ByteArray = execute(spec, url, range).use { response ->
        if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
        response.body?.bytes() ?: throw IOException("Empty response")
    }

    /** Runs a request with the channel's headers; cancelling the coroutine aborts it. */
    private suspend fun execute(spec: StreamSpec, url: String, range: String? = null): Response {
        val builder = Request.Builder().url(url)
            .header("User-Agent", spec.header("User-Agent") ?: UserAgent.current)
        spec.headers.forEach { (k, v) -> if (!k.equals("User-Agent", ignoreCase = true)) builder.header(k, v) }
        if (range != null) builder.header("Range", range)
        val call: Call = http.newCall(builder.build())
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return call.execute()
        } finally {
            handle.dispose()
        }
    }

    private fun fileName(rec: RecordingEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(rec.start))
        val label = "${rec.channelName} - ${rec.title}".replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(80).trim()
        return "${date}_${label}_${rec.id}"
    }

    companion object {
        /** File names that mark a recording saved as a folder (index + segments). */
        val FOLDER_INDEXES = setOf(DashRecorder.MANIFEST, HlsFolderRecorder.INDEX)
    }
}
