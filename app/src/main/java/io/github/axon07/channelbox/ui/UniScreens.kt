package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.ZarkConfig
import io.github.axon07.channelbox.container
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/** Fundo vermelho do ZarkTV (no estilo da UniTV). */
val FundoZark: Brush get() = ZarkTema.atual.fundo

/** Abas da tela principal. */
object Aba {
    const val TV = 0
    const val JOGOS = 1
    const val DESTAQUES = 2
    const val FILMES = 3
    const val SERIES = 4
    const val KIDS = 5
    const val ANIME = 6
    const val EXPLORAR = 7
    val nomes = listOf("TV", "JOGOS", "DESTAQUES", "FILMES", "SÉRIES", "KIDS", "ANIME", "EXPLORAR")

    /** Abas com a prévia do canal (o vídeo fica ligado). Favoritos é um grupo dentro da TV. */
    fun temPrevia(tab: Int) = tab == TV

    /** Nome do grupo para mostrar (Todos / ★ Favoritos em português). */
    fun rotulo(f: ChannelFilter, titulo: String) = when (f) {
        ChannelFilter.All -> "Todos os canais"
        ChannelFilter.Favorites -> "★ Favoritos"
        else -> limparGrupo(titulo)
    }

    /** "Canais | Premiere" → "Premiere", "CANAIS: SPORTV" → "SporTV" (tira o "Canais" do começo). */
    fun limparGrupo(t: String): String {
        val sem = t.replace(Regex("""^\s*(canais|canal|channels?)\s*([|:/\-–•]+|\s)\s*""", RegexOption.IGNORE_CASE), "").trim()
        val base = sem.ifBlank { t.trim() }
        // Tudo em maiúsculas fica com só a primeira letra maiúscula (siglas curtas como HBO continuam).
        return if (base == base.uppercase() && base.length > 4) base.lowercase().split(' ').joinToString(" ") { w -> if (w.length <= 3) w.uppercase() else w.replaceFirstChar { it.uppercase() } } else base
    }
}

private val REGEX_KIDS = Regex("infantil|kids|desenho|crian|anima[cç][aã]o|disney|cartoon|pixar", RegexOption.IGNORE_CASE)
private val REGEX_ANIME = Regex("anime", RegexOption.IGNORE_CASE)
private val REGEX_ADULTO = Regex("adult|xxx|\\+18|18\\+|hot|er[oó]tic", RegexOption.IGNORE_CASE)

