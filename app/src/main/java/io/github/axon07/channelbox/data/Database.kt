package io.github.axon07.channelbox.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels")
data class ChannelEntity(
    /** Stable key (the stream URL) so favourites survive a playlist refresh. */
    @PrimaryKey val id: String,
    val number: Int,
    val name: String,
    @ColumnInfo(name = "group_title") val groupTitle: String,
    val logoUrl: String?,
    val streamUrl: String,
    /** XMLTV channel id, lower-cased. Null when the guide has no match. */
    val epgId: String?,
    val catchupDays: Int = 0,
    /** Extra HTTP headers, "Name: value" per line (from #EXTVLCOPT / #KODIPROP / #EXTHTTP). */
    val headers: String? = null,
    val drmType: String? = null,
    val drmKey: String? = null,
    /** #KODIPROP manifest_type (mpd, hls…). */
    val mimeHint: String? = null,
    /** Catch-up mode and URL template (see [Catchup]). */
    val catchupType: String? = null,
    val catchupSource: String? = null,
    /** Which playlist (see [Playlist.id]) the channel came from. */
    val playlistId: String? = null,
)

object RecStatus {
    const val SCHEDULED = "scheduled"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    val ACTIVE = setOf(SCHEDULED, RECORDING)
}

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: String,
    val channelName: String,
    val title: String,
    // Copy of the stream details, so a recording still works if the playlist changes.
    val streamUrl: String,
    val headers: String?,
    val drmType: String?,
    val drmKey: String?,
    val mimeHint: String?,
    /** Planned start/stop, epoch millis. */
    val start: Long,
    val stop: Long,
    /** Start of the guide programme this recording is for (links it to the guide), if any. */
    val programStart: Long?,
    val filePath: String? = null,
    val status: String = RecStatus.SCHEDULED,
    val sizeBytes: Long = 0,
    val error: String? = null,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY start DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    /** Your order in Favourites (lowest first). */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
)

@Entity(tableName = "programs", indices = [Index(value = ["epgId", "start"]), Index(value = ["start"])])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val epgId: String,
    /** Epoch millis, UTC. */
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

@Dao
abstract class ChannelDao {
    @Query("SELECT * FROM channels ORDER BY number, name")
    abstract fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY number, name")
    abstract suspend fun getAll(): List<ChannelEntity>

    @Query("DELETE FROM channels")
    abstract suspend fun clear()

    /** Channels left behind by playlists that were removed or turned off (old rows count as "main"). */
    @Query("DELETE FROM channels WHERE IFNULL(playlistId, 'main') NOT IN (:keep)")
    abstract suspend fun deleteOtherPlaylists(keep: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    /**
     * ZarkTV: atualização sem apagar tudo — grava por cima os canais que vieram (mesmo id) e
     * remove só os que sumiram do servidor. A lista continua aparecendo enquanto atualiza.
     */
    @Transaction
    open suspend fun replaceAll(channels: List<ChannelEntity>) {
        val novos = channels.mapTo(HashSet()) { it.id }
        allIds().filter { it !in novos }.chunked(500).forEach { deleteIds(it) }
        channels.chunked(1000).forEach { insertAll(it) }
    }

    @Query("SELECT id FROM channels")
    abstract suspend fun allIds(): List<String>

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<String>)

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId OR (playlistId IS NULL AND :playlistId = 'main')")
    abstract suspend fun forPlaylist(playlistId: String): List<ChannelEntity>

    @Query("DELETE FROM channels WHERE playlistId = :playlistId OR (playlistId IS NULL AND :playlistId = 'main')")
    abstract suspend fun deleteForPlaylist(playlistId: String)

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    abstract suspend fun setEpgId(id: String, epgId: String)

