package io.github.axon07.channelbox.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Kinds of on-demand item. */
object VodKind {
    const val MOVIE = "movie"
    /** A show; its episodes are rows of kind [EPISODE] (M3U) or loaded from the provider (Xtream). */
    const val SERIES = "series"
    const val EPISODE = "episode"
}

/** A movie, a show or an episode. */
@Entity(
    tableName = "vod",
    // Lists are read straight off these indexes (newest first), so huge libraries open instantly.
    indices = [
        Index(value = ["kind", "added", "sortIndex"]),
        Index(value = ["kind", "category", "added", "sortIndex"]),
        Index(value = ["seriesKey"]),
    ],
)
data class VodEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val kind: String,
    val title: String,
    val category: String,
    val poster: String?,
    /** Null for an Xtream show (episodes are fetched when it's opened). */
    val url: String?,
    val headers: String?,
    /** Links a show and its episodes. For Xtream shows: the provider's series id. */
    val seriesKey: String?,
    val season: Int,
    val episode: Int,
    /** When the provider added it (epoch seconds), 0 if unknown. */
    val added: Long,
    /** Minus the position in the playlist (higher = earlier), to keep the provider's order. */
    val sortIndex: Int,
    val plot: String?,
    val rating: String?,
    val year: String?,
)

/** Where you stopped watching a movie or episode. */
@Entity(tableName = "vod_progress")
data class VodProgressEntity(
    @PrimaryKey val id: String,
    val position: Long,
    val duration: Long,
    val updated: Long,
    /** For an episode: the show's [VodEntity.id], so the show appears under "Continue watching". */
    val showId: String?,
)

data class VodCategory(val name: String, val count: Int)

/**
 * The library lists are read once per change of [TvRepository.vodVersion] rather than observed:
 * an observed query re-runs after every batch a reload writes, which on a list with millions of
 * entries keeps the box busy re-counting the whole table for the entire load.
 */
@Dao
interface VodDao {
    @Query("SELECT category AS name, COUNT(*) AS count FROM vod WHERE kind = :kind GROUP BY category ORDER BY MAX(sortIndex) DESC")
    suspend fun categories(kind: String): List<VodCategory>

