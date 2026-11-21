package io.github.axon07.channelbox.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Organise channels and groups many at a time:
 * - Groups: select several (OK) and hide / show them; hold OK on one to move it (▲ ▼, OK to drop).
 * - ▶ (or ›) on a group opens its channels: select several to add to Favourites, or hide / show.
 * - Favourites: hold OK on a channel to move it.
 */
@Composable
fun OrganiseScreen(vm: TvViewModel, start: String? = null, onExit: () -> Unit) {
    // null = the groups list; FAVOURITES or a group's playlist name = that group's channels
    var open by remember { mutableStateOf(start) }
    var focusToken by remember { mutableStateOf(0) }
    BackHandler {
        if (open != null) {
            open = null
            focusToken++
        } else {
            onExit()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20B1016))
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        if (open == null) {
            GroupsOrganiser(vm, focusToken) { group -> open = group }
        } else {
            ChannelsOrganiser(vm, open!!) {
                open = null
                focusToken++
            }
        }
    }
}

private const val FAVOURITES = "\u0000favourites"

@Composable
private fun GroupsOrganiser(vm: TvViewModel, focusToken: Int, onOpen: (String) -> Unit) {
    val groups by vm.organiseGroups.collectAsState()
    val favCount = vm.favoriteOrder.collectAsState().value.size
    val selected = remember { mutableStateListOf<String>() }
    val order = remember { mutableStateListOf<String>() }
    var moving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(groups, moving) { if (moving == null) { order.clear(); order.addAll(groups.map { it.original }) } }
    val byName = groups.associateBy { it.original }

    Column(Modifier.fillMaxSize()) {
        Header(
            "Organise groups",
            if (moving != null) "▲ ▼ move · OK to put it here"
            else "OK select · ▶ open a group's channels · hold OK to move a group",
        )
        ActionBar(
            listOf(
                "Hide${count(selected.size)}" to { vm.setGroupsHidden(selected.toList(), true); selected.clear() },
                "Show${count(selected.size)}" to { vm.setGroupsHidden(selected.toList(), false); selected.clear() },
                "Select all" to { selected.clear(); selected.addAll(order) },
                "Clear" to { selected.clear() },
            ),
            enabled = listOf(selected.isNotEmpty(), selected.isNotEmpty(), true, selected.isNotEmpty()),
        )
        val state = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val first = remember { FocusRequester() }
        LaunchedEffect(focusToken) {
            delay(60)
            runCatching { first.requestFocus() }
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
            item(key = FAVOURITES) {
                OrgRow(
                    title = "★  Favourites",
                    detail = "$favCount channels · ▶ to sort",
                    checked = null,
                    dim = false,
                    moving = false,
                    modifier = Modifier.focusRequester(first),
                    onClick = { onOpen(FAVOURITES) },
                    onOpen = { onOpen(FAVOURITES) },
                )
            }
            itemsIndexed(order, key = { _, g -> g }) { index, g ->
                val info = byName[g] ?: return@itemsIndexed
                OrgRow(
                    title = info.shown,
                    detail = "${info.count} channels" + if (info.hidden) " · hidden" else "",
                    checked = g in selected,
                    dim = info.hidden,
                    moving = moving == g,
                    onClick = {
                        if (moving == g) {
                            vm.setGroupOrder(order.toList())
                            moving = null
                        } else if (g in selected) selected.remove(g) else selected.add(g)
                    },
                    onLongClick = { moving = g },
                    onMove = { delta ->
                        val j = (index + delta).coerceIn(0, order.lastIndex)
                        if (j != index) {
                            order.add(j, order.removeAt(index))
                            scope.launch { state.animateScrollToItem((j + 1 - 3).coerceAtLeast(0)) }
                        }
                    },
                    onOpen = { if (moving == null) onOpen(g) },
                )
            }
        }
    }
}

