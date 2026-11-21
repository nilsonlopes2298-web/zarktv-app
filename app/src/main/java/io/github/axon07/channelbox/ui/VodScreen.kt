package io.github.axon07.channelbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.VodProgressEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate

/** What the Movies / TV Shows screens are showing. Kept outside the screen so it survives playback. */
@Stable
class VodBrowser {
    var kind by mutableStateOf(VodKind.MOVIE)
    /** null = all, [VOD_CONTINUE] = continue watching, else a category name. */
    var category by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var items by mutableStateOf<List<VodEntity>?>(null)
    var show by mutableStateOf<VodEntity?>(null)
    /** ZarkTV: filme aberto na ficha (sinopse, elenco, Assistir). */
    var movie by mutableStateOf<VodEntity?>(null)
    /** ZarkTV: só as categorias que batem (Anime, Kids, Adulto…); null = todas. */
    var filtroCat by mutableStateOf<Regex?>(null)
    /** ZarkTV: miniatura que toca na ficha. Onde ela fica, o fundo da ficha e as ações. */
    var previewRect by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var fundoUrl by mutableStateOf<String?>(null)
    var previewId by mutableStateOf<String?>(null)
    var acaoPreview: ((VodEntity, VodEntity?) -> Unit)? = null
    var acaoTelaCheia: (() -> Unit)? = null
    /** Aberto da tela principal: o botão Voltar da ficha volta para ela. */
    var voltarInicio: (() -> Unit)? = null
    var episodes by mutableStateOf<List<VodEntity>?>(null)
    var season by mutableStateOf<Int?>(null)
    val grid = LazyGridState()
    val episodeList = LazyListState()
    /** Item to put the focus back on (after playback, a dialog, or closing a show). */
    var focusId by mutableStateOf<String?>(null)
    var focusToken by mutableIntStateOf(0)
    internal var listKey = ""

    fun open(kind: String) {
        if (filtroCat != null) { filtroCat = null; category = null; items = null }
        if (this.kind != kind) {
            this.kind = kind
            category = null
            query = ""
            items = null
        }
        show = null
        movie = null
        episodes = null
        focusToken++
    }

    fun openShow(s: VodEntity) {
        focusId = null
        show = s
        episodes = null
        season = null
    }

    fun closeShow() {
        focusId = show?.id
        show = null
        episodes = null
        focusToken++
    }
}

@Composable
fun VodScreen(b: VodBrowser, vm: TvViewModel, onPlay: (item: VodEntity, show: VodEntity?) -> Unit) {
    val progress by vm.vodProgress.collectAsStateWithLifecycle()
    val show = b.show
    // Hold OK on a category: lock or hide it.
    var categoryMenu by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        val movie = b.movie
        if (movie != null) MovieDetail(b, movie, progress[movie.id], vm, onPlay)
        else if (show != null) ShowScreen(b, show, progress, vm, onPlay)
        else LibraryScreen(b, progress, vm, onPlay, onCategoryMenu = { cat, locked -> categoryMenu = cat to locked })
        categoryMenu?.let { (cat, locked) -> CategoryMenu(b, vm, cat, locked) { categoryMenu = null } }
    }
}

@Composable
private fun CategoryMenu(b: VodBrowser, vm: TvViewModel, category: String, locked: Boolean, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    // The hold that opened this menu is still going: its repeats and release mustn't pick a row.
    var armed by remember { mutableStateOf(false) }
    DialogCard(
        modifier = Modifier.onPreviewKeyEvent { e ->
            val n = e.nativeKeyEvent
            val ok = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                n.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || n.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
            when {
                !ok || armed -> false
                n.action == android.view.KeyEvent.ACTION_DOWN && n.repeatCount == 0 -> { armed = true; false } // a new press
                n.action == android.view.KeyEvent.ACTION_UP -> { armed = true; true }
                else -> true
            }
        },
    ) {
        DialogTitle(category)
        Spacer(Modifier.height(12.dp))
        DialogRow(if (locked) "Unlock" else "Lock (needs the PIN)", Modifier.focusRequester(first)) {
            vm.setVodCategoryLocked(b.kind, category, !locked)
            onClose()
        }
        DialogRow("Hide this category") {
            vm.setVodCategoriesHidden(b.kind, listOf(category), true)
            vm.showToast("$category hidden – show it again in Settings → Playlist & guide")
            if (b.category == category) b.category = null
            onClose()
        }
        DialogRow("Cancel", dim = true) { onClose() }
    }
}

