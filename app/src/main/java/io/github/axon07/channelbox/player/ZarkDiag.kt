package io.github.axon07.channelbox.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ZarkTV: medições do player, guardadas só no aparelho (sem senha, sem endereço com usuário).
 * Mede quanto o vídeo leva para abrir, quantas vezes trava e quais erros acontecem.
 * Aparece em Configurações → Diagnóstico e pode ser enviado ao suporte pelo WhatsApp.
 */
object ZarkDiag {
    private const val PREFS = "zarktv_diag"
    private const val MAX = 30

    private var prefs: android.content.SharedPreferences? = null

    // Estado do vídeo atual.
    private var inicio = 0L
    private var aberto = false
    private var travouEm = 0L
    private var tipoAtual = "canal"

    /** Últimas aberturas (ms), mais recentes no fim. */
    private val aberturas = ArrayDeque<Long>()

    fun iniciar(c: Context) {
        if (prefs != null) return
        prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs?.getString("aberturas", null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.let { aberturas.addAll(it.takeLast(MAX)) }
    }

    /** Começou a abrir um canal, filme ou episódio. */
    fun abrindo(aoVivo: Boolean) {
        inicio = SystemClock.elapsedRealtime()
        aberto = false
        travouEm = 0L
        tipoAtual = if (aoVivo) "canal" else "filme"
    }

    /** Apareceu o primeiro quadro: registra quanto levou. */
    fun primeiroQuadro() {
        if (aberto || inicio == 0L) return
        aberto = true
        val ms = SystemClock.elapsedRealtime() - inicio
        if (ms in 1..120_000) {
            aberturas.addLast(ms)
            while (aberturas.size > MAX) aberturas.removeFirst()
            prefs?.edit()?.putString("aberturas", aberturas.joinToString(","))?.apply()
        }
        somar("aberturas_$tipoAtual")
    }

    /** Parou para carregar depois de já estar tocando. */
    fun travou() {
        if (!aberto || travouEm != 0L) return
        travouEm = SystemClock.elapsedRealtime()
        somar("travadas")
    }

    /** Voltou a tocar depois de travar. */
    fun voltou() {
        if (travouEm == 0L) return
        val ms = SystemClock.elapsedRealtime() - travouEm
        travouEm = 0L
        prefs?.let { p -> p.edit().putLong("travadas_ms", p.getLong("travadas_ms", 0L) + ms).apply() }
    }

    /** Erro do player, agrupado por tipo (nunca guarda o endereço). */
    fun erro(e: PlaybackException) {
        var c: Throwable? = e
        var tipo: String? = null
        while (c != null && tipo == null) {
            tipo = when (c) {
                is HttpDataSource.InvalidResponseCodeException -> "HTTP ${c.responseCode}"
                is UnknownHostException -> "Endereço não encontrado (DNS)"
                is SocketTimeoutException -> "Servidor demorou (timeout)"
                else -> null
            }
            c = c.cause
        }
        if (tipo == null) tipo = when (e.errorCode) {
            in 4000..4999 -> "Decodificação (formato do vídeo)"
            in 2000..2999 -> "Rede"
            in 3000..3999 -> "Arquivo/formato inválido"
            else -> e.errorCodeName
        }
        somar("erro|$tipo")
    }

    private fun somar(chave: String) {
        prefs?.let { p -> p.edit().putInt(chave, p.getInt(chave, 0) + 1).apply() }
    }

    fun zerar() {
        aberturas.clear()
        prefs?.edit()?.clear()?.apply()
    }

    data class Resumo(
        val aberturaMediaMs: Long?,
        val aberturaPiorMs: Long?,
        val vidoesAbertos: Int,
        val travadas: Int,
        val travadaMediaMs: Long?,
        val erros: List<Pair<String, Int>>,
    )

    fun resumo(): Resumo {
        val p = prefs
        val abertos = (p?.getInt("aberturas_canal", 0) ?: 0) + (p?.getInt("aberturas_filme", 0) ?: 0)
        val trav = p?.getInt("travadas", 0) ?: 0
        val travMs = p?.getLong("travadas_ms", 0L) ?: 0L
        val erros = p?.all.orEmpty().filterKeys { it.startsWith("erro|") }
            .map { (k, v) -> k.removePrefix("erro|") to ((v as? Int) ?: 0) }
            .sortedByDescending { it.second }
        return Resumo(
            aberturaMediaMs = aberturas.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            aberturaPiorMs = aberturas.maxOrNull(),
            vidoesAbertos = abertos,
            travadas = trav,
            travadaMediaMs = if (trav > 0) travMs / trav else null,
            erros = erros,
        )
    }

    /** Texto pronto para mandar ao suporte (sem dados da conta). */
    fun texto(c: Context, versao: String, velocidadeMBs: Double): String {
        val r = resumo()
        val am = android.app.ActivityManager.MemoryInfo().also {
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }
        val livre = c.filesDir.usableSpace / (1024 * 1024)
        fun seg(ms: Long?) = if (ms == null) "–" else String.format(java.util.Locale("pt", "BR"), "%.1f s", ms / 1000.0)
        return buildString {
            appendLine("Diagnóstico ZarkTV $versao")
            appendLine("Aparelho: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Memória livre: ${am.availMem / (1024 * 1024)} MB de ${am.totalMem / (1024 * 1024)} MB · Espaço livre: $livre MB")
            appendLine("Internet medida: " + String.format(java.util.Locale("pt", "BR"), "%.1f MB/s", velocidadeMBs))
            appendLine("Vídeos abertos: ${r.vidoesAbertos} · Abertura média: ${seg(r.aberturaMediaMs)} · Pior: ${seg(r.aberturaPiorMs)}")
            appendLine("Travadas: ${r.travadas} · Duração média: ${seg(r.travadaMediaMs)}")
            if (r.erros.isEmpty()) appendLine("Erros: nenhum") else appendLine("Erros: " + r.erros.joinToString(" · ") { "${it.first} ×${it.second}" })
        }.trim()
    }
}
