package io.github.axon07.channelbox.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Modelos de cor do app. O padrão vem do painel ("tema" no config.json); o cliente pode trocar no Perfil. */
data class ZarkModelo(val id: String, val nome: String, val cores: List<Color>, val destaque: Color) {
    val fundo: Brush get() = Brush.linearGradient(cores)
}

object ZarkTema {
    val modelos = listOf(
        ZarkModelo("vermelho", "Vermelho", listOf(Color(0xFF6B0B14), Color(0xFF3A0509), Color(0xFF160204)), Color(0xFFFFB74D)),
        ZarkModelo("preto", "Preto e vermelho", listOf(Color(0xFF1C1C1C), Color(0xFF0E0E0E), Color(0xFF050505)), Color(0xFFE50914)),
        ZarkModelo("roxo", "Roxo e dourado", listOf(Color(0xFF3B1466), Color(0xFF1E0A38), Color(0xFF0C0418)), Color(0xFFFFC23D)),
        ZarkModelo("azul", "Azul", listOf(Color(0xFF0D2A66), Color(0xFF081A40), Color(0xFF030A1C)), Color(0xFF4FC3F7)),
    )

    var atual by mutableStateOf(modelos[0])

    /** Lê a escolha do cliente; se não tiver, usa o tema mandado pelo painel. */
    fun carregar(context: Context, doPainel: String?) {
        val local = context.getSharedPreferences("zarktv", Context.MODE_PRIVATE).getString("tema_local", null)
        atual = modelos.firstOrNull { it.id == (local ?: doPainel) } ?: modelos[0]
    }

    fun proximo(context: Context) {
        val i = modelos.indexOf(atual)
        atual = modelos[(i + 1) % modelos.size]
        context.getSharedPreferences("zarktv", Context.MODE_PRIVATE).edit().putString("tema_local", atual.id).apply()
    }
}
