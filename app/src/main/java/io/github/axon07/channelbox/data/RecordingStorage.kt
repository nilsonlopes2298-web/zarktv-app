package io.github.axon07.channelbox.data

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.security.bc.BCSecurityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** A place a recording is being written to. */
interface RecordingSink {
    /** Where the file lives (local path, smb://… or https://…). */
    val location: String
    val output: OutputStream

    /** Closes the file; for WebDAV this uploads it. Returns the final location. */
    suspend fun finish(): String
}

/**
 * Where recordings go: this box, a USB drive / SD card, an SMB share (Windows / NAS / Samba)
 * or a WebDAV folder (Nextcloud, Synology, QNAP…).
 */
class RecordingStorage(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
) {

    fun localDirectory(): File =
        (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).also { it.mkdirs() }

    /** USB drives / SD cards Android has mounted (app folders on them need no permission). */
    fun removableDirectories(): List<File> =
        context.getExternalFilesDirs("recordings").drop(1).filterNotNull().onEach { it.mkdirs() }

    suspend fun describe(st: StorageSettings): String = when (st.type) {
        "removable" -> removableDirectories().getOrNull(st.removableIndex)
            ?.let { "USB / SD card · ${humanSize(it.usableSpace)} free" } ?: "USB / SD card not found"
        "smb" -> "Network share · \\\\${st.smbHost}\\${st.smbShare}\\${st.smbPath}"
        "webdav" -> "WebDAV · ${st.davUrl}"
        else -> "This box · ${humanSize(localDirectory().usableSpace)} free"
    }

    suspend fun open(fileName: String): RecordingSink = withContext(Dispatchers.IO) {
        val st = settings.current().storage
        when (st.type) {
            "removable" -> {
                val dir = removableDirectories().getOrNull(st.removableIndex)
                    ?: throw IOException("The USB drive / SD card isn't connected")
                LocalSink(File(dir, fileName))
            }
            "smb" -> SmbSink(st, fileName)
            "webdav" -> WebDavSink(st, File(localDirectory(), fileName))
            else -> LocalSink(File(localDirectory(), fileName))
        }
    }

    /**
     * A folder for a multi-file (DASH) recording. These stay on this box or the USB drive / SD card:
     * with a network share chosen, they're kept on this box.
     */
    suspend fun folderFor(name: String): File = withContext(Dispatchers.IO) {
        val st = settings.current().storage
        val base = if (st.type == "removable") removableDirectories().getOrNull(st.removableIndex) ?: localDirectory()
        else localDirectory()
        File(base, name).also { it.mkdirs() }
    }

    suspend fun delete(location: String) = withContext(Dispatchers.IO) {
        val st = settings.current().storage
        when {
            location.startsWith("smb://") -> runCatching { Smb.connect(st).use { it.share.rm(Smb.pathOf(location)) } }
            location.startsWith("http") -> runCatching {
                http.newCall(
                    Request.Builder().url(location).delete()
                        .header("Authorization", Credentials.basic(st.davUser, st.davPass)).build()
                ).execute().close()
            }
            else -> runCatching {
                val file = File(location)
                // DASH and split-audio HLS recordings are folders: an index plus segments.
                if (file.name in Recorder.FOLDER_INDEXES) file.parentFile?.deleteRecursively() else file.delete()
            }
        }
        Unit
    }

    /** Tries the chosen location. Returns null if it works, otherwise what went wrong. */
    suspend fun test(st: StorageSettings): String? = withContext(Dispatchers.IO) {
        try {
            when (st.type) {
                "smb" -> Smb.connect(st).use { s -> Smb.ensureFolder(s.share, st.smbPath) }
                "webdav" -> {
                    val response = http.newCall(
                        Request.Builder().url(st.davUrl.trimEnd('/') + "/")
                            .method("PROPFIND", null)
                            .header("Depth", "0")
                            .header("Authorization", Credentials.basic(st.davUser, st.davPass))
                            .build()
                    ).execute()
                    response.close()
                    if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
                }
                "removable" -> if (removableDirectories().getOrNull(st.removableIndex) == null) {
                    throw IOException("No USB drive / SD card found")
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ---- Sinks -------------------------------------------------------------------------------------

    private class LocalSink(val file: File) : RecordingSink {
        override val location: String = file.path
        override val output: OutputStream = BufferedOutputStream(FileOutputStream(file, true), 256 * 1024)
        override suspend fun finish(): String {
            withContext(Dispatchers.IO) { runCatching { output.flush(); output.close() } }
            return location
        }
    }

    /** Writes straight to the share while recording. */
    private class SmbSink(st: StorageSettings, fileName: String) : RecordingSink {
        private val conn = Smb.connect(st)
        private val path = Smb.join(st.smbPath, fileName)
        private val file = run {
            Smb.ensureFolder(conn.share, st.smbPath)
            conn.share.openFile(
                path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
            )
        }
        override val location = "smb://${st.smbHost}/${st.smbShare}/${path.replace('\\', '/')}"
        override val output: OutputStream = BufferedOutputStream(file.outputStream, 512 * 1024)
        override suspend fun finish(): String {
            withContext(Dispatchers.IO) {
                runCatching { output.flush(); output.close() }
                runCatching { file.close() }
                conn.close()
            }
            return location
        }
    }

    /** Records to this box, then uploads to the WebDAV folder when the recording ends. */
    private inner class WebDavSink(st: StorageSettings, private val temp: File) : RecordingSink {
        private val url = st.davUrl.trimEnd('/') + "/" + temp.name
        private val auth = Credentials.basic(st.davUser, st.davPass)
        override val location: String = temp.path
        override val output: OutputStream = BufferedOutputStream(FileOutputStream(temp, true), 256 * 1024)
        override suspend fun finish(): String = withContext(Dispatchers.IO) {
            runCatching { output.flush(); output.close() }
            if (temp.length() == 0L) return@withContext location
            val upload = http.newBuilder().writeTimeout(0, TimeUnit.MILLISECONDS).readTimeout(5, TimeUnit.MINUTES).build()
            val response = upload.newCall(
                Request.Builder().url(url)
                    .put(temp.asRequestBody("video/mp2t".toMediaType()))
                    .header("Authorization", auth)
                    .build()
            ).execute()
            response.close()
            if (!response.isSuccessful) throw IOException("Upload to WebDAV failed (HTTP ${response.code}) – kept on this box")
            temp.delete()
            url
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        else -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1e6)
    }
}

/** Small helper around smbj (SMB 2/3 – Windows shares, NAS boxes, Samba). */
object Smb {
    class Conn(val client: SMBClient, val connection: Connection, val session: Session, val share: DiskShare) : Closeable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    fun connect(st: StorageSettings): Conn {
        if (st.smbHost.isBlank() || st.smbShare.isBlank()) throw IOException("Enter the server and share name")
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        val host = st.smbHost.substringBefore(':')
        val port = st.smbHost.substringAfter(':', "").toIntOrNull()
        val connection = if (port != null) client.connect(host, port) else client.connect(host)
        val auth = if (st.smbUser.isBlank()) AuthenticationContext.anonymous()
        else AuthenticationContext(st.smbUser, st.smbPass.toCharArray(), st.smbDomain.ifBlank { null })
        val session = connection.authenticate(auth)
        val share = session.connectShare(st.smbShare) as? DiskShare ?: throw IOException("${st.smbShare} isn't a folder share")
        return Conn(client, connection, session, share)
    }

    fun join(folder: String, name: String): String =
        listOf(folder.trim('/', '\\'), name).filter { it.isNotEmpty() }.joinToString("\\").replace('/', '\\')

    fun ensureFolder(share: DiskShare, folder: String) {
        var path = ""
        folder.split('/', '\\').filter { it.isNotBlank() }.forEach { part ->
            path = if (path.isEmpty()) part else "$path\\$part"
            if (!share.folderExists(path)) share.mkdir(path)
        }
    }

    /** "smb://host/share/a/b.ts" → "a\b.ts" */
    fun pathOf(location: String): String =
        location.removePrefix("smb://").split('/').drop(2).joinToString("\\")
}
