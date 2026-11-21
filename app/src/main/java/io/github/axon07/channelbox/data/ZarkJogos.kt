package io.github.axon07.channelbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Guia de jogos de futebol do dia (placar público da ESPN, sem chave). */
object ZarkJogos {
    /** Ligas consultadas, na ordem em que aparecem. */
    val LIGAS = listOf(
        "bra.1", "bra.2", "conmebol.libertadores", "conmebol.sudamericana", "bra.copa_do_brazil",
        "uefa.champions", "eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "por.1", "arg.1", "uefa.europa", "fifa.world",
    )

    data class Time(val nome: String, val logo: String?, val placar: String?)
    data class Jogo(
        val liga: String, val ligaLogo: String?, val inicio: Long,
        val estado: String, // pre | in | post
        val detalhe: String,
        val casa: Time, val fora: Time, val canais: List<String>,
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.o(k: String) = this[k] as? JsonObject
    private fun JsonObject.a(k: String) = (this[k] as? JsonArray).orEmpty()

    suspend fun hoje(http: OkHttpClient): List<Jogo> = withContext(Dispatchers.IO) {
        val rapido = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
        coroutineScope {
            LIGAS.map { lg ->
                async {
                    runCatching {
                        val txt = rapido.newCall(
                            Request.Builder().url("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/scoreboard?lang=pt&region=br").build()
                        ).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching emptyList<Jogo>()
                        val raiz = Json.parseToJsonElement(txt) as JsonObject
                        val liga = raiz.a("leagues").firstOrNull() as? JsonObject
                        val nomeLiga = liga?.s("name") ?: lg
                        val logoLiga = (liga?.a("logos")?.firstOrNull() as? JsonObject)?.s("href")
                        raiz.a("events").mapNotNull { ev ->
                            val e = ev as? JsonObject ?: return@mapNotNull null
                            val comp = e.a("competitions").firstOrNull() as? JsonObject ?: return@mapNotNull null
                            val tipo = e.o("status")?.o("type")
                            val times = comp.a("competitors").mapNotNull { it as? JsonObject }
                            fun time(lado: String): Time? = times.firstOrNull { it.s("homeAway") == lado }?.let { c ->
                                val t = c.o("team")
                                Time(t?.s("shortDisplayName") ?: t?.s("displayName") ?: "?", t?.s("logo"), c.s("score"))
                            }
                            // Android 5 não tem java.time: lê a data no formato da ESPN ("2026-11-21T20:00Z").
                            val inicio = runCatching {
                                val d = e.s("date").orEmpty().removeSuffix("Z").take(16)
                                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
                                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)!!.time
                            }.getOrDefault(0L)
                            Jogo(
                                liga = nomeLiga, ligaLogo = logoLiga, inicio = inicio,
                                estado = tipo?.s("state") ?: "pre",
                                detalhe = tipo?.s("shortDetail") ?: "",
                                casa = time("home") ?: return@mapNotNull null,
                                fora = time("away") ?: return@mapNotNull null,
                                canais = comp.a("broadcasts").flatMap { (it as? JsonObject)?.a("names").orEmpty() }.mapNotNull { (it as? JsonPrimitive)?.content },
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }.sortedWith(compareBy<Jogo>({ when (it.estado) { "in" -> 0; "pre" -> 1; else -> 2 } }, { it.inicio }))
    }
}
