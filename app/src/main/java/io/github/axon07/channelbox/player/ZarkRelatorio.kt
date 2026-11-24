package io.github.axon07.channelbox.player

import android.os.Build
import io.github.axon07.channelbox.BuildConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ZarkTV: relatório de erros para o painel (ZarkTV Cloud → Erros dos aparelhos).
 * Leve: guarda na memória, manda em pacotes (a cada 5 erros ou 1 minuto) e no máximo 50 a cada 10 min.
 * Manda só: usuário, servidor, nome do canal/filme, tipo do erro e o aparelho. NUNCA senha nem endereço.
 * Se o painel não responder, descarta (não insiste, não pesa).
 */
object ZarkRelatorio {
    private const val URL = "https://painel.zarktvqx.cfd/checkout/api/erros"
    private val fila = ArrayList<JsonObject>()
    private var primeiroNaFila = 0L
    private var janela = 0L
    private var enviadosNaJanela = 0

    /** O app diz quem está assistindo o quê (preenchido pelo TvViewModel). */
    @Volatile var contexto: () -> Map<String, String?> = { emptyMap() }

    private val http by lazy {
        OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
    }

    private val aparelho by lazy {
        buildJsonObject {
            put("marca", Build.MANUFACTURER.orEmpty())
            put("modelo", Build.MODEL.orEmpty())
            put("android", Build.VERSION.SDK_INT)
            put("app", BuildConfig.VERSION_NAME)
            put("versao", BuildConfig.APARELHO) // tv, mobile ou auto
        }
    }

    /** Um erro aconteceu (tipo já sem endereço). */
    fun registrar(tipo: String, detalhe: String = "") = runCatching {
        val agora = System.currentTimeMillis()
        val ctx = runCatching { contexto() }.getOrDefault(emptyMap())
        val ev = buildJsonObject {
            put("quando", agora)
            put("tipo", tipo.take(80))
            if (detalhe.isNotBlank()) put("detalhe", detalhe.take(120))
            ctx.forEach { (k, v) -> if (!v.isNullOrBlank()) put(k, v.take(80)) }
            put("leve", io.github.axon07.channelbox.ui.ZarkLeve.ativo)
        }
        val enviar: List<JsonObject>
        synchronized(this) {
            if (agora - janela > 10 * 60_000L) { janela = agora; enviadosNaJanela = 0 }
            if (enviadosNaJanela >= 50) return@runCatching
            enviadosNaJanela++
            if (fila.isEmpty()) primeiroNaFila = agora
            fila.add(ev)
            if (fila.size < 5 && agora - primeiroNaFila < 60_000L) return@runCatching
            enviar = ArrayList(fila); fila.clear()
        }
        mandar(enviar)
    }

    private fun mandar(eventos: List<JsonObject>) {
        Thread {
            runCatching {
                val corpo = buildJsonObject {
                    put("aparelho", aparelho)
                    put("eventos", buildJsonArray { eventos.forEach { add(it) } })
                }.toString()
                http.newCall(
                    Request.Builder().url(URL).header("User-Agent", "ZarkTV/${BuildConfig.VERSION_NAME}")
                        .post(corpo.toRequestBody("application/json".toMediaType())).build()
                ).execute().close()
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
