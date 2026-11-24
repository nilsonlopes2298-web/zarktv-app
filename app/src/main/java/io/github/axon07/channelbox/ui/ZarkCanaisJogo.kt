package io.github.axon07.channelbox.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * ZarkTV: memória dos canais (leve, só no aparelho). Canal que já tocou bem sobe para o começo
 * da lista do jogo; canal que deu erro nas últimas 6 horas vai para o fim. Assim, da próxima vez
 * o jogo já abre direto num canal que funciona.
 */
object MemoriaCanais {
    private fun p(c: Context) = c.getSharedPreferences("zarktv_canais", Context.MODE_PRIVATE)
    fun funcionou(c: Context, id: String) = p(c).edit().putLong("ok_$id", System.currentTimeMillis()).remove("erro_$id").apply()
    fun deuErro(c: Context, id: String) = p(c).edit().putLong("erro_$id", System.currentTimeMillis()).apply()
    /** Ordena: os que funcionaram (mais recente primeiro), depois o resto, e os com erro recente no fim. */
    fun <T> ordenar(c: Context, lista: List<T>, id: (T) -> String): List<T> {
        val pr = p(c); val agora = System.currentTimeMillis()
        fun erroRecente(x: T) = agora - pr.getLong("erro_${id(x)}", 0L) < 6 * 3600_000L
        val bons = lista.filter { pr.getLong("ok_${id(it)}", 0L) > 0 && !erroRecente(it) }.sortedByDescending { pr.getLong("ok_${id(it)}", 0L) }
        val ruins = lista.filter(::erroRecente)
        return bons + lista.filter { it !in bons && it !in ruins } + ruins
    }
}

/** Opções de canal do jogo aberto (mostradas como botões embaixo do vídeo). */
object JogoOpcoes {
    val nomes = mutableStateOf<List<String>>(emptyList())
    val atual = mutableIntStateOf(0)
    var escolher: (Int) -> Unit = {}
}

/**
 * Outras qualidades do mesmo canal (ex.: "BandSports FHD" ↔ "BandSports HD" ↔ "BandSports SD"):
 * compara o nome sem as marcas de qualidade. Rótulo curto para o botão (FHD, HD, SD, 4K…).
 */
object Qualidades {
    private val marcas = Regex("""(?i)\b(fhd|full ?hd|uhd|4k|hd|sd|hevc|h\.?265|h\.?264|60fps|fps|alt\d*|backup|bkp|\d{3,4}p)\b|[\[\]\(\)|¹²³⁴•*★_-]""")
    fun base(nome: String) = nome.replace(marcas, " ").replace(Regex("\\s+"), " ").trim().lowercase()
    fun rotulo(nome: String): String {
        val m = Regex("""(?i)\b(fhd|full ?hd|uhd|4k|hd|sd|hevc|h\.?265|\d{3,4}p)\b""").findAll(nome).map { it.value.uppercase().replace("FULL HD", "FHD").replace("FULLHD", "FHD") }.toList()
        return m.joinToString(" ").ifBlank { "Normal" }
    }
    fun <T> variantes(atual: T, todos: List<T>, nome: (T) -> String, id: (T) -> String): List<T> {
        val b = base(nome(atual))
        if (b.length < 2) return emptyList()
        return todos.filter { base(nome(it)) == b }.distinctBy(id).takeIf { it.size > 1 }.orEmpty()
    }
}
