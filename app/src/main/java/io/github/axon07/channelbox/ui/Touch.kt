package io.github.axon07.channelbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tap / long-press for touch screens and mice. Pointer only, so it never
 * doubles up with the remote's OK button, which the TV components already handle.
 */
fun Modifier.tap(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { longClick?.invoke() },
            onTap = { click() },
        )
    }
}

/** Tap (touch/mouse) reporting where along the width it landed, 0..1 – for seek bars. */
fun Modifier.tapAt(onTap: (Float) -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        detectTapGestures { offset -> if (size.width > 0) tap((offset.x / size.width).coerceIn(0f, 1f)) }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = Palette.Text,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) Palette.Accent else Color(0xE61F2A37))
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, tint = iconTint, size = 16.dp)
            if (text.isNotEmpty()) androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 7.dp))
        }
        if (text.isNotEmpty()) Text(text, fontSize = 14.sp, color = Palette.Text, maxLines = 1)
    }
}

/**
 * On-screen controls shown when the video is tapped (phones, tablets, mouse): a slim bar along the
 * bottom. Channels, the guide and settings are in the menus, so only things about what's playing.
 */
@Composable
fun BoxScope.TouchBar(
    favorite: Boolean,
    recording: Boolean,
    onFavorite: () -> Unit,
    onRecord: () -> Unit,
    onCaptions: () -> Unit,
    paused: Boolean,
    behindLive: Boolean,
    onPause: () -> Unit,
    onLive: () -> Unit,
    onOptions: () -> Unit,
    onPip: (() -> Unit)?,
    onMultiView: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            .tap { } // swallow taps between the buttons
            .horizontalScroll(rememberScrollState())
            .padding(top = 18.dp, bottom = 10.dp, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
    ) {
        ControlButton(if (paused) "Resume" else "Pause", if (paused) AppIcons.Play else AppIcons.Pause, onClick = onPause)
        if (behindLive) ControlButton("Live", AppIcons.Forward, onClick = onLive)
        ControlButton(
            if (recording) "Stop" else "Record", if (recording) AppIcons.Stop else AppIcons.Record,
            tint = Palette.Recording, onClick = onRecord,
        )
        ControlButton(
            "Favourite", if (favorite) AppIcons.Star else AppIcons.StarOutline,
            tint = if (favorite) Palette.Favorite else Color.White, onClick = onFavorite,
        )
        ControlButton("Captions", AppIcons.Captions, onClick = onCaptions)
        ControlButton("Audio", AppIcons.Audio, onClick = onOptions)
        if (onPip != null) ControlButton("Mini", AppIcons.Pip, onClick = onPip)
        ControlButton("Multi-view", AppIcons.Grid, onClick = onMultiView)
    }
}

@Composable
private fun ControlButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        Modifier.tap(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(38.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0x26FFFFFF)),
            contentAlignment = Alignment.Center,
        ) { AppIcon(icon, tint = tint, size = 18.dp) }
        Text(label, fontSize = 10.sp, color = Color(0xE6FFFFFF), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}
