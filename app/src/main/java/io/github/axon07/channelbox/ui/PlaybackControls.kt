package io.github.axon07.channelbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything the player bar shows. */
data class PlaybackUi(
    val title: String,
    val subtitle: String? = null,
    val position: Long,
    val duration: Long,
    val buffered: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    /** Seconds just skipped (shown briefly as "−10 s" / "+30 s"), 0 = none. */
    val skipped: Long = 0,
    /** ZarkTV: capa do filme/série mostrada na barra. */
    val poster: String? = null,
)

/**
 * Player bar for movies, episodes, recordings and catch-up. Remote: OK play/pause, ◀ ▶ skip,
 * hold OK for audio & subtitles, Back to close. Touch/mouse: tap the buttons.
 */
/** Buttons on the movie / recording controls, in remote order (◀ ▶ move between them). */
enum class PlaybackSlot { REWIND, PLAY, FORWARD, CAPTIONS, OPTIONS }

/** Buttons on the live TV controls, in remote order. */
enum class LiveSlot { FAVORITE, RECORD, REWIND, PAUSE, LIVE, CAPTIONS, MULTIVIEW, PIP, OPTIONS }

/**
 * The live buttons actually shown (LIVE only after a pause, PiP only where the box supports it,
 * rewind only on channels with catch-up).
 */
fun liveSlots(behindLive: Boolean, pip: Boolean, rewind: Boolean = false): List<LiveSlot> = LiveSlot.entries.filter {
    (it != LiveSlot.LIVE || behindLive) && (it != LiveSlot.PIP || pip) && (it != LiveSlot.REWIND || rewind)
}

