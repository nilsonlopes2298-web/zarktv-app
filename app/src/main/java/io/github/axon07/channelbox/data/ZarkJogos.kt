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
        val id: String = "",
        val ligaSlug: String = "",
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
                                id = e.s("id").orEmpty(),
                                ligaSlug = lg,
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


/** Detalhes do jogo: lances, escalação, estatísticas e classificação (resumo público da ESPN). */
object ZarkJogoDetalhes {
    data class Lance(val minuto: String, val tipo: String, val jogador: String, val casa: Boolean)
    data class Jogador(val numero: String, val nome: String, val titular: Boolean, val posicao: String)
    data class Escalacao(val esquema: String, val jogadores: List<Jogador>)
    data class Estat(val nome: String, val casa: String, val fora: String)
    data class Linha(val pos: Int, val time: String, val logo: String?, val j: String, val v: String, val e: String, val d: String, val sg: String, val pts: String)
    data class Detalhes(
        val lances: List<Lance>,
        val casa: Escalacao?, val fora: Escalacao?,
        val estat: List<Estat>,
        val tabela: List<Linha>,
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.o(k: String) = this[k] as? JsonObject
    private fun JsonObject.a(k: String) = (this[k] as? JsonArray).orEmpty()

    private val NOMES = mapOf(
        "possessionPct" to "Posse de bola (%)", "totalShots" to "Finalizações", "shotsOnTarget" to "Chutes no gol",
        "wonCorners" to "Escanteios", "foulsCommitted" to "Faltas", "yellowCards" to "Cartões amarelos",
        "redCards" to "Cartões vermelhos", "offsides" to "Impedimentos", "saves" to "Defesas", "accuratePasses" to "Passes certos",
        "totalPasses" to "Passes", "effectiveTackles" to "Desarmes",
    )

    suspend fun buscar(http: OkHttpClient, jogo: ZarkJogos.Jogo): Detalhes? = withContext(Dispatchers.IO) {
        if (jogo.id.isBlank() || jogo.ligaSlug.isBlank()) return@withContext null
        val txt = runCatching {
            http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(
                Request.Builder().url("https://site.api.espn.com/apis/site/v2/sports/soccer/${jogo.ligaSlug}/summary?event=${jogo.id}&lang=pt&region=br").build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        val r = runCatching { Json.parseToJsonElement(txt) as JsonObject }.getOrNull() ?: return@withContext null

        // Time da casa pelo id (para separar os lances e a escalação).
        val comp = (r.o("header")?.a("competitions")?.firstOrNull() as? JsonObject)
        val casaId = comp?.a("competitors")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.s("homeAway") == "home" }?.let { it.s("id") ?: it.o("team")?.s("id") }

        val lances = r.a("keyEvents").mapNotNull { it as? JsonObject }.mapNotNull { e ->
            val tipoEn = e.o("type")?.s("text") ?: e.o("type")?.s("type") ?: return@mapNotNull null
            val tipo = when {
                tipoEn.contains("goal", true) -> "Gol"
                tipoEn.contains("red", true) -> "Cartão vermelho"
                tipoEn.contains("yellow", true) -> "Cartão amarelo"
                tipoEn.contains("substitution", true) -> "Substituição"
                else -> return@mapNotNull null
            }
            val jogador = e.a("participants").mapNotNull { (it as? JsonObject)?.o("athlete")?.s("displayName") }.joinToString(" ↔ ")
                .ifBlank { e.s("text").orEmpty() }
            Lance(e.o("clock")?.s("displayValue").orEmpty(), tipo, jogador, e.o("team")?.s("id") == casaId)
        }

        fun escal(o: JsonObject) = Escalacao(
            o.s("formation").orEmpty(),
            o.a("roster").mapNotNull { it as? JsonObject }.map { p ->
                Jogador(
                    p.s("jersey").orEmpty(),
                    p.o("athlete")?.let { it.s("shortName") ?: it.s("displayName") }.orEmpty(),
                    p.s("starter") == "true",
                    p.o("position")?.s("abbreviation").orEmpty(),
                )
            },
        )
        val rosters = r.a("rosters").mapNotNull { it as? JsonObject }
        val casaR = rosters.firstOrNull { it.s("homeAway") == "home" }?.let(::escal)
        val foraR = rosters.firstOrNull { it.s("homeAway") == "away" }?.let(::escal)

        val times = r.o("boxscore")?.a("teams")?.mapNotNull { it as? JsonObject }.orEmpty()
        val casaT = times.firstOrNull { it.s("homeAway") == "home" } ?: times.getOrNull(1)
        val foraT = times.firstOrNull { it.s("homeAway") == "away" } ?: times.getOrNull(0)
        val estat = casaT?.a("statistics")?.mapNotNull { it as? JsonObject }?.mapNotNull { st ->
            val nome = st.s("name") ?: return@mapNotNull null
            val rot = NOMES[nome] ?: return@mapNotNull null
            val f = foraT?.a("statistics")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.s("name") == nome }?.s("displayValue") ?: "-"
            Estat(rot, st.s("displayValue") ?: "-", f)
        }.orEmpty()

        val entradas = r.o("standings")?.a("groups")?.mapNotNull { it as? JsonObject }
            ?.flatMap { g -> (g.o("standings")?.a("entries") ?: g.a("entries")).mapNotNull { it as? JsonObject } }.orEmpty()
        val tabela = entradas.mapIndexed { i, en ->
            fun st(vararg k: String) = en.a("stats").mapNotNull { it as? JsonObject }
                .firstOrNull { x -> k.any { it == x.s("name") || it == x.s("abbreviation") || it == x.s("type") } }?.s("displayValue") ?: "-"
            val t = en["team"]
            val nome = (t as? JsonObject)?.s("displayName") ?: (t as? JsonPrimitive)?.content ?: "?"
            val logo = (t as? JsonObject)?.let { it.a("logos").firstOrNull() as? JsonObject }?.s("href") ?: en.s("logo")
            Linha(
                st("rank").toIntOrNull() ?: (i + 1), nome, logo,
                st("gamesPlayed", "GP"), st("wins", "W"), st("ties", "D", "T"), st("losses", "L"),
                st("pointDifferential", "GD"), st("points", "P"),
            )
        }
        Detalhes(lances, casaR, foraR, estat, tabela)
    }
}

/** Lembretes de jogos: guarda os jogos marcados ("id|início|título") e avisa quando começam. */
object ZarkLembretes {
    private const val PREFS = "zarktv"
    data class Lembrete(val id: String, val inicio: Long, val titulo: String)

