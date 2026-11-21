package io.github.axon07.channelbox.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
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

    /**
     * Servidor automático: testa usuário e senha em todos os servidores do painel ao mesmo tempo
     * e fica com o primeiro (na ordem do painel) que aceitar.
     */
    suspend fun loginAuto(http: OkHttpClient, servers: List<Server>, user: String, pass: String): Pair<Server, Login> =
        kotlinx.coroutines.coroutineScope {
            // Testa todos ao mesmo tempo e fica com o PRIMEIRO que aceitar (o mais rápido), não com o primeiro da lista.
            val canal = kotlinx.coroutines.channels.Channel<Pair<Server, Login>>(servers.size)
            val jobs = servers.map { s -> launch { canal.send(s to runCatching { login(http, s, user, pass) }.getOrDefault(Login.SemResposta)) } }
            var resposta: Login = Login.SemResposta
            repeat(servers.size) {
                val (s, r) = canal.receive()
                if (r is Login.Ok) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope s to r
                }
                if (r == Login.SenhaErrada) resposta = r
            }
            // Ninguém respondeu a tempo: tenta mais uma vez, um por um (servidor lento ou ocupado).
            if (resposta == Login.SemResposta) {
                for (s in servers) {
                    val r = login(http, s, user, pass)
                    if (r is Login.Ok) return@coroutineScope s to r
                    if (r == Login.SenhaErrada) resposta = r
                }
            }
            (servers.firstOrNull() ?: PADRAO.first()) to resposta
        }

    // "Lembrar de mim": guarda usuário e senha para preencher o login da próxima vez.
    fun lembrar(context: Context, user: String?, pass: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (user == null) remove("lembrar_user").remove("lembrar_pass").putBoolean("lembrar", false)
            else putString("lembrar_user", user).putString("lembrar_pass", pass).putBoolean("lembrar", true)
        }.apply()
    }

    fun lembrado(context: Context): Triple<Boolean, String, String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(p.getBoolean("lembrar", true), p.getString("lembrar_user", "").orEmpty(), p.getString("lembrar_pass", "").orEmpty())
    }

    // Player de filmes e séries: interno (padrão) ou externo (VLC, MX Player…).
    fun playerExterno(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("player_externo", false)

    fun setPlayerExterno(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("player_externo", on).apply()

    /** Abre o link num player instalado no aparelho (VLC, MX Player, Just Player…). */
    fun abrirExterno(context: Context, url: String, titulo: String): Boolean = runCatching {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(url), "video/*")
            .putExtra("title", titulo)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(android.content.Intent.createChooser(i, "Abrir com…").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Dias que faltam até [expira] (0 = vence hoje, negativo = vencido). */
    fun diasRestantes(expira: Long): Int {
        val dia = 24L * 60 * 60 * 1000
        return (expira - System.currentTimeMillis()).floorDiv(dia).toInt()
    }
}

/** Ficha de um filme ou série (do get_vod_info / get_series_info do servidor). */
data class ZarkInfo(
    val sinopse: String?,
    val elenco: String?,
    val diretor: String?,
    val genero: String?,
    val data: String?,
    val nota: String?,
    val duracao: String?,
    val fundo: String?,
    val capa: String?,
)

object ZarkFicha {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ZarkInfo>()

    private fun JsonObject.txt(vararg k: String): String? =
        k.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() && it != "null" } }

    private fun JsonObject.fundo(): String? = when (val b = this["backdrop_path"]) {
        is JsonArray -> (b.firstOrNull() as? JsonPrimitive)?.content
        is JsonPrimitive -> b.content
        else -> null
    }?.takeIf { it.startsWith("http") }

    /** [login] do servidor; [movieId] para filme ou [seriesId] para série. */
    suspend fun buscar(http: OkHttpClient, login: XtreamClient.Login, movieId: String?, seriesId: String?): ZarkInfo? =
        withContext(Dispatchers.IO) {
            val chave = "${login.base}|${movieId ?: ""}|${seriesId ?: ""}"
            cache[chave]?.let { return@withContext it }
            val url = if (movieId != null) login.api("get_vod_info", "&vod_id=$movieId") else login.api("get_series_info", "&series_id=$seriesId")
            val txt = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull() ?: return@withContext null
            val raiz = runCatching { Json.parseToJsonElement(txt) as? JsonObject }.getOrNull() ?: return@withContext null
            val i = raiz["info"] as? JsonObject ?: return@withContext null
            ZarkInfo(
                sinopse = i.txt("plot", "description"),
                elenco = i.txt("cast", "actors"),
                diretor = i.txt("director"),
                genero = i.txt("genre"),
                data = i.txt("releasedate", "releaseDate", "release_date"),
                nota = i.txt("rating", "rating_5based"),
                duracao = i.txt("duration"),
                fundo = i.fundo(),
                capa = i.txt("movie_image", "cover", "cover_big"),
            ).also { cache[chave] = it }
        }
}

/**
 * O que o painel manda junto no config.json, além dos servidores:
 *   "tema": "vermelho" | "preto" | "roxo" | "azul"   (tema padrão do app)
 *   "banners": [{"imagem": "https://…", "link": "https://…", "titulo": "…"}]
 *   "aviso": "texto que aparece para todos os clientes"
 *   "checkout": "https://painel.zarktvqx.cfd/checkout"
 */
