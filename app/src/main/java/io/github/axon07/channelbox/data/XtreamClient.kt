package io.github.axon07.channelbox.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder

/** Minimal Xtream Codes "player_api.php" client for live TV. */
class XtreamClient(private val http: OkHttpClient) {

    data class Login(val server: String, val user: String, val pass: String) {
        companion object {
            /**
             * Most provider M3U links are Xtream Codes links underneath
             * (http://host:port/get.php?username=…&password=…). Returns the login, or null.
             */
            fun fromM3uUrl(url: String): Login? = runCatching {
                val uri = java.net.URI(url.trim())
                if (uri.path?.endsWith("/get.php") != true) return null
                val q = uri.rawQuery?.split('&').orEmpty().mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }.toMap()
                val user = q["username"]?.takeIf { it.isNotBlank() } ?: return null
                val pass = q["password"] ?: return null
                val port = if (uri.port > 0) ":${uri.port}" else ""
                val prefix = uri.path.removeSuffix("/get.php")
                Login("${uri.scheme}://${uri.host}$port$prefix", user, pass)
            }.getOrNull()

            private val livePattern = Regex(
                """^(https?://[^/?#]+)/(?:live/)?([^/?#]+)/([^/?#]+)/(\d+)(?:\.[A-Za-z0-9]+)?(?:[?#|].*)?$""",
                RegexOption.IGNORE_CASE,
            )

            /**
             * The login and stream id inside an Xtream live link (…/live/USER/PASS/123.ts, or
             * …/USER/PASS/123), or null.
             */
            fun fromLiveUrl(url: String): Pair<Login, String>? = livePattern.find(url.trim())?.let { m ->
                runCatching {
                    Login(
                        m.groupValues[1],
                        java.net.URLDecoder.decode(m.groupValues[2], "UTF-8"),
                        java.net.URLDecoder.decode(m.groupValues[3], "UTF-8"),
                    ) to m.groupValues[4]
                }.getOrNull()
            }

            private val streamPattern = Regex("""^(https?://[^/]+)/(?:movie|series)/([^/]+)/([^/]+)/[^/?#]+""", RegexOption.IGNORE_CASE)

            /** The login inside an Xtream movie/episode link (…/series/USER/PASS/123.mp4), or null. */
            fun fromStreamUrl(url: String): Login? = streamPattern.find(url.trim())?.let { m ->
                runCatching {
                    Login(
                        m.groupValues[1],
                        java.net.URLDecoder.decode(m.groupValues[2], "UTF-8"),
                        java.net.URLDecoder.decode(m.groupValues[3], "UTF-8"),
                    )
                }.getOrNull()
            }
        }

        /**
         * The server address, tidied: "http://" added if missing, and the scheme and host made
         * lower case ("HTTP://Example.COM:8080" from a TV keyboard stuck on capitals works too –
         * web addresses don't care, but "HTTP://" wasn't recognised and the host wasn't found).
         * Username and password stay exactly as typed: those are case-sensitive.
         */
        val base: String
            get() = normalizeServer(server)

        private val u get() = enc(user.trim())
        private val p get() = enc(pass.trim())

        fun api(action: String? = null, extra: String = "") =
            "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "") + extra

        fun movieUrl(id: String, ext: String) = "$base/movie/$u/$p/$id.$ext"
        fun episodeUrl(id: String, ext: String) = "$base/series/$u/$p/$id.$ext"

        fun liveUrl(streamId: String) = "$base/live/$u/$p/$streamId.ts"

        val xmltvUrl: String get() = "$base/xmltv.php?username=$u&password=$p"

