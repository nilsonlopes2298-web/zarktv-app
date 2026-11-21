package io.github.axon07.channelbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgAndDrmTest {

    private fun candidate(id: String, epgId: String?, vararg names: String) =
        EpgMatcher.Candidate(id, epgId?.lowercase(), names.toList())

    @Test
    fun matchesByExactIdThenNormalisedIdThenName() {
        val m = EpgMatcher(
            listOf(
                candidate("a", "CBC.ca", "CBC"),
                candidate("b", "tsn 1", "TSN1"),
                candidate("c", "wrong-id", "CA: CTV Toronto HD"),
                candidate("d", null, "Unknown Channel"),
            )
        )
        m.onChannel("CBC.ca", listOf("CBC"))
        m.onChannel("TSN1.ca", listOf("TSN 1"))
        m.onChannel("ctv.toronto", listOf("CTV Toronto"))
        assertTrue(m.wants("cbc.ca"))
        assertEquals("cbc.ca", m.assigned["a"])
        assertEquals("tsn1.ca", m.assigned["b"])
        assertEquals("ctv.toronto", m.assigned["c"])
        assertFalse("d" in m.assigned)
    }

    @Test
    fun exactIdWinsOverAnEarlierNameMatch() {
        val m = EpgMatcher(listOf(candidate("a", "news.real", "News")))
        m.onChannel("news.other", listOf("News"))
        m.onChannel("news.real", listOf("Real News"))
        m.finish()
        assertEquals("news.real", m.assigned["a"])
    }

    @Test
    fun clearKeyResponseUsesRequestedKidOrFallsBack() {
        val keys = StreamSpecs.parseClearKeys("0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210")
        // Request for the key id we know.
        val exact = StreamSpecs.clearKeyResponse("""{"kids":["ASNFZ4mrze8BI0VniavN7w"],"type":"temporary"}""", keys)
        assertTrue(exact.contains("\"kid\":\"ASNFZ4mrze8BI0VniavN7w\""))
        // Request for a different key id: answer with our only key under the requested id.
        val other = StreamSpecs.clearKeyResponse("""{"kids":["AAAAAAAAAAAAAAAAAAAAAA"],"type":"temporary"}""", keys)
        assertTrue(other.contains("\"kid\":\"AAAAAAAAAAAAAAAAAAAAAA\""))
        assertTrue(other.contains("\"k\":\"_ty6mHZUMhD-3LqYdlQyEA\""))
    }

    @Test
    fun parsesKodiDrmLegacyAndJson() {
        val playlist = """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210
            #EXTINF:-1,Legacy
            https://cdn/a.mpd
            #KODIPROP:inputstream.adaptive.drm={"com.widevine.alpha":{"license":{"server_url":"https://lic/wv","req_headers":"Referer=https://x"}}}
            #EXTINF:-1,Json
            https://cdn/b.mpd
        """.trimIndent()
        val (a, b) = M3uParser.parse(playlist.reader().buffered()).channels
        val da = StreamSpecs.parseDrm(a.drmType, a.drmKey)!!
        assertEquals(StreamSpecs.CLEARKEY, da.type)
        assertEquals(1, da.clearKeys.size)
        val db = StreamSpecs.parseDrm(b.drmType, b.drmKey)!!
        assertEquals(StreamSpecs.WIDEVINE, db.type)
        assertEquals("https://lic/wv", db.licenseUrl)
        assertEquals("https://x", db.licenseHeaders["Referer"])
    }
}
