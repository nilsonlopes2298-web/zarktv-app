package io.github.axon07.channelbox.ui

import io.github.axon07.channelbox.data.JellyfinSettings
import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.axon07.channelbox.BuildConfig
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.SettingsStore
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.DNS_LABELS
import io.github.axon07.channelbox.data.REFRESH_CHOICES
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import io.github.axon07.channelbox.data.UserAgent
import io.github.axon07.channelbox.data.redact
import io.github.axon07.channelbox.data.refreshLabel
import io.github.axon07.channelbox.system.SetTopBox
import io.github.axon07.channelbox.system.VpnManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class SettingsCategory(val title: String, val blurb: String) {
    SOURCE("Playlist & guide", "Your TV service and programme guide"),
    PLAYBACK("Playback", "Picture, sound, quality and the remote"),
    NETWORK("Network & VPN", "VPN, proxy and private DNS"),
    RECORDINGS("Recordings", "Where recordings are saved"),
    STARTUP("Start-up & exit", "Make it behave like a cable box"),
    LANGUAGE("Language", "The language Channelbox uses"),
    PARENTAL("Parental & hidden", "PIN, locked and hidden channels"),
    UPDATES("Updates", "Keep Channelbox up to date"),
    ABOUT("About", "Version and this device"),
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.SOURCE -> AppIcons.List
    SettingsCategory.PLAYBACK -> AppIcons.Play
    SettingsCategory.NETWORK -> AppIcons.Shield
    SettingsCategory.RECORDINGS -> AppIcons.Record
    SettingsCategory.STARTUP -> AppIcons.Power
    SettingsCategory.LANGUAGE -> AppIcons.Globe
    SettingsCategory.PARENTAL -> AppIcons.Lock
    SettingsCategory.UPDATES -> AppIcons.Refresh
    SettingsCategory.ABOUT -> AppIcons.Info
}

/** Sub-pages with text fields, opened from a setting card. */
private enum class Page(val title: String) {
    VPN("WireGuard VPN"), PROXY("Proxy"), DNS("Encrypted DNS"), USER_AGENT("User agent"),
    SMB("Network share (SMB)"), WEBDAV("WebDAV folder"), JELLYFIN("Jellyfin"),
    // Pick-one lists
    SCALE("Picture size"), QUALITY("Maximum quality"), BUFFER("Buffer size"), REFRESH("Reload automatically"),
    BACK("Back button on the groups menu"), STORAGE("Save recordings to"), IDLE("Back to TV when idle"), GUIDE_DAYS("Days of TV guide"),
    PLAYLIST("Playlist"),
    MOVIE_CATEGORIES("Movie categories"), SHOW_CATEGORIES("TV show categories"),
}

