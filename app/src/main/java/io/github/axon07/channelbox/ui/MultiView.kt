package io.github.axon07.channelbox.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.player.TvPlayer
import kotlinx.coroutines.delay

/**
 * Watch up to 4 channels at once. Only the channels you add take up room: 2 side by side, 3 as one
 * big and two small, 4 as a grid. Sound comes from the highlighted tile.
 * How many play smoothly depends on the box's video decoders (most handle 2, many handle 4 at HD).
 */
class MultiViewState {
    val channels = mutableStateListOf<ChannelEntity>()
    private val players = ArrayList<TvPlayer>()
    var focused by mutableIntStateOf(0)
    var version by mutableIntStateOf(0) // bumped when the tiles change
    /** Tile the channel picker fills; == [count] adds a new tile. */
    var pickTarget by mutableIntStateOf(0)
    /** Bumped on any key or tap, to show the instructions again for a few seconds. */
    var hintToken by mutableIntStateOf(0)

    val count: Int get() = channels.size
    val canAdd: Boolean get() = count < MAX

    fun player(i: Int): TvPlayer? = players.getOrNull(i)

    fun start(vm: TvViewModel, first: ChannelEntity?) {
        vm.player.stop() // frees a decoder for the tiles
        release()
        focused = 0
        if (first != null) assign(vm, 0, first)
    }

    /** Puts [channel] in [tile]; a tile one past the end is added. */
    fun assign(vm: TvViewModel, tile: Int, channel: ChannelEntity) {
        if (tile >= count) {
            if (!canAdd) return
            channels += channel
            players += vm.newTilePlayer()
            focused = count - 1
            version++
        } else {
            channels[tile] = channel
        }
        players[tile.coerceAtMost(count - 1)].play(StreamSpecs.from(channel))
        updateVolumes()
    }

    fun remove(tile: Int) {
        if (tile !in 0 until count) return
        players.removeAt(tile).release()
        channels.removeAt(tile)
        focused = focused.coerceAtMost((count - 1).coerceAtLeast(0))
        version++
        updateVolumes()
    }

    /** Moves the highlight to the nearest tile in that direction. */
    fun move(dx: Int, dy: Int) {
        val rects = layout(count)
        val from = rects.getOrNull(focused) ?: return
        val (fx, fy) = from.cx to from.cy
        val best = rects.indices.filter { it != focused }.filter { i ->
            val r = rects[i]
            (dx > 0 && r.cx > fx + 0.01f) || (dx < 0 && r.cx < fx - 0.01f) ||
                (dy > 0 && r.cy > fy + 0.01f) || (dy < 0 && r.cy < fy - 0.01f)
        }.minByOrNull { i ->
            val r = rects[i]
            val along = if (dx != 0) kotlin.math.abs(r.cx - fx) else kotlin.math.abs(r.cy - fy)
            val across = if (dx != 0) kotlin.math.abs(r.cy - fy) else kotlin.math.abs(r.cx - fx)
            along + across * 2
        } ?: return
        focused = best
        updateVolumes()
    }

    fun select(tile: Int) {
        focused = tile
        updateVolumes()
    }

    private fun updateVolumes() {
        players.forEachIndexed { i, p -> p.exo.volume = if (i == focused) 1f else 0f }
    }

    fun release() {
        players.forEach { it.release() }
        players.clear()
        channels.clear()
        version++
    }

    /** A tile's place on screen, in fractions of the screen. */
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val cx get() = x + w / 2
        val cy get() = y + h / 2
    }

    companion object {
        const val MAX = 4

        fun layout(count: Int): List<Rect> = when (count) {
            1 -> listOf(Rect(0f, 0f, 1f, 1f))
            2 -> listOf(Rect(0f, 0f, .5f, 1f), Rect(.5f, 0f, .5f, 1f))
            3 -> listOf(Rect(0f, 0f, .5f, 1f), Rect(.5f, 0f, .5f, .5f), Rect(.5f, .5f, .5f, .5f))
            4 -> listOf(Rect(0f, 0f, .5f, .5f), Rect(.5f, 0f, .5f, .5f), Rect(0f, .5f, .5f, .5f), Rect(.5f, .5f, .5f, .5f))
            else -> emptyList()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun MultiViewScreen(
    state: MultiViewState,
    onTileTap: (Int) -> Unit,
    onTileLongPress: (Int) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val v = state.version // recompose when the tiles change
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    // Instructions fade out after a few seconds (no burn-in on OLED screens); any key brings them back.
    var hints by remember { mutableStateOf(true) }
    LaunchedEffect(state.hintToken) {
        hints = true
        delay(5_000)
        hints = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        @Composable
        fun tile(i: Int, modifier: Modifier) {
            val focused = state.focused == i
            Box(
                modifier
                    .padding(3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.Surface)
                    .border(if (focused && state.count > 1) 2.dp else 0.dp, if (focused && state.count > 1) Palette.Accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .tap(onLongClick = { onTileLongPress(i) }) { onTileTap(i) },
            ) {
                val tilePlayer = state.player(i)
                if (tilePlayer != null) {
                    key(i, state.version) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    isFocusable = false
                                    isClickable = false
                                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    player = tilePlayer.exo
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val ch = state.channels.getOrNull(i)
                AnimatedVisibility(hints || focused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
                    Row(
                        Modifier
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (focused && state.count > 1) {
                            AppIcon(AppIcons.Audio, tint = Palette.Text, size = 13.dp)
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 5.dp))
                        }
                        Text(
                            ch?.let { "${it.number}  ${it.name}" } ?: "",
                            fontSize = 12.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        when (state.count) {
            0 -> Text(
                "Press Menu (or long-press) to add a channel",
                fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.align(Alignment.Center),
            )
            1 -> tile(0, Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize()) {
                tile(0, Modifier.weight(1f).fillMaxSize())
                tile(1, Modifier.weight(1f).fillMaxSize())
            }
            3 -> Row(Modifier.fillMaxSize()) {
                tile(0, Modifier.weight(1f).fillMaxSize())
                Column(Modifier.weight(1f).fillMaxSize()) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    tile(0, Modifier.weight(1f).fillMaxSize())
                    tile(1, Modifier.weight(1f).fillMaxSize())
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    tile(2, Modifier.weight(1f).fillMaxSize())
                    tile(3, Modifier.weight(1f).fillMaxSize())
                }
            }
        }

        AnimatedVisibility(hints, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(
                if (touch) "Tap: sound from that channel · Long-press: full screen, change, add or remove"
                else "OK: change channel · Hold OK: full screen & more · Menu: add a channel · Back: exit",
                fontSize = 12.sp, color = Color.White,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}
