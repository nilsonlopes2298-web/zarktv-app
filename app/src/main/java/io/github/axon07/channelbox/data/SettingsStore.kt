package io.github.axon07.channelbox.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class SourceType { NONE, M3U, XTREAM, STALKER }

/**
 * One TV service: an M3U link, an Xtream Codes login or a Stalker portal (portal URL + MAC
 * address), plus an optional guide. A Stalker portal keeps its URL in [xtServer] and the optional
 * portal login in [xtUser] / [xtPass].
 */
data class Playlist(
    val id: String,
    val name: String = "",
    val type: SourceType = SourceType.M3U,
    val m3uUrl: String = "",
    /** Extra XMLTV guide URL(s) for this playlist, one per line or comma separated. */
    val epgUrl: String = "",
    val xtServer: String = "",
    val xtUser: String = "",
    val xtPass: String = "",
    val enabled: Boolean = true,
    /** Load the playlist's movies & shows (off = live TV only, much faster for huge lists). */
    val includeVod: Boolean = true,
    /** Stalker portal: the MAC address the provider registered, and optional box identity. */
    val mac: String = "",
    val serial: String = "",
    val deviceId1: String = "",
    val deviceId2: String = "",
) {
    fun stalkerAccount() = StalkerAccount(
        portalUrl = xtServer, mac = mac, login = xtUser, password = xtPass,
        serial = serial, deviceId1 = deviceId1, deviceId2 = deviceId2,
    )

    /** Name to show: the one the user gave, else the server's host name. */
    val displayName: String
        get() = name.trim().ifEmpty {
            val url = if (type == SourceType.XTREAM || type == SourceType.STALKER) xtServer else m3uUrl
            runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
                ?: "Playlist"
        }

    val summary: String
        get() = when (type) {
            SourceType.XTREAM -> "Xtream Codes · ${xtServer.trim()} · ${xtUser.trim()}"
            SourceType.STALKER -> "Stalker portal · ${xtServer.trim()} · ${StalkerClient.normalizeMac(mac)}"
            else -> "M3U · ${redact(m3uUrl.trim())}"
        }

    fun toJson() = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "type" to JsonPrimitive(type.name),
            "m3u" to JsonPrimitive(m3uUrl), "epg" to JsonPrimitive(epgUrl), "server" to JsonPrimitive(xtServer),
            "user" to JsonPrimitive(xtUser), "pass" to JsonPrimitive(xtPass), "enabled" to JsonPrimitive(enabled),
            "vod" to JsonPrimitive(includeVod), "mac" to JsonPrimitive(mac), "sn" to JsonPrimitive(serial),
            "dev1" to JsonPrimitive(deviceId1), "dev2" to JsonPrimitive(deviceId2),
        )
    )

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

        fun fromJson(o: JsonObject): Playlist? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = str("id").ifEmpty { return null }
            return Playlist(
                id = id, name = str("name"),
                type = runCatching { SourceType.valueOf(str("type")) }.getOrDefault(SourceType.M3U),
                m3uUrl = str("m3u"), epgUrl = str("epg"), xtServer = str("server"), xtUser = str("user"),
                xtPass = str("pass"), enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                includeVod = (o["vod"] as? JsonPrimitive)?.booleanOrNull ?: true,
                mac = str("mac"), serial = str("sn"), deviceId1 = str("dev1"), deviceId2 = str("dev2"),
            )
        }
    }
}

/** What the last Back press does (TV → guide → groups → this). */
enum class BackAction(val label: String) {
    ASK_EXIT("Press Back twice to exit"),
    OPEN_GUIDE("Never exit (set-top box)"),
    EXIT("Exit right away"),
}

/** Network settings that help when an ISP blocks or throttles IPTV. */
data class NetworkSettings(
    /** system, cloudflare, google, quad9, adguard, custom */
    val dns: String = "system",
    val dnsCustomUrl: String = "",
    /** none, http, socks5 */
    val proxyType: String = "none",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val vpnEnabled: Boolean = false,
    /** WireGuard .conf text. */
    val vpnConfig: String = "",
    /** false = only Channelbox uses the VPN; true = the whole box. */
    val vpnAllApps: Boolean = false,
    /** Empty = Channelbox's default. */
    val userAgent: String = "",
)

