package io.github.axon07.channelbox.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import java.util.Date

object Palette {
    val Background = Color(0xFF141414)
    val Panel = Color(0xF2121820)
    val Surface = Color(0xFF1C1C1C)
    val SurfaceLive = Color(0xFF22262B)
    val Accent = Color(0xFFE50914)
    val Text = Color(0xFFF2F5F8)
    val TextDim = Color(0xFFA3AFBF)
    val Favorite = Color(0xFFFFCF40)
    val Error = Color(0xFFFF6B6B)
    val Recording = Color(0xFFFF4D4D)
}

@Composable
fun ChannelboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Background,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
        ),
    ) {
        // Arabic and other right-to-left languages: menus and settings read from the right.
        val rtl = I18n.language in RTL_LANGUAGES
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides
                if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            content = content,
        )
    }
}

/** Clock formatter that follows the box's 12/24-hour setting. */
@Composable
fun rememberTimeFormatter(): (Long) -> String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return remember(format) { { t: Long -> format.format(Date(t)) } }
}

/**
 * Channel logo. Transparent PNGs are drawn as-is (no box behind them); a small
 * placeholder with the channel's initials only shows when there's no logo or it fails to load.
 */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val placeholder = url.isNullOrBlank() || failed
    Box(
        modifier.then(
            if (placeholder) Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Surface) else Modifier
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder) {
            Text(
                text = name.take(3).uppercase(),
                color = Palette.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .background(Palette.Accent)
        )
    }
}

fun programProgress(start: Long, stop: Long, now: Long): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
