package io.github.axon07.channelbox.data

import java.net.URLDecoder

/** DRM details from #KODIPROP lines (inputstream.adaptive.license_type / license_key). */
data class DrmSpec(
    /** "clearkey", "widevine" or "playready". */
    val type: String,
    /** License server URL, if the key is fetched from a server. */
    val licenseUrl: String? = null,
    val licenseHeaders: Map<String, String> = emptyMap(),
    /**
     * ClearKey keys given right in the playlist: base64url key-id → base64url key.
     * A key stored under [ANY_KID] is used for whatever key id the stream asks for.
     */
    val clearKeys: Map<String, String> = emptyMap(),
) {
    val clearKeyJson: String? get() = if (clearKeys.isEmpty()) null else StreamSpecs.clearKeyJwk(clearKeys)

    companion object {
        const val ANY_KID = "*"
    }
}

/** Everything needed to open one stream. */
data class StreamSpec(
    val url: String,
    val headers: Map<String, String>,
    val drm: DrmSpec?,
    /** Forced container type, or null to let the player work it out. */
    val mime: String?,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

object StreamSpecs {
    const val MIME_HLS = "application/x-mpegURL"
    const val MIME_DASH = "application/dash+xml"
    const val MIME_SS = "application/vnd.ms-sstr+xml"

    const val CLEARKEY = "clearkey"
    const val WIDEVINE = "widevine"
    const val PLAYREADY = "playready"

    fun from(channel: ChannelEntity) =
        parse(channel.streamUrl, decodeHeaders(channel.headers), channel.drmType, channel.drmKey, channel.mimeHint)

    fun from(recording: RecordingEntity) =
        parse(recording.streamUrl, decodeHeaders(recording.headers), recording.drmType, recording.drmKey, recording.mimeHint)

    /**
     * [rawUrl] may carry Kodi-style headers after a pipe:
     * `http://host/stream.mpd|User-Agent=Foo&Referer=http%3A%2F%2Fsite`
     */
    fun parse(
        rawUrl: String,
        extraHeaders: Map<String, String> = emptyMap(),
        drmType: String? = null,
        drmKey: String? = null,
        manifestType: String? = null,
    ): StreamSpec {
        val trimmed = rawUrl.trim()
        val pipe = trimmed.indexOf('|')
        val url = if (pipe >= 0) trimmed.substring(0, pipe).trim() else trimmed
        val headers = LinkedHashMap<String, String>(extraHeaders)
        if (pipe >= 0) headers += parseHeaderString(trimmed.substring(pipe + 1))
        return StreamSpec(url, headers, parseDrm(drmType, drmKey), detectMime(url, manifestType))
    }

    /** "User-Agent=Foo&Referer=bar" (values may be URL-encoded). */
    fun parseHeaderString(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        s.split('&').forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isNotEmpty()) out[name] = decode(value)
        }
        return out
    }

    private fun decode(v: String): String =
        if ('%' in v) runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v) else v

    /**
     * Works out the stream type from the manifest_type hint or the URL path.
     * Matches ".mpd" anywhere in the path, so "…/manifest.mpd/abc123" is still DASH.
     */
    fun detectMime(url: String, manifestType: String? = null): String? {
        when (manifestType?.trim()?.lowercase()) {
            "mpd", "dash" -> return MIME_DASH
            "hls", "m3u8" -> return MIME_HLS
            "ism", "smooth", "smoothstreaming", "mss" -> return MIME_SS
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            ".mpd" in path -> MIME_DASH
            ".m3u8" in path || path.endsWith(".m3u") -> MIME_HLS
            ".ism" in path && "manifest" in path -> MIME_SS
            else -> null
        }
    }

    fun parseDrm(type: String?, key: String?): DrmSpec? {
        val k = key?.trim().orEmpty()
        val t = type?.trim()?.lowercase().orEmpty()
        if (t.isEmpty() && k.isEmpty()) return null
        val kind = when {
            "clearkey" in t -> CLEARKEY
            "widevine" in t -> WIDEVINE
            "playready" in t -> PLAYREADY
            "clear" in t || "w3" in t -> CLEARKEY
            t.isEmpty() && !k.startsWith("http", ignoreCase = true) -> CLEARKEY
            else -> WIDEVINE
        }
        if (k.startsWith("http", ignoreCase = true)) {
            // "https://license.server/path|Header=Value&H2=V2|R{SSM}|"
            val parts = k.split('|')
            return DrmSpec(
                type = kind,
                licenseUrl = parts[0].trim(),
                licenseHeaders = parts.getOrNull(1)?.let { parseHeaderString(it) }.orEmpty(),
            )
        }
        if (kind != CLEARKEY || k.isEmpty()) return DrmSpec(kind)

        // ClearKey with the keys right in the playlist.
        val keys = parseClearKeys(k)
        return DrmSpec(CLEARKEY, clearKeys = keys)
    }

    /**
     * Accepts every format seen in IPTV playlists:
     *  - "kid:key" or "kid1:key1,kid2:key2" (hex or base64)
     *  - {"kid":"key", …} or a full JWK set {"keys":[{"kty":"oct","kid":"…","k":"…"}]}
     *  - just "key" (used for any key id)
     */
    fun parseClearKeys(text: String): Map<String, String> {
        val k = text.trim()
        val out = LinkedHashMap<String, String>()
        if (k.startsWith("{") && "\"keys\"" in k) {
            Regex("""\{[^{}]*\}""").findAll(k).forEach { obj ->
                val o = obj.value
                val kid = Regex(""""kid"\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1)
                val key = Regex(""""k"\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1)
                if (key != null) out[kid?.let { toBase64Url(it) } ?: DrmSpec.ANY_KID] = toBase64Url(key)
            }
        } else if (k.startsWith("{")) {
            Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(k).forEach {
                out[toBase64Url(it.groupValues[1])] = toBase64Url(it.groupValues[2])
            }
        } else {
            k.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                if (':' in part) out[toBase64Url(part.substringBefore(':'))] = toBase64Url(part.substringAfter(':'))
                else out[DrmSpec.ANY_KID] = toBase64Url(part)
            }
        }
        return out
    }

    /**
     * Builds the ClearKey license response for one request. Uses the key with the requested
     * id when we have it; otherwise falls back to the only (or first) key we were given –
     * playlists often list the right key under a different key id.
     */
    fun clearKeyResponse(request: String, keys: Map<String, String>): String {
        val requested = Regex(""""kids"\s*:\s*\[([^\]]*)]""").find(request)?.groupValues?.get(1)
            ?.let { list -> Regex(""""([^"]+)"""").findAll(list).map { toBase64Url(it.groupValues[1]) }.toList() }
            .orEmpty()
        if (requested.isEmpty()) return clearKeyJwk(keys.filterKeys { it != DrmSpec.ANY_KID }.ifEmpty { keys })
        val fallback = keys[DrmSpec.ANY_KID] ?: keys.values.firstOrNull()
        val answer = LinkedHashMap<String, String>()
        requested.forEach { kid -> (keys[kid] ?: fallback)?.let { answer[kid] = it } }
        return clearKeyJwk(answer)
    }

    /** Kodi 21 "inputstream.adaptive.drm" JSON → (license type, license key in the legacy format). */
    fun parseKodiDrmJson(json: String): Pair<String, String>? {
        val system = Regex(""""((?:com\.widevine\.alpha)|(?:org\.w3\.clearkey)|(?:com\.microsoft\.playready)|(?:com\.clearkey\.alpha))"""")
            .find(json)?.groupValues?.get(1) ?: return null
        val server = Regex(""""server_url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val headers = Regex(""""req_headers"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
        if (server != null) return system to (server + (headers?.let { "|$it" } ?: ""))
        val keyIds = Regex(""""keyids"\s*:\s*(\{[^}]*\})""").find(json)?.groupValues?.get(1)
        if (keyIds != null) return system to keyIds
        return system to ""
    }

    /** Builds the JSON Web Key set ExoPlayer's ClearKey support expects. */
    fun clearKeyJwk(pairs: Map<String, String>): String =
        pairs.filterKeys { it != DrmSpec.ANY_KID }.entries.joinToString(
            prefix = "{\"keys\":[",
            postfix = "],\"type\":\"temporary\"}",
            separator = ",",
        ) { (kid, key) -> "{\"kty\":\"oct\",\"k\":\"$key\",\"kid\":\"$kid\"}" }

    /** Hex (32 chars, dashes allowed) → base64url; anything else is treated as base64 already. */
    internal fun toBase64Url(value: String): String {
        val v = value.trim().replace("-", "").let { if (it.length == 32 && it.all { c -> c.isHexDigit() }) it else null }
        if (v != null) return base64Url(v.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        return value.trim().replace('+', '-').replace('/', '_').trimEnd('=')
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal fun base64Url(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(B64[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(B64[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    // Headers are stored in the database as "Name: value" lines.
    fun encodeHeaders(headers: Map<String, String>): String? =
        headers.takeIf { it.isNotEmpty() }?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }

    fun decodeHeaders(s: String?): Map<String, String> =
        s.orEmpty().lines().filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
}
