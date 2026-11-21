package io.github.axon07.channelbox.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.ConnectException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Login details for a Stalker / Ministra portal (the MAG set-top box "portal URL + MAC" kind). */
data class StalkerAccount(
    val portalUrl: String,
    val mac: String,
    /** Only for portals that ask for a login on top of the MAC. */
    val login: String = "",
    val password: String = "",
    val serial: String = "",
    val deviceId1: String = "",
    val deviceId2: String = "",
)

/** Something the portal said no to, in words the user can act on. */
class StalkerException(message: String) : IOException(message)

/**
 * Client for Stalker / Ministra middleware portals ("load.php" / "portal.php"), as used by MAG
 * set-top boxes and by IPTV services that hand out a portal URL and a MAC address.
 *
 * It presents itself as a MAG250, the same way the real box and other players (Kodi's
 * pvr.stalker, StbEmu, IPTVnator) do:
 *  - finds the API behind the address the user typed (…/c/ → server/load.php, portal.php …);
 *  - handshake → token, get_profile (and do_auth when the portal also wants a login);
 *  - all channels (get_all_channels, or page by page when a portal doesn't have it), the TV
 *    guide, movies and shows;
 *  - play links: a channel's own link when the portal says it's final, otherwise a fresh one from
 *    create_link right before playing (those links only live a few seconds);
 *  - a token the portal stops accepting ("Authorization failed.") is renewed once, then the
 *    request is repeated.
 *
 * Calls block: use them off the main thread.
 */