    /** ZarkTV: favoritos de filmes e séries (lista de ids). */
    @Query("SELECT * FROM vod WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<VodEntity>

    @Query("SELECT COUNT(*) FROM vod WHERE kind = :kind")
    suspend fun count(kind: String): Int

    // Each list walks an index newest-first and stops at the limit, instead of sorting the whole table.
    @Query("SELECT * FROM vod WHERE kind = :kind ORDER BY added DESC, sortIndex DESC LIMIT :limit")
    suspend fun allItems(kind: String, limit: Int): List<VodEntity>

    @Query("SELECT * FROM vod WHERE kind = :kind AND category = :category ORDER BY added DESC, sortIndex DESC LIMIT :limit")
    suspend fun categoryItems(kind: String, category: String, limit: Int): List<VodEntity>

    @Query(
        """SELECT * FROM vod WHERE kind = :kind AND title LIKE '%' || :query || '%'
           ORDER BY added DESC, sortIndex DESC LIMIT :limit"""
    )
    suspend fun search(kind: String, query: String, limit: Int): List<VodEntity>

    /** Started but not finished, most recent first. */
    @Query(
        """SELECT vod.* FROM vod JOIN vod_progress p ON p.id = vod.id OR p.showId = vod.id
           WHERE vod.kind = :kind GROUP BY vod.id ORDER BY MAX(p.updated) DESC LIMIT 50"""
    )
    suspend fun continueWatching(kind: String): List<VodEntity>

    @Query("SELECT * FROM vod WHERE kind = 'episode' AND seriesKey = :seriesKey ORDER BY season, episode, sortIndex DESC")
    suspend fun episodes(seriesKey: String): List<VodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllBlocking(items: List<VodEntity>)

    @Query("DELETE FROM vod WHERE playlistId = :playlistId")
    fun deleteForPlaylistBlocking(playlistId: String)

    @Query("DELETE FROM vod WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /** Watch positions of a playlist's movies, episodes and shows. Call before [deleteForPlaylist]. */
    @Query(
        """DELETE FROM vod_progress WHERE id IN (SELECT id FROM vod WHERE playlistId = :playlistId)
           OR showId IN (SELECT id FROM vod WHERE playlistId = :playlistId)"""
    )
    suspend fun deleteProgressForPlaylist(playlistId: String)

    @Query("DELETE FROM vod WHERE playlistId NOT IN (:keep)")
    suspend fun deleteOtherPlaylists(keep: List<String>)

    @Query("SELECT * FROM vod_progress")
    fun observeProgress(): Flow<List<VodProgressEntity>>

    @Query("SELECT COUNT(*) FROM vod_progress p JOIN vod ON p.id = vod.id OR p.showId = vod.id WHERE vod.kind = :kind")
    suspend fun continueCount(kind: String): Int

    @Query("SELECT * FROM vod_progress WHERE id = :id")
    suspend fun progress(id: String): VodProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(p: VodProgressEntity)

    @Query("DELETE FROM vod_progress WHERE id = :id")
    suspend fun clearProgress(id: String)
}

/** Sorts playlist entries into live channels, movies and episodes. */
object VodClassifier {
    enum class Kind { LIVE, MOVIE, EPISODE }

    private val videoFiles = setOf(
        "mkv", "mp4", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg", "divx", "xvid", "3gp", "ogv", "vob",
    )

    /** "Show Name S01E02", "Show.Name.S1 E2 - Title", "Show: S01 E02" */
    private val episodePattern = Regex("""(?i)^(.*?)[\s._\-:|]*\bS(\d{1,2})\s*[._\- ]?\s*E(\d{1,4})\b(.*)$""")

    fun kind(url: String, name: String): Kind {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if ("/series/" in path) return Kind.EPISODE
        if ("/movie/" in path || "/movies/" in path) return Kind.MOVIE
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext in videoFiles) return if (episodePattern.containsMatchIn(name)) Kind.EPISODE else Kind.MOVIE
        return Kind.LIVE
    }

    data class EpisodeName(val show: String, val season: Int, val episode: Int, val rest: String)

    fun episodeName(name: String): EpisodeName? {
        val m = episodePattern.find(name.trim()) ?: return null
        val show = m.groupValues[1].replace('.', ' ').replace('_', ' ').trim().trimEnd('-', ':', '|').trim()
        return EpisodeName(
            show = show,
            season = m.groupValues[2].toIntOrNull() ?: 0,
            episode = m.groupValues[3].toIntOrNull() ?: 0,
            rest = m.groupValues[4].trim().trimStart('-', ':', '|', '.').trim(),
        )
    }

}

/**
 * Movie / episode ids include the playlist, so the same title in two playlists (e.g. one provider
 * added as both an M3U link and a login) is two rows – removing one playlist removes only its own.
 */
fun vodId(playlistId: String, url: String) = "$playlistId|$url"

/** Turns M3U file entries into movies, episodes and one show row per series, one entry at a time. */
class VodBuilder(private val playlistId: String) {
    private val shows = HashSet<String>()
    private var index = 0
    val isEmpty get() = index == 0

