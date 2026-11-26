package io.github.axon07.channelbox.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.video.VideoListener
import io.github.axon07.channelbox.data.StreamSpec
import io.github.axon07.channelbox.data.StreamSpecs
import okhttp3.OkHttpClient

/**
 * ZarkTV: motor clássico para os canais ao vivo — o ExoPlayer 2.13 (código aberto, Apache 2.0), o mesmo
 * motor que o XCIPTV usa. No Fire Stick 4K ele toca Full HD liso no decodificador do aparelho, onde o motor
 * novo (Media3) travava. Filmes e séries continuam no motor novo (áudio/legenda/continuar de onde parou).
 *
 * Só toca, para e avisa o estado; quem decide quando usar é o [TvPlayer].
 */
class MotorClassico(
    private val context: Context,
    private val http: OkHttpClient,
    /** Estado para a tela: true = tocando, false = carregando. */
    private val aoMudar: (tocando: Boolean) -> Unit,
    /** Não conseguiu tocar este canal (o [TvPlayer] tenta no motor novo). */
    private val aoFalhar: (motivo: String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tela: AspectRatioFrameLayout? = null
    private var superficie: SurfaceView? = null
    private var atual: StreamSpec? = null
    private var urlAtual: String? = null
    private var tentativas = 0
    private var jaTocou = false

    val player: SimpleExoPlayer by lazy {
        SimpleExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build().also { p ->
            p.addListener(object : Player.EventListener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> { jaTocou = true; tentativas = 0; aoMudar(true) }
                        Player.STATE_BUFFERING -> aoMudar(false)
                        Player.STATE_ENDED -> reabrir() // ao vivo não termina: o servidor fechou, abre de novo
                        else -> {}
                    }
                }

                override fun onPlayerError(error: ExoPlaybackException) = erro(error)
            })
            p.addVideoListener(object : VideoListener {
                override fun onVideoSizeChanged(width: Int, height: Int, unappliedRotationDegrees: Int, pixelWidthHeightRatio: Float) {
                    if (height > 0) tela?.setAspectRatio(width * pixelWidthHeightRatio / height)
                }
            })
        }
    }

    /** A tela onde o motor clássico desenha (SurfaceView, direto para a TV). */
    fun criarTela(ctx: Context): AspectRatioFrameLayout {
        val moldura = AspectRatioFrameLayout(ctx)
        val sv = SurfaceView(ctx).apply { setZOrderMediaOverlay(true) }
        moldura.addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        tela = moldura
        superficie = sv
        player.setVideoSurfaceView(sv)
        player.videoFormat?.let { f -> if (f.height > 0) moldura.setAspectRatio(f.width * f.pixelWidthHeightRatio / f.height) }
        return moldura
    }

    fun tocar(spec: StreamSpec) {
        handler.removeCallbacksAndMessages(null)
        atual = spec
        urlAtual = spec.url
        tentativas = 0
        jaTocou = false
        abrir(spec.url)
    }

    private fun abrir(url: String) {
        val spec = atual ?: return
        urlAtual = url
        val ua = spec.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: io.github.axon07.channelbox.data.UserAgent.current
        val fabrica = OkHttpDataSourceFactory(http, ua).also { f ->
            f.defaultRequestProperties.set(spec.headers.filterKeys { !it.equals("User-Agent", true) })
        }
        val item = MediaItem.fromUri(Uri.parse(url))
        val fonte: MediaSource = if (url.contains(".m3u8", true) || spec.mime == StreamSpecs.MIME_HLS) {
            HlsMediaSource.Factory(fabrica).setAllowChunklessPreparation(true).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(fabrica, DefaultExtractorsFactory()).createMediaSource(item)
        }
        aoMudar(false)
        player.setMediaSource(fonte)
        player.prepare()
        player.playWhenReady = true
    }

    private fun codigoHttp(e: Throwable?): Int? {
        var c = e
        while (c != null && c !is HttpDataSource.InvalidResponseCodeException) c = c.cause
        return (c as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    }

    private fun erro(e: ExoPlaybackException) {
        android.util.Log.w("ZarkClassico", "erro no motor clássico: ${e.message}", e)
        val url = urlAtual ?: return
        // Erro do decodificador / formato: este canal vai para o motor novo.
        if (e.type == ExoPlaybackException.TYPE_RENDERER) { aoFalhar("decodificador"); return }
        // Canal .ts que não abriu: tenta a versão .m3u8 do mesmo canal (como o motor novo faz).
        if (!jaTocou && tentativas == 0 && Regex("""\.ts(\?|$)""").containsMatchIn(url)) {
            tentativas++
            abrir(url.replace(Regex("""\.ts(\?|$)"""), ".m3u8$1"))
            return
        }
        tentativas++
        if (tentativas > 6) { aoFalhar("servidor (${codigoHttp(e) ?: e.type})"); return }
        // Servidor recusou / caiu / internet oscilou: espera um pouco e abre de novo, sem mostrar erro.
        aoMudar(false)
        handler.postDelayed({ reabrir() }, 1_500L * tentativas)
    }

    private fun reabrir() { urlAtual?.let { abrir(it) } }

    val tocando: Boolean get() = player.isPlaying

    fun pausar() { player.playWhenReady = false }
    fun continuar() { player.playWhenReady = true }

    fun parar() {
        handler.removeCallbacksAndMessages(null)
        atual = null
        urlAtual = null
        player.stop()
        player.clearMediaItems()
    }

    fun liberar() {
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