    /** Favourites in your order (new ones at the end). */
    @Query("SELECT channelId FROM favorites ORDER BY position, rowid")
    abstract fun observeFavorites(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorite(favorite: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorites(favorites: List<FavoriteEntity>)

    @Query("SELECT IFNULL(MAX(position), 0) FROM favorites")
    abstract suspend fun lastFavoritePosition(): Int

    @Query("UPDATE favorites SET position = :position WHERE channelId = :id")
    abstract suspend fun setFavoritePosition(id: String, position: Int)

    @Query("DELETE FROM favorites WHERE channelId = :id")
    abstract suspend fun removeFavorite(id: String)

    @Query("DELETE FROM favorites WHERE channelId IN (:ids)")
    abstract suspend fun removeFavorites(ids: List<String>)

    @Query("DELETE FROM favorites")
    abstract suspend fun clearFavorites()

    @Query("SELECT channelId FROM favorites ORDER BY position, rowid")
    abstract suspend fun favoriteOrder(): List<String>
}

@Dao
interface ProgramDao {
    @Query("SELECT * FROM programs WHERE stop > :from AND start < :to ORDER BY start")
    fun observeWindow(from: Long, to: Long): Flow<List<ProgramEntity>>

    /** Guide search: programmes whose title contains [query], ending after [from]. */
    @Query("SELECT * FROM programs WHERE title LIKE '%' || :query || '%' AND stop > :from ORDER BY start LIMIT 300")
    suspend fun search(query: String, from: Long): List<ProgramEntity>

    /** Blocking on purpose: called from the streaming XMLTV parser on an IO thread. */
    @Insert
    fun insertBlocking(programs: List<ProgramEntity>)

    @Query("DELETE FROM programs")
    suspend fun clear()
}

@Database(
    entities = [
        ChannelEntity::class, FavoriteEntity::class, ProgramEntity::class, RecordingEntity::class,
        VodEntity::class, VodProgressEntity::class, OverrideEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun programs(): ProgramDao
    abstract fun recordings(): RecordingDao
    abstract fun vod(): VodDao
    abstract fun overrides(): OverrideDao

    companion object {
        /** v3: catch-up columns. Keeps favourites and recordings. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupType TEXT")
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupSource TEXT")
            }
        }

        /** v4: which playlist each channel came from. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN playlistId TEXT")
            }
        }

        /** v5: movies & shows, resume positions, and a time index for big guides. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vod` (`id` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `category` TEXT NOT NULL, `poster` TEXT, `url` TEXT, `headers` TEXT, " +
                        "`seriesKey` TEXT, `season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `added` INTEGER NOT NULL, " +
                        "`sortIndex` INTEGER NOT NULL, `plot` TEXT, `rating` TEXT, `year` TEXT, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_kind_category` ON `vod` (`kind`, `category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_seriesKey` ON `vod` (`seriesKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vod_progress` (`id` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`duration` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `showId` TEXT, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_programs_start` ON `programs` (`start`)")
            }
        }

        /** v6: list indexes for big movie/show libraries (lists no longer sort the whole table). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_vod_kind_category`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_kind_added_sortIndex` ON `vod` (`kind`, `added`, `sortIndex`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vod_kind_category_added_sortIndex` ON `vod` (`kind`, `category`, `added`, `sortIndex`)"
                )
                db.execSQL("UPDATE vod SET sortIndex = -sortIndex WHERE sortIndex > 0")
            }
        }

        /** v7: your own channel/group names, numbers, hidden and locked items. */
        /** v8: your order of favourites. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `overrides` (`id` TEXT NOT NULL, `name` TEXT, `hidden` INTEGER NOT NULL, " +
                        "`locked` INTEGER NOT NULL, `position` INTEGER, PRIMARY KEY(`id`))"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "channelbox.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
    }
}

/**
 * Your changes to a channel, group or movie/show category, kept across playlist reloads.
 * [id]: "ch:<channel id>", "grp:<group name>" or "vod:<kind>:<category>".
 */
@Entity(tableName = "overrides")
data class OverrideEntity(
    @PrimaryKey val id: String,
    /** Your name for it (null = the playlist's). */
    val name: String? = null,
    val hidden: Boolean = false,
    /** Needs the parental PIN. */
    val locked: Boolean = false,
    /** Channel: your channel number. Group: position in the groups menu. */
    val position: Int? = null,
) {
    val isEmpty: Boolean get() = name == null && !hidden && !locked && position == null
}

@Dao
interface OverrideDao {
    @Query("SELECT * FROM overrides")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<OverrideEntity>>

    @Query("SELECT * FROM overrides")
    suspend fun all(): List<OverrideEntity>

    @Query("DELETE FROM overrides")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(items: List<OverrideEntity>)

    @Query("DELETE FROM overrides WHERE id = :id")
    suspend fun delete(id: String)

    /** Undo every rename, number, hide and order change (locks are kept). */
    @Query("UPDATE overrides SET name = NULL, hidden = 0, position = NULL")
    suspend fun resetEdits()

    @Query("DELETE FROM overrides WHERE name IS NULL AND hidden = 0 AND locked = 0 AND position IS NULL")
    suspend fun deleteEmpty()
}
