package io.github.axon07.channelbox.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ZarkTV: lista de servidores (cada um com vários DNS) vinda do mesmo config.json do webplay,
 * teste de login com troca automática de DNS e leitura do vencimento da conta.
 */
object ZarkConfig {
    /** Mesmo arquivo que o webplay usa: uma fonte só para web e app. */
    const val CONFIG_URL = "https://tv.zarktvqx.cfd/config.json"
    const val WHATSAPP = "https://wa.me/558587890473"

    data class Server(val name: String, val dns: List<String>)

    /** Usada se o config.json não abrir e não houver cópia salva. */
    val PADRAO = listOf(
        Server("Club", listOf("http://roncb.sytes.net")),
        Server("Uniplay", listOf("http://zarktvqx.top")),
        Server("Five", listOf("http://imagenshd.top")),
        Server("Live", listOf("http://atomm.top")),
    )

    private const val PREFS = "zarktv"
    private const val KEY_CONFIG = "config_json"

    private fun quick(http: OkHttpClient) = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun get(http: OkHttpClient, url: String): String? = runCatching {
        quick(http).newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    /** Servidores do painel; se a internet falhar usa a última cópia boa, senão a lista padrão. */
    suspend fun servers(context: Context, http: OkHttpClient): List<Server> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = get(http, CONFIG_URL)?.let { txt -> parse(txt).takeIf { it.isNotEmpty() }?.also { prefs.edit().putString(KEY_CONFIG, txt).apply() } }
        fresh ?: prefs.getString(KEY_CONFIG, null)?.let { parse(it).takeIf { l -> l.isNotEmpty() } } ?: PADRAO
    }

    /** Lista salva (sem internet), para a tela abrir na hora. */
    fun cached(context: Context): List<Server> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)
            ?.let { parse(it).takeIf { l -> l.isNotEmpty() } } ?: PADRAO

    /**
     * Link do checkout do painel (campo "checkout" ou "pagamento" no config.json), com o usuário
     * e o servidor do cliente. Sem esse campo, cai no WhatsApp do suporte.
     */
    fun checkoutUrl(context: Context, user: String, server: String): String {
        val txt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)
        val base = txt?.let {
            runCatching {
                val o = Json.parseToJsonElement(it) as? JsonObject
                listOf("checkout", "pagamento", "checkout_url").firstNotNullOfOrNull { k -> (o?.get(k) as? JsonPrimitive)?.content?.takeIf { s -> s.startsWith("http") } }
            }.getOrNull()
        } ?: return WHATSAPP + "?text=" + URLEncoder.encode("Olá! Quero renovar meu ZarkTV. Usuário: $user", "UTF-8")
        val sep = if ('?' in base) "&" else "?"
        return base + sep + "usuario=" + URLEncoder.encode(user, "UTF-8") + "&servidor=" + URLEncoder.encode(server, "UTF-8")
    }

    /**
     * Aceita os formatos mais comuns: {"servers":[{"name":"Club","dns":["http://a","http://b"]}]},
     * "servidores"/"nome", um DNS só em texto, ou um mapa {"Club":"http://a"}.
     * Servidores com "enabled"/"ativo" = false ficam de fora.
     */
    fun parse(text: String): List<Server> = runCatching {
        val root = Json.parseToJsonElement(text)
        val list: JsonElement? = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("servers", "servidores", "server", "servidor").firstNotNullOfOrNull { root[it] as? JsonArray }
                ?: root
            else -> null
        }
        when (list) {
            is JsonArray -> list.mapNotNull { (it as? JsonObject)?.let(::server) }
            is JsonObject -> list.entries.mapNotNull { (k, v) ->
                urls(v).takeIf { it.isNotEmpty() }?.let { Server(k, it) }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun server(o: JsonObject): Server? {
        val off = listOf("enabled", "ativo", "active").any { k -> (o[k] as? JsonPrimitive)?.content?.lowercase() in setOf("false", "0", "no", "nao", "não") }
        if (off) return null
        val name = listOf("name", "nome", "title", "label").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            ?: return null
        val dns = listOf("dns", "dnss", "urls", "hosts", "url", "host", "server", "servidor")
            .flatMap { o[it]?.let(::urls).orEmpty() }.distinct()
        return dns.takeIf { it.isNotEmpty() }?.let { Server(name.trim(), it) }
    }

    private fun urls(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> listOfNotNull(e.content.trim().takeIf { it.startsWith("http", ignoreCase = true) || it.contains('.') })
        is JsonArray -> e.flatMap { el ->
            if (el is JsonObject) listOf("url", "dns", "host").firstNotNullOfOrNull { el[it]?.let(::urls) }.orEmpty() else urls(el)
        }
        else -> emptyList()
    }.map { it.trimEnd('/') }

    sealed interface Login {
        /** Entrou: [dns] é o que respondeu; [expira] em milissegundos (null = sem vencimento). */
        data class Ok(val dns: String, val expira: Long?, val status: String) : Login
        object SenhaErrada : Login
        object SemResposta : Login
    }

    /** Testa usuário e senha em cada DNS do servidor, na ordem, até um responder. */
    suspend fun login(http: OkHttpClient, server: Server, user: String, pass: String): Login = withContext(Dispatchers.IO) {
        var wrong = false
        for (dns in server.dns) {
            val base = XtreamClient.Login(dns, user, pass).base
            val u = URLEncoder.encode(user.trim(), "UTF-8")
            val p = URLEncoder.encode(pass.trim(), "UTF-8")
            val txt = get(http, "$base/player_api.php?username=$u&password=$p") ?: continue
            val info = runCatching { (Json.parseToJsonElement(txt) as? JsonObject)?.get("user_info") as? JsonObject }.getOrNull()
            val auth = (info?.get("auth") as? JsonPrimitive)?.content
            if (info == null || auth == "0") {
                wrong = true
                continue
            }
            val exp = (info["exp_date"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
            val status = (info["status"] as? JsonPrimitive)?.content.orEmpty()
            return@withContext Login.Ok(base, exp, status)
        }
        if (wrong) Login.SenhaErrada else Login.SemResposta
    }

    /** Dias que faltam até [expira] (0 = vence hoje, negativo = vencido). */
    fun diasRestantes(expira: Long): Int {
        val dia = 24L * 60 * 60 * 1000
        return (expira - System.currentTimeMillis()).floorDiv(dia).toInt()
    }
}
