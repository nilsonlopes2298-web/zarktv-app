package io.github.axon07.channelbox.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import io.github.axon07.channelbox.data.RecStatus
import io.github.axon07.channelbox.data.RecordingEntity
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ---- Groups menu (Back from the guide) ---------------------------------------------------------

/**
 * Left-hand menu over the guide: a slim rail of shortcuts (Search, Movies, TV Shows, Recordings,
 * Multi-view, Settings) beside the channel groups (one section per playlist when there are several).
 * Index layout: 0 until rows.size = group rows, then +0 Multi-view, +1 Recordings, +2 Settings,
 * +3 Movies, +4 TV Shows, +5 Search.
 */
@Composable
fun GroupsPanel(
    rows: List<MenuRow>,
    counts: Map<ChannelFilter, Int>,
    active: ChannelFilter,
    selected: Int,
    listState: LazyListState,
    recordingCount: Int,
    movieCount: Int,
    showCount: Int,
    showRail: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    LaunchedEffect(selected) {
        if (selected < rows.size) listState.scrollToItem((selected - 4).coerceAtLeast(0))
    }
    val n = rows.size
    val inRail = selected >= n
    Row(
        Modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xFC0C1118), Color(0xF50F151D), Color(0xE60F151D))))
            .tap { },
    ) {
        // Shortcut rail (scrolls if the screen is too short, e.g. a phone on its side).
        val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
        if (showRail) Column(
            Modifier
                .fillMaxHeight()
                .width(if (compact) 72.dp else 84.dp)
                .background(Color(0x66000000))
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(vertical = if (compact) 8.dp else 20.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailItem("Search", AppIcons.Search, selected == n + 5) { onSelect(n + 5) }
            RailItem("Live TV", AppIcons.List, highlighted = !inRail, selected = selected == n + 6) { onSelect(n + 6) }
            if (movieCount > 0) RailItem("Movies", AppIcons.Film, selected == n + 3) { onSelect(n + 3) }
            if (showCount > 0) RailItem("TV Shows", AppIcons.Tv, selected == n + 4) { onSelect(n + 4) }
            RailItem(if (recordingCount > 0) "Recordings ($recordingCount)" else "Recordings", AppIcons.Record, selected == n + 1) { onSelect(n + 1) }
            RailItem("Settings", AppIcons.Settings, selected == n + 2) { onSelect(n + 2) }
        }
        // Groups.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .width(290.dp)
                .padding(horizontal = 10.dp),
            // Extra room at the bottom so the last group isn't hidden by TV overscan.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 20.dp, bottom = 56.dp),
        ) {
            itemsIndexed(rows) { i, row ->
                when (row) {
                    is MenuRow.Header -> SectionHeader(
                        label = row.label,
                        open = row.open,
                        collapsible = row.collapsible,
                        first = i == 0,
                        selected = i == selected,
                        onClick = { onSelect(i) },
                    )
                    is MenuRow.Item -> GroupRow(
                        filter = row.filter,
                        count = counts[row.filter],
                        active = row.filter == active,
                        selected = i == selected,
                        indented = row.indented,
                        onClick = { onSelect(i) },
                    )
                }
            }
        }
    }
}

/** One shortcut on the rail: icon with a small label under it. */
@Composable
private fun RailItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> Color(0x33FFFFFF)
                    highlighted -> Color(0x14FFFFFF)
                    else -> Color.Transparent
                }
            )
            .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .tap(onClick = onClick)
            .padding(vertical = if (compact) 5.dp else 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(icon, tint = if (selected || highlighted) Color.White else Color(0xFF9AA6B4), size = if (compact) 19.dp else 22.dp)
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Text(
            label, fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
            color = if (selected || highlighted) Color.White else Color(0xFF9AA6B4),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A playlist's heading: its name and an arrow showing whether its groups are open. */
@Composable
private fun SectionHeader(
    label: String,
    open: Boolean,
    collapsible: Boolean,
    first: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 8.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x29FFFFFF) else Color.Transparent)
            .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .tap(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            AppIcon(if (open) AppIcons.Up else AppIcons.Down, tint = if (selected) Color.White else Palette.TextDim, size = 18.dp)
        }
    }
}

