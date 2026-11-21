package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface

/** Shared look for pop-ups: flat dark card, quiet rows, a light pill on the focused item. */
object DialogStyle {
    val Scrim = Color(0xB3000000)
    val Card = Color(0xFF131A22)
    val Edge = Color(0x14FFFFFF)
    val Focus = Color(0xFFECF1F6)
    val OnFocus = Color(0xFF0E141B)
    val Rest = Color(0x10FFFFFF)
    val Danger = Color(0xFFFF8A80)
    val Shape = RoundedCornerShape(14.dp)
    val ItemShape = RoundedCornerShape(10.dp)
}

/** Dimmed screen with a centred card. */
@Composable
fun DialogCard(
    width: Dp = 440.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier.fillMaxSize().background(DialogStyle.Scrim).tap { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(width)
                .clip(DialogStyle.Shape)
                .background(DialogStyle.Card)
                .border(1.dp, DialogStyle.Edge, DialogStyle.Shape)
                .tap { }
                .padding(horizontal = 22.dp, vertical = 20.dp),
            content = content,
        )
    }
}

@Composable
fun DialogTitle(title: String, subtitle: String? = null) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (!subtitle.isNullOrBlank()) {
        Text(subtitle, fontSize = 13.sp, color = Palette.TextDim, maxLines = 3, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A full-width choice in a pop-up list. */
@Composable
fun DialogRow(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(DialogStyle.ItemShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = DialogStyle.Focus,
            focusedContentColor = DialogStyle.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.01f)),
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                focused -> DialogStyle.OnFocus
                danger -> DialogStyle.Danger
                dim -> Palette.TextDim
                else -> Palette.Text
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** A compact flat button (Save, Cancel, Test…). [primary]: filled in the accent colour. */
@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(DialogStyle.ItemShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Palette.Accent else DialogStyle.Rest,
            contentColor = Palette.Text,
            focusedContainerColor = DialogStyle.Focus,
            focusedContentColor = DialogStyle.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.03f)),
    ) {
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (focused) DialogStyle.OnFocus else Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
