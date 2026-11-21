package io.github.axon07.channelbox.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The Stalker client against a simulated portal that behaves like a real Stalker / Ministra
 * server: plain-text "Authorization failed." for a missing or dropped token, status 1 / 2 profiles,
 * 14-row pages, "localhost" channel links that only create_link turns into real ones, etc.
 */
class StalkerClientTest {
    private lateinit var server: MockWebServer
    private val portal = FakePortal()
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = portal
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun client(mac: String = MAC, login: String = "", password: String = "", path: String = "/stalker_portal/c/") =
        StalkerClient(http, StalkerAccount(base() + path, mac, login, password), timeZone = "Europe/London")

    // ---- Pure helpers ---------------------------------------------------------------------------

    @Test
    fun candidatesFromTheBoxAddress() {
        assertEquals(
            listOf(
                "http://h:8080/portal.php", "http://h:8080/server/load.php", "http://h:8080/stalker_portal/server/load.php",
            ),
            StalkerClient.candidates("http://h:8080/c/"),
        )
        val sp = StalkerClient.candidates("HTTP://Host.example/stalker_portal/c/")
        assertEquals("http://host.example/stalker_portal/portal.php", sp[0])
        assertEquals("http://host.example/stalker_portal/server/load.php", sp[1])
        assertTrue("http://host.example/portal.php" in sp)
        assertEquals("http://h/portal.php", StalkerClient.candidates("h/portal.php")[0])
        assertEquals("http://h/stalker_portal/server/load.php", StalkerClient.candidates("http://h/stalker_portal/server/load.php")[0])
    }

    @Test
    fun macAddresses() {
        assertEquals("00:1A:79:AB:CD:EF", StalkerClient.normalizeMac("00-1a-79-ab-cd-ef"))
        assertEquals("00:1A:79:AB:CD:EF", StalkerClient.normalizeMac(" 001a79abcdef "))
        assertTrue(StalkerClient.isValidMac("00:1a:79:ab:cd:ef"))
        assertFalse(StalkerClient.isValidMac("00:1A:79:AB:CD"))
        assertFalse(StalkerClient.isValidMac("00:1A:79:AB:CD:EG"))
    }

    @Test
    fun cmdIsSentTheWayAMagBoxSendsIt() {
        assertEquals("ffmpeg%20http://localhost/ch/12_", StalkerClient.encodeCmd("ffmpeg http://localhost/ch/12_"))
        assertEquals("a%3Ab", StalkerClient.encodeCmd("a%3Ab")) // never double-encoded
        assertEquals("x%26y%23z%3B", StalkerClient.encodeCmd("x&y#z;")) // can't break the query
        val url = StalkerClient.requestUrl("http://h/portal.php?old=1", mapOf("type" to "itv", "cmd" to "ffrt http://x/a b"))
        assertEquals("http://h/portal.php?type=itv&cmd=ffrt%20http://x/a%20b&JsHttpRequest=1-xml", url)
    }

    @Test
    fun commandsAndFinalLinks() {
        assertEquals("http://cdn/x.m3u8", StalkerClient.normalizeCommand("ffmpeg http://cdn/x.m3u8"))
        assertEquals("http://cdn/x.m3u8", StalkerClient.normalizeCommand("http://cdn/x.m3u8"))
        assertTrue(StalkerClient.isFinalUrl("http://cdn.example/live/1.ts"))
        assertFalse(StalkerClient.isFinalUrl("http://localhost/ch/1_"))
        assertFalse(StalkerClient.isFinalUrl("http://127.0.0.1/ch/1_"))
        assertFalse(StalkerClient.isFinalUrl("ffrt4://ch/1"))
        assertNull(StalkerClient.linkFlag(mapOf("id" to "1")))
        assertEquals(false, StalkerClient.linkFlag(mapOf("use_http_tmp_link" to "0", "use_load_balancing" to 0)))
        assertEquals(true, StalkerClient.linkFlag(mapOf("use_http_tmp_link" to "1")))
    }

