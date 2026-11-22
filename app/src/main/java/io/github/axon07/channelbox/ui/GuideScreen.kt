package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.TvRepository.Companion.HOUR

// Layout of the guide on a 960x540 dp TV screen. The live preview sits in the top-right corner.
/** Guide sizes: compact on phones (landscape height under 480 dp), roomier on TVs. */
data class GuideMetrics(
    val previewWidth: Dp,
    val previewHeight: Dp,
    val previewPadding: Dp,
    val channelColumn: Dp,
    val rowHeight: Dp,
    val compact: Boolean,
) {
    val topHeight: Dp get() = previewHeight + previewPadding * 2
}

@Composable
fun guideMetrics(): GuideMetrics =
    if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480) {
        GuideMetrics(196.dp, 99.dp, 8.dp, 150.dp, 36.dp, compact = true)
    } else {
        GuideMetrics(264.dp, 135.dp, 14.dp, 190.dp, 40.dp, compact = false)
    }

/**
 * Selection state for the grid. The guide doesn't use Compose focus traversal: a
 * remote press moves a (row, time) cursor, which is predictable on slow boxes and
 * keeps the time axis shared between rows, like a cable EPG.
 */
class GuideState {
    var row by mutableIntStateOf(0)

    /** When ▲ / ▼ was last pressed, to tell a new press from a held key (some remotes misreport). */
    private var lastVerticalAt = 0L
    var focusTime by mutableLongStateOf(System.currentTimeMillis())
    val listState = LazyListState()

    fun open(channels: List<ChannelEntity>, playingId: String?, vm: TvViewModel) {
        val now = System.currentTimeMillis()
        focusTime = now
        vm.setGuideStart(now)
        row = channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    }

    fun programAt(channel: ChannelEntity?, programs: Map<String, List<ProgramEntity>>, time: Long = focusTime) =
        channel?.epgId?.let { programs[it] }?.firstOrNull { it.start <= time && time < it.stop }

    /**
     * Moves up / down. At the top, ▲ jumps to the last channel; at the bottom, ▼ jumps to the first
     * ([wrap] is off while a key is held, so holding ▼ stops at the end instead of flying round).
     */
    fun moveVertical(delta: Int, channelCount: Int, wrap: Boolean = false) {
        if (channelCount == 0) return
        val now = android.os.SystemClock.uptimeMillis()
        // A held key repeats every ~50 ms (the first repeat after ~0.5 s); a longer gap means the
        // key was let go and pressed again, even on remotes that misreport the repeat count.
        @Suppress("NAME_SHADOWING")
        val wrap = wrap || now - lastVerticalAt > 700
        lastVerticalAt = now
        val last = channelCount - 1
        row = when {
            wrap && delta < 0 && row == 0 -> last
            wrap && delta > 0 && row == last -> 0
            else -> (row + delta).coerceIn(0, last)
        }
    }

    fun moveHorizontal(delta: Int, channel: ChannelEntity?, programs: Map<String, List<ProgramEntity>>, vm: TvViewModel) {
        val p = programAt(channel, programs)
        val target = if (delta > 0) {
            p?.stop ?: (TvViewModel.roundDown(focusTime) + GUIDE_STEP)
        } else {
            (p?.start ?: TvViewModel.roundDown(focusTime)) - 1
        }
        val earliest = System.currentTimeMillis() - 6 * HOUR
        if (target < earliest) return
        focusTime = target
        val start = vm.guideStart.value
        if (target >= start + GUIDE_SPAN) vm.setGuideStart(target - GUIDE_SPAN + GUIDE_STEP)
        else if (target < start) vm.setGuideStart(target)
    }
}

