package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/** Fundo vermelho do ZarkTV (no estilo da UniTV). */
val FundoZark: Brush get() = ZarkTema.atual.fundo

/** Abas da tela principal. */
object Aba {
    const val TV = 0
    const val FAVORITOS = 1
    const val DESTAQUES = 2
    const val FILMES = 3
    const val SERIES = 4
    const val KIDS = 5
    const val ANIME = 6
    val nomes = listOf("TV", "★ FAVORITOS", "DESTAQUES", "FILMES", "SÉRIES", "KIDS", "ANIME")

    /** Abas com a prévia do canal (o vídeo fica ligado). */
    fun temPrevia(tab: Int) = tab == TV || tab == FAVORITOS
}

private val REGEX_KIDS = Regex("infantil|kids|desenho|crian|anima[cç][aã]o|disney|cartoon|pixar", RegexOption.IGNORE_CASE)
private val REGEX_ANIME = Regex("anime", RegexOption.IGNORE_CASE)

/**
 * Tela principal no fluxo da UniTV: logo, ícones (buscar, histórico, perfil), abas e o conteúdo da aba.
 * Na aba TV a prévia do canal é desenhada pelo player no retângulo informado em [onPreviewRect].
 */
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
    // Topo
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onProfile: () -> Unit,
    onRenew: () -> Unit,
) {
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
    var avisoFechado by remember { mutableStateOf(false) }
    val tabFocus = remember { List(Aba.nomes.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { tabFocus[tab].requestFocus() }
    }
    if (!Aba.temPrevia(tab)) LaunchedEffect(Unit) { onPreviewRect(null) }

    // O fundo é desenhado atrás do vídeo (TvApp), para a prévia do canal aparecer.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compacto = maxWidth < 700.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (compacto) 10.dp else 28.dp, vertical = if (compacto) 8.dp else 14.dp)) {
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
                IconeRedondo(AppIcons.Home, "Perfil", onProfile)
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(if (compacto) 4.dp else 14.dp), modifier = Modifier.fillMaxWidth()) {
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
                    )
                    Aba.FAVORITOS -> AbaTv(
                        compacto, favChannels, "★ Meus favoritos", current, favorites, upcoming, now, timeFormat,
                        {}, {}, onPickChannel, onFull, onFavorite, onPreviewRect,
                    )
                    Aba.DESTAQUES -> AbaDestaques(vm, compacto, extras.banners, onOpenItem)
                    Aba.FILMES -> AbaCatalogo(vm, VodKind.MOVIE, compacto, null, onOpenItem, onOpenLibrary)
                    Aba.SERIES -> AbaCatalogo(vm, VodKind.SERIES, compacto, null, onOpenItem, onOpenLibrary)
                    Aba.KIDS -> AbaCatalogo(vm, null, compacto, REGEX_KIDS, onOpenItem, onOpenLibrary)
                    Aba.ANIME -> AbaCatalogo(vm, null, compacto, REGEX_ANIME, onOpenItem, onOpenLibrary)
                }
            }
            if (userName.isNotBlank() && !compacto) {
                Text(
                    "Conta: $userName" + (conta?.expira?.let { " · vence em " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(it)) } ?: ""),
                    color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Aviso no cantinho: vencimento (5, 3, 1 dia, hoje, vencido) e recado do painel.
        val textoVenc = when {
            dias == null || dias > 5 -> null
            dias < 0 -> "Seu acesso venceu. Renove agora para continuar assistindo."
            dias == 0 -> "Seu acesso vence HOJE! Renove agora."
            dias == 1 -> "Falta 1 dia para o seu acesso vencer."
            else -> "Faltam $dias dias para o seu acesso vencer."
        }
        if (!avisoFechado && (textoVenc != null || extras.aviso != null)) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp).width(330.dp)
                    .background(Color(0xF0141414), RoundedCornerShape(12.dp))
                    .border(2.dp, if (textoVenc != null) Color(0xFFFFB300) else Color(0x55FFFFFF), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (textoVenc != null) "⏰ Aviso de vencimento" else "📢 Aviso", color = Color(0xFFFFD54F), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("✕", color = Color.White, fontSize = 16.sp, modifier = Modifier.tap(onClick = { avisoFechado = true }).padding(4.dp))
                }
                textoVenc?.let { Text(it, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                extras.aviso?.let { Text(it, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                if (textoVenc != null) {
                    Spacer(Modifier.height(10.dp))
                    PillButton("Renovar agora", primary = true, onClick = onRenew)
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0x33FFFFFF),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(
            Modifier
                .border(if (selecionada) 2.dp else 0.dp, if (selecionada) Color(0xFFFFB74D) else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(horizontal = if (compacto) 12.dp else 22.dp, vertical = if (compacto) 6.dp else 9.dp)
        ) {
            if (nome == "KIDS") {
                Row {
                    val cores = listOf(Color(0xFFFF6B9D), Color(0xFF4FC3F7), Color(0xFFFFD54F), Color(0xFF81C784))
                    nome.forEachIndexed { i, c -> Text(c.toString(), color = cores[i % 4], fontSize = if (compacto) 15.sp else 21.sp, fontWeight = FontWeight.Black) }
                }
            } else {
                Text(
                    nome, color = Color.White, fontSize = if (compacto) 15.sp else 21.sp,
                    fontWeight = if (selecionada || focused) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun IconeRedondo(icone: ImageVector, descricao: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp).onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x33FFFFFF), focusedContainerColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppIcon(icone, tint = if (focused) Palette.Accent else Color.White, size = 20.dp)
        }
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
) {
    val previa: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .border(2.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp))
                    .onGloballyPositioned { onPreviewRect(it.boundsInRoot()) }
                    .tap(onClick = { if (current != null) onFull() })
            ) {
                if (current == null) Text("Escolha um canal na lista", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
                else Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(6.dp)
                ) { Text("⛶", color = Color.White, fontSize = 18.sp) }
            }
            Box(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column {
                    Text(current?.name ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val progs = current?.epgId?.let { upcoming[it] }.orEmpty()
                    progs.firstOrNull { it.start <= now && it.stop > now }?.let {
                        Text("${timeFormat(it.start)} – ${timeFormat(it.stop)}  ${it.title}", color = Color(0xFFFFD34D), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    val lista: @Composable (Modifier) -> Unit = { mod ->
        ListaDeCanais(mod, channels, groupLabel, current, favorites, onPrevGroup, onNextGroup, onPick, onFull, onFavorite)
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
            previa(Modifier.weight(0.58f))
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
) {
    val listState = rememberLazyListState()
    val inicio = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(channels) { runCatching { listState.scrollToItem((inicio - 2).coerceAtLeast(0)) } }
    Column(modifier.background(Color(0x55000000), RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("◀", onClick = onPrevGroup)
            Text(
                groupLabel.ifBlank { "Lista de Canais" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            PillButton("▶", onClick = onNextGroup)
        }
        if (channels.isEmpty()) {
            Text("Nenhum canal neste grupo", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
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
    val filmes by remember { vm.vodTop(VodKind.MOVIE, null, 12) }.collectAsState(initial = emptyList())
    val series by remember { vm.vodTop(VodKind.SERIES, null, 12) }.collectAsState(initial = emptyList())
    val todos = remember(filmes, series) { (filmes.zip(series).flatMap { listOf(it.first, it.second) } + filmes.drop(series.size) + series.drop(filmes.size)).take(16) }
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
                        delay(6000)
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
                val altura = if (compacto) 200.dp else (alturaTotal - (if (temBanner) alturaBanner + 10.dp else 0.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(todos, key = { it.id }) { item ->
                        PosterTile(item, altura * (2f / 3f), altura, mostrarTitulo = false) { onOpen(item) }
                    }
                }
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (kind != null) item {
            // Atalhos coloridos (como Gênero / 2026 / No cinema / Todos na UniTV).
            val lanc = (if (kind == VodKind.MOVIE) catsFilmes else catsSeries)
                .firstOrNull { Regex("lan[cç]amento|2026|novidade|cinema", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }?.name
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Atalho("Todos", Color(0xFF1E88E5), Modifier.weight(1f), compacto) { onLibrary(kind, null) }
                Atalho("Lançamentos", Color(0xFFE53935), Modifier.weight(1f), compacto) { onLibrary(kind, lanc) }
                Atalho("Continuar", Color(0xFFFDD835), Modifier.weight(1f), compacto, texto = Color(0xFF222222)) { onLibrary(kind, VOD_CONTINUE) }
                Atalho("Gêneros", Color(0xFF43A047), Modifier.weight(1f), compacto) { onLibrary(kind, null) }
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
        items(fileiras, key = { "${it.first}|${it.second}" }) { (t, cat, titulo) ->
            Fileira(vm, t, cat, titulo, compacto, onOpen) { onLibrary(t, cat) }
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
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
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
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
