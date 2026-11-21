package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
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
        else -> titulo
    }
}

private val REGEX_KIDS = Regex("infantil|kids|desenho|crian|anima[cç][aã]o|disney|cartoon|pixar", RegexOption.IGNORE_CASE)
private val REGEX_ANIME = Regex("anime", RegexOption.IGNORE_CASE)

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
        runCatching { tabFocus[tab].requestFocus() }
    }
    if (!Aba.temPrevia(tab) && !(tab == Aba.JOGOS && jogoAberto != null)) LaunchedEffect(Unit) { onPreviewRect(null) }

    // O fundo é desenhado atrás do vídeo (TvApp), para a prévia do canal aparecer.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compacto = maxWidth < 700.dp
        Column(Modifier.fillMaxSize().padding(start = if (compacto) 10.dp else 28.dp, end = if (compacto) 10.dp else 28.dp, top = if (compacto) 14.dp else 20.dp, bottom = if (compacto) 8.dp else 14.dp)) {
            // ---- Topo ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(io.github.axon07.channelbox.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(if (compacto) 34.dp else 42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0A0A)),
                )
                Spacer(Modifier.width(10.dp))
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
                    Text(
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(hora)),
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(if (compacto) 6.dp else 10.dp))

            // ---- Abas ----
            // Subindo do conteúdo para as abas, o foco cai sempre na aba aberta (não numa vizinha, que trocaria a aba).
            LaunchedEffect(tab) { runCatching { tabFocus[tab].requestFocus() } }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (compacto) 4.dp else 14.dp),
                modifier = Modifier.fillMaxWidth()
                    .focusProperties { enter = { tabFocus[tab] } }
                    .focusGroup(),
            ) {
                itemsIndexed(Aba.nomes) { i, nome ->
                    AbaItem(nome, i == tab, Modifier.focusRequester(tabFocus[i]), compacto) { onTab(i) }
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
                    Aba.JOGOS -> if (jogoAberto != null) JogoAberto(
                        vm, jogoAberto, canalJogo, canalJogoInfo, compacto, onPreviewRect, onFull, onMudarCanalJogo, onFecharJogo,
                    ) else AbaJogos(vm, compacto, onAbrirJogo)
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
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp).width(320.dp)
                    .background(Color(0xE6141414), RoundedCornerShape(10.dp))
                    .border(1.dp, if (textoVenc != null) Color(0xAAFFB300) else Color(0x44FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(if (textoVenc != null) "Aviso de vencimento" else "Aviso", color = Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                textoVenc?.let { Text("$it Renove agora ou fale com seu revendedor.", color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
                extras.aviso?.let { Text(it, color = Color(0xFFDDDDDD), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
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
            focusedContainerColor = Color(0x22FFFFFF),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.04f)),
    ) {
        Box(
            Modifier
                .border(if (focused) 2.dp else 0.dp, if (focused) Color(0xFFFFCC80) else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = if (compacto) 12.dp else 20.dp, vertical = if (compacto) 6.dp else 8.dp)
        ) {
            if (nome == "KIDS") {
                Row {
                    val cores = listOf(Color(0xFFFF6B9D), Color(0xFF4FC3F7), Color(0xFFFFD54F), Color(0xFF81C784))
                    nome.forEachIndexed { i, c -> Text(c.toString(), color = cores[i % 4], fontSize = if (compacto) 15.sp else 21.sp, fontWeight = FontWeight.Black) }
                }
            } else {
                Text(
                    nome, color = if (selecionada) Color(0xFFFF4D57) else Color(0xE6FFFFFF), fontSize = if (compacto) 15.sp else 21.sp,
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
        val altPrev = minOf(maxWidth * 9f / 16f, maxHeight * 0.58f)
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(altPrev * 16f / 9f).height(altPrev)
                    .border(2.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp))
                    .onGloballyPositioned { onPreviewRect(it.boundsInRoot()) }
                    .tap(onClick = { if (current != null) onFull() })
            ) {
                if (current == null) Text("Escolha um canal na lista", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
                else Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(6.dp)
                ) { Text("⛶", color = Color.White, fontSize = 18.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).background(Color(0x66000000)).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column {
                    Text(current?.name ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val progs = current?.epgId?.let { upcoming[it] }.orEmpty()
                    val ctxEpg = androidx.compose.ui.platform.LocalContext.current
                    var curto by remember(current?.id) { mutableStateOf<List<io.github.axon07.channelbox.data.ZarkEpg.Prog>>(emptyList()) }
                    LaunchedEffect(current?.id) {
                        if (current != null && progs.count { it.stop > now } < 4) {
                            curto = io.github.axon07.channelbox.data.ZarkEpg.curto(ctxEpg.container.http, current.streamUrl)
                        }
                    }
                    // Programação: o que passou, o que passa agora e os próximos, com horário.
                    val lista = (progs.map { Triple(it.title, it.start, it.stop) } + curto.map { Triple(it.titulo, it.inicio, it.fim) })
                        .distinctBy { it.second }.sortedBy { it.second }
                    val iAgora = lista.indexOfFirst { it.second <= now && it.third > now }
                    val mostrar = if (iAgora >= 0) lista.subList((iAgora - 1).coerceAtLeast(0), (iAgora + 6).coerceAtMost(lista.size))
                        else lista.filter { it.third > now }.take(6)
                    if (mostrar.isEmpty() && current != null) Text("Sem programação para este canal", color = Color(0x99FFFFFF), fontSize = 12.sp)
                    mostrar.forEach { (t, i, f) ->
                        val ag = i <= now && f > now
                        val passou = f <= now
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .background(if (ag) Color(0x33E50914) else Color.Transparent, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${timeFormat(i)} – ${timeFormat(f)}", fontSize = 12.sp,
                                color = when { ag -> Color(0xFFFFD34D); passou -> Color(0x77FFFFFF); else -> Color(0xCCFFFFFF) },
                                modifier = Modifier.width(110.dp),
                            )
                            Text(
                                (if (ag) "AGORA  " else "") + t, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (ag) FontWeight.Bold else FontWeight.Normal,
                                color = when { ag -> Color.White; passou -> Color(0x77FFFFFF); else -> Color(0xDDFFFFFF) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (ag) {
                            val total = (f - i).coerceAtLeast(1)
                            Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                                Box(Modifier.fillMaxWidth(((now - i).toFloat() / total).coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent))
                            }
                        }
                    }
                }
            }
        }
        }
    }
    val lista: @Composable (Modifier) -> Unit = { mod ->
        ListaDeCanais(mod, channels, groupLabel, current, favorites, onPrevGroup, onNextGroup, onPick, onFull, onFavorite, groups, currentGroup, onGroup)
    }
    if (compacto) {
        Column(Modifier.fillMaxSize()) {
            previa(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            lista(Modifier.fillMaxWidth().weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            lista(Modifier.weight(0.42f).fillMaxHeight())
            Spacer(Modifier.width(22.dp))
            previa(Modifier.weight(0.58f).fillMaxHeight())
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
) {
    var verGrupos by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val inicio = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(channels) { runCatching { listState.scrollToItem((inicio - 2).coerceAtLeast(0)) } }
    Column(modifier.background(Color(0x55000000), RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("◀", onClick = onPrevGroup)
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                LinhaFocavel(selecionado = verGrupos, onClick = { verGrupos = !verGrupos }) {
                    Text(
                        (groups.firstOrNull { it.first == currentGroup }?.second ?: groupLabel.ifBlank { "Lista de Canais" }) + if (verGrupos) "  ▲" else "  ▼",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
            }
            PillButton("▶", onClick = onNextGroup)
        }
        if (verGrupos && groups.isNotEmpty()) {
            // Grupos de canais (o ★ Favoritos é um deles).
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(groups) { (f, nome) ->
                    LinhaFocavel(selecionado = f == currentGroup, onClick = { onGroup(f); verGrupos = false }) {
                        Text(nome, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
            return@Column
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
                    onLongClick = { onFavorite(ch) },
                    onClick = { if (tocando) onFull() else onPick(ch) },
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                            if (!ch.logoUrl.isNullOrBlank()) AsyncImage(ch.logoUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
                            else Text(ch.name.take(2).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("${ch.number}", color = Color(0xCCFFFFFF), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                        Text(
                            ch.name.uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (ch.id in favorites) Text("★", color = Palette.Favorite, fontSize = 15.sp)
                        if (tocando) Text("  ▶", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
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
private fun Atalho(label: String, cor: Color, modifier: Modifier, compacto: Boolean, texto: Color = Color.White, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (compacto) 52.dp else 72.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = cor, focusedContainerColor = cor),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.05f)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color.Transparent))), contentAlignment = Alignment.Center) {
            Text(label, color = texto, fontSize = if (compacto) 14.sp else 18.sp, fontWeight = FontWeight.Bold)
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
                if (!item.poster.isNullOrBlank()) AsyncImage(item.poster, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
    Row(Modifier.fillMaxSize()) {
        if (!compacto) {
            Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Atalho("Todos", Color(0xFF43A047), Modifier.fillMaxWidth().weight(1f), compacto) { filtro = 0 }
                Atalho("Ao vivo", Color(0xFFFB8C00), Modifier.fillMaxWidth().weight(1f), compacto) { filtro = 1 }
                Atalho("Encerrados", Color(0xFF039BE5), Modifier.fillMaxWidth().weight(1f), compacto) { filtro = 2 }
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
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(lista) { j ->
                    LinhaFocavel(onClick = { onAssistir(j) }) {
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
    val naoComecou = j.estado == "pre" && j.inicio > agora
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
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("O jogo começa às $hora", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(relogio, color = Color(0xFFFFD54F), fontSize = if (compacto) 30.sp else 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                            Text("O canal abre sozinho na hora do jogo", color = Color(0x99FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(10.dp))
                            BarrinhaCarregando(Modifier.width(140.dp))
                        }
                    }
                } else if (canal == null) {
                    Text("Nenhum canal encontrado.\nUse \"Mudar de canal\".", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    BotaoQuadrado(AppIcons.Grid, "Tela cheia", Color(0xFFD32F2F), Modifier.focusRequester(foco)) { if (canal != null) onFull() }
                    BotaoQuadrado(AppIcons.Refresh, "Mudar de canal", Color(0x55FFFFFF), Modifier, onMudar)
                    if (j.estado == "pre") BotaoQuadrado(AppIcons.History, if (lembrete) "Lembrete ligado" else "Lembrete", if (lembrete) Color(0xFFF9A825) else Color(0x55FFFFFF), Modifier) {
                        lembrete = io.github.axon07.channelbox.data.ZarkLembretes.alternar(ctx, j.id, j.inicio, "${j.casa.nome} x ${j.fora.nome}")
                        vm.showToast(if (lembrete) "Vamos te avisar quando o jogo começar" else "Lembrete removido")
                    }
                    BotaoQuadrado(AppIcons.Close, "Voltar", Color(0x55FFFFFF), Modifier, onVoltar)
                }
                if (canalInfo.isNotBlank()) Text(canalInfo, color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Modifier.fillMaxWidth().heightIn(min = 260.dp)
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
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.align(Alignment.End)) {
            Legenda(Color.White, "Gol")
            Legenda(Color(0xFFE53935), "Cartão vermelho")
            Legenda(Color(0xFFFDD835), "Cartão amarelo")
            Legenda(Color(0xFF66BB6A), "Substituição")
        }
        Spacer(Modifier.height(12.dp))
        if (d.lances.isEmpty()) Text(if (j.estado == "pre") "O jogo ainda não começou." else "Nenhum lance registrado ainda.", color = Color.White, fontSize = 15.sp)
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
        Box(Modifier.size(width = 12.dp, height = 16.dp).background(cor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(nome, color = Color.White, fontSize = 13.sp)
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
