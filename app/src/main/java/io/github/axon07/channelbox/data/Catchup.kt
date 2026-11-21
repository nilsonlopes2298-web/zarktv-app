package io.github.axon07.channelbox.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Builds catch-up (archive) URLs so past programmes can be watched from the guide.
 *
 * Supported playlist modes (catchup="…" on #EXTINF or #EXTM3U):
 *  - default   – catchup-source is a full URL template
 *  - append    – catchup-source is appended to the live URL
 *  - shift / timeshift – adds ?utc=<start>&lutc=<now> to the live URL
 *  - flussonic / fs / flussonic-hls / flussonic-ts – archive URL derived from the live URL
 *  - xc – Xtream Codes /timeshift/ URL derived from the live URL
 *
 * Template tokens (both Kodi {…} and ${…} styles): ${start} {utc} {start}, ${end} {utcend} {end},
 * ${duration} {duration} {duration:60}, ${timestamp} ${now} {lutc} {now}, ${offset} {offset} {offset:60},
 * {Y} {m} {d} {H} {M} {S} (programme start), and {tz=Area/City} to set the time zone for {Y}…{S}.
 */
object Catchup {

    fun isAvailable(type: String?, source: String?, days: Int): Boolean {
        if (days <= 0) return false
        val t = type?.trim()?.lowercase().orEmpty()
        return t.isNotEmpty() && t != "disabled" || !source.isNullOrBlank()
    }

    /** Archive URL for a programme from [startMs] to [endMs], or null if the channel has no catch-up. */
    fun buildUrl(
        liveUrl: String,
        type: String?,
        source: String?,
        startMs: Long,
        endMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val t = type?.trim()?.lowercase().orEmpty()
        val src = source?.trim().orEmpty()
        // Keep Kodi-style "|headers" on the live URL at the end of the archive URL too.
        val pipe = liveUrl.indexOf('|')
        val url = if (pipe >= 0) liveUrl.substring(0, pipe) else liveUrl
        val headers = if (pipe >= 0) liveUrl.substring(pipe) else ""

        val template = when {
            t == "append" -> url + src
            t == "shift" || t == "timeshift" -> url + (if ('?' in url) "&" else "?") + "utc={utc}&lutc={lutc}"
            t.startsWith("flussonic") || t == "fs" -> flussonic(url, t == "flussonic-ts") ?: return null
            t == "xc" || t == "xtream" -> xtream(url) ?: return null
            src.isNotEmpty() && (src.startsWith("http", ignoreCase = true) || src.contains("://")) -> src
            src.isNotEmpty() -> url + src // a bare query fragment behaves like append
            t == "default" -> url + (if ('?' in url) "&" else "?") + "utc={utc}&lutc={lutc}"
            else -> return null
        }
        return format(template, startMs / 1000, endMs / 1000, nowMs / 1000) + headers
    }

    private val flussonicRegex = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""")

    private fun flussonic(url: String, forceTs: Boolean): String? {
        val m = flussonicRegex.find(url) ?: return null
        val (host, path, file) = m.destructured
        val query = m.groupValues[4]
        return when {
            forceTs || file == "mpegts" || file.endsWith(".ts") -> "$host/$path/timeshift_abs-{utc}.ts$query"
            file.endsWith(".m3u8") -> "$host/$path/${file.removeSuffix(".m3u8")}-{utc}-{duration}.m3u8$query"
            else -> "$host/$path/index-{utc}-{duration}.m3u8$query"
        }
    }

    // http://host:port[/live]/user/pass/123[.ts|.m3u8]
    private val xtreamRegex = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?$""")

    private fun xtream(url: String): String? {
        val m = xtreamRegex.find(url.substringBefore('?')) ?: return null
        val (host, user, pass, id) = m.destructured
        return "$host/timeshift/$user/$pass/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$id.ts"
    }

    private val tzToken = Regex("""\{tz=([^}]*)\}""")
    private val divided = Regex("""\$?\{(duration|offset):(\d+)\}""")

    fun format(template: String, start: Long, end: Long, now: Long): String {
        val zone = tzToken.find(template)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        var s = template.replace(tzToken, "")
        val duration = (minOf(end, now) - start).coerceAtLeast(0)
        val offset = (now - start).coerceAtLeast(0)
        s = divided.replace(s) { m ->
            val value = if (m.groupValues[1] == "duration") duration else offset
            (value / m.groupValues[2].toLong().coerceAtLeast(1)).toString()
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone(zone ?: "UTC")).apply { timeInMillis = start * 1000 }
        fun two(v: Int) = v.toString().padStart(2, '0')
        val replacements = linkedMapOf(
            "\${start}" to start.toString(), "{utc}" to start.toString(), "{start}" to start.toString(),
            "\${end}" to end.toString(), "{utcend}" to end.toString(), "{end}" to end.toString(),
            "\${duration}" to duration.toString(), "{duration}" to duration.toString(),
            "\${timestamp}" to now.toString(), "\${now}" to now.toString(), "{lutc}" to now.toString(), "{now}" to now.toString(),
            "\${offset}" to offset.toString(), "{offset}" to offset.toString(),
            "{Y}" to cal.get(Calendar.YEAR).toString(),
            "{m}" to two(cal.get(Calendar.MONTH) + 1),
            "{d}" to two(cal.get(Calendar.DAY_OF_MONTH)),
            "{H}" to two(cal.get(Calendar.HOUR_OF_DAY)),
            "{M}" to two(cal.get(Calendar.MINUTE)),
            "{S}" to two(cal.get(Calendar.SECOND)),
        )
        replacements.forEach { (k, v) -> s = s.replace(k, v) }
        return s
    }
}