    private fun set(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getStringSet("lembretes", emptySet()).orEmpty()

    fun lista(ctx: android.content.Context): List<Lembrete> = set(ctx).mapNotNull { e ->
        val p = e.split("|", limit = 3)
        if (p.size < 2) null else Lembrete(p[0], p[1].toLongOrNull() ?: 0L, p.getOrNull(2).orEmpty())
    }

    fun tem(ctx: android.content.Context, id: String) = set(ctx).any { it.startsWith("$id|") }

    /** Liga/desliga o lembrete; devolve true se ficou ligado. */
    fun alternar(ctx: android.content.Context, id: String, inicio: Long, titulo: String): Boolean {
        val atual = set(ctx).toMutableSet()
        val tinha = atual.removeAll { it.startsWith("$id|") }
        if (!tinha) atual += "$id|$inicio|$titulo"
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putStringSet("lembretes", atual).apply()
        return !tinha
    }

    fun remover(ctx: android.content.Context, id: String) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putStringSet("lembretes", set(ctx).filterNot { it.startsWith("$id|") }.toSet()).apply()
    }
}


/** Programação (EPG) direto do servidor Xtream, quando o guia XMLTV não tem o canal. */
object ZarkEpg {
    data class Prog(val titulo: String, val inicio: Long, val fim: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Prog>>>()

    suspend fun curto(http: OkHttpClient, streamUrl: String): List<Prog> = withContext(Dispatchers.IO) {
        cache[streamUrl]?.let { (quando, l) -> if (System.currentTimeMillis() - quando < 10 * 60_000) return@withContext l }
        val par = XtreamClient.Login.fromLiveUrl(streamUrl) ?: return@withContext emptyList()
        val (login, id) = par
        val txt = runCatching {
            http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build().newCall(
                Request.Builder().url(login.api("get_short_epg", "&stream_id=$id&limit=12")).build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        fun b64(t: String?) = runCatching { String(android.util.Base64.decode(t ?: "", android.util.Base64.DEFAULT)) }.getOrDefault(t.orEmpty())
        val lista = runCatching {
            (Json.parseToJsonElement(txt) as JsonObject)["epg_listings"].let { it as? JsonArray }.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content
                val ini = s("start_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
                val fim = s("stop_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
                Prog(b64(s("title")).trim(), ini, fim)
            }
        }.getOrDefault(emptyList())
        cache[streamUrl] = System.currentTimeMillis() to lista
        lista
    }
}
