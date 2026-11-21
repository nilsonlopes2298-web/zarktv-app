package io.github.axon07.channelbox.data

import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.TimeZone

/**
 * Streaming XMLTV reader. Works on guides with hundreds of thousands of programmes
 * without loading the whole file into memory.
 */
class XmlTvParser(private val parser: XmlPullParser) {

    fun interface ChannelCallback {
        fun onChannel(id: String, displayNames: List<String>)
    }

    fun interface ProgrammeCallback {
        fun onProgramme(channelId: String, start: Long, stop: Long, title: String, description: String?)
    }

    fun parse(onChannel: ChannelCallback, onProgramme: ProgrammeCallback) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(onChannel)
                    "programme" -> readProgramme(onProgramme)
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel(cb: ChannelCallback) {
        val id = parser.getAttributeValue(null, "id")
        val names = ArrayList<String>(2)
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.depth == depth) break
            if (e == XmlPullParser.START_TAG && parser.name == "display-name") {
                safeText()?.let { names += it }
            }
        }
        if (!id.isNullOrBlank()) cb.onChannel(id.trim(), names)
    }

    private fun readProgramme(cb: ProgrammeCallback) {
        val channel = parser.getAttributeValue(null, "channel")
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val stop = parseTime(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.depth == depth) break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title == null) title = safeText()
                    "desc" -> if (desc == null) desc = safeText()
                }
            }
        }
        if (channel.isNullOrBlank() || start == null || stop == null || stop <= start) return
        cb.onProgramme(channel.trim(), start, stop, title?.takeIf { it.isNotBlank() } ?: "No title", desc)
    }

    private fun safeText(): String? = try {
        parser.nextText()?.trim()
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Parses XMLTV times like "20261116183000 +0000" (offset optional, seconds optional). */
        fun parseTime(value: String?): Long? {
            val t = value?.trim() ?: return null
            val digits = t.takeWhile { it.isDigit() }
            if (digits.length < 12) return null
            fun num(a: Int, b: Int) = digits.substring(a, b).toInt()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                num(0, 4), num(4, 6) - 1, num(6, 8),
                num(8, 10), num(10, 12), if (digits.length >= 14) num(12, 14) else 0,
            )
            var millis = cal.timeInMillis
            val tz = t.substring(digits.length).trim()
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val hours = tz.substring(1, 3).toIntOrNull() ?: 0
                val minutes = tz.substring(3, 5).toIntOrNull() ?: 0
                val offset = (hours * 60L + minutes) * 60_000L
                millis -= if (tz[0] == '+') offset else -offset
            }
            return millis
        }
    }
}
