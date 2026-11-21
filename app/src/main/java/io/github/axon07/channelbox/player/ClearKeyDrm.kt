package io.github.axon07.channelbox.player

import android.os.Looper
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.extractor.mp4.PsshAtomUtil
import io.github.axon07.channelbox.data.DrmSpec
import io.github.axon07.channelbox.data.StreamSpecs
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Answers ClearKey license requests from the keys in the playlist, giving the stream the key
 * for exactly the key id it asks for (or the only key we have, when the ids don't line up).
 */
@OptIn(UnstableApi::class)
class ClearKeyCallback(private val keys: Map<String, String>) : MediaDrmCallback {
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        throw UnsupportedOperationException("ClearKey needs no provisioning")

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray =
        StreamSpecs.clearKeyResponse(String(request.data, Charsets.UTF_8), keys).toByteArray(Charsets.UTF_8)
}

/**
 * ClearKey for streams whose manifest only describes PlayReady/Widevine (no ClearKey or
 * "cenc" key-id entry). Like Kodi's inputstream.adaptive, it adds the missing ClearKey init
 * data built from the key ids in the playlist – or, failing that, from the Widevine PSSH.
 */
@OptIn(UnstableApi::class)
class ClearKeyDrmSessionManager(private val keys: Map<String, String>) : DrmSessionManager {

    private val delegate: DefaultDrmSessionManager = DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
        .setMultiSession(true)
        .setPlayClearSamplesWithoutKeys(true)
        .build(ClearKeyCallback(keys))

    override fun prepare() = delegate.prepare()
    override fun release() = delegate.release()
    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) = delegate.setPlayer(playbackLooper, playerId)
    override fun getCryptoType(format: Format): Int = delegate.getCryptoType(withClearKeyData(format))

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession? = delegate.acquireSession(eventDispatcher, withClearKeyData(format))

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSessionManager.DrmSessionReference = delegate.preacquireSession(eventDispatcher, withClearKeyData(format))

    private fun withClearKeyData(format: Format): Format {
        val init = format.drmInitData ?: return format
        val schemes = (0 until init.schemeDataCount).map { init.get(it) }
        val usable = schemes.any { s ->
            s.data != null && (s.uuid == C.CLEARKEY_UUID || s.uuid == C.COMMON_PSSH_UUID)
        }
        if (usable) return format

        val keyIds = playlistKeyIds().ifEmpty { schemes.flatMap { widevineKeyIds(it) }.distinct() }
        if (keyIds.isEmpty()) return format
        val pssh = PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, keyIds.toTypedArray(), null)
        val added = DrmInitData.SchemeData(C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, pssh)
        return format.buildUpon()
            .setDrmInitData(DrmInitData(init.schemeType, *(schemes + added).toTypedArray()))
            .build()
    }

    private fun playlistKeyIds(): List<UUID> = keys.keys.filter { it != DrmSpec.ANY_KID }.mapNotNull { b64 ->
        runCatching {
            val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (bytes.size != 16) null else ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
        }.getOrNull()
    }

    /** Key ids from a Widevine PSSH (protobuf field 2: 0x12, length 0x10, 16 bytes). */
    private fun widevineKeyIds(scheme: DrmInitData.SchemeData): List<UUID> {
        if (scheme.uuid != C.WIDEVINE_UUID) return emptyList()
        val atom = scheme.data ?: return emptyList()
        val data = PsshAtomUtil.parseSchemeSpecificData(atom, C.WIDEVINE_UUID) ?: atom
        val out = ArrayList<UUID>()
        var i = 0
        while (i + 18 <= data.size) {
            if (data[i] == 0x12.toByte() && data[i + 1] == 0x10.toByte()) {
                val b = ByteBuffer.wrap(data, i + 2, 16)
                out += UUID(b.long, b.long)
                i += 18
            } else {
                i++
            }
        }
        return out
    }
}