/**
 * Two panes: categories on the left, that category's settings on the right.
 * Moving through the categories with the remote shows each one; ▶ goes into its settings.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    refreshState: RefreshState,
    channelCount: Int,
    vm: TvViewModel,
    onEditSource: (String?) -> Unit,
    onOpenRecordings: () -> Unit,
    onExit: () -> Unit,
    /** Opens the channel & group organiser. */
    onOrganise: () -> Unit = {},
    /** Open on Network & VPN (from first-time setup, when the ISP blocks the playlist). */
    openNetwork: Boolean = false,
    /** ZarkTV "Minha conta": abre direto em "parental" (senha de adulto) ou "playback" (player). */
    openSection: String? = null,
) {
    var category by rememberSaveable {
        mutableStateOf(
            when {
                openNetwork -> SettingsCategory.NETWORK
                openSection == "parental" -> SettingsCategory.PARENTAL
                openSection == "playback" -> SettingsCategory.PLAYBACK
                else -> SettingsCategory.SOURCE
            }
        )
    }
    var page by remember { mutableStateOf<Page?>(null) }
    /** Playlist shown on the PLAYLIST page. */
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    // Section to come back to when a sub-page closes. While set, stray focus doesn't switch sections.
    var returnTo by remember { mutableStateOf<SettingsCategory?>(null) }
    val categoryFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    // With a remote, start on the first category. With touch/mouse nothing needs focus.
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    LaunchedEffect(Unit) {
        if (!touch) {
            delay(50)
            runCatching { categoryFocus.getValue(category).requestFocus() }
        }
    }
    LaunchedEffect(page) {
        val back = returnTo
        if (page == null && back != null) {
            category = back
            if (!touch) {
                delay(30)
                runCatching { categoryFocus.getValue(back).requestFocus() }
            }
            delay(100)
            returnTo = null
        }
    }
    fun openPage(p: Page) {
        if (returnTo == null) returnTo = category
        page = p
    }
    fun closePage() { page = null }
    // Back on a sub-page returns to the category instead of leaving Settings.
    BackHandler(enabled = page != null) { closePage() }

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        // ---- Categories ----
        Column(
            Modifier
                .width(290.dp)
                .fillMaxHeight()
                .background(Color(0xFF0F151C))
                .verticalScroll(rememberScrollState())
                .padding(top = 28.dp, start = 14.dp, end = 14.dp, bottom = 16.dp)
        ) {
            Text(
                "Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                modifier = Modifier.padding(start = 10.dp),
            )
            Text(
                "Channelbox ${vm.appVersion}", fontSize = 12.sp, color = Color(0xFF7D8A9A),
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 18.dp),
            )
            SettingsCategory.entries.forEachIndexed { i, c ->
                CategoryRow(
                    category = c,
                    selected = c == category,
                    modifier = Modifier.focusRequester(categoryFocus.getValue(c)),
                    onFocus = { if (page == null && returnTo == null) category = c },
                    onTap = {
                        returnTo = null
                        page = null
                        category = c
                    },
                )
            }
        }

        // ---- Settings in the chosen category (or a sub-page) ----
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = 30.dp, start = 40.dp, end = 40.dp, bottom = 28.dp)
                .widthIn(max = 780.dp)
        ) {
            val current = page
            if (current != null) {
                BackLink(category.title) { closePage() }
                SubPage(current, settings, vm, onClose = { closePage() }, onOpen = { openPage(it) }, playlistId = playlistId, onEditSource = onEditSource)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                    IconBadge(category.icon(), size = 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(category.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                        Text(category.blurb, fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 1.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (category) {
                        SettingsCategory.SOURCE -> SourceSettings(
                            settings, refreshState, channelCount, vm, onEditSource,
                            onPlaylist = { id ->
                                playlistId = id
                                openPage(Page.PLAYLIST)
                            },
                            onOrganise = onOrganise,
                        ) { openPage(it) }
                        SettingsCategory.PLAYBACK -> PlaybackSettings(settings, vm) { openPage(it) }
                        SettingsCategory.NETWORK -> NetworkSettingsList(settings, vm) { openPage(it) }
                        SettingsCategory.RECORDINGS -> RecordingSettings(settings, vm, onOpenRecordings) { openPage(it) }
                        SettingsCategory.STARTUP -> StartupSettings(settings, vm, onExit) { openPage(it) }
                        SettingsCategory.LANGUAGE -> {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val languages = remember { I18n.available(ctx) }
                            ChoiceList(
                                options = languages,
                                selected = I18n.language,
                                onSelect = { vm.setLanguage(it) },
                                autoFocus = false,
                            )
                            if (languages.size == 1) {
                                Text("More languages are on the way.", fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                        SettingsCategory.PARENTAL -> ParentalSettings(settings, vm)
                        SettingsCategory.UPDATES -> UpdateSettings(settings, vm)
                        SettingsCategory.ABOUT -> AboutSettings(vm)
                    }
                }
            }
        }
    }
}

// ---- Categories ------------------------------------------------------------------------------------

@Composable
private fun SourceSettings(
    settings: AppSettings,
    refreshState: RefreshState,
    channelCount: Int,
    vm: TvViewModel,
    onEditSource: (String?) -> Unit,
    onPlaylist: (String) -> Unit,
    onOrganise: () -> Unit,
    openPage: (Page) -> Unit,
) {
    val counts by vm.playlistCounts.collectAsStateWithLifecycle()
    val errors by vm.playlistErrors.collectAsStateWithLifecycle()
    settings.playlists.forEach { p ->
        val n = counts[p.id] ?: if (p.id == SettingsStore.LEGACY_ID) counts[null] else null
        val error = errors[p.id]
        SettingCard(
            title = p.displayName,
            description = when {
                !p.enabled -> p.summary + " · off"
                error != null -> "Couldn't load: $error" + (n?.let { " · showing $it channels from last time" } ?: "")
                else -> p.summary + (n?.let { " · $it channels" } ?: "")
            },
            value = if (p.enabled) "Manage" else "Off",
            onClick = { onPlaylist(p.id) },
        )
    }
    SettingCard(
        title = if (settings.playlists.isEmpty()) "Add your TV service" else "Add another playlist",
        description = "M3U link or Xtream Codes login. Channels from every playlist are shown together.",
        value = "Add",
        onClick = { onEditSource(null) },
    )
    val last = settings.lastRefresh.takeIf { it > 0 }
        ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    SettingCard(
        title = "Reload channels and guide now",
        description = when (refreshState) {
            is RefreshState.Running -> refreshState.message
            is RefreshState.Failed -> "Last attempt failed: ${refreshState.message}"
            is RefreshState.Done -> "$channelCount channels · ${refreshState.programmes} guide entries"
            RefreshState.Idle -> "$channelCount channels" + (last?.let { " · updated $it" } ?: "")
        },
        value = if (refreshState is RefreshState.Running) "Loading…" else "Reload",
        onClick = { vm.refreshNow() },
    )
    if (settings.playlists.isNotEmpty()) {
        var confirmClear by remember { mutableStateOf(false) }
        SettingCard(
            title = if (confirmClear) "Press again to clear and reload" else "Clear saved channels and reload",
            description = "Deletes all saved channels, movies & shows and guide, then loads your playlists fresh. " +
                "Favourites, recordings and your own names and numbers are kept.",
            highlight = confirmClear,
            onClick = {
                if (confirmClear) {
                    confirmClear = false
                    vm.clearSavedData()
                } else confirmClear = true
            },
        )
    }
    // Backup & restore: the system file picker where there is one; otherwise the Download folder.
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.backupTo(it) } }
    val openFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.restoreFrom(it) } }
    SettingCard(
        title = "Back up data",
        description = "Save playlists, settings, favourites and channel changes to a file. It includes your logins – keep it private",
        onClick = {
            try { saveFile.launch(vm.backupFileName()) } catch (e: android.content.ActivityNotFoundException) { vm.backupToDownloads() }
        },
    )
    SettingCard(
        title = "Restore data",
        description = "Load a backup file. Replaces your current settings, favourites and channel changes",
        onClick = {
            try { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            catch (e: android.content.ActivityNotFoundException) { vm.restoreLatest() }
        },
    )
    SettingCard(
        title = "Organise channels & groups",
        description = "Hide many groups at once, put them in your order, add lots of channels to Favourites and sort them",
        onClick = onOrganise,
    )
    SettingCard(
        title = "Movie categories",
        description = "Choose which movie categories show – turn off the ones you don't want",
        onClick = { openPage(Page.MOVIE_CATEGORIES) },
    )
    SettingCard(
        title = "TV show categories",
        description = "Choose which TV show categories show – turn off the ones you don't want",
        onClick = { openPage(Page.SHOW_CATEGORIES) },
    )
    SettingCard(
        title = "Jellyfin",
        description = errors[io.github.axon07.channelbox.data.JellyfinClient.PLAYLIST_ID]?.let { "Last load failed: $it" }
            ?: "Show movies & shows from your own Jellyfin server, next to or instead of your provider's",
        highlight = errors.containsKey(io.github.axon07.channelbox.data.JellyfinClient.PLAYLIST_ID),
        value = when {
            !settings.jellyfin.ready -> "Off"
            settings.jellyfin.mode == "replace" -> "Replaces provider"
            else -> "Added to provider"
        },
        onClick = { openPage(Page.JELLYFIN) },
    )
    SettingCard(
        title = "Days of TV guide",
        description = "Fewer days load faster and use less space with very big guides",
        value = guideDaysLabel(settings.guideDays),
        onClick = { openPage(Page.GUIDE_DAYS) },
    )
    SettingCard(
        title = "Channel numbers in the guide",
        description = "Off gives channel names more room",
        toggle = settings.showChannelNumbers,
        onClick = { vm.setShowChannelNumbers(!settings.showChannelNumbers) },
    )
    SettingCard(
        title = "Reload automatically",
        description = "Fetch the latest channels and guide in the background",
        value = refreshLabel(settings.refreshHours).substringBefore(" ("),
        onClick = { openPage(Page.REFRESH) },
    )
}

private fun guideDaysLabel(days: Int) = if (days == 1) "1 day" else "$days days"

private val IDLE_CHOICES = listOf(30, 60, 120, 300, 0)

private fun idleLabel(seconds: Int) = when {
    seconds <= 0 -> "Never"
    seconds < 60 -> "After $seconds seconds"
    seconds == 60 -> "After 1 minute"
    else -> "After ${seconds / 60} minutes"
}