/**
 * Tela principal no fluxo da UniTV: logo, ícones (buscar, histórico, perfil), abas e o conteúdo da aba.
 * Na aba TV a prévia do canal é desenhada pelo player no retângulo informado em [onPreviewRect].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun UniShell(
    vm: TvViewModel,
    tab: Int,
    onTab: (Int) -> Unit,
    userName: String,
    conta: ZarkConfig.Login.Ok?,
    // TV
    channels: List<ChannelEntity>,
    favChannels: List<ChannelEntity>,
    groupLabel: String,
    groups: List<Pair<ChannelFilter, String>> = emptyList(),
    currentGroup: ChannelFilter = ChannelFilter.All,
    onGroup: (ChannelFilter) -> Unit = {},
    jogoAberto: io.github.axon07.channelbox.data.ZarkJogos.Jogo? = null,
    canalJogo: ChannelEntity? = null,
    canalJogoInfo: String = "",
    onAbrirJogo: (io.github.axon07.channelbox.data.ZarkJogos.Jogo) -> Unit = {},
    onFecharJogo: () -> Unit = {},
    onMudarCanalJogo: () -> Unit = {},
    current: ChannelEntity?,
    favorites: Set<String>,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    timeFormat: (Long) -> String,
    onPrevGroup: () -> Unit,
    onNextGroup: () -> Unit,
    onPickChannel: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    onPreviewRect: (Rect?) -> Unit,
    // Filmes / séries
    onOpenItem: (VodEntity) -> Unit,
    onOpenLibrary: (kind: String, category: String?) -> Unit,
    onOpenFiltrado: (kind: String, filtro: Regex) -> Unit = { k, _ -> onOpenLibrary(k, null) },
    adultoLiberado: Boolean = false,
    onAdulto: () -> Unit = {},
    // Topo
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onProfile: () -> Unit,
    onRenew: () -> Unit,
    versaoNova: String? = null,
    novidades: List<String> = emptyList(),
    onAtualizar: () -> Unit = {},
) {
    // ZarkTV Mobile (só em pé): telas no jeito da UniTV Mobile, com Início · TV · Perfil embaixo.
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        ShellCelular(
            vm, tab, onTab, channels, current, favorites, upcoming, now, onPickChannel, onFull, onFavorite, onPreviewRect,
            groups, currentGroup, onGroup, jogoAberto, canalJogo, canalJogoInfo, onAbrirJogo, onFecharJogo, onMudarCanalJogo,
            onOpenItem, onOpenLibrary, onOpenFiltrado, adultoLiberado, onSearch, onProfile, onAdulto,
        )
        return
    }
    var sinoAberto by uniSinoAberto
    var hora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            hora = System.currentTimeMillis()
        }
    }
    val dias = conta?.expira?.let { ZarkConfig.diasRestantes(it) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val extras = remember { io.github.axon07.channelbox.data.ZarkPainel.extras(ctx) }
    LaunchedEffect(Unit) { ZarkTema.carregar(ctx, extras.tema) }
    val tabFocus = remember { List(Aba.nomes.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        delay(150)
        // Voltando da tela cheia para a aba TV: o foco fica no canal (ListaDeCanais), não na aba.
        if (!(voltouDoCanal.value && tab == Aba.TV)) runCatching { tabFocus[tab].requestFocus() }
    }
    if (!Aba.temPrevia(tab) && !(tab == Aba.JOGOS && jogoAberto != null)) LaunchedEffect(Unit) { onPreviewRect(null) }

    // O fundo é desenhado atrás do vídeo (TvApp), para a prévia do canal aparecer.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Deitado (TV, tablet ou celular) sempre no formato UniTV: lista à esquerda, janelinha à direita.
        val compacto = maxWidth < 600.dp || maxWidth < maxHeight
        Column(Modifier.fillMaxSize().padding(start = if (compacto) 10.dp else 28.dp, end = if (compacto) 10.dp else 28.dp, top = if (compacto) 14.dp else 20.dp, bottom = if (compacto) 8.dp else 14.dp)) {
            // ---- Topo ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Só o nome com o símbolo do play (sem a imagem do ícone).
                Text("▶", color = Color(0xFFFF4D57), fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                Text("Zark", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("TV", color = Color(0xFFFF4D57), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconeRedondo(AppIcons.Search, "Buscar", onSearch)
                Spacer(Modifier.width(10.dp))
                IconeRedondo(AppIcons.History, "Histórico", onHistory)
                Spacer(Modifier.width(10.dp))
                IconeRedondo(AppIcons.User, "Perfil", onProfile)
                Spacer(Modifier.width(10.dp))
                Box {
                    IconeRedondo(AppIcons.Bell, "Avisos") { sinoAberto = !sinoAberto }
                    val temAviso = versaoNova != null || (dias != null && dias <= 5) || extras.aviso != null || novidades.isNotEmpty() || extras.recados.containsKey(userName.trim().lowercase())
                    if (temAviso) Box(Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(Color(0xFFFF1744)))
                }
                if (!compacto) {
                    Spacer(Modifier.width(16.dp))
                    // Conexão (Wi-Fi / cabo / sem internet), atualizada junto com o relógio.
                    val ctxRede = androidx.compose.ui.platform.LocalContext.current
                    val rede = remember(hora) {
                        runCatching {
                            val cm = ctxRede.getSystemService(android.net.ConnectivityManager::class.java)
                            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                            when {
                                caps == null -> 0
                                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> 1
                                else -> 3
                            }
                        }.getOrDefault(1)
                    }
                    AppIcon(
                        when (rede) { 0 -> AppIcons.SemRede; 2 -> AppIcons.Cabo; 3 -> AppIcons.Dados; else -> AppIcons.Wifi },
                        tint = if (rede == 0) Color(0xFFFF5252) else Color.White, size = 24.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(hora)),
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            java.text.SimpleDateFormat("EEE, dd/MM", java.util.Locale("pt", "BR")).format(java.util.Date(hora)),
                            color = Color(0xCCFFFFFF), fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(if (compacto) 6.dp else 10.dp))

            // ---- Abas ----
            // Subindo do conteúdo para as abas, o foco cai sempre na aba aberta (não numa vizinha, que trocaria a aba).
            LaunchedEffect(tab) { if (!(voltouDoCanal.value && tab == Aba.TV)) runCatching { tabFocus[tab].requestFocus() } }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (compacto) 2.dp else 4.dp),
                modifier = Modifier.fillMaxWidth()
                    .focusProperties { enter = { tabFocus[tab] } }
                    .focusGroup(),
            ) {
                itemsIndexed(Aba.nomes) { i, nome ->
                    AbaItem(nome, i == tab, Modifier.focusRequester(tabFocus[i]), compacto) { if (i == tab && i == Aba.TV && current != null) onFull() else onTab(i) }
                }
            }
            Spacer(Modifier.height(if (compacto) 8.dp else 14.dp))

            // ---- Conteúdo ----
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    Aba.TV -> AbaTv(
                        compacto, channels, groupLabel, current, favorites, upcoming, now, timeFormat,
                        onPrevGroup, onNextGroup, onPickChannel, onFull, onFavorite, onPreviewRect,
                        groups, currentGroup, onGroup,
                    )
                    // Jogo aberto: a telinha fica parada em cima; só rola quando o foco desce para os detalhes.
                    Aba.JOGOS -> if (jogoAberto != null) SemPivo { JogoAberto(
                        vm, jogoAberto, canalJogo, canalJogoInfo, compacto, onPreviewRect, onFull, onMudarCanalJogo, onFecharJogo,
                    ) } else AbaJogos(vm, compacto, onAbrirJogo)
                    Aba.DESTAQUES -> AbaDestaques(vm, compacto, extras.banners, onOpenItem)
                    Aba.FILMES -> AbaCatalogo(vm, VodKind.MOVIE, compacto, null, onOpenItem, onOpenLibrary)
                    Aba.SERIES -> AbaCatalogo(vm, VodKind.SERIES, compacto, null, onOpenItem, onOpenLibrary)
                    Aba.KIDS -> AbaCatalogo(vm, null, compacto, REGEX_KIDS, onOpenItem, onOpenLibrary, onOpenFiltrado)
                    Aba.ANIME -> AbaCatalogo(vm, null, compacto, REGEX_ANIME, onOpenItem, onOpenLibrary, onOpenFiltrado)
                    Aba.EXPLORAR -> AbaExplorar(vm, compacto, adultoLiberado, onOpenItem, onOpenFiltrado, onAdulto)
                }
            }
            if (userName.isNotBlank() && !compacto) {
                Text(
                    "Conta: $userName" + (conta?.expira?.let { " · vence em " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(it)) } ?: ""),
                    color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Sino: "Caixa de mensagens" deslizando da esquerda (como na UniTV).
        if (sinoAberto) {
            val msgs = buildList {
                versaoNova?.let { add(Triple("Mensagem de atualização", "Nova versão encontrada: atualize para V$it.", "Atualizar" to { sinoAberto = false; onAtualizar() })) }
                if (dias != null && dias <= 5) add(
                    Triple(
                        "Aviso de vencimento",
                        when {
                            dias < 0 -> "Seu acesso venceu. Renove para continuar assistindo."
                            dias == 0 -> "Seu acesso vence hoje. Renove a tempo para não perder o acesso."
                            else -> "Seu acesso é válido por mais $dias dia(s). Renove a tempo."
                        },
                        "Renovar" to { sinoAberto = false; onRenew() },
                    )
                )
                extras.aviso?.let { add(Triple("Aviso", it, null)) }
                extras.recados[userName.trim().lowercase()]?.let { add(Triple("Recado para você", it, null)) }
                novidades.forEach { add(Triple("Episódio novo", it, null)) }
            }
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).tap(onClick = { sinoAberto = false }))
            Column(
                Modifier.fillMaxHeight().width(340.dp).background(Color(0xF22A2A2E)).tap { }.padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 22.dp).background(Palette.Accent))
                    Spacer(Modifier.width(8.dp))
                    Text("Caixa de mensagens", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    "Você tem ${msgs.size.toString().padStart(2, '0')} mensagem(ns)",
                    color = Color(0xAAFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 14.dp),
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (msgs.isEmpty()) item { Text("Nenhuma mensagem no momento.", color = Color(0xAAFFFFFF), fontSize = 14.sp) }
                    items(msgs) { (titulo, texto, acao) ->
                        LinhaFocavel(onClick = { acao?.second?.invoke() }) {
                            Column(Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(4.dp)).padding(12.dp)) {
                                Text(titulo, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Text(texto, color = Color(0xDDFFFFFF), fontSize = 13.sp, lineHeight = 18.sp)
                                acao?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text("[OK] ${it.first} ›", color = Color(0xFFFFB74D), fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
                                }
                            }
                        }
                    }
                }
            }
        }
        // Aviso no cantinho: vencimento (5, 3, 1 dia, hoje, vencido) e recado do painel.
        val textoVenc = when {
            dias == null || dias > 5 -> null
            dias < 0 -> "Seu acesso venceu."
            dias == 0 -> "Seu acesso vence hoje."
            dias == 1 -> "Falta 1 dia para o seu acesso vencer."
            else -> "Faltam $dias dias para o seu acesso vencer."
        }
        // Aviso rápido no canto: aparece uns segundos e some sozinho (sem botão), uma vez por abertura do app.
        if (!avisoMostrado.value && (textoVenc != null || extras.aviso != null)) {
            LaunchedEffect(Unit) {
                delay(9000)
                avisoMostrado.value = true
            }
            // Como na UniTV: escurece a tela uns segundos e mostra o recado embaixo; some sozinho.
            Box(Modifier.fillMaxSize().background(Color(0x8C000000))) {
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).widthIn(max = 640.dp)
                        .background(Color(0xF0000000), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                ) {
                    textoVenc?.let {
                        Text("•  $it", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                        Text("•  Renove a tempo para continuar assistindo sem parar. Fale com seu revendedor ou pague pelo Perfil.", color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    extras.aviso?.let { Text("•  $it", color = Color(0xFFDDDDDD), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AbaItem(nome: String, selecionada: Boolean, modifier: Modifier, compacto: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !selecionada) onClick() // no controle, passar pela aba já abre
            }
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            // Como na UniTV: a aba onde o controle está fica vermelha; sem moldura amarela.
            focusedContainerColor = Color(0xFFE50914),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(
            Modifier.padding(horizontal = if (compacto) 10.dp else 14.dp, vertical = if (compacto) 5.dp else 6.dp)
        ) {
            if (nome == "KIDS") {
                Row {
                    val cores = listOf(Color(0xFFFF6B9D), Color(0xFF4FC3F7), Color(0xFFFFD54F), Color(0xFF81C784))
                    nome.forEachIndexed { i, c -> Text(c.toString(), color = if (focused) Color.White else cores[i % 4], fontSize = if (compacto) 15.sp else 19.sp, fontWeight = FontWeight.Black) }
                }
            } else {
                Text(
                    nome, color = when { focused -> Color.White; selecionada -> Color(0xFFFF4D57); else -> Color(0xE6FFFFFF) }, fontSize = if (compacto) 15.sp else 19.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun IconeRedondo(icone: ImageVector, descricao: String, onClick: () -> Unit) {
    // Box clicável simples (toque e OK do controle). Com um único caminho de clique o sino não abre e fecha ao mesmo tempo.
    var focused by remember { mutableStateOf(false) }
    var ultimo by remember { mutableStateOf(0L) }
    val clique = {
        val agora = android.os.SystemClock.uptimeMillis()
        if (agora - ultimo > 400) { ultimo = agora; onClick() }
    }
    Box(
        Modifier.size(40.dp)
            .graphicsLayer { val e = if (focused) 1.1f else 1f; scaleX = e; scaleY = e }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x33FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = descricao) { clique() },
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icone, tint = if (focused) Palette.Accent else Color.White, size = 20.dp)
    }
}

// ---- Aba TV: lista de canais + prévia ------------------------------------------------------------

@Composable
private fun AbaTv(
    compacto: Boolean,
    channels: List<ChannelEntity>,
    groupLabel: String,
    current: ChannelEntity?,
    favorites: Set<String>,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    timeFormat: (Long) -> String,
    onPrevGroup: () -> Unit,
    onNextGroup: () -> Unit,
    onPick: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    onPreviewRect: (Rect?) -> Unit,
    groups: List<Pair<ChannelFilter, String>>,
    currentGroup: ChannelFilter,
    onGroup: (ChannelFilter) -> Unit,
) {
    val previa: @Composable (Modifier) -> Unit = { mod ->
        // A prévia nunca ocupa mais que ~58% da altura: sempre sobra espaço para a programação (EPG) embaixo.
        BoxWithConstraints(mod) {
        // Prévia ocupa a largura toda da coluna; a altura é limitada para sempre sobrar a programação.
        // Janelinha sempre no formato do vídeo (16:9), sem faixas pretas dos lados, como na UniTV.
        val altPrev = minOf(maxWidth * 9f / 16f, maxHeight - 60.dp)
        val largPrev = altPrev * 16f / 9f
        var prevFoco by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(largPrev).height(altPrev)
                    .onGloballyPositioned { onPreviewRect(it.boundsInRoot()) }
                    .onFocusChanged { prevFoco = it.isFocused }
                    .border(if (prevFoco) 4.dp else 2.dp, if (prevFoco) Color.White else Color(0x55FFFFFF), RoundedCornerShape(4.dp))
                    .clickable { if (current != null) onFull() }
            ) {
                if (current == null) Text("Escolha um canal na lista", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
                else Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(6.dp)
                ) { Text("⛶", color = Color.White, fontSize = 18.sp) }
            }
            // Embaixo só o nome do canal e o que está passando agora (como na UniTV).
            Row(
                Modifier.width(largPrev).background(Color(0x88000000), RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val progs = current?.epgId?.let { upcoming[it] }.orEmpty()
                val ctxEpg = androidx.compose.ui.platform.LocalContext.current
                var curto by remember(current?.id) { mutableStateOf<List<io.github.axon07.channelbox.data.ZarkEpg.Prog>>(emptyList()) }
                LaunchedEffect(current?.id) {
                    if (current != null && progs.none { it.start <= now && it.stop > now }) {
                        curto = io.github.axon07.channelbox.data.ZarkEpg.curto(ctxEpg.container.http, current.streamUrl)
                    }
                }
                val agora = progs.firstOrNull { it.start <= now && it.stop > now }?.title
                    ?: curto.firstOrNull { it.inicio <= now && it.fim > now }?.titulo
                Text(current?.name ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (agora != null) {
                    Text("  ·  Agora: $agora", color = Color(0xCCFFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            // Agendar programação (como na UniTV): próximos programas do canal; OK marca/desmarca o aviso.
            if (current != null) ProgramacaoCanal(current, current.epgId?.let { upcoming[it] }.orEmpty(), now, Modifier.width(largPrev).weight(1f))
        }
        }
    }
    val lista: @Composable (Modifier) -> Unit = { mod ->
        // Como na UniTV: 1º clique abre o canal na janelinha, 2º clique no mesmo canal abre em tela cheia.
        ListaDeCanais(mod, channels, groupLabel, current, favorites, onPrevGroup, onNextGroup, onPick, onFull, onFavorite, groups, currentGroup, onGroup, upcoming, now)
    }
    if (compacto) {
        // Celular em pé: vídeo em cima, Canais | Favoritos, grupos | canais (como na UniTV Mobile).
        AbaTvCelular(channels, current, favorites, upcoming, now, onPick, onFull, onFavorite, onPreviewRect, groups, currentGroup, onGroup)
    } else {
        Row(Modifier.fillMaxSize()) {
            lista(Modifier.weight(0.38f).fillMaxHeight())
            Spacer(Modifier.width(18.dp))
            previa(Modifier.weight(0.62f).fillMaxHeight())
        }
    }
}

@Composable
fun ListaDeCanais(
    modifier: Modifier,
    channels: List<ChannelEntity>,
    groupLabel: String,
    current: ChannelEntity?,
    favorites: Set<String>,
    onPrevGroup: () -> Unit,
    onNextGroup: () -> Unit,
    onPick: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    groups: List<Pair<ChannelFilter, String>> = emptyList(),
    currentGroup: ChannelFilter = ChannelFilter.All,
    onGroup: (ChannelFilter) -> Unit = {},
    upcoming: Map<String, List<ProgramEntity>> = emptyMap(),
    now: Long = System.currentTimeMillis(),
) {
    var verGrupos by remember { mutableStateOf(false) }
    // Programação de cada canal (pelo id do guia ou pelo nome), com horário e barrinha de progresso.
    val porNome = remember(upcoming) {
        val m = HashMap<String, List<ProgramEntity>>()
        upcoming.forEach { (id, l) -> m.putIfAbsent(chaveEpg(id.substringBeforeLast('.')), l) }
        m
    }
    val listState = rememberLazyListState()
    val inicio = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(channels) { runCatching { listState.scrollToItem((inicio - 2).coerceAtLeast(0)) } }
    val focoCanal = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (voltouDoCanal.value) {
            delay(250)
            runCatching { listState.scrollToItem((inicio - 2).coerceAtLeast(0)) }
            delay(50)
            runCatching { focoCanal.requestFocus() }
            voltouDoCanal.value = false
        }
    }
    // Lista de canais (usada sozinha ou ao lado dos grupos).
    val listaCanais: @Composable (Modifier) -> Unit = { mod ->
      Column(mod) {
        // Outras qualidades do canal que está tocando (FHD, HD, SD…): troca com um clique.
        val atualCh = current
        val variantes = remember(atualCh?.id, channels) { if (atualCh == null) emptyList() else Qualidades.variantes(atualCh, channels, { it.name }, { it.id }) }
        if (variantes.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { Text("Qualidade", color = Color(0x99FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp)) }
                items(variantes.size) { i ->
                    val v = variantes[i]
                    PillButton(Qualidades.rotulo(v.name), primary = v.id == atualCh?.id) { if (v.id != atualCh?.id) onPick(v) }
                }
            }
        }
        if (channels.isEmpty()) {
            Text(
                if (currentGroup == ChannelFilter.Favorites) "Nenhum favorito ainda. Segure OK num canal para favoritar." else "Nenhum canal neste grupo",
                color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { _, ch ->
                val tocando = ch.id == current?.id
                LinhaFocavel(
                    selecionado = tocando,
                    modifier = if (tocando) Modifier.focusRequester(focoCanal) else Modifier,
                    onLongClick = { onFavorite(ch) },
                    onClick = { if (tocando) onFull() else onPick(ch) },
                ) {
                    val prog = (ch.epgId?.let { upcoming[it] } ?: porNome[chaveEpg(ch.name)])?.firstOrNull { it.start <= now && it.stop > now }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Número na frente, numa coluna fixa (todos alinhados), como na UniTV.
                        Text(
                            if (tocando) "▶" else "${ch.number}", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.width(66.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                            ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxSize().padding(3.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.name.uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (prog != null) {
                                val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
                                Text(
                                    f.format(java.util.Date(prog.start)) + " – " + f.format(java.util.Date(prog.stop)) + "  " + prog.title,
                                    color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val frac = ((now - prog.start).toFloat() / (prog.stop - prog.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                                Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.9f).height(2.dp).background(Color(0x33FFFFFF))) {
                                    Box(Modifier.fillMaxWidth(frac).height(2.dp).background(Color(0xFFE53935)))
                                }
                            }
                        }
                        if (ch.id in favorites) Text("★", color = Palette.Favorite, fontSize = 15.sp)
                    }
                }
            }
        }
      }
    }
    // Grupos ao lado: só de passar o foco num grupo, os canais dele já aparecem à direita (▶ para entrar).
    var grupoEmFoco by remember { mutableStateOf<ChannelFilter?>(null) }
    LaunchedEffect(grupoEmFoco) {
        val g = grupoEmFoco ?: return@LaunchedEffect
        delay(250)
        if (g != currentGroup) onGroup(g)
    }
    Column(modifier.background(Color(0x55000000), RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Sem botões ◀ ▶: toca/OK no nome do grupo abre a lista de grupos.
            Box(Modifier.weight(1f)) {
                LinhaFocavel(selecionado = verGrupos, onClick = { verGrupos = !verGrupos }) {
                    Text(
                        (groups.firstOrNull { it.first == currentGroup }?.second ?: groupLabel.ifBlank { "Lista de Canais" }) + if (verGrupos) "  ▲" else "  ▼",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
            }
        }
        if (verGrupos && groups.isNotEmpty()) {
            // Grupos de canais (o ★ Favoritos é um deles).
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.weight(0.42f).fillMaxHeight()) {
                    items(groups) { (f, nome) ->
                        val atual = f == currentGroup
                        LinhaFocavel(
                            selecionado = atual,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) grupoEmFoco = f },
                            onClick = { onGroup(f); verGrupos = false },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Barrinha vermelha que cresce no grupo aberto.
                                val barra by androidx.compose.animation.core.animateDpAsState(if (atual) 18.dp else 0.dp, label = "barraGrupo")
                                Box(Modifier.padding(start = 8.dp).size(width = 3.dp, height = barra).background(Color(0xFFE50914), RoundedCornerShape(2.dp)))
                                Text(nome, color = Color.White, fontSize = 15.sp, fontWeight = if (atual) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x22FFFFFF)))
                listaCanais(Modifier.weight(0.58f).fillMaxHeight())
            }
            return@Column
        }
        listaCanais(Modifier.fillMaxWidth().weight(1f))
        Text("OK no canal: assistir · OK de novo: tela cheia · Segure OK: favoritar", color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(8.dp))
    }
}

// ---- Destaques ------------------------------------------------------------------------------------

@Composable
private fun AbaDestaques(vm: TvViewModel, compacto: Boolean, banners: List<io.github.axon07.channelbox.data.ZarkBanner>, onOpen: (VodEntity) -> Unit) {
    // Só os mais novos: 3 filmes + 2 séries (no máximo 5 cards, como na UniTV).
    val filmes by remember { vm.vodTop(VodKind.MOVIE, null, 3) }.collectAsState(initial = emptyList())
    val series by remember { vm.vodTop(VodKind.SERIES, null, 3) }.collectAsState(initial = emptyList())
    val todos = remember(filmes, series) { (filmes.zip(series).flatMap { listOf(it.first, it.second) } + filmes.drop(series.size) + series.drop(filmes.size)).distinctBy { it.title.lowercase().substringBefore(" (") }.take(5) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val temBanner = banners.isNotEmpty()
        val alturaTotal = maxHeight
        val alturaBanner = if (compacto) 150.dp else alturaTotal * 0.42f
        Column(Modifier.fillMaxSize()) {
            if (temBanner) {
                // Banners enviados pelo painel, trocando sozinhos.
                var i by remember { mutableStateOf(0) }
                LaunchedEffect(banners.size) {
                    while (true) {
                        delay(if (ZarkLeve.ativo) 10000 else 6000)
                        i = (i + 1) % banners.size
                    }
                }
                val b = banners[i % banners.size]
                Box(
                    Modifier.fillMaxWidth().height(alturaBanner).clip(RoundedCornerShape(10.dp)).background(Color(0x33000000))
                        .tap(onClick = { b.link?.let { runCatching { uri.openUri(it) } } })
                ) {
                    AsyncImage(b.imagem, b.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    b.titulo?.let {
                        Text(it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(12.dp))
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        banners.indices.forEach { k ->
                            Box(Modifier.size(width = if (k == i % banners.size) 18.dp else 7.dp, height = 7.dp).clip(RoundedCornerShape(4.dp)).background(if (k == i % banners.size) Palette.Accent else Color(0x99FFFFFF)))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (todos.isEmpty()) {
                Text("Carregando destaques…", color = Color.White, fontSize = 16.sp)
            } else {
                val altura = if (compacto) 300.dp else (alturaTotal - (if (temBanner) alturaBanner + 10.dp else 0.dp))
                DestaquesGrade(todos, altura, onOpen = onOpen)
            }
        }
    }
}

// ---- Filmes / Séries / Kids / Anime -------------------------------------------------------------

@Composable
private fun AbaCatalogo(
    vm: TvViewModel,
    kind: String?,
    compacto: Boolean,
    filtro: Regex?,
    onOpen: (VodEntity) -> Unit,
    onLibrary: (String, String?) -> Unit,
    onFiltrado: (String, Regex) -> Unit = { k, _ -> onLibrary(k, null) },
) {
    val tipos = if (kind != null) listOf(kind) else listOf(VodKind.MOVIE, VodKind.SERIES)
    val catsFilmes by remember { vm.vodCategories(VodKind.MOVIE) }.collectAsState(initial = emptyList())
    val catsSeries by remember { vm.vodCategories(VodKind.SERIES) }.collectAsState(initial = emptyList())
    // Fileiras: (tipo, categoria, título)
    val fileiras = remember(catsFilmes, catsSeries, kind, filtro) {
        buildList {
            if (kind != null && filtro == null) add(Triple(kind, null as String?, "Adicionados recentemente"))
            tipos.forEach { t ->
                val cats = if (t == VodKind.MOVIE) catsFilmes else catsSeries
                cats.filter { filtro == null || filtro.containsMatchIn(it.name) }
                    .take(if (filtro == null) 8 else 12)
                    .forEach { add(Triple(t, it.name, it.name)) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val alturaHero = if (compacto) 260.dp else (maxHeight - 8.dp).coerceAtLeast(260.dp)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (kind != null) item {
            // Topo igual à UniTV: banner que troca sozinho, blocos coloridos e capas altas.
            val cats = if (kind == VodKind.MOVIE) catsFilmes else catsSeries
            val ano = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val lanc = cats.firstOrNull { Regex("lan[cç]amento.*$ano|$ano", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.name
                ?: cats.firstOrNull { Regex("lan[cç]amento|novidade|no ar", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.name
            val cinema = cats.firstOrNull { Regex("cinema|lan[cç]amento", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.name ?: lanc
            val top = cats.firstOrNull { Regex("netflix|top|popular", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.name
            val nacional = cats.firstOrNull { Regex("nacion|brasil|globoplay", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.name
            val topo by remember(kind, lanc) { vm.vodTop(kind, lanc, 10) }.collectAsState(initial = emptyList())
            val img = { k: Int -> topo.getOrNull(k)?.poster }
            val blocos = if (kind == VodKind.MOVIE) listOf(
                BlocoCor("Gênero", Color(0xFF2E7D32), img(6)) { onLibrary(kind, null) },
                BlocoCor("$ano", Color(0xFFC62828), img(7)) { onLibrary(kind, lanc) },
                BlocoCor("No Cinema", Color(0xFFF9A825), img(8)) { onLibrary(kind, cinema) },
                BlocoCor("Todos", Color(0xFF0288D1), img(9)) { onLibrary(kind, null) },
            ) else listOf(
                BlocoCor("Gênero", Color(0xFF1565C0), img(6)) { onLibrary(kind, null) },
                BlocoCor("Nacional", Color(0xFFF9A825), img(7)) { onLibrary(kind, nacional) },
                BlocoCor("No ar", Color(0xFF2E7D32), img(8)) { onLibrary(kind, lanc) },
                BlocoCor("Top", Color(0xFFC62828), img(9)) { onLibrary(kind, top) },
            )
            if (topo.isNotEmpty()) HeroCatalogo(vm, topo, blocos, alturaHero, onOpen = onOpen)
        }
        if (filtro != null) item {
            // Kids / Anime: mosaico com as capas das categorias encontradas.
            val primeira = (catsFilmes.map { VodKind.MOVIE to it.name } + catsSeries.map { VodKind.SERIES to it.name })
                .firstOrNull { filtro.containsMatchIn(it.second) }
            if (primeira != null) {
                val itens by remember(primeira) { vm.vodTop(primeira.first, primeira.second, 9) }.collectAsState(initial = emptyList())
                if (filtro == REGEX_ANIME) GradeComTodos(itens, alturaHero, "Todos", onOpen) { onFiltrado(primeira.first, filtro) }
                else Mosaico(itens, alturaHero, onOpen = onOpen, onMais = { onFiltrado(primeira.first, filtro) })
            }
        }
        if (fileiras.isEmpty()) item {
            Column {
                Text(
                    if (filtro != null) "Nenhuma categoria encontrada para esta aba no seu servidor."
                    else "Os filmes e séries ainda não foram carregados.",
                    color = Color.White, fontSize = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                PillButton("Atualizar conteúdo agora", primary = true) { vm.refreshNow() }
            }
        }
        // Filmes e Séries: só o destaque em cima (como na UniTV); o resto fica em "Todos".
        if (kind == null) items(fileiras, key = { "${it.first}|${it.second}" }) { (t, cat, titulo) ->
            Fileira(vm, t, cat, titulo, compacto, onOpen) { onLibrary(t, cat) }
        }
    }
    }
}

@Composable
private fun Atalho(label: String, cor: Color, modifier: Modifier, compacto: Boolean, texto: Color = Color.White, icone: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (compacto) 52.dp else 72.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Dourado else Color.Transparent, RoundedCornerShape(10.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = cor, focusedContainerColor = cor),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.08f)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color.Transparent))), contentAlignment = Alignment.Center) {
            if (icone != null && !compacto) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(icone, tint = Color.White, size = 44.dp)
                Spacer(Modifier.height(8.dp))
                Text(label, color = texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            } else Text(label, color = texto, fontSize = if (compacto) 14.sp else 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Fileira(
    vm: TvViewModel,
    kind: String,
    category: String?,
    titulo: String,
    compacto: Boolean,
    onOpen: (VodEntity) -> Unit,
    onMais: () -> Unit,
) {
    val itens by remember(kind, category) { vm.vodTop(kind, category, 20) }.collectAsState(initial = null)
    val lista = itens
    if (lista != null && lista.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(titulo, color = Color.White, fontSize = if (compacto) 15.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text("Ver tudo ›", color = Color(0xFFFFB74D), fontSize = 13.sp, modifier = Modifier.tap(onClick = onMais))
        }
        Spacer(Modifier.height(6.dp))
        val w = if (compacto) 104.dp else 140.dp
        if (lista == null) Box(Modifier.height(w * 1.5f))
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(lista, key = { it.id }) { item -> PosterTile(item, w, w * 1.5f) { onOpen(item) } }
        }
    }
}

/** Capa de filme/série com nota, que cresce no foco do controle. */
@Composable
fun PosterTile(item: VodEntity, w: Dp, h: Dp, mostrarTitulo: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(w)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.width(w).height(h).onFocusChanged { focused = it.isFocused }
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                .tap(onClick = onClick),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A2A), focusedContainerColor = Color(0xFF2A2A2A)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.06f)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(item.title, color = Color(0x99FFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                if (!item.poster.isNullOrBlank()) CapaLeve(item.poster, item.title, Modifier.fillMaxSize())
                item.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { n ->
                    Box(Modifier.align(Alignment.BottomStart).background(Color(0xCC000000), RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(String.format(java.util.Locale.US, "%.1f", n), color = Color(0xFFFFB74D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (mostrarTitulo) {
            Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}


// ---- Jogos: guia de jogos do dia ----------------------------------------------------------------

/**
 * ZarkPlay (XCIPTV): só a aba de Jogos do dia, sem as outras abas do app de TV.
 * Voltar fecha o jogo aberto e depois volta para a tela inicial.
 */
@Composable
internal fun SoJogosZp(
    vm: TvViewModel,
    jogoAberto: io.github.axon07.channelbox.data.ZarkJogos.Jogo?,
    canalJogo: ChannelEntity?,
    canalJogoInfo: String,
    onAbrirJogo: (io.github.axon07.channelbox.data.ZarkJogos.Jogo) -> Unit,
    onFecharJogo: () -> Unit,
    onMudarCanalJogo: () -> Unit,
    onPreviewRect: (Rect?) -> Unit,
    onFull: () -> Unit,
) {
    val compacto = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700
    if (jogoAberto == null) LaunchedEffect(Unit) { onPreviewRect(null) }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).padding(horizontal = if (compacto) 12.dp else 28.dp, vertical = if (compacto) 10.dp else 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Zark", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("TV", color = Color(0xFFE50914), fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(16.dp))
            AppIcon(AppIcons.Trofeu, tint = Color(0xFFFFD60A), size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Text("Jogos do dia", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (jogoAberto != null) SemPivo { JogoAberto(vm, jogoAberto, canalJogo, canalJogoInfo, compacto, onPreviewRect, onFull, onMudarCanalJogo, onFecharJogo) }
            else AbaJogos(vm, compacto, onAbrirJogo)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AbaJogos(vm: TvViewModel, compacto: Boolean, onAssistir: (io.github.axon07.channelbox.data.ZarkJogos.Jogo) -> Unit) {
    var jogos by remember { mutableStateOf<List<io.github.axon07.channelbox.data.ZarkJogos.Jogo>?>(null) }
    var filtro by remember { mutableStateOf(0) } // 0 todos, 1 ao vivo, 2 encerrados
    val ctxJogos = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            jogos = io.github.axon07.channelbox.data.ZarkJogos.hoje(vm.zarkHttp)
            delay(60_000) // atualiza o placar a cada minuto
        }
    }
    val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
    val focoJogo = remember { FocusRequester() }
    val estadoLista = androidx.compose.foundation.lazy.rememberLazyListState()
    var alvo by remember { mutableIntStateOf(0) }
    // Descendo das abas, o foco vai direto para os jogos (o que está passando primeiro); à esquerda ficam os filtros.
    Row(Modifier.fillMaxSize().focusProperties { enter = { if (jogos.isNullOrEmpty()) FocusRequester.Default else focoJogo } }.focusGroup()) {
        if (!compacto) {
            Column(Modifier.width(170.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Atalho("Todos", Color(0xFF43A047), Modifier.fillMaxWidth().weight(1f), compacto, icone = AppIcons.Calendario) { filtro = 0 }
                Atalho("Ao vivo", Color(0xFFFB8C00), Modifier.fillMaxWidth().weight(1f), compacto, icone = AppIcons.AoVivo) { filtro = 1 }
                Atalho("Encerrados", Color(0xFF039BE5), Modifier.fillMaxWidth().weight(1f), compacto, icone = AppIcons.Trofeu) { filtro = 2 }
            }
            Spacer(Modifier.width(12.dp))
        }
        val lista = jogos?.filter {
            when (filtro) {
                1 -> it.estado == "in"
                2 -> it.estado == "post"
                else -> true
            }
        }
        when {
            lista == null -> Text("Carregando os jogos de hoje…", color = Color.White, fontSize = 16.sp)
            lista.isEmpty() -> Text("Nenhum jogo encontrado agora.", color = Color.White, fontSize = 16.sp)
            else -> {
              LaunchedEffect(lista.map { it.id }) {
                  alvo = lista.indexOfFirst { it.estado == "in" }.takeIf { it >= 0 } ?: lista.indexOfFirst { it.estado == "pre" }.coerceAtLeast(0)
                  runCatching { estadoLista.scrollToItem(alvo) }
              }
              LazyColumn(state = estadoLista, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(lista) { idx, j ->
                    CartaoJogo(modifier = if (idx == alvo) Modifier.focusRequester(focoJogo) else Modifier, onClick = { onAssistir(j) }) {
                        Row(
                            Modifier.fillMaxWidth().background(Color(0x55FFFFFF), RoundedCornerShape(6.dp)).height(if (compacto) 76.dp else 92.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.width(if (compacto) 80.dp else 150.dp).fillMaxHeight().background(Color(0x33FFFFFF)),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                if (j.ligaLogo != null) AsyncImage(j.ligaLogo, null, modifier = Modifier.size(if (compacto) 28.dp else 40.dp))
                                Text(j.liga, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                            Text(j.casa.nome, color = Color.White, fontSize = if (compacto) 14.sp else 19.sp, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            j.casa.logo?.let { AsyncImage(it, null, modifier = Modifier.size(if (compacto) 30.dp else 42.dp)) }
                            Column(Modifier.width(if (compacto) 84.dp else 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (j.estado == "pre") hora.format(java.util.Date(j.inicio)) else "${j.casa.placar ?: 0} x ${j.fora.placar ?: 0}",
                                    color = Color.White, fontSize = if (compacto) 18.sp else 26.sp, fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    when (j.estado) {
                                        "in" -> "Em andamento"
                                        "post" -> "Encerrado"
                                        else -> java.text.SimpleDateFormat("dd/MM", java.util.Locale("pt", "BR")).format(java.util.Date(j.inicio)) +
                                            if (io.github.axon07.channelbox.data.ZarkLembretes.tem(ctxJogos, j.id)) "  ⏰" else ""
                                    },
                                    color = if (j.estado == "in") Color(0xFFFF5252) else Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1,
                                )
                            }
                            j.fora.logo?.let { AsyncImage(it, null, modifier = Modifier.size(if (compacto) 30.dp else 42.dp)) }
                            Text(j.fora.nome, color = Color.White, fontSize = if (compacto) 14.sp else 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            if (!compacto && j.canais.isNotEmpty()) {
                                Text(j.canais.take(2).joinToString("\n"), color = Color(0xFFFFD54F), fontSize = 11.sp, maxLines = 2, modifier = Modifier.width(110.dp).padding(end = 8.dp))
                            }
                        }
                    }
                }
              }
            }
        }
    }
}


/** Jogo aberto dentro da aba Jogos (como na UniTV): telinha, placar, botões e as abas de detalhes. */
@Composable
private fun JogoAberto(
    vm: TvViewModel,
    j: io.github.axon07.channelbox.data.ZarkJogos.Jogo,
    canal: ChannelEntity?,
    canalInfo: String,
    compacto: Boolean,
    onPreviewRect: (Rect?) -> Unit,
    onFull: () -> Unit,
    onMudar: () -> Unit,
    onVoltar: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { foco.requestFocus() }
    }
    var agora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); agora = System.currentTimeMillis() } }
    var lembrete by remember(j.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkLembretes.tem(ctx, j.id)) }
    var detalhes by remember(j.id) { mutableStateOf<io.github.axon07.channelbox.data.ZarkJogoDetalhes.Detalhes?>(null) }
    var carregou by remember(j.id) { mutableStateOf(false) }
    LaunchedEffect(j.id) {
        while (true) {
            detalhes = io.github.axon07.channelbox.data.ZarkJogoDetalhes.buscar(vm.zarkHttp, j) ?: detalhes
            carregou = true
            delay(60_000)
        }
    }
    var aba by remember { mutableIntStateOf(0) }
    // Até 30 minutos antes mostra a arte do jogo com a contagem; depois o canal já fica liberado (pré-jogo).
    val naoComecou = j.estado == "pre" && j.inicio - agora > 30 * 60_000L
    val falta = (j.inicio - agora).coerceAtLeast(0) / 60_000
    val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(j.inicio))
    val data = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale("pt", "BR")).format(java.util.Date(j.inicio))

    Column(
        // Sem fundo aqui: o vídeo é desenhado por trás e o fundo cobriria a janelinha.
        Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(if (compacto) 8.dp else 18.dp)
    ) {
        val previa: @Composable (Modifier) -> Unit = { mod ->
            Box(
                mod.aspectRatio(16f / 9f)
                    .onGloballyPositioned { onPreviewRect(it.boundsInRoot()) }
                    .tap(onClick = { if (canal != null) onFull() })
            ) {
                if (naoComecou) {
                    // Contagem regressiva (relógio andando) no lugar do vídeo, até o jogo começar.
                    var agoraSeg by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(j.id) { while (true) { delay(1000); agoraSeg = System.currentTimeMillis() } }
                    val rest = ((j.inicio - agoraSeg) / 1000).coerceAtLeast(0)
                    val relogio = "%02d:%02d:%02d".format(rest / 3600, (rest % 3600) / 60, rest % 60)
                    val esperando = j.inicio - agoraSeg > 60_000L
                    Box(Modifier.fillMaxSize().background(if (esperando) Brush.verticalGradient(listOf(Color(0xFF14161C), Color(0xFF0A0A0A))) else Brush.verticalGradient(listOf(Color(0x99000000), Color(0x99000000))))) {
                        // Arte do jogo montada sozinha: brilho vermelho, escudos, campeonato, horário e contagem.
                        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x40E50914), Color.Transparent), radius = 700f)))
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(j.liga, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EscudoArte(j.casa, if (compacto) 52.dp else 72.dp)
                                Text("x", color = Color.White, fontSize = if (compacto) 20.sp else 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                                EscudoArte(j.fora, if (compacto) 52.dp else 72.dp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Hoje às $hora", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(relogio, color = Color(0xFFFFD54F), fontSize = if (compacto) 24.sp else 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Text("O canal abre 30 minutos antes do jogo", color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                } else if (canal == null) {
                    Text("Nenhum canal encontrado.\nUse \"Mudar de canal\".", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
                }
                // Canal já liberado (30 min antes), mas o jogo ainda não começou: avisa a hora em cima do vídeo.
                if (canal != null && !naoComecou && j.estado == "pre") AvisoInicioJogo(j, Modifier.align(Alignment.TopStart).padding(8.dp))
                if (canal != null) Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(canal.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("⛶", color = Color.White, fontSize = 16.sp)
                }
            }
        }
        val info: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(j.liga, color = Color.White, fontSize = if (compacto) 18.sp else 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TimeJogo(j.casa, Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(if (j.estado == "pre") hora else "${j.casa.placar ?: 0} - ${j.fora.placar ?: 0}", color = Color.White, fontSize = if (compacto) 26.sp else 34.sp, fontWeight = FontWeight.Bold)
                        Text(data, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                        Text(
                            when (j.estado) { "in" -> "Em andamento"; "post" -> "Encerrado"; else -> "Não começou" },
                            color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    TimeJogo(j.fora, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                if (compacto) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Celular: botões pequenos e iguais (só o principal em vermelho).
                    PilulaJogo(AppIcons.Grid, "Tela cheia", true, Modifier.weight(1f)) { if (canal != null) onFull() }
                    PilulaJogo(AppIcons.Refresh, "Canal", false, Modifier.weight(1f), onMudar)
                    if (j.estado == "pre") PilulaJogo(AppIcons.Bell, if (lembrete) "Avisando" else "Lembrar", lembrete, Modifier.weight(1f)) {
                        lembrete = io.github.axon07.channelbox.data.ZarkLembretes.alternar(ctx, j.id, j.inicio, "${j.casa.nome} x ${j.fora.nome}")
                        vm.showToast(if (lembrete) "Vamos te avisar quando o jogo começar" else "Lembrete removido")
                    }
                    PilulaJogo(AppIcons.Close, "Voltar", false, Modifier.weight(1f), onVoltar)
                } else Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // TV (encaixe do Lovable): Tela cheia na largura toda; Próximo canal e Lembrar lado a lado; Voltar embaixo.
                    PilulaTv(AppIcons.Grid, "Tela cheia", true, Modifier.fillMaxWidth().focusRequester(foco)) { if (canal != null) onFull() }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PilulaTv(AppIcons.Refresh, "Próximo canal", false, Modifier.weight(1f), onMudar)
                        if (j.estado == "pre") PilulaTv(AppIcons.Bell, if (lembrete) "Avisando" else "Lembrar", lembrete, Modifier.weight(1f)) {
                            lembrete = io.github.axon07.channelbox.data.ZarkLembretes.alternar(ctx, j.id, j.inicio, "${j.casa.nome} x ${j.fora.nome}")
                            vm.showToast(if (lembrete) "Vamos te avisar quando o jogo começar" else "Lembrete removido")
                        }
                        else PilulaTv(AppIcons.Close, "Voltar", false, Modifier.weight(1f), onVoltar)
                    }
                    if (j.estado == "pre") PilulaTv(AppIcons.Close, "Voltar", false, Modifier.fillMaxWidth(), onVoltar)
                }
                // Qualidade do canal do jogo (FHD, HD, SD…): se uma resolução travar ou estiver bloqueada, troca aqui.
                val todosCanais by vm.allChannels.collectAsState()
                val qualidades = remember(canal?.id, todosCanais.size) { if (canal == null) emptyList() else Qualidades.variantes(canal, todosCanais, { it.name }, { it.id }) }
                if (qualidades.isNotEmpty()) {
                    Text("Qualidade", color = Color(0x99FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(qualidades.size) { i ->
                            val q = qualidades[i]
                            PillButton(Qualidades.rotulo(q.name), primary = q.id == canal?.id) {
                                if (q.id != canal?.id) vm.withChannelAccess(q) { vm.tune(q) }
                            }
                        }
                    }
                }
                // Onde mais o jogo está passando (FHD, HD, SD, outros canais): toca para trocar.
                // Se o canal der erro, o app já passa sozinho para a próxima opção.
                val opcoes = JogoOpcoes.nomes.value
                if (opcoes.size > 1) {
                    Text("Assistir em", color = Color(0x99FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(opcoes.size) { i ->
                            PillButton(opcoes[i].take(22), primary = i == JogoOpcoes.atual.intValue) { JogoOpcoes.escolher(i) }
                        }
                    }
                } else if (canalInfo.isNotBlank()) Text(canalInfo, color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (compacto) {
            previa(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            info(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.Top) {
                previa(Modifier.weight(1f))
                Spacer(Modifier.width(28.dp))
                info(Modifier.weight(1f))
            }
        }

        // Abas: Detalhes, Escalação do time, Estatísticas, Classificação.
        Spacer(Modifier.height(16.dp))
        // Celular: abas curtas numa linha que desliza, no mesmo tamanho do resto do app.
        if (compacto) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val nomes = listOf("Lances", "Escalação", "Estatísticas", "Tabela")
            items(nomes.size) { i ->
                val sel = aba == i
                Text(
                    nomes[i], color = if (sel) Color.White else Color(0xB3FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Color(0xFFE50914) else Color(0x1AFFFFFF))
                        .tap(onClick = { aba = i }).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Detalhes", "Escalação do time", "Estatísticas", "Classificação").forEachIndexed { i, nome ->
                var f by remember { mutableStateOf(false) }
                Surface(
                    onClick = { aba = i },
                    modifier = Modifier.onFocusChanged { f = it.isFocused; if (it.isFocused) aba = i }.tap(onClick = { aba = i }),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFFD32F2F)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(nome, color = if (aba == i && !f) Color(0xFFFF5252) else Color.White, fontSize = 17.sp)
                        if (aba == i && !f) Box(Modifier.padding(top = 3.dp).size(width = 26.dp, height = 3.dp).background(Color(0xFFFF5252), RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            if (compacto) Modifier.fillMaxWidth().heightIn(min = 120.dp)
                .background(Color(0xFF1C1C1F), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                .padding(12.dp)
            else Modifier.fillMaxWidth().heightIn(min = 260.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF1E6B2A), Color(0xFF17591F))), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                .padding(16.dp)
        ) {
            val d = detalhes
            when {
                d == null -> Text(if (carregou) "Sem informações para este jogo." else "Carregando…", color = Color.White, fontSize = 15.sp)
                aba == 0 -> AbaLances(j, d)
                aba == 1 -> AbaEscalacao(j, d)
                aba == 2 -> AbaEstatisticas(j, d)
                else -> AbaClassificacao(j, d)
            }
        }
    }
}

@Composable
private fun AbaLances(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo, d: io.github.axon07.channelbox.data.ZarkJogoDetalhes.Detalhes) {
    Column(Modifier.fillMaxWidth()) {
        // Legenda que quebra linha no celular (antes "Substituição" saía letra por letra).
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Legenda(Color.White, "Gol")
            Legenda(Color(0xFFE53935), "Vermelho")
            Legenda(Color(0xFFFDD835), "Amarelo")
            Legenda(Color(0xFF66BB6A), "Substituição")
        }
        Spacer(Modifier.height(12.dp))
        if (d.lances.isEmpty()) Text(if (j.estado == "pre") "O jogo ainda não começou." else "Nenhum lance registrado ainda.", color = Color(0xB3FFFFFF), fontSize = 14.sp)
        d.lances.forEach { l ->
            val cor = when (l.tipo) { "Gol" -> Color.White; "Cartão vermelho" -> Color(0xFFE53935); "Cartão amarelo" -> Color(0xFFFDD835); else -> Color(0xFF66BB6A) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = if (l.casa) Arrangement.Start else Arrangement.End,
            ) {
                Row(
                    Modifier.background(Color(0x55FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 10.dp, height = 14.dp).background(cor, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("${l.minuto}  ${l.tipo} · ${l.jogador}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Legenda(cor: Color, nome: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 8.dp, height = 11.dp).background(cor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(nome, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun AbaEscalacao(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo, d: io.github.axon07.channelbox.data.ZarkJogoDetalhes.Detalhes) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            j.casa.logo?.let { AsyncImage(it, null, modifier = Modifier.size(36.dp)) }
            Text("  ${j.casa.nome}" + (d.casa?.esquema?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""), color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text("VS", color = Color(0xFFFF5252), fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text((d.fora?.esquema?.takeIf { it.isNotBlank() }?.let { "($it)  " } ?: "") + "${j.fora.nome}  ", color = Color.White, fontSize = 17.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            j.fora.logo?.let { AsyncImage(it, null, modifier = Modifier.size(36.dp)) }
        }
        Spacer(Modifier.height(14.dp))
        if (d.casa == null && d.fora == null) {
            Text("A escalação sai perto do início do jogo.", color = Color.White, fontSize = 15.sp)
            return@Column
        }
        Row(Modifier.fillMaxWidth()) {
            ListaTime(d.casa, Modifier.weight(1f), Alignment.Start)
            Box(Modifier.width(1.dp).height(320.dp).background(Color(0x55FFFFFF)))
            ListaTime(d.fora, Modifier.weight(1f), Alignment.End)
        }
    }
}

@Composable
private fun ListaTime(e: io.github.axon07.channelbox.data.ZarkJogoDetalhes.Escalacao?, modifier: Modifier, lado: Alignment.Horizontal) {
    Column(modifier.padding(horizontal = 10.dp), horizontalAlignment = lado) {
        val titulares = e?.jogadores?.filter { it.titular }.orEmpty().ifEmpty { e?.jogadores?.take(11).orEmpty() }
        titulares.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) {
                    Text(p.numero, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(p.nome + if (p.posicao.isNotBlank()) "  · ${p.posicao}" else "", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AbaEstatisticas(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo, d: io.github.axon07.channelbox.data.ZarkJogoDetalhes.Detalhes) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(j.casa.nome, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(j.fora.nome, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (d.estat.isEmpty()) Text("As estatísticas aparecem com o jogo em andamento.", color = Color.White, fontSize = 15.sp)
        d.estat.forEach { e ->
            val c = e.casa.replace("%", "").toFloatOrNull() ?: 0f
            val f = e.fora.replace("%", "").toFloatOrNull() ?: 0f
            val total = (c + f).takeIf { it > 0 } ?: 1f
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(e.casa, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                    Text(e.nome, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(e.fora, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.fillMaxWidth(c / total).fillMaxHeight().background(Color(0xFFFF5252), RoundedCornerShape(3.dp)))
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxWidth(f / total).fillMaxHeight().background(Color(0xFF42A5F5), RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun AbaClassificacao(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo, d: io.github.axon07.channelbox.data.ZarkJogoDetalhes.Detalhes) {
    Column(Modifier.fillMaxWidth()) {
        if (d.tabela.isEmpty()) {
            Text("Classificação não disponível para este campeonato.", color = Color.White, fontSize = 15.sp)
            return@Column
        }
        val cab = listOf("J", "V", "E", "D", "SG", "PTS")
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("#", color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.width(30.dp))
            Text("Time", color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.weight(1f))
            cab.forEach { Text(it, color = Color(0xCCFFFFFF), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp)) }
        }
        d.tabela.forEach { l ->
            val destaque = l.time.contains(j.casa.nome, true) || l.time.contains(j.fora.nome, true) || j.casa.nome.contains(l.time, true) || j.fora.nome.contains(l.time, true)
            Row(
                Modifier.fillMaxWidth().background(if (destaque) Color(0x44FFFFFF) else Color.Transparent, RoundedCornerShape(4.dp)).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${l.pos}", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(30.dp))
                l.logo?.let { AsyncImage(it, null, modifier = Modifier.size(20.dp)) }
                Text("  ${l.time}", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                listOf(l.j, l.v, l.e, l.d, l.sg, l.pts).forEachIndexed { i, v ->
                    Text(v, color = Color.White, fontSize = 14.sp, fontWeight = if (i == 5) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeJogo(t: io.github.axon07.channelbox.data.ZarkJogos.Time, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (t.logo != null) AsyncImage(t.logo, null, modifier = Modifier.size(56.dp))
        Text(t.nome, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** "Começa às 21:30 · faltam 18 min" em cima do vídeo, enquanto o jogo não começa (some sozinho na hora). */
@Composable
fun AvisoInicioJogo(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo, modifier: Modifier = Modifier) {
    var agora by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(j.id) { while (true) { delay(20_000); agora = System.currentTimeMillis() } }
    val falta = ((j.inicio - agora) / 60_000).toInt()
    if (falta < 0) return
    val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(j.inicio))
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC09090B)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFFC83D)))
        Spacer(Modifier.width(6.dp))
        Text(
            if (falta == 0) "O jogo começa agora ($hora)" else "O jogo começa às $hora · faltam $falta min",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}

@Composable
private fun EscudoArte(t: io.github.axon07.channelbox.data.ZarkJogos.Time, tam: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(tam).clip(CircleShape).background(Color(0xFF1C1C1F)).border(1.dp, Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
        if (t.logo != null) AsyncImage(t.logo, t.nome, contentScale = ContentScale.Fit, modifier = Modifier.size(tam * 0.66f))
        else Text(t.nome.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Botão do jogo na TV: pílula de 52 dp com ícone, anel vermelho no foco (controle remoto). */
@Composable
private fun PilulaTv(icone: ImageVector, label: String, destaque: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp).onFocusChanged { f = it.isFocused }
            .border(if (f) 3.dp else 0.dp, if (f) Color(0xFFFF3347) else Color.Transparent, RoundedCornerShape(26.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(26.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (destaque) Color(0xFFE50914) else Color(0xFF1C1C1F),
            focusedContainerColor = if (destaque) Color(0xFFE50914) else Color(0xFF2A2A2E),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.06f)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            AppIcon(icone, tint = Color.White, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Botão pequeno do jogo no celular: ícone + texto numa pílula de 40 dp. */
@Composable
private fun PilulaJogo(icone: ImageVector, label: String, destaque: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (destaque) Color(0xFFE50914) else Color(0x1FFFFFFF))
            .tap(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        AppIcon(icone, tint = Color.White, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun BotaoQuadrado(icone: ImageVector, label: String, cor: Color, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(64.dp).onFocusChanged { f = it.isFocused }
                .border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                .tap(onClick = onClick),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = cor, focusedContainerColor = cor),
            scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.08f)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppIcon(icone, tint = Color.White, size = 28.dp) }
        }
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}


/** Explorar (como na UniTV): 3 capas em destaque + "Séries Curtas" e "Adulto" (com senha). */
@Composable
private fun AbaExplorar(
    vm: TvViewModel,
    compacto: Boolean,
    adultoLiberado: Boolean,
    onOpen: (VodEntity) -> Unit,
    onFiltrado: (String, Regex) -> Unit,
    onAdulto: () -> Unit,
) {
    val itens by remember { vm.vodTop(VodKind.MOVIE, null, 3) }.collectAsState(initial = emptyList())
    val catsSeries by remember { vm.vodCategories(VodKind.SERIES) }.collectAsState(initial = emptyList())
    val rxCurtas = Regex("curt|mini|novelinha|dorama", RegexOption.IGNORE_CASE)
    // Sem categoria de séries curtas no servidor: abre todas as séries.
    val filtroCurtas = if (catsSeries.any { rxCurtas.containsMatchIn(it.name) }) rxCurtas else Regex(".*")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val altura = if (compacto) 300.dp else maxHeight - 8.dp
        Row(Modifier.fillMaxWidth().height(altura), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itens.take(3).forEach { a -> TileImagem(a.poster, a.title, Modifier.weight(1f).fillMaxHeight()) { onOpen(a) } }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TileImagem(null, "Séries Curtas", Modifier.fillMaxWidth().weight(1f), cor = Color(0xFF43A047), rotulo = true) {
                    onFiltrado(VodKind.SERIES, filtroCurtas)
                }
                TileImagem(null, if (adultoLiberado) "Adulto" else "Adulto (senha)", Modifier.fillMaxWidth().weight(1f), cor = Color(0xFFE57373), rotulo = true) {
                    if (adultoLiberado) onFiltrado(VodKind.MOVIE, io.github.axon07.channelbox.data.ZarkPrefs.ADULTO) else onAdulto()
                }
            }
        }
    }
}

/** Caixa de mensagens aberta (fica fora do UniShell para o Voltar do TvApp conseguir fechar). */
internal val uniSinoAberto = androidx.compose.runtime.mutableStateOf(false)

/** O aviso de vencimento já apareceu nesta abertura do app. */
internal val avisoMostrado = androidx.compose.runtime.mutableStateOf(false)


/** Dourado da seleção (como na UniTV). */
internal val Dourado = Color(0xFFE8B84A)

/** Linha de jogo: ao selecionar ganha borda dourada e cresce um pouco, com animação. */
@Composable
private fun CartaoJogo(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by androidx.compose.animation.core.animateFloatAsState(if (foco) ZarkLeve.escala(1.03f) else 1f, label = "escalaJogo")
    Box(
        modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .onFocusChanged { foco = it.isFocused }
            .border(if (foco) 2.dp else 0.dp, if (foco) Dourado else Color.Transparent, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) { content() }
}


/** Marcado ao voltar da tela cheia para a lista: o foco volta para o canal que estava tocando. */
internal val voltouDoCanal = androidx.compose.runtime.mutableStateOf(false)


// ================================ ZarkTV Mobile ================================================
// Mesmas telas do Web Play no celular (tv.zarktvqx.cfd): Início · TV · Perfil embaixo;
// no Início as abas Recomendações · Jogos · Filmes · Séries em cima.

private val VermelhoCel = Color(0xFFE50914)
private val FundoCel = Color(0xFF0F0F10)
private val CartaoCel = Color(0xFF19191B)
private val ElevadoCel = Color(0xFF232325)
private val Texto1 = Color(0xFFEDEDED)
private val Texto2 = Color(0xFFA1A1A6)
private val Texto3 = Color(0xFF85858B)
private val LinhaCel = Color(0x14EDEDED)

/** Barra de baixo do celular (também usada por cima do Perfil). */
@Composable
fun BarraCelular(ativo: Int, onInicio: () -> Unit, onTv: () -> Unit, onPerfil: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(FundoCel).padding(top = 6.dp, bottom = 8.dp)) {
        ItemBarraCel("Início", AppIcons.Home, ativo == 0, Modifier.weight(1f), onInicio)
        ItemBarraCel("TV", AppIcons.Tv, ativo == 1, Modifier.weight(1f), onTv)
        ItemBarraCel("Perfil", AppIcons.User, ativo == 2, Modifier.weight(1f), onPerfil)
    }
}

@Composable
private fun ItemBarraCel(nome: String, icone: ImageVector, ativo: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cor = if (ativo) VermelhoCel else Texto3
    Column(modifier.clickable(onClick = onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(icone, tint = cor, size = 22.dp)
        Spacer(Modifier.height(3.dp))
        Text(nome, color = if (ativo) Color.White else Texto3, fontSize = 11.sp, fontWeight = if (ativo) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ShellCelular(
    vm: TvViewModel,
    tab: Int,
    onTab: (Int) -> Unit,
    channels: List<ChannelEntity>,
    current: ChannelEntity?,
    favorites: Set<String>,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    onPickChannel: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    onPreviewRect: (Rect?) -> Unit,
    groups: List<Pair<ChannelFilter, String>>,
    currentGroup: ChannelFilter,
    onGroup: (ChannelFilter) -> Unit,
    jogoAberto: io.github.axon07.channelbox.data.ZarkJogos.Jogo?,
    canalJogo: ChannelEntity?,
    canalJogoInfo: String,
    onAbrirJogo: (io.github.axon07.channelbox.data.ZarkJogos.Jogo) -> Unit,
    onFecharJogo: () -> Unit,
    onMudarCanalJogo: () -> Unit,
    onOpenItem: (VodEntity) -> Unit,
    onOpenLibrary: (String, String?) -> Unit,
    onOpenFiltrado: (String, Regex) -> Unit,
    adultoLiberado: Boolean,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onAdulto: () -> Unit,
) {
    val subAbas = buildList {
        add("Recomendações" to Aba.DESTAQUES); add("Jogos" to Aba.JOGOS); add("Filmes" to Aba.FILMES); add("Séries" to Aba.SERIES)
        add("Animes" to Aba.ANIME); add("Desenhos" to Aba.KIDS)
        // Adulto só aparece na tela inicial depois de liberado em Perfil → Conteúdo Hot (+18).
        if (adultoLiberado) add("Hot +18" to Aba.EXPLORAR)
    }
    // Pesquisa própria do celular (por cima de tudo, com o mais buscado).
    var buscando by remember { mutableStateOf(false) }
    if (buscando) {
        BuscaCel(vm, soCanais = tab == Aba.TV, onOpen = { buscando = false; onOpenItem(it) }, onCanal = { buscando = false; onTab(Aba.TV); onPickChannel(it) }) { buscando = false }
        return
    }
    val naTv = tab == Aba.TV
    if (!naTv && !(tab == Aba.JOGOS && jogoAberto != null)) LaunchedEffect(tab) { onPreviewRect(null) }
    // O vídeo é desenhado pelo TvApp ATRÁS desta tela: na TV e no jogo aberto o fundo fica transparente,
    // senão a janelinha fica preta (só tocava na tela cheia).
    val comVideo = naTv || (tab == Aba.JOGOS && jogoAberto != null)
    Column(Modifier.fillMaxSize().background(if (comVideo) Color.Transparent else FundoCel)) {
        // ---- Topo: ZarkTV e pesquisa ----
        Row(Modifier.fillMaxWidth().background(FundoCel).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.Play, tint = VermelhoCel, size = 22.dp)
            Spacer(Modifier.width(2.dp))
            Text("Zark", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            Text("TV", color = VermelhoCel, fontSize = 21.sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(ElevadoCel)
                    .border(1.dp, LinhaCel, RoundedCornerShape(10.dp)).clickable { buscando = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppIcons.Search, tint = Texto3, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (naTv) "Pesquisa por canal" else "Pesquisar pelo título", color = Texto3, fontSize = 14.sp, maxLines = 1)
            }
        }
        // ---- Abas de cima (só no Início): texto, a escolhida em vermelho com o tracinho embaixo (UniTV) ----
        if (!naTv) {
            LazyRow(
                Modifier.fillMaxWidth().background(FundoCel),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(subAbas) { (nome, aba) ->
                    val sel = aba == tab
                    Column(Modifier.clickable { onTab(aba) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(nome, color = if (sel) VermelhoCel else Texto1, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.width(20.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (sel) VermelhoCel else Color.Transparent))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                Aba.TV -> TvCelular(vm, channels, current, favorites, upcoming, now, onPickChannel, onFull, onFavorite, onPreviewRect, groups, currentGroup, onGroup)
                Aba.JOGOS -> if (jogoAberto != null) SemPivo { JogoAberto(vm, jogoAberto, canalJogo, canalJogoInfo, true, onPreviewRect, onFull, onMudarCanalJogo, onFecharJogo) }
                    else JogosCel(vm, onAbrirJogo)
                Aba.ANIME -> FiltradoCel(vm, REGEX_ANIME, onOpenItem, onOpenFiltrado)
                Aba.KIDS -> FiltradoCel(vm, REGEX_KIDS, onOpenItem, onOpenFiltrado)
                Aba.EXPLORAR -> if (adultoLiberado) FiltradoCel(vm, REGEX_ADULTO, onOpenItem, onOpenFiltrado) else {
                    LaunchedEffect(Unit) { onAdulto() }
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AppIcon(AppIcons.Lock, tint = Texto3, size = 32.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Conteúdo adulto bloqueado", color = Texto1, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Digitar a senha", color = Color.White, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(VermelhoCel).clickable { onAdulto() }.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
                Aba.FILMES -> CatalogoCel(vm, VodKind.MOVIE, onOpenItem, onOpenLibrary)
                Aba.SERIES -> CatalogoCel(vm, VodKind.SERIES, onOpenItem, onOpenLibrary)
                else -> RecomendacoesCel(
                    vm, onOpenItem, onTab,
                    onAbrirJogo = { onTab(Aba.JOGOS); onAbrirJogo(it) },
                    onCanal = { ch -> onTab(Aba.TV); onPickChannel(ch) },
                )
            }
        }
        BarraCelular(if (naTv) 1 else 0, onInicio = { onTab(Aba.DESTAQUES) }, onTv = { onTab(Aba.TV) }, onPerfil = onProfile)
    }
}

/** Título de fileira com "Ver todos" em vermelho. */
@Composable
private fun TituloCel(titulo: String, extra: String? = null, icone: ImageVector? = null, onTodos: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icone != null) { AppIcon(icone, tint = Texto3, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(titulo, color = Texto1, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (extra != null) { Spacer(Modifier.width(8.dp)); Text(extra, color = Texto3, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        if (onTodos != null) Text("Ver todos", color = VermelhoCel, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onTodos).padding(horizontal = 4.dp, vertical = 8.dp))
    }
}

/** Capa do Web Play: pôster alto, selo NOVO / EP. NOVO, nota, coração e nome embaixo. */
@Composable
private fun CapaCel(vm: TvViewModel, item: VodEntity, modifier: Modifier = Modifier.width(118.dp), onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var fav by remember(item.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkPrefs.favoritos(ctx).contains(item.id)) }
    val novo = item.added > 0 && System.currentTimeMillis() / 1000 - item.added < 7 * 86400
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(ElevadoCel)) {
            if (!item.poster.isNullOrBlank()) CapaLeve(item.poster, item.title, Modifier.fillMaxSize())
            else Text(iniciaisCel(item.title), color = Texto3, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            Column(Modifier.align(Alignment.TopStart).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (novo) Text(if (item.kind == VodKind.SERIES) "Episódio novo" else "Novo", color = Texto1, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.background(Color(0xE60F0F10), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                item.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { nota ->
                    Text("★ " + String.format(java.util.Locale.US, "%.1f", nota), color = Texto1, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.background(Color(0xE60F0F10), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xAA000000)).clickable { fav = vm.zarkFavoritar(item) },
                contentAlignment = Alignment.Center,
            ) { Text(if (fav) "♥" else "♡", color = if (fav) VermelhoCel else Color.White, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, color = Texto1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun iniciaisCel(t: String) = t.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

/** Carrossel do topo: os mais vistos da semana (🔥 EM ALTA) + os que acabaram de chegar (NOVO NO CATÁLOGO). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CarrosselCel(vm: TvViewModel, itens: List<Pair<VodEntity, String>>, onOpen: (VodEntity) -> Unit) {
    if (itens.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(CartaoCel))
        return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val estado = androidx.compose.foundation.pager.rememberPagerState { itens.size }
    LaunchedEffect(itens.size) {
        while (true) {
            delay(6_000)
            runCatching { estado.animateScrollToPage((estado.currentPage + 1) % itens.size) }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = estado,
            contentPadding = PaddingValues(horizontal = 12.dp),
            // Espaço entre cards maior que a margem: os vizinhos ficam fora da tela
            // (antes, com 10 < 12, aparecia uma tirinha do card anterior e do próximo).
            pageSpacing = 24.dp,
        ) { i ->
            val (item, rotulo) = itens[i]
            var fav by remember(item.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkPrefs.favoritos(ctx).contains(item.id)) }
            // Imagem larga do filme (quando o servidor tiver), senão o pôster.
            var fundo by remember(item.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(item.id) { fundo = runCatching { vm.zarkFicha(item)?.fundo }.getOrNull() }
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                    .background(CartaoCel).clickable { onOpen(item) }
            ) {
                AsyncImage(
                    fundo ?: item.poster, item.title, contentScale = ContentScale.Crop,
                    alignment = if (fundo != null) Alignment.Center else Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent, 0.35f to Color(0x22000000), 1f to Color(0xF00B0B0F))))
                if (rotulo.isNotBlank()) Text(
                    rotulo, color = Texto1, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        .background(Color(0xCC0F0F10), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
                    Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itens.indices.forEach { k ->
                val sel = k == estado.currentPage
                Box(Modifier.size(width = if (sel) 18.dp else 6.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(if (sel) VermelhoCel else Color(0x55FFFFFF)))
            }
        }
    }
}

/** Início → Recomendações, na mesma ordem do Web Play. */
@Composable
private fun RecomendacoesCel(
    vm: TvViewModel,
    onOpen: (VodEntity) -> Unit,
    onTab: (Int) -> Unit,
    onAbrirJogo: (io.github.axon07.channelbox.data.ZarkJogos.Jogo) -> Unit,
    onCanal: (ChannelEntity) -> Unit,
) {
    val destaques by remember { vm.vodDestaquesWeb() }.collectAsState(initial = emptyList())
    // Guarda umas capas para o fundo animado da entrada.
    val ctxP = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(destaques) {
        val urls = destaques.mapNotNull { it.first.poster }.filter { it.startsWith("http") }
        if (urls.size >= 4) ctxP.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE).edit().putString("capas_parede", urls.joinToString("\n")).apply()
    }
    val continuar by remember { vm.vodContinuar() }.collectAsState(initial = emptyList())
    val filmes by remember { vm.vodTop(VodKind.MOVIE, null, 20) }.collectAsState(initial = null)
    val series by remember { vm.vodTop(VodKind.SERIES, null, 20) }.collectAsState(initial = null)
    val canais by vm.allChannels.collectAsState()
    var jogos by remember { mutableStateOf<List<io.github.axon07.channelbox.data.ZarkJogos.Jogo>>(emptyList()) }
    LaunchedEffect(Unit) { jogos = runCatching { io.github.axon07.channelbox.data.ZarkJogos.hoje(vm.zarkHttp) }.getOrDefault(emptyList()).filter { it.estado != "post" }.take(15) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Column { Spacer(Modifier.height(4.dp)); CarrosselCel(vm, destaques, onOpen) } }
        if (jogos.isNotEmpty()) item {
            Column {
                TituloCel("Jogos de hoje", icone = AppIcons.Trofeu, onTodos = { onTab(Aba.JOGOS) })
                Spacer(Modifier.height(8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(jogos, key = { it.id.ifBlank { it.casa.nome + it.inicio } }) { j -> CartaoJogoCel(j) { onAbrirJogo(j) } }
                }
            }
        }
        if (continuar.isNotEmpty()) item {
            Column {
                TituloCel("Continue Assistindo", onTodos = null)
                Spacer(Modifier.height(8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continuar, key = { it.id }) { item -> CapaCel(vm, item, Modifier.width(150.dp)) { onOpen(item) } }
                }
            }
        }
        if (canais.isNotEmpty()) item {
            Column {
                TituloCel("Ao vivo agora", icone = AppIcons.AoVivo, extra = "${canais.size} canais", onTodos = { onTab(Aba.TV) })
                Spacer(Modifier.height(8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(canais.take(20), key = { it.id }) { ch -> CanalCel(ch) { onCanal(ch) } }
                }
            }
        }
        item { FileiraVodCel(vm, "Filmes em Alta", filmes, onOpen) { onTab(Aba.FILMES) } }
        item { FileiraVodCel(vm, "Séries", series, onOpen) { onTab(Aba.SERIES) } }
        if (filmes?.isEmpty() == true && series?.isEmpty() == true) item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("Os filmes e séries ainda estão chegando do servidor…", color = Texto2, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PillButton("Atualizar conteúdo agora", primary = true) { vm.refreshNow() }
            }
        }
    }
}

@Composable
private fun FileiraVodCel(vm: TvViewModel, titulo: String, lista: List<VodEntity>?, onOpen: (VodEntity) -> Unit, onTodos: () -> Unit) {
    if (lista != null && lista.isEmpty()) return
    Column {
        TituloCel(titulo, onTodos = onTodos)
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lista == null) items(4) { Box(Modifier.width(118.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(CartaoCel)) }
            else items(lista, key = { it.id }) { item -> CapaCel(vm, item) { onOpen(item) } }
        }
    }
}

/** Cartão de canal da fileira "Ao vivo agora". */
@Composable
private fun CanalCel(ch: ChannelEntity, onClick: () -> Unit) {
    Row(
        Modifier.width(200.dp).height(64.dp).clip(RoundedCornerShape(10.dp)).background(CartaoCel).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(ElevadoCel), contentAlignment = Alignment.Center) {
            ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxSize().padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            if (ch.number > 0) Text(ch.number.toString(), color = Texto3, fontSize = 11.sp)
            Text(ch.name, color = Texto1, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Cartão do jogo no jeito da UniTV: campeonato em cima, escudos em círculo, hora grande e o botão vermelho. */
@Composable
private fun CartaoJogoCel(j: io.github.axon07.channelbox.data.ZarkJogos.Jogo, modifier: Modifier = Modifier.width(280.dp), onClick: () -> Unit) {
    val ctxJ = androidx.compose.ui.platform.LocalContext.current
    var lembrar by remember(j.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkLembretes.tem(ctxJ, j.id)) }
    val br = java.util.Locale("pt", "BR")
    val hora = java.text.SimpleDateFormat("HH:mm", br).format(java.util.Date(j.inicio))
    val dia = java.text.SimpleDateFormat("dd/MM", br).format(java.util.Date(j.inicio))
    val aoVivo = j.estado == "in"
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(CartaoCel)
            .border(1.dp, if (aoVivo) VermelhoCel.copy(alpha = 0.5f) else LinhaCel, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        // Altura fixa: com ou sem o sininho, todo cartão (programado, ao vivo, encerrado) fica igual.
        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            if (j.ligaLogo != null) { AsyncImage(j.ligaLogo, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
            Text(j.liga, color = Texto2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // Jogo que ainda vai começar: sininho para lembrar (avisa 10 min antes e na hora).
            if (j.estado == "pre") Box(
                Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (lembrar) VermelhoCel else ElevadoCel)
                    .clickable { lembrar = io.github.axon07.channelbox.data.ZarkLembretes.alternar(ctxJ, j.id, j.inicio, "${j.casa.nome} x ${j.fora.nome}") },
                contentAlignment = Alignment.Center,
            ) { AppIcon(AppIcons.Bell, tint = Color.White, size = 16.dp) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeCel(j.casa.nome, j.casa.logo, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                // Sempre duas linhas (número grande + linha pequena), para o cartão não mudar de tamanho.
                val grande = if (aoVivo || j.estado == "post") "${j.casa.placar ?: 0}  x  ${j.fora.placar ?: 0}" else hora
                val pequena = when {
                    aoVivo -> j.detalhe.ifBlank { "Começou $hora" }
                    j.estado == "post" -> "Fim de jogo"
                    else -> dia
                }
                Text(grande, color = Texto1, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(pequena, color = if (aoVivo) VermelhoCel else Texto3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    when { aoVivo -> "Ao vivo"; j.estado == "post" -> "Encerrado"; else -> "Ver jogo" },
                    color = if (aoVivo) Color.White else Texto1, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (aoVivo) VermelhoCel else ElevadoCel).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            TimeCel(j.fora.nome, j.fora.logo, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimeCel(nome: String, logo: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(ElevadoCel), contentAlignment = Alignment.Center) {
            if (logo != null) AsyncImage(logo, nome, contentScale = ContentScale.Fit, modifier = Modifier.size(32.dp))
            else Text(iniciaisCel(nome), color = Texto2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(nome, color = Texto1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Aba Jogos do celular: jogos de hoje, ao vivo primeiro, um cartão por linha. */
@Composable
private fun JogosCel(vm: TvViewModel, onAbrir: (io.github.axon07.channelbox.data.ZarkJogos.Jogo) -> Unit) {
    var jogos by remember { mutableStateOf<List<io.github.axon07.channelbox.data.ZarkJogos.Jogo>?>(null) }
    LaunchedEffect(Unit) {
        jogos = runCatching { io.github.axon07.channelbox.data.ZarkJogos.hoje(vm.zarkHttp) }.getOrDefault(emptyList())
            .sortedWith(compareBy({ when (it.estado) { "in" -> 0; "pre" -> 1; else -> 2 } }, { it.inicio }))
    }
    val lista = jogos
    when {
        lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Carregando jogos…", color = Texto3, fontSize = 14.sp) }
        lista.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum jogo hoje.", color = Texto3, fontSize = 14.sp) }
        else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val aoVivo = lista.count { it.estado == "in" }
            if (aoVivo > 0) item { Text("Ao vivo agora · $aoVivo", color = VermelhoCel, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            items(lista, key = { it.id.ifBlank { it.casa.nome + it.inicio } }) { j -> CartaoJogoCel(j, Modifier.fillMaxWidth()) { onAbrir(j) } }
        }
    }
}

/** Filmes / Séries: categorias em botões (Todos + cada categoria) e grade de 3 capas por linha. */
@Composable
private fun GradeCel(vm: TvViewModel, kind: String, onOpen: (VodEntity) -> Unit) {
    val cats by remember(kind) { vm.vodCategories(kind) }.collectAsState(initial = emptyList())
    var cat by remember(kind) { mutableStateOf<String?>(null) }
    val itens by remember(kind, cat) { vm.vodTop(kind, cat, 300) }.collectAsState(initial = null)
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ChipCel("Todos", cat == null) { cat = null } }
            items(cats, key = { it.name }) { c -> ChipCel(c.name.trim(), cat == c.name) { cat = c.name } }
        }
        val lista = itens
        when {
            lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Carregando…", color = Texto3) }
            lista.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nada aqui ainda.", color = Texto2, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                PillButton("Atualizar conteúdo agora", primary = true) { vm.refreshNow() }
            }
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(lista.size, key = { lista[it].id }) { i -> CapaCel(vm, lista[i], Modifier.fillMaxWidth()) { onOpen(lista[i]) } }
            }
        }
    }
}

@Composable
private fun ChipCel(nome: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        nome, color = if (sel) FundoCel else Texto1, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Texto1 else ElevadoCel)
            .border(1.dp, if (sel) Texto1 else LinhaCel, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Aba TV do Web Play: vídeo em cima, programação, Canais | Favoritos, grupos à esquerda e canais à
 * direita. Ao entrar já abre o último canal (ou o primeiro); tocar de novo no mesmo canal = tela cheia.
 */
@Composable
private fun TvCelular(
    vm: TvViewModel,
    channels: List<ChannelEntity>,
    current: ChannelEntity?,
    favorites: Set<String>,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    onPick: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    onPreviewRect: (Rect?) -> Unit,
    groups: List<Pair<ChannelFilter, String>>,
    currentGroup: ChannelFilter,
    onGroup: (ChannelFilter) -> Unit,
) {
    // Abre sozinho um canal (o Web Play faz igual), senão o vídeo fica preto.
    LaunchedEffect(channels.isNotEmpty()) {
        if (current == null) channels.firstOrNull()?.let(onPick)
    }
    var transmitir by remember { mutableStateOf(false) }
    if (transmitir) TransmitirDialog(vm, current?.streamUrl, current?.name ?: "ZarkTV", aoVivo = true, capa = current?.logoUrl) { transmitir = false }
    AbaTvCelular(channels, current, favorites, upcoming, now, onPick, onFull, onFavorite, onPreviewRect, groups, currentGroup, onGroup) { transmitir = true }
}


/** Filmes / Séries no jeito da UniTV: banner em cima e fileiras (novos + cada categoria) com "Ver todos". */
@Composable
private fun CatalogoCel(vm: TvViewModel, kind: String, onOpen: (VodEntity) -> Unit, onLibrary: (String, String?) -> Unit) {
    val cats by remember(kind) { vm.vodCategories(kind) }.collectAsState(initial = emptyList())
    val topo by remember(kind) { vm.vodDestaques(kind, 6) }.collectAsState(initial = emptyList())
    val novos by remember(kind) { vm.vodTop(kind, null, 20) }.collectAsState(initial = null)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Column { Spacer(Modifier.height(4.dp)); CarrosselCel(vm, topo.map { it to "" }, onOpen) } }
        item { FileiraVodCel(vm, if (kind == VodKind.SERIES) "Episódios novos" else "Adicionados recentemente", novos, onOpen) { onLibrary(kind, null) } }
        items(cats.take(25), key = { kind + "|" + it.name }) { c -> FileiraCatCel(vm, kind, c.name, onOpen) { onLibrary(kind, c.name) } }
        if (novos?.isEmpty() == true) item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Os filmes e séries ainda estão chegando do servidor…", color = Texto2, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PillButton("Atualizar conteúdo agora", primary = true) { vm.refreshNow() }
            }
        }
    }
}

@Composable
private fun FileiraCatCel(vm: TvViewModel, kind: String, cat: String, onOpen: (VodEntity) -> Unit, onTodos: () -> Unit) {
    val itens by remember(kind, cat) { vm.vodTop(kind, cat, 15) }.collectAsState(initial = null)
    FileiraVodCel(vm, nomeCategoria(cat), itens, onOpen, onTodos)
}


/** Animes / Desenhos / Adulto: fileiras das categorias que batem (filmes e séries juntos). */
@Composable
private fun FiltradoCel(vm: TvViewModel, filtro: Regex, onOpen: (VodEntity) -> Unit, onFiltrado: (String, Regex) -> Unit) {
    val catsF by remember { vm.vodCategories(VodKind.MOVIE) }.collectAsState(initial = emptyList())
    val catsS by remember { vm.vodCategories(VodKind.SERIES) }.collectAsState(initial = emptyList())
    val fileiras = remember(catsF, catsS, filtro) {
        catsS.filter { filtro.containsMatchIn(it.name) }.map { VodKind.SERIES to it.name } + catsF.filter { filtro.containsMatchIn(it.name) }.map { VodKind.MOVIE to it.name }
    }
    if (fileiras.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Nenhuma categoria encontrada no seu servidor.", color = Texto3, fontSize = 14.sp) }
        return
    }
    // "Ver todos" abre o catálogo no padrão do celular (categorias em chips, busca e grade de 3),
    // em vez da tela dividida da TV.
    var aberta by remember { mutableStateOf<Pair<String, String>?>(null) }
    aberta?.let { atual ->
        CatalogoCel(vm, fileiras, atual, onOpen, onTrocar = { aberta = it }) { aberta = null }
        return
    }
    val primeira = fileiras.first()
    val topo by remember(primeira) { vm.vodTop(primeira.first, primeira.second, 6) }.collectAsState(initial = emptyList())
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { CarrosselCel(vm, topo.map { it to "" }, onOpen) }
        items(fileiras, key = { it.first + "|" + it.second }) { (k, c) -> FileiraCatCel(vm, k, c, onOpen) { aberta = k to c } }
    }
}

/** Catálogo de uma categoria no celular: chips das categorias (com quantidade), busca e grade de capas. */
@Composable
private fun CatalogoCel(
    vm: TvViewModel,
    fileiras: List<Pair<String, String>>,
    atual: Pair<String, String>,
    onOpen: (VodEntity) -> Unit,
    onTrocar: (Pair<String, String>) -> Unit,
    onVoltar: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onVoltar() }
    var busca by remember(atual) { mutableStateOf("") }
    val itens by remember(atual, busca) { vm.vodItems(atual.first, atual.second, busca.trim()) }.collectAsState(initial = null)
    val catsF by remember { vm.vodCategories(VodKind.MOVIE) }.collectAsState(initial = emptyList())
    val catsS by remember { vm.vodCategories(VodKind.SERIES) }.collectAsState(initial = emptyList())
    fun qtd(k: String, c: String) = (if (k == VodKind.SERIES) catsS else catsF).firstOrNull { it.name == c }?.count
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).tap(onClick = onVoltar), contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.Voltar, tint = Texto1, size = 22.dp)
            }
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C1F)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppIcons.Search, tint = Texto3, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (busca.isEmpty()) Text("Buscar nesta categoria", color = Texto3, fontSize = 14.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        busca, { busca = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Texto1, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(VermelhoCel),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(fileiras.size) { i ->
                val f = fileiras[i]
                val sel = f == atual
                val nome = f.second.replace(Regex("\\s*\\+?18\\+?"), "").trim().ifBlank { f.second }
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) VermelhoCel else Color(0xFF1C1C1F))
                        .tap(onClick = { onTrocar(f) }).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nome, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    qtd(f.first, f.second)?.let { Text("  $it", color = if (sel) Color(0xCCFFFFFF) else Texto3, fontSize = 12.sp) }
                }
            }
        }
        val lista = itens
        when {
            lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Carregando…", color = Texto3, fontSize = 14.sp) }
            lista.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nada encontrado.", color = Texto3, fontSize = 14.sp) }
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(lista.size, key = { lista[it].id }) { i -> CapaCel(vm, lista[i], Modifier.fillMaxWidth()) { onOpen(lista[i]) } }
            }
        }
    }
}

/**
 * Pesquisa do celular: campo em cima; sem nada digitado mostra "Mais buscados" (ranking 1, 2, 3…
 * do que os clientes do ZarkTV mais assistem na semana) e as buscas recentes; digitando, mostra
 * filmes, séries e canais em abas.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BuscaCel(vm: TvViewModel, soCanais: Boolean, onOpen: (VodEntity) -> Unit, onCanal: (ChannelEntity) -> Unit, onFechar: () -> Unit) {
    androidx.activity.compose.BackHandler { onFechar() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE) }
    var texto by remember { mutableStateOf("") }
    var tipo by remember { mutableStateOf(0) } // 0 todos, 1 filmes, 2 séries, 3 canais
    var recentes by remember { mutableStateOf(prefs.getString("buscas", "").orEmpty().split('\n').filter { it.isNotBlank() }) }
    fun guardar(q: String) {
        val l = (listOf(q.trim()) + recentes.filter { !it.equals(q.trim(), true) }).take(8)
        recentes = l; prefs.edit().putString("buscas", l.joinToString("\n")).apply()
    }
    val foco = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { foco.requestFocus() } }
    val q = texto.trim()
    val filmes by remember(q) { if (q.length < 2 || soCanais) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.vodItems(VodKind.MOVIE, null, q) }.collectAsState(initial = emptyList())
    val series by remember(q) { if (q.length < 2 || soCanais) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.vodItems(VodKind.SERIES, null, q) }.collectAsState(initial = emptyList())
    val todosCanais by vm.allChannels.collectAsState()
    val canais = remember(q, todosCanais) { if (q.length < 2 || !soCanais) emptyList() else todosCanais.filter { it.name.contains(q, ignoreCase = true) }.take(60) }
    // Mais buscados: os mais vistos da semana no ZarkTV (o mesmo "em alta" do Web Play).
    val maisBuscados by remember { vm.vodDestaquesWeb() }.collectAsState(initial = emptyList())
    val rank = remember(maisBuscados) { maisBuscados.map { it.first }.take(10) }

    Column(Modifier.fillMaxSize().background(FundoCel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onFechar), contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.Voltar, tint = Texto1, size = 22.dp)
            }
            Row(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp)).background(ElevadoCel).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppIcons.Search, tint = Texto3, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (texto.isEmpty()) Text(if (soCanais) "Nome do canal" else "Filme ou série", color = Texto3, fontSize = 15.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        texto, { texto = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Texto1, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(VermelhoCel),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (q.length >= 2) guardar(q) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(foco),
                    )
                }
                if (texto.isNotEmpty()) Text("✕", color = Texto3, fontSize = 15.sp, modifier = Modifier.clickable { texto = "" }.padding(4.dp))
            }
        }
        if (q.length < 2) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recentes.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Buscas recentes", color = Texto1, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("Limpar", color = Texto3, fontSize = 13.sp, modifier = Modifier.clickable { recentes = emptyList(); prefs.edit().remove("buscas").apply() }.padding(4.dp))
                        }
                    }
                    item {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recentes.forEach { r ->
                                Text(r, color = Texto1, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ElevadoCel).clickable { texto = r }.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                if (rank.isNotEmpty() && !soCanais) {
                    item { Text("Mais buscados", color = Texto1, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                    items(rank.size, key = { "r" + rank[it].id }) { i ->
                        val item = rank[i]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { guardar(item.title); onOpen(item) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = if (i < 3) VermelhoCel else Texto3, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(32.dp))
                            Box(Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(ElevadoCel)) {
                                if (!item.poster.isNullOrBlank()) CapaLeve(item.poster, item.title, Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = Texto1, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.kind == VodKind.SERIES) "Série" else "Filme", color = Texto3, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            val abas = listOf("Todos", "Filmes (${filmes.size})", "Séries (${series.size})")
            if (!soCanais) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(abas.size) { i ->
                    val sel = i == tipo
                    Text(abas[i], color = if (sel) Color.White else Texto1, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) VermelhoCel else ElevadoCel).clickable { tipo = i }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            val vod = when (tipo) { 1 -> filmes; 2 -> series; else -> (series + filmes) }
            val mostrarCanais = soCanais
            if (vod.isEmpty() && (!mostrarCanais || canais.isEmpty())) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nada encontrado para \"$q\".", color = Texto3, fontSize = 14.sp) }
            } else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (mostrarCanais && canais.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { Text("Canais", color = Texto1, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    items(canais.size, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { i ->
                        val ch = canais[i]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { guardar(q); onCanal(ch) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 48.dp, height = 32.dp)) { ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxSize()) }
                            Spacer(Modifier.width(12.dp))
                            Text(ch.name, color = Texto1, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (vod.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { Text("Filmes e séries", color = Texto1, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
                items(vod.size, key = { "v" + vod[it].id }) { i -> CapaCel(vm, vod[i], Modifier.fillMaxWidth()) { guardar(q); onOpen(vod[i]) } }
            }
        }
    }
}


/** ZarkTV: capa decodificada pequena (cabe muitas no cache e abre rápido na TV box) e aparecendo suave. */
@Composable
internal fun CapaLeve(url: String?, titulo: String, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(
        remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).size(300, 450).crossfade(180).build() },
        titulo, contentScale = ContentScale.Crop, modifier = modifier,
    )
}


/** Próximos programas do canal na aba TV; OK num programa que ainda vai começar liga o aviso ⏰. */
@Composable
private fun ProgramacaoCanal(canal: ChannelEntity, progs: List<ProgramEntity>, now: Long, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var curto by remember(canal.id) { mutableStateOf<List<io.github.axon07.channelbox.data.ZarkEpg.Prog>>(emptyList()) }
    LaunchedEffect(canal.id) {
        if (progs.none { it.stop > now }) curto = io.github.axon07.channelbox.data.ZarkEpg.curto(ctx.container.http, canal.streamUrl)
    }
    val lista = (if (progs.any { it.stop > now }) progs.map { Triple(it.title, it.start, it.stop) } else curto.map { Triple(it.titulo, it.inicio, it.fim) })
        .filter { it.third > now }.sortedBy { it.second }.take(12)
    if (lista.isEmpty()) return
    var marcados by remember(canal.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkLembretes.lista(ctx).map { it.id }.toSet()) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    androidx.compose.foundation.lazy.LazyColumn(modifier.padding(top = 6.dp)) {
        items(lista.size) { i ->
            val (titulo, ini, fim) = lista[i]
            val id = "epg:${canal.id}:$ini"
            val noAr = ini <= now
            val marcado = id in marcados
            var foco by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .background(if (foco) Color(0xFFE50914) else Color(0x66000000), RoundedCornerShape(4.dp))
                    .onFocusChanged { foco = it.isFocused }
                    .clickable {
                        if (noAr) return@clickable
                        val ligou = io.github.axon07.channelbox.data.ZarkLembretes.alternar(ctx, id, ini, "${canal.name}: $titulo")
                        marcados = if (ligou) marcados + id else marcados - id
                        android.widget.Toast.makeText(ctx, if (ligou) "Vamos te avisar quando começar" else "Aviso removido", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt.format(java.util.Date(ini)) + "–" + fmt.format(java.util.Date(fim)), color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.width(92.dp))
                Text(titulo, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    when { noAr -> "NO AR"; marcado -> "⏰ Avisar"; foco -> "OK: avisar"; else -> "" },
                    color = if (noAr) Color(0xFFFFC107) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
