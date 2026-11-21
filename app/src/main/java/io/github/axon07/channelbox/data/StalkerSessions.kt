package io.github.axon07.channelbox.data

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * One signed-in [StalkerClient] per Stalker playlist, shared by loading, playing and recording,
 * so the portal sees one box rather than a new sign-in for every channel change. Also sends the
 * portal's keep-alive while something from it was played in the last few hours – some providers
 * stop serving streams to a box that goes quiet.
 */
class StalkerSessions(
    private val http: OkHttpClient,
    /** The current playlists (read when a link has to be resolved). */
    private val playlists: () -> List<Playlist>,
) {
    private val clients = ConcurrentHashMap<String, StalkerClient>()
    @Volatile private var lastPlayed = 0L
    @Volatile private var watchdog: Thread? = null

    fun client(p: Playlist): StalkerClient {
        val account = p.stalkerAccount()
        clients[p.id]?.let { if (it.account == account) return it }
        return StalkerClient(http, account).also { clients[p.id] = it }
    }

    /** Forgets a playlist's sign-in (it was edited or removed). */
    fun forget(playlistId: String) {
        clients.remove(playlistId)
    }

    /** Turns a saved "stalker://" link into one that plays. Blocks: call off the main thread. */
    fun resolve(url: String): StreamSpec {
        val link = StalkerLinks.parse(url) ?: throw IOException("Not a portal link")
        val p = playlists().firstOrNull { it.id == link.playlistId && it.type == SourceType.STALKER }
            ?: throw StalkerException("This portal isn't in your playlists any more")
        val client = client(p)
        val playable = client.playable(link.type, link.cmd, link.needsLink, link.series)
        lastPlayed = System.currentTimeMillis()
        startWatchdog()
        return StreamSpecs.parse(playable.url, playable.headers)
    }

    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread({
            while (System.currentTimeMillis() - lastPlayed < KEEP_ALIVE_FOR_MS) {
                val wait = clients.values.minOfOrNull { it.watchdogSeconds } ?: 120
                try {
                    Thread.sleep(wait * 1000L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                clients.values.forEach { c -> if (c.endpoint != null) runCatching { c.watchdog() } }
            }
        }, "stalker-watchdog").apply { isDaemon = true; start() }
    }

    companion object {
        private const val KEEP_ALIVE_FOR_MS = 4 * 3_600_000L
    }
}

/** The "stalker://" links of Stalker channels, movies and episodes are resolved here when played. */
object StreamResolver {
    @Volatile var stalker: StalkerSessions? = null

    fun needsResolving(url: String) = url.startsWith(StalkerLinks.SCHEME)

    /**
     * The spec to actually open: unchanged for normal links; for portal links a fresh link from
     * the portal (with the headers it needs). Blocks: call off the main thread.
     */
    fun resolve(spec: StreamSpec): StreamSpec {
        if (!needsResolving(spec.url)) return spec
        val sessions = stalker ?: throw IOException("Portal links can't be played yet")
        val real = sessions.resolve(spec.url)
        return real.copy(headers = spec.headers + real.headers, drm = spec.drm ?: real.drm, mime = spec.mime ?: real.mime)
    }
}
