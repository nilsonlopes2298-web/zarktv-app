package io.github.axon07.channelbox.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore to a file: every setting (playlists and their logins included), favourites in
 * your order, and your channel & group changes (names, numbers, order, hidden, locked).
 * Recordings and the downloaded channel lists / guide aren't included – they reload by themselves.
 */
class Backup(private val context: Context, private val db: AppDatabase, private val settings: SettingsStore) {

    fun suggestedName(): String =
        "channelbox-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    suspend fun create(): String = withContext(Dispatchers.IO) {
        val favorites = db.channels().favoriteOrder()
        val overrides = db.overrides().all()
        buildJsonObject {
            put("app", "Channelbox")
            put("format", 1)
            put("created", System.currentTimeMillis())
            put("settings", settings.exportAll())
            put("favorites", buildJsonArray { favorites.forEach { add(JsonPrimitive(it)) } })
            put("overrides", buildJsonArray {
                overrides.forEach { o ->
                    add(buildJsonObject {
                        put("id", o.id)
                        o.name?.let { put("name", it) }
                        put("hidden", o.hidden)
                        put("locked", o.locked)
                        o.position?.let { put("position", it) }
                    })
                }
            })
        }.toString()
    }

    /** Replaces settings, favourites and channel changes with the backup's. */
    suspend fun restore(text: String) = withContext(Dispatchers.IO) {
        val root = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
        if (root == null || (root["app"] as? JsonPrimitive)?.content != "Channelbox") {
            throw IOException("This isn't a Channelbox backup")
        }
        (root["settings"] as? JsonObject)?.let { settings.importAll(it) }
        val favorites = (root["favorites"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val overrides = (root["overrides"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { o[k] !is JsonNull }?.content
            OverrideEntity(
                id = s("id") ?: return@mapNotNull null,
                name = s("name"),
                hidden = s("hidden")?.toBooleanStrictOrNull() ?: false,
                locked = s("locked")?.toBooleanStrictOrNull() ?: false,
                position = s("position")?.toIntOrNull(),
            )
        }.orEmpty()
        db.withTransaction {
            db.channels().clearFavorites()
            db.channels().addFavorites(favorites.mapIndexed { i, id -> FavoriteEntity(id, i) })
            db.overrides().clear()
            if (overrides.isNotEmpty()) db.overrides().put(overrides)
        }
    }

    suspend fun writeTo(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IOException("Couldn't write the file")
    }

    suspend fun readFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IOException("Couldn't read the file")
    }

    /**
     * For boxes without a file picker (Fire TV and others): saves to the Download folder
     * (or Channelbox's own folder on older Android). Returns where it went.
     */
    suspend fun saveToDownloads(text: String): String = withContext(Dispatchers.IO) {
        val name = suggestedName()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't save to Downloads")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            "Download/$name"
        } else {
            val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
            File(dir, name).writeText(text)
            "Android/data/${context.packageName}/files/backups/$name"
        }
    }

    /** The newest backup saved by [saveToDownloads], for boxes without a file picker. */
    suspend fun readLatestFromDownloads(): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cols = arrayOf(MediaStore.Downloads._ID)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("channelbox-backup%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }
        } else {
            File(context.getExternalFilesDir(null), "backups").listFiles()
                ?.filter { it.name.startsWith("channelbox-backup") }
                ?.maxByOrNull { it.lastModified() }?.readText()
        }
    }
}