        fun timeshiftTemplate(streamId: String) =
            "$base/timeshift/$u/$p/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$streamId.ts"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    suspend fun load(login: Login): ParsedPlaylist {
        val auth = getJson(login.api()) as? JsonObject
            ?: throw IOException("Login failed – check the server, username and password")
        val userInfo = auth["user_info"] as? JsonObject
        if (userInfo == null || userInfo.str("auth") == "0") {
            throw IOException("Login failed – check the server, username and password")
        }
        userInfo.str("status")?.let { status ->
            if (!status.equals("Active", ignoreCase = true)) throw IOException("Account status: $status")
        }
        // Archive times are given in the server's own time zone.
        val serverZone = (auth["server_info"] as? JsonObject)?.str("timezone")?.takeIf { it.isNotBlank() }

        val categories = categories(login, "get_live_categories")
        val channels = ArrayList<ParsedChannel>()
        streamObjects(login.api("get_live_streams")) { o ->
            val id = o["stream_id"] ?: return@streamObjects
            channels += ParsedChannel(
                name = o["name"]?.trim().orEmpty().ifBlank { "Channel $id" },
                number = o["num"]?.toIntOrNull(),
                group = o["category_id"]?.let { categories[it] } ?: "Uncategorized",
                logo = o["stream_icon"]?.takeIf { it.isNotBlank() },
                url = login.liveUrl(id),
                epgId = o["epg_channel_id"]?.takeIf { it.isNotBlank() },
                catchupDays = archiveDays(o),
                catchupType = "default",
                catchupSource = login.timeshiftTemplate(id) + (serverZone?.let { "{tz=$it}" } ?: ""),
            )
        }
        return ParsedPlaylist(channels, listOf(login.xmltvUrl))
    }

    /** Catch-up (archive) of an Xtream server's live channels. */
    class Archive(val days: Map<String, Int>, val login: Login, private val serverZone: String?) {
        /** The archive link template for [streamId] (see [Catchup]). */
        fun source(streamId: String) = login.timeshiftTemplate(streamId) + (serverZone?.let { "{tz=$it}" } ?: "")
    }

    /**
     * Which live channels have an archive, and for how many days – for M3U playlists that are
     * Xtream links underneath (the M3U itself rarely says). Null if the login doesn't work.
     */
    suspend fun archive(login: Login): Archive? {
        val auth = getJson(login.api()) as? JsonObject ?: return null
        val userInfo = auth["user_info"] as? JsonObject ?: return null
        if (userInfo.str("auth") == "0") return null
        val serverZone = (auth["server_info"] as? JsonObject)?.str("timezone")?.takeIf { it.isNotBlank() }
        val days = HashMap<String, Int>()
        streamObjects(login.api("get_live_streams")) { o ->
            val id = o["stream_id"] ?: return@streamObjects
            archiveDays(o).takeIf { it > 0 }?.let { days[id] = it }
        }
        return Archive(days, login, serverZone)
    }

    /**
     * Days of archive for a live stream. Servers send tv_archive as 1, "1" or true, and some leave
     * the duration out or at 0 – those still have an archive, so assume a week (the server limits it).
     */
    private fun archiveDays(o: Map<String, String>): Int {
        val on = o["tv_archive"]?.trim()?.lowercase()
        if (on != "1" && on != "true") return 0
        return o["tv_archive_duration"]?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: 7
    }

