package io.github.axon07.channelbox.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.axon07.channelbox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class ReleaseInfo(
    /** e.g. "0.2.0" (no leading v). */
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

/** Checks GitHub Releases for a newer APK, downloads it and hands it to Android's installer. */
class Updater(private val context: Context, private val http: OkHttpClient) {

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** The newest published release that has an APK attached, or null if there is none. */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Channelbox/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // no releases yet
            if (!response.isSuccessful) throw IOException("GitHub answered HTTP ${response.code}")
            val json = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                ?: throw IOException("Unexpected answer from GitHub")
            val tag = json.str("tag_name") ?: return@withContext null
            val apk = (json["assets"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
                ?: return@withContext null
            ReleaseInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                title = json.str("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = json.str("body").orEmpty().trim(),
                apkUrl = apk.str("browser_download_url") ?: return@withContext null,
                apkSize = apk.str("size")?.toLongOrNull() ?: 0L,
            )
        }
    }

    fun isNewer(release: ReleaseInfo): Boolean = compareVersions(release.version, currentVersion) > 0

    private fun updatesDir(): File {
        // Android 7+ shares the file through a FileProvider; older versions need a world-readable location.
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.cacheDir else (context.externalCacheDir ?: context.cacheDir)
        return File(base, "updates").also { it.mkdirs() }
    }

    /** Downloads the APK, reporting progress 0–100. */
    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = updatesDir()
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "channelbox-${release.version}.apk")
        val request = Request.Builder().url(release.apkUrl)
            .header("User-Agent", "Channelbox/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) target.setReadable(true, false)
        target
    }

    /** Android 8+ asks once per app whether it may install other apps. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Opens Android's "Do you want to update this app?" screen. */
    fun install(apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        } else {
            Uri.fromFile(apk)
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        return if (v is JsonNull) null else v.content
    }

    companion object {
        /** Compares "1.2.10" style versions numerically; >0 if [a] is newer. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