@Composable
fun BoxScope.PlaybackControls(
    ui: PlaybackUi,
    visible: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOptions: () -> Unit,
    onClose: () -> Unit,
    /** Captions on/off. */
    onCaptions: () -> Unit = onOptions,
    captionsOn: Boolean = false,
    /** Button highlighted for the remote (▼ moves into the buttons), or null. */
    focused: PlaybackSlot? = null,
) {
    // Big centre button while paused, finished or loading.
    if (visible || !ui.playing || ui.buffering) {
        Box(Modifier.align(Alignment.Center)) {
            when {
                ui.buffering && ui.playing -> LoadingRing()
                focused != null -> Unit // the remote is on the buttons below
                !ui.playing || ui.ended -> RoundButton(
                    icon = if (ui.ended) AppIcons.Refresh else AppIcons.Play,
                    size = 76.dp, onClick = onToggle,
                )
            }
        }
    }
    // Skip feedback in the middle-left / middle-right.
    if (ui.skipped != 0L) {
        Box(Modifier.align(if (ui.skipped < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 80.dp)) {
            Text(
                (if (ui.skipped < 0) "−" else "+") + "${kotlin.math.abs(ui.skipped) / 1000} s",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }

    AnimatedVisibility(
        visible = visible || !ui.playing,
        enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val touch = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode ==
            androidx.compose.ui.input.InputMode.Touch
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xE6000000))))
                .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 14.dp)
        ) {
            // ZarkTV (estilo UniTV): capa à esquerda, título, tempo e barra vermelha.
            Row(verticalAlignment = Alignment.Bottom) {
                if (!ui.poster.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        ui.poster, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.width(62.dp).height(92.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(ui.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.subtitle?.let {
                        Text(it, fontSize = 13.sp, color = Color(0xFFFFC107), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(ui.position), fontSize = 13.sp, color = Color.White, modifier = Modifier.width(70.dp))
                        Box(Modifier.weight(1f)) { Scrubber(ui, onSeekTo) }
                        Text(
                            if (ui.duration > 0) formatClock(ui.duration) else "",
                            fontSize = 13.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.width(70.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // Transport in the middle; captions and close on the right; remote hints on the left.
            Box(Modifier.fillMaxWidth()) {
                if (!touch) {
                    Text(
                        if (focused != null) "◀ ▶ escolher · OK confirmar · ▲ voltar" else "OK pausar · ◀ voltar 10s · ▶ avançar 30s · ▼ mais",
                        fontSize = 11.sp, color = Palette.TextDim, maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTap(AppIcons.Rewind, size = 26.dp, badge = "10", selected = focused == PlaybackSlot.REWIND) { onSeek(-10_000L) }
                    IconTap(
                        if (ui.ended) AppIcons.Refresh else if (ui.playing) AppIcons.Pause else AppIcons.Play,
                        size = 38.dp, selected = focused == PlaybackSlot.PLAY, onClick = onToggle,
                    )
                    IconTap(AppIcons.Forward, size = 26.dp, badge = "30", selected = focused == PlaybackSlot.FORWARD) { onSeek(30_000L) }
                }
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTap(AppIcons.Captions, size = 22.dp, active = captionsOn, selected = focused == PlaybackSlot.CAPTIONS, onClick = onCaptions)
                    IconTap(AppIcons.More, size = 22.dp, selected = focused == PlaybackSlot.OPTIONS, onClick = onOptions)
                }
            }
        }
    }
}

@Composable
private fun Scrubber(ui: PlaybackUi, onSeekTo: (Long) -> Unit) {
    val dur = ui.duration.coerceAtLeast(1)
    val played = if (ui.duration > 0) (ui.position.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val buffered = if (ui.duration > 0) (ui.buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .tapAt { fraction -> if (ui.duration > 0) onSeekTo((fraction * ui.duration).toLong()) },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(buffered).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(played).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
        if (ui.duration > 0) {
            val thumb = 11.dp
            Box(
                Modifier
                    .offset(x = (width - thumb) * played)
                    .size(thumb)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/** A plain white icon you can tap; [badge] = small number under it (skip seconds). */
@Composable
internal fun IconTap(
    icon: ImageVector,
    size: Dp,
    badge: String? = null,
    active: Boolean = false,
    /** Highlighted for the remote. */
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape)
            .tap(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(icon, tint = Color.White, size = size)
            // A small bar under an icon that's switched on (captions).
            if (active) Box(Modifier.padding(top = 2.dp).size(width = size * 0.6f, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(Palette.Accent))
            if (badge != null) Text(badge, fontSize = 9.sp, color = Color(0xCCFFFFFF), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Big centre button while paused or finished: a soft dark circle with a white icon. */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    size: Dp,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x80000000))
            .border(1.dp, Color(0x40FFFFFF), CircleShape)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint = Color.White, size = size * 0.42f)
    }
}

@Composable
internal fun LoadingRing() {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
        val angle by spin.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)
            ),
            label = "angle",
        )
        androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
            drawArc(
                color = Color.White, startAngle = angle, sweepAngle = 270f, useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/**
 * Live TV controls (tap the picture): same look as the movie player – channel and programme,
 * the programme's progress, pause / record / live in the middle, small icons on the right.
 */
@Composable
fun BoxScope.LiveControls(
    channel: io.github.axon07.channelbox.data.ChannelEntity,
    program: io.github.axon07.channelbox.data.ProgramEntity?,
    now: Long,
    timeFormat: (Long) -> String,
    favorite: Boolean,
    recording: Boolean,
    paused: Boolean,
    behindLive: Boolean,
    onPause: () -> Unit,
    onLive: () -> Unit,
    /** ◀◀ through the provider's archive; null when the channel has no catch-up. */
    onRewind: (() -> Unit)? = null,
    onRecord: () -> Unit,
    onFavorite: () -> Unit,
    onOptions: () -> Unit,
    onPip: (() -> Unit)?,
    onMultiView: () -> Unit,
    onCaptions: () -> Unit,
    captionsOn: Boolean,
    /** Button highlighted for the remote, or null. */
    focused: LiveSlot? = null,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xE6000000))))
            .tap { } // swallow taps between the buttons
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    program?.title ?: channel.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${channel.number}  ${channel.name}", fontSize = 13.sp, color = Palette.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // How far into the programme we are.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(program?.let { timeFormat(it.start) } ?: "", fontSize = 12.sp, color = Color.White, modifier = Modifier.width(72.dp))
            val fraction = program?.let { ((now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
            Box(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
            }
            Text(
                program?.let { timeFormat(it.stop) } ?: "", fontSize = 12.sp, color = Palette.TextDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(72.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTapTint(if (favorite) AppIcons.Star else AppIcons.StarOutline, if (favorite) Palette.Favorite else Color.White, onFavorite, selected = focused == LiveSlot.FAVORITE)
            }
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTapTint(if (recording) AppIcons.Stop else AppIcons.Record, Palette.Recording, onRecord, size = 24.dp, selected = focused == LiveSlot.RECORD)
                if (onRewind != null) IconTap(AppIcons.Rewind, size = 26.dp, selected = focused == LiveSlot.REWIND, onClick = onRewind)
                IconTap(if (paused) AppIcons.Play else AppIcons.Pause, size = 38.dp, selected = focused == LiveSlot.PAUSE, onClick = onPause)
                if (behindLive) IconTap(AppIcons.Forward, size = 26.dp, badge = "LIVE", selected = focused == LiveSlot.LIVE, onClick = onLive)
                else Spacer(Modifier.width(38.dp))
            }
            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTap(AppIcons.Captions, size = 22.dp, active = captionsOn, selected = focused == LiveSlot.CAPTIONS, onClick = onCaptions)
                IconTap(AppIcons.Grid, size = 22.dp, selected = focused == LiveSlot.MULTIVIEW, onClick = onMultiView)
                if (onPip != null) IconTap(AppIcons.Pip, size = 22.dp, selected = focused == LiveSlot.PIP, onClick = onPip)
                IconTap(AppIcons.More, size = 22.dp, selected = focused == LiveSlot.OPTIONS, onClick = onOptions)
            }
        }
    }
}

@Composable
private fun IconTapTint(icon: ImageVector, tint: Color, onClick: () -> Unit, size: Dp = 22.dp, selected: Boolean = false) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape)
            .tap(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint = tint, size = size)
    }
}

/** Celular: rodinha igual à do YouTube — só um arco branco que gira, aumenta e diminui. Discreta. */
@Composable
internal fun CarregandoYt(tamanho: androidx.compose.ui.unit.Dp = 44.dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "yt")
    val giro by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "giro",
    )
    val fase by t.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_100, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "fase",
    )
    androidx.compose.foundation.Canvas(Modifier.size(tamanho)) {
        val traco = 3.5.dp.toPx()
        val varre = 30f + 240f * fase
        drawArc(
            color = Color.White, startAngle = giro * 2f + fase * 120f, sweepAngle = varre, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(traco / 2, traco / 2),
            size = androidx.compose.ui.geometry.Size(size.width - traco, size.height - traco),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = traco, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
