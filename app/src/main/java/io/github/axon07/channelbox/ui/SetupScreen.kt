package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import io.github.axon07.channelbox.data.Playlist
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import kotlinx.coroutines.delay

/**
 * Add or edit one playlist: M3U URL, Xtream Codes login or Stalker portal (portal URL + MAC).
 * [initial] = null adds a new one; [firstRun] shows the welcome wording.
 */
@Composable
fun SetupScreen(
    initial: Playlist?,
    firstRun: Boolean,
    refreshState: RefreshState,
    vm: TvViewModel,
    onDone: () -> Unit,
    /** Opens Settings → Network & VPN (DNS, proxy, VPN, user agent). */
    onNetwork: () -> Unit,
) {
    val id = rememberSaveable { initial?.id ?: Playlist.newId() }
    var type by rememberSaveable {
        mutableStateOf(initial?.type?.takeIf { it == SourceType.XTREAM || it == SourceType.STALKER } ?: SourceType.M3U)
    }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var m3uUrl by rememberSaveable { mutableStateOf(initial?.m3uUrl.orEmpty()) }
    var server by rememberSaveable { mutableStateOf(initial?.xtServer.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(initial?.xtUser.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(initial?.xtPass.orEmpty()) }
    var epgUrl by rememberSaveable { mutableStateOf(initial?.epgUrl.orEmpty()) }
    // Stalker portal: the portal URL is kept in [server]; the login is optional.
    var mac by rememberSaveable { mutableStateOf(initial?.mac.orEmpty()) }
    var serial by rememberSaveable { mutableStateOf(initial?.serial.orEmpty()) }
    var deviceId1 by rememberSaveable { mutableStateOf(initial?.deviceId1.orEmpty()) }
    var deviceId2 by rememberSaveable { mutableStateOf(initial?.deviceId2.orEmpty()) }
    var moreStalker by rememberSaveable {
        mutableStateOf(initial?.let { it.serial.isNotBlank() || it.deviceId1.isNotBlank() || it.deviceId2.isNotBlank() } ?: false)
    }
    var submitted by remember { mutableStateOf(false) }
    var sawRunning by remember { mutableStateOf(false) }

    // "Use your phone": a small web page on the home network fills in this form.
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var fromPhone by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val link = io.github.axon07.channelbox.system.PhoneLink { f ->
            type = when (f["type"]) {
                "xtream" -> SourceType.XTREAM
                "stalker" -> SourceType.STALKER
                else -> SourceType.M3U
            }
            f["name"]?.takeIf { it.isNotBlank() }?.let { name = it.trim() }
            f["url"]?.let { m3uUrl = it.trim() }
            if (type == SourceType.STALKER) {
                f["portal"]?.let { server = it.trim() }
                f["mac"]?.let { mac = it.trim() }
                f["suser"]?.let { user = it.trim() }
                f["spass"]?.let { pass = it }
            } else {
                f["server"]?.let { server = it.trim() }
                f["user"]?.let { user = it.trim() }
                f["pass"]?.let { pass = it }
            }
            f["epg"]?.let { epgUrl = it.trim() }
            fromPhone = true
        }
        phoneUrl = runCatching { link.start() }.getOrNull()
        onDispose { link.stop() }
    }

    // Only leave once *this* save has finished loading (not an older refresh result).
    LaunchedEffect(refreshState, submitted) {
        if (!submitted) return@LaunchedEffect
        if (refreshState is RefreshState.Running) sawRunning = true
        // Done, or only the TV guide is still loading: go to TV and let it finish in the background.
        if (sawRunning && (refreshState is RefreshState.Done || (refreshState as? RefreshState.Running)?.background == true)) onDone()
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    // The QR code panel is for TV boxes: on a phone or tablet you can type right here.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isTv = (config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val showPhone = isTv || config.screenWidthDp >= 960

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            Column(Modifier.widthIn(max = 640.dp)) {
                Text(
                    when {
                        firstRun -> "Set up your TV service"
                        initial == null -> "Add a playlist"
                        else -> "Edit ${initial.displayName}"
                    },
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (firstRun) "Enter the details from your IPTV provider. Channelbox doesn't come with any channels. You can add more playlists later in Settings."
                    else "Channels from all your playlists are shown together, and each playlist also gets its own entry in the groups menu.",
                    fontSize = 13.sp, color = Palette.TextDim, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(24.dp))

                SourceTabs(
                    selected = type,
                    firstModifier = Modifier.focusRequester(first),
                    onSelect = { type = it },
                )
                Spacer(Modifier.height(22.dp))

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvTextField("Name – optional, e.g. \"Sports\" or the provider's name", name, { name = it })
                    when (type) {
                        SourceType.STALKER -> {
                            TvTextField("Portal URL (e.g. http://example.com:8080/c/)", server, { server = it }, keyboardType = KeyboardType.Uri)
                            TvTextField("MAC address (e.g. 00:1A:79:12:34:56)", mac, { mac = it })
                            if (mac.isNotBlank() && !io.github.axon07.channelbox.data.StalkerClient.isValidMac(mac)) {
                                Text("A MAC address is 12 letters and numbers (0–9, A–F), e.g. 00:1A:79:12:34:56", color = Palette.Error, fontSize = 12.sp)
                            }
                            TvTextField("Username – only if your provider gave you one", user, { user = it })
                            TvTextField("Password – only if your provider gave you one", pass, { pass = it }, password = true)
                            if (moreStalker) {
                                TvTextField("Serial number – optional", serial, { serial = it })
                                TvTextField("Device ID 1 – optional", deviceId1, { deviceId1 = it })
                                TvTextField("Device ID 2 – optional", deviceId2, { deviceId2 = it })
                            } else {
                                QuietButton("More options (serial number, device IDs)") { moreStalker = true }
                            }
                        }
                        SourceType.XTREAM -> {
                            TvTextField("Server (e.g. http://example.com:8080)", server, { server = it }, keyboardType = KeyboardType.Uri)
                            TvTextField("Username", user, { user = it })
                            TvTextField("Password", pass, { pass = it }, password = true)
                        }
                        else -> TvTextField("Playlist URL", m3uUrl, { m3uUrl = it }, keyboardType = KeyboardType.Uri)
                    }
                    TvTextField("TV guide (XMLTV) URL – optional, leave empty to use the provider's", epgUrl, { epgUrl = it }, keyboardType = KeyboardType.Uri)
                }
                Spacer(Modifier.height(24.dp))

                // Only this screen's own save counts as busy; a background reload is cancelled by saving.
                val busy = submitted && refreshState is RefreshState.Running
                val canSave = !busy && when (type) {
                    SourceType.M3U -> m3uUrl.isNotBlank()
                    SourceType.STALKER -> server.isNotBlank() && io.github.axon07.channelbox.data.StalkerClient.isValidMac(mac)
                    else -> server.isNotBlank() && user.isNotBlank()
                }
                val save = {
                    if (canSave) {
                        submitted = true
                        sawRunning = false
                        vm.savePlaylist(
                            Playlist(
                                id = id, name = name, type = type, m3uUrl = m3uUrl, epgUrl = epgUrl,
                                xtServer = server, xtUser = user, xtPass = pass, enabled = initial?.enabled ?: true,
                                includeVod = initial?.includeVod ?: true,
                                mac = if (type == SourceType.STALKER) io.github.axon07.channelbox.data.StalkerClient.normalizeMac(mac) else "",
                                serial = if (type == SourceType.STALKER) serial.trim() else "",
                                deviceId1 = if (type == SourceType.STALKER) deviceId1.trim() else "",
                                deviceId2 = if (type == SourceType.STALKER) deviceId2.trim() else "",
                            )
                        )
                    }
                }
                // Sent from the phone: save straight away.
                LaunchedEffect(fromPhone) {
                    if (fromPhone) {
                        fromPhone = false
                        save()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        if (busy) "Loading…" else if (initial == null) "Add and load channels" else "Save and reload",
                        enabled = canSave,
                        onClick = { if (canSave) save() },
                    )
                    Spacer(Modifier.width(16.dp))
                    when (refreshState) {
                        is RefreshState.Running -> Text(
                            refreshState.message, color = Palette.TextDim, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        is RefreshState.Failed -> if (submitted) Text(
                            refreshState.message, color = Palette.Error, fontSize = 13.sp,
                            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        else -> Unit
                    }
                }

                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(SetupStyle.Line))
                Spacer(Modifier.height(16.dp))
                val failed = submitted && refreshState is RefreshState.Failed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (failed) "Works in other apps or on other networks? Your internet provider may be blocking it – try another DNS, a proxy or a VPN, then save again."
                        else "Internet provider blocking IPTV? Set up DNS, a proxy, a VPN or the user agent first.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = if (failed) Palette.Text else Palette.TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    QuietButton("Network & VPN settings", onClick = onNetwork)
                }
            }
        }
        if (showPhone) PhonePanel(phoneUrl)
    }
}

