package io.github.axon07.channelbox.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class XmlTvParserTest {

    @Test
    fun parsesTimesWithOffsets() {
        // 2026-11-16 12:00 UTC
        val utc = 1_794_830_400_000L
        assertEquals(utc, XmlTvParser.parseTime("20261116120000 +0000"))
        assertEquals(utc, XmlTvParser.parseTime("20261116060000 -0600"))
        assertEquals(utc, XmlTvParser.parseTime("20261116133000 +0130"))
        assertEquals(utc, XmlTvParser.parseTime("202611161200"))
        assertEquals(null, XmlTvParser.parseTime("garbage"))
    }

    @Test
    fun streamsChannelsAndProgrammes() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cbc.ca"><display-name>CBC</display-name><display-name>CBC HD</display-name></channel>
              <programme start="20261116120000 +0000" stop="20261116130000 +0000" channel="cbc.ca">
                <title lang="en">The National</title>
                <desc>News.</desc>
                <category>News</category>
              </programme>
              <programme start="20261116130000 +0000" stop="20261116120000 +0000" channel="cbc.ca">
                <title>Broken (stop before start)</title>
              </programme>
            </tv>
        """.trimIndent()

        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val channels = mutableListOf<Pair<String, List<String>>>()
        val titles = mutableListOf<String>()
        XmlTvParser(parser).parse(
            onChannel = { id, names -> channels += id to names },
            onProgramme = { _, _, _, title, desc ->
                titles += title
                assertEquals("News.", desc)
            },
        )
        assertEquals(listOf("cbc.ca" to listOf("CBC", "CBC HD")), channels)
        assertEquals(listOf("The National"), titles)
    }
}
