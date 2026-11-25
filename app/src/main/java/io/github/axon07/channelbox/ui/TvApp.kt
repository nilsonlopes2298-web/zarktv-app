package io.github.axon07.channelbox.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.VodEntity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.RecStatus
import io.github.axon07.channelbox.data.RecordingEntity
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.player.PlayerStatus
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/**
 * Screens. Back walks: TV → Guide → Groups menu → "Exit?" (configurable in Settings).
 */
enum class Mode {
    PLAYER, CHANNEL_LIST, GUIDE, GROUPS, PROGRAM_MENU,
    RECORDINGS, RECORDING_MENU, PLAYBACK,
    SETTINGS, SETUP, EXIT_CONFIRM, UPDATE, OPTIONS, MULTIVIEW,
    /** Movies / TV Shows (normal focus, like Settings). */
    VOD,
    /** Search the guide (normal focus). */
    SEARCH,
    /** Organise many channels / groups at once (normal focus). */
    ORGANISE,
    /** ZarkTV: tela inicial com os blocões (normal focus). */
    HOME,
    /** ZarkTV: TV ao vivo – lista de canais e prévia ao lado (normal focus). */
    LIVE,
    /** ZarkTV: perfil (conta, renovar, conteúdo adulto, player, suporte). */
    PROFILE,
    /** ZarkTV: configurações no estilo UniTV (reprodução, legendas, proporção…). */
    AJUSTES,
}

/** Screens where we read the remote ourselves (the others use normal focus). */
private val OWN_KEY_MODES = setOf(Mode.PLAYER, Mode.CHANNEL_LIST, Mode.GUIDE, Mode.GROUPS, Mode.RECORDINGS, Mode.PLAYBACK, Mode.MULTIVIEW)

/** Screens that show the guide with the small live preview. */
private val GUIDE_MODES = setOf(Mode.GUIDE, Mode.GROUPS, Mode.PROGRAM_MENU)

private class OkKeyTracker {
    var armed = false
    var longFired = false
}

private val OK_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

private val RECORD_KEYS = setOf(AndroidKeyEvent.KEYCODE_MEDIA_RECORD, AndroidKeyEvent.KEYCODE_PROG_RED)

/** Every key we act on. Anything else (volume, Home, Back…) is left to the system. */
private val HANDLED_KEYS = OK_KEYS + RECORD_KEYS + setOf(
    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
    AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_PAGE_DOWN,
    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_MENU,
    AndroidKeyEvent.KEYCODE_LAST_CHANNEL,
    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
    AndroidKeyEvent.KEYCODE_CAPTIONS, AndroidKeyEvent.KEYCODE_PROG_GREEN,
) + (AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) +
    (AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9)

private fun digitOf(code: Int): Int? = when (code) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> code - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> code - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

@Composable
fun TvApp(vm: TvViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        val locked by vm.lockedChannelIds.collectAsStateWithLifecycle()
        val recording by vm.recordingChannels.collectAsStateWithLifecycle()
        androidx.compose.runtime.CompositionLocalProvider(
            LocalLockedChannels provides locked,
            LocalRecordingChannels provides recording,
            LocalShowChannelNumbers provides s.showChannelNumbers,
        ) {
            Box(Modifier.fillMaxSize()) {
                TvAppContent(vm, s)
                // Abertura. TV/TV Box: logo e versão, como na UniTV (sem mudança).
                // Celular/tablet: abertura animada (ZarkAberturaMobile.kt), por cima da tela
                // que já está carregando (login ou tela inicial), sem segurar o carregamento.
                val ctxAbertura = androidx.compose.ui.platform.LocalContext.current
                val celular = remember { ZarkAnimacao.aberturaAtiva && (!ehTv(ctxAbertura) || ZarkAnimacao.aberturaNaTv) }
                // Só uma vez por uso do app: ao girar para a tela cheia o Android recria a tela,
                // e a abertura não pode voltar por cima do vídeo.
                var abertura by remember { mutableStateOf(!ZarkAnimacao.jaMostrou) }
                if (!celular && abertura) LaunchedEffect(Unit) {
                    delay(1600)
                    abertura = false
                    ZarkAnimacao.jaMostrou = true
                }
                if (abertura) {
                    if (celular) ZarkAberturaAnimada(onFim = { abertura = false; ZarkAnimacao.jaMostrou = true })
                    else ZarkAbertura(vm.appVersion)
                }
            }
        }
    }
}

/** Ids of channels recording right now, for the red dot in the guide and channel list. */
val LocalRecordingChannels = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

/** How far one ◀◀ / ▶▶ press moves through live TV on channels with catch-up. */
const val REWIND_STEP = 30_000L

/** Whether the guide shows channel numbers beside the names. */
val LocalShowChannelNumbers = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Ids of locked channels (parental PIN), for the little lock in the guide and channel list. */
val LocalLockedChannels = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

