package io.github.axon07.channelbox.ui

import android.app.ActivityManager
import android.content.Context

/**
 * Modo leve: TV Box/aparelho fraco (pouca memória ou processador simples).
 * Tira as animações de zoom no foco, troca os banners mais devagar e carrega
 * as imagens em qualidade mais leve. Detectado sozinho na abertura do app.
 */
object ZarkLeve {
    @Volatile var ativo = false
        private set

    fun detectar(ctx: Context) {
        ativo = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = mem.totalMem / (1024.0 * 1024 * 1024)
            val nucleos = Runtime.getRuntime().availableProcessors()
            am.isLowRamDevice || gb < 2.5 || (nucleos <= 4 && gb < 3.5) || am.memoryClass <= 192
        }.getOrDefault(false)
    }

    /** Zoom do foco: no modo leve fica parado (sem animação). */
    fun escala(f: Float): Float = if (ativo) 1f else f
}

/**
 * Layout da tela inicial: "unitv" (abas, prévia, catálogo) ou "leve" (4 cartões e canais em 3 colunas,
 * estilo Zplay 2). Vem do painel (campo "layout" no config.json: unitv, leve ou auto) e o cliente pode
 * trocar no Perfil. "auto" = leve só em aparelho fraco.
 */
object ZarkLayout {
    val leve = androidx.compose.runtime.mutableStateOf(false)
    private fun p(c: Context) = c.getSharedPreferences("zarktv", Context.MODE_PRIVATE)

    fun escolha(c: Context): String =
        p(c).getString("layout_local", null) ?: io.github.axon07.channelbox.data.ZarkPainel.extras(c).layout ?: "padrao"

    fun atualizar(c: Context) {
        leve.value = when (escolha(c).lowercase()) {
            "leve", "lite", "lista", "zplay", "simples" -> true
            "auto" -> ZarkLeve.ativo
            // Padrão (sem nada no painel): visual UniTV (abas, prévia e catálogo).
            else -> false
        }
    }

    /** Perfil: Automático → Leve → Completo → Automático. */
    fun proximo(c: Context) {
        val atual = p(c).getString("layout_local", null) ?: "padrao"
        val novo = when (atual) { "padrao", "unitv" -> "leve"; "leve" -> "auto"; else -> "padrao" }
        p(c).edit().putString("layout_local", novo).apply()
        atualizar(c)
    }

    fun nome(c: Context): String = when (p(c).getString("layout_local", null) ?: "padrao") {
        "leve" -> "Leve (4 cartões)"
        "auto" -> "Automático"
        else -> "UniTV (padrão)"
    }
}