    @Test
    fun savedLinksRoundTrip() {
        val url = StalkerLinks.make("pl1", "vod", "ffmpeg http://x/a?b=1&c=2|weird", needsLink = true, series = 3)
        assertFalse(url.contains('|')) // a "|" would be read as Kodi-style headers
        val link = StalkerLinks.parse(url)!!
        assertEquals("pl1", link.playlistId)
        assertEquals("vod", link.type)
        assertEquals("ffmpeg http://x/a?b=1&c=2|weird", link.cmd)
        assertEquals(true, link.needsLink)
        assertEquals(3, link.series)
        assertNull(StalkerLinks.parse(StalkerLinks.make("p", "itv", "c", null))!!.needsLink)
    }

    // ---- Against the simulated portal ------------------------------------------------------------

    @Test
    fun findsTheApiBehindTheBoxAddressAndSignsIn() {
        val c = client()
        c.connect()
        assertEquals(base() + "/stalker_portal/server/load.php", c.endpoint)
        val shake = portal.calls.first { it.action == "handshake" && it.path.startsWith("/stalker_portal/server") }
        assertTrue(shake.cookie!!.contains("mac=$MAC"))
        assertTrue(shake.userAgent!!.contains("MAG200"))
        assertEquals(StalkerClient.prehash(MAC), shake.query["prehash"])
        val profile = portal.calls.first { it.action == "get_profile" }
        assertEquals("Bearer ${portal.token}", profile.authorization)
        assertEquals("MAG250", profile.query["stb_type"])
        assertTrue(profile.query["metrics"]!!.contains(MAC))
    }

    @Test
    fun loadsAllChannelsWithTheirGroups() {
        val c = client()
        val list = StalkerLoader.channels(c, "pl") {}
        assertEquals(3, list.size)
        val news = list.first { it.name == "News One" }
        assertEquals("News", news.group)
        assertEquals(StalkerLoader.channelId("pl", "1"), news.id)
        assertEquals(101, news.number)
        assertEquals("news.one", news.epgId)
        assertEquals(base() + "/logos/1.png", news.logo)
        val link = StalkerLinks.parse(news.url)!!
        assertEquals("ffmpeg http://localhost/ch/1_", link.cmd)
        assertEquals(false, link.needsLink)
        assertEquals("stalker:pl:2", list.first { it.name == "Sports" }.epgId)
        assertEquals("pl", StalkerLoader.portalId(news.id!!, "pl")?.let { "pl" })
    }

    @Test
    fun pagesThroughChannelsWhenThePortalHasNoFullList() {
        portal.allChannels = false
        portal.pagedChannels = 30
        val list = client().channels()
        assertEquals(30, list.size)
        assertEquals(30, list.map { it.id }.toSet().size)
        assertTrue(portal.calls.any { it.action == "get_ordered_list" && it.query["p"] == "3" })
        assertTrue(portal.calls.none { it.action == "get_ordered_list" && it.query["p"] == "4" })
    }

    @Test
    fun stopsPagingWhenThePortalIgnoresThePageNumber() {
        portal.allChannels = false
        portal.pagedChannels = 30
        portal.ignorePage = true
        val list = client().channels()
        assertEquals(14, list.size)
    }

    @Test
    fun localhostLinksGoThroughCreateLink() {
        val c = client()
        val p = c.playable("itv", "ffmpeg http://localhost/ch/1_", needsLink = false)
        assertEquals("http://cdn.example/live/1.ts?play_token=abc", p.url)
        assertTrue(p.headers.isEmpty()) // another host: no portal identity sent along
        val link = portal.calls.last { it.action == "create_link" }
        assertEquals("ffmpeg http://localhost/ch/1_", link.query["cmd"])
        assertEquals("Bearer ${portal.token}", link.authorization)
    }