/** Where recordings are saved. */
data class StorageSettings(
    /** local, removable, smb, webdav */
    val type: String = "local",
    val removableIndex: Int = 0,
    val smbHost: String = "",
    val smbShare: String = "",
    val smbPath: String = "Channelbox",
    val smbUser: String = "",
    val smbPass: String = "",
    val smbDomain: String = "",
    val davUrl: String = "",
    val davUser: String = "",
    val davPass: String = "",
)

/** Choices for automatic reloading; 0 = off. */
val REFRESH_CHOICES = listOf(6, 12, 24, 48, 0)

fun refreshLabel(hours: Int) = if (hours <= 0) "Off (only when you choose Reload)" else "Every $hours hours"

data class AppSettings(
    /** Every TV service, in lineup order. Enabled ones are merged into one channel list. */
    val playlists: List<Playlist> = emptyList(),
    val startOnBoot: Boolean = false,
    /** Open live TV when the box wakes from sleep. */
    val startOnWake: Boolean = false,
    val invertChannelKeys: Boolean = false,
    /** Turn subtitles / closed captions on when a channel or video starts (off unless chosen). */
    val captionsByDefault: Boolean = false,
    /** App language ("en" = English). */
    val language: String = "pt-BR",
    val refreshHours: Int = 24, // ZarkTV: atualiza o conteúdo uma vez por dia
    val backAction: BackAction = BackAction.ASK_EXIT,
    val autoUpdateCheck: Boolean = true,
    /** Decode audio with FFmpeg even when the box has its own decoder (fixes silent channels on some boxes). */
    val preferSoftwareAudio: Boolean = false,
    /** Decode video in software instead of the box's hardware decoder. */
    val softwareVideo: Boolean = false,
    /** Dolby Vision: "auto" (when the TV shows it), "on" or "off" (play as HDR10). */
    val dolbyVision: String = "auto",
    /** Playback buffer: none, small, medium, large, xlarge. */
    val bufferSize: String = "medium",
    /** Send Dolby / DTS audio untouched to a receiver or soundbar that supports it. */
    val audioPassthrough: Boolean = true,
    /** Switch the TV's refresh rate to match the video (auto frame rate). */
    val autoFrameRate: Boolean = false,
    /** Picture size: 0 = fit, 1 = zoom (fill, crop edges), 2 = stretch. */
    val videoScale: Int = 0,
    /** Highest resolution to pick automatically (0 = no limit). */
    val maxQuality: Int = 0,
    /** Keep playing in a small window when you press Home. */
    val pipOnLeave: Boolean = false,
    /** Seconds without a key press before the guide/menus close back to TV; 0 = never. */
    val idleCloseSeconds: Int = 60,
    /** Days of TV guide to keep. Fewer days = faster loading with very big guides. */
    val guideDays: Int = 3,
    /** Parental PIN (hashed); null = no PIN. */
    val pinHash: String? = null,
    /** Ask for the PIN before Settings opens. */
    val pinForSettings: Boolean = false,
    val network: NetworkSettings = NetworkSettings(),
    val storage: StorageSettings = StorageSettings(),
    /** Jellyfin library for Movies & TV Shows. */
    val jellyfin: JellyfinSettings = JellyfinSettings(),
    /** Version the user chose "Skip this version" for. */
    val skippedVersion: String? = null,
    val lastChannelId: String? = null,
    /** Start playing the last channel when the app opens (otherwise open on the guide). */
    val playLastOnStart: Boolean = true,
    /** Show channel numbers beside the names in the TV guide. */
    val showChannelNumbers: Boolean = false,
    val lastRefresh: Long = 0L,
) {
    /** False until at least one playlist has been added (first-run setup). */
    val hasSource: Boolean get() = playlists.isNotEmpty()
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    companion object {
        /** Id given to the single source carried over from older versions (its channels keep working). */
        const val LEGACY_ID = "main"
    }

    private object Keys {
        val SOURCE = stringPreferencesKey("source_type")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val EPG_URL = stringPreferencesKey("epg_url")
        val XT_SERVER = stringPreferencesKey("xt_server")
        val XT_USER = stringPreferencesKey("xt_user")
        val XT_PASS = stringPreferencesKey("xt_pass")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val START_ON_WAKE = booleanPreferencesKey("start_on_wake")
        val INVERT_KEYS = booleanPreferencesKey("invert_channel_keys")
        val CAPTIONS_DEFAULT = booleanPreferencesKey("captions_by_default")
        val LANGUAGE = stringPreferencesKey("language")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val PLAY_LAST = booleanPreferencesKey("play_last_on_start")
        val SHOW_NUMBERS = booleanPreferencesKey("guide_show_numbers")
        val LAST_REFRESH = longPreferencesKey("last_refresh")
        val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        val BACK_ACTION = stringPreferencesKey("back_action")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
        val SOFTWARE_AUDIO = booleanPreferencesKey("prefer_software_audio")
        val SOFTWARE_VIDEO = booleanPreferencesKey("software_video")
        val DOLBY_VISION = stringPreferencesKey("dolby_vision")
        val BUFFER_SIZE = stringPreferencesKey("buffer_size")
        val PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
        val AFR = booleanPreferencesKey("auto_frame_rate")
        val VIDEO_SCALE = intPreferencesKey("video_scale")
        val MAX_QUALITY = intPreferencesKey("max_quality")
        val PIP = booleanPreferencesKey("pip_on_leave")
        val IDLE_CLOSE = intPreferencesKey("idle_close_seconds")
        val GUIDE_DAYS = intPreferencesKey("guide_days")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SETTINGS = booleanPreferencesKey("pin_settings")
        val DNS = stringPreferencesKey("net_dns")
        val DNS_URL = stringPreferencesKey("net_dns_url")
        val PROXY_TYPE = stringPreferencesKey("net_proxy_type")
        val PROXY_HOST = stringPreferencesKey("net_proxy_host")
        val PROXY_PORT = intPreferencesKey("net_proxy_port")
        val PROXY_USER = stringPreferencesKey("net_proxy_user")
        val PROXY_PASS = stringPreferencesKey("net_proxy_pass")
        val VPN_ON = booleanPreferencesKey("vpn_enabled")
        val VPN_CONFIG = stringPreferencesKey("vpn_config")
        val VPN_ALL = booleanPreferencesKey("vpn_all_apps")
        val USER_AGENT = stringPreferencesKey("net_user_agent")
        val ST_TYPE = stringPreferencesKey("st_type")
        val ST_REMOVABLE = intPreferencesKey("st_removable")
        val SMB_HOST = stringPreferencesKey("smb_host")
        val JF_ON = booleanPreferencesKey("jellyfin_on")
        val JF_SERVER = stringPreferencesKey("jellyfin_server")
        val JF_USER = stringPreferencesKey("jellyfin_user")
        val JF_PASS = stringPreferencesKey("jellyfin_pass")
        val JF_MODE = stringPreferencesKey("jellyfin_mode")
        val SMB_SHARE = stringPreferencesKey("smb_share")
        val SMB_PATH = stringPreferencesKey("smb_path")
        val SMB_USER = stringPreferencesKey("smb_user")
        val SMB_PASS = stringPreferencesKey("smb_pass")
        val SMB_DOMAIN = stringPreferencesKey("smb_domain")
        val DAV_URL = stringPreferencesKey("dav_url")
        val DAV_USER = stringPreferencesKey("dav_user")
        val DAV_PASS = stringPreferencesKey("dav_pass")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        val PLAYLISTS = stringPreferencesKey("playlists")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    /** The playlist list; older versions stored a single source in separate keys. */
    private fun Preferences.playlists(): List<Playlist> {
        this[Keys.PLAYLISTS]?.let { text ->
            return runCatching {
                (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(Playlist::fromJson) }
            }.getOrDefault(emptyList())
        }
        val type = runCatching { SourceType.valueOf(this[Keys.SOURCE] ?: "NONE") }.getOrDefault(SourceType.NONE)
        if (type == SourceType.NONE) return emptyList()
        return listOf(
            Playlist(
                id = LEGACY_ID, type = type, m3uUrl = this[Keys.M3U_URL].orEmpty(), epgUrl = this[Keys.EPG_URL].orEmpty(),
                xtServer = this[Keys.XT_SERVER].orEmpty(), xtUser = this[Keys.XT_USER].orEmpty(), xtPass = this[Keys.XT_PASS].orEmpty(),
            )
        )
    }

    private fun Preferences.toSettings() = AppSettings(
        playlists = playlists(),
        startOnBoot = this[Keys.START_ON_BOOT] ?: false,
        startOnWake = this[Keys.START_ON_WAKE] ?: false,
        invertChannelKeys = this[Keys.INVERT_KEYS] ?: false,
        captionsByDefault = this[Keys.CAPTIONS_DEFAULT] ?: false,
        language = this[Keys.LANGUAGE] ?: "pt-BR",
        refreshHours = this[Keys.REFRESH_HOURS] ?: 24,
        backAction = runCatching { BackAction.valueOf(this[Keys.BACK_ACTION] ?: "") }
            .getOrDefault(BackAction.ASK_EXIT),
        autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: true,
        preferSoftwareAudio = this[Keys.SOFTWARE_AUDIO] ?: false,
        softwareVideo = this[Keys.SOFTWARE_VIDEO] ?: false,
        dolbyVision = this[Keys.DOLBY_VISION] ?: "auto",
        bufferSize = this[Keys.BUFFER_SIZE] ?: "medium",
        audioPassthrough = this[Keys.PASSTHROUGH] ?: true,
        autoFrameRate = this[Keys.AFR] ?: false,
        videoScale = this[Keys.VIDEO_SCALE] ?: 0,
        maxQuality = this[Keys.MAX_QUALITY] ?: 0,
        pipOnLeave = this[Keys.PIP] ?: false,
        idleCloseSeconds = this[Keys.IDLE_CLOSE] ?: 60,
        guideDays = this[Keys.GUIDE_DAYS] ?: 3,
        pinHash = this[Keys.PIN_HASH],
        pinForSettings = this[Keys.PIN_SETTINGS] ?: false,
        network = NetworkSettings(
            dns = this[Keys.DNS] ?: "system",
            dnsCustomUrl = this[Keys.DNS_URL].orEmpty(),
            proxyType = this[Keys.PROXY_TYPE] ?: "none",
            proxyHost = this[Keys.PROXY_HOST].orEmpty(),
            proxyPort = this[Keys.PROXY_PORT] ?: 0,
            proxyUser = this[Keys.PROXY_USER].orEmpty(),
            proxyPass = this[Keys.PROXY_PASS].orEmpty(),
            vpnEnabled = this[Keys.VPN_ON] ?: false,
            vpnConfig = this[Keys.VPN_CONFIG].orEmpty(),
            vpnAllApps = this[Keys.VPN_ALL] ?: false,
            userAgent = this[Keys.USER_AGENT].orEmpty(),
        ),
        storage = StorageSettings(
            type = this[Keys.ST_TYPE] ?: "local",
            removableIndex = this[Keys.ST_REMOVABLE] ?: 0,
            smbHost = this[Keys.SMB_HOST].orEmpty(),
            smbShare = this[Keys.SMB_SHARE].orEmpty(),
            smbPath = this[Keys.SMB_PATH] ?: "Channelbox",
            smbUser = this[Keys.SMB_USER].orEmpty(),
            smbPass = this[Keys.SMB_PASS].orEmpty(),
            smbDomain = this[Keys.SMB_DOMAIN].orEmpty(),
            davUrl = this[Keys.DAV_URL].orEmpty(),
            davUser = this[Keys.DAV_USER].orEmpty(),
            davPass = this[Keys.DAV_PASS].orEmpty(),
        ),
        jellyfin = JellyfinSettings(
            enabled = this[Keys.JF_ON] ?: false,
            server = this[Keys.JF_SERVER].orEmpty(),
            user = this[Keys.JF_USER].orEmpty(),
            password = this[Keys.JF_PASS].orEmpty(),
            mode = this[Keys.JF_MODE] ?: "add",
        ),
        skippedVersion = this[Keys.SKIPPED_VERSION],
        lastChannelId = this[Keys.LAST_CHANNEL],
        playLastOnStart = this[Keys.PLAY_LAST] ?: true,
        showChannelNumbers = this[Keys.SHOW_NUMBERS] ?: false,
        lastRefresh = this[Keys.LAST_REFRESH] ?: 0L,
    )

    private suspend fun editPlaylists(change: (MutableList<Playlist>) -> Unit) {
        context.dataStore.edit { prefs ->
            val list = prefs.playlists().toMutableList()
            change(list)
            prefs[Keys.PLAYLISTS] = JsonArray(list.map { it.toJson() }).toString()
            prefs[Keys.LAST_REFRESH] = 0L
        }
    }

    /** Adds the playlist, or replaces the one with the same id. */
    suspend fun savePlaylist(p: Playlist) = editPlaylists { list ->
        val clean = p.copy(
            name = p.name.trim(), m3uUrl = p.m3uUrl.trim(), epgUrl = p.epgUrl.trim(),
            xtServer = p.xtServer.trim(), xtUser = p.xtUser.trim(),
        )
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = clean else list += clean
    }

    suspend fun removePlaylist(id: String) = editPlaylists { list -> list.removeAll { it.id == id } }

    suspend fun setPlaylistEnabled(id: String, on: Boolean) = editPlaylists { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(enabled = on)
    }

    /** Moves a playlist up (-1) or down (+1); order decides channel numbering. */
    suspend fun movePlaylist(id: String, delta: Int) = editPlaylists { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
    }

    suspend fun setStartOnBoot(on: Boolean) = context.dataStore.edit { it[Keys.START_ON_BOOT] = on }
    suspend fun setStartOnWake(on: Boolean) = context.dataStore.edit { it[Keys.START_ON_WAKE] = on }
    suspend fun setInvertChannelKeys(on: Boolean) = context.dataStore.edit { it[Keys.INVERT_KEYS] = on }
    suspend fun setCaptionsByDefault(on: Boolean) = context.dataStore.edit { it[Keys.CAPTIONS_DEFAULT] = on }
    suspend fun setLanguage(code: String) = context.dataStore.edit { it[Keys.LANGUAGE] = code }
    suspend fun setLastChannel(id: String) = context.dataStore.edit { it[Keys.LAST_CHANNEL] = id }
    suspend fun setPlayLastOnStart(on: Boolean) = context.dataStore.edit { it[Keys.PLAY_LAST] = on }
    suspend fun setShowChannelNumbers(on: Boolean) = context.dataStore.edit { it[Keys.SHOW_NUMBERS] = on }

    /** Every setting as JSON values (for a backup file). */
    suspend fun exportAll(): kotlinx.serialization.json.JsonObject {
        val prefs = context.dataStore.data.first().asMap()
        return kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in prefs) {
                val typed = when (v) {
                    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v) to "b"
                    is Int -> kotlinx.serialization.json.JsonPrimitive(v) to "i"
                    is Long -> kotlinx.serialization.json.JsonPrimitive(v) to "l"
                    is Float -> kotlinx.serialization.json.JsonPrimitive(v) to "f"
                    is Double -> kotlinx.serialization.json.JsonPrimitive(v) to "d"
                    is String -> kotlinx.serialization.json.JsonPrimitive(v) to "s"
                    is Set<*> -> kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }) to "ss"
                    else -> continue
                }
                put(k.name, kotlinx.serialization.json.buildJsonObject {
                    put("t", kotlinx.serialization.json.JsonPrimitive(typed.second))
                    put("v", typed.first)
                })
            }
        }
    }

    /** Replaces every setting with the ones from a backup ([exportAll]'s format). */
    suspend fun importAll(json: kotlinx.serialization.json.JsonObject) = context.dataStore.edit { p ->
        p.clear()
        for ((k, e) in json) {
            val o = e as? kotlinx.serialization.json.JsonObject ?: continue
            val t = (o["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val v = o["v"] ?: continue
            val prim = v as? kotlinx.serialization.json.JsonPrimitive
            when (t) {
                "b" -> prim?.content?.toBooleanStrictOrNull()?.let { p[booleanPreferencesKey(k)] = it }
                "i" -> prim?.content?.toIntOrNull()?.let { p[androidx.datastore.preferences.core.intPreferencesKey(k)] = it }
                "l" -> prim?.content?.toLongOrNull()?.let { p[androidx.datastore.preferences.core.longPreferencesKey(k)] = it }
                "f" -> prim?.content?.toFloatOrNull()?.let { p[androidx.datastore.preferences.core.floatPreferencesKey(k)] = it }
                "d" -> prim?.content?.toDoubleOrNull()?.let { p[androidx.datastore.preferences.core.doublePreferencesKey(k)] = it }
                "s" -> prim?.content?.let { p[stringPreferencesKey(k)] = it }
                "ss" -> (v as? kotlinx.serialization.json.JsonArray)?.let { arr ->
                    p[androidx.datastore.preferences.core.stringSetPreferencesKey(k)] =
                        arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
                }
            }
        }
    }
    suspend fun setRefreshHours(hours: Int) = context.dataStore.edit { it[Keys.REFRESH_HOURS] = hours }
    suspend fun setBackAction(action: BackAction) = context.dataStore.edit { it[Keys.BACK_ACTION] = action.name }
    suspend fun setPreferSoftwareAudio(on: Boolean) = context.dataStore.edit { it[Keys.SOFTWARE_AUDIO] = on }
    suspend fun setSoftwareVideo(on: Boolean) = context.dataStore.edit { it[Keys.SOFTWARE_VIDEO] = on }
    suspend fun setDolbyVision(mode: String) = context.dataStore.edit { it[Keys.DOLBY_VISION] = mode }
    suspend fun setBufferSize(size: String) = context.dataStore.edit { it[Keys.BUFFER_SIZE] = size }
    suspend fun setAudioPassthrough(on: Boolean) = context.dataStore.edit { it[Keys.PASSTHROUGH] = on }
    suspend fun setAutoFrameRate(on: Boolean) = context.dataStore.edit { it[Keys.AFR] = on }
    suspend fun setVideoScale(scale: Int) = context.dataStore.edit { it[Keys.VIDEO_SCALE] = scale }
    suspend fun setMaxQuality(height: Int) = context.dataStore.edit { it[Keys.MAX_QUALITY] = height }
    suspend fun setPipOnLeave(on: Boolean) = context.dataStore.edit { it[Keys.PIP] = on }
    suspend fun setIdleClose(seconds: Int) = context.dataStore.edit { it[Keys.IDLE_CLOSE] = seconds }
    suspend fun setGuideDays(days: Int) = context.dataStore.edit { it[Keys.GUIDE_DAYS] = days }
    suspend fun setPinHash(hash: String?) = context.dataStore.edit {
        if (hash == null) {
            it.remove(Keys.PIN_HASH)
            it[Keys.PIN_SETTINGS] = false
        } else it[Keys.PIN_HASH] = hash
    }
    suspend fun setPinForSettings(on: Boolean) = context.dataStore.edit { it[Keys.PIN_SETTINGS] = on }

    suspend fun saveNetwork(n: NetworkSettings) = context.dataStore.edit {
        it[Keys.DNS] = n.dns
        it[Keys.DNS_URL] = n.dnsCustomUrl.trim()
        it[Keys.PROXY_TYPE] = n.proxyType
        it[Keys.PROXY_HOST] = n.proxyHost.trim()
        it[Keys.PROXY_PORT] = n.proxyPort
        it[Keys.PROXY_USER] = n.proxyUser
        it[Keys.PROXY_PASS] = n.proxyPass
        it[Keys.VPN_ON] = n.vpnEnabled
        it[Keys.VPN_CONFIG] = n.vpnConfig
        it[Keys.VPN_ALL] = n.vpnAllApps
        it[Keys.USER_AGENT] = n.userAgent.trim()
    }

    suspend fun saveJellyfin(j: JellyfinSettings) = context.dataStore.edit {
        it[Keys.JF_ON] = j.enabled
        it[Keys.JF_SERVER] = j.server.trim()
        it[Keys.JF_USER] = j.user.trim()
        it[Keys.JF_PASS] = j.password
        it[Keys.JF_MODE] = j.mode
    }

    suspend fun saveStorage(st: StorageSettings) = context.dataStore.edit {
        it[Keys.ST_TYPE] = st.type
        it[Keys.ST_REMOVABLE] = st.removableIndex
        it[Keys.SMB_HOST] = st.smbHost.trim()
        it[Keys.SMB_SHARE] = st.smbShare.trim().trim('/', '\\')
        it[Keys.SMB_PATH] = st.smbPath.trim().trim('/', '\\')
        it[Keys.SMB_USER] = st.smbUser.trim()
        it[Keys.SMB_PASS] = st.smbPass
        it[Keys.SMB_DOMAIN] = st.smbDomain.trim()
        it[Keys.DAV_URL] = st.davUrl.trim().trimEnd('/')
        it[Keys.DAV_USER] = st.davUser.trim()
        it[Keys.DAV_PASS] = st.davPass
    }

    suspend fun setAutoUpdateCheck(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_UPDATE] = on }
    suspend fun setSkippedVersion(version: String) = context.dataStore.edit { it[Keys.SKIPPED_VERSION] = version }
    suspend fun setLastRefresh(time: Long) = context.dataStore.edit { it[Keys.LAST_REFRESH] = time }
}
