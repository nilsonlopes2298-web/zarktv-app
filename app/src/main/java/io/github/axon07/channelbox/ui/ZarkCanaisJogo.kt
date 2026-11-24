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
