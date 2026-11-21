package io.github.axon07.channelbox.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File
import io.github.axon07.channelbox.data.Smb
import io.github.axon07.channelbox.data.StorageSettings
import java.io.IOException
import java.util.EnumSet

/** Plays recordings saved on an SMB share (smb://host/share/folder/file.ts). */
@OptIn(UnstableApi::class)
class SmbDataSource(private val storage: () -> StorageSettings) : BaseDataSource(true) {

    class Factory(private val storage: () -> StorageSettings) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(storage)
    }

    private var uri: Uri? = null
    private var conn: Smb.Conn? = null
    private var file: File? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val location = dataSpec.uri.toString()
        val st = storage().let { s ->
            // Use the saved login, but the host/share from the recording's own address.
            val parts = location.removePrefix("smb://").split('/')
            s.copy(smbHost = parts.getOrElse(0) { s.smbHost }, smbShare = parts.getOrElse(1) { s.smbShare })
        }
        try {
            val c = Smb.connect(st)
            conn = c
            val f = c.share.openFile(
                Smb.pathOf(location), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            )
            file = f
            val size = f.fileInformation.standardInformation.endOfFile
            position = dataSpec.position
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
            if (remaining < 0) throw IOException("Position beyond end of file")
        } catch (e: Exception) {
            close()
            throw IOException("Can't open recording on the network share: ${e.message}", e)
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), remaining).toInt()
        val n = try {
            f.read(buffer, position, offset, toRead)
        } catch (e: Exception) {
            throw IOException(e)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val wasOpen = file != null
        runCatching { file?.close() }
        file = null
        conn?.close()
        conn = null
        uri = null
        if (wasOpen) transferEnded()
    }
}