private val QUALITY_CHOICES = listOf(
    0 to "Best available",
    1080 to "Up to 1080p",
    720 to "Up to 720p",
    480 to "Up to 480p",
    -1 to "Automatic (follows connection speed)",
)

@Composable
private fun PlaybackSettings(settings: AppSettings, vm: TvViewModel, openPage: (Page) -> Unit) {
    val context = LocalContext.current
    SettingCard(
        title = "Picture size",
        description = "How the picture fills the screen. Also while watching: hold OK.",
        value = SCALE_LABELS.getOrElse(settings.videoScale) { SCALE_LABELS[0] },
        onClick = { openPage(Page.SCALE) },
    )
    SettingCard(
        title = "Maximum quality",
        description = "Lower it on a slow connection to avoid buffering",
        value = QUALITY_CHOICES.firstOrNull { it.first == settings.maxQuality }?.second ?: "Best available",
        onClick = { openPage(Page.QUALITY) },
    )
    SettingCard(
        title = "Buffer size",
        description = "How much video is loaded ahead. Bigger rides out a shaky connection; smaller starts channels faster",
        value = BUFFER_CHOICES.firstOrNull { it.first == settings.bufferSize }?.second ?: "Medium",
        onClick = { openPage(Page.BUFFER) },
    )
    SettingCard(
        title = "Video decoder",
        description = "Hardware is faster and smoother. Try software if a channel shows green blocks or won't play. Applies after restarting",
        value = if (settings.softwareVideo) "Software" else "Hardware",
        onClick = { vm.setSoftwareVideo(!settings.softwareVideo) },
    )
    val hdrScreen = remember { io.github.axon07.channelbox.system.Hdr.displayLabel(context) }
    val dvDecoder = remember { io.github.axon07.channelbox.system.Hdr.hasDolbyVisionDecoder() }
    SettingCard(
        title = "Dolby Vision",
        description = "Auto uses Dolby Vision when the TV supports it, otherwise it plays as HDR10. Choose Off if the picture looks purple or green. Applies after restarting",
        value = when (settings.dolbyVision) {
            "on" -> "On"
            "off" -> "Off (HDR10)"
            else -> "Auto"
        },
        onClick = {
            vm.setDolbyVision(
                when (settings.dolbyVision) {
                    "auto" -> "on"
                    "on" -> "off"
                    else -> "auto"
                }
            )
        },
    )
    SettingCard(
        title = "HDR on this TV",
        description = if (dvDecoder) "This box can decode Dolby Vision. HDR needs the hardware video decoder"
        else "This box has no Dolby Vision decoder. HDR needs the hardware video decoder",
        value = hdrScreen ?: "Not supported",
        onClick = {},
    )
    SettingCard(
        title = "Audio decoder",
        description = "Try software if some channels play without sound. Applies after restarting",
        value = if (settings.preferSoftwareAudio) "Software" else "Hardware",
        onClick = { vm.setPreferSoftwareAudio(!settings.preferSoftwareAudio) },
    )
    SettingCard(
        title = "Audio passthrough",
        description = "Send Dolby and DTS sound untouched to a receiver or soundbar. Turn off if there's no sound through the TV. Applies after restarting",
        toggle = settings.audioPassthrough,
        onClick = { vm.setAudioPassthrough(!settings.audioPassthrough) },
    )
    SettingCard(
        title = "Auto frame rate (AFR)",
        description = "Switch the TV's refresh rate to match the video for smoother motion. The screen may blank for a moment when it changes",
        toggle = settings.autoFrameRate,
        onClick = { vm.setAutoFrameRate(!settings.autoFrameRate) },
    )
    SettingCard(
        title = "Back to TV when idle",
        description = "Close the guide and menus if no button is pressed for a while",
        value = idleLabel(settings.idleCloseSeconds),
        onClick = { openPage(Page.IDLE) },
    )
    if (SetTopBox.supportsPip(context)) {
        SettingCard(
            title = "Mini player when pressing Home",
            description = "Keep watching in a small window (picture-in-picture) while using other apps",
            toggle = settings.pipOnLeave,
            onClick = { vm.setPipOnLeave(!settings.pipOnLeave) },
        )
    }
    SettingCard(
        title = "Captions on by default",
        description = "Turn on subtitles or closed captions when a channel or video starts. CC switches them any time",
        toggle = settings.captionsByDefault,
        onClick = { vm.setCaptionsByDefault(!settings.captionsByDefault) },
    )
    SettingCard(
        title = "Reverse channel up / down",
        description = "Swap which way ▲ and ▼ change the channel",
        toggle = settings.invertChannelKeys,
        onClick = { vm.setInvertChannelKeys(!settings.invertChannelKeys) },
    )
}

@Composable
private fun NetworkSettingsList(settings: AppSettings, vm: TvViewModel, openPage: (Page) -> Unit) {
    val context = LocalContext.current
    val net = settings.network
    val vpn by vm.vpnState.collectAsStateWithLifecycle()
    val askVpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.setVpnEnabled(true)
        else Toast.makeText(context, "Android didn't allow the VPN", Toast.LENGTH_SHORT).show()
    }

    if (vm.networkNeedsRestart(net)) {
        SettingCard(
            title = "Restart to apply",
            description = "Proxy and DNS changes take effect after Channelbox restarts",
            value = "Restart now",
            highlight = true,
            onClick = { SetTopBox.restart(context) },
        )
    }
    SettingCard(
        title = "VPN (WireGuard)",
        description = when (val v = vpn) {
            VpnManager.State.On -> "Connected" + if (net.vpnAllApps) " · whole box" else " · Channelbox only"
            VpnManager.State.Connecting -> "Connecting…"
            is VpnManager.State.Failed -> "Couldn't connect: ${v.message}"
            VpnManager.State.Off -> if (net.vpnConfig.isBlank()) "Add a WireGuard config from your VPN provider first" else "Off"
        },
        toggle = net.vpnEnabled,
        onClick = {
            if (net.vpnEnabled) {
                vm.setVpnEnabled(false)
            } else if (net.vpnConfig.isBlank()) {
                openPage(Page.VPN)
            } else {
                val ask = VpnManager.permissionIntent(context)
                if (ask == null) vm.setVpnEnabled(true) else askVpn.launch(ask)
            }
        },
    )
    SettingCard(
        title = "VPN configuration",
        description = if (net.vpnConfig.isBlank()) "Not set" else "WireGuard config saved",
        value = "Edit",
        onClick = { openPage(Page.VPN) },
    )
    SettingCard(
        title = "Encrypted DNS",
        description = "Look up server names privately – gets around ISP DNS blocking",
        value = DNS_LABELS[net.dns] ?: "System",
        onClick = { openPage(Page.DNS) },
    )
    SettingCard(
        title = "Proxy",
        description = "Send Channelbox's traffic through an HTTP or SOCKS5 proxy",
        value = when (net.proxyType) {
            "http" -> "HTTP · ${net.proxyHost}:${net.proxyPort}"
            "socks5" -> "SOCKS5 · ${net.proxyHost}:${net.proxyPort}"
            else -> "Off"
        },
        onClick = { openPage(Page.PROXY) },
    )
    SettingCard(
        title = "User agent",
        description = "How Channelbox identifies itself. Some providers only allow certain players.",
        value = UserAgent.presetName(net.userAgent),
        onClick = { openPage(Page.USER_AGENT) },
    )
}