    /**
     * Streams movies and shows into [writer] (episodes come later, from [episodes]).
     * A part that fails is skipped. [onProgress] gets the running count.
     */
    suspend fun loadVod(login: Login, playlistId: String, writer: VodWriter, onProgress: (Int) -> Unit = {}) {
        var n = 0
        try {
            val cats = categories(login, "get_vod_categories")
            streamObjects(login.api("get_vod_streams")) { o ->
                val id = o["stream_id"] ?: return@streamObjects
                val url = login.movieUrl(id, o["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4")
                writer.add(
                    VodEntity(
                        id = vodId(playlistId, url), playlistId = playlistId, kind = VodKind.MOVIE,
                        title = o["name"]?.trim().orEmpty().ifBlank { "Movie $id" },
                        category = o["category_id"]?.let { cats[it] } ?: "Uncategorized",
                        poster = o["stream_icon"]?.takeIf { it.isNotBlank() }, url = url, headers = null,
                        seriesKey = null, season = 0, episode = 0, added = o["added"]?.toLongOrNull() ?: 0,
                        sortIndex = -n, plot = null, rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                        year = o["year"]?.takeIf { it.isNotBlank() },
                    )
                )
                if (++n % 2_000 == 0) onProgress(n)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Movies unavailable – carry on with shows.
        }
        try {
            val cats = categories(login, "get_series_categories")
            streamObjects(login.api("get_series")) { o ->
                val id = o["series_id"] ?: return@streamObjects
                writer.add(
                    VodEntity(
                        id = "xseries:$playlistId:$id", playlistId = playlistId, kind = VodKind.SERIES,
                        title = o["name"]?.trim().orEmpty().ifBlank { "Show $id" },
                        category = o["category_id"]?.let { cats[it] } ?: "Uncategorized",
                        poster = o["cover"]?.takeIf { it.isNotBlank() }, url = null, headers = null, seriesKey = id,
                        season = 0, episode = 0, added = o["last_modified"]?.toLongOrNull() ?: 0, sortIndex = -n,
                        plot = o["plot"]?.takeIf { it.isNotBlank() },
                        rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                        year = (o["releaseDate"] ?: o["release_date"])?.take(4)?.takeIf { it.isNotBlank() },
                    )
                )
                if (++n % 2_000 == 0) onProgress(n)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Shows unavailable.
        }
    }

    /** Episodes of one Xtream show, in season order. */
    suspend fun episodes(login: Login, playlistId: String, seriesId: String): List<VodEntity> {
        val root = getJson(login.api("get_series_info", "&series_id=$seriesId")) as? JsonObject ?: return emptyList()
        val seasons = root["episodes"]
        val lists: List<JsonElement> = when (seasons) {
            is JsonObject -> seasons.values.flatMap { (it as? JsonArray).orEmpty() }
            is JsonArray -> seasons.flatMap { (it as? JsonArray) ?: listOf(it) }
            else -> emptyList()
        }
        return lists.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.str("id") ?: return@mapIndexedNotNull null
            val info = o["info"] as? JsonObject
            val url = login.episodeUrl(id, o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4")
            VodEntity(
                id = url, playlistId = playlistId, kind = VodKind.EPISODE,
                title = o.str("title")?.trim().orEmpty().ifBlank { "Episode ${o.str("episode_num") ?: i + 1}" },
                category = "", poster = info?.str("movie_image")?.takeIf { it.isNotBlank() }, url = url, headers = null,
                seriesKey = seriesId, season = o.str("season")?.toIntOrNull() ?: 0,
                episode = o.str("episode_num")?.toIntOrNull() ?: (i + 1), added = 0, sortIndex = i,
                plot = info?.str("plot")?.takeIf { it.isNotBlank() },
                rating = info?.str("duration")?.takeIf { it.isNotBlank() }, // shown as the episode's length
                year = null,
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private suspend fun categories(login: Login, action: String): Map<String, String> {
        val out = HashMap<String, String>()
        streamObjects(login.api(action)) { o -> out[o["category_id"] ?: ""] = o["category_name"] ?: "Uncategorized" }
        return out
    }

    /**
     * Reads a JSON array of objects one object at a time, keeping only their simple fields.
     * Provider lists can hold tens of thousands of entries – far too many to load in one piece.
     */
    private suspend fun streamObjects(url: String, onItem: (Map<String, String>) -> Unit) {
        http.download(url) { input ->
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue() // e.g. {} or [] variants for "nothing here"
                return@download
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                val o = HashMap<String, String>(32)
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> o[name] = r.nextString()
                        JsonToken.BOOLEAN -> o[name] = r.nextBoolean().toString()
                        JsonToken.NULL -> r.nextNull()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                onItem(o)
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun getJson(url: String): JsonElement =
        http.download(url) { Json.decodeFromStream(JsonElement.serializer(), it) }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        return if (v is JsonNull) null else v.content
    }
}

/** "HTTP://Example.COM:8080/path/" → "http://example.com:8080/path"; adds http:// when missing. */
internal fun normalizeServer(server: String): String {
    val raw = server.trim().trimEnd('/')
    val m = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/]*)(.*)$""").find(raw)
    return if (m != null) {
        val (scheme, host, rest) = m.destructured
        "${scheme.lowercase()}://${host.lowercase()}$rest"
    } else {
        val slash = raw.indexOf('/')
        val host = if (slash < 0) raw else raw.substring(0, slash)
        val rest = if (slash < 0) "" else raw.substring(slash)
        "http://${host.lowercase()}$rest"
    }
}
