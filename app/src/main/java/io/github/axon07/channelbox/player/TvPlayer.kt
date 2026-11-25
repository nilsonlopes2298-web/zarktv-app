package io.github.axon07.channelbox.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.github.axon07.channelbox.data.StorageSettings
import io.github.axon07.channelbox.data.StreamSpec
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.data.UserAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.UUID

sealed interface PlayerStatus {
    data object Idle : PlayerStatus
    data object Buffering : PlayerStatus
    data object Playing : PlayerStatus
    data class Error(val message: String) : PlayerStatus
}

/**
 * Engine options that need a new player (applied when Channelbox starts).
 * [buffer]: none, small, medium, large, xlarge.
 */
data class PlayerOptions(
    val softwareAudio: Boolean = false,
    val softwareVideo: Boolean = false,
    val buffer: String = "medium",
    val passthrough: Boolean = true,
    /** Dolby Vision: "auto" (only when the TV shows it), "on" or "off" (played as HDR10). */
    val dolbyVision: String = "auto",
)

/** What's playing right now, for the info banner. */
data class StreamInfo(val video: String?, val audio: String?, val bitrate: String?)

/** An audio, video or subtitle track the viewer can pick. [group] null = "Off" / "Auto". */
data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

/**
 * One ExoPlayer for the whole app.
 *
 * Live channels: every failure (bad link, unsupported format, DRM error) becomes an
 * on-screen message and an automatic retry – never a crash. When the stream type isn't
 * known it tries plain/TS, then HLS, then DASH. Widevine channels that fail are retried
 * once at security level L3, which fixes many cheap boxes.
 *
 * Catch-up programmes and recordings play "on demand": seekable, no auto-reconnect.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val http: OkHttpClient,
    private val options: PlayerOptions = PlayerOptions(),
    /**
     * Take Android's audio focus. Off for multi-view tiles: each tile asking for focus would pause
     * the others (only the newest tile kept playing).
     */
    private val handleAudioFocus: Boolean = true,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<PlayerStatus>(PlayerStatus.Idle)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    /**
     * ZarkTV: formato do vídeo quando o arquivo tem vídeo mas nenhum decodificador do aparelho
     * consegue abrir (aí só toca o som). null = está tudo certo.
     */
    val videoSemSuporte = MutableStateFlow<String?>(null)

    /** Bumped whenever the available/selected audio & subtitle tracks change. */
    private val _tracksVersion = MutableStateFlow(0)
    val tracksVersion: StateFlow<Int> = _tracksVersion.asStateFlow()

    private var spec: StreamSpec? = null
    /** Quantas alternativas (formato/identificação) já tentou neste canal. */
    private var tentativaAlternativa = 0
    private var primeiroErroVod: String? = null
    private var hostsVod: List<String>? = null
    private var urlVodOriginal: String? = null
    private var tentativaHost = 0
    /** Filme/episódio cujo endereço nem respondeu (sem conexão): não insiste nele, vai para outro caminho. */
    private var erroConexaoVod = false
    /** Cliente só do player: desiste de conectar em 8 s (o geral espera 15 s). */
    private val httpPlayer: OkHttpClient by lazy { http.newBuilder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build() }
    /** Outros endereços (DNS) do painel para o mesmo servidor, dado o endereço atual. */
    var hostsAlternativos: (String) -> List<String> = { emptyList() }
    private var avisouDesistencia = false
    /** Chamado quando um canal ao vivo esgota todas as tentativas. */
    var aoDesistir: (() -> Unit)? = null
    private var candidates: List<String?> = listOf(null)
    private var attempt = 0
    private var confirmed = false // the current type has played at least once
    /** O canal atual já mostrou imagem (travadas depois disso são só "carregando" no canto). */
    val jaTocou: Boolean get() = confirmed
    private var retries = 0
    private var widevineL3 = false
    private var onDemand = false

    /** False while a recording or catch-up programme is playing. */
    val isLive: Boolean get() = !onDemand

    // Assume a fast connection at the start, so streams don't open at their lowest quality.
    /** Velocidade de download estimada agora, em MB/s (para o "2MB/S" do carregamento). */
    fun velocidadeMBs(): Double = bandwidthMeter.bitrateEstimate / 8_000_000.0

    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setInitialBitrateEstimate(20_000_000L)
        .build()

    /** Login for playing recordings saved on an SMB share. */
    var storageSettings: () -> StorageSettings = { StorageSettings() }

    /** Settings → captions on by default. */
    var captionsByDefault: () -> Boolean = { false }

    /** Captions turned on / off with CC or the menu this session; carries over to the next channel. */
    private var captionsWanted: Boolean? = null

    /**
     * Captions start off unless asked for. Without this the player picks up the box's accessibility
     * caption setting and streams that flag a subtitle track as default, so CC came on by itself.
     */
    private fun applyCaptionDefault() {
        val on = captionsWanted ?: captionsByDefault()
        val b = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
        if (on) {
            b.setPreferredTextLanguage(java.util.Locale.getDefault().language)
                .setSelectUndeterminedTextLanguage(true)
        }
        exo.trackSelectionParameters = b.build()
    }

    /**
     * Whether Dolby Vision video goes to the box's Dolby Vision decoder. When it doesn't, Media3
     * falls back to the HEVC / AVC base layer, so most Dolby Vision streams still play as HDR10
     * (or SDR) instead of with the purple / green picture a DV decoder gives on a non-DV TV.
     */
    private val dolbyVisionAllowed: Boolean = when (options.dolbyVision) {
        "on" -> true
        "off" -> false
        else -> io.github.axon07.channelbox.system.Hdr.displaySupportsDolbyVision(context)
    }

    // FFmpeg (bundled) decodes AC3 / E-AC3 / DTS / TrueHD audio when the box can't.
    private val renderers = object : DefaultRenderersFactory(context) {
        // Fire Stick / Fire TV: ao trocar a superfície do vídeo (janelinha ↔ tela cheia) o decodificador
        // congelava a imagem e só ficava o som. Liga a correção do ExoPlayer para esses aparelhos.
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: android.os.Handler,
            eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
        ) {
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            if (!android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return
            val i = out.indexOfFirst { it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer }
            if (i < 0) return
            out[i] = object : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(
                context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ) {
                override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
            }
        }

        // Passthrough off: decode everything to PCM, as if the TV had no Dolby / DTS receiver.
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink? {
            if (options.passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            @Suppress("DEPRECATION")
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .build()
        }
    }
        .setExtensionRendererMode(
            if (options.softwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector { mime, secure, tunneling ->
            if (mime == MimeTypes.VIDEO_DOLBY_VISION && !dolbyVisionAllowed) return@setMediaCodecSelector emptyList()
            val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            // HDR / Dolby Vision only come out of the hardware decoder, so it stays first for Dolby Vision.
            val software = if (mime.startsWith("video/")) (options.softwareVideo != trocarDecodificador) && mime != MimeTypes.VIDEO_DOLBY_VISION
            else options.softwareAudio
            // Software first when chosen; hardware stays as a fallback (protected video has no software decoder).
            if (software) all.sortedBy { !it.softwareOnly } else all
        }

    val exo: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setBandwidthMeter(bandwidthMeter)
        .setLoadControl(if (options.buffer == "medium" || options.buffer.isBlank()) BufferAutomatico { velocidadeMBs() } else loadControl(options.buffer))
        .build()
        .apply {
            // ZarkTV: registro do que o player recebe (formato, codec, erros) para diagnosticar canais que não abrem.
            // Só na versão de desenvolvimento: em aparelho fraco (Fire Stick) esse registro a cada evento pesa.
            if (io.github.axon07.channelbox.BuildConfig.DEBUG) addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("ZarkPlayer"))
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                handleAudioFocus,
            )
            setWakeMode(C.WAKE_MODE_NETWORK)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val videos = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    videoSemSuporte.value =
                        if (videos.isNotEmpty() && videos.none { g -> (0 until g.length).any { g.isTrackSupported(it) } })
                            videos.first().getTrackFormat(0).let { it.sampleMimeType ?: it.codecs ?: "desconhecido" }
                        else null
                }
            })
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            ZarkDiag.voltou()
                            retries = 0
                            refusals = 0
                            confirmed = true
                            _status.value = PlayerStatus.Playing
                        }
                        Player.STATE_BUFFERING -> {
                            if (confirmed) ZarkDiag.travou()
                            _status.value = PlayerStatus.Buffering
                        }
                        // A live stream "ending" means the server closed the connection: reconnect.
                        Player.STATE_ENDED -> {
                            if (isLive) scheduleRetry("Reconectando…") else _status.value = PlayerStatus.Idle
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    ZarkDiag.erro(error)
                    handleError(error)
                }

                override fun onRenderedFirstFrame() = ZarkDiag.primeiroQuadro()

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) vigiarImagem()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    _tracksVersion.value++
                    updateFrameRate()
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updateFrameRate()
            })
        }

    private val _frameRate = MutableStateFlow(0f)

    /** Frame rate of the video playing (0 = unknown / nothing), for auto frame rate. */
    val frameRate: StateFlow<Float> = _frameRate.asStateFlow()

    private fun updateFrameRate() {
        val fps = exo.videoFormat?.frameRate ?: 0f
        _frameRate.value = if (fps > 0f) fps else 0f
    }

    /** Live channel. */
    fun play(stream: StreamSpec) = open(stream, onDemand = false)

    /** Catch-up programme: seekable, plays once. */
    fun playOnDemand(stream: StreamSpec, startAt: Long = 0) = open(stream, onDemand = true, startAt = startAt)

    /** Where an on-demand video starts (kept while a portal link is being fetched). */
    private var startPosition = 0L

    /**
     * Pause-live-TV buffer: plays [file] from the start while [growing] (the stream is still being
     * written to it). It can't be seeked – the length isn't known yet.
     */
    fun playTimeshift(file: File, growing: () -> Boolean) {
        startToken++
        handler.removeCallbacksAndMessages(null)
        applyCaptionDefault()
        onDemand = true
        spec = null
        val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
            { GrowingFileDataSource(file, growing) },
            androidx.media3.extractor.DefaultExtractorsFactory(),
        ).createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        _status.value = PlayerStatus.Buffering
    }

    /**
     * Pause-live-TV buffer saved as a local HLS playlist (channels with separate audio): plays it
     * from the start; the player re-reads the playlist as new segments are added.
     */
    fun playTimeshiftPlaylist(index: File) {
        open(StreamSpec(Uri.fromFile(index).toString(), emptyMap(), null, StreamSpecs.MIME_HLS), onDemand = true)
        exo.seekTo(0L)
    }

    /** Recorded file. */
    fun playFile(path: String) =
        open(StreamSpec(Uri.fromFile(File(path)).toString(), emptyMap(), null, null), onDemand = true)

    private fun open(stream: StreamSpec, onDemand: Boolean, startAt: Long = 0) {
        ZarkDiag.iniciar(context)
        ZarkDiag.abrindo(aoVivo = !onDemand)
        startPosition = startAt.coerceAtLeast(0)
        handler.removeCallbacksAndMessages(null)
        // Let go of the previous stream first: on one-connection accounts the provider refuses the
        // new channel (HTTP 401/403) while the old connection is still open.
        exo.stop()
        applyCaptionDefault()
        this.onDemand = onDemand
        spec = stream
        val isHttp = stream.url.startsWith("http://", true) || stream.url.startsWith("https://", true) ||
            io.github.axon07.channelbox.data.StreamResolver.needsResolving(stream.url) // a portal link becomes http(s)
        candidates = when {
            stream.mime != null -> listOf(stream.mime)
            !isHttp -> listOf(null) // rtsp://, rtmp://, udp://, file:// – the player knows these
            // Xtream ao vivo (.ts): é TS mesmo; se falhar, troca direto para .m3u8 (mais rápido que testar 3 tipos).
            Regex("""/live/[^?]+\.ts(\?|$)""").containsMatchIn(stream.url) -> listOf(null)
            // DRM-protected IPTV streams are almost always DASH, even without ".mpd" in the link.
            stream.drm != null -> listOf(StreamSpecs.MIME_DASH, null, StreamSpecs.MIME_HLS)
            else -> listOf(null, StreamSpecs.MIME_HLS, StreamSpecs.MIME_DASH)
        }
        attempt = 0
        tentativaAlternativa = 0
        primeiroErroVod = null
        hostsVod = null
        urlVodOriginal = null
        tentativaHost = 0
        erroConexaoVod = false
        avisouDesistencia = false
        // ZarkTV: cada vídeo novo começa de novo no decodificador normal (hardware).
        trocarDecodificador = false
        travadasImagem = 0
        contaVerificada = false
        bloqueioConta = null
        confirmed = false
        retries = 0
        refusals = 0
        widevineL3 = false
        start()
    }

    /** Times in a row the provider refused this stream (connection limit), retried quietly. */
    private var refusals = 0

    private fun httpCode(error: PlaybackException): Int? {
        var cause: Throwable? = error
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        return (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    }

    /** Called when the app comes back to the foreground. */
    fun resume() {
        if (onDemand) { exo.prepare(); exo.playWhenReady = true } // keeps the position
        else if (spec != null) start()
    }

    /** ZarkTV: saiu da conta / conta vencida — para e esquece o canal ou filme (voltar ao app não toca nada). */
    fun esquecer() {
        stop()
        spec = null
        onDemand = false
        runCatching { exo.clearMediaItems() }
    }

    fun stop() {
        startToken++
        handler.removeCallbacksAndMessages(null)
        exo.stop()
        _status.value = PlayerStatus.Idle
    }

    /**
     * Pause live TV: let go of the stream (many accounts allow only one connection, which the pause
     * buffer now needs) while the paused picture stays on screen.
     */
    fun hold() {
        startToken++
        handler.removeCallbacksAndMessages(null)
        exo.pause()
        exo.stop()
    }

    fun release() {
        startToken++
        resolver.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        exo.release()
    }

    /** Portal links ("stalker://") are turned into playable ones here, off the main thread. */
    private val resolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var startToken = 0

    private fun start() {
        val s = spec ?: return
        val mime = candidates.getOrNull(attempt)
        val token = ++startToken
        if (io.github.axon07.channelbox.data.StreamResolver.needsResolving(s.url)) {
            _status.value = PlayerStatus.Buffering
            resolver.execute {
                // A fresh link every time: portal links expire within seconds, so retries ask again.
                val result = runCatching { io.github.axon07.channelbox.data.StreamResolver.resolve(s) }
                handler.post {
                    if (token != startToken || spec !== s) return@post
                    result.fold(
                        onSuccess = { real -> prepare(real, real.mime ?: mime) },
                        onFailure = { e -> failed(e.message ?: "couldn't get a link from the portal") },
                    )
                }
            }
            return
        }
        prepare(s, mime)
    }

    private fun prepare(s: StreamSpec, mime: String?) {
        try {
            val source = buildSource(s, mime)
            if (onDemand && startPosition > 0) exo.setMediaSource(source, startPosition) else exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
            _status.value = PlayerStatus.Buffering
            // ZarkTV: canal ao vivo que fica só "Carregando…" (sem imagem em 12 s) conta como falha:
            // tenta o outro formato e, se não der, mostra o aviso em vez de girar para sempre.
            if (isLive) {
                val alvo = spec
                val tk = startToken
                handler.postDelayed({
                    if (spec === alvo && startToken == tk && !confirmed && exo.playbackState != Player.STATE_READY) failed("sem imagem em 12 s")
                }, 12_000L)
            }
        } catch (e: Exception) {
            // e.g. an unsupported stream type – try the next one instead of crashing.
            failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildSource(s: StreamSpec, mime: String?): MediaSource {
        val userAgent = s.header("User-Agent") ?: UserAgent.current
        val httpSource = OkHttpDataSource.Factory(httpPlayer)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        // DefaultDataSource adds udp://, rtmp://, file:// … and uses OkHttp for http(s).
        val dataSource = if (s.url.startsWith("smb://")) SmbDataSource.Factory(storageSettings)
        else DefaultDataSource.Factory(context, httpSource)
        // ZarkTV: canais Full HD de IPTV costumam vir sem "quadro-chave IDR" e com unidades de acesso
        // fora do padrão; sem estas opções o player fica só carregando (HD/SD abrem, FHD não).
        val tsFlags = androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val extratores = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(tsFlags)
            .setTsExtractorTimestampSearchBytes(1500 * androidx.media3.extractor.ts.TsExtractor.TS_PACKET_SIZE)
            .setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(dataSource, extratores)
        // Filmes/séries: endereço que nem conecta não é tentado de novo pelo player (o app já tem outros caminhos).
        if (!isLive) sources.setLoadErrorHandlingPolicy(SemInsistirSemConexao)
        if (mime == StreamSpecs.MIME_HLS || s.url.contains(".m3u8", ignoreCase = true)) {
            val hls = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSource)
                .setExtractorFactory(androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(tsFlags, true))
                .setAllowChunklessPreparation(true)
            val itemHls = MediaItem.Builder().setUri(s.url).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build()
            if (s.drm == null) return hls.createMediaSource(itemHls)
        }
        val item = MediaItem.Builder().setUri(s.url)
        if (mime != null) item.setMimeType(mime)

        val drm = s.drm
        if (drm != null) {
            if (drm.clearKeys.isNotEmpty()) {
                // ClearKey with the keys in the playlist: answer license requests locally.
                val manager = ClearKeyDrmSessionManager(drm.clearKeys)
                sources.setDrmSessionManagerProvider { manager }
            } else {
                val scheme = when (drm.type) {
                    StreamSpecs.CLEARKEY -> C.CLEARKEY_UUID
                    // Many Android TV boxes have no PlayReady; most license servers also speak Widevine.
                    StreamSpecs.PLAYREADY -> if (supported(C.PLAYREADY_UUID)) C.PLAYREADY_UUID else C.WIDEVINE_UUID
                    else -> C.WIDEVINE_UUID
                }
                val callback = HttpMediaDrmCallback(drm.licenseUrl, httpSource).apply {
                    drm.licenseHeaders.forEach { (k, v) -> setKeyRequestProperty(k, v) }
                }
                val forceL3 = widevineL3 && scheme == C.WIDEVINE_UUID
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(scheme, drmProvider(forceL3))
                    .setMultiSession(scheme == C.CLEARKEY_UUID)
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(callback)
                sources.setDrmSessionManagerProvider { manager }
            }
        }
        return sources.createMediaSource(item.build())
    }

    private fun drmProvider(forceL3: Boolean) = ExoMediaDrm.Provider { uuid: UUID ->
        try {
            FrameworkMediaDrm.newInstance(uuid).also { drm ->
                if (forceL3) runCatching { drm.setPropertyString("securityLevel", "L3") }
            }
        } catch (e: UnsupportedDrmException) {
            DummyExoMediaDrm()
        }
    }

    private fun supported(scheme: UUID): Boolean =
        runCatching { android.media.MediaDrm.isCryptoSchemeSupported(scheme) }.getOrDefault(false)

    private fun handleError(error: PlaybackException) {
        val s = spec
        // Widevine at L1 fails on many cheap boxes; L3 (software) usually works.
        val isDrmError = error.errorCode in 6000..6999
        if (isDrmError && !widevineL3 && s?.drm != null && s.drm.clearKeys.isEmpty() &&
            (s.drm.type == StreamSpecs.WIDEVINE || (s.drm.type == StreamSpecs.PLAYREADY && !supported(C.PLAYREADY_UUID)))
        ) {
            widevineL3 = true
            handler.post { start() }
            return
        }
        if (!isLive && semConexao(error)) {
            // Servidor do filme não respondeu: vai logo para o próximo caminho (Web Play / outro DNS).
            erroConexaoVod = true
            attempt = candidates.lastIndex // outro formato no mesmo endereço morto não adianta
            android.util.Log.w("ZarkPlayer", "filme: servidor não respondeu – indo para outro caminho")
            handler.post { failed("o servidor não respondeu") }
            return
        }
        if (!isLive) {
            _status.value = PlayerStatus.Error("Can't play this (${describe(error)})")
            if (!confirmed && attempt < candidates.lastIndex) {
                attempt++
                handler.post { start() }
            } else if (!confirmed) {
                // Este caminho não deu: segue para o próximo (outra identificação, outro DNS, Web Play)
                // na hora, em vez de esperar o vigia de 12 s.
                handler.post { failed(describe(error)) }
            }
            return
        }
        // Refused (401/403) or "too many connections": usually the previous channel's connection is
        // still being closed on a one-connection account. Not a format problem, so don't try other
        // formats (more connections) – wait a moment and ask again, without alarming messages.
        val code = httpCode(error)
        if (code != null && code in REFUSED && refusals < 4) {
            refusals++
            val current = spec
            _status.value = PlayerStatus.Buffering
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ if (spec === current && current != null) start() }, 1_200L * refusals)
            return
        }
        // ZarkTV: recusou de novo depois das tentativas → antes de tentar outros formatos e endereços
        // (cada um abre mais uma conexão), pergunta ao servidor se a conta está em uso em outro aparelho
        // ou vencida. Se estiver, mostra o aviso e para de tentar.
        if (code != null && code in REFUSED && !contaVerificada) {
            contaVerificada = true
            val atual = spec
            _status.value = PlayerStatus.Buffering
            handler.removeCallbacksAndMessages(null)
            verificarConta { aviso ->
                handler.post {
                    if (atual == null || spec !== atual) return@post
                    if (aviso != null) {
                        bloqueioConta = aviso
                        handler.removeCallbacksAndMessages(null)
                        runCatching { exo.stop() }
                        _status.value = PlayerStatus.Error(aviso)
                    } else {
                        failed(describe(error))
                    }
                }
            }
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        failed(describe(error))
    }

    /** What went wrong, in words. For HTTP errors: the server's status code and what it usually means. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        val ex = cause as? HttpDataSource.InvalidResponseCodeException
        val code = ex?.responseCode ?: return error.errorCodeName
        // Diagnóstico: endereço (sem a senha) e o que o servidor respondeu.
        val resposta = runCatching { String(ex.responseBody, Charsets.UTF_8) }.getOrDefault("")
            .replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(90)
        runCatching {
            val u = ex.dataSpec.uri.toString().replace(Regex("""/(live|movie|series)/([^/]+)/[^/]+/"""), "/$1/$2/***/")
            android.util.Log.w("ZarkPlayer", "HTTP $code em $u resposta=[$resposta] cabecalhos=${ex.headerFields}")
        }
        val hint = when (code) {
            400 -> "o servidor recusou este arquivo – pode estar fora do ar no servidor"
            401, 403 -> "o servidor recusou – a conta pode estar em uso em outro aparelho ou vencida"
            404, 410 -> "o arquivo não existe mais no servidor"
            429, 456, 458, 509 -> "conexões demais nesta conta – feche em outros aparelhos"
            in 500..599 -> "o servidor está com problema"
            else -> null
        }
        val extra = if (resposta.isNotBlank()) "\nServidor: $resposta" else ""
        return (if (hint != null) "HTTP $code: $hint" else "HTTP $code") + extra
    }

    private fun failed(reason: String) {
        if (spec == null) return
        if (!confirmed && attempt < candidates.lastIndex) {
            attempt++
            handler.post { start() }
            return
        }
        // ZarkTV: canal Xtream em .ts que não abriu → tenta o mesmo canal em HLS (.m3u8) antes de desistir.
        // Alguns servidores (Five, Club…) só liberam um dos dois formatos.
        val atual = spec
        val ehXtream = atual != null && Regex("""/live/[^?]+\.(ts|m3u8)(\?|$)""").containsMatchIn(atual.url)
        if (atual != null && ehXtream && tentativaAlternativa < ALTERNATIVAS.size) {
            // Sequência: .m3u8 → .ts com outra identificação (VLC) → .m3u8 com outra (okhttp).
            val (ext, ua) = ALTERNATIVAS[tentativaAlternativa++]
            val base = atual.url.replace(Regex("""\.(ts|m3u8)(\?|$)"""), ".$ext$2")
            val headers = if (ua == null) atual.headers - "User-Agent" else atual.headers + ("User-Agent" to ua)
            spec = atual.copy(url = base, headers = headers, mime = if (ext == "m3u8") StreamSpecs.MIME_HLS else null)
            candidates = listOf(spec!!.mime)
            attempt = 0
            handler.post { start() }
            return
        }
        // ZarkTV: filme/episódio Xtream que o servidor recusou (HTTP 400/403/404…) → tenta com outra
        // identificação do app e outra extensão (mp4/mkv) antes de desistir. Uniplay e outros recusam o ExoPlayer.
        val ehVod = atual != null && Regex("""/(movie|series)/[^/]+/[^/]+/[^/?]+\.\w+(\?|$)""").containsMatchIn(atual.url)
        if (atual != null && ehVod && !isLive && tentativaAlternativa == 0 && tentativaHost == 0) {
            primeiroErroVod = reason
            urlVodOriginal = atual.url
        }
        if (atual != null && ehVod && !isLive && !erroConexaoVod && tentativaAlternativa < ALT_VOD.size) {
            val (ext, ua) = ALT_VOD[tentativaAlternativa++]
            val url = if (ext == null) atual.url else atual.url.replace(Regex("""\.\w+(\?|$)"""), ".$ext$1")
            val headers = if (ua == null) atual.headers - "User-Agent" else atual.headers + ("User-Agent" to ua)
            spec = atual.copy(url = url, headers = headers, mime = null)
            candidates = listOf(null)
            attempt = 0
            handler.post { start() }
            return
        }
        // Depois das outras identificações/formatos: filme/episódio recusado neste endereço ("servidor não liberado"): tenta o mesmo arquivo nos outros
        // endereços (DNS) do painel, primeiro os do mesmo servidor.
        if (atual != null && ehVod && !isLive) {
            if (hostsVod == null) {
                val host = Regex("""^(https?://[^/]+)""").find(urlVodOriginal ?: atual.url)?.value
                hostsVod = if (host == null) emptyList() else hostsAlternativos(host).filter { !it.equals(host, ignoreCase = true) }.distinct()
                    // Sem conexão com o servidor: o caminho do Web Play (servidor da ZarkTV) primeiro.
                    .let { l -> if (erroConexaoVod) l.sortedBy { if (it.contains("tv.zarktvqx.cfd")) 0 else 1 } else l }
            }
            val lista = hostsVod.orEmpty()
            if (tentativaHost < lista.size) {
                val novo = lista[tentativaHost++].trimEnd('/')
                val url = urlVodOriginal!!.replace(Regex("""^https?://[^/]+"""), novo)
                android.util.Log.w("ZarkPlayer", "filme recusado – tentando outro endereço do painel (${tentativaHost}/${lista.size})")
                spec = atual.copy(url = url, mime = null, headers = atual.headers + ("User-Agent" to "VLC/3.0.20 LibVLC/3.0.20"))
                candidates = listOf(null)
                attempt = 0
                handler.post { start() }
                return
            }
        }
        if (!isLive) {
            _status.value = PlayerStatus.Error("Não foi possível reproduzir (${primeiroErroVod ?: reason})")
            return
        }
        // Avisa uma vez (por canal) que esgotou as tentativas: o app pode abrir outra versão do mesmo canal.
        if (!avisouDesistencia) { avisouDesistencia = true; aoDesistir?.invoke() }
        scheduleRetry("Canal fora do ar no momento – tentando de novo… ($reason)")
    }

    // ---- ZarkTV: imagem congelada com o som tocando (decodificador de vídeo travado, ex.: Fire Stick) ----
    /** Depois de travar uma vez, o vídeo passa a usar o outro decodificador (software ↔ hardware) nesta sessão. */
    /**
     * ZarkTV: quando o servidor recusa o vídeo (401/403), consulta a conta. Chama o retorno com o aviso
     * para o cliente (conta em uso em outro aparelho, vencida…) ou null se a conta está normal.
     */
    var verificarConta: ((String?) -> Unit) -> Unit = { it(null) }
    @Volatile private var contaVerificada = false
    /** Aviso da conta mostrado agora (em uso em outro aparelho, vencida). Null quando está tudo certo. */
    @Volatile var bloqueioConta: String? = null
        private set

    @Volatile private var trocarDecodificador = false
    /** Quantas vezes a imagem congelou neste vídeo. */
    private var travadasImagem = 0
    private var ultimoQuadro = -1
    private var quadroParado = 0
    private val vigia = object : Runnable {
        override fun run() {
            if (!exo.isPlaying || exo.videoFormat == null) { ultimoQuadro = -1; quadroParado = 0; return }
            val c = exo.videoDecoderCounters ?: run { handler.postDelayed(this, 1_500); return }
            c.ensureUpdated()
            val n = c.renderedOutputBufferCount
            if (n == ultimoQuadro) quadroParado++ else quadroParado = 0
            ultimoQuadro = n
            if (quadroParado >= 3) {
                // Som andando e nenhum quadro novo há ~4,5 s. 1ª vez: só recarrega do mesmo ponto.
                // 2ª vez: troca para o decodificador por software, mas só até 720p — acima disso o
                // Fire Stick não dá conta e o filme/série passa a travar o tempo todo.
                travadasImagem++
                val altura = exo.videoFormat?.height ?: 0
                val trocar = travadasImagem >= 2 && !trocarDecodificador && altura in 1..720
                android.util.Log.w("ZarkPlayer", "imagem parada com som (${altura}p, vez $travadasImagem) – " + if (trocar) "trocando o decodificador" else "recarregando")
                if (trocar) trocarDecodificador = true
                quadroParado = 0
                val pos = exo.currentPosition
                exo.stop()
                exo.prepare()
                if (!isLive) exo.seekTo(pos)
                return
            }
            handler.postDelayed(this, 1_500)
        }
    }

    private fun vigiarImagem() {
        // Só nos aparelhos da Amazon (onde a imagem travava); nos outros não mexe em nada.
        if (!android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return
        handler.removeCallbacks(vigia)
        ultimoQuadro = -1
        quadroParado = 0
        handler.postDelayed(vigia, 1_500)
    }

    private fun scheduleRetry(message: String) {
        val current = spec ?: return
        retries++
        _status.value = PlayerStatus.Error(message)
        val delayMs = (retries * 3_000L).coerceAtMost(30_000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (spec === current && isLive) {
                if (!confirmed) attempt = 0
                start()
            }
        }, delayMs)
    }

    // ---- Audio / subtitle tracks ---------------------------------------------------------------

    fun audioTracks(): List<TrackOption> = tracks(C.TRACK_TYPE_AUDIO)

    fun subtitleTracks(): List<TrackOption> {
        val list = tracks(C.TRACK_TYPE_TEXT)
        val disabled = C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes
        val anySelected = !disabled && list.any { it.selected }
        return listOf(TrackOption("Off", null, 0, !anySelected)) + if (disabled) list.map { it.copy(selected = false) } else list
    }

    private fun tracks(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        exo.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                out += TrackOption(describe(group.getTrackFormat(i), out.size + 1), group, i, group.isTrackSelected(i))
            }
        }
        return out
    }

    /** Video qualities, "Auto" first. */
    fun videoTracks(): List<TrackOption> {
        val list = tracks(C.TRACK_TYPE_VIDEO)
        val overridden = exo.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        val playing = exo.videoFormat?.height?.takeIf { it > 0 }?.let { " (now ${it}p)" }.orEmpty()
        return listOf(TrackOption("Auto$playing", null, 0, !overridden)) +
            list.sortedByDescending { it.group?.getTrackFormat(it.index)?.height ?: 0 }
                .map { it.copy(selected = overridden && it.selected) }
    }

    /**
     * Quality choice: 0 = best available, >0 = best up to that height, both held steady;
     * [ADAPTIVE] = switch with the connection speed. Switching levels mid-stream can show green
     * blocks on some streams, so steady is the default.
     */
    fun setMaxQuality(height: Int) {
        val b = exo.trackSelectionParameters.buildUpon()
        if (height <= 0) b.clearVideoSizeConstraints() else b.setMaxVideoSize(Int.MAX_VALUE, height)
        b.setForceHighestSupportedBitrate(height != ADAPTIVE)
        exo.trackSelectionParameters = b.build()
    }

    /** Languages of the subtitles / closed captions the stream offers. */
    fun captionLanguages(): List<String> = tracks(C.TRACK_TYPE_TEXT).map { it.label.substringBefore(" · ") }.distinct()

    val captionsOn: Boolean
        get() = C.TRACK_TYPE_TEXT !in exo.trackSelectionParameters.disabledTrackTypes &&
            tracks(C.TRACK_TYPE_TEXT).any { it.selected }

    /** CC button: turns captions off, or on in the box's language (else the first one). Returns the new state. */
    fun toggleCaptions(): Boolean {
        val text = tracks(C.TRACK_TYPE_TEXT)
        if (captionsOn || text.isEmpty()) {
            select(C.TRACK_TYPE_TEXT, TrackOption("Off", null, 0, false))
            return false
        }
        val lang = Locale.getDefault().language
        val pick = text.firstOrNull { it.group?.getTrackFormat(it.index)?.language?.startsWith(lang) == true } ?: text.first()
        select(C.TRACK_TYPE_TEXT, pick)
        return true
    }

    fun streamInfo(): StreamInfo {
        val v = exo.videoFormat
        val a = exo.audioFormat
        val video = v?.let { f ->
            listOfNotNull(
                if (f.height > 0) "${f.height}p" else null,
                f.frameRate.takeIf { it > 0 }?.let { "${Math.round(it)} fps" },
                codecName(f.sampleMimeType ?: f.codecs),
                dynamicRange(f),
            ).joinToString(" · ").ifEmpty { null }
        }
        val audio = a?.let { describe(it, 1).substringAfter("Track 1 · ", describe(it, 1)) }
        val bits = (v?.bitrate?.takeIf { it > 0 } ?: bandwidthMeter.bitrateEstimate.toInt().takeIf { it > 0 })
            ?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }
        return StreamInfo(video, audio, bits)
    }

    /** "Dolby Vision", "HDR10" or "HLG" for HDR video; null for normal (SDR) video. */
    private fun dynamicRange(f: Format): String? {
        val dv = f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            f.codecs?.let { c -> listOf("dvh1", "dvhe", "dva1", "dvav", "dav1").any { c.startsWith(it) } } == true
        val transfer = f.colorInfo?.colorTransfer
        return when {
            dv && dolbyVisionAllowed -> "Dolby Vision"
            dv -> "Dolby Vision as HDR10"
            transfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            transfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun codecName(mime: String?): String? = when {
        mime == null -> null
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "H.265"
        mime.contains("avc") || mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime.contains("hev") || mime.contains("hvc") || mime == MimeTypes.VIDEO_H265 -> "H.265"
        mime.contains("av01") || mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        mime.contains("vp9") || mime == MimeTypes.VIDEO_VP9 -> "VP9"
        else -> null
    }

    fun select(type: Int, option: TrackOption) {
        val builder = exo.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null && type == C.TRACK_TYPE_VIDEO) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else if (group == null) {
            builder.setTrackTypeDisabled(type, true)
            if (type == C.TRACK_TYPE_TEXT) captionsWanted = false
        } else {
            if (type == C.TRACK_TYPE_TEXT) captionsWanted = true
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun describe(f: Format, number: Int): String {
        if (f.sampleMimeType?.startsWith("video/") == true || f.height > 0) {
            return listOfNotNull(
                if (f.height > 0) "${f.height}p" else "Video $number",
                f.frameRate.takeIf { it > 0 }?.let { "${Math.round(it)} fps" },
                f.bitrate.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) },
            ).joinToString(" · ")
        }
        val language = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale(it).displayLanguage.takeIf { name -> name.isNotBlank() } ?: it }
        val name = f.label?.takeIf { it.isNotBlank() } ?: language ?: "Track $number"
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
            else -> null
        }
        val channels = when (f.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            Format.NO_VALUE -> null
            else -> "${f.channelCount} ch"
        }
        return listOfNotNull(name, codec, channels).distinct().joinToString(" · ")
    }

    companion object {
        /** Erro de "nem conectou" (tempo esgotado, recusado, sem rota, nome não encontrado). */
        fun semConexao(e: Throwable?): Boolean {
            var c = e
            var n = 0
            while (c != null && n++ < 8) {
                if (c is java.net.SocketTimeoutException || c is java.net.ConnectException || c is java.net.UnknownHostException ||
                    c is java.net.NoRouteToHostException) return true
                c = c.cause
            }
            return false
        }

        /** Não repete a mesma requisição quando nem conectou; outros erros seguem o padrão. */
        private object SemInsistirSemConexao : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (semConexao(info.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }

        /** HTTP codes providers use for "refused" / "too many connections". */
        private val REFUSED = setOf(401, 403, 429, 456, 458, 509)
        const val DEFAULT_USER_AGENT = UserAgent.DEFAULT

        /** Formatos e identificações tentados quando um canal Xtream não abre. */
        /** Filmes/episódios: mesma extensão com outras identificações, depois mp4 e mkv. */
        private val ALT_VOD = listOf<Pair<String?, String?>>(
            null to "VLC/3.0.20 LibVLC/3.0.20",
            null to "okhttp/4.12.0",
            null to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
            null to "Lavf/58.76.100",
            null to "IPTVSmartersPlayer",
            "mp4" to "VLC/3.0.20 LibVLC/3.0.20",
            "mkv" to "VLC/3.0.20 LibVLC/3.0.20",
            "m3u8" to null,
        )

        private val ALTERNATIVAS = listOf(
            "m3u8" to null,
            "ts" to "VLC/3.0.20 LibVLC/3.0.20",
            "m3u8" to "okhttp/4.12.0",
        )
    }
}

