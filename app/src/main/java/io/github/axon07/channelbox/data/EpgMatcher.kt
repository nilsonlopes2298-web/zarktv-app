package io.github.axon07.channelbox.data

/**
 * Links playlist channels to XMLTV guide channels, in order of confidence:
 *  1. tvg-id equals the XMLTV channel id (ignoring case)
 *  2. tvg-id equals the XMLTV id after normalising ("CBC.ca" ~ "cbc ca")
 *  3. tvg-name / channel name equals one of the XMLTV display-names after normalising
 *     ("CA: CBC Toronto HD" ~ "CBC Toronto")
 *
 * XMLTV files list their <channel>s before the <programme>s, so channels are collected
 * first and matched when the first programme arrives; any later channels are matched as they come.
 */
class EpgMatcher(channels: List<Candidate>) {

    data class Candidate(val channelId: String, val epgId: String?, val names: List<String>)

    private data class XmlChannel(val id: String, val names: List<String>)

    private val all = channels.associateBy { it.channelId }
    private val unmatched = LinkedHashMap<String, Candidate>().apply { channels.forEach { put(it.channelId, it) } }
    /** Channels linked only by name; a later guide with a matching id takes them over. */
    private val weak = LinkedHashMap<String, Candidate>()
    private val pending = ArrayList<XmlChannel>()
    private var finalized = false

    /** playlist channel id → XMLTV channel id (lower-case). */
    val assigned = HashMap<String, String>()

    /** XMLTV ids (lower-case) we want programmes for. */
    val wanted = HashSet<String>()

    fun onChannel(id: String, displayNames: List<String>) {
        val x = XmlChannel(id.trim(), displayNames)
        if (finalized) match(listOf(x)) else pending += x
    }

    /** Returns true if programmes for [xmlChannelId] should be kept. */
    fun wants(xmlChannelId: String): Boolean {
        if (!finalized) finish()
        return xmlChannelId.trim().lowercase() in wanted
    }

    fun finish() {
        if (finalized) return
        finalized = true
        match(pending)
        pending.clear()
    }

    private fun match(xml: List<XmlChannel>) {
        // Id matches may also take over channels an earlier guide only linked by name.
        val byId = unmatched.values + weak.values

        // Pass 1: exact id.
        val byExact = HashMap<String, MutableList<Candidate>>()
        byId.forEach { c -> c.epgId?.let { byExact.getOrPut(it.trim().lowercase()) { mutableListOf() } += c } }
        xml.forEach { x -> byExact[x.id.lowercase()]?.toList()?.forEach { assign(it, x.id, byName = false) } }

        // Pass 2: normalised id.
        val byNormId = HashMap<String, MutableList<Candidate>>()
        (unmatched.values + weak.values).forEach { c ->
            c.epgId?.let { normalizeName(it) }?.takeIf { it.isNotEmpty() }?.let { byNormId.getOrPut(it) { mutableListOf() } += c }
        }
        xml.forEach { x -> byNormId[normalizeName(x.id)]?.toList()?.forEach { assign(it, x.id, byName = false) } }

        // Pass 3: names (only channels nothing has claimed yet).
        val byName = HashMap<String, MutableList<Candidate>>()
        unmatched.values.forEach { c ->
            c.names.map { normalizeName(it) }.filter { it.isNotEmpty() }.distinct()
                .forEach { byName.getOrPut(it) { mutableListOf() } += c }
        }
        xml.forEach { x ->
            x.names.forEach { n -> byName[normalizeName(n)]?.toList()?.forEach { assign(it, x.id, byName = true) } }
        }
    }

    /**
     * Called after each guide source. Channels whose guide entry got no programmes (anywhere so far)
     * become unmatched again, so the next guide can link them by id or name.
     */
    fun releaseEmpty(withProgrammes: Set<String>) {
        val empty = assigned.filterValues { it !in withProgrammes }.keys.toList()
        empty.forEach { id ->
            val key = assigned.remove(id)
            if (key != null && assigned.values.none { it == key }) wanted -= key
            weak.remove(id)
            all[id]?.let { unmatched[id] = it }
        }
        pending.clear()
        finalized = false
    }

    private fun assign(c: Candidate, xmlId: String, byName: Boolean) {
        val wasUnmatched = unmatched.remove(c.channelId) != null
        // Upgrade a name-only link to an id link; otherwise first match wins.
        val upgrade = !wasUnmatched && !byName && weak.remove(c.channelId) != null
        if (!wasUnmatched && !upgrade) return
        if (byName) weak[c.channelId] = c
        val key = xmlId.lowercase()
        assigned[c.channelId] = key
        wanted += key
    }

    companion object {
        private val countryPrefix = Regex("""^\s*[A-Za-z]{2,4}\s*[:|]\s*""")
        private val bracketed = Regex("""[\[(][^\])]*[\])]""")
        private val noise = Regex("""\b(hd|fhd|uhd|sd|4k|hevc|h265|h264|1080p|720p|50fps|60fps|backup|raw)\b""")

        /** "CA: CBC Toronto HD (1080p)" → "cbctoronto" */
        fun normalizeName(name: String): String =
            name.replace(countryPrefix, "")
                .replace(bracketed, " ")
                .lowercase()
                .replace(noise, " ")
                .filter { it.isLetterOrDigit() }
    }
}