    fun add(p: ParsedChannel, kind: VodClassifier.Kind): List<VodEntity> {
        val i = index++
        val headers = StreamSpecs.encodeHeaders(p.headers)
        if (kind == VodClassifier.Kind.MOVIE) {
            return listOf(
                VodEntity(
                    id = vodId(playlistId, p.url), playlistId = playlistId, kind = VodKind.MOVIE, title = p.name, category = p.group,
                    poster = p.logo, url = p.url, headers = headers, seriesKey = null, season = 0, episode = 0,
                    added = 0, sortIndex = -i, plot = null, rating = null, year = null,
                )
            )
        }
        // Episodes without "S01E02" in the name are grouped by their group title.
        val parsed = VodClassifier.episodeName(p.name)
        val showName = parsed?.show?.takeIf { it.isNotBlank() } ?: p.group
        val key = "$playlistId:" + EpgMatcher.normalizeName(showName).ifEmpty { showName.lowercase() }
        val out = ArrayList<VodEntity>(2)
        if (shows.add(key)) {
            out += VodEntity(
                id = "show:$key", playlistId = playlistId, kind = VodKind.SERIES, title = showName,
                category = p.group, poster = p.logo, url = null, headers = null, seriesKey = key,
                season = 0, episode = 0, added = 0, sortIndex = -i, plot = null, rating = null, year = null,
            )
        }
        out += VodEntity(
            id = vodId(playlistId, p.url), playlistId = playlistId, kind = VodKind.EPISODE,
            title = parsed?.rest?.takeIf { it.isNotBlank() } ?: p.name,
            category = p.group, poster = p.logo, url = p.url, headers = headers, seriesKey = key,
            season = parsed?.season ?: 0, episode = parsed?.episode ?: (i + 1), added = 0, sortIndex = -i,
            plot = null, rating = null, year = null,
        )
        return out
    }
}

/**
 * Saves a playlist's movies & shows in batches while it downloads, replacing the old ones.
 * Blocking: call from an IO thread, never the main thread (Room refuses).
 */
class VodWriter(private val db: AppDatabase, private val playlistId: String) {
    private val batch = ArrayList<VodEntity>(BATCH)
    private var started = false
    var count = 0
        private set

    fun add(item: VodEntity) {
        batch += item
        count++
        if (batch.size >= BATCH) flush()
    }

    /**
     * New items replace old ones with the same id as they arrive; the old list stays visible
     * meanwhile. The ids written are noted in a side table, so [finish] can drop what's gone.
     */
    private fun flush() {
        val sql = db.openHelper.writableDatabase
        db.runInTransaction {
            if (!started) {
                sql.execSQL("CREATE TABLE IF NOT EXISTS vod_keep (playlistId TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (playlistId, id))")
                sql.execSQL("DELETE FROM vod_keep WHERE playlistId = ?", arrayOf(playlistId))
                started = true
            }
            if (batch.isNotEmpty()) {
                db.vod().insertAllBlocking(batch)
                // One prepared statement for the whole batch (thousands of rows a second on big lists).
                sql.compileStatement("INSERT OR IGNORE INTO vod_keep (playlistId, id) VALUES (?, ?)").use { st ->
                    for (item in batch) {
                        st.clearBindings()
                        st.bindString(1, playlistId)
                        st.bindString(2, item.id)
                        st.executeInsert()
                    }
                }
            }
        }
        batch.clear()
    }

    /** Writes what's left, then removes this playlist's movies & shows that weren't in the new list. */
    fun finish() {
        flush()
        val sql = db.openHelper.writableDatabase
        db.runInTransaction {
            // Watch positions saved under the old ids (before ids included the playlist) carry over.
            sql.execSQL(
                "UPDATE OR IGNORE vod_progress SET id = ? || '|' || id " +
                    "WHERE (? || '|' || id) IN (SELECT id FROM vod WHERE playlistId = ?)",
                arrayOf(playlistId, playlistId, playlistId),
            )
            sql.execSQL(
                "DELETE FROM vod WHERE playlistId = ? AND id NOT IN (SELECT id FROM vod_keep WHERE playlistId = ?)",
                arrayOf(playlistId, playlistId),
            )
            sql.execSQL("DELETE FROM vod_keep WHERE playlistId = ?", arrayOf(playlistId))
        }
    }

    private companion object {
        const val BATCH = 1_000
    }
}