/** [TvPlayer.setMaxQuality] value: let quality follow the connection speed. */
const val ADAPTIVE = -1

/**
 * Buffer sizes (how much video is loaded ahead). Bigger rides out a shaky connection but starts
 * channels a little slower; "none" starts fastest. Values: minimum / maximum buffered, and how
 * much is needed to start (and to restart after running out).
 */
private fun loadControl(size: String): androidx.media3.exoplayer.LoadControl {
    val (min, max, start, restart) = when (size) {
        "none" -> listOf(1_500, 4_000, 500, 1_000)
        "small" -> listOf(8_000, 20_000, 1_500, 3_500)
        "large" -> listOf(30_000, 60_000, 4_000, 8_000)
        "xlarge" -> listOf(60_000, 120_000, 6_000, 12_000)
        // medium (ZarkTV): guarda ~2,5 s antes de começar e 5 s depois de travar, para não ficar
        // abrindo e carregando de novo logo em seguida; mantém até 40 s guardados.
        else -> listOf(15_000, 40_000, 2_500, 5_000)
    }
    return androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(min, max, start, restart)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}


/**
 * ZarkTV: buffer automático. Antes de começar (e depois de travar) espera um tempo que depende da
 * internet medida no aparelho: rápida = começa logo (troca de canal rápida); lenta = guarda mais
 * para não ficar parando. O resto (quanto baixar, quando parar) fica com o controle padrão.
 */
