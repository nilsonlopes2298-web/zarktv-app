package io.github.axon07.channelbox.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small line icons drawn in code, so they look the same on every box
 * (some Android TV fonts are missing symbols like ⏻ and show a box with an X).
 */
object AppIcons {

    private fun icon(name: String, vararg paths: Pair<String, Boolean>): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { (d, filled) ->
            if (filled) {
                b.addPath(addPathNodes(d), fill = SolidColor(Color.White))
            } else {
                b.addPath(
                    addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    private fun stroke(d: String) = d to false
    private fun fill(d: String) = d to true

    val List = icon("list", stroke("M4,6H20 M4,12H20 M4,18H14"))
    val Play = icon("play", fill("M8,5L19,12L8,19Z"))
    val Pause = icon("pause", fill("M7,5H10V19H7Z M14,5H17V19H14Z"))
    val Rewind = icon("rewind", fill("M11,6L3,12L11,18Z M20,6L12,12L20,18Z"))
    val Forward = icon("forward", fill("M4,6L12,12L4,18Z M13,6L21,12L13,18Z"))
    val More = icon("more", fill("M12,4.5A2,2 0 1,1 11.99,4.5Z M12,10A2,2 0 1,1 11.99,10Z M12,15.5A2,2 0 1,1 11.99,15.5Z"))
    val Record = icon("record", fill("M12,5A7,7 0 1,1 11.99,5Z"))
    val Stop = icon("stop", fill("M6,6H18V18H6Z"))
    val Power = icon("power", stroke("M12,3V11"), stroke("M7.05,6.05A7.5,7.5 0 1,0 16.95,6.05"))
    val Refresh = icon("refresh", stroke("M19,12A7,7 0 1,1 16.95,7.05"), stroke("M17,3V7.5H12.5"))
    val Info = icon("info", stroke("M12,3A9,9 0 1,1 11.99,3Z"), stroke("M12,11V16.5"), fill("M12,6.6A1.2,1.2 0 1,1 11.99,6.6Z"))
    val Close = icon("close", stroke("M6,6L18,18 M18,6L6,18"))
    val Up = icon("up", stroke("M6,15L12,9L18,15"))
    val Down = icon("down", stroke("M6,9L12,15L18,9"))
    val Guide = icon("guide", stroke("M4,5H20V19H4Z M4,10H20 M4,14.5H20 M10,10V19"))
    val Audio = icon("audio", stroke("M9,17V6L19,4V15"), fill("M6.5,14.5A2.5,2.5 0 1,1 6.49,14.5Z M16.5,12.5A2.5,2.5 0 1,1 16.49,12.5Z"))
    val Captions = icon("captions", stroke("M3.5,5.5H20.5V18.5H3.5Z"), stroke("M10.5,10.2A2,2 0 1,0 10.5,13.8 M17,10.2A2,2 0 1,0 17,13.8"))
    val Pip = icon("pip", stroke("M3,5H21V19H3Z"), fill("M12,11H19V17H12Z"))
    val Grid = icon("grid", stroke("M4,4H11V11H4Z M13,4H20V11H13Z M4,13H11V20H4Z M13,13H20V20H13Z"))
    val Shield = icon("shield", stroke("M12,3L19,6V11.5C19,15.9 16,19.6 12,21C8,19.6 5,15.9 5,11.5V6Z"), stroke("M9,12L11.2,14.2L15.2,10"))
    val Folder = icon("folder", stroke("M3.5,6.5H9.5L11.5,8.5H20.5V18.5H3.5Z"))
    val Home = icon("home", stroke("M4,11L12,4L20,11 M6.5,9V20H17.5V9"))
    val Download = icon("download", stroke("M12,4V15 M7.5,10.5L12,15L16.5,10.5 M5,20H19"))
    val Globe = icon("globe", stroke("M12,3A9,9 0 1,1 11.99,3Z M3,12H21 M12,3C9.5,6 9.5,18 12,21 M12,3C14.5,6 14.5,18 12,21"))
    val Lock = icon("lock", stroke("M6,11H18V20H6Z"), stroke("M8.5,11V8A3.5,3.5 0 0,1 15.5,8V11"))
    val Search = icon("search", stroke("M10.5,4A6.5,6.5 0 1,1 10.49,4Z M15.5,15.5L20,20"))
    val Phone = icon("phone", stroke("M8,3H16V21H8Z M11,18H13"))
    val Film = icon("film", stroke("M4,5H20V19H4Z M8,5V19 M16,5V19 M4,9.5H8 M4,14.5H8 M16,9.5H20 M16,14.5H20"))
    /** Catch-up: a clock with an arrow going back. */
    val History = icon("history", stroke("M4.6,13A7.5,7.5 0 1,0 6.7,6.7"), stroke("M3.8,4.6V8.6H7.8"), stroke("M12,8V12.3L14.8,14"))
    val Bell = icon("bell", stroke("M6,17V11A6,6 0 0,1 18,11V17L20,19H4Z"), stroke("M10,21H14"))
    val Tv = icon("tv", stroke("M3,6H21V17H3Z M8,21H16 M12,17V21"))
    val Star = star(true)
    val StarOutline = star(false)
    val Settings = gear()
    val Exit = icon("exit", stroke("M14,4H19V20H14"), stroke("M4,12H14 M10,8L14,12L10,16"))

    private fun star(filled: Boolean): ImageVector {
        val pts = (0 until 10).joinToString(" ") { i ->
            val r = if (i % 2 == 0) 9.0 else 3.9
            val a = Math.toRadians(-90.0 + i * 36)
            "${if (i == 0) "M" else "L"}${12 + r * cos(a)},${12.6 + r * sin(a)}"
        } + "Z"
        return icon(if (filled) "star" else "star_outline", if (filled) fill(pts) else stroke(pts))
    }

    private fun gear(): ImageVector {
        val teeth = 8
        val sb = StringBuilder()
        for (i in 0 until teeth * 2) {
            val r = if (i % 2 == 0) 9.0 else 7.0
            val a1 = Math.toRadians(i * 360.0 / (teeth * 2) - 360.0 / (teeth * 8))
            val a2 = Math.toRadians(i * 360.0 / (teeth * 2) + 360.0 / (teeth * 8))
            sb.append(if (i == 0) "M" else "L").append("${12 + r * cos(a1)},${12 + r * sin(a1)}")
            sb.append("L${12 + r * cos(a2)},${12 + r * sin(a2)}")
        }
        sb.append("Z")
        return icon("settings", stroke(sb.toString()), stroke("M12,9A3,3 0 1,1 11.99,9Z"))
    }
}

@Composable
fun AppIcon(icon: ImageVector, tint: Color = Palette.Text, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