// ---- Poster grid with categories -----------------------------------------------------------------

@Composable
private fun LibraryScreen(
    b: VodBrowser,
    progress: Map<String, VodProgressEntity>,
    vm: TvViewModel,
    onPlay: (VodEntity, VodEntity?) -> Unit,
    onCategoryMenu: (String, Boolean) -> Unit,
) {
    val movies = b.kind == VodKind.MOVIE
    val total by (if (movies) vm.movieCount else vm.showCount).collectAsStateWithLifecycle()
    val todasCats by remember(b.kind) { vm.vodCategories(b.kind) }.collectAsState(initial = emptyList())
    val categories = remember(todasCats, b.filtroCat) { b.filtroCat?.let { r -> todasCats.filter { r.containsMatchIn(it.name) } } ?: todasCats }
    // Com filtro (Anime, Kids…) "Todos" mostraria o catálogo inteiro: abre na primeira categoria filtrada.
    LaunchedEffect(categories, b.filtroCat) {
        if (b.filtroCat != null && b.category == null && b.query.isBlank()) categories.firstOrNull()?.let { b.category = it.name }
    }
    val continueCount by remember(b.kind) { vm.vodContinueCount(b.kind) }.collectAsState(initial = 0)
    val overrides by vm.overrides.collectAsStateWithLifecycle()

    // Load the items for the chosen category / search (kept in [b] so returning is instant).
    LaunchedEffect(b.kind, b.category, b.query) {
        val key = "${b.kind}|${b.category}|${b.query}"
        if (key != b.listKey) {
            if (b.listKey.isNotEmpty()) b.items = null
            b.listKey = key
            // Em paralelo: a grade ainda não está na tela, e esperar por ela travava o carregamento.
            launch { runCatching { b.grid.scrollToItem(0) } }
        }
        if (b.query.isNotBlank()) delay(300) // wait for typing to pause
        // Search needs 2+ letters; Room re-runs the query as new titles are saved during a reload.
        val q = b.query.trim().takeIf { it.length >= 2 }.orEmpty()
        // ZarkTV: sem busca, lê só os primeiros 400 títulos (rápido em TV box e tablet).
        (if (q.isNotEmpty()) vm.vodItems(b.kind, b.category, q) else vm.vodTop(b.kind, b.category, 400))
            .conflate()
            .collect { b.items = it }
    }

    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val categoryFocus = remember { FocusRequester() }
    LaunchedEffect(b.focusToken) {
        if (!touch && b.focusId == null) {
            delay(60)
            runCatching { categoryFocus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().background(FundoZark)) {
        // ---- Left: title, search, categories ----
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(Color(0x55000000))
                .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
        ) {
            TvTextField("Buscar", b.query, { b.query = it })
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                val entries = buildList {
                    if (b.filtroCat == null) {
                        if (continueCount > 0) add(Triple(VOD_CONTINUE, "Continuar assistindo", continueCount))
                        add(Triple(VOD_FAVORITOS, "★ Meus favoritos", 0))
                        add(Triple(null, "Todos", total))
                    }
                    categories.forEach { add(Triple(it.name, nomeCategoria(it.name), it.count)) }
                }
                items(entries, key = { it.first ?: "\u0000all" }) { (id, label, count) ->
                    val selected = b.query.isBlank() && b.category == id
                    val real = id != null && id != VOD_CONTINUE && id != VOD_FAVORITOS
                    val locked = real && overrides[TvViewModel.vodKey(b.kind, id!!)]?.locked == true
                    CategoryEntry(
                        label = label,
                        count = count,
                        selected = selected,
                        locked = locked,
                        modifier = if (selected) Modifier.focusRequester(categoryFocus) else Modifier,
                        // A locked category only opens with the PIN, not just by moving over it.
                        onFocus = { if (b.query.isBlank() && (!locked || vm.unlocked)) b.category = id },
                        onClick = {
                            val open = {
                                b.query = ""
                                b.category = id
                            }
                            if (locked) vm.requirePin("Enter your PIN to open $label", action = open) else open()
                        },
                        onLongClick = if (real) ({ onCategoryMenu(id!!, locked) }) else null,
                    )
                }
            }
        }

        // ---- Right: posters ----
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp, start = 24.dp, end = 16.dp)) {
            val items = b.items
            val heading = (if (movies) "Filmes > " else "Séries > ") + when {
                b.query.isNotBlank() -> "Busca: “${b.query.trim()}”"
                b.category == VOD_CONTINUE -> "Continuar assistindo"
                b.category == VOD_FAVORITOS -> "Meus favoritos"
                b.category == null -> "Todos"
                else -> nomeCategoria(b.category.orEmpty())
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(heading, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (items != null) {
                    val more = items.size >= (if (b.query.isNotBlank()) VOD_SEARCH_LIMIT else VOD_PAGE)
                    Text(
                        if (more) "" else "",
                        fontSize = 13.sp, color = Palette.TextDim,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // Fetch the posters just below what's on screen, so they're ready when you scroll to them.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(items) {
                val list = items ?: return@LaunchedEffect
                val loader = coil.Coil.imageLoader(ctx)
                val asked = HashSet<String>()
                androidx.compose.runtime.snapshotFlow { b.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                    .collect { last ->
                        val from = (last + 1).coerceAtMost(list.size)
                        val to = (last + 1 + PREFETCH).coerceAtMost(list.size)
                        list.subList(from, to).forEach { item ->
                            val url = item.poster?.takeIf { it.isNotBlank() } ?: return@forEach
                            if (asked.add(url)) {
                                loader.enqueue(coil.request.ImageRequest.Builder(ctx).data(url).size(300, 450).build())
                            }
                        }
                    }
            }
            when {
                items == null -> Text("Carregando…", color = Palette.TextDim, fontSize = 14.sp)
                b.query.isNotBlank() && b.query.trim().length < 2 -> Text("Digite pelo menos 2 letras", color = Palette.TextDim, fontSize = 14.sp)
                items.isEmpty() -> Text(
                    if (b.query.isNotBlank()) "Nada encontrado" else "Nada aqui ainda",
                    color = Palette.TextDim, fontSize = 14.sp,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(132.dp),
                    state = b.grid,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        PosterCard(
                            item = item,
                            progress = progress[item.id],
                            focusMe = item.id == b.focusId,
                            focusToken = b.focusToken,
                            onFocused = { if (b.focusId == item.id) b.focusId = null },
                            onClick = {
                                b.focusId = item.id
                                if (item.kind == VodKind.SERIES) b.openShow(item)
                                else b.movie = item // ZarkTV: abre a ficha antes de tocar
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryEntry(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    locked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .tap(onLongClick = onLongClick, onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x1AFFFFFF) else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Color(0x29FFFFFF),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0x33FFFFFF),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = RoundedCornerShape(8.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (locked) {
                AppIcon(AppIcons.Lock, tint = Palette.TextDim, size = 14.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                count.toString(), fontSize = 11.sp, color = Palette.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1AFFFFFF)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PosterCard(
    item: VodEntity,
    progress: VodProgressEntity?,
    focusMe: Boolean,
    focusToken: Int,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (focusMe) {
        LaunchedEffect(focusToken) {
            delay(60)
            runCatching { requester.requestFocus() }
        }
    }
    Column {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .tap(onClick = onClick),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.Surface,
                focusedContainerColor = Palette.Surface,
                pressedContainerColor = Palette.Surface,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Palette.Accent), shape = RoundedCornerShape(6.dp)),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.06f)),
        ) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(item.poster, item.title, Modifier.fillMaxSize())
                if (progress != null && progress.duration > 0) {
                    ProgressBar(
                        progress.position.toFloat() / progress.duration,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, fontSize = 12.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
    }
}

/** Poster or thumbnail; a quiet title card when there's no picture or it fails to load. */
@Composable
private fun PosterImage(
    url: String?,
    title: String,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Pixel size to decode at: small enough that thousands stay in the memory cache. */
    decodeWidth: Int = 300,
    decodeHeight: Int = 450,
) {
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) {
        coil.request.ImageRequest.Builder(context)
            .data(url)
            .size(decodeWidth, decodeHeight)
            .crossfade(false)
            .build()
    }
    Box(
        modifier.background(Color(0xFF151C25)),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(
                title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim, maxLines = 4,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp),
            )
        } else {
            AsyncImage(
                model = request, contentDescription = null, contentScale = contentScale,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- One show: seasons and episodes ----------------------------------------------------------------

@Composable
private fun ShowScreen(
    b: VodBrowser,
    show: VodEntity,
    progress: Map<String, VodProgressEntity>,
    vm: TvViewModel,
    onPlay: (VodEntity, VodEntity?) -> Unit,
) {
    LaunchedEffect(show.id) {
        if (b.episodes == null) {
            val list = vm.episodes(show)
            b.episodes = list
            val last = list.filter { it.id in progress }.maxByOrNull { progress.getValue(it.id).updated }
            b.season = last?.season ?: list.firstOrNull()?.season
            b.focusId = last?.id
            b.focusToken++
        }
    }
    var ficha by remember(show.id) { mutableStateOf<io.github.axon07.channelbox.data.ZarkInfo?>(null) }
    LaunchedEffect(show.id) { ficha = vm.zarkFicha(show) }
    val episodes = b.episodes
    val seasons = episodes?.map { it.season }?.distinct().orEmpty()
    val inSeason = episodes?.filter { it.season == b.season }.orEmpty()
    val ultimo = episodes?.filter { it.id in progress }?.maxByOrNull { progress.getValue(it.id).updated }
    var listaTemporadas by remember { mutableStateOf(false) }
    val primeiro = remember { FocusRequester() }
    LaunchedEffect(episodes != null) {
        delay(150)
        runCatching { primeiro.requestFocus() }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(ficha, show.id) { b.fundoUrl = ficha?.fundo ?: show.poster }
    // Começa a tocar na miniatura assim que os episódios chegam (último assistido ou o primeiro).
    val retratoAuto = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }
    LaunchedEffect(show.id, episodes != null) {
        if (!retratoAuto && episodes != null && b.previewId == null) {
            delay(500)
            (ultimo ?: episodes.firstOrNull { it.season == b.season } ?: episodes.firstOrNull())?.let { b.acaoPreview?.invoke(it, show) }
        }
    }
    // O fundo é desenhado atrás do vídeo (TvApp), senão cobriria a miniatura.
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 36.dp, vertical = 24.dp)
        ) {
            val retratoS = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }
            val tocarSerie = {
                (ultimo ?: inSeason.firstOrNull())?.let { b.focusId = it.id; onPlay(it, show) }
                Unit
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.25f)) {
                    FichaCabecalho(show.title, ficha, show.plot, show.rating, show.year, ultimo?.let { "T${it.season} - E${it.episode}" })
                }
                if (!retratoS) {
                    Spacer(Modifier.width(24.dp))
                    MiniPlay(b, ficha?.fundo ?: show.poster, show.title, Modifier.weight(1f), tocarSerie)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                val alvo = ultimo ?: inSeason.firstOrNull()
                BotaoFicha(if (ultimo != null) "⛶  Tela cheia · T${ultimo.season}E${ultimo.episode}" else "⛶  Tela cheia", true, Modifier.focusRequester(primeiro)) {
                    if (b.previewId != null && b.acaoTelaCheia != null) b.acaoTelaCheia?.invoke()
                    else alvo?.let { b.focusId = it.id; onPlay(it, show) }
                }
                BotoesExtras(vm, show, serie = true, episodios = episodes?.size ?: 0)
                BotaoFicha("Player externo", false) { alvo?.url?.let { io.github.axon07.channelbox.data.ZarkConfig.abrirExterno(ctx, it, show.title) } }
                BotaoFicha("‹ Voltar", false) {
                    b.closeShow()
                    b.voltarInicio?.invoke()
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                episodes == null -> Text("Carregando episódios…", color = Color.White, fontSize = 16.sp)
                episodes.isEmpty() -> Text("Nenhum episódio encontrado", color = Color.White, fontSize = 16.sp)
                else -> {
                    // Seletor de temporada (abre a lista ao clicar) e o intervalo de episódios.
                    // Temporada + faixas de episódios (1-10, 11-20…) para não virar uma lista enorme.
                    val faixas = inSeason.chunked(10)
                    var faixa by remember(b.season) { mutableStateOf(faixas.indexOfFirst { l -> l.any { it.id == ultimo?.id } }.coerceAtLeast(0)) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                        if (seasons.isNotEmpty()) {
                            BotaoFicha((if (b.season == 0) "Episódios" else "Temporada ${b.season}") + if (listaTemporadas) "  ▲" else "  ▼", false) {
                                listaTemporadas = !listaTemporadas
                            }
                            Spacer(Modifier.width(20.dp))
                        }
                        faixas.forEachIndexed { i, l ->
                            val ini = inSeason.indexOf(l.first()) + 1
                            val fim = ini + l.size - 1
                            var ff by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { faixa = i },
                                modifier = Modifier.padding(end = 6.dp).onFocusChanged { ff = it.isFocused; if (it.isFocused) faixa = i }.tap(onClick = { faixa = i }),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x33FFFFFF)),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            ) {
                                Text("$ini-$fim", color = if (i == faixa) Color(0xFFFF5252) else Color.White, fontSize = 20.sp, fontWeight = if (i == faixa) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                    if (listaTemporadas) {
                        Column(Modifier.padding(top = 4.dp).width(260.dp).background(Color(0xF2FFFFFF))) {
                            seasons.forEach { t ->
                                BotaoLista(if (t == 0) "Episódios" else "Temporada $t", t == b.season) {
                                    b.season = t
                                    listaTemporadas = false
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Episódios em botões numerados (como na UniTV).
                    listOfNotNull(faixas.getOrNull(faixa)).forEach { linha ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                            linha.forEach { ep ->
                                val n = if (ep.episode > 0) ep.episode else inSeason.indexOf(ep) + 1
                                val p = progress[ep.id]
                                BotaoNumero(
                                    if (ep.id == b.previewId || (b.previewId == null && ep.id == ultimo?.id)) "▶" else "$n",
                                    visto = p != null && p.duration > 0 && p.position > p.duration * 0.9,
                                ) {
                                    b.focusId = ep.id
                                    // Toca na miniatura (para escolher com calma); no celular em pé abre direto.
                                    val prev = b.acaoPreview
                                    if (prev != null && !retratoAuto) prev(ep, show) else onPlay(ep, show)
                                }
                            }
                        }
                    }
                }
            }
            Recomendados(vm, io.github.axon07.channelbox.data.VodKind.SERIES, show) { b.openShow(it) }
        }
    }
}

@Composable
private fun EpisodeRow(ep: VodEntity, progress: VodProgressEntity?, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0FFFFFFF),
            contentColor = Palette.Text,
            focusedContainerColor = Color(0x29FFFFFF),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0x33FFFFFF),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = RoundedCornerShape(8.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                PosterImage(ep.poster, "E${ep.episode}", Modifier.fillMaxSize(), decodeWidth = 320, decodeHeight = 180)
                if (progress != null && progress.duration > 0) {
                    ProgressBar(
                        progress.position.toFloat() / progress.duration,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(5.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (ep.episode > 0) "${ep.episode}. " else "") + ep.title,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                ep.plot?.let {
                    Text(it, fontSize = 12.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                val meta = listOfNotNull(
                    ep.rating, // episode length for Xtream
                    progress?.let { "Parou em ${formatClock(it.position)}" },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 3.dp))
            }
            AppIcon(AppIcons.Play, tint = Palette.TextDim, size = 20.dp)
        }
    }
}

/** Posters fetched ahead of scrolling. */
private const val PREFETCH = 36


/** Imagem de fundo da ficha, escurecida para o texto aparecer. */
@Composable
private fun FundoFicha(url: String) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20A0A0A), Color(0xB30A0A0A), Color(0x660A0A0A)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0A0A)))))
    }
}

