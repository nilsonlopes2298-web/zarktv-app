package io.github.axon07.channelbox.data

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage

/**
 * ZarkTV: Chromecast / Google TV pelo Google Cast (como o YouTube). Só funciona em aparelho com
 * os serviços do Google; sem eles, a lista do Chromecast fica vazia e sobram DLNA e espelhar.
 */
object Chromecast {
    val seletor: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }

    fun contexto(ctx: Context): CastContext? = runCatching { CastContext.getSharedInstance(ctx.applicationContext) }.getOrNull()

    /** Conecta no aparelho escolhido e manda o vídeo. [aoTerminar] diz se deu certo. */
    fun tocar(ctx: Context, rota: MediaRouter.RouteInfo, url: String, titulo: String, capa: String?, aoVivo: Boolean, aoTerminar: (Boolean) -> Unit) {
        val cast = contexto(ctx) ?: return aoTerminar(false)
        val sm = cast.sessionManager
        val ouvinte = object : SessionManagerListener<CastSession> {
            fun carregar(s: CastSession) {
                val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, titulo)
                    if (capa != null) addImage(WebImage(android.net.Uri.parse(capa)))
                }
                val tipo = when {
                    url.contains(".m3u8", true) -> "application/x-mpegurl"
                    url.contains(".mkv", true) -> "video/x-matroska"
                    url.contains(".ts", true) -> "video/mp2t"
                    else -> "video/mp4"
                }
                val info = MediaInfo.Builder(url)
                    .setStreamType(if (aoVivo) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(tipo).setMetadata(meta).build()
                val cliente = s.remoteMediaClient
                if (cliente == null) { aoTerminar(false); return }
                cliente.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                    .setResultCallback { r -> aoTerminar(r.status.isSuccess) }
                sm.removeSessionManagerListener(this, CastSession::class.java)
            }
            override fun onSessionStarted(s: CastSession, id: String) = carregar(s)
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = carregar(s)
            override fun onSessionStartFailed(s: CastSession, e: Int) { sm.removeSessionManagerListener(this, CastSession::class.java); aoTerminar(false) }
            override fun onSessionResumeFailed(s: CastSession, e: Int) = onSessionStartFailed(s, e)
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, e: Int) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionSuspended(s: CastSession, r: Int) {}
        }
        val atual = sm.currentCastSession
        if (atual != null && atual.isConnected && atual.castDevice?.deviceId == com.google.android.gms.cast.CastDevice.getFromBundle(rota.extras)?.deviceId) {
            ouvinte.carregar(atual); return
        }
        sm.addSessionManagerListener(ouvinte, CastSession::class.java)
        MediaRouter.getInstance(ctx).selectRoute(rota)
    }
}
