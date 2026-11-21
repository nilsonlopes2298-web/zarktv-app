package io.github.axon07.channelbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSpecTest {

    @Test
    fun detectsDashEvenWithTextAfterMpd() {
        assertEquals(StreamSpecs.MIME_DASH, StreamSpecs.parse("https://cdn/live/manifest.mpd/abcToken").mime)
        assertEquals(StreamSpecs.MIME_DASH, StreamSpecs.parse("https://cdn/live/manifest.mpd?token=1").mime)
        assertEquals(StreamSpecs.MIME_HLS, StreamSpecs.parse("https://cdn/live/index.m3u8").mime)
        assertNull(StreamSpecs.parse("http://host/live/user/pass/123.ts").mime)
    }

    @Test
    fun splitsKodiPipeHeaders() {
        val s = StreamSpecs.parse("http://host/1.ts|User-Agent=Kodi%2F20&Referer=http://ref")
        assertEquals("http://host/1.ts", s.url)
        assertEquals("Kodi/20", s.header("user-agent"))
        assertEquals("http://ref", s.header("Referer"))
    }

    @Test
    fun buildsClearKeyJwkFromHexPair() {
        val drm = StreamSpecs.parseDrm(
            "clearkey",
            "0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210",
        )!!
        val json = drm.clearKeyJson!!
        assertTrue(json.contains("\"kid\":\"ASNFZ4mrze8BI0VniavN7w\""))
        assertTrue(json.contains("\"k\":\"_ty6mHZUMhD-3LqYdlQyEA\""))
    }

    @Test
    fun parsesWidevineLicenseUrlWithHeaders() {
        val drm = StreamSpecs.parseDrm(
            "com.widevine.alpha",
            "https://lic.example.com/wv|Content-Type=application%2Foctet-stream|R{SSM}|",
        )!!
        assertEquals(StreamSpecs.WIDEVINE, drm.type)
        assertEquals("https://lic.example.com/wv", drm.licenseUrl)
        assertEquals("application/octet-stream", drm.licenseHeaders["Content-Type"])
    }

    @Test
    fun m3uCollectsKodiPropsBeforeOrAfterExtinf() {
        val playlist = """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210
            #EXTINF:-1,One
            https://cdn/one.mpd
            #EXTINF:-1,Two
            #EXTVLCOPT:http-user-agent=MyUA
            http://host/two.m3u8
        """.trimIndent()
        val (one, two) = M3uParser.parse(playlist.reader().buffered()).channels
        assertEquals("clearkey", one.drmType)
        assertNull(two.drmType)
        assertEquals("MyUA", two.headers["User-Agent"])
    }

    @Test
    fun picksHighestHlsVariant() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh.m3u8\n"
        assertEquals("http://h/live/high.m3u8", Hls.bestVariant(master, "http://h/live/master.m3u8"))
    }
}
