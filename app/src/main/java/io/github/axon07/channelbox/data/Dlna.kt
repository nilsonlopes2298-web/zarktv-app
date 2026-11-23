package io.github.axon07.channelbox.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * ZarkTV: transmitir para Smart TV pela rede (DLNA / UPnP), como a UniTV faz.
 * Procura TVs na mesma rede Wi-Fi (Samsung, LG, TV Box com DLNA…) e manda o endereço do vídeo
 * para a TV tocar sozinha — o celular fica livre.
 */
object Dlna {
    data class Tv(val nome: String, val controle: String)

    /** Procura por ~4 s as TVs que aceitam receber vídeo (AVTransport). */
    suspend fun procurar(ctx: Context, http: OkHttpClient): List<Tv> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val trava = wifi.createMulticastLock("zarktv-dlna").apply { setReferenceCounted(false); acquire() }
        val locais = LinkedHashSet<String>()
        try {
            MulticastSocket().use { sock ->
                sock.soTimeout = 1200
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n").toByteArray()
                val grupo = InetAddress.getByName("239.255.255.250")
                repeat(3) { sock.send(DatagramPacket(msg, msg.size, grupo, 1900)) }
                val fim = System.currentTimeMillis() + 4000
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < fim) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val txt = String(p.data, 0, p.length)
                    Regex("(?im)^location:\\s*(\\S+)").find(txt)?.groupValues?.get(1)?.let { locais += it }
                }
            }
        } catch (_: Exception) {
        } finally { runCatching { trava.release() } }
        val rapido = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        locais.mapNotNull { loc ->
            runCatching {
                val xml = rapido.newCall(Request.Builder().url(loc).build()).execute().use { it.body?.string() } ?: return@runCatching null
                val nome = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: "TV"
                // O bloco do serviço AVTransport e o endereço de controle dele.
                val servico = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                    .map { it.groupValues[1] }.firstOrNull { it.contains("AVTransport") } ?: return@runCatching null
                val ctrl = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL).find(servico)?.groupValues?.get(1)?.trim() ?: return@runCatching null
                val base = Regex("<URLBase>(.*?)</URLBase>").find(xml)?.groupValues?.get(1)?.trimEnd('/')
                    ?: Regex("^(https?://[^/]+)").find(loc)!!.value
                Tv(nome.replace("&amp;", "&"), if (ctrl.startsWith("http")) ctrl else base + (if (ctrl.startsWith("/")) ctrl else "/$ctrl"))
            }.getOrNull()
        }.distinctBy { it.controle }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(http: OkHttpClient, tv: Tv, acao: String, corpo: String): Boolean {
        val env = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$acao xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>$corpo</u:$acao></s:Body></s:Envelope>"""
        val req = Request.Builder().url(tv.controle)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$acao\"")
            .post(env.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use { it.isSuccessful }
    }

    /** Manda a TV tocar [url]. */
    suspend fun tocar(http: OkHttpClient, tv: Tv, url: String, titulo: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rapido = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
            runCatching { soap(rapido, tv, "Stop", "") }
            val meta = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${esc(titulo)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/*:*">${esc(url)}</res></item></DIDL-Lite>"""
            soap(rapido, tv, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>") &&
                soap(rapido, tv, "Play", "<Speed>1</Speed>")
        }.getOrDefault(false)
    }
}
