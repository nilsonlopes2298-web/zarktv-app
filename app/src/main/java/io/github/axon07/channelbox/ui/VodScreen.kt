package io.github.axon07.channelbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
    val categories by remember(b.kind) { vm.vodCategories(b.kind) }.collectAsState(initial = emptyList())
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

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        // ---- Left: title, search, categories ----
        Column(
            Modifier
                .width(290.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Color(0xFF111821), Color(0xFF0B1016))))
                .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
        ) {
            Text(
                if (movies) "Movies" else "TV Shows", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                "$total ${if (movies) "movies" else "shows"}", fontSize = 12.sp, color = Palette.TextDim,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 12.dp),
            )
            TvTextField("Search", b.query, { b.query = it })
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                val entries = buildList {
                    if (continueCount > 0) add(Triple(VOD_CONTINUE, "Continue watching", continueCount))
                    add(Triple(null, if (movies) "All movies" else "All shows", total))
                    categories.forEach { add(Triple(it.name, it.name, it.count)) }
                }
                items(entries, key = { it.first ?: "\u0000all" }) { (id, label, count) ->
                    val selected = b.query.isBlank() && b.category == id
                    val real = id != null && id != VOD_CONTINUE
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
            val heading = when {
                b.query.isNotBlank() -> "Results for “${b.query.trim()}”"
                b.category == VOD_CONTINUE -> "Continue watching"
                b.category == null -> if (movies) "All movies" else "All shows"
                else -> b.category.orEmpty()
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(heading, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (items != null) {
                    val more = items.size >= (if (b.query.isNotBlank()) VOD_SEARCH_LIMIT else VOD_PAGE)
                    Text(
                        if (more) "  newest ${items.size} – search to find more" else "  ${items.size}",
                        fontSize = 13.sp, color = Palette.TextDim,
                    )
                }
            }
            // ZarkTV: atalhos das plataformas (Netflix, Prime Video…) e Lançamentos.
            val plataformas = remember(categories) {
                listOf("Lançamentos", "Netflix", "Prime Video", "Disney", "HBO", "Max", "Globoplay", "Apple", "Paramount", "Star", "Legendados", "4K")
                    .mapNotNull { p ->
                        val rx = Regex(if (p == "Lançamentos") "lan[cç]amento" else Regex.escape(p), RegexOption.IGNORE_CASE)
                        categories.firstOrNull { rx.containsMatchIn(it.name) }?.let { p to it.name }
                    }.distinctBy { it.second }
            }
            if (plataformas.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plataformas) { (nome, cat) ->
                        PillButton(nome, primary = b.category == cat) {
                            b.query = ""
                            b.category = cat
                        }
                    }
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
                items == null -> Text("Loading…", color = Palette.TextDim, fontSize = 14.sp)
                b.query.isNotBlank() && b.query.trim().length < 2 -> Text("Type at least 2 letters", color = Palette.TextDim, fontSize = 14.sp)
                items.isEmpty() -> Text(
                    if (b.query.isNotBlank()) "Nothing matches your search" else "Nothing here yet",
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
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
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
            // Open on the season you were last watching, else the first one.
            val last = list.filter { it.id in progress }.maxByOrNull { progress.getValue(it.id).updated }
            b.season = last?.season ?: list.firstOrNull()?.season
            b.focusId = last?.id
            b.focusToken++
        }
    }
    val episodes = b.episodes
    val seasons = episodes?.map { it.season }?.distinct().orEmpty()
    val inSeason = episodes?.filter { it.season == b.season }.orEmpty()
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val firstEpisode = remember { FocusRequester() }
    LaunchedEffect(b.focusToken, episodes != null) {
        if (!touch && episodes != null && b.focusId == null) {
            delay(60)
            runCatching { firstEpisode.requestFocus() }
        }
    }

    var ficha by remember(show.id) { mutableStateOf<io.github.axon07.channelbox.data.ZarkInfo?>(null) }
    LaunchedEffect(show.id) { ficha = vm.zarkFicha(show) }
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
    ficha?.fundo?.let { FundoFicha(it) }
    Row(Modifier.fillMaxSize()) {
        // ---- Left: the show and its seasons ----
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Color(0xE6140405), Color(0xE60A0A0A))))
                .padding(top = 24.dp, start = 18.dp, end = 18.dp, bottom = 12.dp)
        ) {
            Row {
                PosterImage(show.poster, show.title, Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(show.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(show.year, show.rating?.let { "★ $it" }, show.category.takeIf { it.isNotBlank() }).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = Palette.TextDim, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
            }
            ficha?.genero?.let { Text(it, fontSize = 12.sp, color = Color(0xFF7CD992), maxLines = 1, modifier = Modifier.padding(top = 10.dp)) }
            ficha?.elenco?.let { Text(it, fontSize = 12.sp, color = Color(0xFF7FB8FF), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            (ficha?.sinopse ?: show.plot)?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFFDDDDDD), maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(seasons, key = { it }) { s ->
                    CategoryEntry(
                        label = if (s == 0) "Episódios" else "Temporada $s",
                        count = episodes?.count { it.season == s } ?: 0,
                        selected = s == b.season,
                        modifier = Modifier,
                        onFocus = { b.season = s },
                        onClick = { b.season = s },
                    )
                }
            }
        }

        // ---- Right: episodes ----
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp, start = 24.dp, end = 24.dp)) {
            Text(
                when {
                    episodes == null -> "Carregando episódios…"
                    episodes.isEmpty() -> "Nenhum episódio encontrado"
                    b.season == 0 -> "Episódios"
                    else -> "Temporada ${b.season}"
                },
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(state = b.episodeList, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(inSeason, key = { it.id }) { ep ->
                    val requester = remember { FocusRequester() }
                    if (ep.id == b.focusId) {
                        LaunchedEffect(b.focusToken) {
                            delay(60)
                            runCatching { requester.requestFocus() }
                        }
                    }
                    EpisodeRow(
                        ep = ep,
                        progress = progress[ep.id],
                        modifier = (if (ep == inSeason.first()) Modifier.focusRequester(firstEpisode) else Modifier)
                            .focusRequester(requester),
                        onClick = {
                            b.focusId = ep.id
                            onPlay(ep, show)
                        },
                    )
                }
            }
        }
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
    var carregou by remember(movie.id) { mutableStateOf(false) }
    LaunchedEffect(movie.id) {
        ficha = vm.zarkFicha(movie)
        carregou = true
    }
    val play = remember { FocusRequester() }
    LaunchedEffect(movie.id) {
        delay(120)
        runCatching { play.requestFocus() }
    }
    val retrato = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        (ficha?.fundo ?: movie.poster)?.let { FundoFicha(it) }
        val conteudo: @Composable () -> Unit = {
            Text(movie.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val nota = (ficha?.nota ?: movie.rating)?.toDoubleOrNull()
            if (nota != null && nota > 0) {
                val estrelas = (nota / 2).toInt().coerceIn(0, 5)
                Text("★".repeat(estrelas) + "☆".repeat(5 - estrelas) + "  (${"%.1f".format(nota)})", color = Color(0xFFFFC107), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
            val meta = listOfNotNull(ficha?.data ?: movie.year, ficha?.duracao).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, color = Color(0xFF7FB8FF), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            ficha?.genero?.let { Text(it, color = Color(0xFF7CD992), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            ficha?.diretor?.let { Text("Direção: $it", color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            ficha?.elenco?.let { Text("Elenco: $it", color = Color(0xFF7FB8FF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            val sinopse = ficha?.sinopse ?: movie.plot
            Text(
                sinopse ?: if (carregou) "Sinopse não disponível." else "Carregando informações…",
                color = Color(0xFFEEEEEE), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 7, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                Box(Modifier.width(240.dp).focusRequester(play)) {
                    BotaoCheio(if (progress != null && progress.position > 0) "▶  Continuar" else "▶  Assistir", Palette.Accent) {
                        b.focusId = movie.id
                        onPlay(movie, null)
                    }
                }
                val ctxExt = androidx.compose.ui.platform.LocalContext.current
                Box(Modifier.width(200.dp)) {
                    BotaoContorno("Player externo", cor = Color(0x55FFFFFF)) {
                        movie.url?.let { io.github.axon07.channelbox.data.ZarkConfig.abrirExterno(ctxExt, it, movie.title) }
                    }
                }
                Box(Modifier.width(160.dp)) {
                    BotaoContorno("‹ Voltar", cor = Color(0x55FFFFFF)) {
                        b.movie = null
                        b.focusToken++
                        b.voltarInicio?.invoke()
                    }
                }
            }
            // Você também pode gostar (mesma categoria).
            val parecidos by remember(movie.id) {
                vm.vodTop(VodKind.MOVIE, movie.category, 16).let { f ->
                    kotlinx.coroutines.flow.flow { f.collect { l -> emit(l.filter { it.id != movie.id }.take(15)) } }
                }
            }.collectAsState(initial = emptyList())
            if (parecidos.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Você também pode gostar", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(parecidos, key = { it.id }) { p -> PosterTile(p, 110.dp, 165.dp) { b.movie = p } }
                }
            }
        }
        if (retrato) {
            Column(
                Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
            ) {
                PosterImage(ficha?.capa ?: movie.poster, movie.title, Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.height(16.dp))
                conteudo()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(36.dp), verticalAlignment = Alignment.Top) {
                PosterImage(ficha?.capa ?: movie.poster, movie.title, Modifier.width(230.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(30.dp))
                Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState())) { conteudo() }
            }
        }
    }
}
