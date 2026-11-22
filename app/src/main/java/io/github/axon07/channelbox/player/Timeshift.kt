package io.github.axon07.channelbox.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import io.github.axon07.channelbox.data.Recorder
import io.github.axon07.channelbox.data.StreamSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Pause live TV. When you pause, the channel is captured from that moment into a file on this
 * box; when you resume, the player plays that file while it keeps growing, so you carry on where
 * you left off, behind live. "Voltar ao vivo" or changing channel throws the buffer away.
 */
class Timeshift(private val dir: File, private val recorder: Recorder) {
    private var job: Job? = null

    /** The buffer file, once the stream has started arriving. */
    @Volatile var file: File? = null
        private set

    /**
     * True when [file] is a local HLS playlist (channels whose sound is a separate stream):
     * the video and audio segments are saved side by side instead of into one file.
     */
    @Volatile var isPlaylist = false
        private set

    /** True while the stream is still being written to [file]. */
    @Volatile var writing = false
        private set

    /** Wall-clock time the pause started (the start of the buffer). */
    var startedAt = 0L
        private set

    val active: Boolean get() = job != null

    /** Why the buffer stopped early (e.g. the provider refused a connection), if it did. */
    @Volatile var lastError: String? = null
        private set

    fun canBuffer(spec: StreamSpec) = recorder.canCapture(spec)

    fun start(portalOrStream: StreamSpec, scope: CoroutineScope) {
        stop()
        dir.mkdirs()
        dir.listFiles()?.forEach { it.deleteRecursively() }
        startedAt = System.currentTimeMillis()
        writing = true
        lastError = null
        isPlaylist = false
        job = scope.launch(Dispatchers.IO) {
            // Portal (Stalker) channels: a fresh link from the portal for the buffer's own connection.
            val spec = try {
                io.github.axon07.channelbox.data.StreamResolver.resolve(portalOrStream)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                writing = false
                return@launch
            }
            // Separate audio: buffer both playlists, or the paused picture would have no sound.
            val split = runCatching { recorder.splitAudio(spec) }.getOrNull()
            if (split != null) {
                val folder = File(dir, "hls")
                try {
                    val hls = recorder.folderRecorder(folder, spec, split) { _, index ->
                        if (file == null) {
                            isPlaylist = true
                            file = File(index)
                        }
                    }
                    hls.run {
                        System.currentTimeMillis() - startedAt < MAX_MS && hls.bytesWritten < MAX_BYTES &&
                            dir.usableSpace > MIN_FREE_BYTES
                    }
                    runCatching { hls.writePlaylists(done = true) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    writing = false
                }
                return@launch
            }
            var out: OutputStream? = null
            var size = 0L
            val target = object : Recorder.CaptureTarget {
                override suspend fun open(extension: String) {
                    val f = File(dir, "buffer.$extension")
                    out = FileOutputStream(f)
                    file = f
                }

                override suspend fun write(bytes: ByteArray, length: Int) {
                    out?.let {
                        it.write(bytes, 0, length)
                        it.flush()
                    }
                    size += length
                }
            }
            try {
                recorder.capture(
                    spec, target, fromLiveEdge = true,
                    until = {
                        System.currentTimeMillis() - startedAt < MAX_MS && size < MAX_BYTES &&
                            dir.usableSpace > MIN_FREE_BYTES
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stream stopped: what's buffered can still be watched.
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                writing = false
                runCatching { out?.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        writing = false
        file = null
        isPlaylist = false
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Enough has been buffered to start playing it. */
    fun hasEnough(): Boolean {
        val f = file ?: return false
        return if (isPlaylist) f.exists() else f.length() >= 64 * 1024
    }

    companion object {
        /** Longest pause that's kept: 2 hours. */
        const val MAX_MS = 2 * 3_600_000L
        const val MAX_BYTES = 6_000_000_000L
        const val MIN_FREE_BYTES = 500_000_000L
    }
}

/**
 * Reads a file that's still being written: at the end it waits for more data instead of stopping,
 * until [growing] says the writer has finished.
 */
@UnstableApi
class GrowingFileDataSource(private val file: File, private val growing: () -> Boolean) : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        raf = RandomAccessFile(file, "r").also { it.seek(dataSpec.position) }
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = raf ?: return C.RESULT_END_OF_INPUT
        var waited = 0L
        while (true) {
            val n = r.read(buffer, offset, length)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (!growing()) return C.RESULT_END_OF_INPUT
            if (waited > 30_000) throw IOException("The pause buffer stopped growing")
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
            waited += 40
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { raf?.close() }
        raf = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }
}