@Composable
fun GuideScreen(
    state: GuideState,
    channels: List<ChannelEntity>,
    programs: Map<String, List<ProgramEntity>>,
    filterLabel: String,
    guideStart: Long,
    now: Long,
    playingId: String?,
    favorites: Set<String>,
    recordingKeys: Set<String>,
    canCatchUp: (ChannelEntity, ProgramEntity) -> Boolean,
    timeFormat: (Long) -> String,
    onTune: (ChannelEntity) -> Unit,
    onProgram: (ChannelEntity, ProgramEntity) -> Unit,
    onMenu: (ChannelEntity, ProgramEntity?) -> Unit,
    onGroups: () -> Unit,
    onShift: (Long) -> Unit,
    onNow: () -> Unit,
    onClose: () -> Unit,
) {
    val m = guideMetrics()
    LaunchedEffect(state.row, channels.size) {
        if (channels.isNotEmpty()) state.listState.scrollToItem((state.row - 2).coerceAtLeast(0))
    }

    val selectedChannel = channels.getOrNull(state.row)
    val selectedProgram = state.programAt(selectedChannel, programs)

    Column(Modifier.fillMaxSize()) {
        // Top: programme details, with a transparent hole on the right for the live preview.
        Row(Modifier.fillMaxWidth().height(m.topHeight)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Palette.Background)
                    .padding(start = 28.dp, top = 20.dp, end = 16.dp)
            ) {
                when {
                    selectedProgram != null -> {
                        val recorded = selectedChannel != null &&
                            TvViewModel.recordingKey(selectedChannel.id, selectedProgram.start) in recordingKeys
                        Text(
                            (if (recorded) "● " else "") + selectedProgram.title,
                            fontSize = if (m.compact) 16.sp else 20.sp, fontWeight = FontWeight.Bold,
                            color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${timeFormat(selectedProgram.start)} – ${timeFormat(selectedProgram.stop)}  ·  ${selectedChannel?.name.orEmpty()}",
                            fontSize = 14.sp, color = Palette.Accent,
                        )
                        if (selectedChannel != null && selectedProgram.stop <= now && canCatchUp(selectedChannel, selectedProgram)) {
                            Text("↺ Catch-up available – press OK to watch", fontSize = 13.sp, color = Palette.Text)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            selectedProgram.description.orEmpty(), fontSize = 14.sp, color = Palette.TextDim,
                            maxLines = 5, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    selectedChannel != null -> {
                        Text(selectedChannel.name, fontSize = if (m.compact) 16.sp else 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                        Spacer(Modifier.height(4.dp))
                        Text("Sem programação", fontSize = 14.sp, color = Palette.TextDim)
                    }
                    else -> Text("No channels in this group", fontSize = 18.sp, color = Palette.TextDim)
                }
            }
            // Transparent: the live video shows through here. Tapping it goes back to full screen.
            Box(Modifier.width(m.previewWidth + m.previewPadding * 2).fillMaxHeight().tap(onClick = onClose))
        }

        // Time ruler with the clock on the left (tap it to jump back to now).
        Row(Modifier.fillMaxWidth().height(26.dp).background(Palette.Background)) {
            Row(
                Modifier.width(m.channelColumn).fillMaxHeight().tap(onClick = onNow).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(timeFormat(now), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val total = maxWidth
                // "Now" marker: a small pill on the time ruler.
                if (now in guideStart until guideStart + GUIDE_SPAN) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = total * ((now - guideStart).toFloat() / GUIDE_SPAN) - 1.dp)
                            .width(3.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Palette.Accent)
                    )
                }
                for (i in 0 until (GUIDE_SPAN / GUIDE_STEP).toInt()) {
                    Text(
                        timeFormat(guideStart + i * GUIDE_STEP),
                        fontSize = 12.sp, color = Palette.TextDim,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = total * (i.toFloat() * GUIDE_STEP / GUIDE_SPAN) + 6.dp),
                    )
                }
            }
        }

        // Touch: swipe sideways to move through the times.
        val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
        LazyColumn(
            state = state.listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Background)
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            if (dx > swipe) onShift(-GUIDE_STEP) else if (dx < -swipe) onShift(GUIDE_STEP)
                        },
                    ) { change, amount ->
                        dx += amount
                        change.consume()
                    }
                },
            // Room under the last channel: many TVs crop the screen edges (overscan), which hid
            // the last row – ▼ seemed to do nothing there and the jump back to the top was missed.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = m.rowHeight + 24.dp),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                GuideRow(
                    channel = ch,
                    programs = ch.epgId?.let { programs[it] }.orEmpty(),
                    rowSelected = index == state.row,
                    focusTime = state.focusTime,
                    guideStart = guideStart,
                    now = now,
                    playing = ch.id == playingId,
                    favorite = ch.id in favorites,
                    recordingKeys = recordingKeys,
                    canCatchUp = { p -> canCatchUp(ch, p) },
                    onTune = { onTune(ch) },
                    onProgram = { p -> onProgram(ch, p) },
                    onMenu = { p -> onMenu(ch, p) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programs: List<ProgramEntity>,
    rowSelected: Boolean,
    focusTime: Long,
    guideStart: Long,
    now: Long,
    playing: Boolean,
    favorite: Boolean,
    recordingKeys: Set<String>,
    canCatchUp: (ProgramEntity) -> Boolean,
    onTune: () -> Unit,
    onProgram: (ProgramEntity) -> Unit,
    onMenu: (ProgramEntity?) -> Unit,
) {
    val m = guideMetrics()
    val end = guideStart + GUIDE_SPAN
    Row(Modifier.fillMaxWidth().height(m.rowHeight).padding(vertical = 2.dp)) {
        Row(
            Modifier
                .width(m.channelColumn)
                .fillMaxHeight()
                .padding(start = 6.dp, end = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (rowSelected) Palette.SurfaceLive else Palette.Surface)
                .tap(onLongClick = { onMenu(null) }, onClick = onTune)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (LocalShowChannelNumbers.current) {
                Text(channel.number.toString(), fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.width(34.dp))
            }
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 44.dp, height = 26.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (playing) "▶ " else "") + channel.name + (if (favorite) " ★" else ""),
                // Long names wrap onto a second line instead of being cut off.
                fontSize = 13.sp, lineHeight = 15.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (io.github.axon07.channelbox.data.Catchup.isAvailable(channel.catchupType, channel.catchupSource, channel.catchupDays)) {
                Spacer(Modifier.width(5.dp))
                AppIcon(AppIcons.History, tint = Palette.TextDim, size = 13.dp)
            }
            if (channel.id in LocalRecordingChannels.current) {
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Recording))
            }
            if (channel.id in LocalLockedChannels.current) {
                Spacer(Modifier.width(4.dp))
                AppIcon(AppIcons.Lock, tint = Palette.TextDim, size = 12.dp)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val total = maxWidth
            fun x(t: Long): Dp = total * ((t - guideStart).toFloat() / GUIDE_SPAN)

            val visible = programs.filter { it.stop > guideStart && it.start < end }
            if (visible.isEmpty()) {
                ProgramCell(
                    if (channel.id in LocalRecordingChannels.current) "Recording" else "No information",
                    Modifier.fillMaxSize().tap(onLongClick = { onMenu(null) }, onClick = onTune),
                    selected = rowSelected,
                    // Shade the elapsed part like a live programme so the "now" line runs through every row.
                    progress = if (now in guideStart until end) {
                        ((now - guideStart).toFloat() / (end - guideStart).coerceAtLeast(1)).coerceIn(0f, 1f)
                    } else 0f,
                    live = now in guideStart until end,
                    recording = channel.id in LocalRecordingChannels.current,
                    catchup = false,
                )
            } else {
                for (p in visible) {
                    val s = maxOf(p.start, guideStart)
                    val e = minOf(p.stop, end)
                    ProgramCell(
                        title = (if (p.start < guideStart) "‹ " else "") + p.title,
                        progress = if (p.start <= now && now < p.stop) {
                            ((now - s).toFloat() / (e - s).coerceAtLeast(1)).coerceIn(0f, 1f)
                        } else 0f,
                        modifier = Modifier
                            .offset(x = x(s))
                            .width(x(e) - x(s))
                            .fillMaxHeight()
                            .tap(onLongClick = { onMenu(p) }, onClick = { onProgram(p) }),
                        selected = rowSelected && p.start <= focusTime && focusTime < p.stop,
                        live = p.start <= now && now < p.stop,
                        recording = TvViewModel.recordingKey(channel.id, p.start) in recordingKeys,
                        catchup = p.stop <= now && canCatchUp(p),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramCell(
    title: String,
    modifier: Modifier,
    selected: Boolean,
    live: Boolean,
    recording: Boolean,
    catchup: Boolean,
    progress: Float = 0f,
) {
    Box(
        modifier
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    selected -> Palette.Accent
                    live -> Palette.SurfaceLive
                    else -> Palette.Surface
                }
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Elapsed part of the show that's on now, drawn behind the title so the text stays readable.
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(if (selected) Color(0x26FFFFFF) else Color(0x1A3D8BFD))
            )
        }
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recording) Text("● ", fontSize = 13.sp, color = Palette.Recording)
            if (catchup) Text("↺ ", fontSize = 13.sp, color = Palette.TextDim)
            Text(title, fontSize = 13.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = Palette.Text, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.SurfaceLive)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun RulerButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.Surface)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = Palette.Text, maxLines = 1)
    }
}
