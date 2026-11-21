package io.github.axon07.channelbox.system

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * "Use your phone": a tiny web page on the home network, open only while the add-playlist screen
 * is showing. The address includes a random code, so only someone who scans the QR code (or types
 * the address shown on the TV) can reach the form. Requests from outside the local network are
 * refused. What's sent is handed to [onReceived] on the main thread.
 */
class PhoneLink(private val onReceived: (Map<String, String>) -> Unit) {
    private val token: String = buildString {
        val rnd = SecureRandom()
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        repeat(10) { append(chars[rnd.nextInt(chars.length)]) }
    }
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    /** Starts the page; returns its address, or null when the box isn't on a local network. */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        val ss = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        server = ss
        thread(name = "phone-link", isDaemon = true) {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(socket) }; runCatching { socket.close() } }
            }
        }
        return "http://${ip.hostAddress}:${ss.localPort}/$token"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val from = socket.inetAddress
        if (!(from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress)) return
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var length = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        val out = socket.getOutputStream()
        fun respond(status: String, html: String) {
            val body = html.toByteArray()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(body)
            out.flush()
        }
        if (path != "/$token") {
            respond("404 Not Found", page("Not found", "<p>Scan the QR code on the TV again.</p>"))
            return
        }
        when (method) {
            "GET" -> respond("200 OK", FORM)
            "POST" -> {
                if (length !in 1..65_536) {
                    respond("400 Bad Request", page("Too big", "<p>That's more than a playlist link.</p>"))
                    return
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val fields = String(body, 0, read).split('&').mapNotNull {
                    val k = it.substringBefore('=', "")
                    if (k.isEmpty()) null
                    else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }.toMap()
                main.post { onReceived(fields) }
                respond("200 OK", page("Sent ✓", "<p>Look at your TV – Channelbox is loading the playlist.</p><p>You can close this page.</p>"))
            }
            else -> respond("405 Method Not Allowed", page("Not allowed", ""))
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8_192) return null
        }
    }

    companion object {
        /** The box's address on the home network (Wi-Fi or Ethernet), IPv4. */
        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        /** Black-on-white QR code for [text]. */
        fun qrCode(text: String, size: Int = 360): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val pixels = IntArray(m.width * m.height) { i ->
                if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
        }

        private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Channelbox</title>$STYLE</head>
<body><main><h1>$title</h1>$body</main></body></html>"""

        private const val STYLE = """<style>
body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0b0f14;color:#f2f5f8}
main{max-width:480px;margin:0 auto;padding:24px 18px}
h1{font-size:22px;margin:4px 0 6px}p{color:#a3afbf;font-size:14px;line-height:1.4}
label{display:block;font-size:13px;color:#a3afbf;margin:14px 0 6px}
input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2e3946;background:#18202a;color:#fff;font-size:16px}
.tabs{display:flex;gap:8px;margin:16px 0 4px}.tabs label{flex:1;margin:0;text-align:center;padding:10px;border-radius:10px;
border:1px solid #2e3946;color:#f2f5f8;font-size:14px}input[type=radio]{display:none}
#m3u:checked~.tabs label[for=m3u],#xt:checked~.tabs label[for=xt],#st:checked~.tabs label[for=st]{background:#3d8bfd;border-color:#3d8bfd}
.xt,.st{display:none}#xt:checked~.xt{display:block}#xt:checked~.m3u{display:none}#st:checked~.st{display:block}#st:checked~.m3u{display:none}
button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:12px;background:#3d8bfd;color:#fff;font-size:16px;font-weight:600}
</style>"""

        private val FORM = page(
            "Add a playlist",
            """<p>Fill this in and press Send – it goes straight to your TV. Nothing leaves your home network.</p>
<form method="post">
<input type="radio" name="type" id="m3u" value="m3u" checked><input type="radio" name="type" id="xt" value="xtream"><input type="radio" name="type" id="st" value="stalker">
<div class="tabs"><label for="m3u">M3U link</label><label for="xt">Xtream Codes</label><label for="st">Stalker (MAC)</label></div>
<label>Name (optional)</label><input name="name" autocomplete="off">
<div class="m3u"><label>Playlist URL</label><input name="url" inputmode="url" autocomplete="off" autocapitalize="off"></div>
<div class="xt"><label>Server (e.g. http://example.com:8080)</label><input name="server" inputmode="url" autocomplete="off" autocapitalize="off">
<label>Username</label><input name="user" autocomplete="off" autocapitalize="off">
<label>Password</label><input name="pass" type="password" autocomplete="off"></div>
<div class="st"><label>Portal URL (e.g. http://example.com:8080/c/)</label><input name="portal" inputmode="url" autocomplete="off" autocapitalize="off">
<label>MAC address (e.g. 00:1A:79:12:34:56)</label><input name="mac" autocomplete="off" autocapitalize="characters">
<label>Username – only if your provider gave you one</label><input name="suser" autocomplete="off" autocapitalize="off">
<label>Password – only if your provider gave you one</label><input name="spass" type="password" autocomplete="off"></div>
<label>TV guide (XMLTV) URL – optional</label><input name="epg" inputmode="url" autocomplete="off" autocapitalize="off">
<button type="submit">Send to TV</button></form>"""
        )
    }
}
