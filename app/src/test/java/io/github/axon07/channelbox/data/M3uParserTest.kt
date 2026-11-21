package io.github.axon07.channelbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="http://example.com/guide.xml.gz,http://backup/guide.xml"
        #EXTINF:-1 tvg-id="CBC.ca" tvg-name="CBC" tvg-logo="http://logo/cbc.png" group-title="News" tvg-chno="5",CBC Toronto, ON
        http://example.com/live/1.ts
        #EXTINF:-1 tvg-id="" group-title="",No Attributes Channel
        #EXTGRP:Sports
        http://example.com/live/2.m3u8

        #EXTINF:-1 catchup-days="7" group-title="Movies, Classic",Movie "Quoted" Name
        #EXTVLCOPT:http-user-agent=Foo
        http://example.com/live/3
    """.trimIndent()

    @Test
    fun parsesChannelsAndAttributes() {
        val result = M3uParser.parse(sample.reader().buffered())
        assertEquals(listOf("http://example.com/guide.xml.gz", "http://backup/guide.xml"), result.epgUrls)
        assertEquals(3, result.channels.size)

        val cbc = result.channels[0]
        assertEquals("CBC Toronto, ON", cbc.name)
        assertEquals(5, cbc.number)
        assertEquals("News", cbc.group)
        assertEquals("CBC.ca", cbc.epgId)
        assertEquals("http://logo/cbc.png", cbc.logo)

        val second = result.channels[1]
        assertEquals("No Attributes Channel", second.name)
        assertEquals("Sports", second.group)
        assertNull(second.epgId)
        assertNull(second.number)

        val third = result.channels[2]
        assertEquals("Movies, Classic", third.group)
        assertEquals(7, third.catchupDays)
        assertEquals("http://example.com/live/3", third.url)
    }

    @Test
    fun displayNameIgnoresCommasInsideQuotes() {
        assertEquals("Name", M3uParser.displayName("""#EXTINF:-1 group-title="a,b",Name"""))
    }

    @Test
    fun nameMatchingNormalisesQualitySuffixes() {
        assertEquals(TvRepository.normalizeName("CNN"), TvRepository.normalizeName("CNN HD"))
        assertEquals(TvRepository.normalizeName("TSN 1"), TvRepository.normalizeName("tsn1 fhd"))
    }
}