    @Test
    fun finalLinksPlayWithoutAskingThePortal() {
        val c = client()
        val p = c.playable("itv", "ffmpeg http://cdn.example/static/2.m3u8", needsLink = false)
        assertEquals("http://cdn.example/static/2.m3u8", p.url)
        assertTrue(portal.calls.none { it.action == "create_link" })
        // Without the portal's flags (or with them set) a fresh link is asked for.
        c.playable("itv", "ffmpeg http://cdn.example/static/2.m3u8", needsLink = null)
        assertEquals(1, portal.calls.count { it.action == "create_link" })
    }

    @Test
    fun streamsOnThePortalsOwnServerGetItsIdentity() {
        portal.linkOnPortalHost = true
        val c = client()
        val p = c.playable("vod", "/media/file_5.mpg", needsLink = true)
        assertEquals(base() + "/media/file_5.mpg?token=t", p.url)
        assertEquals("Bearer ${portal.token}", p.headers["Authorization"])
        assertTrue(p.headers["Cookie"]!!.contains("mac=$MAC"))
    }

    @Test
    fun renewsADroppedTokenOnce() {
        val c = client()
        c.connect()
        val first = portal.token
        portal.dropToken() // as if the same MAC signed in on another box
        val p = c.playable("itv", "ffmpeg http://localhost/ch/1_", needsLink = true)
        assertEquals("http://cdn.example/live/1.ts?play_token=abc", p.url)
        assertTrue(portal.token != first)
        assertEquals(2, portal.calls.count { it.action == "handshake" && it.path.startsWith("/stalker_portal/server") })
    }

    @Test
    fun portalsThatAlsoWantALogin() {
        portal.requireLogin = true
        try {
            client().connect()
            fail("needs a login")
        } catch (e: StalkerException) {
            assertTrue(e.message!!.contains("username and password"))
        }
        val c = client(login = "joe", password = "secret")
        c.connect()
        val auth = portal.calls.last { it.action == "do_auth" }
        assertEquals("joe", auth.query["login"])
        assertEquals("secret", auth.query["password"])
        assertEquals("1", portal.calls.last { it.action == "get_profile" }.query["auth_second_step"])
        assertEquals(3, c.channels().size)
    }

    @Test
    fun wrongLogin() {
        portal.requireLogin = true
        try {
            client(login = "joe", password = "wrong").connect()
            fail("wrong password")
        } catch (e: StalkerException) {
            assertTrue(e.message!!.contains("didn't accept"))
        }
    }

    @Test
    fun refusedMacSaysWhy() {
        try {
            client(mac = "00:1A:79:00:00:00").connect()
            fail("refused")
        } catch (e: StalkerException) {
            assertTrue(e.message, e.message!!.contains("refused this MAC"))
            assertTrue(e.message, e.message!!.contains("not registered"))
        }
    }

    @Test
    fun noPortalAtTheAddress() {
        try {
            client(path = "/nothing/here/").connect()
        } catch (e: StalkerException) {
            // The simulated portal also answers at the root, so this one finds it there.
            fail("should have found the portal at the server root: ${e.message}")
        }
        portal.gone = true
        try {
            client(path = "/nothing/here/").connect()
            fail("no portal")
        } catch (e: StalkerException) {
            assertTrue(e.message!!.contains("No Stalker portal"))
        }
    }

    @Test
    fun portalsWithoutSignIn() {
        portal.noAuth = true
        val c = client(path = "/c/")
        c.connect()
        assertEquals(base() + "/portal.php", c.endpoint)
        assertEquals(3, c.channels().size)
    }

    @Test
    fun linkErrorsInWords() {
        portal.linkError = "limit"
        try {
            client().playable("itv", "ffmpeg http://localhost/ch/1_", needsLink = true)
            fail("limit")
        } catch (e: StalkerException) {
            assertTrue(e.message!!.contains("Too many"))
        }
    }

