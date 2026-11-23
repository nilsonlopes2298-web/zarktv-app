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
 * ZarkTV: logo do canal. Tenta, nesta ordem: o logo que o servidor mandou, o logo do acervo
 * aberto tv-logos (pelo nome do canal: Globo, CNN Brasil, Paramount…) e, se nada vier, um
 * quadradinho neutro com as iniciais — assim a lista fica sempre arrumada.
 */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    val candidatos = remember(url, name) { listOfNotNull(url?.takeIf { it.isNotBlank() }, logoAcervo(name)) }
    var tentativa by remember(url, name) { mutableStateOf(0) }
    val atual = candidatos.getOrNull(tentativa)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (atual == null) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF232325)), contentAlignment = Alignment.Center) {
                Text(
                    text = iniciaisCanal(name),
                    color = Color(0xFFA1A1A6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        } else {
            AsyncImage(
                model = atual,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { tentativa++ },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}

/** "Globo SP FHD²" → "globo-sp", para achar o arquivo no acervo tv-logos (Brasil). */
fun logoAcervo(nome: String): String? {
    val base = java.text.Normalizer.normalize(nome, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        .replace(Regex("""[²³¹]|\b(fhd|uhd|4k|hd|sd|h265|hevc|alt|backup|24h)\b|\(.*?\)|\[.*?\]|\|.*$"""), " ")
        .replace(Regex("""^\s*\d+\s*[-.:]?\s*"""), "")
        .replace("&", " and ").replace("+", " plus ")
        .replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
    if (base.isBlank()) return null
    return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/brazil/$base-br.png"
}

private fun iniciaisCanal(n: String) = n.replace(Regex("""[^\p{L}\p{N} ]"""), " ").split(' ').filter { it.isNotBlank() }
    .take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { n.take(2).uppercase() }

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