data class ZarkBanner(val imagem: String, val link: String?, val titulo: String?)
data class ZarkExtras(
    val tema: String?,
    val banners: List<ZarkBanner>,
    val aviso: String?,
    /** "unitv", "leve" ou "auto" (painel). */
    val layout: String? = null,
    /** Logo e imagem de fundo trocados pelo painel (URLs). */
    val logo: String? = null,
    val fundo: String? = null,
    /** Recado para um cliente só: usuário → texto (aparece no sininho dele). */
    val recados: Map<String, String> = emptyMap(),
)

object ZarkPainel {
    fun extras(context: Context): ZarkExtras {
        val txt = context.getSharedPreferences("zarktv", Context.MODE_PRIVATE).getString("config_json", null)
            ?: return ZarkExtras(null, emptyList(), null)
        val o = runCatching { Json.parseToJsonElement(txt) as? JsonObject }.getOrNull() ?: return ZarkExtras(null, emptyList(), null)
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val banners = (o["banners"] as? JsonArray).orEmpty().mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.content.takeIf { it.startsWith("http") }?.let { ZarkBanner(it, null, null) }
                is JsonObject -> (e.s("imagem") ?: e.s("image") ?: e.s("url"))?.takeIf { it.startsWith("http") }
                    ?.let { ZarkBanner(it, e.s("link"), e.s("titulo") ?: e.s("title")) }
                else -> null
            }
        }
        val recados = (o["recados"] as? JsonObject)?.mapNotNull { (u, v) ->
            (v as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }?.let { u.trim().lowercase() to it }
        }?.toMap().orEmpty()
        return ZarkExtras(
            o.s("tema"), banners, o.s("aviso") ?: o.s("mensagem"),
            layout = o.s("layout"),
            logo = o.s("logo")?.takeIf { it.startsWith("http") },
            fundo = o.s("fundo")?.takeIf { it.startsWith("http") },
            recados = recados,
        )
    }
}


/** Preferências do cliente no ZarkTV (favoritos de filmes/séries, idioma, resolução, séries seguidas). */
object ZarkPrefs {
    private fun p(c: Context) = c.getSharedPreferences("zarktv", Context.MODE_PRIVATE)

    /** Conteúdo adulto: escondido até o cliente liberar com a senha no Perfil. */
    val ADULTO = Regex("""adult|\bxxx\b|\+ ?18|18 ?\+|er[oó]tic|playboy|sexy|\bhot\b|porn|sexo|privê|prive""", RegexOption.IGNORE_CASE)
    fun adulto(c: Context) = p(c).getBoolean("adulto_liberado", false)
    fun setAdulto(c: Context, on: Boolean) = p(c).edit().putBoolean("adulto_liberado", on).apply()

    fun favoritos(c: Context): Set<String> = p(c).getStringSet("vod_fav", emptySet()).orEmpty()
    fun ehFavorito(c: Context, id: String) = id in favoritos(c)
    fun alternarFavorito(c: Context, id: String): Boolean {
        val s = favoritos(c).toMutableSet()
        val on = if (id in s) { s.remove(id); false } else { s.add(id); true }
        p(c).edit().putStringSet("vod_fav", s).apply()
        return on
    }

    /** "auto" (do arquivo), "pt" (dublado) ou "leg" (original legendado). */
    fun idioma(c: Context) = p(c).getString("idioma", "auto") ?: "auto"
    fun proximoIdioma(c: Context): String {
        val n = when (idioma(c)) { "auto" -> "pt"; "pt" -> "leg"; else -> "auto" }
        p(c).edit().putString("idioma", n).apply()
        return n
    }
    fun nomeIdioma(v: String) = when (v) { "pt" -> "Dublado"; "leg" -> "Legendado"; else -> "Automático" }

    /** 0 = não mexer, -1 = melhor, ou altura máxima (1080, 720, 480). */
    fun resolucao(c: Context) = p(c).getInt("resolucao", 0)
    fun proximaResolucao(c: Context): Int {
        val n = when (resolucao(c)) { 0 -> -1; -1 -> 1080; 1080 -> 720; 720 -> 480; else -> 0 }
        p(c).edit().putInt("resolucao", n).apply()
        return n
    }
    fun nomeResolucao(v: Int) = when (v) { 0 -> "Automática"; -1 -> "Máxima"; else -> "Até ${v}p" }

    /** Séries seguidas: id -> quantidade de episódios na última conferida. */
    fun seguidas(c: Context): Map<String, Int> = p(c).getStringSet("seguidas", emptySet()).orEmpty()
        .mapNotNull { e -> e.split("|").takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }.toMap()
    fun segue(c: Context, id: String) = seguidas(c).containsKey(id)
    fun seguir(c: Context, id: String, episodios: Int) {
        val m = seguidas(c).toMutableMap(); m[id] = episodios
        p(c).edit().putStringSet("seguidas", m.map { "${it.key}|${it.value}" }.toSet()).apply()
    }
    fun alternarSeguir(c: Context, id: String, episodios: Int): Boolean {
        val m = seguidas(c).toMutableMap()
        val on = if (m.containsKey(id)) { m.remove(id); false } else { m[id] = episodios; true }
        p(c).edit().putStringSet("seguidas", m.map { "${it.key}|${it.value}" }.toSet()).apply()
        return on
    }
}