/** Ficha do filme (estilo UniTV/play): capa, título, nota, data, gênero, elenco, sinopse e Assistir. */
@Composable
private fun MovieDetail(
    b: VodBrowser,
    movie: VodEntity,
    progress: VodProgressEntity?,
    vm: TvViewModel,
    onPlay: (VodEntity, VodEntity?) -> Unit,
) {
    var ficha by remember(movie.id) { mutableStateOf<io.github.axon07.channelbox.data.ZarkInfo?>(null) }
    LaunchedEffect(movie.id) { ficha = vm.zarkFicha(movie) }
    val play = remember { FocusRequester() }
    LaunchedEffect(movie.id) {
        delay(150)
        runCatching { play.requestFocus() }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val retrato = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }
    val assistir = {
        b.focusId = movie.id
        onPlay(movie, null)
    }
    LaunchedEffect(ficha, movie.id) { b.fundoUrl = ficha?.fundo ?: movie.poster }
    LaunchedEffect(movie.id) {
        if (!retrato) {
            delay(500)
            b.acaoPreview?.invoke(movie, null)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = if (retrato) 18.dp else 36.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.25f)) {
                    FichaCabecalho(movie.title, ficha, movie.plot, movie.rating, movie.year, null)
                }
                if (!retrato) {
                    Spacer(Modifier.width(24.dp))
                    MiniPlay(b, ficha?.fundo ?: movie.poster, movie.title, Modifier.weight(1f), assistir)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                BotaoFicha(if (progress != null && progress.position > 0) "⛶  Continuar" else "⛶  Tela cheia", true, Modifier.focusRequester(play)) {
                    if (b.previewId == movie.id && b.acaoTelaCheia != null) b.acaoTelaCheia?.invoke() else assistir()
                }
                BotoesExtras(vm, movie, serie = false, episodios = 0)
                BotaoFicha("Player externo", false) { movie.url?.let { io.github.axon07.channelbox.data.ZarkConfig.abrirExterno(ctx, it, movie.title) } }
                BotaoFicha("‹ Voltar", false) {
                    b.movie = null
                    b.focusToken++
                    b.voltarInicio?.invoke()
                }
            }
            Recomendados(vm, VodKind.MOVIE, movie) { b.movie = it }
        }
    }
}