@Composable
private fun ChannelsOrganiser(vm: TvViewModel, group: String, onBack: () -> Unit) {
    val favourites = group == FAVOURITES
    val channels by remember(group) { vm.organiseChannels(if (favourites) null else group) }.collectAsState(initial = emptyList())
    val selected = remember { mutableStateListOf<String>() }
    val order = remember { mutableStateListOf<String>() }
    var moving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(channels, moving) { if (moving == null) { order.clear(); order.addAll(channels.map { it.channel.id }) } }
    val byId = channels.associateBy { it.channel.id }
    val title = if (favourites) "Favourites" else vm.organiseGroups.collectAsState().value.firstOrNull { it.original == group }?.shown ?: group

    Column(Modifier.fillMaxSize()) {
        Header(
            title,
            when {
                moving != null -> "▲ ▼ move · OK to put it here"
                favourites -> "OK select · hold OK to move a channel · Back to groups"
                else -> "OK select channels, then add them to Favourites or hide them · Back to groups"
            },
        )
        val sel = selected.toList()
        val actions = buildList<Pair<String, () -> Unit>> {
            if (!favourites) add("★ Add to Favourites${count(sel.size)}" to { vm.addFavorites(sel); selected.clear() })
            add("Remove from Favourites${count(sel.size)}" to { vm.removeFavorites(sel); selected.clear() })
            add("Hide${count(sel.size)}" to { vm.setChannelsHidden(sel, true); selected.clear() })
            add("Show${count(sel.size)}" to { vm.setChannelsHidden(sel, false); selected.clear() })
            add("Select all" to { selected.clear(); selected.addAll(order) })
            add("Clear" to { selected.clear() })
        }
        ActionBar(actions, enabled = actions.map { (label, _) -> label == "Select all" || sel.isNotEmpty() })
        val state = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val first = remember { FocusRequester() }
        LaunchedEffect(group, channels.isNotEmpty()) {
            delay(60)
            runCatching { first.requestFocus() }
        }
        if (channels.isEmpty()) {
            Text(
                if (favourites) "No favourites yet – open a group and add some." else "No channels in this group.",
                fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 20.dp),
            )
            return@Column
        }
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(order, key = { _, id -> id }) { index, id ->
                val info = byId[id] ?: return@itemsIndexed
                val ch = info.channel
                OrgRow(
                    title = (if (ch.number > 0) "${ch.number}  " else "") + ch.name + if (info.favorite && !favourites) "  ★" else "",
                    detail = if (info.hidden) "hidden" else null,
                    checked = id in selected,
                    dim = info.hidden,
                    moving = moving == id,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = {
                        if (moving == id) {
                            vm.setFavoriteOrder(order.toList())
                            moving = null
                        } else if (id in selected) selected.remove(id) else selected.add(id)
                    },
                    onLongClick = if (favourites) ({ moving = id }) else null,
                    onMove = { delta ->
                        val j = (index + delta).coerceIn(0, order.lastIndex)
                        if (j != index) {
                            order.add(j, order.removeAt(index))
                            scope.launch { state.animateScrollToItem((j - 3).coerceAtLeast(0)) }
                        }
                    },
                )
            }
        }
    }
}

private fun count(n: Int) = if (n > 0) " ($n)" else ""

@Composable
private fun Header(title: String, hint: String) {
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    Text(hint, fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
}

@Composable
private fun ActionBar(actions: List<Pair<String, () -> Unit>>, enabled: List<Boolean>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
        actions.forEachIndexed { i, (label, action) ->
            val on = enabled.getOrElse(i) { true }
            var focused by remember { mutableStateOf(false) }
            Surface(
                onClick = { if (on) action() },
                modifier = Modifier.onFocusChanged { focused = it.isFocused }.tap { if (on) action() },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x1AFFFFFF),
                    contentColor = Palette.Text,
                    focusedContainerColor = Color(0xFFECF1F6),
                    focusedContentColor = Color(0xFF0E141B),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.04f)),
            ) {
                Text(
                    label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = when {
                        focused -> Color(0xFF0E141B)
                        on -> Palette.Text
                        else -> Color(0xFF5E6A78)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * One row. [checked] null = no checkbox. While [moving], ▲ ▼ call [onMove] instead of moving focus.
 * ▶ calls [onOpen] when there is one.
 */
@Composable
private fun OrgRow(
    title: String,
    detail: String?,
    checked: Boolean?,
    dim: Boolean,
    moving: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMove: (Int) -> Unit = {},
    onOpen: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (moving) { onMove(-1); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (moving) { onMove(1); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (!moving && onOpen != null) { onOpen(); true } else false
                    else -> false
                }
            }
            .tap(onLongClick = onLongClick, onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (moving) Color(0x333D8BFD) else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = if (moving) Palette.Accent else Color(0xFFECF1F6),
            focusedContentColor = Color(0xFF0E141B),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.01f)),
    ) {
        val ink = when {
            focused && moving -> Color.White
            focused -> Color(0xFF0E141B)
            dim -> Color(0xFF6B7888)
            else -> Palette.Text
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked != null) {
                CheckBox(checked, focused && !moving)
                Spacer(Modifier.width(14.dp))
            }
            if (moving) {
                Text("⇅", fontSize = 18.sp, color = ink)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                title, fontSize = 15.sp, color = ink, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (detail != null) {
                Text(detail, fontSize = 12.sp, color = if (focused) ink.copy(alpha = 0.7f) else Palette.TextDim)
            }
            if (onOpen != null) {
                Spacer(Modifier.width(12.dp))
                Text("›", fontSize = 20.sp, color = ink, modifier = Modifier.tap(onClick = onOpen).padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean, onLight: Boolean) {
    val edge = if (onLight) Color(0xFF0E141B) else Color(0xFF7D8A9A)
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) Palette.Accent else Color.Transparent)
            .border(2.dp, if (checked) Palette.Accent else edge, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