@Composable
private fun RecordingSettings(settings: AppSettings, vm: TvViewModel, onOpenRecordings: () -> Unit, openPage: (Page) -> Unit) {
    val st = settings.storage
    var description by remember { mutableStateOf("") }
    LaunchedEffect(st) { description = vm.describeStorage(st) }
    val drives = remember { vm.removableDrives() }
    SettingCard(
        title = "Recordings",
        description = "Watch, stop or delete your recordings",
        value = "Open",
        onClick = onOpenRecordings,
    )
    SettingCard(
        title = "Save recordings to",
        description = description,
        value = when (st.type) {
            "removable" -> "USB / SD card"
            "smb" -> "Network share"
            "webdav" -> "WebDAV"
            else -> "This box"
        },
        onClick = { openPage(Page.STORAGE) },
    )
    if (st.type == "removable" && drives > 1) {
        SettingCard(
            title = "Which drive",
            description = "$drives drives found",
            value = "Drive ${st.removableIndex + 1}",
            onClick = { vm.saveStorage(st.copy(removableIndex = (st.removableIndex + 1) % drives)) },
        )
    }
    if (st.type == "smb") {
        SettingCard(
            title = "Network share (SMB)",
            description = if (st.smbHost.isBlank()) "Windows PC, NAS or Samba server – not set" else "\\\\${st.smbHost}\\${st.smbShare}",
            value = "Edit",
            onClick = { openPage(Page.SMB) },
        )
    }
    if (st.type == "webdav") {
        SettingCard(
            title = "WebDAV folder",
            description = st.davUrl.ifBlank { "Nextcloud, Synology, QNAP… – not set" } +
                " · recorded here first, uploaded when finished",
            value = "Edit",
            onClick = { openPage(Page.WEBDAV) },
        )
    }
}

@Composable
private fun ParentalSettings(settings: AppSettings, vm: TvViewModel) {
    val hidden by vm.hiddenItems.collectAsStateWithLifecycle()
    val locked by vm.lockedItems.collectAsStateWithLifecycle()
    val hasPin = settings.pinHash != null
    InfoCard(
        "Rename, renumber, hide or lock",
        "Hold OK on a channel in the guide (Edit channel) or on a group in the groups menu to rename, " +
            "renumber, move, hide or lock it. In Movies & TV Shows, hold OK on a category to lock it."
    )
    SettingCard(
        title = if (hasPin) "Change PIN" else "Set a PIN",
        description = if (hasPin) "Locked channels, groups and categories need this PIN"
        else "Needed before you can lock anything",
        value = if (hasPin) "Change" else "Set",
        onClick = { vm.changePin() },
    )
    if (hasPin) {
        SettingCard(
            title = "Ask for the PIN to open Settings",
            description = "Stops others from changing settings or unlocking things",
            toggle = settings.pinForSettings,
            onClick = { vm.setPinForSettings(!settings.pinForSettings) },
        )
        SettingCard(
            title = "Lock again now",
            description = "After the PIN, locked things stay open for 2 hours",
            value = "Lock",
            onClick = { vm.lockNow() },
        )
        SettingCard(
            title = "Remove PIN",
            description = "Turns off every lock",
            value = "Remove",
            onClick = { vm.removePin() },
        )
    }
    if (locked.isNotEmpty()) {
        PageHeader("Locked", "Select one to unlock it")
        locked.forEach { item ->
            SettingCard(title = item.label, description = null, value = "Unlock", onClick = { vm.unlockItem(item) })
        }
    }
    PageHeader("Hidden", if (hidden.isEmpty()) "Nothing is hidden" else "Select one to show it again")
    hidden.forEach { item ->
        SettingCard(
            title = item.label,
            description = if (item.isGroup) "Group" else "Channel",
            value = "Show",
            onClick = { vm.showAgain(item) },
        )
    }
    SettingCard(
        title = "Undo all channel & group changes",
        description = "Names, numbers, order and hidden items go back to the playlist's (locks are kept)",
        value = "Undo",
        onClick = { vm.resetEdits() },
    )
}