    @Test
    fun wholeGuide() {
        val got = ArrayList<StalkerClient.Programme>()
        assertTrue(client().guide(24) { ch, start, stop, title, desc -> got += StalkerClient.Programme(ch, start, stop, title, desc) })
        assertEquals(3, got.size)
        val first = got.first { it.channel == "1" && it.title == "Morning News" }
        assertEquals(1_700_000_000_000L, first.start)
        assertEquals(1_700_001_800_000L, first.stop)
        // Times given only as text are in the portal's time zone (the one sent in the cookie).
        val textTimes = got.first { it.title == "Match" }
        assertEquals(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = java.util.TimeZone.getTimeZone("Europe/London")
        }.parse("2023-11-14 22:00:00")!!.time, textTimes.start)
    }

    @Test
    fun guideAsAListAndTheLoaderKeys() {
        portal.guideAsList = true
        val c = client()
        val channels = StalkerLoader.channels(c, "pl") {}.map {
            ChannelEntity(id = it.id!!, number = it.number ?: 0, name = it.name, groupTitle = it.group, logoUrl = null,
                streamUrl = it.url, epgId = it.epgId?.lowercase(), playlistId = "pl")
        }
        val keys = ArrayList<String>()
        assertTrue(StalkerLoader.guide(c, "pl", channels, 24) { key, _ -> keys += key })
        assertEquals(setOf("news.one", "stalker:pl:2"), keys.toSet())
    }

    @Test
    fun shortGuideWhenThereIsNoWholeGuide() {
        portal.noWholeGuide = true
        val c = client()
        val channels = StalkerLoader.channels(c, "pl") {}.map {
            ChannelEntity(id = it.id!!, number = 0, name = it.name, groupTitle = "", logoUrl = null,
                streamUrl = it.url, epgId = it.epgId, playlistId = "pl")
        }
        val keys = ArrayList<String>()
        assertTrue(StalkerLoader.guide(c, "pl", channels, 24) { key, _ -> keys += key })
        assertTrue("news.one" in keys)
        assertTrue(portal.calls.any { it.action == "get_short_epg" && it.query["ch_id"] == "1" })
    }

    @Test
    fun moviesAndShows() {
        val c = client()
        val items = ArrayList<VodEntity>()
        StalkerLoader.vod(c, "pl", { items += it }) {}
        val movies = items.filter { it.kind == VodKind.MOVIE }
        val shows = items.filter { it.kind == VodKind.SERIES }
        assertEquals(20, movies.size) // two pages of one category ("*" = all is skipped)
        assertEquals("Action", movies.first().category)
        assertEquals("2021", movies.first { it.title == "Movie 1" }.year)
        assertEquals(StalkerLinks.make("pl", "vod", "/media/file_1.mpg", null), movies.first { it.title == "Movie 1" }.url)
        assertEquals(2, shows.size)

        // A show listed among the movies with its episode numbers right in it.
        val embedded = shows.first { it.title == "Old Show" }
        val eps = StalkerLoader.episodes(c, embedded)
        assertEquals(listOf(1, 2, 3), eps.map { it.episode })
        assertEquals(3, StalkerLinks.parse(eps.last().url!!)!!.series)

        // A show from the shows section: seasons come from the portal.
        val show = shows.first { it.title == "Big Show" }
        val episodes = StalkerLoader.episodes(c, show)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), episodes.map { it.season to it.episode })
        val link = StalkerLinks.parse(episodes.last().url!!)!!
        assertEquals("season2cmd", link.cmd)
        assertEquals(1, link.series)
    }

    // ---- The simulated portal --------------------------------------------------------------------

    data class Call(val path: String, val action: String?, val query: Map<String, String>, val cookie: String?, val userAgent: String?, val authorization: String?)

    class FakePortal : Dispatcher() {
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var token = "tok1"
        private var tokens = 1
        @Volatile private var active: String? = null
        var allChannels = true
        var pagedChannels = 0
        var ignorePage = false
        var requireLogin = false
        var noAuth = false
        var rootPortal = true
        var gone = false
        var linkError: String? = null
        var linkOnPortalHost = false
        var guideAsList = false
        var noWholeGuide = false
        private var loggedIn = false

        fun dropToken() {
            active = null
            token = "dropped"
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val path = url.encodedPath
            val q = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
            calls += Call(path, q["action"], q, request.getHeader("Cookie"), request.getHeader("User-Agent"), request.getHeader("Authorization"))
            val api = !gone && (path == "/stalker_portal/server/load.php" || (rootPortal && path == "/portal.php"))
            if (!api) return MockResponse().setResponseCode(404).setBody("<html>Not found</html>")
            if (request.getHeader("Cookie")?.contains("mac=") != true) return text("Unauthorized request.")
            val mac = Regex("mac=([^;]+)").find(request.getHeader("Cookie")!!)!!.groupValues[1]
            val type = q["type"]
            val action = q["action"]
            if (noAuth) {
                if (type == "stb" && action == "handshake") return json("""{"js":[]}""")
                return content(type, action, q, url.toString())
            }
            if (type == "stb" && action == "handshake") {
                tokens++
                token = "tok$tokens"
                return json("""{"js":{"token":"$token","random":"r$tokens"}}""")
            }
            val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")
            if (bearer != token) return text("Authorization failed.")
            if (type == "stb" && action == "get_profile") {
                if (mac == "00:1A:79:00:00:00") return json("""{"js":{"status":1,"msg":"MAC <b>not registered</b>"}}""")
                if (requireLogin && q["auth_second_step"] != "1") return json("""{"js":{"status":2,"msg":"Login required"}}""")
                if (requireLogin && !loggedIn) return json("""{"js":{"status":2}}""")
                active = token
                return json("""{"js":{"id":"1","status":0,"watchdog_timeout":120}}""")
            }
            if (type == "stb" && action == "do_auth") {
                loggedIn = q["login"] == "joe" && q["password"] == "secret"
                return json("""{"js":$loggedIn}""")
            }
            if (active != token) return text("Authorization failed.")
            return content(type, action, q, url.toString())
        }

        private fun content(type: String?, action: String?, q: Map<String, String>, url: String): MockResponse {
            val origin = url.substringBefore("/", "").let { Regex("^(https?://[^/]+)").find(url)!!.groupValues[1] }
            return when ("$type/$action") {
                "itv/get_genres" -> json("""{"js":[{"id":"*","title":"All"},{"id":"10","title":"News"},{"id":"20","title":"Sports"}]}""")
                "itv/get_all_channels" -> if (!allChannels) json("""{"js":{"data":[]}}""") else json(
                    """{"js":{"total_items":3,"data":[
                    {"id":"1","name":"News One","number":"101","logo":"/logos/1.png","tv_genre_id":"10","cmd":"ffmpeg http://localhost/ch/1_","use_http_tmp_link":"0","use_load_balancing":0,"xmltv_id":"news.one"},
                    {"id":"2","name":"Sports","number":102,"logo":"","tv_genre_id":"20","cmd":"ffmpeg http://cdn.example/live/2.ts","use_http_tmp_link":"0","use_load_balancing":"0"},
                    {"id":"1","name":"News One (again)","cmd":"ffmpeg http://localhost/ch/1_"},
                    {"id":"3","name":"Music","tv_genre_id":"99","cmds":[{"url":"ffmpeg http://localhost/ch/3_"}]}
                    ]}}"""
                )
                "itv/get_ordered_list" -> {
                    val page = if (ignorePage) 1 else q["p"]!!.toInt()
                    val from = (page - 1) * 14
                    val rows = (from until minOf(from + 14, pagedChannels)).joinToString(",") {
                        """{"id":"${it + 1}","name":"Ch ${it + 1}","cmd":"ffmpeg http://localhost/ch/${it + 1}_"}"""
                    }
                    json("""{"js":{"total_items":$pagedChannels,"max_page_items":14,"selected_item":0,"cur_page":$page,"data":[$rows]}}""")
                }
                "itv/create_link", "vod/create_link" -> when {
                    linkError != null -> json("""{"js":{"id":"","cmd":"","error":"$linkError"}}""")
                    linkOnPortalHost -> json("""{"js":{"cmd":"ffmpeg ${q["cmd"]}?token=t"}}""")
                    else -> json("""{"js":{"id":"1","cmd":"ffmpeg http://cdn.example/live/1.ts?play_token=abc","error":""}}""")
                }
                "itv/get_epg_info" -> when {
                    noWholeGuide -> json("""{"js":[]}""")
                    guideAsList -> json(
                        """{"js":{"data":[
                        {"ch_id":"1","name":"A","start_timestamp":"1700000000","stop_timestamp":"1700001800"},
                        {"ch_id":"2","name":"B","start_timestamp":1700000000,"stop_timestamp":1700003600},
                        {"ch_id":"77","name":"Unknown channel","start_timestamp":"1700000000","stop_timestamp":"1700001800"}
                        ]}}"""
                    )
                    else -> json(
                        """{"js":{"data":{
                        "1":[{"id":"1","name":"Morning News","descr":"Headlines","start_timestamp":"1700000000","stop_timestamp":"1700001800"},
                             {"id":"2","name":"Weather","start_timestamp":"1700001800","stop_timestamp":"1700003600"}],
                        "2":[{"id":"3","name":"Match","time":"2023-11-14 22:00:00","time_to":"2023-11-14 23:45:00"},
                             {"id":"4","name":"","start_timestamp":"1","stop_timestamp":"2"}]
                        }}}"""
                    )
                }
                "itv/get_short_epg" -> json("""{"js":[{"ch_id":"${q["ch_id"]}","name":"Now","start_timestamp":"1700000000","stop_timestamp":"1700001800"}]}""")
                "vod/get_categories" -> json("""{"js":[{"id":"*","title":"All"},{"id":"5","title":"Action"}]}""")
                "vod/get_ordered_list" -> if (q["movie_id"] != null) json("""{"js":{"data":[]}}""") else {
                    val page = q["p"]!!.toInt()
                    val rows = when (page) {
                        1 -> (1..14).joinToString(",") { """{"id":"$it","name":"Movie $it","cmd":"/media/file_$it.mpg","year":"2021-05-01","added":"2023-01-02 10:00:00"}""" } +
                            """,{"id":"100","name":"Old Show","cmd":"/media/show_100.mpg","series":[3,1,2]}"""
                        2 -> (15..20).joinToString(",") { """{"id":"$it","name":"Movie $it","cmd":"/media/file_$it.mpg"}""" }
                        else -> ""
                    }
                    json("""{"js":{"total_items":21,"max_page_items":15,"data":[$rows]}}""")
                }
                "series/get_categories" -> json("""{"js":[{"id":"7","title":"Drama"}]}""")
                "series/get_ordered_list" -> if (q["movie_id"] == "200") json(
                    """{"js":{"total_items":2,"max_page_items":14,"data":[
                    {"id":"200:1","name":"Season 1","cmd":"season1cmd","series":[1,2]},
                    {"id":"200:2","name":"Season 2","cmd":"season2cmd","series":[1]}
                    ]}}"""
                ) else json("""{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"200","name":"Big Show","screenshot_uri":"http://img/1.jpg"}]}}""")
                "watchdog/get_events" -> json("""{"js":{"data":{"msgs":0}}}""")
                else -> json("""{"js":[]}""")
            }.also { if (origin.isEmpty()) error("no origin") }
        }

        private fun json(body: String) = MockResponse().setHeader("Content-Type", "text/javascript").setBody(body)
        private fun text(body: String) = MockResponse().setHeader("Content-Type", "text/html").setBody(body)
    }

    companion object {
        const val MAC = "00:1A:79:12:34:56"
    }
}
