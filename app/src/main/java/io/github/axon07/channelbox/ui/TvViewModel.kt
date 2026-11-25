package io.github.axon07.channelbox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.axon07.channelbox.container
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.REFRESH_CHOICES
import io.github.axon07.channelbox.system.RefreshWorker
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.RecStatus
import io.github.axon07.channelbox.data.RecordingEntity
import io.github.axon07.channelbox.data.ReleaseInfo
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.Playlist
import io.github.axon07.channelbox.data.SourceType
import io.github.axon07.channelbox.data.XtreamClient
import io.github.axon07.channelbox.data.ZarkConfig
import io.github.axon07.channelbox.data.ZarkPrefs
import io.github.axon07.channelbox.data.OverrideEntity
import io.github.axon07.channelbox.player.Timeshift
import io.github.axon07.channelbox.data.VodCategory
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.VodProgressEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.axon07.channelbox.data.TvRepository.Companion.HOUR
import io.github.axon07.channelbox.player.PlayerOptions
import io.github.axon07.channelbox.player.TvPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.axon07.channelbox.data.NetworkSettings
import io.github.axon07.channelbox.data.StorageSettings
import io.github.axon07.channelbox.data.UserAgent
import io.github.axon07.channelbox.data.download
import io.github.axon07.channelbox.system.VpnManager
import io.github.axon07.channelbox.data.Catchup
import io.github.axon07.channelbox.data.StreamSpec

/** A row of the groups menu. */
sealed interface MenuRow {
    /** A playlist's heading; selecting it opens or closes the section. */
    data class Header(val key: String, val label: String, val open: Boolean, val collapsible: Boolean) : MenuRow
    data class Item(val filter: ChannelFilter, val section: String, val indented: Boolean) : MenuRow
}

sealed interface ChannelFilter {
    val label: String

    data object All : ChannelFilter {
        override val label = "All channels"
    }

    data object Favorites : ChannelFilter {
        override val label = "Favourites"
    }

    /** Every channel from one playlist (shown when more than one is in use). */
    data class Source(val id: String, val name: String) : ChannelFilter {
        override val label: String get() = name
    }

    /** A group; with several playlists, [playlistId] keeps each playlist's groups apart. */
    data class Group(val name: String, val playlistId: String? = null) : ChannelFilter {
        override val label: String get() = if (name == UNGROUPED) "Other channels" else name
    }

    companion object {
        /** Group name the playlist parsers give channels without a group. */
        const val UNGROUPED = "Uncategorized"
    }
}

/** Most titles listed at once in a category (search finds the rest). */
const val VOD_PAGE = 3_000
const val VOD_SEARCH_LIMIT = 300

/** Pseudo-category: movies / shows you've started. */
const val VOD_CONTINUE = "\u0000continue"

/** ZarkTV: pseudo-categoria "Meus favoritos" (filmes e séries). */
const val VOD_FAVORITOS = "\u0000favoritos"

/** How much of the timeline the guide shows at once. */
const val GUIDE_SPAN = 2 * HOUR
const val GUIDE_STEP = HOUR / 2

