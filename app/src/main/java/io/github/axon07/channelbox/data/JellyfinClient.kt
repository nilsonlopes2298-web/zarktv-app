package io.github.axon07.channelbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** Settings → Movies & shows → Jellyfin. [mode]: "add" (next to the provider's) or "replace". */
data class JellyfinSettings(
    val enabled: Boolean = false,
    val server: String = "",
    val user: String = "",
    val password: String = "",
    val mode: String = "add",
) {
    val ready get() = enabled && server.isNotBlank() && user.isNotBlank()
    val replacesProvider get() = ready && mode == "replace"
}

/**
 * Reads movies and shows from a Jellyfin server (your own media library) into the Movies and
 * TV Shows sections. Signs in with a username and password; videos play straight from the server
 * (direct play, no transcoding).
 */
class JellyfinClient(private val http: OkHttpClient, private val settings: JellyfinSettings) {

    /** A signed-in session. */
    data class Session(val server: String, val userId: String, val token: String)

    private val server = normalizeServer(settings.server)

    private val authHeader =
        """MediaBrowser Client="Channelbox", Device="Android TV", DeviceId="channelbox-${settings.user.hashCode()}", Version="1.0""""

    suspend fun signIn(): Session = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("Username", settings.user)
            put("Pw", settings.password)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$server/Users/AuthenticateByName")
            .header("X-Emby-Authorization", authHeader)
            .post(body)
            .build()
        http.newCall(request).execute().use { r ->
            when (r.code) {
                200 -> Unit
                401 -> throw IOException("Jellyfin: wrong username or password")
                else -> throw IOException("Jellyfin: server answered ${r.code}")
            }
            val o = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                ?: throw IOException("Jellyfin: unexpected answer")
            val token = o.str("AccessToken") ?: throw IOException("Jellyfin: no access token")
            val userId = (o["User"] as? JsonObject)?.str("Id") ?: throw IOException("Jellyfin: no user id")
            Session(server, userId, token)
        }
    }

    /** Writes every movie and show into [writer] under [playlistId]. Returns how many. */
    suspend fun load(session: Session, playlistId: String, writer: VodWriter, onProgress: (Int) -> Unit): Int {
        var n = 0
        val prefix = if (settings.mode == "replace") "" else "Jellyfin · "
        for ((type, kind) in listOf("Movie" to VodKind.MOVIE, "Series" to VodKind.SERIES)) {
            var start = 0
            while (true) {
                val page = items(
                    session,
                    "/Users/${session.userId}/Items?IncludeItemTypes=$type&Recursive=true" +
                        "&Fields=Genres,Overview,ProductionYear,DateCreated,CommunityRating" +
                        "&SortBy=DateCreated&SortOrder=Descending&StartIndex=$start&Limit=$PAGE",
                )
                page.forEach { o ->
                    val id = o.str("Id") ?: return@forEach
                    val genre = (o["Genres"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
                    val category = prefix + (genre ?: if (kind == VodKind.MOVIE) "Movies" else "Shows")
                    val movie = kind == VodKind.MOVIE
                    writer.add(
                        VodEntity(
                            id = if (movie) vodId(playlistId, id) else "jfseries:$id",
                            playlistId = playlistId,
                            kind = kind,
                            title = o.str("Name") ?: "Untitled",
                            category = category,
                            poster = poster(session, id, o),
                            url = if (movie) streamUrl(session, id) else null,
                            headers = null,
                            seriesKey = if (movie) null else id,
                            season = 0,
                            episode = 0,
                            added = o.str("DateCreated")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: 0L,
                            sortIndex = -n,
                            plot = o.str("Overview"),
                            rating = o.str("CommunityRating")?.toDoubleOrNull()?.let { "%.1f".format(java.util.Locale.US, it) },
                            year = o.str("ProductionYear"),
                        )
                    )
                    n++
                }
                onProgress(n)
                if (page.size < PAGE) break
                start += PAGE
            }
        }
        return n
    }

    /** Episodes of a show, loaded when it's opened. */
    suspend fun episodes(session: Session, playlistId: String, seriesId: String): List<VodEntity> =
        items(session, "/Shows/$seriesId/Episodes?UserId=${session.userId}&Fields=Overview")
            .mapIndexedNotNull { i, o ->
                val id = o.str("Id") ?: return@mapIndexedNotNull null
                VodEntity(
                    id = vodId(playlistId, id),
                    playlistId = playlistId,
                    kind = VodKind.EPISODE,
                    title = o.str("Name") ?: "Episode",
                    category = "",
                    poster = poster(session, id, o),
                    url = streamUrl(session, id),
                    headers = null,
                    seriesKey = seriesId,
                    season = o.str("ParentIndexNumber")?.toIntOrNull() ?: 0,
                    episode = o.str("IndexNumber")?.toIntOrNull() ?: (i + 1),
                    added = 0L,
                    sortIndex = -i,
                    plot = o.str("Overview"),
                    rating = null,
                    year = null,
                )
            }

    private fun streamUrl(s: Session, id: String) = "${s.server}/Videos/$id/stream?static=true&api_key=${s.token}"

    private fun poster(s: Session, id: String, o: JsonObject): String? {
        val tag = (o["ImageTags"] as? JsonObject)?.str("Primary") ?: return null
        return "${s.server}/Items/$id/Images/Primary?maxHeight=450&tag=$tag"
    }

    private suspend fun items(s: Session, path: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(s.server + path)
            .header("X-Emby-Authorization", "$authHeader, Token=\"${s.token}\"")
            .header("X-Emby-Token", s.token)
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Jellyfin: server answered ${r.code}")
            val root = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
            (root?.get("Items") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        }
    }

    private fun JsonObject.str(key: String): String? {
        val v: JsonElement = this[key] ?: return null
        return if (v is JsonNull || v !is JsonPrimitive) null else v.content
    }

    companion object {
        /** Movies & shows from Jellyfin are stored under this playlist id. */
        const val PLAYLIST_ID = "jellyfin"
        private const val PAGE = 500
    }
}