/**
 * TV Box fraca (pouca memória): guarda menos vídeo (12 a 30 s) para não estourar a memória, mas sempre
 * pelo TEMPO, nunca pelo tamanho — limitar por tamanho fazia canal HD/FHD parar de baixar com poucos
 * segundos guardados e dar travadinhas.
 */
private val LEVE get() = io.github.axon07.channelbox.ui.ZarkLeve.ativo

private class BufferAutomatico(
    private val velocidade: () -> Double,
) : androidx.media3.exoplayer.DefaultLoadControl(
    androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE),
    if (LEVE) 12_000 else 15_000, if (LEVE) 30_000 else 40_000, 2_500, 5_000,
    androidx.media3.common.C.LENGTH_UNSET, true, 0, false,
) {
    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
        val mbs = runCatching { velocidade() }.getOrDefault(0.0)
        val alvoMs = when {
            mbs <= 0.0 -> 2_500L // ainda sem medição
            mbs < 0.7 -> 5_000L  // internet fraca: estável
            mbs < 2.0 -> 2_500L  // equilibrado
            else -> 1_200L       // internet boa: rápido
        } * (if (parameters.rebuffering) 2 else 1)
        val guardadoMs = parameters.bufferedDurationUs / 1000 / parameters.playbackSpeed.coerceAtLeast(0.1f).toDouble()
        // Buffer cheio (o controle padrão parou de baixar) também libera, para não travar esperando.
        return guardadoMs >= alvoMs || (super.shouldStartPlayback(parameters) && guardadoMs >= alvoMs / 2)
    }
}