/** Flat colours for this screen, in the same family as the settings and pop-ups. */
private object SetupStyle {
    val Track = Color(0x0DFFFFFF)
    val Line = Color(0x14FFFFFF)
    val Selected = Color(0xFF243140)
    val Focus = Color(0xFFECF1F6)
    val OnFocus = Color(0xFF0E141B)
    val Field = Color(0xFF111820)
    val FieldEdge = Color(0xFF242F3B)
    val Panel = Color(0xFF0F141B)
    val Shape = RoundedCornerShape(8.dp)
}

/** The three kinds of playlist as one slim segmented switch. */
@Composable
private fun SourceTabs(selected: SourceType, firstModifier: Modifier, onSelect: (SourceType) -> Unit) {
    val tabs = listOf(
        SourceType.M3U to "M3U link",
        SourceType.XTREAM to "Xtream Codes",
        SourceType.STALKER to "Stalker (MAC)",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(SetupStyle.Track, RoundedCornerShape(10.dp))
            .border(1.dp, SetupStyle.Line, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tabs.forEachIndexed { i, (kind, label) ->
            var focused by remember { mutableStateOf(false) }
            val isSelected = kind == selected
            Surface(
                onClick = { onSelect(kind) },
                modifier = (if (i == 0) firstModifier else Modifier)
                    .weight(1f)
                    .height(38.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .tap(onClick = { onSelect(kind) }),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) SetupStyle.Selected else Color.Transparent,
                    contentColor = Palette.Text,
                    focusedContainerColor = SetupStyle.Focus,
                    focusedContentColor = SetupStyle.OnFocus,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            focused -> SetupStyle.OnFocus
                            isSelected -> Color.White
                            else -> Palette.TextDim
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** The main action: accent fill, light when focused, dim when it can't be used yet. */
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(SetupStyle.Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) Palette.Accent else SetupStyle.Track,
            contentColor = Palette.Text,
            focusedContainerColor = SetupStyle.Focus,
            focusedContentColor = SetupStyle.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = when {
                focused -> SetupStyle.OnFocus
                enabled -> Color.White
                else -> Palette.TextDim
            },
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
        )
    }
}

/** A secondary action: hairline outline, light when focused. */
@Composable
private fun QuietButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .border(1.dp, if (focused) Color.Transparent else SetupStyle.Line, SetupStyle.Shape)
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(SetupStyle.Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = SetupStyle.Focus,
            focusedContentColor = SetupStyle.OnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (focused) SetupStyle.OnFocus else Palette.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** QR code + address of the "add a playlist from your phone" page. */
@Composable
private fun PhonePanel(url: String?) {
    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(SetupStyle.Panel)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(AppIcons.Phone, tint = Palette.TextDim, size = 22.dp)
        Spacer(Modifier.height(10.dp))
        Text("Use your phone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        if (url == null) {
            Text(
                "Connect this box to your home Wi-Fi or network to type the playlist on your phone instead.",
                fontSize = 12.sp, lineHeight = 17.sp, color = Palette.TextDim, textAlign = TextAlign.Center,
            )
            return@Column
        }
        Text(
            "Scan with your phone's camera and type the link there – much easier than with the remote.",
            fontSize = 12.sp, lineHeight = 17.sp, color = Palette.TextDim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        val qr = remember(url) { io.github.axon07.channelbox.system.PhoneLink.qrCode(url).asImageBitmap() }
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(8.dp)) {
            androidx.compose.foundation.Image(
                bitmap = qr, contentDescription = "QR code",
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                modifier = Modifier.size(172.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("or open", fontSize = 11.sp, color = Palette.TextDim)
        Text(url, fontSize = 12.sp, color = Palette.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(
            "Your phone must be on the same Wi-Fi. The page only works while this screen is open.",
            fontSize = 11.sp, lineHeight = 16.sp, color = Palette.TextDim, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    lines: Int = 1,
    /** Extra modifier for the input itself (e.g. a FocusRequester). */
    fieldModifier: Modifier = Modifier,
) {
    // On a TV the remote first lands on the field (no keyboard); OK starts typing. Phones and
    // tablets type straight away when the field is tapped.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val tv = (config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var editing by remember { mutableStateOf(!tv) }
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val input = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing && tv) {
            runCatching { input.requestFocus() }
            keyboard?.show()
        }
    }
    fun finish() {
        if (tv) editing = false
        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
    }
    val active = focused || hovered
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = if (active) Palette.Text else Palette.TextDim)
        Spacer(Modifier.height(6.dp))
        Box(
            fieldModifier
                .fillMaxWidth()
                .onFocusChanged { hovered = it.isFocused }
                .then(
                    if (tv) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { editing = true } else Modifier
                )
                .background(SetupStyle.Field, SetupStyle.Shape)
                .border(
                    if (active) 1.5.dp else 1.dp,
                    when {
                        focused -> Palette.Accent
                        hovered -> Color(0xFF8A98A8)
                        else -> SetupStyle.FieldEdge
                    },
                    SetupStyle.Shape,
                ),
        ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else lines,
            textStyle = TextStyle(color = Color.White, fontSize = if (lines == 1) 15.sp else 12.sp),
            cursorBrush = SolidColor(Palette.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            // No auto-capitals or autocorrect: addresses, usernames and passwords must stay as typed.
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = if (lines == 1) ImeAction.Next else ImeAction.Default,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { finish() }, onDone = { finish() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(input)
                // Not reachable with the remote until OK is pressed on the field.
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    if (focused && !it.isFocused && tv) editing = false // moved away: stop typing
                    focused = it.isFocused
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
        }
    }
}