/** Título, nota, data, gêneros em etiquetas, direção, elenco e sinopse (com "Mais"). */
@Composable
private fun FichaCabecalho(
    titulo: String,
    ficha: io.github.axon07.channelbox.data.ZarkInfo?,
    plot: String?,
    rating: String?,
    ano: String?,
    destaque: String?,
) {
    var mais by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(titulo, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        (ficha?.nota ?: rating)?.toDoubleOrNull()?.takeIf { it > 0 }?.let {
            Spacer(Modifier.width(12.dp))
            Text(String.format(java.util.Locale.US, "%.1f", it), color = Color(0xFFFFC107), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
    val meta = listOfNotNull(ficha?.data ?: ano, ficha?.duracao).joinToString("  |  ")
    if (meta.isNotEmpty()) Text(meta, color = Color(0xFFB0B8C8), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    destaque?.let { Text(it, color = Color(0xFFFFA726), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
    val generos = ficha?.genero?.split(",", "/", "|")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (generos.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Text("Gênero : ", color = Color(0xFFB0B8C8), fontSize = 13.sp)
            generos.take(5).forEach { g ->
                Text(
                    g, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 6.dp).border(1.dp, Color(0xCCFFFFFF), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
    LinhaInfo("Direção", ficha?.diretor)
    LinhaInfo("Elenco", ficha?.elenco)
    val sinopse = ficha?.sinopse ?: plot
    Row(Modifier.padding(top = 6.dp)) {
        Text("Sinopse :  ", color = Color(0xFFB0B8C8), fontSize = 13.sp)
        Column {
            Text(sinopse ?: "Sem informações", color = Color(0xE6FFFFFF), fontSize = 13.sp, lineHeight = 19.sp, maxLines = if (mais) 20 else 3, overflow = TextOverflow.Ellipsis)
            if (!mais && (sinopse?.length ?: 0) > 160) {
                Text("Mais", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.tap(onClick = { mais = true }).padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun LinhaInfo(rotulo: String, valor: String?) {
    Row(Modifier.padding(top = 4.dp)) {
        Text("$rotulo :  ", color = Color(0xFFB0B8C8), fontSize = 13.sp)
        Text(valor ?: "Sem informações", color = Color(0xE6FFFFFF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Botão da ficha (Tela cheia, Player externo, Temporada…): cinza-azulado, vermelho no foco. */
@Composable
private fun BotaoFicha(label: String, principal: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp).widthIn(min = 120.dp).onFocusChanged { f = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (principal) Color(0xCCB71C1C) else Color(0x80596273),
            focusedContainerColor = Color(0xFFE53935),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.04f)),
    ) {
        Box(Modifier.padding(horizontal = 16.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun BotaoLista(label: String, marcado: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).onFocusChanged { f = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (marcado) Color(0xFFE53935) else Color.Transparent, focusedContainerColor = Color(0xFFE53935)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (marcado || f) Color.White else Color(0xFF555555), fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (marcado) Text("✓", color = Color.White, fontSize = 17.sp)
        }
    }
}

@Composable
private fun BotaoNumero(label: String, visto: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 70.dp, height = 40.dp).onFocusChanged { f = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(3.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (visto) Color(0x55434A57) else Color(0x99596273),
            focusedContainerColor = Color(0xFFE53935),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.06f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** "Você também pode gostar": títulos da mesma categoria. */
@Composable
private fun Recomendados(vm: TvViewModel, kind: String, atual: VodEntity, onOpen: (VodEntity) -> Unit) {
    val parecidos by remember(atual.id) {
        vm.vodTop(kind, atual.category, 16).let { f ->
            kotlinx.coroutines.flow.flow { f.collect { l -> emit(l.filter { it.id != atual.id }.take(15)) } }
        }
    }.collectAsState(initial = emptyList())
    if (parecidos.isEmpty()) return
    Spacer(Modifier.height(22.dp))
    Text("Você também pode gostar", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(parecidos, key = { it.id }) { p -> PosterTile(p, 120.dp, 180.dp) { onOpen(p) } }
    }
}


/** Nome curto da categoria: tira "Filmes |", "Séries /" etc. do começo. */
fun nomeCategoria(n: String): String =
    n.replace(Regex("""^\s*(filmes|s[ée]ries|movies|series)\s*[|/:\-]+\s*""", RegexOption.IGNORE_CASE), "").trim().ifEmpty { n }


/** Miniatura com o botão play vermelho (canto superior direito da ficha, como na UniTV). */
@Composable
private fun MiniPlay(b: VodBrowser, imagem: String?, titulo: String, modifier: Modifier, onPlay: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val tocando = b.previewId != null
    val clique = { if (tocando && b.acaoTelaCheia != null) b.acaoTelaCheia?.invoke() ?: Unit else onPlay() }
    DisposableEffect(Unit) { onDispose { b.previewRect = null } }
    if (tocando) {
        // Janelinha transparente: o vídeo aparece por trás. Toque/OK = tela cheia.
        Surface(
            onClick = clique,
            modifier = modifier.aspectRatio(16f / 9f).onFocusChanged { f = it.isFocused }
                .onGloballyPositioned { b.previewRect = it.boundsInRoot() }
                .border(if (f) 3.dp else 2.dp, if (f) Color(0xFFFFE082) else Color(0x99FFE0B2), RoundedCornerShape(4.dp))
                .tap(onClick = clique),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text("⛶", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0x88000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp))
            }
        }
        return
    }
    Surface(
        onClick = onPlay,
        modifier = modifier.aspectRatio(16f / 9f).onFocusChanged { f = it.isFocused }
            .border(if (f) 3.dp else 2.dp, if (f) Color(0xFFFFE082) else Color(0x99FFE0B2), RoundedCornerShape(4.dp))
            .tap(onClick = onPlay),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.02f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(imagem, titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFFF5252), Color(0xFFC62828)))),
                contentAlignment = Alignment.Center,
            ) { AppIcon(AppIcons.Play, tint = Color.White, size = 30.dp) }
        }
    }
}


/** Botões da ficha como na UniTV: Idioma, Resolução, Favorito e (séries) Notificações. */
@Composable
private fun BotoesExtras(vm: TvViewModel, item: VodEntity, serie: Boolean, episodios: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var idioma by remember { mutableStateOf(io.github.axon07.channelbox.data.ZarkPrefs.idioma(ctx)) }
    var resol by remember { mutableStateOf(io.github.axon07.channelbox.data.ZarkPrefs.resolucao(ctx)) }
    var fav by remember(item.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkPrefs.ehFavorito(ctx, item.id)) }
    var segue by remember(item.id) { mutableStateOf(io.github.axon07.channelbox.data.ZarkPrefs.segue(ctx, item.id)) }
    // Rótulos curtos (como na UniTV); o valor escolhido aparece num aviso rápido.
    BotaoFicha("Idioma", false) {
        idioma = io.github.axon07.channelbox.data.ZarkPrefs.proximoIdioma(ctx)
        vm.showToast("Áudio: " + io.github.axon07.channelbox.data.ZarkPrefs.nomeIdioma(idioma))
    }
    BotaoFicha("Resolução", false) {
        resol = io.github.axon07.channelbox.data.ZarkPrefs.proximaResolucao(ctx)
        vm.showToast("Resolução: " + io.github.axon07.channelbox.data.ZarkPrefs.nomeResolucao(resol))
    }
    BotaoFicha(if (fav) "★ Favorito" else "☆ Favorito", fav) { fav = vm.zarkFavoritar(item) }
    if (serie) BotaoFicha(if (segue) "Notificações ✓" else "Notificações", segue) {
        segue = io.github.axon07.channelbox.data.ZarkPrefs.alternarSeguir(ctx, item.id, episodios)
        vm.showToast(if (segue) "Vamos avisar no sininho quando sair episódio novo" else "Notificações desligadas")
    }
}