@Composable
private fun StartupSettings(settings: AppSettings, vm: TvViewModel, onExit: () -> Unit, openPage: (Page) -> Unit) {
    val context = LocalContext.current
    SettingCard(
        title = "Turn on last channel on app start",
        description = "Off: Channelbox opens on the TV guide instead of playing",
        toggle = settings.playLastOnStart,
        onClick = { vm.setPlayLastOnStart(!settings.playLastOnStart) },
    )
    // Re-read system state (permissions, launcher) whenever we come back to the screen.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val isHome = remember(resumeTick) { SetTopBox.isHomeScreen(context) }
    var canAutoStart by remember { mutableStateOf(SetTopBox.canStartFromBackground(context)) }
    // Re-check on return, and again shortly after: some boxes report the change a moment late.
    LaunchedEffect(resumeTick) {
        repeat(5) {
            canAutoStart = SetTopBox.canStartFromBackground(context)
            if (canAutoStart) return@LaunchedEffect
            kotlinx.coroutines.delay(1_000)
        }
    }

    SettingCard(
        title = "Start when the box turns on",
        description = "Go straight to live TV after a power cut or restart",
        toggle = settings.startOnBoot,
        onClick = { vm.setStartOnBoot(!settings.startOnBoot) },
    )
    SettingCard(
        title = "Start when the box wakes up",
        description = "Go back to live TV when the box comes out of sleep",
        toggle = settings.startOnWake,
        onClick = { vm.setStartOnWake(!settings.startOnWake) },
    )
    if (SetTopBox.needsOverlayPermission()) {
        SettingCard(
            title = "Permission to start by itself",
            description = if (canAutoStart) "Allowed – Channelbox can open on power-on and wake"
            else "Needed for both options above: allow \"Display over other apps\"",
            value = if (canAutoStart) "Granted" else "Allow",
            onClick = {
                if (!SetTopBox.openOverlaySettings(context)) {
                    Toast.makeText(
                        context,
                        "This box hides that setting. Grant it from a computer:\n" + SetTopBox.adbGrantCommand(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
    SettingCard(
        title = "Use as home screen",
        description = if (isHome) "Press Home and choose Channelbox → Always" else "Home button opens live TV instead of the Android home screen",
        toggle = isHome,
        onClick = {
            SetTopBox.setHomeScreen(context, !isHome)
            resumeTick++
        },
    )
    SettingCard(
        title = "Back button on the groups menu",
        description = "What happens on the last Back press (TV → guide → groups → …)",
        value = settings.backAction.label,
        onClick = { openPage(Page.BACK) },
    )
    SettingCard(
        title = "Exit Channelbox",
        description = "Close the app and stop playback",
        value = "Exit",
        onClick = onExit,
    )
}

@Composable
private fun UpdateSettings(settings: AppSettings, vm: TvViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    SettingCard(
        title = "Check for updates",
        description = when (val u = update) {
            TvViewModel.UpdateState.Checking -> "Checking…"
            TvViewModel.UpdateState.UpToDate -> "You have the latest version (${vm.appVersion})"
            is TvViewModel.UpdateState.Available -> "Version ${u.release.version} is available"
            is TvViewModel.UpdateState.Downloading -> "Downloading version ${u.release.version}… ${u.percent}%"
            is TvViewModel.UpdateState.Failed -> "Couldn't check: ${u.message}"
            TvViewModel.UpdateState.Idle -> "Installed: version ${vm.appVersion}"
        },
        value = when (update) {
            is TvViewModel.UpdateState.Available -> "Update"
            is TvViewModel.UpdateState.Downloading -> null
            else -> "Check"
        },
        // When a new version is already known, this card installs it straight away.
        onClick = {
            if (update is TvViewModel.UpdateState.Available) vm.startUpdate() else vm.checkForUpdates(manual = true)
        },
    )
    SettingCard(
        title = "Check when the app starts",
        description = "Offer new versions automatically",
        toggle = settings.autoUpdateCheck,
        onClick = { vm.setAutoUpdateCheck(!settings.autoUpdateCheck) },
    )
}

@Composable
private fun AboutSettings(vm: TvViewModel) {
    val widevine = remember { vm.widevineLevel() }
    InfoCard("Version", "Channelbox ${vm.appVersion} (build ${BuildConfig.VERSION_CODE})")
    InfoCard("Device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    InfoCard("Widevine security level", "$widevine – L1 = hardware, L3 = software")
    InfoCard("Audio decoding", "Box decoders, plus bundled FFmpeg for Dolby Digital, DD+, DTS and TrueHD")
    InfoCard("Source code", "github.com/Axon07/Channelbox · free and open source under GPL-3.0")
    InfoCard("Channels", "Channelbox is only a player – channels come from your own TV service")
}

// ---- Sub-pages with text fields ---------------------------------------------------------------------

@Composable
private fun SubPage(
    page: Page,
    settings: AppSettings,
    vm: TvViewModel,
    onClose: () -> Unit,
    onOpen: (Page) -> Unit,
    playlistId: String?,
    onEditSource: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val net = settings.network
    val st = settings.storage
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    when (page) {
        Page.VPN -> {
            var config by remember { mutableStateOf(net.vpnConfig) }
            var link by remember { mutableStateOf("") }
            var allApps by remember { mutableStateOf(net.vpnAllApps) }
            val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    }.getOrNull()
                    if (text != null) config = text else toast("Couldn't read that file")
                }
            }
            PageHeader(
                "WireGuard VPN",
                "Get a WireGuard config (.conf) from your VPN provider – most offer one – and paste it below, load it from a link, or open the file.",
            )
            TvTextField("Config", config, { config = it }, lines = 8)
            Spacer(Modifier.height(8.dp))
            TvTextField("…or a link to the config file", link, { link = it }, keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(10.dp))
            SettingCard(
                title = "Send all apps through the VPN",
                description = if (allApps) "The whole box uses the VPN" else "Only Channelbox uses the VPN (recommended)",
                toggle = allApps,
                onClick = { allApps = !allApps },
            )
            Spacer(Modifier.height(10.dp))
            ButtonRow(
                "Save" to {
                    val error = vm.saveVpnConfig(config, allApps)
                    if (error != null) {
                        toast("Not a valid WireGuard config: $error")
                    } else {
                        toast("Saved")
                        onClose()
                    }
                },
                "Load link" to {
                    if (link.isBlank()) {
                        toast("Type the link first")
                    } else {
                        scope.launch {
                            runCatching { vm.fetchText(link.trim()) }
                                .onSuccess { config = it; toast("Loaded – check it and press Save") }
                                .onFailure { toast("Couldn't load it: ${it.message}") }
                        }
                    }
                },
                "Open file" to {
                    try {
                        pickFile.launch("*/*")
                    } catch (e: ActivityNotFoundException) {
                        toast("This box has no file picker – use a link instead")
                    }
                },
                "Cancel" to onClose,
            )
        }
        Page.PROXY -> {
            var type by remember { mutableStateOf(net.proxyType) }
            var host by remember { mutableStateOf(net.proxyHost) }
            var port by remember { mutableStateOf(net.proxyPort.takeIf { it > 0 }?.toString().orEmpty()) }
            var user by remember { mutableStateOf(net.proxyUser) }
            var pass by remember { mutableStateOf(net.proxyPass) }
            PageHeader("Proxy", "All of Channelbox's traffic (playlists, guide, streams) goes through the proxy. Restart to apply.")
            ChoiceList(
                options = listOf("none" to "Off", "http" to "HTTP proxy", "socks5" to "SOCKS5 proxy"),
                selected = type,
                onSelect = { type = it },
            )
            if (type != "none") {
                Spacer(Modifier.height(8.dp))
                TvTextField("Server", host, { host = it }, keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(8.dp))
                TvTextField("Port", port, { port = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
                Spacer(Modifier.height(8.dp))
                TvTextField("Username (optional)", user, { user = it })
                Spacer(Modifier.height(8.dp))
                TvTextField("Password (optional)", pass, { pass = it }, password = true)
            }
            Spacer(Modifier.height(12.dp))
            ButtonRow(
                "Save" to {
                    vm.saveNetwork(
                        net.copy(proxyType = type, proxyHost = host, proxyPort = port.toIntOrNull() ?: 0, proxyUser = user, proxyPass = pass)
                    )
                    onClose()
                },
                "Cancel" to onClose,
            )
        }
        Page.DNS -> {
            var url by remember { mutableStateOf(net.dnsCustomUrl) }
            PageHeader(
                "Encrypted DNS",
                "Looks up server names over an encrypted connection, so your ISP can't block or redirect them. Restart to apply.",
            )
            ChoiceList(
                options = DNS_LABELS.toList(),
                selected = net.dns,
                onSelect = { key ->
                    if (key == "custom") {
                        vm.saveNetwork(net.copy(dns = "custom"))
                    } else {
                        vm.saveNetwork(net.copy(dns = key))
                        onClose()
                    }
                },
            )
            if (net.dns == "custom") {
                Spacer(Modifier.height(12.dp))
                TvTextField("DNS-over-HTTPS address (e.g. https://dns.nextdns.io/abc123)", url, { url = it }, keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(10.dp))
                ButtonRow(
                    "Save" to {
                        if (!url.trim().startsWith("https://")) {
                            toast("It needs to start with https://")
                        } else {
                            vm.saveNetwork(net.copy(dns = "custom", dnsCustomUrl = url))
                            onClose()
                        }
                    },
                )
            }
        }
        Page.USER_AGENT -> {
            val presetKeys = UserAgent.PRESETS.values.toList()
            var custom by remember { mutableStateOf(net.userAgent.takeIf { it !in presetKeys }.orEmpty()) }
            var showCustom by remember { mutableStateOf(net.userAgent !in presetKeys) }
            PageHeader("User agent", "How Channelbox introduces itself to servers. Some providers only allow certain players.")
            ChoiceList(
                options = UserAgent.PRESETS.map { (name, value) -> value to name } + ("__custom" to "Custom…"),
                selected = if (showCustom) "__custom" else net.userAgent,
                onSelect = { key ->
                    if (key == "__custom") {
                        showCustom = true
                    } else {
                        showCustom = false
                        vm.saveNetwork(net.copy(userAgent = key))
                        onClose()
                    }
                },
            )
            if (showCustom) {
                Spacer(Modifier.height(12.dp))
                TvTextField("Custom user agent", custom, { custom = it }, lines = 3)
                Spacer(Modifier.height(10.dp))
                ButtonRow(
                    "Save" to {
                        vm.saveNetwork(net.copy(userAgent = custom))
                        onClose()
                    },
                )
            }
        }
        Page.SCALE -> {
            PageHeader("Picture size", "How the picture fills the screen. You can also change it while watching (hold OK).")
            ChoiceList(
                options = SCALE_LABELS.mapIndexed { i, label -> i.toString() to label },
                selected = settings.videoScale.toString(),
                onSelect = { vm.setVideoScale(it.toInt()); onClose() },
            )
        }
        Page.MOVIE_CATEGORIES, Page.SHOW_CATEGORIES -> {
            val kind = if (page == Page.MOVIE_CATEGORIES) io.github.axon07.channelbox.data.VodKind.MOVIE
            else io.github.axon07.channelbox.data.VodKind.SERIES
            val categories by remember(kind) { vm.allVodCategories(kind) }.collectAsStateWithLifecycle(initialValue = null)
            // Re-read on every change so the switches follow.
            val overridesVersion by vm.overrides.collectAsStateWithLifecycle()
            PageHeader(
                page.title,
                "On = shown in ${if (page == Page.MOVIE_CATEGORIES) "Movies" else "TV Shows"}. Off = hidden there and from search. Stays this way after reloads.",
            )
            val list = categories
            when {
                list == null -> Text("Loading…", fontSize = 13.sp, color = Palette.TextDim)
                list.isEmpty() -> Text("No categories yet – they appear after your playlists' movies & shows load.", fontSize = 13.sp, color = Palette.TextDim)
                else -> {
                    val names = list.map { it.name }
                    val hiddenCount = names.count { overridesVersion[TvViewModel.vodKey(kind, it)]?.hidden == true }
                    SettingCard(
                        title = "Show all",
                        description = "${names.size - hiddenCount} of ${names.size} shown",
                        onClick = { vm.setVodCategoriesHidden(kind, names, false) },
                    )
                    SettingCard(
                        title = "Hide all",
                        description = "Then turn on just the ones you want",
                        onClick = { vm.setVodCategoriesHidden(kind, names, true) },
                    )
                    Spacer(Modifier.height(8.dp))
                    list.forEach { c ->
                        val hidden = overridesVersion[TvViewModel.vodKey(kind, c.name)]?.hidden == true
                        SettingCard(
                            title = c.name,
                            description = "${c.count}",
                            toggle = !hidden,
                            onClick = { vm.setVodCategoriesHidden(kind, listOf(c.name), !hidden) },
                        )
                    }
                }
            }
        }
        Page.GUIDE_DAYS -> {
            PageHeader("Days of TV guide", "How far ahead the guide goes. Applies at the next reload.")
            ChoiceList(
                options = listOf(1, 2, 3, 5, 7).map { it.toString() to guideDaysLabel(it) },
                selected = settings.guideDays.toString(),
                onSelect = {
                    vm.setGuideDays(it.toInt())
                    onClose()
                },
            )
        }
        Page.IDLE -> {
            PageHeader("Back to TV when idle", "If the guide, channel list or a menu is left open with no button pressed, go back to the channel that's playing.")
            ChoiceList(
                options = IDLE_CHOICES.map { it.toString() to idleLabel(it) },
                selected = settings.idleCloseSeconds.toString(),
                onSelect = { vm.setIdleClose(it.toInt()); onClose() },
            )
        }
        Page.BUFFER -> {
            PageHeader("Buffer size", "How much video is loaded ahead. Takes effect the next time Channelbox starts.")
            ChoiceList(
                options = BUFFER_CHOICES,
                selected = settings.bufferSize,
                onSelect = { vm.setBufferSize(it); onClose() },
            )
        }
        Page.QUALITY -> {
            PageHeader("Maximum quality", "Channelbox picks the best quality your connection can handle, up to this limit.")
            ChoiceList(
                options = QUALITY_CHOICES.map { it.first.toString() to it.second },
                selected = settings.maxQuality.toString(),
                onSelect = { vm.setMaxQuality(it.toInt()); onClose() },
            )
        }
        Page.PLAYLIST -> {
            val index = settings.playlists.indexOfFirst { it.id == playlistId }
            val p = settings.playlists.getOrNull(index)
            if (p == null) {
                LaunchedEffect(Unit) { onClose() }
                return
            }
            var confirmRemove by remember(p.id) { mutableStateOf(false) }
            PageHeader(p.displayName, p.summary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingCard(
                    title = "Use this playlist",
                    description = if (p.enabled) "Its channels are in the guide" else "Hidden – its channels aren't loaded",
                    toggle = p.enabled,
                    onClick = { vm.setPlaylistEnabled(p.id, !p.enabled) },
                )
                SettingCard(
                    title = "Include movies & shows",
                    description = if (p.includeVod) "Shown under Movies and TV Shows. Turn off to load huge lists much faster."
                    else "Off – only live TV is loaded from this playlist",
                    toggle = p.includeVod,
                    onClick = { vm.savePlaylist(p.copy(includeVod = !p.includeVod)) },
                )
                SettingCard(
                    title = "Edit details",
                    description = "Name, link or login, and TV guide",
                    value = "Edit",
                    onClick = { onEditSource(p.id) },
                )
                if (settings.playlists.size > 1) {
                    if (index > 0) SettingCard(
                        title = "Move up",
                        description = "Earlier playlists get the lower channel numbers",
                        value = "${index + 1} of ${settings.playlists.size}",
                        onClick = { vm.movePlaylist(p.id, -1) },
                    )
                    if (index < settings.playlists.size - 1) SettingCard(
                        title = "Move down",
                        description = "Later playlists continue the numbering",
                        value = "${index + 1} of ${settings.playlists.size}",
                        onClick = { vm.movePlaylist(p.id, 1) },
                    )
                }
                SettingCard(
                    title = if (confirmRemove) "Press again to remove" else "Remove playlist",
                    description = "Deletes its channels, movies & shows and guide from this device. Favourites and recordings are kept.",
                    highlight = confirmRemove,
                    onClick = {
                        if (confirmRemove) {
                            vm.removePlaylist(p.id)
                            onClose()
                        } else confirmRemove = true
                    },
                )
            }
        }
        Page.REFRESH -> {
            PageHeader("Reload automatically", "How often to fetch the latest channels and programme guide in the background.")
            ChoiceList(
                options = REFRESH_CHOICES.map { it.toString() to refreshLabel(it).substringBefore(" (") },
                selected = settings.refreshHours.toString(),
                onSelect = { vm.setRefreshHours(it.toInt()); onClose() },
            )
        }
        Page.BACK -> {
            PageHeader("Back button on the groups menu", "Back goes TV → guide → groups menu. This is what the next press does.")
            ChoiceList(
                options = BackAction.entries.map { it.name to it.label },
                selected = settings.backAction.name,
                onSelect = { vm.setBackAction(BackAction.valueOf(it)); onClose() },
            )
        }
        Page.STORAGE -> {
            val drives = remember { vm.removableDrives() }
            PageHeader("Save recordings to", "Choose where new recordings are kept. Existing recordings stay where they are.")
            ChoiceList(
                options = listOf(
                    "local" to "This box",
                    "removable" to if (drives > 0) "USB drive / SD card" else "USB drive / SD card (none connected)",
                    "smb" to "Network share (NAS, Windows, Samba)",
                    "webdav" to "WebDAV (Nextcloud, Synology…)",
                ),
                selected = st.type,
                onSelect = { key ->
                    vm.saveStorage(st.copy(type = key))
                    // Network locations need their details the first time.
                    when {
                        key == "smb" && st.smbHost.isBlank() -> onOpen(Page.SMB)
                        key == "webdav" && st.davUrl.isBlank() -> onOpen(Page.WEBDAV)
                        else -> onClose()
                    }
                },
            )
        }
        Page.SMB -> {
            var host by remember { mutableStateOf(st.smbHost) }
            var share by remember { mutableStateOf(st.smbShare) }
            var path by remember { mutableStateOf(st.smbPath) }
            var user by remember { mutableStateOf(st.smbUser) }
            var pass by remember { mutableStateOf(st.smbPass) }
            var domain by remember { mutableStateOf(st.smbDomain) }
            fun edited() = st.copy(
                type = "smb", smbHost = host, smbShare = share, smbPath = path,
                smbUser = user, smbPass = pass, smbDomain = domain,
            )
            PageHeader(
                "Network share (SMB)",
                "Windows shared folder, NAS (Synology, QNAP, TrueNAS, Unraid…) or Samba. Recordings are written straight to it.",
            )
            TvTextField("Server (name or IP, e.g. 192.168.1.20)", host, { host = it }, keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            TvTextField("Share name (e.g. media)", share, { share = it })
            Spacer(Modifier.height(8.dp))
            TvTextField("Folder inside the share", path, { path = it })
            Spacer(Modifier.height(8.dp))
            TvTextField("Username (empty for guest)", user, { user = it })
            Spacer(Modifier.height(8.dp))
            TvTextField("Password", pass, { pass = it }, password = true)
            Spacer(Modifier.height(8.dp))
            TvTextField("Domain / workgroup (optional)", domain, { domain = it })
            Spacer(Modifier.height(12.dp))
            ButtonRow(
                "Test" to {
                    scope.launch { toast(vm.testStorage(edited())?.let { "Didn't work: $it" } ?: "Connected – it works") }
                },
                "Save" to {
                    vm.saveStorage(edited())
                    onClose()
                },
                "Cancel" to onClose,
            )
        }
        Page.JELLYFIN -> {
            val jf = settings.jellyfin
            var on by remember { mutableStateOf(jf.enabled || jf.server.isBlank()) }
            var server by remember { mutableStateOf(jf.server) }
            var user by remember { mutableStateOf(jf.user) }
            var pass by remember { mutableStateOf(jf.password) }
            var replace by remember { mutableStateOf(jf.mode == "replace") }
            fun edited() = JellyfinSettings(on, server, user, pass, if (replace) "replace" else "add")
            PageHeader(
                "Jellyfin",
                "Your own movies & shows in Movies and TV Shows. Videos play straight from your server.",
            )
            SettingCard(
                title = "Use Jellyfin",
                toggle = on,
                onClick = { on = !on },
            )
            SettingCard(
                title = "Provider's movies & shows",
                description = if (replace) "Hidden – only your Jellyfin library is shown" else "Kept – Jellyfin is added alongside, in its own categories",
                value = if (replace) "Replace" else "Add to",
                onClick = { replace = !replace },
            )
            Spacer(Modifier.height(8.dp))
            TvTextField("Server address (e.g. http://192.168.1.20:8096)", server, { server = it }, keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            TvTextField("Username", user, { user = it })
            Spacer(Modifier.height(8.dp))
            TvTextField("Password", pass, { pass = it }, password = true)
            Spacer(Modifier.height(12.dp))
            ButtonRow(
                "Test" to {
                    scope.launch { toast(vm.testJellyfin(edited())?.let { "Didn't work: $it" } ?: "Signed in – it works") }
                },
                "Save" to {
                    vm.saveJellyfin(edited())
                    onClose()
                },
                "Cancel" to onClose,
            )
        }
        Page.WEBDAV -> {
            var url by remember { mutableStateOf(st.davUrl) }
            var user by remember { mutableStateOf(st.davUser) }
            var pass by remember { mutableStateOf(st.davPass) }
            fun edited() = st.copy(type = "webdav", davUrl = url, davUser = user, davPass = pass)
            PageHeader(
                "WebDAV folder",
                "Nextcloud, ownCloud, Synology, QNAP… Recordings are saved on the box first and uploaded when they finish.",
            )
            TvTextField("Folder address (https://…)", url, { url = it }, keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            TvTextField("Username", user, { user = it })
            Spacer(Modifier.height(8.dp))
            TvTextField("Password / app password", pass, { pass = it }, password = true)
            Spacer(Modifier.height(12.dp))
            ButtonRow(
                "Test" to {
                    scope.launch { toast(vm.testStorage(edited())?.let { "Didn't work: $it" } ?: "Connected – it works") }
                },
                "Save" to {
                    vm.saveStorage(edited())
                    onClose()
                },
                "Cancel" to onClose,
            )
        }
    }
}

@Composable
private fun PageHeader(title: String, text: String) {
    Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    Text(text, fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
}

@Composable
private fun ButtonRow(vararg buttons: Pair<String, () -> Unit>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons.forEach { (label, action) ->
            // The main action (Save / Add) is filled; the others are quiet.
            PillButton(label, primary = label == "Save", onClick = action)
        }
    }
}

// ---- Building blocks -------------------------------------------------------------------------------

/** Subtle rounded square with a muted icon – the look used in both side menus. */
@Composable
fun IconBadge(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 30.dp, highlight: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(if (highlight) Color(0x26FFFFFF) else Color(0x14FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint = if (highlight) Color.White else Color(0xFFAEB8C4), size = size * 0.56f)
    }
}

/** Colours shared by every row: quiet at rest, a bright pill with dark text when focused (Google TV style). */
private object Rows {
    val Rest = Color.Transparent
    val Focus = Color(0xFFECF1F6)
    val Pressed = Color(0xFFD9E1EA)
    val OnFocus = Color(0xFF0E141B)
    val OnFocusDim = Color(0xFF4A5868)
    val Shape = RoundedCornerShape(12.dp)
}

@Composable
private fun CategoryRow(
    category: SettingsCategory,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onTap: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val content = when {
        focused -> Rows.OnFocus
        selected -> Color.White
        else -> Color(0xFF9AA6B4)
    }
    Surface(
        onClick = onTap,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .tap(onClick = onTap),
        shape = ClickableSurfaceDefaults.shape(Rows.Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x14FFFFFF) else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Rows.Focus,
            focusedContentColor = Rows.OnFocus,
            pressedContainerColor = Rows.Pressed,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thin accent bar marks the open section.
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(width = 3.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected && !focused) Palette.Accent else Color.Transparent)
            )
            Spacer(Modifier.width(11.dp))
            AppIcon(category.icon(), tint = content, size = 18.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                category.title, fontSize = 14.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One setting: title and description on the left, and either a value ("Every 12 hours ›")
 * or an on/off switch on the right.
 */
@Composable
private fun SettingCard(
    title: String,
    description: String? = null,
    value: String? = null,
    toggle: Boolean? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(Rows.Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlight) Color(0x223D8BFD) else Rows.Rest,
            contentColor = Palette.Text,
            focusedContainerColor = Rows.Focus,
            focusedContentColor = Rows.OnFocus,
            pressedContainerColor = Rows.Pressed,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = if (focused) Rows.OnFocus else Palette.Text,
                )
                val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
                // Less clutter: the explanation shows only for the row you're on (always on touch screens).
                androidx.compose.animation.AnimatedVisibility(visible = !description.isNullOrBlank() && (focused || touch)) {
                    Text(
                        description.orEmpty(), fontSize = 12.sp, lineHeight = 16.sp,
                        color = if (focused) Rows.OnFocusDim else Palette.TextDim, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            when {
                toggle != null -> ToggleSwitch(toggle)
                value != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                        color = if (focused) Rows.OnFocusDim else Palette.TextDim,
                    )
                    Text(
                        "  ›", fontSize = 18.sp,
                        color = if (focused) Rows.OnFocus else Color(0xFF6B7888),
                    )
                }
            }
        }
    }
}

/** A read-only line (About page). */
@Composable
private fun InfoCard(title: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Rows.Shape)
            .background(Rows.Rest)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(title.uppercase(), fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF7D8A9A))
        Text(text, fontSize = 14.sp, color = Palette.Text, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Animated on/off switch. */
@Composable
private fun ToggleSwitch(on: Boolean) {
    val track by androidx.compose.animation.animateColorAsState(
        if (on) Palette.Accent else Color(0xFF465263), label = "track",
    )
    val thumbX by androidx.compose.animation.core.animateDpAsState(if (on) 20.dp else 0.dp, label = "thumb")
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbX)
                .size(18.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White)
        )
    }
}

/** Pick-one list with a radio button – used for every multiple-choice setting. */
@Composable
private fun ChoiceList(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    /** Pages opened from a card take focus; a list shown beside the categories waits for ▶. */
    autoFocus: Boolean = true,
) {
    val first = remember { FocusRequester() }
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    LaunchedEffect(Unit) {
        if (!touch && autoFocus) {
            delay(50)
            runCatching { first.requestFocus() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, (key, label) ->
            val isSelected = key == selected
            var focused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onSelect(key) },
                modifier = (if (isSelected || (i == 0 && options.none { it.first == selected })) Modifier.focusRequester(first) else Modifier)
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .tap { onSelect(key) },
                shape = ClickableSurfaceDefaults.shape(Rows.Shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Rows.Rest,
                    contentColor = Palette.Text,
                    focusedContainerColor = Rows.Focus,
                    focusedContentColor = Rows.OnFocus,
                    pressedContainerColor = Rows.Pressed,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label, fontSize = 15.sp, modifier = Modifier.weight(1f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (focused) Rows.OnFocus else Palette.Text,
                    )
                    RadioDot(isSelected, focused)
                }
            }
        }
    }
}

@Composable
private fun RadioDot(on: Boolean, focused: Boolean = false) {
    val ring = when {
        on -> Palette.Accent
        focused -> Color(0xFF8995A5)
        else -> Color(0xFF4A5666)
    }
    Box(
        Modifier
            .size(20.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .border(2.dp, ring, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Accent))
    }
}

/** "‹ Playback" above a sub-page – tap or press Back to return. */
@Composable
private fun BackLink(label: String, onBack: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .tap(onClick = onBack)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹  $label", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.TextDim)
    }
}

private val BUFFER_CHOICES = listOf(
    "none" to "None – fastest start",
    "small" to "Small",
    "medium" to "Medium (recommended)",
    "large" to "Large",
    "xlarge" to "Very large – for shaky connections",
)
