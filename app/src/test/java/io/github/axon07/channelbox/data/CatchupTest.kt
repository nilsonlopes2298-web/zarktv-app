package io.github.axon07.channelbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {
    // 2026-11-16 12:00:00 UTC, one hour long, watched at 15:00 UTC.
    private val start = 1_794_830_400_000L
    private val end = start + 3_600_000L
    private val now = start + 3 * 3_600_000L

    @Test
    fun defaultTemplateWithBothTokenStyles() {
        val url = Catchup.buildUrl(
            "http://h/live/1.m3u8", "default",
            "http://h/arch/1/\${start}-\${duration}.m3u8?lutc={lutc}&e={utcend}", start, end, now,
        )
        assertEquals("http://h/arch/1/1794830400-3600.m3u8?lutc=1794841200&e=1794834000", url)
    }

    @Test
    fun appendAndShift() {
        assertEquals(
            "http://h/live/1.ts?utc=1794830400&lutc=1794841200",
            Catchup.buildUrl("http://h/live/1.ts", "append", "?utc={utc}&lutc={lutc}", start, end, now),
        )
        assertEquals(
            "http://h/live/1.ts?token=x&utc=1794830400&lutc=1794841200",
            Catchup.buildUrl("http://h/live/1.ts?token=x", "shift", null, start, end, now),
        )
    }

    @Test
    fun flussonicHlsAndTs() {
        assertEquals(
            "http://f:8080/ch1/index-1794830400-3600.m3u8?token=t",
            Catchup.buildUrl("http://f:8080/ch1/index.m3u8?token=t", "flussonic", null, start, end, now),
        )
        assertEquals(
            "http://f:8080/ch1/timeshift_abs-1794830400.ts",
            Catchup.buildUrl("http://f:8080/ch1/mpegts", "fs", null, start, end, now),
        )
    }

    @Test
    fun xtreamUsesServerTimeZoneAndMinutes() {
        val template = "http://x:80/timeshift/u/p/{duration:60}/{Y}-{m}-{d}:{H}-{M}/123.ts{tz=America/Toronto}"
        assertEquals(
            "http://x:80/timeshift/u/p/60/2026-11-16:07-00/123.ts",
            Catchup.buildUrl("http://x:80/live/u/p/123.ts", "default", template, start, end, now),
        )
        assertEquals(
            "http://x:80/timeshift/u/p/60/2026-11-16:12-00/123.ts",
            Catchup.buildUrl("http://x:80/live/u/p/123.ts", "xc", null, start, end, now),
        )
    }

    @Test
    fun keepsPipeHeadersAndHandlesNoCatchup() {
        assertEquals(
            "http://h/a.ts?utc=1794830400&lutc=1794841200|User-Agent=X",
            Catchup.buildUrl("http://h/a.ts|User-Agent=X", "shift", null, start, end, now),
        )
        assertNull(Catchup.buildUrl("http://h/a.ts", null, null, start, end, now))
        assertTrue(Catchup.isAvailable("shift", null, 3))
        assertFalse(Catchup.isAvailable("shift", null, 0))
        assertFalse(Catchup.isAvailable(null, null, 7))
    }

    @Test
    fun ongoingProgrammeDurationIsCappedAtNow() {
        val url = Catchup.buildUrl("http://h/1.m3u8", "default", "http://h/a-{utc}-{duration}.m3u8", start, end, start + 600_000L)
        assertEquals("http://h/a-1794830400-600.m3u8", url)
    }

    @Test
    fun m3uReadsCatchupAttributesAndHeaderDefaults() {
        val playlist = """
            #EXTM3U catchup="shift" catchup-days="3"
            #EXTINF:-1 tvg-id="a",A
            http://h/a.ts
            #EXTINF:-1 catchup="default" catchup-source="http://h/b-{utc}.ts" catchup-days="7",B
            http://h/b.ts
        """.trimIndent()
        val (a, b) = M3uParser.parse(playlist.reader().buffered()).channels
        assertEquals("shift", a.catchupType)
        assertEquals(3, a.catchupDays)
        assertEquals("default", b.catchupType)
        assertEquals("http://h/b-{utc}.ts", b.catchupSource)
        assertEquals(7, b.catchupDays)
    }
}
