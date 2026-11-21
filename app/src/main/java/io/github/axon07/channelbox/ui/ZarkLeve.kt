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
