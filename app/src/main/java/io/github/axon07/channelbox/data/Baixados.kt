package io.github.axon07.channelbox.data

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

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

    // O próprio app baixa (com o mesmo acesso à internet do player). O gerenciador de downloads do
    // Android não servia: em alguns aparelhos ele não acha o endereço do servidor ZarkTV.
    private val escopo = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val andamento = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Começa a baixar [url] (já conferida). */
    fun baixar(ctx: Context, http: okhttp3.OkHttpClient, item: VodEntity, titulo: String, capa: String?, url: String): Boolean = runCatching {
        val ext = Regex("""\.(\w{2,4})(\?|$)""").find(url)?.groupValues?.get(1)?.lowercase()?.takeIf { it in setOf("mp4", "mkv", "avi", "ts", "mov") } ?: "mp4"
        val pasta = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        val arquivo = File(pasta, "zarktv-" + item.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext)
        salvar(ctx, listOf(Item(item.id, titulo, capa, item.kind, 0L, arquivo.absolutePath)) + lista(ctx).filter { it.id != item.id })
        andamento[item.id] = 0
        escopo.launch {
            val parte = File(arquivo.path + ".part")
            val ok = runCatching {
                val cliente = http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
                cliente.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val corpo = r.body ?: error("vazio")
                    val total = corpo.contentLength()
                    corpo.byteStream().use { inp ->
                        parte.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024); var feito = 0L; var n: Int
                            while (inp.read(buf).also { n = it } >= 0) {
                                if (!andamento.containsKey(item.id)) error("cancelado")
                                out.write(buf, 0, n); feito += n
                                if (total > 0) andamento[item.id] = (feito * 100 / total).toInt().coerceIn(0, 99)
                            }
                        }
                    }
                }
                parte.renameTo(arquivo)
            }.getOrDefault(false)
            if (ok) andamento.remove(item.id) else { parte.delete(); if (andamento.containsKey(item.id)) andamento[item.id] = -1 }
        }
        true
    }.getOrDefault(false)

    /** 0–100 enquanto baixa; 100 = pronto; -1 = falhou. */
    fun progresso(ctx: Context, it: Item): Int = andamento[it.id] ?: if (File(it.arquivo).exists()) 100 else -1

    fun apagar(ctx: Context, id: String) {
        val l = lista(ctx)
        andamento.remove(id)
        l.firstOrNull { it.id == id }?.let { x -> runCatching { File(x.arquivo).delete(); File(x.arquivo + ".part").delete() } }
        salvar(ctx, l.filter { it.id != id })
    }
}