class StalkerClient(
    private val http: OkHttpClient,
    val account: StalkerAccount,
    private val timeZone: String = defaultTimeZone(),
) {
    val mac: String = normalizeMac(account.mac)

    /** The API address that answered (…/server/load.php or …/portal.php). */
    @Volatile var endpoint: String? = null
        private set

    @Volatile private var token: String? = null
    @Volatile private var random: String = randomHex()

    /** The portal wants a keep-alive this often (seconds). */
    @Volatile var watchdogSeconds: Int = 120
        private set

    private val lock = Any()

    // ---- Connecting -----------------------------------------------------------------------------

    /** Finds the portal's API and signs in, unless that's already done. */
    fun connect() {
        synchronized(lock) {
            if (endpoint != null) return
            var lastError: IOException? = null
            for (candidate in candidates(account.portalUrl)) {
                try {
                    if (tryEndpoint(candidate)) {
                        endpoint = candidate
                        return
                    }
                } catch (e: StalkerException) {
                    throw e // the portal answered and said no: that's the answer
                } catch (e: UnknownHostException) {
                    throw StalkerException("Couldn't find the portal's server – check the portal URL")
                } catch (e: ConnectException) {
                    throw StalkerException("Couldn't connect to the portal – check the portal URL and port")
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw StalkerException(
                "No Stalker portal found at this address" + (lastError?.message?.let { " ($it)" } ?: "") +
                    " – use the portal URL your provider gave you, e.g. http://example.com:8080/c/"
            )
        }
    }

    /** True if [url] is a working portal API; it's then signed in. */
    private fun tryEndpoint(url: String): Boolean {
        val shake = try {
            call(url, handshakeParams(""), withToken = false)
        } catch (e: AuthFailure) {
            null
        } catch (e: HttpStatus) {
            if (e.code == 401 || e.code == 403) null else return false
        } catch (e: NotJson) {
            return false
        }
        val js = (shake as? Map<*, *>)?.get("js") as? Map<*, *>
        val newToken = js?.get("token")?.toString()?.takeIf { it.isNotBlank() }
        if (newToken != null) {
            token = newToken
            js["random"]?.toString()?.takeIf { it.isNotBlank() }?.let { random = it }
            profile(url, notValid = flag(js["not_valid"]))
            return true
        }
        // Some reseller panels need no sign-in at all: the channel groups answer without a token.
        val genres = try {
            call(url, mapOf("type" to "itv", "action" to "get_genres"), withToken = false)
        } catch (e: IOException) {
            return false
        }
        val list = (genres as? Map<*, *>)?.get("js")
        return list is List<*> || (list as? Map<*, *>)?.get("data") is List<*>
    }

    private fun handshakeParams(previous: String) = linkedMapOf(
        "type" to "stb", "action" to "handshake", "token" to previous, "prehash" to prehash(mac),
    )

    private fun profile(url: String, notValid: Boolean, secondStep: Boolean = false) {
        val metrics = buildString {
            append("{\"mac\":\"").append(mac).append('"')
            if (account.serial.isNotBlank()) append(",\"sn\":\"").append(account.serial.trim()).append('"')
            append(",\"model\":\"MAG250\",\"type\":\"STB\",\"uid\":\"\",\"random\":\"").append(random).append("\"}")
        }
        val params = linkedMapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "ver" to MAG_VERSION, "num_banks" to "2", "stb_type" to "MAG250", "client_type" to "STB",
            "image_version" to "218", "video_out" to "hdmi", "hw_version" to "1.7-BD-00",
            "not_valid_token" to if (notValid) "1" else "0",
            "auth_second_step" to if (secondStep) "1" else "0",
            "metrics" to metrics,
            "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "prehash" to prehash(mac),
        )
        if (account.serial.isNotBlank()) params["sn"] = account.serial.trim()
        if (account.deviceId1.isNotBlank()) params["device_id"] = account.deviceId1.trim()
        if (account.deviceId2.isNotBlank()) params["device_id2"] = account.deviceId2.trim()
        val js = (call(url, params, withToken = true) as? Map<*, *>)?.get("js") as? Map<*, *> ?: return
        (js["watchdog_timeout"]?.toString()?.toDoubleOrNull()?.toInt())?.let { watchdogSeconds = it.coerceIn(30, 3600) }
        val text = listOfNotNull(js["msg"], js["block_msg"]).map { stripTags(it.toString()) }
            .filter { it.isNotBlank() }.distinct().joinToString(" – ")
        when (js["status"]?.toString()?.trim()?.toDoubleOrNull()?.toInt()) {
            1 -> throw StalkerException(
                "The portal refused this MAC address" + (if (text.isNotEmpty()) ": $text" else " – check it with your provider")
            )
            2 -> {
                if (secondStep) throw StalkerException("The portal didn't accept the login" + if (text.isNotEmpty()) ": $text" else "")
                if (account.login.isBlank()) {
                    throw StalkerException("This portal also needs a username and password – add them to the playlist")
                }
                val auth = linkedMapOf("type" to "stb", "action" to "do_auth", "login" to account.login.trim(), "password" to account.password)
                if (account.deviceId1.isNotBlank()) auth["device_id"] = account.deviceId1.trim()
                if (account.deviceId2.isNotBlank()) auth["device_id2"] = account.deviceId2.trim()
                val ok = (call(url, auth, withToken = true) as? Map<*, *>)?.get("js")
                if (ok != true && ok?.toString() != "true" && ok?.toString() != "1") {
                    throw StalkerException("The portal didn't accept the username and password" + if (text.isNotEmpty()) ": $text" else "")
                }
                profile(url, notValid = false, secondStep = true)
            }
        }
    }

    /** Signs in again (the token was dropped, e.g. the same MAC was used on another device). */
    private fun reauthenticate(url: String) {
        synchronized(lock) {
            val shake = (call(url, handshakeParams(""), withToken = false) as? Map<*, *>)?.get("js") as? Map<*, *>
            val newToken = shake?.get("token")?.toString()?.takeIf { it.isNotBlank() }
                ?: throw StalkerException("The portal stopped accepting this MAC address")
            token = newToken
            shake["random"]?.toString()?.takeIf { it.isNotBlank() }?.let { random = it }
            profile(url, notValid = flag(shake["not_valid"]))
        }
    }

    // ---- Requests -------------------------------------------------------------------------------

    /** One API request; renews the sign-in once if the portal stopped accepting it. */
    fun request(params: Map<String, String>): Any? {
        connect()
        val url = endpoint!!
        return try {
            call(url, params, withToken = true)
        } catch (e: AuthFailure) {
            reauthenticate(url)
            try {
                call(url, params, withToken = true)
            } catch (again: AuthFailure) {
                throw StalkerException("The portal refused access (${again.message})")
            }
        }
    }

    /** Like [request], but hands the raw JSON to [read] without loading it all (huge guides). */
    fun <T> stream(params: Map<String, String>, read: (JsonReader) -> T): T {
        connect()
        val url = endpoint!!
        return try {
            open(url, params, withToken = true) { body -> read(reader(body)) }
        } catch (e: AuthFailure) {
            reauthenticate(url)
            open(url, params, withToken = true) { body -> read(reader(body)) }
        }
    }

    private fun call(url: String, params: Map<String, String>, withToken: Boolean): Any? =
        open(url, params, withToken) { body -> parseTree(body.readText()) }

    private fun <T> open(url: String, params: Map<String, String>, withToken: Boolean, read: (Reader) -> T): T {
        val request = Request.Builder().url(requestUrl(url, params)).apply {
            apiHeaders(withToken).forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            val body = response.body ?: throw NotJson("empty answer")
            val source = body.charStream().buffered()
            // Peek at the start: real portals answer a refused request with plain text, not JSON.
            source.mark(256)
            val head = CharArray(256)
            var n = 0
            while (n < head.size) {
                val got = source.read(head, n, head.size - n)
                if (got < 0) break
                n += got
            }
            source.reset()
            val start = String(head, 0, n).trimStart('﻿', ' ', '\n', '\r', '\t')
            if (!start.startsWith("{") && !start.startsWith("[")) {
                val text = start.trim()
                if (AUTH_FAILURES.any { text.startsWith(it, ignoreCase = true) }) throw AuthFailure(text.take(80))
                throw NotJson(text.take(60).ifEmpty { "empty answer" })
            }
            return read(source)
        }
    }

    /** Headers a MAG box sends to its portal. */
    private fun apiHeaders(withToken: Boolean): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to MAG_USER_AGENT,
            "X-User-Agent" to MAG_X_USER_AGENT,
            "Cookie" to cookie(),
            "Accept" to "*/*",
            "Referer" to referer(),
        )
        if (account.serial.isNotBlank()) h["SN"] = account.serial.trim()
        val t = token
        if (withToken && t != null) h["Authorization"] = "Bearer $t"
        return h
    }

    private fun cookie() = "mac=$mac; stb_lang=en; timezone=$timeZone"

    private fun referer(): String {
        val base = endpoint ?: candidates(account.portalUrl).first()
        return base.substringBeforeLast('/').removeSuffix("/server") + "/c/"
    }

    // ---- Live TV --------------------------------------------------------------------------------

    data class Channel(
        val id: String,
        val name: String,
        val number: Int?,
        val logo: String?,
        val genreId: String?,
        val cmd: String,
        /** true: needs a fresh link from the portal; false: [cmd] plays as is; null: unknown. */
        val needsLink: Boolean?,
        val xmltvId: String?,
    )

    /** Channel groups: id → name. */
    fun genres(): Map<String, String> {
        val js = (request(mapOf("type" to "itv", "action" to "get_genres")) as? Map<*, *>)?.get("js")
        val list = js as? List<*> ?: (js as? Map<*, *>)?.get("data") as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        list.forEach { g ->
            val m = g as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString()?.trim().orEmpty()
            val title = (m["title"] ?: m["name"])?.toString()?.trim().orEmpty()
            if (id.isNotEmpty() && id != "*" && title.isNotEmpty()) out[id] = title
        }
        return out
    }

    /**
     * Every live channel: all at once with get_all_channels (what the MAG box itself does), or
     * page by page for portals without it.
     */
    fun channels(onProgress: (Int) -> Unit = {}): List<Channel> {
        val all = try {
            val js = (request(mapOf("type" to "itv", "action" to "get_all_channels")) as? Map<*, *>)?.get("js")
            (js as? Map<*, *>)?.get("data") as? List<*> ?: js as? List<*>
        } catch (e: StalkerException) {
            throw e
        } catch (e: IOException) {
            null
        }
        if (!all.isNullOrEmpty()) {
            val out = dedupe(all.mapNotNull { channel(it) })
            onProgress(out.size)
            return out
        }
        return dedupe(crawl("itv", mapOf("genre" to "*", "category" to "*", "sortby" to "number"), onProgress).mapNotNull { channel(it) })
    }

    private fun dedupe(list: List<Channel>): List<Channel> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.id) }
    }

    private fun channel(item: Any?): Channel? {
        val m = item as? Map<*, *> ?: return null
        val id = m["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cmd = (m["cmd"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ((m["cmds"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("url")?.toString()?.trim())
            ?.takeIf { it.isNotEmpty() } ?: return null
        return Channel(
            id = id,
            name = m["name"]?.toString()?.trim()?.ifEmpty { null } ?: "Channel $id",
            number = m["number"]?.toString()?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
            logo = absolute(m["logo"]?.toString()),
            genreId = m["tv_genre_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            cmd = cmd,
            needsLink = linkFlag(m),
            xmltvId = m["xmltv_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The TV guide for every channel at once (get_epg_info), streamed to [onProgramme] as
     * (channel id, start ms, stop ms, title, description). False if the portal doesn't offer it.
     */
    fun guide(hours: Int, onProgramme: (String, Long, Long, String, String?) -> Unit): Boolean = try {
        stream(mapOf("type" to "itv", "action" to "get_epg_info", "period" to hours.toString())) { r ->
            var found = false
            readObject(r) { key ->
                if (key != "js") return@readObject false
                if (r.peek() != JsonToken.BEGIN_OBJECT) return@readObject false
                readObject(r) { jsKey ->
                    if (jsKey != "data") return@readObject false
                    when (r.peek()) {
                        // { "data": { "<channel id>": [ programmes ] } } – the usual shape.
                        JsonToken.BEGIN_OBJECT -> readObject(r) { channelId ->
                            if (r.peek() != JsonToken.BEGIN_ARRAY) return@readObject false
                            r.beginArray()
                            while (r.hasNext()) {
                                programme(readTree(r), channelId)?.let { p -> found = true; onProgramme(p.channel, p.start, p.stop, p.title, p.description) }
                            }
                            r.endArray()
                            true
                        }
                        // [ programmes with "ch_id" ] – some portals.
                        JsonToken.BEGIN_ARRAY -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                programme(readTree(r), null)?.let { p -> found = true; onProgramme(p.channel, p.start, p.stop, p.title, p.description) }
                            }
                            r.endArray()
                        }
                        else -> return@readObject false
                    }
                    true
                }
                true
            }
            found
        }
    } catch (e: StalkerException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** The next few programmes of one channel (for portals without the full guide). */
    fun shortGuide(channelId: String, size: Int = 10): List<Programme> {
        val js = (request(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to channelId, "size" to size.toString())) as? Map<*, *>)?.get("js")
        val list = js as? List<*> ?: (js as? Map<*, *>)?.get("data") as? List<*> ?: return emptyList()
        return list.mapNotNull { programme(it, channelId) }
    }

    data class Programme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String?)

    private fun programme(item: Any?, channelId: String?): Programme? {
        val m = item as? Map<*, *> ?: return null
        val ch = channelId ?: m["ch_id"]?.toString() ?: return null
        val start = seconds(m["start_timestamp"]) ?: time(m["time"]) ?: return null
        val stop = seconds(m["stop_timestamp"]) ?: time(m["time_to"])
            ?: m["duration"]?.toString()?.toLongOrNull()?.let { start + it * 1000 } ?: return null
        if (stop <= start) return null
        val title = m["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Programme(ch, start, stop, title, m["descr"]?.toString()?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun seconds(v: Any?): Long? = v?.toString()?.trim()?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }?.times(1000)

    private fun time(v: Any?): Long? {
        val s = v?.toString()?.trim()?.takeIf { it.length >= 16 } ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { this.timeZone = TimeZone.getTimeZone(this@StalkerClient.timeZone) }
                .parse(if (s.length == 16) "$s:00" else s.take(19))?.time
        }.getOrNull()
    }

    // ---- Movies & shows -------------------------------------------------------------------------

    data class VodItem(
        val id: String,
        val name: String,
        val cmd: String?,
        val poster: String?,
        val description: String?,
        val year: String?,
        val rating: String?,
        /** Epoch seconds, 0 if unknown. */
        val added: Long,
        /** A show (episodes are fetched when it's opened, or listed in [episodes]). */
        val isSeries: Boolean,
        /** Episode numbers listed right in the item (older portals), else empty. */
        val episodes: List<Int>,
        val needsLink: Boolean?,
    )

    /** Movie or show categories ([type] "vod" or "series"): id → name. */
    fun vodCategories(type: String): Map<String, String> {
        val js = (request(mapOf("type" to type, "action" to "get_categories")) as? Map<*, *>)?.get("js")
        val list = js as? List<*> ?: (js as? Map<*, *>)?.get("data") as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        list.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString()?.trim().orEmpty()
            val title = (m["title"] ?: m["name"])?.toString()?.trim().orEmpty()
            if (id.isNotEmpty() && title.isNotEmpty()) out[id] = title
        }
        return out
    }

    /** Every item of one category, page by page. */
    fun vodItems(type: String, categoryId: String, onProgress: (Int) -> Unit = {}): List<VodItem> =
        crawl(type, mapOf("category" to categoryId, "genre" to "*", "sortby" to "added"), onProgress).mapNotNull { vodItem(it) }

    private fun vodItem(item: Any?): VodItem? {
        val m = item as? Map<*, *> ?: return null
        val id = m["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val episodes = (m["series"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.toDoubleOrNull()?.toInt() }.orEmpty()
        return VodItem(
            id = id,
            name = (m["name"] ?: m["o_name"])?.toString()?.trim()?.ifEmpty { null } ?: "Title $id",
            cmd = m["cmd"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            poster = absolute((m["screenshot_uri"] ?: m["cover_big"] ?: m["logo"])?.toString()),
            description = m["description"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            year = m["year"]?.toString()?.trim()?.take(4)?.takeIf { it.isNotEmpty() && it != "0" && it != "N/A" },
            rating = (m["rating_imdb"] ?: m["rating_kinopoisk"])?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "0" && it != "N/A" },
            added = time(m["added"])?.div(1000) ?: 0,
            isSeries = flag(m["is_series"]) || episodes.isNotEmpty(),
            episodes = episodes,
            needsLink = linkFlag(m),
        )
    }

    data class Episode(val season: Int, val episode: Int, val title: String, val cmd: String, val series: Int?)

    /** The episodes of a show ([type] "series", or "vod" for shows listed among the movies). */
    fun episodes(type: String, item: VodItem): List<Episode> {
        // Older portals list the episode numbers right in the show; they share the show's link.
        if (item.episodes.isNotEmpty() && item.cmd != null) {
            return item.episodes.sorted().map { n -> Episode(1, n, "Episode $n", item.cmd, n) }
        }
        val rows = crawl(type, mapOf("movie_id" to item.id, "season_id" to "0", "episode_id" to "0"))
        val out = ArrayList<Episode>()
        rows.forEachIndexed { index, row ->
            val m = row as? Map<*, *> ?: return@forEachIndexed
            val cmd = m["cmd"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            val name = m["name"]?.toString()?.trim().orEmpty()
            val numbers = (m["series"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.toDoubleOrNull()?.toInt() }.orEmpty()
            if (numbers.isNotEmpty()) {
                // A season: its episodes are played with the season's link plus series=<number>.
                val season = m["season_number"]?.toString()?.toIntOrNull() ?: firstNumber(name) ?: (index + 1)
                numbers.sorted().forEach { n -> out += Episode(season, n, "Episode $n", cmd, n) }
            } else {
                // An episode with its own link.
                val n = (m["series_number"] ?: m["episode_number"])?.toString()?.toIntOrNull() ?: firstNumber(name) ?: (index + 1)
                val season = m["season_number"]?.toString()?.toIntOrNull() ?: 1
                out += Episode(season, n, name.ifEmpty { "Episode $n" }, cmd, null)
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun firstNumber(s: String) = Regex("""\d+""").find(s)?.value?.toIntOrNull()

    // ---- Paging ---------------------------------------------------------------------------------

    /**
     * All rows of a get_ordered_list listing. Portals return ~14 rows per page: once the first
     * page says how many there are, the rest are fetched four at a time. Stops at an empty page
     * or one that adds nothing new (some portals ignore the page number).
     */
    private fun crawl(type: String, extra: Map<String, String>, onProgress: (Int) -> Unit = {}): List<Any?> {
        fun page(p: Int): Map<*, *>? =
            (request(mapOf("type" to type, "action" to "get_ordered_list") + extra + ("p" to p.toString())) as? Map<*, *>)
                ?.get("js") as? Map<*, *>
        val first = page(1) ?: return emptyList()
        val rows = ArrayList<Any?>((first["data"] as? List<*>).orEmpty())
        if (rows.isEmpty()) return rows
        val seen = HashSet<String>()
        rows.forEach { (it as? Map<*, *>)?.get("id")?.toString()?.let(seen::add) }
        onProgress(rows.size)
        val total = first["total_items"]?.toString()?.toDoubleOrNull()?.toInt() ?: 0
        val perPage = first["max_page_items"]?.toString()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: rows.size
        val pages = if (total > 0) ((total + perPage - 1) / perPage).coerceAtMost(MAX_PAGES) else MAX_PAGES
        var next = 2
        val pool = Executors.newFixedThreadPool(PAGE_THREADS)
        try {
            while (next <= pages) {
                val batch = (next until (next + PAGE_THREADS).coerceAtMost(pages + 1)).toList()
                next += batch.size
                val results = pool.invokeAll(batch.map { p -> Callable { runCatching { page(p) }.recoverCatching { page(p) }.getOrNull() } })
                var added = 0
                var empty = false
                for (f in results) {
                    val data = (f.get()?.get("data") as? List<*>).orEmpty()
                    if (data.isEmpty()) {
                        empty = true
                        continue
                    }
                    data.forEach { row ->
                        val id = (row as? Map<*, *>)?.get("id")?.toString()
                        if (id == null || seen.add(id)) {
                            rows += row
                            added++
                        }
                    }
                }
                onProgress(rows.size)
                if (added == 0 || (empty && total <= 0)) break
            }
        } finally {
            pool.shutdownNow()
        }
        return rows
    }

    // ---- Playing --------------------------------------------------------------------------------

    /** A link ready to play, with the headers the stream needs. */
    data class Playable(val url: String, val headers: Map<String, String>)

    /**
     * The link to play [cmd] ([type] "itv" or "vod"). The channel's own link is used when the
     * portal marked it as final; otherwise create_link makes a fresh one (they expire in seconds).
     */
    fun playable(type: String, cmd: String, needsLink: Boolean?, series: Int? = null): Playable {
        val static = if (series == null && needsLink == false) normalizeCommand(cmd).takeIf { isFinalUrl(it) } else null
        val url = static ?: createLink(type, cmd, series)
        return Playable(url, streamHeaders(url))
    }

    fun createLink(type: String, cmd: String, series: Int? = null): String {
        val params = linkedMapOf(
            "type" to type, "action" to "create_link", "cmd" to cmd,
            "series" to (series?.toString() ?: ""), "disable_ad" to "0", "download" to "0",
        )
        val js = (request(params) as? Map<*, *>)?.get("js")
        val m = js as? Map<*, *>
        val error = m?.get("error")?.toString()?.trim().orEmpty()
        val raw = (m?.get("cmd") ?: js?.takeIf { it is String })?.toString()?.trim().orEmpty()
        if (error.isNotEmpty() && error != "0") throw StalkerException(linkError(error))
        val url = resolve(normalizeCommand(raw))
        if (!url.contains("://")) throw StalkerException("The portal gave no link to play" + if (raw.isNotEmpty()) " ($raw)" else "")
        return url
    }

    private fun linkError(code: String) = when (code.lowercase()) {
        "limit" -> "Too many people are watching on this account – stop one and try again"
        "nothing_to_play" -> "The portal has nothing to play for this right now"
        "link_fault" -> "The portal couldn't make a link for this"
        "not_found" -> "The portal no longer has this"
        else -> "The portal couldn't play this ($code)"
    }

    /** Headers for the stream: the portal's own servers get the box's identity, others nothing. */
    fun streamHeaders(url: String): Map<String, String> {
        val portalHost = runCatching { URI(endpoint ?: candidates(account.portalUrl).first()).host }.getOrNull()
        val host = runCatching { URI(url).host }.getOrNull()
        if (portalHost == null || host == null || !host.equals(portalHost, ignoreCase = true)) return emptyMap()
        val h = linkedMapOf("User-Agent" to MAG_USER_AGENT, "X-User-Agent" to MAG_X_USER_AGENT, "Cookie" to cookie(), "Referer" to referer())
        token?.let { h["Authorization"] = "Bearer $it" }
        if (account.serial.isNotBlank()) h["SN"] = account.serial.trim()
        return h
    }

    /** The keep-alive a MAG box sends every couple of minutes while watching. */
    fun watchdog() {
        request(
            mapOf(
                "type" to "watchdog", "action" to "get_events", "init" to "0", "cur_play_type" to "1", "event_active_id" to "0",
            )
        )
    }

    private fun resolve(url: String): String {
        if (url.contains("://")) return url
        val base = endpoint ?: return url
        val origin = runCatching { URI(base).let { "${it.scheme}://${it.rawAuthority}" } }.getOrNull() ?: return url
        return when {
            url.startsWith("/") -> origin + url
            else -> url
        }
    }

    private fun absolute(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (s.contains("://")) return s
        if (!s.startsWith("/")) return null
        return resolve(s)
    }

    companion object {
        const val MAG_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val MAG_X_USER_AGENT = "Model: MAG250; Link: Ethernet"
        private const val MAG_VERSION =
            "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.6.0; " +
                "API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566"
        private val AUTH_FAILURES = listOf("Authorization failed", "Access denied", "Unauthorized request", "Unauthorized")
        private const val PAGE_THREADS = 4
        private const val MAX_PAGES = 2_500

        fun defaultTimeZone(): String = TimeZone.getDefault().id.takeIf { it.contains('/') } ?: "UTC"

        /** "00-1a-79-aa-bb-cc", "001a79aabbcc" … → "00:1A:79:AA:BB:CC". */
        fun normalizeMac(raw: String): String {
            val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return raw.trim().uppercase()
            return hex.chunked(2).joinToString(":")
        }

        fun isValidMac(raw: String): Boolean {
            val hex = raw.filter { it.isLetterOrDigit() }
            return hex.length == 12 && hex.all { it in "0123456789abcdefABCDEF" }
        }

        /**
         * Where the API may be, most likely first: the address itself if it names a .php file,
         * then portal.php and server/load.php next to it (a "…/c/" address is the box's web app;
         * its API is one level up), then the same at the server's root.
         */
        fun candidates(portalUrl: String): List<String> {
            var raw = portalUrl.trim()
            if (!raw.contains("://")) raw = "http://$raw"
            val uri = runCatching { URI(raw) }.getOrNull() ?: return listOf(raw)
            val scheme = (uri.scheme ?: "http").lowercase()
            val origin = "$scheme://${uri.rawAuthority?.lowercase() ?: return listOf(raw)}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            val out = ArrayList<String>()
            if (path.endsWith(".php", ignoreCase = true)) out += origin + path
            var base = path.substringBeforeLast('/').takeIf { path.endsWith(".php", ignoreCase = true) } ?: path
            base = base.trimEnd('/')
            if (base.endsWith("/c", ignoreCase = true)) base = base.dropLast(2)
            if (base.endsWith("/server", ignoreCase = true)) base = base.dropLast(7)
            out += "$origin$base/portal.php"
            out += "$origin$base/server/load.php"
            if (!base.endsWith("/stalker_portal", ignoreCase = true)) out += "$origin$base/stalker_portal/server/load.php"
            out += "$origin/portal.php"
            out += "$origin/server/load.php"
            out += "$origin/stalker_portal/server/load.php"
            return out.distinct()
        }

        /** The request URL: every value encoded, except cmd, which goes out the way a MAG box sends it. */
        fun requestUrl(endpoint: String, params: Map<String, String>): String {
            val query = params.entries.joinToString("&") { (k, v) ->
                "$k=" + if (k == "cmd") encodeCmd(v) else URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            }
            return endpoint.substringBefore('?') + "?" + query + if ("JsHttpRequest" in params) "" else "&JsHttpRequest=1-xml"
        }

        /**
         * A MAG box sends cmd almost raw and the portal decodes it once: "%" sequences stay as they
         * are (never double-encoded), URL characters stay raw, and only what could break the query
         * ("&", "#", ";", spaces, quotes, non-ASCII) is percent-encoded.
         */
        fun encodeCmd(value: String): String {
            val sb = StringBuilder()
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c.code < 0x80 && (c.isLetterOrDigit() || c in "-_.~!*()/:?@\$,+=[]%")) sb.append(c)
                else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
            return sb.toString()
        }

        /** "ffmpeg http://…" → "http://…" (the first word names the player on a MAG box). */
        fun normalizeCommand(cmd: String): String {
            val s = cmd.trim()
            val space = s.indexOf(' ')
            if (space > 0 && !s.substring(0, space).contains("://")) return s.substring(space + 1).trim()
            return s
        }

        /** True for a real http(s) address that isn't the portal's own "localhost" placeholder. */
        fun isFinalUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase()?.trimEnd('.')?.removeSurrounding("[", "]") ?: return false
            return !(host == "localhost" || host.endsWith(".localhost") || host == "localhost.localdomain" ||
                host == "0.0.0.0" || host == "::1" || host == "::" || host.matches(Regex("""127\.\d+\.\d+\.\d+""")))
        }

        /**
         * Whether a row needs create_link: true if either flag is set, false if the portal sent the
         * flags and both are off, null if it sent neither (then play it safe and ask for a link).
         */
        fun linkFlag(m: Map<*, *>): Boolean? {
            if (!m.containsKey("use_http_tmp_link") && !m.containsKey("use_load_balancing")) return null
            return flag(m["use_http_tmp_link"]) || flag(m["use_load_balancing"])
        }

        fun flag(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            else -> v.toString().trim().lowercase().let { it.isNotEmpty() && it != "0" && it != "false" && it != "null" }
        }

        fun prehash(mac: String): String =
            MessageDigest.getInstance("SHA-1").digest(mac.uppercase().toByteArray()).joinToString("") { "%02X".format(it) }

        private fun randomHex(): String {
            val r = java.security.SecureRandom()
            return (1..40).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
        }

        private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

        private fun reader(source: Reader) = JsonReader(source).apply { isLenient = true }

        /** Reads a whole JSON answer into maps, lists and strings (numbers stay strings). */
        fun parseTree(text: String): Any? {
            val start = text.indexOfFirst { it == '{' || it == '[' }
            if (start < 0) throw NotJson(text.take(60))
            return try {
                readTree(reader(StringReader(text.substring(start))))
            } catch (e: IllegalStateException) {
                throw NotJson(e.message ?: "bad JSON")
            } catch (e: com.google.gson.stream.MalformedJsonException) {
                throw NotJson(e.message ?: "bad JSON")
            }
        }

        private fun readTree(r: JsonReader): Any? = when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val m = LinkedHashMap<String, Any?>()
                r.beginObject()
                while (r.hasNext()) m[r.nextName()] = readTree(r)
                r.endObject()
                m
            }
            JsonToken.BEGIN_ARRAY -> {
                val l = ArrayList<Any?>()
                r.beginArray()
                while (r.hasNext()) l += readTree(r)
                r.endArray()
                l
            }
            JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.NULL -> { r.nextNull(); null }
            else -> { r.skipValue(); null }
        }

        /**
         * Walks an object: [onKey] reads the value for keys it wants and returns true, or returns
         * false (without reading) to skip it.
         */
        private fun readObject(r: JsonReader, onKey: (String) -> Boolean) {
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                if (!onKey(key)) r.skipValue()
            }
            r.endObject()
        }
    }

    private class AuthFailure(message: String) : IOException(message)
    private class NotJson(message: String) : IOException("not a portal answer: $message")
    private class HttpStatus(val code: Int) : IOException("HTTP $code")
}

/**
 * Stalker channels, movies and episodes are saved with a "stalker://" link holding what's needed
 * to ask the portal for the real one when they're played (portal links expire within seconds).
 */
object StalkerLinks {
    const val SCHEME = "stalker://"

    data class Link(val playlistId: String, val type: String, val cmd: String, val needsLink: Boolean?, val series: Int?)

    fun make(playlistId: String, type: String, cmd: String, needsLink: Boolean?, series: Int? = null): String =
        SCHEME + enc(playlistId) + "/" + type + "?cmd=" + enc(cmd) +
            (needsLink?.let { "&tmp=" + if (it) "1" else "0" } ?: "") +
            (series?.let { "&series=$it" } ?: "")

    fun parse(url: String): Link? {
        if (!url.startsWith(SCHEME)) return null
        val rest = url.removePrefix(SCHEME)
        val slash = rest.indexOf('/').takeIf { it > 0 } ?: return null
        val playlistId = dec(rest.substring(0, slash))
        val type = rest.substring(slash + 1).substringBefore('?')
        val query = rest.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to dec(it.substringAfter('=')) }
        val cmd = query["cmd"] ?: return null
        return Link(
            playlistId, type, cmd,
            needsLink = query["tmp"]?.let { it == "1" },
            series = query["series"]?.toIntOrNull(),
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
