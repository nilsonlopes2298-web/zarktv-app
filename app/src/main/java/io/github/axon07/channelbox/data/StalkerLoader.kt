package io.github.axon07.channelbox.data

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Turns what a Stalker portal returns into Channelbox channels, guide, movies and shows. */
object StalkerLoader {

    /** The stable id of a portal channel (favourites, locks and edits stay attached across reloads). */
    fun channelId(playlistId: String, portalId: String) = "stalker:$playlistId:itv:$portalId"

    /** The portal's own id of a channel saved by [channels], or null for other channels. */
    fun portalId(channelId: String, playlistId: String): String? =
        channelId.removePrefix("stalker:$playlistId:itv:").takeIf { it != channelId }

    fun guideUrl(playlistId: String) = "$GUIDE_SCHEME$playlistId"
    fun isGuideUrl(url: String) = url.startsWith(GUIDE_SCHEME)
    fun guidePlaylist(url: String) = url.removePrefix(GUIDE_SCHEME)

    /** Live channels, with their group names. */
    fun channels(client: StalkerClient, playlistId: String, onProgress: (Int) -> Unit): List<ParsedChannel> {
        val genres = runCatching { client.genres() }.getOrDefault(emptyMap())
        return client.channels(onProgress).map { ch ->
            ParsedChannel(
                id = channelId(playlistId, ch.id),
                name = ch.name,
                number = ch.number,
                group = ch.genreId?.let { genres[it] } ?: "Uncategorized",
                logo = ch.logo,
                url = StalkerLinks.make(playlistId, "itv", ch.cmd, ch.needsLink),
                // The portal's guide is stored under the channel's XMLTV id when it has one, so an
                // extra XMLTV guide you add for the playlist matches the same channels.
                epgId = ch.xmltvId ?: "stalker:$playlistId:${ch.id}",
                catchupDays = 0,
            )
        }
    }

    /**
     * The portal's TV guide for its channels, sent to [onProgramme] as (guide id, programme).
     * Uses the whole-guide request; for portals without it, the next few programmes of up to
     * [SHORT_GUIDE_CHANNELS] channels instead. Returns false if the portal gave no guide at all.
     */
    fun guide(
        client: StalkerClient,
        playlistId: String,
        channels: List<ChannelEntity>,
        hours: Int,
        onProgramme: (String, ProgramEntity) -> Unit,
    ): Boolean {
        val keys = HashMap<String, String>()
        channels.forEach { ch -> portalId(ch.id, playlistId)?.let { id -> ch.epgId?.let { keys[id] = it } } }
        if (keys.isEmpty()) return false
        val full = client.guide(hours) { channel, start, stop, title, desc ->
            keys[channel]?.let { key -> onProgramme(key, ProgramEntity(epgId = key, start = start, stop = stop, title = title, description = desc)) }
        }
        if (full) return true
        val pool = Executors.newFixedThreadPool(4)
        var any = false
        try {
            val jobs = keys.entries.take(SHORT_GUIDE_CHANNELS).map { (portalId, key) ->
                Callable { key to runCatching { client.shortGuide(portalId) }.getOrDefault(emptyList()) }
            }
            pool.invokeAll(jobs).forEach { f ->
                val (key, list) = f.get()
                list.forEach { p ->
                    any = true
                    onProgramme(key, ProgramEntity(epgId = key, start = p.start, stop = p.stop, title = p.title, description = p.description))
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return any
    }

    /** Movies and shows, category by category. A category that fails is skipped. */
    fun vod(client: StalkerClient, playlistId: String, add: (VodEntity) -> Unit, onProgress: (Int) -> Unit) {
        var n = 0
        for (type in listOf("vod", "series")) {
            val categories = try {
                client.vodCategories(type)
            } catch (e: StalkerException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            // "All" repeats every other category: only use it when it's the only one.
            val usable = categories.filterKeys { it != "*" }.ifEmpty { categories }
            for ((categoryId, categoryName) in usable) {
                val items = try {
                    client.vodItems(type, categoryId)
                } catch (e: StalkerException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                val category = if (categoryId == "*") (if (type == "vod") "Movies" else "TV Shows") else categoryName
                items.forEach { item ->
                    add(entity(playlistId, type, item, category, -n))
                    if (++n % 500 == 0) onProgress(n)
                }
            }
        }
        onProgress(n)
    }

    private fun entity(playlistId: String, type: String, item: StalkerClient.VodItem, category: String, sortIndex: Int): VodEntity {
        val show = type == "series" || item.isSeries
        return VodEntity(
            id = "stalker:$playlistId:$type:${item.id}",
            playlistId = playlistId,
            kind = if (show) VodKind.SERIES else VodKind.MOVIE,
            title = item.name,
            category = category,
            poster = item.poster,
            url = if (show || item.cmd == null) null else StalkerLinks.make(playlistId, "vod", item.cmd, item.needsLink),
            headers = null,
            seriesKey = if (show) showKey(type, item) else null,
            season = 0, episode = 0,
            added = item.added,
            sortIndex = sortIndex,
            plot = item.description,
            rating = item.rating,
            year = item.year,
        )
    }

    /** True for a show saved by [vod] (its episodes come from [episodes]). */
    fun isShow(show: VodEntity) = show.id.startsWith("stalker:") && show.kind == VodKind.SERIES

    /** The episodes of a show, asked from the portal when it's opened. */
    fun episodes(client: StalkerClient, show: VodEntity): List<VodEntity> {
        val (type, item) = parseShowKey(show.seriesKey ?: return emptyList()) ?: return emptyList()
        return client.episodes(type, item).mapIndexed { i, ep ->
            VodEntity(
                id = "${show.id}:${ep.season}:${ep.episode}:${ep.cmd.hashCode()}",
                playlistId = show.playlistId, kind = VodKind.EPISODE, title = ep.title, category = "",
                poster = show.poster,
                url = StalkerLinks.make(show.playlistId, "vod", ep.cmd, if (ep.series != null) true else null, ep.series),
                headers = null, seriesKey = show.seriesKey, season = ep.season, episode = ep.episode,
                added = 0, sortIndex = i, plot = null, rating = null, year = null,
            )
        }
    }

    // What's needed to list a show's episodes later: type | id | listed episode numbers | link.
    private fun showKey(type: String, item: StalkerClient.VodItem) =
        listOf(type, item.id, item.episodes.joinToString(","), URLEncoder.encode(item.cmd.orEmpty(), "UTF-8")).joinToString("|")

    private fun parseShowKey(key: String): Pair<String, StalkerClient.VodItem>? {
        val parts = key.split('|')
        if (parts.size < 4) return null
        val cmd = URLDecoder.decode(parts[3], "UTF-8").takeIf { it.isNotEmpty() }
        val episodes = parts[2].split(',').mapNotNull { it.trim().toIntOrNull() }
        return parts[0] to StalkerClient.VodItem(
            id = parts[1], name = "", cmd = cmd, poster = null, description = null, year = null, rating = null,
            added = 0, isSeries = true, episodes = episodes, needsLink = null,
        )
    }

    private const val GUIDE_SCHEME = "stalker-epg://"
    private const val SHORT_GUIDE_CHANNELS = 300
}
