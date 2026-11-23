package io.github.axon07.channelbox.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ZarkTV: filmes e episódios baixados para ver sem internet (só filmes e séries; canal não grava).
 * Usa o gerenciador de downloads do Android (continua baixando com o app fechado e mostra o
 * progresso na barra de notificações). O arquivo fica na pasta do app — some se desinstalar.
 */
object Baixados {
    data class Item(val id: String, val titulo: String, val capa: String?, val kind: String, val downloadId: Long, val arquivo: String)

    private const val PREFS = "zarktv_baixados"

    fun lista(ctx: Context): List<Item> {
        val txt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("itens", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(txt)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Item(o.getString("id"), o.getString("titulo"), o.optString("capa").ifBlank { null }, o.optString("kind"), o.getLong("dl"), o.getString("arquivo"))
            }
        }.getOrDefault(emptyList())
    }

    private fun salvar(ctx: Context, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("titulo", it.titulo).put("capa", it.capa ?: "").put("kind", it.kind).put("dl", it.downloadId).put("arquivo", it.arquivo)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("itens", a.toString()).apply()
    }

    fun baixado(ctx: Context, id: String): Item? = lista(ctx).firstOrNull { it.id == id }

    /** Começa a baixar [url] (já conferida). */
    fun baixar(ctx: Context, item: VodEntity, titulo: String, capa: String?, url: String): Boolean = runCatching {
        val ext = Regex("""\.(\w{2,4})(\?|$)""").find(url)?.groupValues?.get(1)?.lowercase()?.takeIf { it in setOf("mp4", "mkv", "avi", "ts", "mov") } ?: "mp4"
        val nome = "zarktv-" + item.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(titulo)
            .setDescription("ZarkTV – baixando para ver sem internet")
            .addRequestHeader("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, nome)
        val id = dm.enqueue(req)
        val arquivo = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), nome).absolutePath
        salvar(ctx, listOf(Item(item.id, titulo, capa, item.kind, id, arquivo)) + lista(ctx).filter { it.id != item.id })
        true
    }.getOrDefault(false)

    /** 0–100 enquanto baixa; 100 = pronto; -1 = falhou. */
    fun progresso(ctx: Context, it: Item): Int {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(it.downloadId)).use { c ->
            if (c == null || !c.moveToFirst()) return if (File(it.arquivo).exists()) 100 else -1
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (st == DownloadManager.STATUS_SUCCESSFUL) return 100
            if (st == DownloadManager.STATUS_FAILED) return -1
            val feito = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return if (total > 0) (feito * 100 / total).toInt().coerceIn(0, 99) else 0
        }
    }

    fun apagar(ctx: Context, id: String) {
        val l = lista(ctx)
        l.firstOrNull { it.id == id }?.let { x ->
            runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(x.downloadId) }
            runCatching { File(x.arquivo).delete() }
        }
        salvar(ctx, l.filter { it.id != id })
    }
}