@OptIn(UnstableApi::class)
@Composable
private fun TvAppContent(vm: TvViewModel, settings: AppSettings) {
    val gm = guideMetrics()
    val allChannels by vm.allChannels.collectAsStateWithLifecycle()
    val visible by vm.visibleChannels.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val shift by vm.shift.collectAsStateWithLifecycle()
    val rewinding by vm.rewinding.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val menuSections by vm.menuSections.collectAsStateWithLifecycle()
    val expandedSections by vm.expanded.collectAsStateWithLifecycle()
    val menuRows = remember(menuSections, expandedSections) { vm.menuRows(menuSections, expandedSections) }
    val current by vm.current.collectAsStateWithLifecycle()
    val channelsLoaded by vm.channelsLoaded.collectAsStateWithLifecycle()
    // Guarda o "destaque do momento" (em alta e novidades do catálogo) para a próxima tela de entrada.
    val ctxDestaque = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(channelsLoaded) {
        // Espera o app assentar (canal já tocando) e faz no máximo 1 vez a cada 12 h, para não pesar na TV Box.
        if (!channelsLoaded) return@LaunchedEffect
        val prefsD = ctxDestaque.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefsD.getLong("destaques_entrada_em", 0L) < 12 * 3600_000L) return@LaunchedEffect
        delay(45_000)
        runCatching { DestaquesEntrada.atualizar(ctxDestaque, vm) }
        prefsD.edit().putLong("destaques_entrada_em", System.currentTimeMillis()).apply()
    }
    /** A long Back press already went to full screen (swallow the rest of that press). */
    var backLongFired by remember { mutableStateOf(false) }
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val guideStart by vm.guideStart.collectAsStateWithLifecycle()
    val guidePrograms by vm.guidePrograms.collectAsStateWithLifecycle()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val playerStatus by vm.player.status.collectAsStateWithLifecycle()
    val bannerToken by vm.bannerToken.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val recordingKeys by vm.recordingKeys.collectAsStateWithLifecycle()
    val recordingChannels by vm.recordingChannels.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updatePrompt by vm.updatePrompt.collectAsStateWithLifecycle()
    val tracksVersion by vm.player.tracksVersion.collectAsStateWithLifecycle()
    val filterCounts by vm.filterCounts.collectAsStateWithLifecycle()
    val inPip by vm.inPip.collectAsStateWithLifecycle()
    val vpnState by vm.vpnState.collectAsStateWithLifecycle()
    val timeFormat = rememberTimeFormatter()

    var mode by remember {
        mutableStateOf(
            when {
                !settings.hasSource -> Mode.SETUP
                else -> Mode.HOME // ZarkTV: sempre abre na tela inicial
            }
        )
    }
    /** Playlist being edited on the setup screen; null = adding a new one. */
    var setupPlaylistId by remember { mutableStateOf<String?>(null) }
    /** Settings opened from first-time setup for network options. */
    var settingsFromSetup by remember { mutableStateOf(false) }
    /** ZarkTV "Minha conta": seção a abrir nas configurações ("parental", "playback") ou null. */
    var settingsSection by remember { mutableStateOf<String?>(null) }
    /** ZarkTV: "Trocar de conta"/"Sair" no perfil – volta para a tela de login. */
    var zarkTrocarConta by remember { mutableStateOf(false) }
    /** ZarkTV: aba aberta na tela principal (TV, Destaques, Filmes…) e onde fica a prévia do canal. */
    /** Muda toda vez que o app volta para a frente (para religar o vídeo). */
    var voltouToken by remember { mutableIntStateOf(0) }
    val donoCiclo = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(donoCiclo) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_START) {
                voltouToken++
                // Ao vivo: pede um quadro novo ao decodificador.
                val exo = vm.player.exo
                if (exo.isCurrentMediaItemLive) exo.seekToDefaultPosition() else if (exo.playbackState != androidx.media3.common.Player.STATE_IDLE) exo.seekTo(exo.currentPosition)
            }
        }
        donoCiclo.lifecycle.addObserver(obs)
        onDispose { donoCiclo.lifecycle.removeObserver(obs) }
    }
    var homeTab by remember { mutableIntStateOf(if (io.github.axon07.channelbox.system.Aparelho.soCelular) Aba.DESTAQUES else Aba.TV) } // celular abre no Início (filmes e séries), como o Web Play
    /** ZarkTV: jogo aberto dentro da aba Jogos, os canais que podem passar ele e qual está tocando. */
    var jogoAberto by remember { mutableStateOf<io.github.axon07.channelbox.data.ZarkJogos.Jogo?>(null) }
    var candidatosJogo by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var jogoCanalIdx by remember { mutableIntStateOf(0) }
    val zarkCtxJogo = LocalContext.current
    fun jogoEsperando(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo) =
        j.estado == "pre" && j.inicio - System.currentTimeMillis() > 30 * 60_000L // libera o canal 30 minutos antes do jogo
    // Jogo aberto: uns 20 min depois do fim (≈ 2h10 depois do início) fecha sozinho e para o canal.
    LaunchedEffect(jogoAberto) {
        val j = jogoAberto ?: return@LaunchedEffect
        val fim = j.inicio + (2 * 60 + 10) * 60_000L
        while (System.currentTimeMillis() < fim) kotlinx.coroutines.delay(60_000)
        if (jogoAberto?.id == j.id && io.github.axon07.channelbox.system.Aparelho.soCelular) {
            jogoAberto = null
            vm.enterLibrary()
            vm.showToast("O jogo terminou")
        }
    }
    // Chegou a hora do jogo aberto que estava esperando: liga o canal sozinho.
    LaunchedEffect(jogoAberto) {
        val j = jogoAberto ?: return@LaunchedEffect
        if (!jogoEsperando(j)) return@LaunchedEffect
        while (jogoEsperando(j)) kotlinx.coroutines.delay(20_000)
        if (jogoAberto?.id == j.id) {
            vm.leaveLibrary()
            candidatosJogo.firstOrNull()?.let { ch -> vm.withChannelAccess(ch) { vm.tune(ch) } }
        }
    }
    fun canaisDoJogo(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo): List<ChannelEntity> {
        // Canais de filme nunca entram (ex.: transmissão "Max" não pode abrir o Cinemax / HBO Max).
        val filme = Regex("""cinemax|hbo|telecine|megapix|warner|tnt series|space|universal|sony|axn|amc|tcm|cine|filmes|series|séries|novela""", RegexOption.IGNORE_CASE)
        fun ehFilme(ch: ChannelEntity) = filme.containsMatchIn(ch.name) || filme.containsMatchIn(ch.groupTitle)
        fun palavra(t: String) = Regex("""(^|[^a-z0-9])""" + Regex.escape(t.lowercase().trim()) + """($|[^a-z0-9])""")
        val genericos = setOf("max", "star", "star+", "paramount", "paramount+", "prime", "prime video", "globoplay", "youtube", "app")
        val transmissao = j.canais.map { it.trim() }.filter { it.length >= 3 && it.lowercase() !in genericos }
        val porTransmissao = allChannels.filter { ch -> !ehFilme(ch) && transmissao.any { palavra(it).containsMatchIn(ch.name.lowercase()) } }
        fun limpa(t: String) = t.lowercase().replace(Regex("[^a-z0-9]"), "")
        val times = listOf(j.casa.nome, j.fora.nome).map(::limpa).filter { it.length >= 4 }
        val porTime = allChannels.filter { ch -> !ehFilme(ch) && times.any { limpa(ch.name).contains(it) } }
        val esporte = Regex("""premiere|espn|sportv|tnt ?sports|band ?sports|combate|caz[eé] ?tv|disney ?\+|goat|nosso futebol|onefootball|dazn|eleven|\bsports?\b|futebol|ppv|pay-per-view|jogos""", RegexOption.IGNORE_CASE)
        val esportes = allChannels.filter { !ehFilme(it) && (esporte.containsMatchIn(it.name) || esporte.containsMatchIn(it.groupTitle)) }
        val lista = (porTime + porTransmissao + esportes).distinctBy { it.id }.take(60)
        // Memória: o que já funcionou vem primeiro; o que deu erro há pouco vai para o fim.
        return MemoriaCanais.ordenar(zarkCtxJogo, lista) { it.id }
    }
    // Opções do jogo para os botões embaixo do vídeo (FHD, HD, SD, outros canais…).
    androidx.compose.runtime.SideEffect {
        JogoOpcoes.nomes.value = if (jogoAberto == null) emptyList() else candidatosJogo.take(8).map { it.name }
        JogoOpcoes.atual.intValue = jogoCanalIdx
        JogoOpcoes.escolher = { i ->
            candidatosJogo.getOrNull(i)?.let { ch -> jogoCanalIdx = i; vm.withChannelAccess(ch) { vm.tune(ch) } }
        }
    }
    // Jogo aberto: canal que tocou entra na memória; canal com erro passa sozinho para a próxima opção.
    var trocasAuto by remember(jogoAberto?.id) { mutableIntStateOf(0) }
    LaunchedEffect(playerStatus, jogoAberto?.id) {
        val j = jogoAberto ?: return@LaunchedEffect
        val ch = current ?: return@LaunchedEffect
        if (candidatosJogo.none { it.id == ch.id }) return@LaunchedEffect
        when (playerStatus) {
            is PlayerStatus.Playing -> MemoriaCanais.funcionou(zarkCtxJogo, ch.id)
            is PlayerStatus.Error -> {
                if (vm.player.bloqueioConta != null) return@LaunchedEffect
                MemoriaCanais.deuErro(zarkCtxJogo, ch.id)
                if (jogoEsperando(j) || candidatosJogo.size < 2 || trocasAuto >= minOf(5, candidatosJogo.size - 1)) return@LaunchedEffect
                delay(1_500)
                trocasAuto++
                jogoCanalIdx = (jogoCanalIdx + 1) % candidatosJogo.size
                val prox = candidatosJogo[jogoCanalIdx]
                vm.showToast("Esse canal não abriu. Tentando: ${prox.name}")
                vm.withChannelAccess(prox) { vm.tune(prox) }
            }
            else -> Unit
        }
    }
    // ZarkTV: canal comum (fora dos jogos) que deu erro passa sozinho para outra qualidade
    // do mesmo canal (FHD → HD → SD…). Tenta cada uma no máximo uma vez; o que já funcionou vem primeiro.
    val qualidadesTentadas = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var baseTentada by remember { mutableStateOf("") }
    LaunchedEffect(playerStatus, current?.id) {
        if (jogoAberto != null || vm.tocandoFilme || vm.player.bloqueioConta != null) return@LaunchedEffect
        if (mode == Mode.PLAYBACK || mode == Mode.VOD || mode == Mode.RECORDINGS || mode == Mode.MULTIVIEW) return@LaunchedEffect
        val ch = current ?: return@LaunchedEffect
        val base = Qualidades.base(ch.name)
        if (base != baseTentada) { baseTentada = base; qualidadesTentadas.clear() }
        when (playerStatus) {
            is PlayerStatus.Playing -> MemoriaCanais.funcionou(zarkCtxJogo, ch.id)
            is PlayerStatus.Error -> {
                MemoriaCanais.deuErro(zarkCtxJogo, ch.id)
                if (ch.id !in qualidadesTentadas) qualidadesTentadas.add(ch.id)
                val opcoes = Qualidades.variantes(ch, allChannels, { it.name }, { it.id })
                val prox = MemoriaCanais.ordenar(zarkCtxJogo, opcoes) { it.id }
                    .firstOrNull { it.id !in qualidadesTentadas } ?: return@LaunchedEffect
                delay(1_500)
                qualidadesTentadas.add(prox.id)
                vm.showToast("Não abriu em ${Qualidades.rotulo(ch.name)}. Tentando ${Qualidades.rotulo(prox.name)}…")
                vm.withChannelAccess(prox) { vm.tune(prox) }
            }
            else -> Unit
        }
    }
    var homePreview by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    /** Filme/série aberto direto da tela principal: Voltar retorna para ela. */
    var vodDeInicio by remember { mutableStateOf(false) }
    /** ZarkTV: filme/série aberto pela busca: Voltar retorna para a busca. */
    var vodDaBusca by remember { mutableStateOf(false) }
    /** Link do filme/episódio tocando agora (para abrir no player externo). */
    var vodUrlAtual by remember { mutableStateOf<String?>(null) }
    var vodPreviewItem by remember { mutableStateOf<VodEntity?>(null) }
    var vodPreviewShow by remember { mutableStateOf<VodEntity?>(null) }
    /** ZarkTV: lembrete de jogo que acabou de começar (aviso na tela). */
    var lembreteAviso by remember { mutableStateOf<io.github.axon07.channelbox.data.ZarkLembretes.Lembrete?>(null) }
    var vodPosterAtual by remember { mutableStateOf<String?>(null) }
    /** ZarkTV: na tela cheia, ◀ na lista de canais abre os grupos ao lado (sem sair da TV). */
    var gruposAbertos by remember { mutableStateOf(false) }
    var grupoIndex by remember { mutableIntStateOf(0) }
    val filtrosLista by vm.filters.collectAsStateWithLifecycle()
    val zarkCtx = LocalContext.current
    var ajustesVolta by remember { mutableStateOf(Mode.HOME) }
    // ZarkTV Mobile: tela cheia vira o celular deitado (como na UniTV); fora dela, gira livre.
    val atividade = LocalContext.current as? android.app.Activity
    LaunchedEffect(mode) {
        if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
            // Em pé no app; na tela cheia (canal, filme ou série) vira deitado, como no Web Play.
            val cheia = mode == Mode.PLAYER || mode == Mode.PLAYBACK || mode == Mode.CHANNEL_LIST || mode == Mode.GROUPS ||
                mode == Mode.PROGRAM_MENU || mode == Mode.OPTIONS
            atividade?.requestedOrientation = if (cheia) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    var sairAberto by remember { mutableStateOf(false) }
    var tutorialVisto by remember { mutableStateOf(zarkCtx.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE).getBoolean("tutorial_visto", false) || io.github.axon07.channelbox.system.Aparelho.soCelular) } // o tutorial é do controle remoto
    var playerExterno by remember { mutableStateOf(io.github.axon07.channelbox.data.ZarkConfig.playerExterno(zarkCtx)) }
    /** When the groups menu was opened (for [groupsBack]). */
    var groupsOpenedAt by remember { mutableLongStateOf(0L) }
    /** When Back was pressed on the side menu: a second Back soon after exits. */
    var exitArmedAt by remember { mutableLongStateOf(0L) }
    val vodBrowser = remember { VodBrowser() }
    val movieCount by vm.movieCount.collectAsStateWithLifecycle()
    val showCount by vm.showCount.collectAsStateWithLifecycle()
    /** Movie/episode waiting for "Resume or start over?", with where you stopped. */
    var vodResume by remember { mutableStateOf<Triple<VodEntity, VodEntity?, Long>?>(null) }
    var pixAberto by remember { mutableStateOf(false) }
    // Celular: Configurações avançadas/parental abertas a partir de Configurações voltam para lá.
    var settingsDeAjustes by remember { mutableStateOf(false) }
    // Celular: cartão "Verificando atualização…" aberto pelo Perfil.
    var verificandoCel by remember { mutableStateOf(false) }
    var previewPergunta by remember { mutableStateOf<Triple<VodEntity, VodEntity?, Long>?>(null) }
    val scope = rememberCoroutineScope()
    /** Last key press or touch, for closing the guide/menus when idle. */
    var lastInput by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    var digits by remember { mutableStateOf("") }
    var bannerVisible by remember { mutableStateOf(false) }
    var touchBar by remember { mutableStateOf(false) }
    /** Remote: the live control button that's highlighted (OK opens the bar), or null. */
    var liveFocus by remember { mutableStateOf<LiveSlot?>(null) }
    var touchBarToken by remember { mutableIntStateOf(0) }
    var listIndex by remember { mutableIntStateOf(0) }
    var groupsIndex by remember { mutableIntStateOf(0) }
    /** Group row to return to when coming back from the shortcut rail. */
    var groupsLastRow by remember { mutableIntStateOf(0) }
    var recIndex by remember { mutableIntStateOf(0) }
    var exitReturn by remember { mutableStateOf(Mode.GROUPS) }
    var updateReturn by remember { mutableStateOf(Mode.PLAYER) }
    var optionsReturn by remember { mutableStateOf(Mode.PLAYER) }
    var optionsToken by remember { mutableIntStateOf(0) }
    var playbackReturn by remember { mutableStateOf(Mode.RECORDINGS) }
    val multi = remember { MultiViewState() }
    var multiPicking by remember { mutableStateOf(false) }
    var multiPickIndex by remember { mutableIntStateOf(0) }
    var streamInfo by remember { mutableStateOf<io.github.axon07.channelbox.player.StreamInfo?>(null) }
    var captionLangs by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var menuProgram by remember { mutableStateOf<ProgramEntity?>(null) }
    var menuRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var playbackTitle by remember { mutableStateOf("") }
    var playbackToken by remember { mutableIntStateOf(0) }
    var playbackOverlay by remember { mutableStateOf(true) }
    /** Remote: the movie / recording control button that's highlighted (▼), or null. */
    var pbFocus by remember { mutableStateOf<PlaybackSlot?>(null) }
    var pbPosition by remember { mutableLongStateOf(0L) }
    var pbDuration by remember { mutableLongStateOf(0L) }
    var pbPlaying by remember { mutableStateOf(false) }
    var pbBuffered by remember { mutableLongStateOf(0L) }
    var pbBuffering by remember { mutableStateOf(false) }
    var pbEnded by remember { mutableStateOf(false) }
    var pbSubtitle by remember { mutableStateOf<String?>(null) }
    /** Total of quick ◀ ▶ presses, shown as "−20 s" for a moment. */
    var pbSkipped by remember { mutableLongStateOf(0L) }
    var pbSkipToken by remember { mutableIntStateOf(0) }
    /** Bumped when captions are switched, to refresh the CC icon. */
    var captionsTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val groupsListState = rememberLazyListState()
    val recListState = rememberLazyListState()
    val guide = remember { GuideState() }
    val ok = remember { OkKeyTracker() }
    val rootFocus = remember { FocusRequester() }
    val activity = LocalContext.current.findActivity()
    val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }

    // Pop-ups over any screen: PIN pad, a menu of actions, or a text box.
    val pinRequest by vm.pinRequest.collectAsStateWithLifecycle()
    var popup by remember { mutableStateOf<Popup?>(null) }
    var textInput by remember { mutableStateOf<TextInput?>(null) }
    val overlayOpen = pinRequest != null || popup != null || textInput != null
    val weOwnKeys = mode in OWN_KEY_MODES && !overlayOpen
    LaunchedEffect(overlayOpen) {
        if (!overlayOpen && mode in OWN_KEY_MODES) {
            delay(32)
            runCatching { rootFocus.requestFocus() }
        }
    }

    // ---- Navigation helpers --------------------------------------------------------------------

    fun exitApp() {
        vm.player.stop()
        activity?.finishAndRemoveTask()
    }

    fun openGuide() {
        guide.open(visible, current?.id, vm)
        mode = Mode.GUIDE
    }

    /** Opens the groups menu on the current group, or on shortcut tile [tile] (see the layout below). */
    fun openGroups(tile: Int? = null) {
        val rows = vm.revealInMenu(filter) // opens the playlist section the current group is in
        groupsLastRow = rows.indexOfFirst { it is MenuRow.Item && it.filter == filter }.coerceAtLeast(0)
        groupsIndex = if (tile != null) rows.size + tile else groupsLastRow
        groupsOpenedAt = android.os.SystemClock.uptimeMillis()
        mode = Mode.GROUPS
    }

    fun openList() {
        grupoIndex = filtrosLista.indexOf(filter).coerceAtLeast(0)
        gruposAbertos = false
        listIndex = visible.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        mode = Mode.CHANNEL_LIST
    }

    fun askExit(returnTo: Mode) {
        // ZarkTV: a pergunta aparece por cima da tela atual (não troca de tela nem abre canal atrás).
        exitReturn = returnTo
        sairAberto = true
    }

    /** Back on the side menu – the last step before leaving. */
    fun lastBack() {
        // ZarkTV: do menu lateral, Voltar leva à tela inicial (lá, Voltar pergunta se quer sair).
        if (settings.hasSource) {
            mode = Mode.HOME
            return
        }
        when (settings.backAction) {
            BackAction.ASK_EXIT -> {
                val t = android.os.SystemClock.uptimeMillis()
                if (t - exitArmedAt < EXIT_AGAIN_MS) {
                    exitArmedAt = 0L
                    exitApp()
                } else {
                    exitArmedAt = t
                    vm.showToast("Aperte Voltar de novo para sair")
                }
            }
            BackAction.OPEN_GUIDE -> mode = Mode.GUIDE // never exit: go round to the guide
            BackAction.EXIT -> exitApp()
        }
    }

    /**
     * Back in the groups menu: Guide → Back shows the groups, Back again the side menu
     * (Search, Live TV, Movies…), Back again "press Back again to exit".
     */
    fun groupsBack() {
        val rows = menuRows.size
        if (groupsIndex < rows) {
            groupsLastRow = groupsIndex
            groupsIndex = rows + 6 // the Live TV tile on the side menu
        } else {
            lastBack()
        }
    }

    fun openMultiView() {
        multi.start(vm, current)
        // Start with the current channel and straight away pick a second one: 2 side by side.
        multi.pickTarget = multi.count
        multiPickIndex = 0
        multiPicking = true
        mode = Mode.MULTIVIEW
    }

    /** Leaves multi-view; [watch] = the channel to continue full screen. */
    fun closeMultiView(watch: ChannelEntity? = null) {
        multi.release()
        multiPicking = false
        if (watch != null) vm.tune(watch) else vm.resumeLive()
        mode = Mode.PLAYER
    }

    /** Pick a channel for [tile] (== count adds a tile). */
    fun pickForTile(tile: Int) {
        if (tile >= multi.count && !multi.canAdd) {
            vm.showToast("Up to ${MultiViewState.MAX} channels at once")
            return
        }
        multi.pickTarget = tile
        multiPickIndex = multi.channels.getOrNull(tile)?.let { ch -> visible.indexOfFirst { it.id == ch.id } }?.coerceAtLeast(0) ?: 0
        multiPicking = true
    }

    /** Hold OK / long-press on a tile. */
    fun multiOptions(tile: Int) {
        val ch = multi.channels.getOrNull(tile) ?: return
        multi.select(tile)
        popup = Popup(
            ch.name, "Multi-view",
            buildList {
                add(DialogAction("Watch full screen") {
                    popup = null
                    closeMultiView(ch)
                })
                add(DialogAction("Change channel") {
                    popup = null
                    pickForTile(tile)
                })
                if (multi.canAdd) add(DialogAction("Add a channel") {
                    popup = null
                    pickForTile(multi.count)
                })
                if (multi.count > 1) add(DialogAction("Remove") {
                    popup = null
                    multi.remove(tile)
                })
                add(DialogAction("Close") { popup = null })
            },
        )
    }

    fun toggleCaptions() {
        if (vm.player.captionLanguages().isEmpty()) {
            vm.showToast("No subtitles or captions on this channel")
        } else {
            vm.showToast(if (vm.player.toggleCaptions()) "Captions on" else "Captions off")
        }
    }

    // Groups menu layout: rows 0 until F (playlist headings and groups), then Multi-view F,
    // Recordings F+1, Settings F+2, Movies F+3, TV Shows F+4 (shown above the shortcuts).
    val rowCount = menuRows.size
    val tileMovies = rowCount + 3
    val tileShows = rowCount + 4
    val tileSearch = rowCount + 5
    val tileLive = rowCount + 6
    val guideSearch = remember { GuideSearchState() }

    fun openSearch() {
        mode = Mode.SEARCH
    }
    // The shortcut rail, top to bottom (matches GroupsPanel).
    val railTiles = listOfNotNull(
        tileSearch, tileLive, tileMovies.takeIf { movieCount > 0 }, tileShows.takeIf { showCount > 0 },
        rowCount + 1, rowCount + 2, // Recordings, Settings (Multi-view: hold OK or the green button while watching)
    )

    fun openVod(kind: String) {
        vodBrowser.open(kind)
        mode = Mode.VOD
    }

    /** Settings, behind the PIN when that's turned on. */
    fun openSettings() {
        if (settings.pinForSettings) vm.requirePin("Digite sua senha para abrir as configurações") { mode = Mode.SETTINGS }
        else mode = Mode.SETTINGS
    }

    fun selectGroupItem(i: Int) {
        when {
            i == tileMovies -> openVod(VodKind.MOVIE)
            i == tileShows -> openVod(VodKind.SERIES)
            i == tileSearch -> openSearch()
            // Live TV: back to the guide on the group you were on.
            i == tileLive -> mode = Mode.GUIDE
            i < rowCount -> when (val row = menuRows[i]) {
                is MenuRow.Header -> if (row.collapsible) vm.toggleSection(row.key)
                is MenuRow.Item -> {
                    vm.setFilter(row.filter)
                    guide.row = 0
                    mode = Mode.GUIDE
                }
            }
            i == rowCount -> openMultiView()
            i == rowCount + 1 -> {
                recIndex = 0
                mode = Mode.RECORDINGS
            }
            else -> openSettings()
        }
    }

    /** Hold OK on a channel → change how it shows up. */
    var organiseStart by remember { mutableStateOf<String?>(null) }
    var organiseReturn by remember { mutableStateOf(Mode.GROUPS) }

    /** Opens the organiser on the groups list, or on one group's channels ([group] = playlist's name). */
    fun openOrganise(group: String?) {
        popup = null
        organiseStart = group
        organiseReturn = if (mode == Mode.ORGANISE) organiseReturn else mode
        mode = Mode.ORGANISE
    }

    fun editChannel(ch: ChannelEntity) {
        val locked = vm.isLocked(ch)
        popup = Popup(
            ch.name, "Channel ${ch.number}",
            listOf(
                DialogAction("Select several…") { openOrganise(vm.originalGroup(ch.groupTitle)) },
                DialogAction("Rename") {
                    popup = null
                    textInput = TextInput("Rename ${ch.name}", ch.name, "Leave empty to use the playlist's name", false) {
                        vm.renameChannel(ch, it)
                    }
                },
                DialogAction("Change number") {
                    popup = null
                    textInput = TextInput("Channel number for ${ch.name}", ch.number.toString(), "Leave empty to use the playlist's number", true) {
                        vm.setChannelNumber(ch, it.toIntOrNull())
                    }
                },
                DialogAction("Hide") {
                    popup = null
                    vm.setChannelHidden(ch, true)
                },
                DialogAction(if (locked) "Unlock" else "Lock (PIN)") {
                    popup = null
                    vm.setChannelLocked(ch, !locked)
                },
                DialogAction("Close") { popup = null },
            ),
        )
    }

    /** Hold OK on a group in the groups menu. */
    fun editGroup(group: String) {
        val label = if (group == ChannelFilter.UNGROUPED) "Other channels" else group
        val locked = vm.isGroupLocked(group)
        popup = Popup(
            label, "Group",
            listOf(
                DialogAction("Rename") {
                    popup = null
                    textInput = TextInput("Rename $label", group, "Leave empty to use the playlist's name", false) {
                        vm.renameGroup(group, it)
                    }
                },
                DialogAction("Select channels…") { openOrganise(vm.originalGroup(group)) },
                DialogAction("Organise groups…") { openOrganise(null) },
                DialogAction("Move up") { vm.moveGroup(group, -1); if (groupsIndex > 0) groupsIndex-- },
                DialogAction("Move down") { vm.moveGroup(group, 1); groupsIndex++ },
                DialogAction("Hide") {
                    popup = null
                    vm.setGroupHidden(group, true)
                },
                DialogAction(if (locked) "Unlock" else "Lock (PIN)") {
                    popup = null
                    vm.setGroupLocked(group, !locked)
                },
                DialogAction("Close") { popup = null },
            ),
        )
    }

    fun openProgramMenu(channel: ChannelEntity, program: ProgramEntity?) {
        menuChannel = channel
        menuProgram = program
        mode = Mode.PROGRAM_MENU
    }

    fun tuneAndWatch(channel: ChannelEntity) {
        vm.tune(channel)
        mode = Mode.PLAYER
    }

    fun openOptions() {
        optionsReturn = mode
        optionsToken++
        mode = Mode.OPTIONS
    }

    /** Plays a past programme from the provider's archive. */
    fun watchCatchUp(channel: ChannelEntity, program: ProgramEntity) = vm.withChannelAccess(channel) {
        if (vm.playCatchUp(channel, program)) {
            playbackTitle = program.title
            pbSubtitle = "Catch-up · ${channel.name} · " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
            ).format(java.util.Date(program.start))
            playbackReturn = Mode.GUIDE
            playbackToken++
            mode = Mode.PLAYBACK
        } else {
            vm.showToast("Catch-up isn't available for this channel")
        }
    }

    /** OK / tap on a programme: on now → watch, finished → catch-up, future → record menu. */
    fun programPressed(channel: ChannelEntity, program: ProgramEntity?) {
        val t = System.currentTimeMillis()
        when {
            program == null || (program.start <= t && t < program.stop) -> tuneAndWatch(channel)
            program.stop <= t ->
                if (vm.canCatchUp(channel, program)) watchCatchUp(channel, program)
                else vm.showToast("This programme has finished – the channel has no catch-up")
            else -> openProgramMenu(channel, program)
        }
    }

    fun playRecordingNow(r: RecordingEntity) {
        playbackTitle = r.title
        pbSubtitle = "Recording · ${r.channelName}"
        playbackReturn = Mode.RECORDINGS
        vm.playRecording(r)
        playbackToken++
        mode = Mode.PLAYBACK
    }

    fun openRecording(r: RecordingEntity) {
        val path = r.filePath
        // Network recordings (smb://, WebDAV) aren't local files: they're playable once they have a path.
        val playable = path != null && (!path.startsWith("/") || File(path).length() > 0)
        if (playable) scope.launch {
            vm.withChannelAccess(vm.channelOf(r)) { playRecordingNow(r) }
        } else {
            menuRecording = r
            mode = Mode.RECORDING_MENU
        }
    }

    fun leavePlayback() {
        if (playbackReturn == Mode.VOD && (vodBrowser.movie != null || vodBrowser.show != null) && vodPreviewItem != null) {
            // Igual à UniTV: ao sair da tela cheia o vídeo continua tocando na miniatura da ficha.
            vodBrowser.previewId = vodPreviewItem?.id
            vodBrowser.focusToken++
        } else if (playbackReturn == Mode.VOD) {
            vm.stopVod()
            vodBrowser.focusToken++
            vm.player.stop() // back to browsing; live TV resumes when the library is closed
            vodBrowser.previewId = null
        } else {
            vm.resumeLive()
        }
        mode = playbackReturn
    }

    fun startVod(item: VodEntity, show: VodEntity?, at: Long) {
        vodResume = null
        vodPreviewItem = item
        vodPreviewShow = show
        vodUrlAtual = item.url
        vodPosterAtual = show?.poster ?: item.poster
        // ZarkTV: cliente escolheu player externo (VLC, MX…) para filmes e séries.
        if (playerExterno && item.url != null &&
            io.github.axon07.channelbox.data.ZarkConfig.abrirExterno(zarkCtx, item.url, show?.title ?: item.title)
        ) return
        playbackTitle = show?.title ?: item.title
        pbSubtitle = if (show != null) {
            legendaEpisodio(item, show)
        } else listOfNotNull(item.year, item.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }).joinToString(" · ").ifEmpty { null }
        playbackReturn = Mode.VOD
        vm.player.exo.stop()
        vm.zarkAplicarPreferencias()
        vm.playVod(item, at, show?.id)
        playbackToken++
        mode = Mode.PLAYBACK
    }

    /** ZarkTV: toca na miniatura da ficha (sem tela cheia). Se já assistiu parte, pergunta antes. */
    fun previewVod(item: VodEntity, show: VodEntity?, inicio: Long? = null) {
        scope.launch {
            val salvo = vm.vodResumeAt(item)
            if (inicio == null && salvo != null && salvo > 0) {
                previewPergunta = Triple(item, show, salvo)
                return@launch
            }
            val at = inicio ?: salvo ?: 0L
            previewPergunta = null
            vodUrlAtual = item.url
            vodPosterAtual = show?.poster ?: item.poster
            vodPreviewShow = show
            vodPreviewItem = item
            vodBrowser.previewId = item.id
            vodBrowser.previewDono = show?.id ?: item.id
            vm.player.exo.stop()
            vm.zarkAplicarPreferencias()
            vm.playVod(item, at, show?.id)
        }
    }

    /** Da miniatura para a tela cheia, continuando de onde está. */
    fun previewTelaCheia() {
        val item = vodPreviewItem ?: return
        val show = vodPreviewShow
        playbackTitle = show?.title ?: item.title
        pbSubtitle = if (show != null) {
            legendaEpisodio(item, show)
        } else listOfNotNull(item.year, item.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }).joinToString(" · ").ifEmpty { null }
        playbackReturn = Mode.VOD
        playbackToken++
        mode = Mode.PLAYBACK
    }

    fun playVod(item: VodEntity, show: VodEntity?) {
        scope.launch {
            val at = vm.vodResumeAt(item)
            if (at != null) vodResume = Triple(item, show, at) else startVod(item, show, 0)
        }
    }

    fun seekBy(delta: Long) {
        val exo = vm.player.exo
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + delta).coerceIn(0L, duration))
        pbPosition = exo.currentPosition
        pbSkipped += delta
        pbSkipToken++
        playbackToken++
    }

    fun seekTo(position: Long) {
        vm.player.exo.seekTo(position.coerceAtLeast(0))
        pbPosition = position
        playbackToken++
    }

    /**
     * Play / pause. Uses "wants to play" rather than "is playing", so it works while the video is
     * loading, and starts over when it has finished or retries after an error.
     */
    fun togglePause() {
        val exo = vm.player.exo
        when {
            exo.playbackState == androidx.media3.common.Player.STATE_ENDED -> {
                exo.seekTo(0)
                exo.play()
            }
            exo.playbackState == androidx.media3.common.Player.STATE_IDLE -> {
                exo.prepare()
                exo.play()
            }
            exo.playWhenReady -> exo.pause()
            else -> exo.play()
        }
        pbPlaying = exo.playWhenReady
        playbackToken++
    }

    fun commitDigits() {
        val n = digits.toIntOrNull()
        digits = ""
        if (n == null) return
        if (mode == Mode.GUIDE) {
            val i = visible.indexOfFirst { it.number == n }
            if (i >= 0) guide.row = i
        } else {
            vm.tuneNumber(n)
        }
    }

    // ---- Remote control ------------------------------------------------------------------------

    val pipSupported = io.github.axon07.channelbox.system.SetTopBox.supportsPip(LocalContext.current)

    fun pressPlaybackSlot(slot: PlaybackSlot) {
        playbackToken++
        when (slot) {
            PlaybackSlot.REWIND -> seekBy(-10_000L)
            PlaybackSlot.PLAY -> togglePause()
            PlaybackSlot.FORWARD -> seekBy(30_000L)
            PlaybackSlot.CAPTIONS -> { toggleCaptions(); captionsTick++ }
            PlaybackSlot.OPTIONS -> { pbFocus = null; openOptions() }
        }
    }

    fun pressLiveSlot(slot: LiveSlot) {
        val cur = current ?: return
        touchBarToken++
        when (slot) {
            LiveSlot.FAVORITE -> vm.toggleFavorite(cur)
            LiveSlot.RECORD -> vm.toggleRecordCurrent()
            LiveSlot.REWIND -> vm.skipLive(-REWIND_STEP)
            LiveSlot.PAUSE -> vm.playPauseLive()
            LiveSlot.LIVE -> { vm.goLive(); liveFocus = LiveSlot.PAUSE }
            LiveSlot.CAPTIONS -> { toggleCaptions(); captionsTick++ }
            LiveSlot.MULTIVIEW -> { touchBar = false; openMultiView() }
            LiveSlot.PIP -> { touchBar = false; activity?.let { io.github.axon07.channelbox.system.SetTopBox.enterPip(it) } }
            LiveSlot.OPTIONS -> { touchBar = false; openOptions() }
        }
    }

    /** Moves the live-controls highlight left / right. */
    fun moveLiveFocus(delta: Int) {
        val slots = liveSlots(shift != null, pipSupported, vm.canRewind(current))
        val i = slots.indexOf(liveFocus).coerceAtLeast(0)
        liveFocus = slots[(i + delta).coerceIn(0, slots.lastIndex)]
        touchBarToken++
    }

    fun onShortOk() {
        when (mode) {
            Mode.PLAYER -> when {
                liveFocus != null && touchBar -> liveFocus?.let { pressLiveSlot(it) }
                digits.isNotEmpty() -> commitDigits()
                allChannels.isEmpty() -> mode = Mode.SETTINGS
                // ZarkTV: OK na tela cheia abre a lista de canais do lado esquerdo (como no UniTV).
                current != null -> {
                    bannerVisible = false
                    openList()
                }
                else -> openList()
            }
            Mode.CHANNEL_LIST -> if (gruposAbertos) {
                filtrosLista.getOrNull(grupoIndex)?.let { vm.setFilter(it) }
                listIndex = 0
                gruposAbertos = false
            } else visible.getOrNull(listIndex)?.let { tuneAndWatch(it) }
            Mode.GUIDE -> {
                if (digits.isNotEmpty()) {
                    commitDigits()
                } else {
                    val ch = visible.getOrNull(guide.row)
                    if (ch != null) programPressed(ch, guide.programAt(ch, guidePrograms))
                }
            }
            Mode.GROUPS -> selectGroupItem(groupsIndex)
            Mode.RECORDINGS -> recordings.getOrNull(recIndex)?.let { openRecording(it) }
            Mode.PLAYBACK -> pbFocus?.let { pressPlaybackSlot(it) } ?: togglePause()
            Mode.MULTIVIEW -> if (multiPicking) {
                visible.getOrNull(multiPickIndex)?.let { ch -> vm.withChannelAccess(ch) { multi.assign(vm, multi.pickTarget, ch) } }
                multiPicking = false
            } else {
                pickForTile(multi.focused)
            }
            else -> Unit
        }
    }

    fun onLongOk() {
        when (mode) {
            Mode.PLAYER -> openOptions()
            Mode.CHANNEL_LIST -> visible.getOrNull(listIndex)?.let { vm.toggleFavorite(it) }
            Mode.GUIDE -> visible.getOrNull(guide.row)?.let { openProgramMenu(it, guide.programAt(it, guidePrograms)) }
            Mode.RECORDINGS -> recordings.getOrNull(recIndex)?.let {
                menuRecording = it
                mode = Mode.RECORDING_MENU
            }
            Mode.MULTIVIEW -> if (!multiPicking) multiOptions(multi.focused)
            Mode.GROUPS -> ((menuRows.getOrNull(groupsIndex) as? MenuRow.Item)?.filter as? ChannelFilter.Group)?.let {
                editGroup(it.name)
            }
            else -> Unit
        }
    }

    /** Repeat count of the key being handled (0 = a fresh press). */
    var keyRepeat = 0
    var lastVerticalAt = 0L

    /**
     * True for a new ▲ / ▼ press rather than a held key. Also goes by the gap since the last press,
     * for remotes that misreport the repeat count (a held key repeats every ~50 ms).
     */
    fun freshVerticalPress(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val fresh = keyRepeat == 0 || now - lastVerticalAt > 700
        lastVerticalAt = now
        return fresh
    }

    fun onKeyDown(code: Int): Boolean {
        if (mode == Mode.PLAYER || mode == Mode.CHANNEL_LIST || mode == Mode.GUIDE) {
            digitOf(code)?.let { d ->
                if (mode == Mode.CHANNEL_LIST) mode = Mode.PLAYER
                digits = (digits + d).takeLast(4)
                return true
            }
        }
        val up = if (settings.invertChannelKeys) -1 else 1
        // Remote on the live control buttons: ◀ ▶ move, ▲ leaves, OK presses (in onShortOk).
        if (mode == Mode.PLAYER && liveFocus != null && touchBar) {
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { moveLiveFocus(-1); return true }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { moveLiveFocus(1); return true }
                AndroidKeyEvent.KEYCODE_DPAD_UP -> { liveFocus = null; touchBar = false; return true }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { touchBarToken++; return true }
            }
        }
        // Remote on the movie / recording buttons.
        if (mode == Mode.PLAYBACK && pbFocus != null) {
            val slots = PlaybackSlot.entries
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { pbFocus = slots[(pbFocus!!.ordinal - 1).coerceAtLeast(0)]; playbackToken++; return true }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { pbFocus = slots[(pbFocus!!.ordinal + 1).coerceAtMost(slots.lastIndex)]; playbackToken++; return true }
                AndroidKeyEvent.KEYCODE_DPAD_UP -> { pbFocus = null; playbackToken++; return true }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { playbackToken++; return true }
            }
        }
        when (mode) {
            Mode.PLAYER -> when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> vm.zap(up)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> vm.zap(-up)
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> vm.zap(1)
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> vm.zap(-1)
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> openList()
                // Like TiviMate: ▶ goes back to the channel you watched before (again to swap back).
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> vm.previousChannel()
                AndroidKeyEvent.KEYCODE_INFO -> {
                    if (bannerVisible) bannerVisible = false else vm.showBanner()
                }
                AndroidKeyEvent.KEYCODE_GUIDE -> openGuide()
                AndroidKeyEvent.KEYCODE_SEARCH -> openSearch()
                AndroidKeyEvent.KEYCODE_MENU -> openOptions()
                AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> vm.previousChannel()
                in RECORD_KEYS -> vm.toggleRecordCurrent()
                AndroidKeyEvent.KEYCODE_CAPTIONS -> toggleCaptions()
                AndroidKeyEvent.KEYCODE_PROG_GREEN -> openMultiView()
                // Pause live TV
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> vm.playPauseLive()
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (shift?.paused != true) vm.playPauseLive()
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (shift?.paused == true) vm.playPauseLive()
                // ◀◀ / ▶▶ rewind live TV through the provider's archive (channels with catch-up).
                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> vm.skipLive(-REWIND_STEP)
                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                    if (shift?.archiveFrom != null) vm.skipLive(REWIND_STEP) else if (shift != null) vm.goLive() else return false
                AndroidKeyEvent.KEYCODE_MEDIA_STOP ->
                    if (shift != null) vm.goLive() else return false
                else -> return false
            }
            Mode.CHANNEL_LIST -> if (gruposAbertos) {
                val n = filtrosLista.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (n > 0) grupoIndex = (grupoIndex - 1).mod(n)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (n > 0) grupoIndex = (grupoIndex + 1).mod(n)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> gruposAbertos = false
                    else -> Unit
                }
            } else {
                val size = visible.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (size > 0) listIndex = (listIndex - 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (size > 0) listIndex = (listIndex + 1).mod(size)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                        listIndex = (listIndex - 8).coerceAtLeast(0)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                        listIndex = (listIndex + 8).coerceAtMost((size - 1).coerceAtLeast(0))
                    // ZarkTV: ◀ abre os grupos de canais.
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        grupoIndex = filtrosLista.indexOf(filter).coerceAtLeast(0)
                        gruposAbertos = true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { vm.cycleFilter(1); listIndex = 0 }
                    AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_MENU -> openGuide()
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { vm.previousChannel(); mode = Mode.PLAYER }
                    else -> return false
                }
            }
            Mode.GUIDE -> {
                val channel = visible.getOrNull(guide.row)
                when (code) {
                    // Top ↔ bottom wrap on a fresh press (not while holding the key).
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> guide.moveVertical(-1, visible.size, wrap = keyRepeat == 0)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> guide.moveVertical(1, visible.size, wrap = keyRepeat == 0)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> guide.moveVertical(-7, visible.size, wrap = keyRepeat == 0)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> guide.moveVertical(7, visible.size, wrap = keyRepeat == 0)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> guide.moveHorizontal(-1, channel, guidePrograms, vm)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> guide.moveHorizontal(1, channel, guidePrograms, vm)
                    AndroidKeyEvent.KEYCODE_GUIDE -> mode = Mode.PLAYER
                    AndroidKeyEvent.KEYCODE_MENU -> openGroups()
                    in RECORD_KEYS -> {
                        val p = guide.programAt(channel, guidePrograms)
                        if (channel != null && p != null) vm.toggleRecordProgram(channel, p)
                    }
                    else -> return false
                }
            }
            Mode.GROUPS -> when (code) {
                // Groups list on the right, shortcut rail on the left (see GroupsPanel).
                // In the groups list, a fresh ▲ on the first group jumps to the last, and ▼ on the
                // last jumps to the first (holding the key stops at the end).
                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                    val r = railTiles.indexOf(groupsIndex)
                    val fresh = freshVerticalPress() // every press, so held keys are told apart
                    groupsIndex = when {
                        r >= 0 -> railTiles[(r - 1).coerceAtLeast(0)]
                        groupsIndex == 0 && rowCount > 1 && fresh -> rowCount - 1
                        else -> (groupsIndex - 1).coerceAtLeast(0)
                    }
                }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    val r = railTiles.indexOf(groupsIndex)
                    val fresh = freshVerticalPress() // every press, so held keys are told apart
                    val last = (rowCount - 1).coerceAtLeast(0)
                    groupsIndex = when {
                        r >= 0 -> railTiles[(r + 1).coerceAtMost(railTiles.size - 1)]
                        groupsIndex == last && rowCount > 1 && fresh -> 0
                        else -> (groupsIndex + 1).coerceAtMost(last)
                    }
                }
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                    if (groupsIndex < rowCount) groupsIndex = (groupsIndex - 8).coerceAtLeast(0)
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                    if (groupsIndex < rowCount) groupsIndex = (groupsIndex + 8).coerceAtMost((rowCount - 1).coerceAtLeast(0))
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> when {
                    // On a closed playlist heading: open it.
                    (menuRows.getOrNull(groupsIndex) as? MenuRow.Header)?.let { it.collapsible && !it.open } == true ->
                        vm.setSectionOpen((menuRows[groupsIndex] as MenuRow.Header).key, true)
                    // From the rail back into the groups.
                    groupsIndex >= rowCount -> groupsIndex = groupsLastRow.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
                    else -> mode = Mode.GUIDE
                }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> when {
                    // On a playlist heading or one of its groups: close the section, landing on its heading.
                    groupsIndex < rowCount && run {
                        val row = menuRows[groupsIndex]
                        val key = (row as? MenuRow.Header)?.key ?: (row as MenuRow.Item).section
                        val head = menuRows.indexOfFirst { it is MenuRow.Header && it.key == key && it.collapsible && it.open }
                        if (head >= 0) {
                            vm.setSectionOpen(key, false)
                            groupsIndex = head
                        }
                        head >= 0
                    } -> Unit
                    // From the groups out to the rail.
                    groupsIndex < rowCount -> {
                        groupsLastRow = groupsIndex
                        groupsIndex = railTiles.first()
                    }
                    else -> Unit
                }
                AndroidKeyEvent.KEYCODE_MENU -> mode = Mode.GUIDE
                AndroidKeyEvent.KEYCODE_GUIDE -> mode = Mode.PLAYER
                else -> return false
            }
            Mode.RECORDINGS -> {
                val size = recordings.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (size > 0) recIndex = (recIndex - 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (size > 0) recIndex = (recIndex + 1).mod(size)
                    AndroidKeyEvent.KEYCODE_MENU -> recordings.getOrNull(recIndex)?.let {
                        menuRecording = it
                        mode = Mode.RECORDING_MENU
                    }
                    AndroidKeyEvent.KEYCODE_GUIDE -> openGuide()
                    else -> return false
                }
            }
            Mode.PLAYBACK -> when (code) {
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePause()
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { vm.player.exo.play(); playbackToken++ }
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.player.exo.pause(); playbackToken++ }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000L)
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000L)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    pbFocus = PlaybackSlot.PLAY
                    playbackToken++
                }
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> playbackToken++
                AndroidKeyEvent.KEYCODE_MENU -> openOptions()
                AndroidKeyEvent.KEYCODE_CAPTIONS -> toggleCaptions()
                else -> return false
            }
            Mode.MULTIVIEW -> if (multiPicking) {
                val size = visible.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (size > 0) multiPickIndex = (multiPickIndex - 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (size > 0) multiPickIndex = (multiPickIndex + 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { vm.cycleFilter(-1); multiPickIndex = 0 }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { vm.cycleFilter(1); multiPickIndex = 0 }
                    else -> return false
                }
            } else {
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> multi.move(0, -1)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> multi.move(0, 1)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> multi.move(-1, 0)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> multi.move(1, 0)
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_PROG_GREEN -> pickForTile(multi.count)
                    else -> return false
                }
            }
            else -> return false
        }
        return true
    }

    fun handleKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val code = native.keyCode
        if (mode == Mode.MULTIVIEW) multi.hintToken++
        if (code !in HANDLED_KEYS) return false
        val isDown = native.action == AndroidKeyEvent.ACTION_DOWN

        // OK acts on release so a long press can mean something else.
        if (code in OK_KEYS) {
            if (isDown) {
                if (native.repeatCount == 0) {
                    ok.armed = true
                    ok.longFired = false
                } else if (ok.armed && !ok.longFired && (native.isLongPress || native.repeatCount >= 4)) {
                    ok.longFired = true
                    onLongOk()
                }
            } else {
                if (ok.armed && !ok.longFired) onShortOk()
                ok.armed = false
            }
            return true
        }
        keyRepeat = native.repeatCount
        return if (isDown) onKeyDown(code) else true
    }

    // ---- Timers --------------------------------------------------------------------------------

    LaunchedEffect(bannerToken) {
        if (bannerToken > 0) {
            bannerVisible = true
            delay(5_000)
            bannerVisible = false
        }
    }
    // Live stream details for the info banner (resolution, fps, audio, bitrate, captions).
    LaunchedEffect(bannerVisible) {
        while (bannerVisible) {
            streamInfo = vm.player.streamInfo()
            captionLangs = vm.player.captionLanguages()
            delay(1_000)
        }
    }
    LaunchedEffect(touchBar) { if (!touchBar) liveFocus = null }
    LaunchedEffect(playbackOverlay, mode) { if (!playbackOverlay || mode != Mode.PLAYBACK) pbFocus = null }
    LaunchedEffect(touchBarToken) {
        if (touchBarToken > 0) {
            touchBar = true
            delay(6_000)
            touchBar = false
        }
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(2_000)
            commitDigits()
        }
    }
    LaunchedEffect(listIndex, mode, multiPickIndex, multiPicking) {
        if (mode == Mode.CHANNEL_LIST) listState.scrollToItem((listIndex - 3).coerceAtLeast(0))
        if (mode == Mode.MULTIVIEW && multiPicking) listState.scrollToItem((multiPickIndex - 3).coerceAtLeast(0))
    }
    LaunchedEffect(mode) {
        ok.armed = false
        if (weOwnKeys) {
            delay(32)
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(mode) { if (mode != Mode.SETTINGS) settingsFromSetup = false }
    LaunchedEffect(mode) { if (mode != Mode.CHANNEL_LIST) gruposAbertos = false }
    // ZarkTV: saiu dos filmes/séries (por qualquer caminho) = o filme/miniatura para de tocar.
    LaunchedEffect(mode) {
        if (mode != Mode.VOD) previewPergunta = null
        if (mode != Mode.VOD && mode != Mode.PLAYBACK && mode != Mode.OPTIONS && mode != Mode.UPDATE && vodBrowser.previewId != null) {
            vm.stopVod()
            vm.player.stop()
            vodBrowser.previewId = null
            vodPreviewItem = null
        }
    }
    // Live TV doesn't play behind Movies & TV Shows; it comes back when you leave them.
    LaunchedEffect(mode, homeTab, jogoAberto != null, ZarkLayout.leve.value, tutorialVisto) {
        when (mode) {
            // Telas que não são de canais: nada de TV ao vivo tocando por trás.
            Mode.VOD, Mode.PROFILE, Mode.AJUSTES, Mode.SETTINGS, Mode.ORGANISE -> vm.enterLibrary()
            // Tela de entrada (sem conta / saiu da conta): nada toca.
            Mode.SETUP -> { vm.enterLibrary(); vm.player.esquecer() }
            // Tutorial da primeira vez na tela: nada toca até ele terminar.
            Mode.HOME -> if (tutorialVisto && !ZarkLayout.leve.value && !ZarkPlay.ativo && (Aba.temPrevia(homeTab) || (homeTab == Aba.JOGOS && jogoAberto != null && !jogoEsperando(jogoAberto!!)))) vm.leaveLibrary() else vm.enterLibrary()
            Mode.PLAYBACK, Mode.UPDATE -> Unit
            // Audio & subtitles opened from a movie, episode or recording is still part of playback.
            Mode.OPTIONS -> if (optionsReturn != Mode.PLAYBACK) vm.leaveLibrary()
            else -> vm.leaveLibrary()
        }
    }
    // Guide and menus close back to TV after a while with no button pressed.
    val idleModes = mode == Mode.GUIDE || mode == Mode.GROUPS || mode == Mode.CHANNEL_LIST || mode == Mode.PROGRAM_MENU
    LaunchedEffect(mode, lastInput, settings.idleCloseSeconds) {
        val seconds = settings.idleCloseSeconds
        if (!idleModes || seconds <= 0 || current == null) return@LaunchedEffect
        kotlinx.coroutines.delay(seconds * 1000L)
        mode = Mode.PLAYER
    }
    // "Update available" prompt: shown over whatever screen is open (but not during first setup).
    // Keyed on setup too, so a prompt that arrived during setup shows as soon as setup is done.
    LaunchedEffect(updatePrompt, mode == Mode.SETUP) {
        if (updatePrompt && mode != Mode.UPDATE && mode != Mode.SETUP) {
            updateReturn = mode
            mode = Mode.UPDATE
        } else if (!updatePrompt && mode == Mode.UPDATE) {
            mode = updateReturn
        }
    }
    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        delay(3_000)
        vm.clearToast(t.id)
    }
    // Recording playback: poll the position for the progress bar, hide the controls when idle.
    LaunchedEffect(mode) {
        var ticks = 0
        while (mode == Mode.PLAYBACK) {
            if (playbackReturn == Mode.VOD && ++ticks % 60 == 0) vm.saveVodProgress()
            val exo = vm.player.exo
            pbPosition = exo.currentPosition
            pbDuration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            pbBuffered = exo.bufferedPosition
            pbEnded = exo.playbackState == androidx.media3.common.Player.STATE_ENDED
            pbPlaying = exo.playWhenReady && !pbEnded
            pbBuffering = exo.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            delay(250)
        }
    }
    // Séries: terminou o episódio, começa o próximo sozinho (sem perguntar).
    LaunchedEffect(pbEnded) {
        if (!pbEnded || mode != Mode.PLAYBACK || playbackReturn != Mode.VOD) return@LaunchedEffect
        val ep = vodPreviewItem ?: return@LaunchedEffect
        val sh = vodPreviewShow ?: return@LaunchedEffect
        val lista = vodBrowser.episodes.orEmpty()
        val i = lista.indexOfFirst { it.id == ep.id }
        val prox = if (i >= 0) lista.getOrNull(i + 1) else null
        if (prox != null) {
            vm.showToast("Próximo episódio: T${prox.season} E${prox.episode}")
            delay(1_500)
            if (mode == Mode.PLAYBACK) startVod(prox, sh, 0)
        }
    }
    // Celular: assistindo a série na janelinha da ficha, terminou o episódio, começa o próximo sozinho.
    LaunchedEffect(mode) {
        if (!io.github.axon07.channelbox.system.Aparelho.soCelular || mode != Mode.VOD) return@LaunchedEffect
        var avancado: String? = null
        while (mode == Mode.VOD) {
            delay(1_000)
            val ep = vodPreviewItem ?: continue
            val sh = vodPreviewShow ?: continue
            if (vm.player.exo.playbackState != androidx.media3.common.Player.STATE_ENDED || avancado == ep.id) continue
            if (vodBrowser.previewId != ep.id) continue
            val lista = vodBrowser.episodes.orEmpty()
            val prox = lista.getOrNull(lista.indexOfFirst { it.id == ep.id }.takeIf { it >= 0 }?.plus(1) ?: -1) ?: continue
            avancado = ep.id
            vm.showToast("Próximo episódio: T${prox.season} E${prox.episode}")
            delay(1_500)
            if (mode == Mode.VOD) { vodBrowser.focusId = prox.id; previewVod(prox, sh, 0L) }
        }
    }
    LaunchedEffect(playbackToken) {
        playbackOverlay = true
        delay(4_000)
        if (vm.player.exo.playWhenReady) playbackOverlay = false
    }
    LaunchedEffect(pbSkipToken) {
        delay(900)
        pbSkipped = 0
    }

    // ---- Touch / mouse -------------------------------------------------------------------------

    fun onScreenTap() {
        when (mode) {
            Mode.PLAYER -> if (touchBar) {
                touchBar = false
            } else {
                // ZarkTV: um toque na tela cheia mostra os canais do grupo (e o botão Voltar).
                touchBarToken++
                openList()
            }
            Mode.CHANNEL_LIST -> { gruposAbertos = false; mode = Mode.PLAYER }
            Mode.GROUPS -> mode = Mode.GUIDE
            Mode.PROGRAM_MENU -> mode = Mode.GUIDE
            Mode.RECORDING_MENU -> mode = Mode.RECORDINGS
            Mode.EXIT_CONFIRM -> mode = exitReturn
            Mode.UPDATE -> vm.dismissUpdate(skipThisVersion = false)
            Mode.OPTIONS -> mode = optionsReturn
            Mode.PLAYBACK -> playbackToken++
            else -> Unit
        }
    }

    // Swipe up = next channel, swipe down = previous (phones/tablets).
    val onSwipe by rememberUpdatedState<(Float) -> Unit> { total ->
        if (mode == Mode.PLAYER && abs(total) > swipeThreshold) vm.zap(if (total < 0) 1 else -1)
    }

    // ---- Back: TV → Guide → Groups → Exit? ----------------------------------------------------

    BackHandler(enabled = !(mode == Mode.SETUP && (!settings.hasSource || zarkTrocarConta))) {
        if (sairAberto) { sairAberto = false; return@BackHandler }
        when (mode) {
            Mode.PLAYER -> when {
                digits.isNotEmpty() -> digits = ""
                touchBar -> touchBar = false
                bannerVisible -> bannerVisible = false
                else -> if (ZarkLayout.leve.value) mode = Mode.LIVE else { if (jogoAberto == null) { homeTab = Aba.TV; voltouDoCanal.value = true }; mode = Mode.HOME } // ZarkTV: volta para a lista de canais (ou para o jogo)
            }
            Mode.CHANNEL_LIST -> if (gruposAbertos) gruposAbertos = false else mode = Mode.PLAYER
            Mode.GUIDE -> if (ZarkPlay.ativo) mode = Mode.HOME else openGroups()
            Mode.GROUPS -> groupsBack()
            Mode.EXIT_CONFIRM -> mode = exitReturn
            Mode.UPDATE -> vm.dismissUpdate(skipThisVersion = false)
            Mode.OPTIONS -> mode = optionsReturn
            Mode.PROGRAM_MENU -> mode = Mode.GUIDE
            Mode.RECORDINGS -> if (ZarkPlay.ativo) mode = Mode.HOME else openGroups(tile = 1)
            Mode.RECORDING_MENU -> mode = Mode.RECORDINGS
            // First Back hides the controls, the next one leaves.
            Mode.PLAYBACK -> when {
                pbFocus != null -> pbFocus = null
                playbackOverlay && pbPlaying -> playbackOverlay = false
                else -> leavePlayback()
            }
            Mode.MULTIVIEW -> if (multiPicking && multi.count > 0) multiPicking = false else closeMultiView()
            Mode.SETTINGS -> if (!settings.hasSource) mode = Mode.SETUP else mode = when {
                io.github.axon07.channelbox.system.Aparelho.soCelular && settingsDeAjustes -> { settingsDeAjustes = false; Mode.AJUSTES }
                settingsSection != null -> Mode.PROFILE
                else -> Mode.HOME
            }
            Mode.HOME -> if (uniSinoAberto.value) uniSinoAberto.value = false else if (jogoAberto != null) jogoAberto = null else askExit(Mode.HOME)
            Mode.PROFILE -> mode = Mode.HOME
            Mode.AJUSTES -> mode = ajustesVolta
            Mode.LIVE -> mode = Mode.HOME
            Mode.SEARCH -> if (ZarkPlay.ativo) mode = Mode.HOME else openGroups(tile = 5)
            Mode.ORGANISE -> mode = organiseReturn
            Mode.VOD -> when {
                previewPergunta != null -> {
                    previewPergunta = null
                    vodBrowser.focusToken++
                }
                vodResume != null -> {
                    vodResume = null
                    vodBrowser.focusToken++
                }
                vodBrowser.movie != null -> {
                    vodBrowser.movie = null
                    vodBrowser.focusToken++
                    if (vodDaBusca) { vodDaBusca = false; mode = Mode.SEARCH } else if (vodDeInicio) { vodDeInicio = false; mode = Mode.HOME }
                }
                vodBrowser.show != null -> {
                    vodBrowser.closeShow()
                    if (vodDaBusca) { vodDaBusca = false; mode = Mode.SEARCH } else if (vodDeInicio) { vodDeInicio = false; mode = Mode.HOME }
                }
                vodBrowser.query.isNotEmpty() -> vodBrowser.query = ""
                else -> mode = Mode.HOME
            }
            Mode.SETUP -> mode = Mode.SETTINGS
        }
    }
    // Pop-ups close first.
    BackHandler(enabled = overlayOpen) {
        when {
            textInput != null -> textInput = null
            popup != null -> popup = null
            else -> vm.cancelPin()
        }
    }

    // ---- Layout --------------------------------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                lastInput = android.os.SystemClock.uptimeMillis()
                val native = it.nativeKeyEvent
                // After a long OK opened a menu, the rest of that press (repeats and the release)
                // must not reach the menu, or it picks the first item and closes straight away.
                // Holding Back in the guide or groups menu (with a channel playing behind it) goes
                // straight back to the channel full screen, like a cable box.
                if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) backLongFired = false
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && !backLongFired &&
                        (native.isLongPress || native.repeatCount >= 4) &&
                        (mode == Mode.GUIDE || mode == Mode.GROUPS) && current != null
                    ) {
                        backLongFired = true
                        mode = Mode.PLAYER
                    }
                    // The rest of a long press (repeats and the release) mustn't also act as Back.
                    if (backLongFired) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) backLongFired = false
                        return@onPreviewKeyEvent true
                    }
                }
                if (ok.longFired && native.keyCode in OK_KEYS) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        ok.armed = false
                        ok.longFired = false
                    }
                    true
                } else if (weOwnKeys) handleKey(it) else false
            }
            .pointerInput(Unit) {
                // Watch every touch (without consuming it) so the idle timer restarts.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        lastInput = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
            .focusRequester(rootFocus)
            // Only focusable while we handle the remote ourselves, so Settings/dialogs get normal focus.
            .focusable(enabled = weOwnKeys)
            .tap { onScreenTap() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { onSwipe(total) },
                    onDragCancel = { total = 0f },
                ) { change, amount ->
                    total += amount
                    change.consume()
                }
            }
    ) {
        // Always the first child so the view (and its video surface) is never recreated;
        // in the guide it shrinks into the top-right preview window.
        // ZarkTV: fundo das telas com prévia desenhado ANTES do vídeo (senão cobre a janelinha).
        Box(
            if (!inPip && (mode == Mode.HOME || mode == Mode.LIVE)) Modifier.fillMaxSize().background(FundoZark)
            else Modifier.size(0.dp)
        )
        // Ficha de filme/série: fundo com a imagem, também atrás do vídeo (para a miniatura aparecer).
        if (!inPip && mode == Mode.VOD && (vodBrowser.movie != null || vodBrowser.show != null)) {
            Box(Modifier.fillMaxSize().background(Palette.Background)) {
                vodBrowser.fundoUrl?.let { url ->
                    coil.compose.AsyncImage(url, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF20A0A0A), Color(0xB30A0A0A), Color(0x660A0A0A)))))
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0A0A)))))
            }
        }
        AndroidView(
            factory = { ctx ->
                // Fire Stick / Fire TV: TextureView (com SurfaceView a imagem congelava e só tocava o som).
                val fire = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
                    ctx.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
                (if (fire) android.view.LayoutInflater.from(ctx).inflate(io.github.axon07.channelbox.R.layout.zark_player_textura, null) as PlayerView
                 else PlayerView(ctx)).apply {
                    useController = false
                    player = vm.player.exo
                    keepScreenOn = true
                    isFocusable = false
                    isClickable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Keep the last picture when the stream stops (pause live TV, changing channel).
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                // ZarkTV: ao voltar do app minimizado, religa o vídeo na tela (senão só tocava o som).
                if (view.tag != voltouToken) {
                    view.tag = voltouToken
                    view.player = null
                    view.player = vm.player.exo
                }
                // Só a TV ao vivo guarda o último quadro ao trocar de canal; filme/série começa com tela preta
                // (senão aparecia a imagem parada do canal anterior).
                // Legendas: tamanho e estilo escolhidos em Configurações.
                AjustesEstado.legenda.intValue
                view.subtitleView?.let { sv ->
                    val tam = io.github.axon07.channelbox.data.ZarkPrefs.legTamanho(view.context)
                    sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * when (tam) { 0 -> 0.8f; 2 -> 1.35f; else -> 1f })
                    val est = io.github.axon07.channelbox.data.ZarkPrefs.legEstilo(view.context)
                    sv.setApplyEmbeddedStyles(est == 0)
                    sv.setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            if (est == 1) android.graphics.Color.YELLOW else android.graphics.Color.WHITE,
                            if (est == 2) 0xCC000000.toInt() else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            if (est == 2) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK, null,
                        )
                    )
                }
                view.setKeepContentOnPlayerReset(false) // ZarkTV: ao trocar de canal não fica a imagem congelada do canal anterior
                val cheiaCel = io.github.axon07.channelbox.system.Aparelho.soCelular && (mode == Mode.PLAYER || mode == Mode.PLAYBACK) && TelaCelular.encher.value
                view.resizeMode = if (cheiaCel) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else when (settings.videoScale) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = if (inPip) {
                Modifier.fillMaxSize()
            } else if (mode == Mode.MULTIVIEW || mode == Mode.SETTINGS || mode == Mode.AJUSTES || mode == Mode.ORGANISE) {
                VideoEscondido
            } else if (mode == Mode.VOD) {
                val r = vodBrowser.previewRect
                if (r != null && vodBrowser.previewId != null && (vodBrowser.movie != null || vodBrowser.show != null)) {
                    with(LocalDensity.current) {
                        Modifier.align(Alignment.TopStart).offset(r.left.toDp(), r.top.toDp()).size(r.width.toDp(), r.height.toDp())
                    }
                } else VideoEscondido
            } else if (mode == Mode.HOME) {
                val r = homePreview
                if (!ZarkLayout.leve.value && (Aba.temPrevia(homeTab) || (homeTab == Aba.JOGOS && jogoAberto != null)) && r != null) {
                    val d = LocalDensity.current
                    with(d) {
                        Modifier.align(Alignment.TopStart)
                            .offset(r.left.toDp(), r.top.toDp())
                            .size(r.width.toDp(), r.height.toDp())
                    }
                } else VideoEscondido
            } else if (mode == Mode.LIVE) {
                val g = liveGeom()
                Modifier
                    .align(Alignment.TopStart)
                    .offset(g.x, g.y)
                    .size(g.w, g.h)
            } else if (mode in GUIDE_MODES) {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = gm.previewPadding, end = gm.previewPadding)
                    .size(gm.previewWidth, gm.previewHeight)
            } else {
                Modifier.fillMaxSize()
            },
        )

        // In picture-in-picture only the video is shown.
        if (!inPip) when (mode) {
            Mode.GUIDE, Mode.GROUPS, Mode.PROGRAM_MENU -> Bloco {
                // Timelines and remote directions stay left-to-right in every language.
                LtrBox {
                    GuideScreen(
                        state = guide,
                        channels = visible,
                        programs = guidePrograms,
                        filterLabel = vm.filterTitle(filter, menuSections),
                        guideStart = guideStart,
                        now = now,
                        playingId = current?.id,
                        favorites = favorites,
                        recordingKeys = recordingKeys,
                        canCatchUp = { ch, p -> vm.canCatchUp(ch, p) },
                        timeFormat = timeFormat,
                        onTune = { tuneAndWatch(it) },
                        onProgram = { ch, p -> programPressed(ch, p) },
                        onMenu = { ch, p -> openProgramMenu(ch, p) },
                        onGroups = { openGroups() },
                        onShift = { delta ->
                            vm.setGuideStart(guideStart + delta)
                            guide.focusTime = guideStart + delta
                        },
                        onNow = {
                            val t = System.currentTimeMillis()
                            vm.setGuideStart(t)
                            guide.focusTime = t
                        },
                        onClose = { mode = Mode.PLAYER },
                    )
                }
                // The TV guide is still loading behind the scenes.
                (refreshState as? RefreshState.Running)?.takeIf { it.background }?.let { r ->
                    Box(Modifier.fillMaxSize().padding(top = 10.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
                        Text(
                            r.message, fontSize = 12.sp, color = Color.White,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                .background(Color(0xCC1B2430))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
                if (mode == Mode.GROUPS) {
                    GroupsPanel(
                        rows = menuRows,
                        counts = filterCounts,
                        active = filter,
                        selected = groupsIndex,
                        listState = groupsListState,
                        recordingCount = recordings.count { it.status != RecStatus.CANCELLED },
                        movieCount = movieCount,
                        showCount = showCount,
                        // The side menu shows once you go to it (◀ or Back); always on touch screens.
                        showRail = groupsIndex >= rowCount ||
                            androidx.compose.ui.platform.LocalInputModeManager.current.inputMode ==
                            androidx.compose.ui.input.InputMode.Touch,
                        onSelect = { selectGroupItem(it) },
                    )
                }
                val ch = menuChannel
                if (mode == Mode.PROGRAM_MENU && ch != null) {
                    val p = menuProgram
                    val t = System.currentTimeMillis()
                    val actions = mutableListOf(DialogAction("Watch ${ch.name}") { tuneAndWatch(ch) })
                    if (p != null && p.start < t && vm.canCatchUp(ch, p)) {
                        actions += DialogAction(if (p.stop <= t) "↺ Watch (catch-up)" else "↺ Watch from the start") {
                            watchCatchUp(ch, p)
                        }
                    }
                    if (p != null && p.stop > t) {
                        val scheduled = TvViewModel.recordingKey(ch.id, p.start) in recordingKeys
                        actions += DialogAction(if (scheduled) "Cancelar gravação" else "● Record") {
                            vm.toggleRecordProgram(ch, p)
                            mode = Mode.GUIDE
                        }
                    }
                    actions += DialogAction(if (ch.id in favorites) "★ Remove favourite" else "☆ Add favourite") {
                        vm.toggleFavorite(ch)
                        mode = Mode.GUIDE
                    }
                    actions += DialogAction("Edit channel…") {
                        mode = Mode.GUIDE
                        editChannel(ch)
                    }
                    actions += DialogAction("Close") { mode = Mode.GUIDE }
                    ActionDialog(
                        title = p?.title ?: ch.name,
                        subtitle = p?.let { "${ch.name}  ·  ${timeFormat(it.start)} – ${timeFormat(it.stop)}" },
                        actions = actions,
                    )
                }
            }
            Mode.RECORDINGS, Mode.RECORDING_MENU -> Bloco {
                RecordingsScreen(
                    recordings = recordings,
                    selected = recIndex.coerceAtMost((recordings.size - 1).coerceAtLeast(0)),
                    listState = recListState,
                    onPlay = { openRecording(it) },
                    onMenu = {
                        menuRecording = it
                        mode = Mode.RECORDING_MENU
                    },
                )
                val r = menuRecording?.let { m -> recordings.firstOrNull { it.id == m.id } }
                if (mode == Mode.RECORDING_MENU && r != null) {
                    val actions = mutableListOf<DialogAction>()
                    if (r.filePath != null && r.sizeBytes > 0) actions += DialogAction("▶ Play") { openRecording(r) }
                    if (r.status == RecStatus.RECORDING) actions += DialogAction("■ Stop recording") {
                        vm.stopRecording(r)
                        mode = Mode.RECORDINGS
                    }
                    if (r.status == RecStatus.SCHEDULED) actions += DialogAction("Cancel") {
                        vm.stopRecording(r)
                        mode = Mode.RECORDINGS
                    }
                    actions += DialogAction("Delete") {
                        vm.deleteRecording(r)
                        mode = Mode.RECORDINGS
                    }
                    actions += DialogAction("Close") { mode = Mode.RECORDINGS }
                    ActionDialog(title = r.title, subtitle = r.channelName, actions = actions)
                } else if (mode == Mode.RECORDING_MENU) {
                    mode = Mode.RECORDINGS // the recording was deleted meanwhile
                }
            }
            Mode.PLAYBACK -> Bloco {
                (playerStatus as? PlayerStatus.Error)?.let { StatusMessage(it, null, it.message) }
                // ZarkTV: vídeo num formato que o aparelho não abre (só toca o som) → oferece o player externo.
                val semVideo by vm.player.videoSemSuporte.collectAsStateWithLifecycle()
                val url = vodUrlAtual
                if (semVideo != null) {
                    ActionDialog(
                        title = "Este vídeo não abre no player interno",
                        subtitle = "O formato do vídeo ($semVideo) não é suportado por este aparelho. Abra num player externo como VLC ou MX Player.",
                        actions = listOfNotNull(
                            url?.let {
                                DialogAction("Abrir no player externo") {
                                    vm.player.exo.pause()
                                    io.github.axon07.channelbox.data.ZarkConfig.abrirExterno(zarkCtx, it, playbackTitle)
                                }
                            },
                            url?.let {
                                DialogAction("Sempre usar player externo") {
                                    playerExterno = true
                                    io.github.axon07.channelbox.data.ZarkConfig.setPlayerExterno(zarkCtx, true)
                                    vm.player.exo.pause()
                                    io.github.axon07.channelbox.data.ZarkConfig.abrirExterno(zarkCtx, it, playbackTitle)
                                }
                            },
                            DialogAction("Voltar") { leavePlayback() },
                        ),
                    )
                }
                // Timelines and remote directions stay left-to-right in every language.
                if (io.github.axon07.channelbox.system.Aparelho.soCelular) Box(Modifier.fillMaxSize()) {
                    PlaybackCel(
                        vm = vm,
                        ui = PlaybackUi(
                            title = playbackTitle, subtitle = pbSubtitle, position = pbPosition, duration = pbDuration,
                            buffered = pbBuffered, playing = pbPlaying, buffering = pbBuffering, ended = pbEnded,
                            skipped = pbSkipped, poster = if (playbackReturn == Mode.VOD) vodPosterAtual else null,
                        ),
                        item = vodPreviewItem,
                        onToggle = { togglePause() },
                        onSeek = { seekBy(it) },
                        onSeekTo = { seekTo(it) },
                        onOptions = { openOptions() },
                        onClose = { leavePlayback() },
                        onCaptions = { playbackToken++; toggleCaptions(); captionsTick++ },
                        captionsOn = remember(captionsTick, playbackToken) { vm.player.captionsOn },
                    )
                } else LtrBox {
                    PlaybackControls(
                        ui = PlaybackUi(
                            title = playbackTitle,
                            subtitle = pbSubtitle,
                            position = pbPosition,
                            duration = pbDuration,
                            buffered = pbBuffered,
                            playing = pbPlaying,
                            buffering = pbBuffering,
                            ended = pbEnded,
                            skipped = pbSkipped,
                            poster = if (playbackReturn == Mode.VOD) vodPosterAtual else null,
                        ),
                        visible = playbackOverlay,
                        onToggle = { togglePause() },
                        onSeek = { seekBy(it) },
                        onSeekTo = { seekTo(it) },
                        onOptions = { openOptions() },
                        onClose = { leavePlayback() },
                        onCaptions = { playbackToken++; toggleCaptions(); captionsTick++ },
                        captionsOn = remember(captionsTick, playbackToken) { vm.player.captionsOn },
                        focused = pbFocus,
                    )
                }
            }
            Mode.SEARCH -> GuideSearchScreen(
                state = guideSearch,
                vm = vm,
                onOpenVod = { item ->
                    vodBrowser.open(item.kind)
                    vodDeInicio = false
                    vodDaBusca = true
                    vodBrowser.voltarInicio = { vodDaBusca = false; mode = Mode.SEARCH }
                    if (item.kind == VodKind.SERIES) vodBrowser.openShow(item) else vodBrowser.movie = item
                    mode = Mode.VOD
                },
                onCanal = { ch -> vm.withChannelAccess(ch) { vm.tune(ch); mode = Mode.PLAYER } },
                now = now,
                recordingKeys = recordingKeys,
                timeFormat = timeFormat,
                onPick = { hit ->
                    val t = System.currentTimeMillis()
                    val p = hit.program
                    when {
                        p.start > t -> {
                            val scheduled = TvViewModel.recordingKey(hit.channel.id, p.start) in recordingKeys
                            popup = Popup(
                                p.title, "${hit.channel.name}  ·  ${timeFormat(p.start)} – ${timeFormat(p.stop)}",
                                listOf(
                                    DialogAction(if (scheduled) "Cancelar gravação" else "● Record") {
                                        vm.toggleRecordProgram(hit.channel, p)
                                        popup = null
                                    },
                                    DialogAction("Watch ${hit.channel.name} now") {
                                        popup = null
                                        tuneAndWatch(hit.channel)
                                    },
                                    DialogAction("Close") { popup = null },
                                ),
                            )
                        }
                        else -> programPressed(hit.channel, p)
                    }
                },
            )
            Mode.VOD -> Bloco {
                vodBrowser.acaoPreview = { item, show -> previewVod(item, show) }
                vodBrowser.acaoTelaCheia = { previewTelaCheia() }
                // Saiu da ficha: para a miniatura.
                LaunchedEffect(vodBrowser.movie?.id, vodBrowser.show?.id) {
                    if (vodBrowser.movie == null && vodBrowser.show == null && vodBrowser.previewId != null) {
                        vm.stopVod()
                        vm.player.stop()
                        vodBrowser.previewId = null
                        vodPreviewItem = null
                    } else if (vodBrowser.previewId != null && vodPreviewItem?.let { it.id == vodBrowser.movie?.id || vodPreviewShow?.id == vodBrowser.show?.id } != true) {
                        vodBrowser.previewId = null
                    }
                }
                VodScreen(vodBrowser, vm, onPlay = { item, show -> playVod(item, show) })
                previewPergunta?.let { (item, show, at) ->
                    DialogoContinuar(
                        titulo = show?.title ?: item.title,
                        detalhe = if (show != null && item.season > 0) "Temporada ${item.season} · Episódio ${item.episode}" else null,
                        posicao = at,
                        onContinuar = { previewVod(item, show, at) },
                        onInicio = { previewVod(item, show, 0L) },
                    )
                }
                vodResume?.let { (item, show, at) ->
                    DialogoContinuar(
                        titulo = show?.title ?: item.title,
                        detalhe = if (show != null && item.season > 0) "Temporada ${item.season} · Episódio ${item.episode}" else null,
                        posicao = at,
                        onContinuar = { startVod(item, show, at) },
                        onInicio = { startVod(item, show, 0) },
                    )
                }
            }
            Mode.HOME, Mode.PROFILE -> Bloco {
                LaunchedEffect(Unit) {
                    vm.zarkConferir()
                    vm.zarkConferirNovidades()
                }
                val conta by vm.zarkConta.collectAsStateWithLifecycle()
                val pl = settings.playlists.firstOrNull { it.enabled } ?: settings.playlists.firstOrNull()
                val ctx = LocalContext.current
                val uri = androidx.compose.ui.platform.LocalUriHandler.current
                val abrirPaginaPagamento = {
                    runCatching {
                        uri.openUri(io.github.axon07.channelbox.data.ZarkConfig.checkoutUrl(ctx, pl?.xtUser.orEmpty(), pl?.name.orEmpty()))
                    }.onFailure { vm.showToast("Fale com o suporte no WhatsApp: (85) 8789-0473") }
                    Unit
                }
                // Pagar agora: Pix com QR Code dentro do app (se o painel tiver o checkout); senão abre a página.
                val pagar = {
                    if (io.github.axon07.channelbox.data.ZarkConfig.checkoutBase(ctx) != null) pixAberto = true
                    else abrirPaginaPagamento()
                }
                val suporte = {
                    runCatching { uri.openUri(io.github.axon07.channelbox.data.ZarkConfig.WHATSAPP) }
                        .onFailure { vm.showToast("WhatsApp do suporte: (85) 8789-0473") }
                    Unit
                }
                val aoVivo = {
                    if (current == null && visible.isNotEmpty()) vm.tune(visible.first())
                    mode = Mode.LIVE
                }
                val compartilhar = { texto: String ->
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, texto),
                                "Compartilhar ZarkTV",
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Unit
                }
                val sairDaConta = {
                    // Saiu da conta: para tudo (vídeo, áudio, miniatura) antes de deslogar.
                    vm.stopVod()
                    vm.player.stop()
                    runCatching { vm.player.exo.clearMediaItems() }
                    vodBrowser.previewId = null
                    jogoAberto = null
                    pl?.let { vm.removePlaylist(it.id) }
                    vm.zarkLimparConta()
                    zarkTrocarConta = true
                    setupPlaylistId = null
                    mode = Mode.SETUP
                }
                LaunchedEffect(Unit) { ZarkLayout.atualizar(zarkCtx) }
                val extrasPainel = remember(conta) { io.github.axon07.channelbox.data.ZarkPainel.extras(zarkCtx) }
                if (mode == Mode.HOME && allChannels.isEmpty() && refreshState is RefreshState.Running) ZarkCarregando(refreshState)
                else if (mode == Mode.HOME && ZarkPlay.ativo) InicioZarkPlay(
                    usuario = pl?.xtUser.orEmpty(),
                    vencimento = conta?.expira?.let { java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(it)) },
                    versao = io.github.axon07.channelbox.BuildConfig.VERSION_NAME,
                    logoUrl = extrasPainel.logo,
                    fundoUrl = extrasPainel.fundo,
                    canais = allChannels.size,
                    filmes = movieCount,
                    series = showCount,
                    atualizando = refreshState is RefreshState.Running,
                    avisoVencimento = textoVencimento(conta?.expira?.let { io.github.axon07.channelbox.data.ZarkConfig.diasRestantes(it) }),
                    onAoVivo = aoVivo,
                    onFilmes = {
                        vodBrowser.open(VodKind.MOVIE)
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onSeries = {
                        vodBrowser.open(VodKind.SERIES)
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onGuia = { openGuide() },
                    onCatchup = {
                        openGuide()
                        vm.showToast("No guia, volte no horário (◀) para assistir o que já passou.")
                    },
                    onMultitela = { openMultiView() },
                    onGravacoes = { recIndex = 0; mode = Mode.RECORDINGS },
                    onBuscar = { openSearch() },
                    onConta = { mode = Mode.PROFILE },
                    onAjustes = { ajustesVolta = Mode.HOME; mode = Mode.AJUSTES },
                    onAtualizar = {
                        if (refreshState !is RefreshState.Running) {
                            vm.refreshNow()
                            vm.showToast("Atualizando canais, filmes e séries…")
                        }
                    },
                )
                else if (mode == Mode.HOME && ZarkLayout.leve.value) InicioLeve(
                    usuario = pl?.xtUser.orEmpty(),
                    vencimento = conta?.expira?.let { java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(it)) },
                    versao = io.github.axon07.channelbox.BuildConfig.VERSION_NAME,
                    logoUrl = extrasPainel.logo,
                    fundoUrl = extrasPainel.fundo,
                    atualizando = refreshState is RefreshState.Running,
                    avisoVencimento = textoVencimento(conta?.expira?.let { io.github.axon07.channelbox.data.ZarkConfig.diasRestantes(it) }),
                    onCanais = aoVivo,
                    onFilmes = {
                        vodBrowser.open(VodKind.MOVIE)
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onSeries = {
                        vodBrowser.open(VodKind.SERIES)
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onAtualizar = {
                        if (refreshState !is RefreshState.Running) {
                            vm.refreshNow()
                            vm.showToast("Atualizando canais, filmes e séries…")
                        }
                    },
                    onBuscar = {
                        vodBrowser.open(VodKind.MOVIE)
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onPerfil = { mode = Mode.PROFILE },
                    onAjustes = { ajustesVolta = Mode.HOME; mode = Mode.AJUSTES },
                )
                else if (mode == Mode.HOME) UniShell(
                    vm = vm,
                    tab = homeTab,
                    onTab = { if (it != Aba.JOGOS) jogoAberto = null; homeTab = it },
                    userName = pl?.xtUser.orEmpty(),
                    conta = conta,
                    channels = visible,
                    favChannels = emptyList(),
                    groupLabel = vm.filterTitle(filter, menuSections).uppercase(java.util.Locale("pt", "BR")),
                    groups = filtrosLista.map { f -> f to (Aba.rotulo(f, vm.filterTitle(f, menuSections))).uppercase(java.util.Locale("pt", "BR")) },
                    currentGroup = filter,
                    onGroup = { vm.setFilter(it) },
                    jogoAberto = jogoAberto,
                    canalJogo = current.takeIf { jogoAberto != null && candidatosJogo.isNotEmpty() },
                    canalJogoInfo = if (candidatosJogo.isEmpty()) "" else "Canal ${jogoCanalIdx + 1} de ${candidatosJogo.size}",
                    onAbrirJogo = { j ->
                        jogoAberto = j
                        candidatosJogo = canaisDoJogo(j)
                        jogoCanalIdx = 0
                        // Jogo que ainda não começou: não abre canal, mostra a contagem (abre sozinho na hora).
                        if (!jogoEsperando(j)) candidatosJogo.firstOrNull()?.let { ch -> vm.withChannelAccess(ch) { vm.tune(ch) } }
                    },
                    onFecharJogo = { jogoAberto = null },
                    onMudarCanalJogo = {
                        if (candidatosJogo.isNotEmpty()) {
                            jogoCanalIdx = (jogoCanalIdx + 1) % candidatosJogo.size
                            val ch = candidatosJogo[jogoCanalIdx]
                            vm.withChannelAccess(ch) { vm.tune(ch) }
                        }
                    },
                    current = current,
                    favorites = favorites,
                    upcoming = upcoming,
                    now = now,
                    timeFormat = timeFormat,
                    onPrevGroup = { vm.cycleFilter(-1) },
                    onNextGroup = { vm.cycleFilter(1) },
                    onPickChannel = { ch -> vm.withChannelAccess(ch) { vm.tune(ch) } },
                    onFull = { mode = Mode.PLAYER },
                    onFavorite = { vm.toggleFavorite(it) },
                    onPreviewRect = { homePreview = it },
                    onOpenItem = { item ->
                        vodBrowser.open(item.kind)
                        vodDeInicio = true
                        vodDaBusca = false
                        vodBrowser.voltarInicio = { vodDeInicio = false; mode = Mode.HOME }
                        if (item.kind == VodKind.SERIES) vodBrowser.openShow(item) else vodBrowser.movie = item
                        mode = Mode.VOD
                    },
                    onOpenFiltrado = { kind, rx ->
                        vodBrowser.open(kind)
                        vodBrowser.filtroCat = rx
                        vodBrowser.category = null
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    adultoLiberado = vm.zarkAdulto.collectAsStateWithLifecycle().value,
                    onAdulto = { vm.zarkAlternarAdulto() },
                    onOpenLibrary = { kind, cat ->
                        vodBrowser.open(kind)
                        vodBrowser.category = cat
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onSearch = {
                        vodBrowser.open(VodKind.MOVIE)
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onHistory = {
                        vodBrowser.open(VodKind.MOVIE)
                        vodBrowser.category = VOD_CONTINUE
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onProfile = { mode = Mode.PROFILE },
                    onRenew = pagar,
                    versaoNova = (update as? TvViewModel.UpdateState.Available)?.release?.version,
                    novidades = vm.zarkNovidades.collectAsStateWithLifecycle().value,
                    onAtualizar = { vm.startUpdate() },
                ) else PerfilUni(
                    vm = vm,
                    usuario = pl?.xtUser.orEmpty(),
                    senha = pl?.xtPass.orEmpty(),
                    servidor = pl?.name.orEmpty(),
                    conta = conta,
                    adultoLiberado = vm.zarkAdulto.collectAsStateWithLifecycle().value,
                    temaNome = ZarkTema.atual.nome,
                    playerFilmes = if (playerExterno) "Externo" else "Interno",
                    onRenovar = pagar,
                    onConvidar = { compartilhar("Assista TV, filmes e séries no ZarkTV! Baixe o app: https://zark.zarktvqx.cfd/celular  — Assine: https://wa.me/558587890473") },
                    onSuporte = suporte,
                    onSair = sairDaConta,
                    onOutraConta = sairDaConta,
                    onAdulto = { vm.zarkAlternarAdulto() },
                    onControleParental = { settingsDeAjustes = false; settingsSection = "parental"; openSettings() },
                    onConfiguracoes = { ajustesVolta = Mode.PROFILE; mode = Mode.AJUSTES },
                    onPlayer = { ajustesVolta = Mode.PROFILE; mode = Mode.AJUSTES },
                    onAtualizar = {
                        if (io.github.axon07.channelbox.system.Aparelho.soCelular) verificandoCel = true
                        vm.checkForUpdates(manual = true)
                    },
                    onTema = { ZarkTema.proximo(ctx) },
                    onPlayerFilmes = {
                        playerExterno = !playerExterno
                        io.github.axon07.channelbox.data.ZarkConfig.setPlayerExterno(zarkCtx, playerExterno)
                    },
                    onFavoritosCanais = {
                        vm.setFilter(ChannelFilter.Favorites)
                        homeTab = Aba.TV
                        mode = Mode.HOME
                    },
                    onFavoritosVod = {
                        vodBrowser.open(VodKind.MOVIE)
                        vodBrowser.category = VOD_FAVORITOS
                        vodDeInicio = false
                        vodDaBusca = false
                        vodBrowser.voltarInicio = null
                        mode = Mode.VOD
                    },
                    onAbrir = { item ->
                        vodBrowser.open(item.kind)
                        vodDeInicio = true
                        vodDaBusca = false
                        vodBrowser.voltarInicio = { vodDeInicio = false; mode = Mode.HOME }
                        if (item.kind == VodKind.SERIES) vodBrowser.openShow(item) else vodBrowser.movie = item
                        mode = Mode.VOD
                    },
                    onVoltar = { mode = Mode.HOME },
                )
                // ZarkTV Mobile: no Perfil a barra Início · TV · Perfil continua embaixo (igual ao Web Play).
                if (mode == Mode.PROFILE && io.github.axon07.channelbox.system.Aparelho.soCelular) Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        BarraCelular(2, onInicio = { homeTab = Aba.DESTAQUES; mode = Mode.HOME }, onTv = { homeTab = Aba.TV; mode = Mode.HOME }, onPerfil = {})
                    }
                }
                if (pixAberto) {
                    androidx.activity.compose.BackHandler { pixAberto = false }
                    PixDialogo(
                        http = vm.zarkHttp,
                        checkout = io.github.axon07.channelbox.data.ZarkConfig.checkoutBase(ctx) ?: "",
                        usuario = pl?.xtUser.orEmpty(),
                        servidor = pl?.name.orEmpty(),
                        onFechar = { pixAberto = false },
                        onAbrirPagina = { pixAberto = false; abrirPaginaPagamento() },
                        onSuporte = { pixAberto = false; suporte() },
                    )
                }
                // Tutorial da primeira vez (como o "Otimizada a operação…" da UniTV).
                if (mode == Mode.HOME && !ZarkLayout.leve.value && !tutorialVisto && allChannels.isNotEmpty()) {
                    ZarkTutorial {
                        tutorialVisto = true
                        zarkCtx.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE).edit().putBoolean("tutorial_visto", true).apply()
                    }
                }
            }
            Mode.LIVE -> Bloco {
                val filtros by vm.filters.collectAsStateWithLifecycle()
                val contagemGrupos = remember(allChannels) { allChannels.groupingBy { it.groupTitle }.eachCount() }
                LiveScreen(
                    channels = visible,
                    groupLabel = vm.filterTitle(filter, menuSections).uppercase(java.util.Locale("pt", "BR")),
                    groups = filtros.map { f -> f to (vm.filterTitle(f, menuSections)) },
                    currentGroup = filter,
                    current = current,
                    favorites = favorites,
                    upcoming = upcoming,
                    now = now,
                    timeFormat = timeFormat,
                    onPrevGroup = { vm.cycleFilter(-1) },
                    onNextGroup = { vm.cycleFilter(1) },
                    onGroup = { vm.setFilter(it) },
                    onPick = { ch -> vm.withChannelAccess(ch) { vm.tune(ch) } },
                    onFull = { mode = Mode.PLAYER },
                    onFavorite = { vm.toggleFavorite(it) },
                    onBack = { mode = Mode.HOME },
                    contagem = { f ->
                        when (f) {
                            ChannelFilter.All -> allChannels.size
                            ChannelFilter.Favorites -> favorites.size
                            is ChannelFilter.Group -> contagemGrupos[f.name]
                            else -> null
                        }
                    },
                )
            }
            Mode.ORGANISE -> OrganiseScreen(vm, start = organiseStart, onExit = { mode = organiseReturn })
            Mode.AJUSTES -> ZarkAjustes(
                vm = vm,
                escala = settings.videoScale,
                versao = vm.appVersion,
                onParental = { settingsDeAjustes = true; settingsSection = "parental"; openSettings() },
                onAvancado = { settingsDeAjustes = true; settingsSection = null; openSettings() },
                onAtualizar = { vm.checkForUpdates(manual = true) },
                onSuporte = { runCatching { zarkCtx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(io.github.axon07.channelbox.data.ZarkConfig.WHATSAPP)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            )
            Mode.SETTINGS -> SettingsScreen(
                onOrganise = { openOrganise(null) },
                openNetwork = settingsFromSetup,
                openSection = settingsSection,
                settings = settings,
                refreshState = refreshState,
                channelCount = allChannels.size,
                vm = vm,
                onEditSource = { id ->
                    setupPlaylistId = id
                    mode = Mode.SETUP
                },
                onOpenRecordings = {
                    recIndex = 0
                    mode = Mode.RECORDINGS
                },
                onExit = { exitApp() },
            )
            Mode.SETUP -> Bloco {
                // Decided once on entry: saving the first playlist mustn't restart this screen.
                val firstRun = remember(setupPlaylistId) { !settings.hasSource || zarkTrocarConta }
                var manualSetup by remember { mutableStateOf(false) }
                if (firstRun && !manualSetup) {
                    ZarkLoginScreen(
                        vm = vm,
                        refreshState = refreshState,
                        onAdvanced = { manualSetup = true },
                        onDone = {
                            zarkTrocarConta = false
                            mode = Mode.HOME
                        },
                    )
                } else key(setupPlaylistId) {
                    SetupScreen(
                        initial = settings.playlists.firstOrNull { it.id == setupPlaylistId },
                        firstRun = firstRun,
                        refreshState = refreshState,
                        vm = vm,
                        onNetwork = {
                            settingsFromSetup = true
                            mode = Mode.SETTINGS
                        },
                        onDone = { mode = if (firstRun) Mode.HOME else Mode.SETTINGS },
                    )
                }
            }
            Mode.UPDATE -> Bloco {
                when (val u = update) {
                    is TvViewModel.UpdateState.Available -> NovaVersaoDialog(
                        versao = u.release.version,
                        atual = vm.appVersion,
                        conta = settings.playlists.firstOrNull()?.xtUser.orEmpty(),
                        notas = u.release.notes,
                        obrigatoria = u.release.forced,
                        onAtualizar = { vm.startUpdate() },
                        onCancelar = { vm.dismissUpdate(skipThisVersion = false) },
                    )
                    is TvViewModel.UpdateState.Downloading -> Unit // progresso no canto inferior direito
                    is TvViewModel.UpdateState.Failed -> ActionDialog(
                        title = "A atualização falhou",
                        subtitle = u.message,
                        actions = listOf(
                            DialogAction("Tentar de novo") { vm.checkForUpdates(manual = true) },
                            DialogAction("Fechar") { vm.dismissUpdate(skipThisVersion = false) },
                        ),
                    )
                    else -> Unit
                }
            }
            Mode.OPTIONS -> Bloco {
                // Re-read the tracks each time the panel opens or a choice is made.
                val audio = remember(optionsToken, tracksVersion) { vm.player.audioTracks() }
                val subtitles = remember(optionsToken, tracksVersion) { vm.player.subtitleTracks() }
                val quality = remember(optionsToken, tracksVersion) { vm.player.videoTracks() }
                OptionsPanel(
                    live = if (optionsReturn == Mode.PLAYER) buildList {
                        add(DialogAction(if (shift?.paused == true) "Resume" else "Pause live TV") {
                            mode = Mode.PLAYER
                            vm.playPauseLive()
                        })
                        if (shift != null) add(DialogAction("Voltar ao vivo") {
                            mode = Mode.PLAYER
                            vm.goLive()
                        })
                        add(DialogAction("Multi-view") { openMultiView() })
                    } else emptyList(),
                    quality = quality,
                    onQuality = {
                        vm.player.select(C.TRACK_TYPE_VIDEO, it)
                        optionsToken++
                    },
                    audio = audio,
                    subtitles = subtitles,
                    scale = settings.videoScale,
                    onAudio = {
                        vm.player.select(C.TRACK_TYPE_AUDIO, it)
                        optionsToken++
                    },
                    onSubtitle = {
                        vm.player.select(C.TRACK_TYPE_TEXT, it)
                        optionsToken++
                    },
                    onScale = { vm.setVideoScale(it) },
                )
            }
            Mode.MULTIVIEW -> Bloco {
                // Timelines and remote directions stay left-to-right in every language.
                LtrBox {
                    MultiViewScreen(
                        multi,
                        onTileTap = { i ->
                            multi.hintToken++
                            multi.select(i)
                        },
                        onTileLongPress = { i -> multiOptions(i) },
                    )
                }
                if (multiPicking) {
                    ChannelListPanel(
                        channels = visible,
                        selected = multiPickIndex,
                        listState = listState,
                        filterLabel = vm.filterTitle(filter, menuSections),
                        favorites = favorites,
                        playingId = multi.channels.getOrNull(multi.pickTarget)?.id,
                        upcoming = upcoming,
                        now = now,
                        onSelect = { i ->
                            visible.getOrNull(i)?.let { ch -> vm.withChannelAccess(ch) { multi.assign(vm, multi.pickTarget, ch) } }
                            multiPicking = false
                        },
                        onFavorite = { i -> visible.getOrNull(i)?.let { vm.toggleFavorite(it) } },
                        onGroup = { d ->
                            vm.cycleFilter(d)
                            multiPickIndex = 0
                        },
                    )
                }
            }
            Mode.EXIT_CONFIRM -> DialogoSair(onSair = { exitApp() }, onCancelar = { mode = exitReturn })
            Mode.PLAYER, Mode.CHANNEL_LIST -> Bloco {
                // ZarkTV: no celular/tablet, ao tocar na tela aparece o botão de voltar.
                if (mode == Mode.PLAYER && touchBar) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.align(Alignment.TopStart).padding(16.dp)
                                .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .tap(onClick = { touchBar = false; homeTab = Aba.TV; mode = Mode.HOME })
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) { Text("‹ Voltar", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                // With channels saved from last time, a reload runs quietly behind them (and a
                // failed one keeps them – the reason is in Settings), so nothing covers the picture.
                val fallback = when (val r = refreshState) {
                    is RefreshState.Running -> if (allChannels.isNotEmpty()) null else r.message
                    is RefreshState.Failed -> if (allChannels.isNotEmpty()) null else "Não foi possível carregar os canais: ${r.message}\nAperte OK para abrir as configurações"
                    else -> if (allChannels.isEmpty() && channelsLoaded) "Nenhum canal ainda – aperte OK para abrir as configurações" else null
                }
                var velocidade by remember { mutableStateOf(0.0) }
                LaunchedEffect(playerStatus) { while (playerStatus is PlayerStatus.Buffering) { velocidade = vm.player.velocidadeMBs(); delay(500) } }
                StatusMessage(playerStatus, current, fallback, velocidade, jaTocando = vm.player.jaTocou)
                val cur = current
                // REC and the pause badge show with the banner / controls, then hide with them.
                val overlays = bannerVisible || touchBar
                if (cur != null && cur.id in recordingChannels && overlays) RecIndicator()
                // Rewinding from live: show where the ◀◀ presses will land before the archive opens.
                (shift ?: if (rewinding) TvViewModel.LiveShift(paused = false, buffered = true, startedAt = 0, onBuffer = true) else null)
                    ?.let { st -> if (mode == Mode.PLAYER && (overlays || st.paused || touchBar || rewinding)) TimeshiftBadge(st, vm) }
                if (mode == Mode.PLAYER && bannerVisible && !touchBar && cur != null) {
                    InfoBanner(
                        cur, cur.id in favorites, cur.epgId?.let { upcoming[it] }, now, timeFormat,
                        info = streamInfo,
                        captions = captionLangs,
                        vpnOn = vpnState == io.github.axon07.channelbox.system.VpnManager.State.On,
                    )
                }
                if (mode == Mode.PLAYER && touchBar && cur != null) {
                    // Timelines and remote directions stay left-to-right in every language.
                    LtrBox {
                        LiveControls(
                            channel = cur,
                            program = nowAndNext(cur.epgId?.let { upcoming[it] }, now).first,
                            now = now,
                            timeFormat = timeFormat,
                            favorite = cur.id in favorites,
                            recording = cur.id in recordingChannels,
                            paused = shift?.paused == true,
                            behindLive = shift != null,
                            onPause = { touchBarToken++; vm.playPauseLive() },
                            onLive = { touchBarToken++; vm.goLive() },
                            onRewind = if (vm.canRewind(cur)) {
                                { touchBarToken++; vm.skipLive(-REWIND_STEP) }
                            } else null,
                            onRecord = { touchBarToken++; vm.toggleRecordCurrent() },
                            onFavorite = { touchBarToken++; vm.toggleFavorite(cur) },
                            onOptions = { touchBar = false; openOptions() },
                            onPip = if (io.github.axon07.channelbox.system.SetTopBox.supportsPip(LocalContext.current)) {
                                { touchBar = false; activity?.let { io.github.axon07.channelbox.system.SetTopBox.enterPip(it) } }
                            } else null,
                            onMultiView = { touchBar = false; openMultiView() },
                            onCaptions = { touchBarToken++; toggleCaptions(); captionsTick++ },
                            captionsOn = remember(captionsTick, touchBarToken) { vm.player.captionsOn },
                            focused = liveFocus,
                        )
                    }
                }
                if (mode == Mode.CHANNEL_LIST) CanaisOverlay(
                    titulo = vm.filterTitle(filter, menuSections),
                    channels = visible,
                    selected = listIndex,
                    listState = listState,
                    playingId = cur?.id,
                    upcoming = upcoming,
                    now = now,
                    grupos = filtrosLista.map { f -> Aba.rotulo(f, vm.filterTitle(f, menuSections)) },
                    grupoSel = if (gruposAbertos) grupoIndex else -1,
                    grupoAtual = filtrosLista.indexOf(filter),
                    gruposAbertos = gruposAbertos,
                    onCanal = { i -> visible.getOrNull(i)?.let { tuneAndWatch(it) } },
                    onFavoritar = { i -> visible.getOrNull(i)?.let { vm.toggleFavorite(it) } },
                    onGrupo = { i ->
                        filtrosLista.getOrNull(i)?.let { vm.setFilter(it) }
                        listIndex = 0
                        gruposAbertos = false
                    },
                    onAbrirGrupos = {
                        grupoIndex = filtrosLista.indexOf(filter).coerceAtLeast(0)
                        gruposAbertos = !gruposAbertos
                    },
                    onFavoritos = {
                        vm.setFilter(ChannelFilter.Favorites)
                        listIndex = 0
                        gruposAbertos = false
                    },
                    onBuscar = { gruposAbertos = false; openSearch() },
                    onVoltar = { homeTab = Aba.TV; mode = Mode.HOME },
                )
            }
        }
        if (digits.isNotEmpty() && !inPip) {
            NumberEntry(digits, digits.toIntOrNull()?.let { n -> allChannels.firstOrNull { it.number == n } })
        }
        // ZarkTV: download da atualização no canto inferior direito, com a porcentagem.
        (update as? TvViewModel.UpdateState.Downloading)?.let { u ->
            if (!inPip) Column(
                Modifier.align(Alignment.BottomEnd).padding(18.dp).width(280.dp)
                    .background(Color(0xF0141414), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Row {
                    Text("O progresso do download é: ", color = Color.White, fontSize = 14.sp)
                    Text("${u.percent}%", color = Color(0xFFFF5252), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(u.percent / 100f).height(6.dp).background(Palette.Accent, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                }
                Text("Quando terminar, confirme a instalação.", color = Color(0xAAFFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // ZarkTV Mobile: na tela cheia do canal, botão para encher a tela toda ou mostrar o vídeo inteiro.
        if (io.github.axon07.channelbox.system.Aparelho.soCelular && mode == Mode.PLAYER && !inPip) Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0x66000000)).clickable { TelaCelular.alternar(zarkCtx) },
                contentAlignment = Alignment.Center,
            ) { AppIcon(if (TelaCelular.encher.value) AppIcons.Resolucao else AppIcons.TelaCheia, tint = Color.White, size = 18.dp) }
        }
        // ZarkTV Mobile: 10 min antes do jogo marcado aparece um aviso pequeno em cima ("começa em 8 min · Assistir").
        var lembreteAntes by remember { mutableStateOf<io.github.axon07.channelbox.data.ZarkLembretes.Lembrete?>(null) }
        val avisados = remember { mutableSetOf<String>() }
        if (io.github.axon07.channelbox.system.Aparelho.soCelular) LaunchedEffect(Unit) {
            while (true) {
                val t = System.currentTimeMillis()
                io.github.axon07.channelbox.data.ZarkLembretes.lista(zarkCtx)
                    .firstOrNull { it.id !in avisados && it.inicio - t in 0..10 * 60_000L }
                    ?.let { avisados += it.id; lembreteAntes = it }
                delay(30_000)
            }
        }
        val abrirJogoLembrado: (String) -> Unit = { id ->
            scope.launch {
                val j = runCatching { io.github.axon07.channelbox.data.ZarkJogos.hoje(vm.zarkHttp) }.getOrDefault(emptyList()).firstOrNull { it.id == id }
                homeTab = Aba.JOGOS
                mode = Mode.HOME
                if (j != null) {
                    jogoAberto = j
                    candidatosJogo = canaisDoJogo(j)
                    jogoCanalIdx = 0
                    if (!jogoEsperando(j)) candidatosJogo.firstOrNull()?.let { ch -> vm.withChannelAccess(ch) { vm.tune(ch) } }
                }
            }
        }
        lembreteAntes?.let { l ->
            LaunchedEffect(l.id) { delay(20_000); if (lembreteAntes?.id == l.id) lembreteAntes = null }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(Color(0xF0232325)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val min = ((l.inicio - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Column(Modifier.weight(1f)) {
                        Text(l.titulo.ifBlank { "Seu jogo" }, color = Color(0xFFEDEDED), fontSize = 14.sp, maxLines = 1)
                        Text(if (min > 0) "Começa em $min min" else "Começando agora", color = Color(0xFFA1A1A6), fontSize = 12.sp)
                    }
                    Text("Assistir", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Color(0xFFE50914)).clickable { lembreteAntes = null; abrirJogoLembrado(l.id) }.padding(horizontal = 12.dp, vertical = 8.dp))
                    Text("✕", color = Color(0xFF85858B), fontSize = 14.sp, modifier = Modifier.clickable { lembreteAntes = null }.padding(8.dp))
                }
            }
        }
        // ZarkTV: confere os lembretes de jogos a cada 30 s e avisa quando o jogo começa.
        LaunchedEffect(Unit) {
            while (true) {
                val agoraL = System.currentTimeMillis()
                io.github.axon07.channelbox.data.ZarkLembretes.lista(zarkCtx)
                    .firstOrNull { it.inicio in (agoraL - 3 * 3600_000L)..agoraL }
                    ?.let { l ->
                        io.github.axon07.channelbox.data.ZarkLembretes.remover(zarkCtx, l.id)
                        if (lembreteAviso == null) lembreteAviso = l
                    }
                delay(30_000)
            }
        }
        lembreteAviso?.let { l ->
            if (!inPip) ActionDialog(
                title = "O jogo começou!",
                subtitle = l.titulo.ifBlank { "Seu jogo marcado já está rolando." },
                actions = listOf(
                    DialogAction("Assistir agora") {
                        lembreteAviso = null
                        if (io.github.axon07.channelbox.system.Aparelho.soCelular) abrirJogoLembrado(l.id) else { homeTab = Aba.JOGOS; mode = Mode.HOME }
                    },
                    DialogAction("Fechar") { lembreteAviso = null },
                ),
            )
        }
        if (!inPip) {
            popup?.let { p -> ActionDialog(p.title, p.subtitle, p.actions) }
            textInput?.let { t ->
                TextInputDialog(
                    title = t.title, initial = t.initial, hint = t.hint, numeric = t.numeric,
                    onSave = {
                        textInput = null
                        t.onSave(it)
                    },
                    onCancel = { textInput = null },
                )
            }
            // Tela cheia de um jogo que ainda não começou (canal liberado 30 min antes): mostra a hora do jogo.
            jogoAberto?.let { jg ->
                if (mode == Mode.PLAYER && jg.estado == "pre") Box(Modifier.fillMaxSize()) {
                    AvisoInicioJogo(jg, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
                }
            }
            // Uma tela por conta: a conta foi aberta em outro aparelho.
            vm.zarkOutraTela.collectAsStateWithLifecycle().value?.let { outro ->
                ActionDialog(
                    "Sua conta está em uso em outro aparelho",
                    "Ela está assistindo agora em: $outro. A conta só pode assistir em um aparelho por vez.",
                    listOf(DialogAction("Assistir aqui") { vm.zarkAssistirAqui() }, DialogAction("Fechar") { vm.zarkOutraTela.value = null }),
                )
            }
            // Confirmação do Hot +18 (liberado ou bloqueado), igual no celular e na TV.
            vm.zarkAvisoHot.collectAsStateWithLifecycle().value?.let { (titulo, texto) ->
                ActionDialog(titulo, texto, listOf(DialogAction("Entendi") { vm.zarkAvisoHot.value = null }))
            }
            pinRequest?.let { r ->
                key(r) {
                    PinDialog(r.title, r.setup, onSubmit = { vm.submitPin(it) }, onCancel = {
                        vm.cancelPin()
                        // Someone without the PIN: take them to the menu instead of a blank screen.
                        if (r.forChannel && mode == Mode.PLAYER) openGroups()
                    }, onEsqueci = if (r.setup) null else ({ vm.esqueciPin.value = true }))
                }
            }
            if (verificandoCel) VerificandoAtualizacao(vm, vm.appVersion) { verificandoCel = false }
            vm.zarkManutencao.collectAsStateWithLifecycle().value?.let { texto ->
                if (mode != Mode.SETUP) AvisoManutencao(texto) { vm.zarkManutencao.value = null }
            }
            if (vm.esqueciPin.collectAsStateWithLifecycle().value) {
                EsqueciSenhaDialog(
                    onConfirmar = { u, p -> vm.esqueciPinConfirmar(u, p) },
                    onCancel = { vm.esqueciPin.value = false },
                )
            }
            // Conta vencida: bloqueia tudo (nada toca) até renovar.
            val vencida by vm.zarkVencida.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.zarkVigiarVencimento() }
            if (vencida != null && mode != Mode.SETUP) {
                LaunchedEffect(vencida, mode) {
                    vm.stopVod()
                    vm.player.esquecer()
                    runCatching { vm.player.exo.clearMediaItems() }
                    vodBrowser.previewId = null
                    jogoAberto = null
                }
                val plV = settings.playlists.firstOrNull { it.enabled } ?: settings.playlists.firstOrNull()
                var pixVencida by remember { mutableStateOf(false) }
                androidx.activity.compose.BackHandler { if (pixVencida) pixVencida = false else exitApp() }
                TelaVencida(
                    venceuEm = vencida,
                    usuario = plV?.xtUser.orEmpty(),
                    onPagar = { pixVencida = true },
                    onConferir = { vm.showToast("Conferindo seu pagamento…"); vm.zarkConferir(forcar = true) },
                    onSuporte = { runCatching { zarkCtx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(io.github.axon07.channelbox.data.ZarkConfig.WHATSAPP + "?text=" + android.net.Uri.encode("Olá! Minha conta ZarkTV venceu, quero renovar. Usuário: " + plV?.xtUser.orEmpty()))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    onTrocarConta = {
                        plV?.let { vm.removePlaylist(it.id) }
                        vm.zarkLimparConta()
                        zarkTrocarConta = true
                        setupPlaylistId = null
                        mode = Mode.SETUP
                    },
                )
                if (pixVencida) PixDialogo(
                    http = vm.zarkHttp,
                    checkout = io.github.axon07.channelbox.data.ZarkConfig.checkoutBase(zarkCtx) ?: "",
                    usuario = plV?.xtUser.orEmpty(),
                    servidor = plV?.name.orEmpty(),
                    onFechar = { pixVencida = false },
                    onAbrirPagina = { pixVencida = false },
                    onSuporte = { pixVencida = false },
                )
            }
            if (sairAberto) DialogoSair(onSair = { sairAberto = false; exitApp() }, onCancelar = { sairAberto = false })
            // Baixar pelos dados móveis: avisa que vai gastar o pacote de dados.
            vm.zarkConfirmarDados.collectAsStateWithLifecycle().value?.let { baixar ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { vm.zarkConfirmarDados.value = null }) {
                    Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(Color(0xFF232325)).padding(20.dp)) {
                        Text("Você está nos dados móveis", color = Color(0xFFEDEDED), fontSize = 17.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Baixar um filme pode gastar bastante do seu pacote de dados. Quer baixar mesmo assim ou esperar o Wi-Fi?", color = Color(0xFFA1A1A6), fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("Esperar o Wi-Fi", color = Color(0xFFA1A1A6), fontSize = 14.sp, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { vm.zarkConfirmarDados.value = null }.padding(12.dp))
                            Text("Baixar", color = Color.White, fontSize = 14.sp, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color(0xFFE50914)).clickable { vm.zarkConfirmarDados.value = null; baixar() }.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    }
                }
            }
            toast?.let { ToastMessage(it.text) }
        }
    }
}

/** A small menu of actions shown over any screen. */
private data class Popup(val title: String, val subtitle: String?, val actions: List<DialogAction>)

/** A text box shown over any screen (rename, channel number). */
private data class TextInput(
    val title: String,
    val initial: String,
    val hint: String,
    val numeric: Boolean,
    val onSave: (String) -> Unit,
)

/** Back presses closer together than this go straight through the menus to "Exit?". */
private const val EXIT_AGAIN_MS = 2_500L


/** "Temporada 1 · Episódio 3" + o nome do episódio só quando ele diz algo além de "S01E03 - Episódio 3". */
internal fun legendaEpisodio(item: VodEntity, show: VodEntity): String {
    val base = if (item.season > 0) "Temporada ${item.season} · Episódio ${item.episode}" else ""
    var resto = item.title.replace(show.title, "", ignoreCase = true)
    resto = resto.replace(Regex("""(?i)s\d+\s*e\d+|epis[oó]dio\s*\d+|temporada\s*\d+"""), "")
        .trim(' ', '-', '·', '|', ':', '.')
    return listOf(base, resto).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { item.title }
}


/**
 * ZarkTV: cada tela vira um pedaço separado. Sem isso a função TvAppContent passa do limite que o
 * Android consegue compilar e roda "interpretada" (bem mais lenta em TV Box).
 */
@Composable
internal fun Bloco(conteudo: @Composable () -> Unit) = conteudo()


/**
 * Vídeo fora da tela em vez de tamanho zero: com tamanho zero a superfície do vídeo é destruída e
 * recriada, e no Fire Stick (e outras TVs) a imagem congelava, ficando só o som.
 */
private val VideoEscondido = Modifier.offset((-64).dp, (-64).dp).size(16.dp)
