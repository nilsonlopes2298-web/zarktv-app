package io.github.axon07.channelbox.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** User agent used for every request that doesn't set its own (Settings → Network). */
object UserAgent {
    const val DEFAULT = "Channelbox/1.0 (Linux; Android) ExoPlayer"

    /** Name to user-agent string. Empty string = Channelbox default. */
    val PRESETS = linkedMapOf(
        "Channelbox (default)" to "",
        "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
        "Kodi" to "Kodi/21.0 (Linux; Android 12.0; Build) Android/12.0.0 Sys_CPU/aarch64 App_Bitness/64 Version/21.0",
        "Chrome (Android)" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Smart TV" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
    )

    @Volatile var current: String = DEFAULT

    fun apply(custom: String) {
        current = custom.ifBlank { DEFAULT }
    }

    fun presetName(value: String): String =
        PRESETS.entries.firstOrNull { it.value == value }?.key ?: "Custom"
}

/** DNS-over-HTTPS choices: name to (URL, bootstrap IPs). Gets around ISP DNS blocking. */
val DNS_PRESETS = linkedMapOf(
    "system" to ("System (your ISP)" to emptyList<String>()),
    "cloudflare" to ("https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")),
    "google" to ("https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")),
    "quad9" to ("https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")),
    "adguard" to ("https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15")),
    "custom" to ("" to emptyList()),
)

val DNS_LABELS = mapOf(
    "system" to "System (your ISP)",
    "cloudflare" to "Cloudflare 1.1.1.1",
    "google" to "Google 8.8.8.8",
    "quad9" to "Quad9",
    "adguard" to "AdGuard",
    "custom" to "Custom",
)

/**
 * The app's one HTTP client (playlists, guides, streams, logos, recordings, updates).
 * Proxy and encrypted DNS from [net] are applied here; changing them needs an app restart.
 */
fun createHttpClient(net: NetworkSettings = NetworkSettings()): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", UserAgent.current).build())
            } else {
                chain.proceed(req)
            }
        }

    // Proxy (HTTP or SOCKS5).
    if (net.proxyType != "none" && net.proxyHost.isNotBlank() && net.proxyPort in 1..65535) {
        val type = if (net.proxyType == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP
        builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(net.proxyHost, net.proxyPort)))
        if (net.proxyUser.isNotBlank()) {
            if (type == Proxy.Type.HTTP) {
                val credential = Credentials.basic(net.proxyUser, net.proxyPass)
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            } else {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(net.proxyUser, net.proxyPass.toCharArray())
                })
            }
        }
    }

    // Encrypted DNS (DNS-over-HTTPS), falling back to the system DNS if it's unreachable.
    if (net.dns != "system") {
        val preset = DNS_PRESETS[net.dns]
        val url = (if (net.dns == "custom") net.dnsCustomUrl else preset?.first).orEmpty().toHttpUrlOrNull()
        if (url != null) {
            val bootstrap = preset?.second.orEmpty().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            val doh = DnsOverHttps.Builder()
                .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build())
                .url(url)
                .apply { if (bootstrap.isNotEmpty()) bootstrapDnsHosts(bootstrap) }
                .includeIPv6(false)
                .build()
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    try {
                        doh.lookup(hostname)
                    } catch (e: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
            })
        }
    }
    return builder.build()
}

/**
 * Downloads [url] and hands the body stream to [block] on an IO thread.
 * Cancelling the coroutine aborts the transfer straight away; [timeoutSeconds] > 0 caps the whole call.
 */
suspend fun <T> OkHttpClient.download(url: String, timeoutSeconds: Long = 0, block: (InputStream) -> T): T =
    withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a valid web address: ${redact(url)}")
        }
        val client = if (timeoutSeconds > 0) newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build() else this@download
        val call = client.newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}${httpHint(response.code)}")
                val body = response.body ?: throw IOException("Empty response")
                block(body.byteStream())
            }
        } catch (e: IOException) {
            ensureActive() // cancelled on purpose: report cancellation, not an error
            if (e is java.io.InterruptedIOException) throw IOException("The server took too long to answer")
            throw e
        } finally {
            watcher.cancel()
        }
    }

private fun httpHint(code: Int): String = when (code) {
    401, 403 -> " – the server refused it (often because the account is busy, e.g. a channel is playing on a one-connection account; otherwise check the login)"
    404 -> " – the link wasn't found"
    in 500..599 -> " – the provider's server has a problem"
    else -> ""
}

/** Transparently un-gzips .gz guide files (detected by magic bytes, not file name). */
fun maybeGunzip(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input, 64 * 1024)
    buffered.mark(2)
    val b1 = buffered.read()
    val b2 = buffered.read()
    buffered.reset()
    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
}

/** Keeps usernames/passwords out of error messages shown on screen. */
fun redact(url: String): String = url
    .replace(Regex("(?i)(password|username)=[^&]*"), "$1=***")
    .substringBefore("/live/")