/** A group: just its name and how many channels, like a cable box menu. */
@Composable
private fun GroupRow(
    filter: ChannelFilter,
    count: Int?,
    active: Boolean,
    selected: Boolean,
    indented: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(start = if (indented) 12.dp else 0.dp)
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x29FFFFFF) else Color.Transparent)
            .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .tap(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (filter == ChannelFilter.Favorites) {
            AppIcon(AppIcons.Star, tint = if (active || selected) Color(0xFFFFD27A) else Palette.TextDim, size = 15.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            filter.label,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Color(0xFF8FBBFF) else Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(count.toString(), fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ---- Recordings list ---------------------------------------------------------------------------

@Composable
fun RecordingsScreen(
    recordings: List<RecordingEntity>,
    selected: Int,
    listState: LazyListState,
    onPlay: (RecordingEntity) -> Unit,
    onMenu: (RecordingEntity) -> Unit,
) {
    LaunchedEffect(selected) {
        if (recordings.isNotEmpty()) listState.scrollToItem((selected - 3).coerceAtLeast(0))
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val touch = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode ==
        androidx.compose.ui.input.InputMode.Touch
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 40.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Recordings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            val total = recordings.sumOf { it.sizeBytes }
            Text(
                "  ${recordings.size}" + (if (total > 0) "  ·  ${formatSize(total)}" else ""),
                fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Text(
            if (touch) "Tap to play · long-press for options" else "OK to play · hold OK for options",
            fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(14.dp))
        if (recordings.isEmpty()) {
            Text(
                "No recordings yet.\n\nWhile watching, press ● Record on the remote or tap the picture → record.\n" +
                    "In the guide, choose a programme and Record.",
                fontSize = 14.sp, color = Palette.TextDim,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(recordings, key = { _, r -> r.id }) { i, r ->
                val isSelected = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0x21FFFFFF) else Color(0x0AFFFFFF))
                        .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .tap(onLongClick = { onMenu(r) }) { onPlay(r) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { StatusGlyph(r.status) }
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val failed = r.status == RecStatus.FAILED
                        Text(
                            if (failed) (r.error ?: "Didn't record") + "  ·  " + r.channelName
                            else listOfNotNull(
                                r.channelName,
                                dateFormat.format(Date(r.start)),
                                formatDuration(r.stop - r.start).takeIf { r.status == RecStatus.SCHEDULED },
                                formatSize(r.sizeBytes).takeIf { r.sizeBytes > 0 },
                            ).joinToString("  ·  "),
                            fontSize = 12.sp, color = if (failed) Palette.Error.copy(alpha = 0.85f) else Palette.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when (r.status) {
                        RecStatus.RECORDING -> Chip("REC", Palette.Recording)
                        RecStatus.SCHEDULED -> Chip("Scheduled", Color(0x33FFFFFF))
                        RecStatus.DONE -> if (r.error != null) Chip("Partly recorded", Color(0x33FFFFFF))
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusGlyph(status: String) {
    when (status) {
        RecStatus.RECORDING -> Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Recording))
        RecStatus.SCHEDULED -> AppIcon(AppIcons.Guide, tint = Palette.TextDim, size = 16.dp)
        RecStatus.DONE -> AppIcon(AppIcons.Play, tint = Color.White, size = 14.dp)
        RecStatus.FAILED -> AppIcon(AppIcons.Close, tint = Palette.Error, size = 14.dp)
        else -> AppIcon(AppIcons.Close, tint = Palette.TextDim, size = 14.dp)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

private fun statusIcon(status: String) = when (status) {
    RecStatus.RECORDING -> "●"
    RecStatus.SCHEDULED -> "⏰"
    RecStatus.DONE -> "▶"
    RecStatus.FAILED -> "✕"
    else -> "–"
}

private fun statusLabel(r: RecordingEntity) = when (r.status) {
    RecStatus.RECORDING -> "Recording now"
    RecStatus.SCHEDULED -> "Scheduled"
    RecStatus.DONE -> if (r.error != null) "Partly recorded" else ""
    RecStatus.FAILED -> r.error ?: "Failed"
    RecStatus.CANCELLED -> "Cancelled"
    else -> r.status
}

fun formatDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h} h ${m.toString().padStart(2, '0')} min" else "$m min"
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}

// ---- Playing a recording -----------------------------------------------------------------------

@Composable
fun BoxScope.PlaybackOverlay(
    title: String,
    position: Long,
    duration: Long,
    playing: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 20.dp)
    ) {
        Text(title, fontSize = 18.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatClock(position)} / ${if (duration > 0) formatClock(duration) else "--:--"}",
                fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("10 s", icon = AppIcons.Rewind) { onSeek(-10_000L) }
                Pill(if (playing) "Pause" else "Play", icon = if (playing) AppIcons.Pause else AppIcons.Play, onClick = onToggle)
                Pill("30 s", icon = AppIcons.Forward) { onSeek(30_000L) }
                Pill("Close", icon = AppIcons.Close, onClick = onClose)
            }
        }
    }
}

// ---- Generic button dialog ---------------------------------------------------------------------

data class DialogAction(val label: String, val onClick: () -> Unit)

/** Centered dialog with a row of buttons; works with the remote (focus) and touch/mouse. */
@Composable
fun ActionDialog(title: String, subtitle: String?, actions: List<DialogAction>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    // Short yes/no questions get two buttons side by side; menus get a tidy list.
    val compact = actions.size <= 2 && actions.all { it.label.length <= 14 }
    DialogCard(width = if (compact) 420.dp else 400.dp) {
        DialogTitle(title, subtitle)
        Spacer(Modifier.height(16.dp))
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, a ->
                    PillButton(
                        a.label,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        primary = i == 0,
                        onClick = a.onClick,
                    )
                }
            }
        } else {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions.forEachIndexed { i, a ->
                    val closing = a.label in CLOSE_LABELS
                    if (closing && i > 0) Spacer(Modifier.height(6.dp))
                    DialogRow(
                        a.label,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        danger = DANGER_WORDS.any { w -> a.label.startsWith(w) },
                        dim = closing,
                        onClick = a.onClick,
                    )
                }
            }
        }
    }
}

private val CLOSE_LABELS = setOf("Close", "Cancel", "Not now", "Later")
private val DANGER_WORDS = listOf("Delete", "Remove", "Stop recording", "Exit", "Clear")

// ---- Playback options (audio, subtitles, picture size) -----------------------------------------

val SCALE_LABELS = listOf("Fit", "Zoom (fill screen)", "Stretch")

@Composable
fun OptionsPanel(
    /** Pause live TV / Back to live (only while watching live TV). */
    live: List<DialogAction> = emptyList(),
    quality: List<io.github.axon07.channelbox.player.TrackOption>,
    onQuality: (io.github.axon07.channelbox.player.TrackOption) -> Unit,
    audio: List<io.github.axon07.channelbox.player.TrackOption>,
    subtitles: List<io.github.axon07.channelbox.player.TrackOption>,
    scale: Int,
    onAudio: (io.github.axon07.channelbox.player.TrackOption) -> Unit,
    onSubtitle: (io.github.axon07.channelbox.player.TrackOption) -> Unit,
    onScale: (Int) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x66000000)))),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xEB0E131A))
                .tap { }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 20.dp)
        ) {
            Text("Audio & subtitles", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
            var firstUsed = false
            fun Modifier.firstFocus(): Modifier = if (!firstUsed) { firstUsed = true; this.focusRequester(first) } else this

            if (live.isNotEmpty()) {
                Section("Live TV")
                live.forEach { a -> OptionRow(a.label, false, Modifier.firstFocus(), onClick = a.onClick) }
            }
            Section("Subtitles")
            subtitles.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onSubtitle(t) } }

            Section("Audio")
            if (audio.isEmpty()) Text("No audio tracks yet", fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 10.dp))
            audio.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onAudio(t) } }

            if (quality.size > 1) {
                Section("Quality")
                quality.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onQuality(t) } }
            }

            Section("Picture size")
            SCALE_LABELS.forEachIndexed { i, label -> OptionRow(label, i == scale, Modifier.firstFocus()) { onScale(i) } }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp).tap(onClick = onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Color.White else Palette.Text,
            focusedContainerColor = Color(0x24FFFFFF),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0x33FFFFFF),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (selected) Text("✓", fontSize = 14.sp, color = Palette.Accent, fontWeight = FontWeight.Bold)
        }
    }
}