@OptIn(ExperimentalCoroutinesApi::class)
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val c = app.container
    private val repo = c.repository
    // The audio decoder choice is read once at start (changing it takes effect after a restart).
    /** Engine options are read once: changing them takes effect the next time Channelbox starts. */
    private val playerOptions = runBlocking {
        c.settings.current().let {
            PlayerOptions(
                softwareAudio = it.preferSoftwareAudio,
                softwareVideo = it.softwareVideo,
                dolbyVision = it.dolbyVision,
                buffer = it.bufferSize,
                passthrough = it.audioPassthrough,
            )
        }
    }
    val player = TvPlayer(app, c.http, playerOptions)

    /** Extra players for multi-view (released when multi-view closes). */
    fun newTilePlayer(): TvPlayer = TvPlayer(getApplication(), c.http, playerOptions, handleAudioFocus = false).also {
        it.storageSettings = { settings.value?.storage ?: StorageSettings() }
        it.captionsByDefault = { settings.value?.captionsByDefault ?: false }
    }

    /** True while the app is shrunk to a picture-in-picture window. */
    val inPip = MutableStateFlow(false)

    val settings: StateFlow<AppSettings?> = c.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val refreshState: StateFlow<RefreshState> = repo.state

    /** False until the saved channels have been read at start-up (so "no channels" isn't flashed). */
    val channelsLoaded: StateFlow<Boolean> = combine(repo.channels, c.settings.flow) { _, _ -> true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Your changes: names, numbers, order, hidden, locked ----------------------------------

    val overrides: StateFlow<Map<String, OverrideEntity>> = repo.overrides
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Renamed group → the playlist's name for it (overrides are kept under the original). */
    @Volatile private var groupOriginals: Map<String, String> = emptyMap()

    fun originalGroup(shown: String): String = groupOriginals[shown] ?: shown

    /** The playlist's channels with your names, numbers and hidden items applied. */
    /** ZarkTV: conteúdo adulto liberado com a senha no Perfil (escondido por padrão). */
    val zarkAdulto = MutableStateFlow(ZarkPrefs.adulto(app))

    private fun adultoBloq(vararg textos: String?) =
        !zarkAdulto.value && textos.any { it != null && ZarkPrefs.ADULTO.containsMatchIn(it) }

    val allChannels: StateFlow<List<ChannelEntity>> = combine(repo.channels, overrides, c.settings.flow, zarkAdulto) { saved, ov, s, adulto ->
        // Only channels of playlists that exist and are on: a removed playlist disappears at once,
        // without waiting for the reload that deletes its rows.
        val live = s.playlists.filter { it.enabled }.mapTo(HashSet()) { it.id }
        val list = saved.filter { playlistOf(it) in live }
            .filter { adulto || (!ZarkPrefs.ADULTO.containsMatchIn(it.groupTitle) && !ZarkPrefs.ADULTO.containsMatchIn(it.name)) }
        if (ov.isEmpty()) {
            groupOriginals = emptyMap()
            return@combine list
        }
        val originals = HashMap<String, String>()
        var renumbered = false
        val out = list.mapNotNull { ch ->
            val g = ov[groupKey(ch.groupTitle)]
            val o = ov[channelKey(ch.id)]
            if (g?.hidden == true || o?.hidden == true) return@mapNotNull null
            var c = ch
            o?.name?.let { c = c.copy(name = it) }
            o?.position?.let {
                c = c.copy(number = it)
                renumbered = true
            }
            g?.name?.let {
                originals[it] = ch.groupTitle
                c = c.copy(groupTitle = it)
            }
            c
        }
        groupOriginals = originals
        if (renumbered) out.sortedBy { it.number } else out
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Channels and groups you've hidden, to show again from Settings. */
    data class HiddenItem(val id: String, val label: String, val isGroup: Boolean)

    val hiddenItems: StateFlow<List<HiddenItem>> = combine(repo.channels, overrides) { list, ov ->
        val groups = ov.values.filter { it.hidden && it.id.startsWith("grp:") }
            .map { HiddenItem(it.id, groupLabel(it.id.removePrefix("grp:")), true) }
        val channels = list.filter { ov[channelKey(it.id)]?.hidden == true }
            .map { HiddenItem(channelKey(it.id), ov[channelKey(it.id)]?.name ?: it.name, false) }
        groups.sortedBy { it.label.lowercase() } + channels.sortedBy { it.label.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Locked channels, groups and categories, to unlock from Settings. */
    val lockedItems: StateFlow<List<HiddenItem>> = combine(repo.channels, overrides) { list, ov ->
        val names = list.associate { channelKey(it.id) to it.name }
        ov.values.filter { it.locked }.map { o ->
            when {
                o.id.startsWith("grp:") -> HiddenItem(o.id, "Group: " + groupLabel(o.id.removePrefix("grp:")), true)
                o.id.startsWith("vod:") -> HiddenItem(o.id, (if (o.id.startsWith("vod:movie:")) "Movies: " else "TV Shows: ") + o.id.substringAfter(':').substringAfter(':'), true)
                else -> HiddenItem(o.id, o.name ?: names[o.id] ?: "Channel", false)
            }
        }.sortedBy { it.label.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun groupLabel(name: String) = if (name == ChannelFilter.UNGROUPED) "Other channels" else name

    private fun edit(id: String, change: (OverrideEntity) -> OverrideEntity) = viewModelScope.launch {
        repo.putOverrides(listOf(change(overrides.value[id] ?: OverrideEntity(id))))
    }

    fun renameChannel(ch: ChannelEntity, name: String?) =
        edit(channelKey(ch.id)) { it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    fun setChannelNumber(ch: ChannelEntity, number: Int?) = edit(channelKey(ch.id)) { it.copy(position = number) }

    fun setChannelHidden(ch: ChannelEntity, hidden: Boolean) {
        edit(channelKey(ch.id)) { it.copy(hidden = hidden) }
        if (hidden) showToast("${ch.name} hidden – show it again in Settings → Parental controls & hidden")
    }

    fun renameGroup(shown: String, name: String?) =
        edit(groupKey(originalGroup(shown))) { it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    fun setGroupHidden(shown: String, hidden: Boolean) {
        edit(groupKey(originalGroup(shown))) { it.copy(hidden = hidden) }
        if (hidden) showToast("${groupLabel(shown)} hidden – show it again in Settings → Parental controls & hidden")
    }

    /** Moves a group up (-1) or down (+1) in the groups menu. */
    fun moveGroup(shown: String, delta: Int) = viewModelScope.launch {
        val order = menuSections.value.flatMap { it.filters }.filterIsInstance<ChannelFilter.Group>()
            .map { originalGroup(it.name) }.distinct()
        val i = order.indexOf(originalGroup(shown))
        val j = i + delta
        if (i < 0 || j !in order.indices) return@launch
        val moved = order.toMutableList().apply { add(j, removeAt(i)) }
        repo.putOverrides(moved.mapIndexed { index, g ->
            (overrides.value[groupKey(g)] ?: OverrideEntity(groupKey(g))).copy(position = index)
        })
    }

    fun showAgain(item: HiddenItem) = edit(item.id) { it.copy(hidden = false) }

    // ---- Organise: many channels / groups at once ---------------------------------------

    /** A group as the organiser shows it (hidden ones too). [original] is the playlist's name. */
    data class OrgGroup(val original: String, val shown: String, val count: Int, val hidden: Boolean)

    /** A channel as the organiser shows it (hidden ones too). */
    data class OrgChannel(val channel: ChannelEntity, val hidden: Boolean, val favorite: Boolean)

    /** Channels of the playlists that are on, with your names – hidden ones included. */
    private val everyChannel: StateFlow<List<ChannelEntity>> = combine(repo.channels, overrides, c.settings.flow) { saved, ov, s ->
        val live = s.playlists.filter { it.enabled }.mapTo(HashSet()) { it.id }
        saved.filter { playlistOf(it) in live }.map { ch ->
            val o = ov[channelKey(ch.id)]
            ch.copy(name = o?.name ?: ch.name, number = o?.position ?: ch.number)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Every group in menu order, hidden ones included. */
    val organiseGroups: StateFlow<List<OrgGroup>> = combine(everyChannel, overrides) { list, ov ->
        val counts = list.groupingBy { it.groupTitle }.eachCount()
        val names = list.map { it.groupTitle }.distinct()
        val ordered = names.filter { it != ChannelFilter.UNGROUPED } + names.filter { it == ChannelFilter.UNGROUPED }
        ordered.sortedBy { ov[groupKey(it)]?.position ?: Int.MAX_VALUE }.map { g ->
            val o = ov[groupKey(g)]
            OrgGroup(g, groupLabel(o?.name ?: g), counts[g] ?: 0, o?.hidden == true)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Channels of one group ([group] = the playlist's name), or Favourites in your order when null. */
    fun organiseChannels(group: String?): kotlinx.coroutines.flow.Flow<List<OrgChannel>> =
        combine(everyChannel, overrides, favoriteOrder) { list, ov, favs ->
            val favSet = favs.toHashSet()
            val chosen = if (group == null) {
                val byId = list.associateBy { it.id }
                favs.mapNotNull { byId[it] }
            } else {
                list.filter { it.groupTitle == group }.sortedBy { it.number }
            }
            chosen.map { ch ->
                OrgChannel(
                    ch,
                    hidden = ov[channelKey(ch.id)]?.hidden == true || ov[groupKey(ch.groupTitle)]?.hidden == true,
                    favorite = ch.id in favSet,
                )
            }
        }

    fun setGroupsHidden(originals: Collection<String>, hidden: Boolean) = viewModelScope.launch {
        if (originals.isEmpty()) return@launch
        repo.putOverrides(originals.map { g ->
            (overrides.value[groupKey(g)] ?: OverrideEntity(groupKey(g))).copy(hidden = hidden)
        })
        showToast(if (hidden) "${originals.size} hidden" else "${originals.size} shown again")
    }

    /** Saves the order of every group (playlist names, top first). */
    fun setGroupOrder(originals: List<String>) = viewModelScope.launch {
        repo.putOverrides(originals.mapIndexed { i, g ->
            (overrides.value[groupKey(g)] ?: OverrideEntity(groupKey(g))).copy(position = i)
        })
    }

    fun setChannelsHidden(ids: Collection<String>, hidden: Boolean) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        repo.putOverrides(ids.map { id ->
            (overrides.value[channelKey(id)] ?: OverrideEntity(channelKey(id))).copy(hidden = hidden)
        })
        showToast(if (hidden) "${ids.size} hidden" else "${ids.size} shown again")
    }

    fun addFavorites(ids: List<String>) = viewModelScope.launch {
        val new = ids.filter { it !in favorites.value }
        repo.addFavorites(new)
        showToast(if (new.isEmpty()) "Already in Favourites" else "${new.size} added to Favourites")
    }

    fun removeFavorites(ids: List<String>) = viewModelScope.launch {
        repo.removeFavorites(ids)
        showToast("${ids.size} removed from Favourites")
    }

    fun setFavoriteOrder(ids: List<String>) = viewModelScope.launch { repo.setFavoriteOrder(ids) }

    fun resetEdits() = viewModelScope.launch {
        repo.resetEdits()
        showToast("Channel and group changes undone")
    }

    // ---- Parental PIN ---------------------------------------------------------------------

    /** Something waiting for the PIN. [setup]: choosing a new PIN (asked twice). */
    data class PinRequest(
        val title: String,
        val setup: Boolean = false,
        /** Asked because a locked channel was chosen (Cancel then opens the menu). */
        val forChannel: Boolean = false,
        /** Depois de "Esqueci a senha", continua o que estava esperando (false em trocar/remover PIN). */
        val continuar: Boolean = true,
        val onSuccess: () -> Unit,
    )

    /** "Esqueci a senha": pede o usuário e a senha da conta (os mesmos dos canais). */
    val esqueciPin = MutableStateFlow(false)

    /** Confere [usuario]/[senha] com a conta salva no app; se bater, pede uma senha nova. */
    fun esqueciPinConfirmar(usuario: String, senha: String): Boolean {
        val contas = settings.value?.playlists.orEmpty().filter { it.xtUser.isNotBlank() }
        val ok = contas.any { it.xtUser.trim() == usuario.trim() && it.xtPass == senha }
        if (!ok) return false
        val antes = _pinRequest.value
        esqueciPin.value = false
        _pinRequest.value = PinRequest("Crie a nova senha de 4 números", setup = true) {
            if (antes != null && antes.continuar && !antes.setup) antes.onSuccess()
        }
        return true
    }

    private val _pinRequest = MutableStateFlow<PinRequest?>(null)
    val pinRequest: StateFlow<PinRequest?> = _pinRequest.asStateFlow()

    /** After the right PIN, locked things stay open for a while (or until "Lock now"). */
    private var unlockedUntil = 0L

    val hasPin: Boolean get() = settings.value?.pinHash != null
    val unlocked: Boolean get() = !hasPin || System.currentTimeMillis() < unlockedUntil

    /** Runs [action] straight away, or after the PIN when one is set and things are locked. */
    fun requirePin(title: String, forChannel: Boolean = false, action: () -> Unit) {
        if (unlocked) action() else _pinRequest.value = PinRequest(title, forChannel = forChannel, onSuccess = action)
    }

    /** True if [pin] is right; runs what was waiting. */
    fun submitPin(pin: String): Boolean {
        val req = _pinRequest.value ?: return false
        if (req.setup) {
            viewModelScope.launch {
                c.settings.setPinHash(hashPin(pin))
                showToast("PIN saved")
            }
            unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
            _pinRequest.value = null
            req.onSuccess()
            return true
        }
        if (hashPin(pin) != settings.value?.pinHash) return false
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
        _pinRequest.value = null
        req.onSuccess()
        return true
    }

    fun cancelPin() {
        _pinRequest.value = null
    }

    /** Choose a new PIN (asks for the old one first). */
    fun changePin() {
        val nova = { _pinRequest.value = PinRequest("Choose a 4-digit PIN", setup = true) {} }
        if (unlocked) nova() else _pinRequest.value = PinRequest("Enter your current PIN", continuar = false, onSuccess = nova)
    }

    fun removePin() {
        val tirar = {
            viewModelScope.launch {
                c.settings.setPinHash(null)
                showToast("PIN removed – nothing is locked any more")
            }
            Unit
        }
        if (unlocked) tirar() else _pinRequest.value = PinRequest("Enter your PIN to remove it", continuar = false, onSuccess = tirar)
    }

    fun setPinForSettings(on: Boolean) = requirePin("Digite sua senha") {
        viewModelScope.launch { c.settings.setPinForSettings(on) }
    }

    fun lockNow() {
        unlockedUntil = 0
        showToast("Locked")
    }

    val lockedChannelIds: StateFlow<Set<String>> = combine(allChannels, overrides) { list, ov ->
        if (ov.values.none { it.locked }) emptySet()
        else list.filter { isLocked(it) }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun isLocked(ch: ChannelEntity): Boolean {
        val ov = overrides.value
        return ov[channelKey(ch.id)]?.locked == true || ov[groupKey(originalGroup(ch.groupTitle))]?.locked == true
    }

    fun isGroupLocked(shown: String) = overrides.value[groupKey(originalGroup(shown))]?.locked == true

    fun isVodCategoryLocked(kind: String, category: String) = overrides.value[vodKey(kind, category)]?.locked == true

    private fun setLocked(id: String, locked: Boolean, label: String) {
        if (!hasPin) {
            showToast("Set a PIN first: Settings → Parental controls & hidden")
            return
        }
        requirePin("Digite sua senha") {
            edit(id) { it.copy(locked = locked) }
            showToast(if (locked) "$label locked" else "$label unlocked")
        }
    }

    fun setChannelLocked(ch: ChannelEntity, locked: Boolean) = setLocked(channelKey(ch.id), locked, ch.name)
    fun setGroupLocked(shown: String, locked: Boolean) = setLocked(groupKey(originalGroup(shown)), locked, groupLabel(shown))
    fun setVodCategoryLocked(kind: String, category: String, locked: Boolean) = setLocked(vodKey(kind, category), locked, category)
    fun unlockItem(item: HiddenItem) = setLocked(item.id, false, item.label)

    /** Favourite channel ids in your order. */
    val favoriteOrder: StateFlow<List<String>> = repo.favoriteOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<Set<String>> = favoriteOrder.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _filter = MutableStateFlow<ChannelFilter>(ChannelFilter.All)
    val filter: StateFlow<ChannelFilter> = _filter.asStateFlow()

    /** One section of the groups menu: every playlist, or one playlist with its groups. */
    data class MenuSection(val key: String, val label: String, val filters: List<ChannelFilter>)

    /** With one playlist: a single untitled section. With several: "All playlists", then one per playlist. */
    val menuSections: StateFlow<List<MenuSection>> = combine(allChannels, c.settings.flow, overrides) { list, s, _ ->
        // Playlist order, with channels that have no group collected at the very end.
        fun groupsOf(channels: List<ChannelEntity>): List<String> {
            val names = channels.map { it.groupTitle }.distinct()
            // ZarkTV: jogos do dia primeiro, depois os canais abertos (Globo, TV aberta, Band, SBT, Record…)
            // e o resto em ordem alfabética. Grupos que a pessoa moveu continuam na ordem dela.
            val jogos = Regex("""jogo|futebol|brasileir|campeonato|copa|libertadores|ppv|pay.?per.?view""", RegexOption.IGNORE_CASE)
            val abertos = Regex("""globo|aberta|abertos|\bband\b(?! ?sports)|sbt|record|rede ?tv|cultura|tv brasil""", RegexOption.IGNORE_CASE)
            fun prioridade(n: String) = when {
                n == ChannelFilter.UNGROUPED -> 3
                jogos.containsMatchIn(n) -> 0
                abertos.containsMatchIn(n) -> 1
                else -> 2
            }
            fun semAcento(n: String) = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
            val ordered = names.sortedWith(compareBy<String>({ prioridade(it) }, { semAcento(it) }))
            // Groups you've moved keep your order; the rest follow the playlist.
            val ov = overrides.value
            return ordered.sortedBy { ov[groupKey(originalGroup(it))]?.position ?: Int.MAX_VALUE }
        }
        val byPlaylist = list.groupBy(::playlistOf)
        val playlists = s.playlists.filter { it.id in byPlaylist }
        if (playlists.size <= 1) {
            listOf(MenuSection(SECTION_ALL, "", listOf(ChannelFilter.All, ChannelFilter.Favorites) + groupsOf(list).map { ChannelFilter.Group(it) }))
        } else {
            listOf(MenuSection(SECTION_ALL, "All playlists", listOf(ChannelFilter.Favorites, ChannelFilter.All))) +
                playlists.map { p ->
                    MenuSection(
                        p.id, p.displayName,
                        listOf(ChannelFilter.Source(p.id, p.displayName)) +
                            groupsOf(byPlaylist.getValue(p.id)).map { ChannelFilter.Group(it, p.id) },
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every filter in menu order (Left/Right in the channel list steps through these). */
    val filters: StateFlow<List<ChannelFilter>> = menuSections
        .map { sections -> sections.flatMap { it.filters }.ifEmpty { listOf(ChannelFilter.All, ChannelFilter.Favorites) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(ChannelFilter.All, ChannelFilter.Favorites))

    /** Playlist sections opened in the groups menu ("All playlists" is always open). */
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    fun toggleSection(key: String) {
        _expanded.value = if (key in _expanded.value) _expanded.value - key else _expanded.value + key
    }

    fun setSectionOpen(key: String, open: Boolean) {
        _expanded.value = if (open) _expanded.value + key else _expanded.value - key
    }

    /** Rows of the groups menu as shown right now (closed sections show only their heading). */
    fun menuRows(
        sections: List<MenuSection> = menuSections.value,
        open: Set<String> = _expanded.value,
    ): List<MenuRow> = buildList {
        sections.forEach { s ->
            if (s.label.isEmpty()) {
                s.filters.forEach { add(MenuRow.Item(it, s.key, indented = false)) }
            } else {
                val isOpen = s.key == SECTION_ALL || s.key in open
                add(MenuRow.Header(s.key, s.label, isOpen, collapsible = s.key != SECTION_ALL))
                if (isOpen) s.filters.forEach { add(MenuRow.Item(it, s.key, indented = true)) }
            }
        }
    }

    /** Opens the section holding [filter] so it can be shown selected; returns the rows then. */
    fun revealInMenu(filter: ChannelFilter): List<MenuRow> {
        menuSections.value.firstOrNull { filter in it.filters }?.let { if (it.key != SECTION_ALL) setSectionOpen(it.key, true) }
        return menuRows()
    }

    /** Name of [f] for headings; a group says which playlist it's from when there are several. */
    fun filterTitle(f: ChannelFilter, sections: List<MenuSection> = menuSections.value): String {
        val playlist = (f as? ChannelFilter.Group)?.playlistId ?: return f.label
        val name = sections.firstOrNull { it.key == playlist }?.label.orEmpty()
        return if (name.isEmpty()) f.label else "$name · ${f.label}"
    }

    /** Number of channels in each group, for the groups menu. */
    val filterCounts: StateFlow<Map<ChannelFilter, Int>> = combine(filters, allChannels, favorites) { fs, list, favs ->
        val map = HashMap<ChannelFilter, Int>()
        map[ChannelFilter.All] = list.size
        map[ChannelFilter.Favorites] = list.count { it.id in favs }
        list.groupingBy { it.groupTitle }.eachCount().forEach { (g, n) -> map[ChannelFilter.Group(g)] = n }
        list.groupingBy { playlistOf(it) to it.groupTitle }.eachCount()
            .forEach { (k, n) -> map[ChannelFilter.Group(k.second, k.first)] = n }
        val perSource = list.groupingBy(::playlistOf).eachCount()
        fs.filterIsInstance<ChannelFilter.Source>().forEach { map[it] = perSource[it.id] ?: 0 }
        map
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Channels per playlist id, for Settings. */
    val playlistCounts: StateFlow<Map<String?, Int>> = allChannels
        .map { list -> list.groupingBy { it.playlistId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Where recordings are saved, and free space there. */
    val recordingsFolder: java.io.File get() = c.recorder.directory()

    val visibleChannels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, favoriteOrder, _filter) { list, favs, f ->
            when (f) {
                ChannelFilter.All -> list
                // In your order.
                ChannelFilter.Favorites -> list.associateBy { it.id }.let { byId -> favs.mapNotNull { byId[it] } }
                is ChannelFilter.Group -> list.filter {
                    it.groupTitle == f.name && (f.playlistId == null || playlistOf(it) == f.playlistId)
                }
                is ChannelFilter.Source -> list.filter { playlistOf(it) == f.id }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Wall clock, ticking every 15 s – drives progress bars and the "now" line. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(15_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    /** Programmes from now until +6 h, keyed by epg id. Used by the banner and channel list. */
    val upcoming: StateFlow<Map<String, List<ProgramEntity>>> = now
        .map { it / (5 * 60_000L) }
        .distinctUntilChanged()
        .flatMapLatest {
            val t = System.currentTimeMillis()
            repo.programs(t, t + 6 * HOUR)
        }
        .map { list -> list.groupBy { it.epgId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _guideStart = MutableStateFlow(roundDown(System.currentTimeMillis()))
    val guideStart: StateFlow<Long> = _guideStart.asStateFlow()

    val guidePrograms: StateFlow<Map<String, List<ProgramEntity>>> = _guideStart
        .flatMapLatest { start -> repo.programs(start, start + GUIDE_SPAN) }
        .map { list -> list.groupBy { it.epgId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _current = MutableStateFlow<ChannelEntity?>(null)
    val current: StateFlow<ChannelEntity?> = _current.asStateFlow()
    private var previous: ChannelEntity? = null

    /** Bumped on every channel change so the UI can flash the info banner. */
    private val _bannerToken = MutableStateFlow(0)
    val bannerToken: StateFlow<Int> = _bannerToken.asStateFlow()

    /** Short on-screen message ("Recording started…"). */
    data class Toast(val id: Int, val text: String)
    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()
    private var toastId = 0

    fun showToast(text: String) {
        _toast.value = Toast(++toastId, tr(text))
    }

    fun clearToast(id: Int) {
        if (_toast.value?.id == id) _toast.value = null
    }

    // ---- Updates --------------------------------------------------------------------------

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Available(val release: ReleaseInfo) : UpdateState
        data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val updater = c.updater
    val appVersion: String get() = updater.currentVersion
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** True when the update dialog should be on screen. */
    private val _updatePrompt = MutableStateFlow(false)
    val updatePrompt: StateFlow<Boolean> = _updatePrompt.asStateFlow()

    /**
     * [manual] = the user pressed "Procurar atualização": always report the result.
     * Automatic checks stay silent unless there's a new version they haven't skipped.
     */
    fun checkForUpdates(manual: Boolean) {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateState.Checking
            runCatching { updater.limparBaixados() }
            val result = runCatching { updater.latest() }
            val release = result.getOrNull()
            _update.value = when {
                result.isFailure -> UpdateState.Failed(result.exceptionOrNull()?.message ?: "Couldn't reach GitHub")
                release != null && updater.isNewer(release) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
            val state = _update.value
            if (state is UpdateState.Available) {
                val skipped = c.settings.current().skippedVersion
                if (manual) _updatePrompt.value = false
                _updatePrompt.value = manual || state.release.forced || state.release.version != skipped
            } else if (!manual) {
                _update.value = UpdateState.Idle
            }
        }
    }

    fun startUpdate() {
        val release = when (val s = _update.value) {
            is UpdateState.Available -> s.release
            else -> return
        }
        if (!updater.canInstall()) {
            // Android 8+: the user has to allow Channelbox to install apps (once).
            // When they come back from that screen, resumePendingUpdate() carries on by itself.
            if (updater.openInstallPermission()) {
                pendingInstall = true
                showToast("Ative \"Permitir\" para o ZarkTV e aperte Voltar")
            } else {
                showToast("Permita \"Instalar apps desconhecidos\" para o ZarkTV nas configurações do Android")
            }
            return
        }
        // ZarkTV: baixa em segundo plano (o progresso aparece no cantinho) e o app continua usável.
        _updatePrompt.value = false
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(release, 0)
            try {
                val apk = updater.download(release) { percent ->
                    _update.value = UpdateState.Downloading(release, percent)
                }
                _update.value = UpdateState.Available(release)
                // Obrigatória: o aviso continua até instalar (se o cliente cancelar, volta a aparecer).
                _updatePrompt.value = release.forced
                updater.install(apk)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }

    /** Set while the user is on Android's "install unknown apps" screen. */
    private var pendingInstall = false

    /** Called when the app comes back to the front: finish an update that was waiting on permission. */
    fun resumePendingUpdate() {
        if (!pendingInstall) return
        pendingInstall = false
        if (updater.canInstall()) startUpdate()
        else showToast("O ZarkTV ainda não tem permissão para instalar o app")
    }

    fun dismissUpdate(skipThisVersion: Boolean) {
        val s = _update.value
        // Atualização obrigatória não pode ser fechada.
        val forced = (s as? UpdateState.Available)?.release?.forced == true ||
            (s as? UpdateState.Downloading)?.release?.forced == true
        // Mesmo obrigatória, o cliente pode fechar e continuar assistindo (se a instalação falhar, não trava o app).
        _updatePrompt.value = false
        if (skipThisVersion && s is UpdateState.Available) {
            viewModelScope.launch { c.settings.setSkippedVersion(s.release.version) }
        }
    }

    fun setAutoUpdateCheck(on: Boolean) = viewModelScope.launch { c.settings.setAutoUpdateCheck(on) }

    // ---- Recordings -----------------------------------------------------------------------

    private val recorder = c.recorder

    val recordings: StateFlow<List<RecordingEntity>> = recorder.recordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** "channelId@programStart" for every scheduled/running recording – marks programmes in the guide. */
    val recordingKeys: StateFlow<Set<String>> = recordings
        .map { list ->
            list.filter { it.status in RecStatus.ACTIVE }
                .mapNotNull { r -> r.programStart?.let { recordingKey(r.channelId, it) } }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Channels being recorded right now. */
    val recordingChannels: StateFlow<Set<String>> = recordings
        .map { list -> list.filter { it.status == RecStatus.RECORDING }.map { it.channelId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Record (or stop recording) what's on the current channel now. */
    /** Smooth Streaming channels can't be recorded yet: say so instead of adding a recording that fails. */
    private fun canRecord(ch: ChannelEntity): Boolean {
        if (recorder.canRecord(StreamSpecs.from(ch))) return true
        showToast("${ch.name} uses Smooth Streaming, which can't be recorded yet")
        return false
    }

    fun toggleRecordCurrent() {
        val ch = _current.value ?: return
        val running = recordings.value.firstOrNull { it.channelId == ch.id && it.status == RecStatus.RECORDING }
        if (running != null) {
            stopRecording(running)
            return
        }
        if (!canRecord(ch)) return
        val now = System.currentTimeMillis()
        val program = nowAndNext(ch.epgId?.let { upcoming.value[it] }, now).first
        viewModelScope.launch {
            val title = program?.title ?: ch.name
            recorder.schedule(ch, title, now, program?.stop?.plus(2 * 60_000L) ?: (now + 2 * HOUR), program?.start)
            showToast("● Recording: $title")
        }
    }

    /** Record a programme from the guide, or cancel it if it's already scheduled. */
    fun toggleRecordProgram(channel: ChannelEntity, program: ProgramEntity) {
        val existing = recordings.value.firstOrNull {
            it.channelId == channel.id && it.programStart == program.start && it.status in RecStatus.ACTIVE
        }
        if (existing != null) {
            stopRecording(existing)
            return
        }
        if (!canRecord(channel)) return
        val now = System.currentTimeMillis()
        if (program.stop <= now) {
            showToast("That programme has already finished")
            return
        }
        viewModelScope.launch {
            recorder.schedule(
                channel, program.title,
                start = maxOf(now, program.start - 60_000L),
                stop = program.stop + 2 * 60_000L,
                programStart = program.start,
            )
            showToast(if (program.start > now) "Recording scheduled: ${program.title}" else "● Recording: ${program.title}")
        }
    }

    fun stopRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            recorder.stop(recording)
            showToast(if (recording.status == RecStatus.SCHEDULED) "Recording cancelled" else "Recording stopped")
        }
    }

    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            recorder.delete(recording)
            showToast("Recording deleted")
        }
    }

    fun playRecording(recording: RecordingEntity) {
        endShift()
        val path = recording.filePath ?: return
        val st = settings.value?.storage ?: StorageSettings()
        when {
            // DASH recording (maybe protected): same DRM / licence step as the live channel.
            path.endsWith(io.github.axon07.channelbox.data.HlsFolderRecorder.INDEX) -> {
                val live = StreamSpecs.from(recording)
                player.playOnDemand(
                    StreamSpec(android.net.Uri.fromFile(java.io.File(path)).toString(), live.headers, live.drm, StreamSpecs.MIME_HLS)
                )
            }
            path.endsWith(io.github.axon07.channelbox.data.DashRecorder.MANIFEST) -> {
                val live = StreamSpecs.from(recording)
                player.playOnDemand(
                    StreamSpec(android.net.Uri.fromFile(java.io.File(path)).toString(), live.headers, live.drm, StreamSpecs.MIME_DASH)
                )
            }
            path.startsWith("/") -> StreamSpecs.from(recording).drm?.let { drm ->
                // Protected HLS saved still encrypted: needs the channel's licence / key again.
                player.playOnDemand(StreamSpec(android.net.Uri.fromFile(java.io.File(path)).toString(), emptyMap(), drm, null))
            } ?: player.playFile(path)
            path.startsWith("http") -> player.playOnDemand(
                StreamSpec(path, mapOf("Authorization" to okhttp3.Credentials.basic(st.davUser, st.davPass)), null, null)
            )
            else -> player.playOnDemand(StreamSpec(path, emptyMap(), null, null)) // smb://
        }
    }

    /** True if [program] on [channel] can be watched from the archive (or from its start, if it's on now). */
    fun canCatchUp(channel: ChannelEntity, program: ProgramEntity, now: Long = System.currentTimeMillis()): Boolean =
        Catchup.isAvailable(channel.catchupType, channel.catchupSource, channel.catchupDays) &&
            program.start < now && program.start >= now - channel.catchupDays * 24 * HOUR

    /** Plays a past (or current, from the start) programme from the provider's archive. */
    fun playCatchUp(channel: ChannelEntity, program: ProgramEntity): Boolean {
        endShift()
        val live = StreamSpecs.from(channel)
        val url = Catchup.buildUrl(channel.streamUrl, channel.catchupType, channel.catchupSource, program.start, program.stop)
            ?: return false
        val archive = StreamSpecs.parse(url, live.headers, channel.drmType, channel.drmKey, null)
        player.playOnDemand(StreamSpec(archive.url, archive.headers, archive.drm, archive.mime))
        return true
    }

    fun setPreferSoftwareAudio(on: Boolean) = viewModelScope.launch {
        c.settings.setPreferSoftwareAudio(on)
        showToast("Takes effect the next time Channelbox starts")
    }
    fun setSoftwareVideo(on: Boolean) = viewModelScope.launch {
        c.settings.setSoftwareVideo(on)
        showToast("Takes effect the next time Channelbox starts")
    }
    fun setDolbyVision(mode: String) = viewModelScope.launch {
        c.settings.setDolbyVision(mode)
        showToast("Takes effect the next time Channelbox starts")
    }
    fun setBufferSize(size: String) = viewModelScope.launch {
        c.settings.setBufferSize(size)
        showToast("Takes effect the next time Channelbox starts")
    }
    fun setAudioPassthrough(on: Boolean) = viewModelScope.launch {
        c.settings.setAudioPassthrough(on)
        showToast("Takes effect the next time Channelbox starts")
    }
    fun setAutoFrameRate(on: Boolean) = viewModelScope.launch { c.settings.setAutoFrameRate(on) }

    fun setVideoScale(scale: Int) = viewModelScope.launch { c.settings.setVideoScale(scale) }
    fun setMaxQuality(height: Int) = viewModelScope.launch { c.settings.setMaxQuality(height) }
    fun setPipOnLeave(on: Boolean) = viewModelScope.launch { c.settings.setPipOnLeave(on) }
    fun setIdleClose(seconds: Int) = viewModelScope.launch { c.settings.setIdleClose(seconds) }
    fun setGuideDays(days: Int) = viewModelScope.launch { c.settings.setGuideDays(days) }

    // ---- Network: VPN, proxy, DNS, user agent ----------------------------------------------

    val vpnState = VpnManager.state

    /** Proxy and DNS are built into the HTTP client at start-up, so changing them needs a restart. */
    fun networkNeedsRestart(now: NetworkSettings): Boolean {
        val start = c.startupNetwork
        return now.dns != start.dns || now.dnsCustomUrl != start.dnsCustomUrl ||
            now.proxyType != start.proxyType || now.proxyHost != start.proxyHost || now.proxyPort != start.proxyPort ||
            now.proxyUser != start.proxyUser || now.proxyPass != start.proxyPass
    }

    fun saveNetwork(n: NetworkSettings) = viewModelScope.launch { c.settings.saveNetwork(n) }

    /** Call after Android's VPN permission has been granted (or if it already was). */
    fun setVpnEnabled(on: Boolean) = viewModelScope.launch {
        val n = c.settings.current().network
        if (on && n.vpnConfig.isBlank()) {
            showToast("Add a WireGuard config first")
            return@launch
        }
        c.settings.saveNetwork(n.copy(vpnEnabled = on))
        if (on) {
            if (VpnManager.connect(getApplication(), n.vpnConfig, n.vpnAllApps)) {
                showToast("VPN connected")
                _current.value?.let { player.play(StreamSpecs.from(it)) } // reconnect through the tunnel
            }
        } else {
            VpnManager.disconnect()
            showToast("VPN off")
        }
    }

    /** Saves a WireGuard config (and reconnects if the VPN is on). Returns an error message, or null. */
    fun saveVpnConfig(config: String, allApps: Boolean): String? {
        VpnManager.validate(config)?.let { return it }
        viewModelScope.launch {
            val n = c.settings.current().network.copy(vpnConfig = config.trim(), vpnAllApps = allApps)
            c.settings.saveNetwork(n)
            if (n.vpnEnabled) {
                VpnManager.disconnect()
                VpnManager.connect(getApplication(), n.vpnConfig, n.vpnAllApps)
            }
        }
        return null
    }

    /** Downloads a WireGuard config from a link (e.g. one you uploaded or your provider's). */
    suspend fun fetchText(url: String): String = c.http.download(url) { it.bufferedReader().readText() }

    // ---- Recording storage ------------------------------------------------------------------

    fun saveStorage(st: StorageSettings) = viewModelScope.launch { c.settings.saveStorage(st) }

    suspend fun testStorage(st: StorageSettings): String? = c.storage.test(st)

    suspend fun describeStorage(st: StorageSettings): String = c.storage.describe(st)

    fun removableDrives(): Int = c.storage.removableDirectories().size

    // ---- Device info for Settings → About --------------------------------------------------

    fun widevineLevel(): String = runCatching {
        val drm = android.media.MediaDrm(androidx.media3.common.C.WIDEVINE_UUID)
        try {
            drm.getPropertyString("securityLevel")
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
        }
    }.getOrDefault("not available")

    /** Back to live TV after watching a recording. */
    // ---- Movies & TV shows -----------------------------------------------------------------

    /** Re-reads [read] whenever the library changes (after a reload, or when a watch position is saved). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> library(read: suspend () -> T): Flow<T> = repo.vodVersion.mapLatest { read() }

    val movieCount: StateFlow<Int> = library { repo.vod.count(VodKind.MOVIE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val showCount: StateFlow<Int> = library { repo.vod.count(VodKind.SERIES) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Categories shown in Movies / TV Shows (hidden ones left out). */
    fun vodCategories(kind: String): Flow<List<VodCategory>> =
        combine(library { repo.vod.categories(kind) }, overrides, zarkAdulto) { list, ov, _ ->
            list.filter { ov[vodKey(kind, it.name)]?.hidden != true && !adultoBloq(it.name) }
        }

    /** Every category, for choosing which ones show (Settings). */
    fun allVodCategories(kind: String): Flow<List<VodCategory>> = library { repo.vod.categories(kind) }

    fun isVodCategoryHidden(kind: String, category: String) = overrides.value[vodKey(kind, category)]?.hidden == true

    fun setVodCategoriesHidden(kind: String, categories: Collection<String>, hidden: Boolean) = viewModelScope.launch {
        if (categories.isEmpty()) return@launch
        repo.putOverrides(categories.map { c ->
            (overrides.value[vodKey(kind, c)] ?: OverrideEntity(vodKey(kind, c))).copy(hidden = hidden)
        })
    }
    fun vodContinueCount(kind: String): Flow<Int> = library { repo.vod.continueCount(kind) }

    /** ZarkTV: só os [n] primeiros títulos (fileiras da tela principal) – leve para TV box fraca. */
    fun vodTop(kind: String, category: String?, n: Int): Flow<List<VodEntity>> = library {
        when (category) {
            null -> repo.vod.allItems(kind, n * 3)
            VOD_CONTINUE -> repo.vod.continueWatching(kind)
            VOD_FAVORITOS -> repo.vod.byIds(ZarkPrefs.favoritos(getApplication()).toList()).filter { it.kind == kind }
            else -> repo.vod.categoryItems(kind, category, n * 3)
        }
    }.map { list ->
        list.filter { e ->
            val ov = overrides.value[vodKey(kind, e.category)]
            ov?.hidden != true && (unlocked || ov?.locked != true)
        }.filter { !adultoBloq(it.category, it.title) }.take(n)
    }

    /**
     * ZarkTV: destaques que mudam todo dia. Pega os 80 mais novos do servidor, dá preferência aos
     * lançamentos (ano atual ou anterior) com capa e nota, e sorteia [n] com uma semente do dia —
     * assim muda a cada dia, mas fica igual durante o mesmo dia (não pula a cada abertura).
     */
    fun vodDestaques(kind: String, n: Int): Flow<List<VodEntity>> = vodTop(kind, null, 80).map { lista ->
        val ano = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val comCapa = lista.filter { !it.poster.isNullOrBlank() }
        val recentes = comCapa.filter { (it.year?.take(4)?.toIntOrNull() ?: 0) >= ano - 1 }
        val base = (if (recentes.size >= n * 2) recentes else comCapa).distinctBy { it.title.lowercase().substringBefore(" (").substringBefore(" temp") }
        val dia = java.time.LocalDate.now().toEpochDay()
        // Os 3 mais novos sempre entram; o resto é sorteado do dia.
        (base.take(3) + base.drop(3).shuffled(java.util.Random(dia * 31 + kind.hashCode()))).take(n)
    }

    /**
     * ZarkTV Mobile (igual ao Web Play): destaques = os mais assistidos da semana no ZarkTV
     * (tv.zarktvqx.cfd/api/em-alta, "🔥 EM ALTA") + os que acabaram de chegar ("NOVO NO CATÁLOGO").
     * Muda sozinho conforme os clientes assistem e conforme entra filme novo no servidor.
     */
    fun vodDestaquesWeb(): Flow<List<Pair<VodEntity, String>>> = combine(vodTop(VodKind.MOVIE, null, 60), vodTop(VodKind.SERIES, null, 60)) { f, s -> f to s }
        .mapLatest { (filmes, series) ->
            val alta = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    c.http.newCall(okhttp3.Request.Builder().url("http://tv.zarktvqx.cfd/api/em-alta").build()).execute().use { r ->
                        if (!r.isSuccessful) emptyList() else (kotlinx.serialization.json.Json.parseToJsonElement(r.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                    }
                }
            }.getOrDefault(emptyList())
            fun chave(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()
            val pool = filmes + series
            val achados = alta.mapNotNull { t -> pool.firstOrNull { chave(it.title) == chave(t) } }.filter { !it.poster.isNullOrBlank() }.distinctBy { it.id }.take(4)
            val ids = achados.map { it.id }.toSet()
            achados.map { it to "EM ALTA" } +
                filmes.filter { !it.poster.isNullOrBlank() && it.id !in ids }.take(8 - achados.size).map { it to "NOVO NO CATÁLOGO" }
        }

    /** [category]: null = all, [VOD_CONTINUE] = started but not finished. */
    fun vodItems(kind: String, category: String?, query: String): Flow<List<VodEntity>> = libraryRaw(kind, category, query)
        .map { list ->
            // Titles from locked categories stay out of "All" and search until the PIN is entered.
            val browsingOneCategory = query.isBlank() && category != null && category != VOD_CONTINUE
            // Titles from hidden categories stay out of "All", search and "Continuar assistindo".
            val shown = if (browsingOneCategory) list else list.filter { overrides.value[vodKey(kind, it.category)]?.hidden != true }
            (if (unlocked || browsingOneCategory) shown
            else shown.filter { overrides.value[vodKey(kind, it.category)]?.locked != true })
                .filter { !adultoBloq(it.category, it.title) }
        }

    private fun libraryRaw(kind: String, category: String?, query: String): Flow<List<VodEntity>> = library {
        val q = query.trim()
        when {
            q.isNotEmpty() -> repo.vod.search(kind, q, VOD_SEARCH_LIMIT)
            category == VOD_CONTINUE -> repo.vod.continueWatching(kind)
            category == VOD_FAVORITOS -> repo.vod.byIds(ZarkPrefs.favoritos(getApplication()).toList()).filter { it.kind == kind }
            category == null -> repo.vod.allItems(kind, VOD_PAGE)
            else -> repo.vod.categoryItems(kind, category, VOD_PAGE)
        }
    }

    val vodProgress: StateFlow<Map<String, VodProgressEntity>> = repo.vod.observeProgress()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** ZarkTV Mobile: "Continue assistindo" — filmes e séries com progresso, os mais recentes primeiro. */
    fun vodContinuar(): kotlinx.coroutines.flow.Flow<List<VodEntity>> = vodProgress.map { m ->
        val ids = m.values.filter { it.duration <= 0 || it.position < it.duration * 0.95 }
            .sortedByDescending { it.updated }.map { it.showId ?: it.id }.distinct().take(15)
        if (ids.isEmpty()) emptyList() else runCatching { repo.vod.byIds(ids) }.getOrDefault(emptyList()).sortedBy { ids.indexOf(it.id) }
    }

    /** Episodes of a show; empty list with a message if the provider couldn't be reached. */
    suspend fun episodes(show: VodEntity): List<VodEntity> {
        // ZarkTV: o servidor às vezes responde vazio (série grande / servidor ocupado) → tenta de novo até 3 vezes.
        var ultimoErro: Exception? = null
        repeat(3) { tentativa ->
            try {
                val lista = repo.episodes(show)
                if (lista.isNotEmpty() || tentativa == 2) return lista
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ultimoErro = e
            }
            kotlinx.coroutines.delay(800L * (tentativa + 1))
        }
        if (ultimoErro != null) showToast("O servidor não enviou os episódios desta série agora. Volte e abra de novo em instantes.")
        return emptyList()
    }

    /** ZarkTV: ficha do filme/série (sinopse, elenco, capa de fundo) direto do servidor. */
    /** Endereço que outro aparelho (TV/download) consegue abrir: o direto, ou o do Web Play se o direto recusar. */
    suspend fun zarkUrlAcessivel(url: String): String {
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                c.http.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (ok) return url
        fun sid(n: String) = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "")
        val nome = settings.value?.playlists?.firstOrNull { it.enabled }?.name.orEmpty()
        return url.replace(Regex("""^https?://[^/]+"""), "http://tv.zarktvqx.cfd/v/" + sid(nome))
    }

    /** Baixa filme/episódio para ver sem internet. Se o endereço direto recusar (404), usa o caminho do Web Play. */
    private val idAparelho: String by lazy {
        val p = getApplication<Application>().getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE)
        p.getString("id_aparelho", null) ?: (java.util.UUID.randomUUID().toString().replace("-", "").take(16)).also { p.edit().putString("id_aparelho", it).apply() }
    }

    private fun tipoAparelho(): String {
        val cfg = getApplication<Application>().resources.configuration
        return when {
            io.github.axon07.channelbox.system.Aparelho.ehTv(cfg) -> "TV/Box (app)"
            cfg.smallestScreenWidthDp >= 600 -> "Tablet (app)"
            else -> "Celular (app)"
        }
    }

    /** O que está tocando agora (filme/episódio ou canal), para o painel. */
    private fun assistindoAgora(): String {
        if (!player.exo.isPlaying) return ""
        playingVod?.let { (item, _) -> return item.title }
        return _current.value?.name.orEmpty()
    }

    /** Mesmo aviso do Web Play (/api/acesso): entrou, vivo (a cada 2 min) e saiu. */
    fun zarkAvisarAcesso(ev: String) {
        val pl = settings.value?.playlists?.firstOrNull { it.enabled } ?: return
        val user = pl.xtUser?.takeIf { it.isNotBlank() } ?: return
        val corpo = kotlinx.serialization.json.buildJsonObject {
            put("ev", kotlinx.serialization.json.JsonPrimitive(ev))
            put("a", kotlinx.serialization.json.JsonPrimitive(idAparelho))
            put("t", kotlinx.serialization.json.JsonPrimitive(tipoAparelho()))
            put("u", kotlinx.serialization.json.JsonPrimitive(user))
            put("sv", kotlinx.serialization.json.JsonPrimitive(pl.name))
            put("vendo", kotlinx.serialization.json.JsonPrimitive(assistindoAgora()))
            put("exp", kotlinx.serialization.json.JsonPrimitive((zarkConta.value?.expira ?: 0L) / 1000))
            put("app", kotlinx.serialization.json.JsonPrimitive("ZarkTV ${io.github.axon07.channelbox.BuildConfig.VERSION_NAME}"))
        }.toString()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                c.http.newCall(
                    okhttp3.Request.Builder().url("http://tv.zarktvqx.cfd/api/acesso")
                        .post(corpo.toRequestBody("application/json".toMediaType())).build()
                ).execute().close()
            }
        }
    }

    /** Pergunta antes de baixar pelos dados móveis (no Wi-Fi não pergunta nada). */
    val zarkConfirmarDados = MutableStateFlow<(() -> Unit)?>(null)

    private fun usandoDadosMoveis(): Boolean = runCatching {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun zarkBaixar(item: VodEntity, titulo: String, capa: String?, confirmado: Boolean = false) {
        if (!confirmado && usandoDadosMoveis()) {
            zarkConfirmarDados.value = { zarkBaixar(item, titulo, capa, true) }
            return
        }
        val url = item.url ?: return showToast("Download deste título ainda está em desenvolvimento")
        val ctx = getApplication<Application>()
        if (io.github.axon07.channelbox.data.Baixados.baixado(ctx, item.id) != null) return showToast("Já está nos seus downloads (Perfil → Baixados)")
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    c.http.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build())
                        .execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
            val final = if (ok) url else {
                fun sid(n: String) = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "")
                val nome = settings.value?.playlists?.firstOrNull { it.enabled }?.name.orEmpty()
                url.replace(Regex("""^https?://[^/]+"""), "http://tv.zarktvqx.cfd/v/" + sid(nome))
            }
            val foi = io.github.axon07.channelbox.data.Baixados.baixar(ctx, c.http, item, titulo, capa, final)
            showToast(if (foi) "Baixando \"$titulo\" — acompanhe em Perfil → Baixados" else "Download deste título ainda está em desenvolvimento")
        }
    }

    suspend fun zarkFicha(item: VodEntity): io.github.axon07.channelbox.data.ZarkInfo? {
        val Ficha = io.github.axon07.channelbox.data.ZarkFicha
        return if (item.kind == VodKind.SERIES) {
            val p = c.settings.current().playlists.firstOrNull { it.id == item.playlistId && it.type == SourceType.XTREAM } ?: return null
            val id = item.seriesKey ?: return null
            Ficha.buscar(c.http, XtreamClient.Login(p.xtServer, p.xtUser, p.xtPass), null, id)
        } else {
            val url = item.url ?: return null
            val login = XtreamClient.Login.fromStreamUrl(url) ?: return null
            val id = url.substringAfterLast('/').substringBefore('.').takeIf { it.all(Char::isDigit) } ?: return null
            Ficha.buscar(c.http, login, id, null)
        }
    }

    /** Where to resume [item], or null to start from the beginning. */
    suspend fun vodResumeAt(item: VodEntity): Long? {
        val p = repo.vod.progress(item.id) ?: return null
        return p.position.takeIf { it > 20_000 && (p.duration <= 0 || it < p.duration * 0.95) }
    }

    private var playingVod: Pair<VodEntity, String?>? = null

    /** ZarkTV: true enquanto um filme/episódio está tocando (a troca automática de qualidade só vale para canais). */
    val tocandoFilme: Boolean get() = playingVod != null

    /** Plays a movie or episode; [showId] links an episode to its show for "Continuar assistindo". */
    fun playVod(item: VodEntity, startAt: Long, showId: String? = null) {
        endShift()
        val url = item.url ?: return
        if (playingVod != null && playingVod?.first?.id != item.id) saveVodProgress() // guarda o anterior antes de trocar
        playingVod = item to showId
        player.playOnDemand(StreamSpec(url, StreamSpecs.decodeHeaders(item.headers), null, null), startAt)
    }

    /** Remembers how far into the current movie/episode you got (cleared once it's nearly finished). */
    fun saveVodProgress() {
        val (item, showId) = playingVod ?: return
        val exo = player.exo
        val pos = exo.currentPosition
        val dur = exo.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L
        viewModelScope.launch {
            when {
                dur > 0 && pos >= dur * 0.95 -> repo.clearProgress(item.id)
                pos > 15_000 -> repo.saveProgress(VodProgressEntity(item.id, pos, dur, System.currentTimeMillis(), showId))
            }
        }
    }

    fun stopVod() {
        saveVodProgress()
        playingVod = null
    }

    fun resumeLive() {
        endShift()
        libraryOpen = false
        _current.value?.let { player.play(StreamSpecs.from(it)) }
    }

    /**
     * Movies & TV Shows browser open: live TV stops, so nothing streams until you press play on
     * something (most accounts allow only one or two streams at a time).
     */
    private var libraryOpen = io.github.axon07.channelbox.system.Aparelho.soCelular // celular abre no Início: nada toca até a aba TV

    fun enterLibrary() {
        endShift()
        libraryOpen = true
        if (player.isLive) player.stop()
    }

    fun leaveLibrary() {
        // Sem conta ou conta vencida: não volta a tocar canal nenhum.
        if (zarkVencida.value != null || settings.value?.playlists?.none { it.enabled } != false) return
        if (libraryOpen) resumeLive()
    }

    /**
     * ZarkTV: canal fora do ar (ex.: "Rede TV Gospel FHD²") → abre sozinho outra versão do mesmo canal
     * (FHD, HD, SD…) no mesmo grupo, e avisa.
     */
    private fun versaoAlternativa(ch: ChannelEntity): ChannelEntity? {
        fun raiz(n: String) = n.lowercase()
            .replace(Regex("""[²³¹]|\b(fhd|uhd|4k|hd|sd|h265|hevc|alt|backup)\b|\(.*?\)|\[.*?\]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val r = raiz(ch.name)
        if (r.isBlank()) return null
        val todos = allChannels.value
        val irmaos = todos.filter { it.id != ch.id && raiz(it.name) == r }
        val ordem = listOf("fhd", "hd", "sd", "4k", "uhd")
        return irmaos.sortedBy { c -> ordem.indexOfFirst { c.name.lowercase().contains(it) }.let { if (it < 0) 99 else it } }
            .firstOrNull { it.groupTitle == ch.groupTitle } ?: irmaos.firstOrNull()
    }
    private val jaTentados = mutableSetOf<String>()

    init {
        viewModelScope.launch { while (true) { delay(45_000); runCatching { conferirTela() } } }
        // Relatório de erros para o painel: quem (usuário), em qual servidor e o que estava assistindo.
        io.github.axon07.channelbox.player.ZarkRelatorio.contexto = {
            val pl = settings.value?.playlists?.firstOrNull { it.enabled }
            mapOf("usuario" to pl?.xtUser, "servidor" to pl?.name, "canal" to current.value?.name)
        }
        // ZarkTV: enquanto um filme/episódio toca, guarda a posição a cada 15 s (mesmo que o app feche de repente).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                if (playingVod != null && player.exo.isPlaying) saveVodProgress()
            }
        }
        // ZarkTV: monitor do painel — se o servidor do cliente estiver fora do ar, avisa no meio da tela
        // ("em manutenção") em vez de deixar o vídeo só carregando. Confere a cada 5 min.
        viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            var avisado: String? = null
            while (true) {
                val aviso = runCatching { servidorForaDoAr() }.getOrNull()
                // Mostra uma vez por aviso (de novo só se o texto mudar); some quando o servidor volta.
                if (aviso != null && aviso != avisado) zarkManutencao.value = aviso
                if (aviso == null) zarkManutencao.value = null
                avisado = aviso
                kotlinx.coroutines.delay(5 * 60_000L)
            }
        }
        // ZarkTV: avisa o painel (o mesmo do Web Play) que este aparelho está usando o app e o que
        // está assistindo — é daí que saem o "Em alta" e a lista de onde cada cliente assiste.
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000)
            zarkAvisarAcesso("entrou")
            while (true) {
                kotlinx.coroutines.delay(120_000)
                zarkAvisarAcesso("vivo")
            }
        }
        player.hostsAlternativos = { host ->
            val servidores = io.github.axon07.channelbox.data.ZarkConfig.cached(getApplication())
            val h = host.substringAfter("://").substringBefore(":").lowercase()
            val mesmo = servidores.filter { sv -> sv.dns.any { it.lowercase().contains(h) } }
            val bases = (mesmo + servidores).flatMap { it.dns }
            // Mantém a porta do endereço atual quando o DNS do painel não tem porta.
            val porta = Regex(""":(\d+)$""").find(host)?.value.orEmpty()
            val diretos = bases.map { b -> val t = b.trim().trimEnd('/'); if (Regex(""":\d+$""").containsMatchIn(t.substringAfter("://"))) t else t + porta }.distinct().take(6)
            // Por último, o mesmo caminho que o Web Play usa (tv.zarktvqx.cfd/v/<servidor>): o servidor
            // da ZarkTV busca o filme no IPTV e repassa. Resolve filme que dá 404 direto no aparelho.
            fun sid(n: String) = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "")
            val proxies = (mesmo.ifEmpty { listOfNotNull(servidores.firstOrNull { sv -> sid(sv.name) == sid(settings.value?.playlists?.firstOrNull { it.enabled }?.name.orEmpty()) }) })
                .map { "http://tv.zarktvqx.cfd/v/" + sid(it.name) }
            (diretos + proxies).distinct()
        }
        player.aoDesistir = {
            val ch = _current.value
            if (ch != null) {
                jaTentados += ch.id
                val alt = versaoAlternativa(ch)?.takeIf { it.id !in jaTentados }
                if (alt != null) {
                    showToast("${ch.name} está fora do ar. Abrindo ${alt.name}…")
                    tune(alt)
                }
            }
        }
        // A reload that fails while last time's channels are there: a short note, not a full-screen error.
        viewModelScope.launch {
            var wasRunning = false
            repo.state.collect { st ->
                if (st is RefreshState.Running) wasRunning = true
                if (st is RefreshState.Failed && wasRunning && allChannels.value.isNotEmpty()) {
                    showToast("Couldn't reload the playlist – showing the channels from last time (details in Settings → Playlist & guide)")
                }
                if (st !is RefreshState.Running) wasRunning = false
            }
        }
        // After a reload, the channel playing picks up its new details (catch-up, name, logo…)
        // without having to change channel.
        viewModelScope.launch {
            allChannels.collect { list ->
                val cur = _current.value ?: return@collect
                val fresh = list.firstOrNull { it.id == cur.id } ?: return@collect
                if (fresh != cur) _current.value = fresh
            }
        }
        player.storageSettings = { settings.value?.storage ?: StorageSettings() }
        player.captionsByDefault = { settings.value?.captionsByDefault ?: false }
        // Settings that apply immediately.
        viewModelScope.launch {
            c.settings.flow.collect { s ->
                UserAgent.apply(s.network.userAgent)
                player.setMaxQuality(s.maxQuality)
            }
        }
        // Startup update check, a few seconds in so it doesn't slow down tuning the first channel.
        viewModelScope.launch {
            delay(8_000)
            // ZarkTV: sempre confere na abertura (atualização obrigatória).
            checkForUpdates(manual = false)
        }
        viewModelScope.launch {
            val s = c.settings.current()
            // Bring the VPN up first so the playlist and streams go through it.
            if (s.network.vpnEnabled && s.network.vpnConfig.isNotBlank()) {
                if (VpnManager.permissionIntent(app) == null) {
                    if (!VpnManager.connect(app, s.network.vpnConfig, s.network.vpnAllApps)) {
                        showToast("VPN didn't connect – check Settings → Network")
                    }
                } else {
                    showToast("Allow the VPN in Settings → Network")
                }
            }
            // Clean up after playlists removed in an older version (or while a reload was running).
            runCatching { repo.dropRemovedPlaylists() }
            if (!s.hasSource) return@launch
            val hasChannels = repo.allChannels().isNotEmpty()
            // "Turn on last channel on app start" off: open on the guide without playing.
            val autoPlay = s.playLastOnStart
            if (hasChannels && autoPlay) ensurePlaying()
            val stale = s.refreshHours > 0 && System.currentTimeMillis() - s.lastRefresh > s.refreshHours * HOUR
            if (!hasChannels || stale) {
                val ready: suspend () -> Unit = {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (autoPlay) ensurePlaying() }
                }
                if (repo.refresh(ready) && autoPlay) ensurePlaying()
            }
        }
    }

    // ---- Guide search ----------------------------------------------------------------------

    data class GuideHit(val channel: ChannelEntity, val program: ProgramEntity)

    /** Programmes whose title contains [query], on your channels (hidden ones left out). */
    suspend fun searchGuide(query: String): List<GuideHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val byGuide = allChannels.value.filter { it.epgId != null }.groupBy { it.epgId!! }
        return repo.searchPrograms(q, System.currentTimeMillis() - 6 * HOUR)
            .flatMap { p -> byGuide[p.epgId].orEmpty().map { GuideHit(it, p) } }
            .take(200)
    }

    // ---- Pause live TV ---------------------------------------------------------------------

    /** Live TV paused or watched behind live. null = watching live. */
    data class LiveShift(
        /** Paused right now. */
        val paused: Boolean,
        /** The channel is being buffered (false for DRM/DASH: resuming rejoins live). */
        val buffered: Boolean,
        /** When the pause started. */
        val startedAt: Long,
        /** Playing from the buffer (after the first resume). */
        val onBuffer: Boolean,
        /**
         * Rewound through the provider's archive (catch-up): the wall-clock time the archive
         * stream playing starts at, and where it ends. Null = the pause buffer on this box.
         */
        val archiveFrom: Long? = null,
        val archiveTo: Long = 0,
    )

    private val timeshift = Timeshift(java.io.File(app.cacheDir, "timeshift"), c.recorder)
    private val _shift = MutableStateFlow<LiveShift?>(null)
    val shift: StateFlow<LiveShift?> = _shift.asStateFlow()

    /** Pause / resume live TV (Play/Pause on the remote). */
    fun playPauseLive() {
        val ch = _current.value ?: return
        val st = _shift.value
        when {
            st != null && st.archiveFrom != null -> {
                if (st.paused) player.exo.play() else player.exo.pause()
                _shift.value = st.copy(paused = !st.paused)
            }
            st == null -> {
                player.hold()
                val spec = StreamSpecs.from(ch)
                val buffered = timeshift.canBuffer(spec)
                if (buffered) timeshift.start(spec, viewModelScope)
                _shift.value = LiveShift(paused = true, buffered = buffered, startedAt = System.currentTimeMillis(), onBuffer = false)
                watchShift()
            }
            !st.paused -> {
                player.exo.pause()
                _shift.value = st.copy(paused = true)
            }
            !st.buffered && canRewind(ch) -> openArchive(ch, st.startedAt, paused = false) // via catch-up
            !st.buffered -> {
                showToast("This channel can't be paused – back to live")
                goLive()
            }
            !st.onBuffer -> {
                _shift.value = st.copy(paused = false)
                viewModelScope.launch {
                    // The buffer may only just have started: give it a few seconds.
                    var waited = 0
                    while (!timeshift.hasEnough() && timeshift.writing && waited < 8_000) {
                        delay(250)
                        waited += 250
                    }
                    if (_shift.value?.startedAt != st.startedAt) return@launch // went live meanwhile
                    val f = timeshift.file
                    if (f == null || !f.exists() || f.length() == 0L) {
                        val why = timeshift.lastError
                        showToast(if (why != null) "Couldn't buffer this channel ($why) – back to live" else "Nothing was buffered – back to live")
                        goLive()
                    } else {
                        if (timeshift.isPlaylist) player.playTimeshiftPlaylist(f)
                        else player.playTimeshift(f) { timeshift.writing }
                        _shift.value = st.copy(paused = false, onBuffer = true)
                    }
                }
            }
            else -> {
                player.exo.play()
                _shift.value = st.copy(paused = false)
            }
        }
        showBanner()
    }

    /** Drop the buffer and jump to what's on now. */
    fun goLive() {
        endShift()
        _current.value?.let { player.play(StreamSpecs.from(it)) }
        showBanner()
    }

    /** How far behind live the picture is. */
    fun behindLiveMs(): Long {
        val now = System.currentTimeMillis()
        val st = _shift.value
        val raw = when {
            st == null -> 0L
            st.archiveFrom != null -> now - (st.archiveFrom + player.exo.currentPosition)
            st.onBuffer -> (now - st.startedAt - player.exo.currentPosition)
            else -> now - st.startedAt
        }.coerceAtLeast(0)
        // While ◀◀ / ▶▶ presses are adding up, show where they'll land.
        return (raw + rewindPending).coerceAtLeast(0)
    }

    private fun endShift() {
        rewindJob?.cancel()
        rewindPending = 0
        _rewinding.value = false
        if (_shift.value == null && !timeshift.active) return
        timeshift.stop()
        _shift.value = null
    }

    private var shiftWatcher: Job? = null

    /**
     * Reaching the end of a buffer that's no longer growing (2-hour limit, stream lost) rejoins live.
     * In the provider's archive, the next part is opened until it has caught up with live.
     */
    private fun watchShift() {
        shiftWatcher?.cancel()
        shiftWatcher = viewModelScope.launch {
            while (_shift.value != null) {
                delay(1_000)
                val st = _shift.value ?: break
                if (st.onBuffer && !st.paused && player.exo.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    val ch = _current.value
                    if (st.archiveFrom != null && ch != null && System.currentTimeMillis() - st.archiveTo > 90_000) {
                        openArchive(ch, st.archiveTo, paused = false)
                    } else {
                        goLive()
                    }
                }
            }
        }
    }

    // ---- Rewinding live TV through the provider's archive (catch-up) ------------------------

    /** True when [channel] (default: the one playing) can be rewound – the provider keeps an archive. */
    fun canRewind(channel: ChannelEntity? = _current.value): Boolean =
        channel != null && Catchup.isAvailable(channel.catchupType, channel.catchupSource, channel.catchupDays)

    private var rewindPending = 0L
    private var rewindJob: Job? = null
    private val _rewinding = MutableStateFlow(false)

    /** True while ◀◀ / ▶▶ presses are adding up (before the archive opens). */
    val rewinding: StateFlow<Boolean> = _rewinding.asStateFlow()

    /**
     * ◀◀ / ▶▶ on live TV: moves [deltaMs] through the provider's archive (negative = back).
     * Quick presses (or holding the key) add up, and the archive opens once they stop.
     */
    fun skipLive(deltaMs: Long) {
        val ch = _current.value ?: return
        if (!canRewind(ch)) {
            if (deltaMs < 0) showToast("This channel can't be rewound – it has no catch-up")
            return
        }
        if (deltaMs > 0 && _shift.value == null) return // already live
        rewindPending -= deltaMs
        _rewinding.value = true
        rewindJob?.cancel()
        rewindJob = viewModelScope.launch {
            delay(700)
            val behind = behindLiveMs().coerceAtMost(ch.catchupDays * 24 * HOUR - 5 * 60_000L)
            rewindPending = 0
            _rewinding.value = false
            playBehindLive(ch, behind)
        }
        showBanner()
    }

    private fun playBehindLive(ch: ChannelEntity, behind: Long) {
        if (behind < 20_000) {
            goLive()
            return
        }
        val st = _shift.value
        val from = System.currentTimeMillis() - behind
        val start = st?.archiveFrom
        if (st != null && start != null && from >= start && from < st.archiveTo - 5_000) {
            player.exo.seekTo(from - start) // inside the part already open: just seek
            return
        }
        openArchive(ch, from, paused = st?.paused == true)
    }

    /** Plays the channel from the provider's archive, starting at wall-clock time [from]. */
    private fun openArchive(ch: ChannelEntity, from: Long, paused: Boolean) {
        // Archive links are per minute on most providers (Xtream): open at the minute, then seek.
        val start = from - from % 60_000
        val end = maxOf(System.currentTimeMillis(), start + 60_000)
        val live = StreamSpecs.from(ch)
        val url = Catchup.buildUrl(ch.streamUrl, ch.catchupType, ch.catchupSource, start, end)
        if (url == null) {
            showToast("Catch-up isn't available for this channel")
            goLive()
            return
        }
        if (timeshift.active) timeshift.stop()
        val archive = StreamSpecs.parse(url, live.headers, ch.drmType, ch.drmKey, null)
        player.playOnDemand(StreamSpec(archive.url, archive.headers, archive.drm, archive.mime), startAt = from - start)
        if (paused) player.exo.pause()
        _shift.value = LiveShift(
            paused = paused, buffered = true, startedAt = from, onBuffer = true,
            archiveFrom = start, archiveTo = end,
        )
        watchShift()
        showBanner()
    }

    // ---- Playback -------------------------------------------------------------------------

    /**
     * Runs [action] straight away, or after the PIN if [channel] is locked – for everything that
     * shows a channel other than tuning it (multi-view tiles, catch-up, recordings).
     */
    fun withChannelAccess(channel: ChannelEntity?, action: () -> Unit) {
        if (channel != null && isLocked(channel) && !unlocked) {
            requirePin("Digite sua senha para assistir ${channel.name}", forChannel = true, action = action)
        } else {
            action()
        }
    }

    /** The channel a recording came from, if it's still in the lineup. */
    suspend fun channelOf(recording: RecordingEntity): ChannelEntity? =
        repo.allChannels().firstOrNull { it.id == recording.channelId }

    fun tune(channel: ChannelEntity) {
        if (isLocked(channel) && !unlocked) {
            requirePin("Digite sua senha para assistir ${channel.name}", forChannel = true) { tune(channel) }
            return
        }
        if (_shift.value != null) {
            endShift()
            if (_current.value?.id == channel.id) {
                player.play(StreamSpecs.from(channel)) // same channel: straight back to live
                showBanner()
                return
            }
        }
        if (_current.value?.id != channel.id) {
            if (jaTentados.size > 20) jaTentados.clear()
            previous = _current.value
            _current.value = channel
            // ZarkTV Mobile: fora da aba TV só escolhe o canal; ele toca quando a aba TV abrir
            // (nada de canal tocando escondido no Início, nos filmes ou no perfil).
            if (!(io.github.axon07.channelbox.system.Aparelho.soCelular && libraryOpen)) player.play(StreamSpecs.from(channel))
            viewModelScope.launch { c.settings.setLastChannel(channel.id) }
        } else if (!player.isLive || (io.github.axon07.channelbox.system.Aparelho.soCelular && !libraryOpen && !player.exo.isPlaying)) {
            if (!(io.github.axon07.channelbox.system.Aparelho.soCelular && libraryOpen)) player.play(StreamSpecs.from(channel))
        }
        showBanner()
    }

    fun showBanner() {
        _bannerToken.value++
    }

    /** Channel up/down within the current group, wrapping around. */
    fun zap(delta: Int) {
        val visible = visibleChannels.value
        val list = if (visible.any { it.id == _current.value?.id }) visible else allChannels.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _current.value?.id }
        var next = if (index < 0) 0 else (index + delta).mod(list.size)
        // Skip locked channels while zapping.
        if (!unlocked) {
            var tries = 0
            while (isLocked(list[next]) && tries++ < list.size) next = (next + delta).mod(list.size)
        }
        tune(list[next])
    }

    fun tuneNumber(number: Int): Boolean {
        val ch = allChannels.value.firstOrNull { it.number == number } ?: return false
        tune(ch)
        return true
    }

    fun previousChannel() {
        val p = previous?.let { prev -> allChannels.value.firstOrNull { it.id == prev.id } }
        if (p == null) {
            showToast("No previous channel yet")
            return
        }
        tune(p)
    }

    suspend fun ensurePlaying() {
        if (_current.value != null) return
        val list = repo.allChannels()
        if (list.isEmpty()) return
        val last = c.settings.current().lastChannelId
        // Starting up: never open on a locked channel (no PIN prompt for whoever turns the TV on).
        val usable = if (unlocked) list else list.filter { !isLocked(it) }.ifEmpty { list }
        tune(usable.firstOrNull { it.id == last } ?: usable.first())
    }

    fun onForeground() {
        // Sem conta, conta vencida ou na tela de entrada: nada volta a tocar.
        if (zarkVencida.value != null || settings.value?.playlists?.none { it.enabled } != false) return
        // Live TV paused (not yet playing the buffer): stay paused – restarting the live stream here
        // would play live behind the pause and use a second connection.
        val st = _shift.value
        if (st != null && !st.onBuffer) return
        if (!(libraryOpen && player.isLive)) player.resume()
    }
    fun onBackground() {
        saveVodProgress() // saiu do app no meio do filme: guarda onde parou
        player.stop()
    }

    override fun onCleared() {
        player.release()
    }

    // ---- Lists, guide, favourites ---------------------------------------------------------

    fun setFilter(filter: ChannelFilter) {
        _filter.value = filter
    }

    fun cycleFilter(delta: Int) {
        val list = filters.value
        val index = list.indexOf(_filter.value).coerceAtLeast(0)
        _filter.value = list[(index + delta).mod(list.size)]
    }

    fun toggleFavorite(channel: ChannelEntity) {
        val fav = channel.id !in favorites.value
        viewModelScope.launch { repo.setFavorite(channel.id, fav) }
    }

    fun setGuideStart(time: Long) {
        _guideStart.value = roundDown(time)
    }

    // ---- Settings -------------------------------------------------------------------------

    /** The reload in progress. A newer change cancels it rather than queueing behind it. */
    private var reloadJob: kotlinx.coroutines.Job? = null

    /** Saves the Jellyfin settings and reloads, so the library appears (or goes) straight away. */
    fun saveJellyfin(j: io.github.axon07.channelbox.data.JellyfinSettings) = reload { c.settings.saveJellyfin(j) }

    /** Signs in to check the details; returns an error message or null if it works. */
    suspend fun testJellyfin(j: io.github.axon07.channelbox.data.JellyfinSettings): String? = try {
        io.github.axon07.channelbox.data.JellyfinClient(c.http, j).signIn()
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun reload(change: suspend () -> Unit) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            change()
            reloadAfterSourceChange()
        }
    }

    val playlistErrors: StateFlow<Map<String, String>> get() = repo.playlistErrors

    fun refreshNow() = reload { }

    fun savePlaylist(p: Playlist) = reload { c.settings.savePlaylist(p) }

    // ---- ZarkTV: servidores do painel, troca automática de DNS e vencimento ----------------

    val zarkHttp get() = c.http

    /** true = o painel diz que o servidor deste cliente está fora do ar (mostra o aviso de manutenção). */
    val zarkManutencao = MutableStateFlow<String?>(null)

    /** Texto do aviso do servidor deste cliente ("" = aviso padrão de manutenção), ou null se está tudo certo. */
    private suspend fun servidorForaDoAr(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = io.github.axon07.channelbox.data.ZarkConfig.checkoutBase(getApplication()) ?: return@withContext null
        val txt = c.http.newCall(okhttp3.Request.Builder().url("$base/api/status").build()).execute().use { it.body?.string() } ?: return@withContext null
        val o = kotlinx.serialization.json.Json.parseToJsonElement(txt) as? kotlinx.serialization.json.JsonObject ?: return@withContext null
        val servidores = o["servidores"] as? kotlinx.serialization.json.JsonObject ?: return@withContext null
        fun sid(n: String) = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "")
        val meu = sid(settings.value?.playlists?.firstOrNull { it.enabled }?.name.orEmpty())
        if (meu.isEmpty()) return@withContext null
        val st = servidores.entries.firstOrNull { sid(it.key) == meu || meu.contains(sid(it.key)) }?.value as? kotlinx.serialization.json.JsonObject
            ?: return@withContext null
        if ((st["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false") return@withContext null
        (st["msg"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    }

    /** Conta conferida no player_api (vencimento e status), ou null enquanto não conferiu. */
    val zarkConta = MutableStateFlow<ZarkConfig.Login.Ok?>(null)
    private var zarkConferido = false

    /**
     * Na abertura: confere a conta do servidor ZarkTV. Se o DNS salvo não responde, testa os
     * outros DNS do mesmo servidor (do config.json) e troca sozinho para o que funcionar.
     */
    /** Conta vencida: o app bloqueia tudo e mostra como renovar. Guarda a data em que venceu (0 = sem data). */
    val zarkVencida = MutableStateFlow<Long?>(null)
    private var vigiaVencimento = false

    /** Confere o vencimento sozinho: pela data guardada (a cada minuto, mesmo sem internet) e no servidor (a cada 15 min). */
    fun zarkVigiarVencimento() {
        if (vigiaVencimento) return
        vigiaVencimento = true
        viewModelScope.launch {
            var minutos = 0
            while (true) {
                val exp = ZarkConfig.vencimentoSalvo(getApplication())
                if (exp != null && exp < System.currentTimeMillis()) zarkVencida.value = exp
                if (minutos % 15 == 0) zarkConferir(forcar = true)
                minutos++
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    fun zarkConferir(forcar: Boolean = false) {
        if (zarkConferido && !forcar) return
        zarkConferido = true
        viewModelScope.launch {
            val s = c.settings.current()
            val p = s.playlists.firstOrNull { it.type == SourceType.XTREAM && it.enabled }
                ?: return@launch
            val servers = ZarkConfig.servers(getApplication(), c.http)
            val grupo = servers.firstOrNull { it.name.equals(p.name, ignoreCase = true) }
            // Guia de programação do painel mudou → a playlist passa a usar o novo (vale na próxima atualização diária).
            val epgPainel = ZarkConfig.epgDoServidor(getApplication(), p.name)
            if (epgPainel.isNotBlank() && epgPainel != p.epgUrl) savePlaylist(p.copy(epgUrl = epgPainel))
            val atual = XtreamClient.Login(p.xtServer, p.xtUser, p.xtPass).base
            // O DNS salvo primeiro; depois os outros do mesmo servidor.
            val dns = (listOf(atual) + grupo?.dns.orEmpty()).distinct()
            when (val r = ZarkConfig.login(c.http, ZarkConfig.Server(p.name, dns), p.xtUser, p.xtPass)) {
                is ZarkConfig.Login.Vencida -> {
                    ZarkConfig.salvarVencimento(getApplication(), r.expira ?: 1L)
                    zarkVencida.value = r.expira ?: 0L
                }
                is ZarkConfig.Login.Ok -> {
                    zarkConta.value = r
                    ZarkConfig.salvarVencimento(getApplication(), r.expira)
                    zarkVencida.value = if (ZarkConfig.vencida(r)) (r.expira ?: 0L) else null
                    if (r.dns != atual) {
                        showToast("Servidor trocado automaticamente")
                        savePlaylist(p.copy(xtServer = r.dns))
                    }
                }
                else -> Unit
            }
        }
    }

    /** Liga/desliga favorito de filme/série e atualiza as listas. */
    fun zarkFavoritar(item: VodEntity): Boolean {
        val on = ZarkPrefs.alternarFavorito(getApplication(), item.id)
        repo.vodChanged()
        showToast(if (on) "Adicionado aos favoritos" else "Removido dos favoritos")
        return on
    }

    /** Idioma e resolução escolhidos na ficha (valem para filmes e séries). */
    fun zarkAplicarPreferencias() {
        val ctx = getApplication<Application>()
        val b = player.exo.trackSelectionParameters.buildUpon()
        when (ZarkPrefs.idioma(ctx)) {
            "pt" -> b.setPreferredAudioLanguages("pt", "por", "pt-BR").setPreferredTextLanguage(null)
            "leg" -> b.setPreferredAudioLanguages("en", "eng", "ja", "ko", "es").setPreferredTextLanguages("pt", "por", "pt-BR")
            else -> b.setPreferredAudioLanguage(null)
        }
        when (val l = ZarkPrefs.legIdioma(ctx)) {
            "off" -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            "pt" -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages("pt", "por", "pt-BR")
            "es" -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages("es", "spa")
            "en" -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages("en", "eng")
            else -> Unit
        }
        player.exo.trackSelectionParameters = b.build()
        val r = ZarkPrefs.resolucao(ctx)
        if (r != 0) player.setMaxQuality(if (r < 0) 0 else r)
    }

    /** Séries seguidas com episódio novo desde a última vez (para o sininho). */
    val zarkNovidades = MutableStateFlow<List<String>>(emptyList())
    private var novidadesConferidas = false

    fun zarkConferirNovidades() {
        if (novidadesConferidas) return
        novidadesConferidas = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val seguidas = ZarkPrefs.seguidas(ctx)
            if (seguidas.isEmpty()) return@launch
            val series = repo.vod.byIds(seguidas.keys.toList())
            val novas = mutableListOf<String>()
            for (s in series) {
                val n = runCatching { repo.episodes(s).size }.getOrDefault(0)
                val antes = seguidas[s.id] ?: 0
                if (n > antes && antes > 0) novas += "${s.title}: ${n - antes} episódio(s) novo(s)"
                if (n > 0) ZarkPrefs.seguir(ctx, s.id, n)
            }
            zarkNovidades.value = novas
        }
    }

    // ---- Uma tela por conta: a cada 45 s, enquanto toca, avisa o painel; se a conta estiver em outro
    // aparelho (e o painel decidir que este para), pausa e mostra o aviso com "Assistir aqui".
    val zarkOutraTela = MutableStateFlow<String?>(null)
    private var sessaoInicio = System.currentTimeMillis()
    private val aparelhoId: String by lazy {
        runCatching { android.provider.Settings.Secure.getString(getApplication<Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID) }
            .getOrNull().orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
    }
    private suspend fun conferirTela() {
        if (!player.exo.isPlaying) return
        val pl = settings.value?.playlists?.firstOrNull { it.enabled && it.xtUser.isNotBlank() } ?: return
        val corpo = kotlinx.serialization.json.buildJsonObject {
            put("usuario", kotlinx.serialization.json.JsonPrimitive(pl.xtUser.trim()))
            put("servidor", kotlinx.serialization.json.JsonPrimitive(pl.name))
            put("aparelho", kotlinx.serialization.json.JsonPrimitive(aparelhoId))
            put("modelo", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL.orEmpty()))
            put("inicio", kotlinx.serialization.json.JsonPrimitive(sessaoInicio))
        }.toString()
        val resposta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                c.http.newCall(
                    okhttp3.Request.Builder().url("https://painel.zarktvqx.cfd/checkout/api/sessao")
                        .post(corpo.toRequestBody("application/json".toMediaType())).build()
                ).execute().use { r -> r.body?.string().orEmpty() }
            }.getOrNull()
        } ?: return // painel fora do ar: não atrapalha quem está assistindo
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(resposta) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
        if ((o["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "false") {
            runCatching { player.exo.pause() }
            zarkOutraTela.value = (o["outro"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "outro aparelho"
        }
    }
    /** "Assistir aqui": este aparelho passa a ser o que assiste (o outro para no próximo aviso dele). */
    fun zarkAssistirAqui() {
        sessaoInicio = System.currentTimeMillis()
        zarkOutraTela.value = null
        runCatching { player.exo.play() }
        viewModelScope.launch { delay(1_500); runCatching { conferirTela() } }
    }

    /** Aviso depois de liberar/bloquear o Hot (+18): título e texto, mostrado num cartão com "Entendi". */
    val zarkAvisoHot = MutableStateFlow<Pair<String, String>?>(null)

    /** Liberar/bloquear o conteúdo Hot (+18): pede a senha nos dois casos (cria uma na primeira vez) e confirma o que mudou. */
    fun zarkAlternarAdulto() {
        val ctx = getApplication<Application>()
        if (zarkAdulto.value) {
            val bloquear = {
                ZarkPrefs.setAdulto(ctx, false)
                zarkAdulto.value = false
                repo.vodChanged()
                zarkAvisoHot.value = "Hot +18 bloqueado" to "A aba Hot e os canais e filmes adultos saíram da tela inicial. Para ver de novo, libere com a sua senha em Perfil → Hot +18."
            }
            _pinRequest.value = if (hasPin) PinRequest("Hot +18: digite a senha para BLOQUEAR.\nA aba Hot vai sair da tela inicial.", onSuccess = bloquear)
            else { bloquear(); null }
            return
        }
        val liberar = {
            ZarkPrefs.setAdulto(ctx, true)
            zarkAdulto.value = true
            repo.vodChanged()
            zarkAvisoHot.value = "Hot +18 liberado" to "A aba Hot e os canais e filmes adultos agora aparecem na tela inicial deste aparelho. Para esconder de novo, vá em Perfil → Hot +18 e digite a senha."
        }
        // Hot (+18): ao liberar, a aba "Hot +18" aparece na tela inicial do app.
        _pinRequest.value = if (hasPin) PinRequest("Hot +18: digite a senha para liberar.\nA aba Hot vai aparecer na tela inicial.", onSuccess = liberar)
        else PinRequest("Hot +18: crie uma senha de 4 números.\nDepois de liberado, a aba Hot aparece na tela inicial.", setup = true, onSuccess = liberar)
    }

    fun zarkLimparConta() {
        zarkAvisarAcesso("saiu")
        player.esquecer()
        zarkConta.value = null
        zarkVencida.value = null
        ZarkConfig.salvarVencimento(getApplication(), null)
        zarkConferido = false
    }

    fun removePlaylist(id: String) = reload {
        // Finish the clean-up even if another change (e.g. adding a playlist) starts a new reload.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            c.settings.removePlaylist(id)
            repo.forgetPlaylist(id)
        }
    }

    /** Throws away every saved channel, movie & show and guide entry, then loads the playlists fresh. */
    fun clearSavedData() = reload {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { repo.clearSavedData() }
    }

    fun setPlaylistEnabled(id: String, on: Boolean) = reload { c.settings.setPlaylistEnabled(id, on) }

    fun movePlaylist(id: String, delta: Int) = reload { c.settings.movePlaylist(id, delta) }

    private suspend fun reloadAfterSourceChange() {
        // Runs once the channels are saved – the guide may still be loading.
        val channelsReady: suspend () -> Unit = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val ids = repo.allChannels().map { it.id }.toSet()
                // Keep watching the same channel if it's still there (e.g. after adding a second playlist).
                if (_current.value?.id !in ids) {
                    if (_current.value != null) player.stop()
                    _current.value = null
                    previous = null
                }
                if (_filter.value != ChannelFilter.All && _filter.value !in filters.value) _filter.value = ChannelFilter.All
                ensurePlaying()
            }
        }
        if (repo.refresh(channelsReady)) ensurePlaying()
    }

    fun setStartOnBoot(on: Boolean) = viewModelScope.launch { c.settings.setStartOnBoot(on) }
    fun setStartOnWake(on: Boolean) = viewModelScope.launch { c.settings.setStartOnWake(on) }
    fun setPlayLastOnStart(on: Boolean) = viewModelScope.launch { c.settings.setPlayLastOnStart(on) }
    fun setShowChannelNumbers(on: Boolean) = viewModelScope.launch { c.settings.setShowChannelNumbers(on) }

    // ---- Backup & restore -------------------------------------------------------------------

    fun backupFileName() = c.backup.suggestedName()

    fun backupTo(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { c.backup.writeTo(uri, c.backup.create()) }
            .onSuccess { showToast("Backup saved") }
            .onFailure { showToast("Backup failed: ${it.message}") }
    }

    /** No file picker on this box: save to the Download folder instead. */
    fun backupToDownloads() = viewModelScope.launch {
        runCatching { c.backup.saveToDownloads(c.backup.create()) }
            .onSuccess { showToast("Backup saved to $it") }
            .onFailure { showToast("Backup failed: ${it.message}") }
    }

    fun restoreFrom(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { c.backup.restore(c.backup.readFrom(uri)) }
            .onSuccess { afterRestore() }
            .onFailure { showToast("Couldn't restore: ${it.message}") }
    }

    /** No file picker on this box: restore the newest backup in the Download folder. */
    fun restoreLatest() = viewModelScope.launch {
        val text = runCatching { c.backup.readLatestFromDownloads() }.getOrNull()
        if (text == null) {
            showToast("No backup found in the Download folder")
            return@launch
        }
        runCatching { c.backup.restore(text) }
            .onSuccess { afterRestore() }
            .onFailure { showToast("Couldn't restore: ${it.message}") }
    }

    private fun afterRestore() {
        showToast("Backup restored – reloading channels")
        viewModelScope.launch {
            val code = c.settings.current().language
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { I18n.load(getApplication(), code) }
        }
        reload { }
    }
    fun setInvertChannelKeys(on: Boolean) = viewModelScope.launch { c.settings.setInvertChannelKeys(on) }
    fun setCaptionsByDefault(on: Boolean) = viewModelScope.launch { c.settings.setCaptionsByDefault(on) }

    /** Switches the app's language straight away. */
    fun setLanguage(code: String) = viewModelScope.launch {
        c.settings.setLanguage(code)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { I18n.load(getApplication(), code) }
    }
    fun setBackAction(action: BackAction) = viewModelScope.launch { c.settings.setBackAction(action) }

    fun setRefreshHours(hours: Int) = viewModelScope.launch {
        c.settings.setRefreshHours(hours)
        RefreshWorker.schedule(getApplication<Application>(), hours)
    }

    /** Steps through 6 h → 12 h → 24 h → 48 h → off. */
    fun cycleRefreshHours(currentHours: Int) = viewModelScope.launch {
        val index = REFRESH_CHOICES.indexOf(currentHours)
        val next = REFRESH_CHOICES[(index + 1).mod(REFRESH_CHOICES.size)]
        c.settings.setRefreshHours(next)
        RefreshWorker.schedule(getApplication<Application>(), next)
    }

    companion object {
        const val SECTION_ALL = "\u0000all"
        fun channelKey(id: String) = "ch:$id"
        fun groupKey(name: String) = "grp:$name"
        fun vodKey(kind: String, category: String) = "vod:$kind:$category"
        private fun hashPin(pin: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest("channelbox-pin:$pin".toByteArray())
                .joinToString("") { "%02x".format(it) }
        /** How long locked things stay open after the right PIN. */
        const val UNLOCK_MS = 2 * HOUR

        private fun playlistOf(ch: ChannelEntity): String =
            ch.playlistId ?: io.github.axon07.channelbox.data.SettingsStore.LEGACY_ID
        fun roundDown(time: Long) = time - time.mod(GUIDE_STEP)
        fun recordingKey(channelId: String, programStart: Long) = "$channelId@$programStart"
    }
}
