package io.github.axon07.channelbox.system

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Built-in WireGuard VPN. Paste or load a WireGuard config from any VPN provider that offers one
 * (most do). By default only Channelbox's own traffic goes through it, so the rest of the box
 * is unaffected – handy when an ISP blocks IPTV.
 */
object VpnManager {

    sealed interface State {
        data object Off : State
        data object Connecting : State
        data object On : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName() = "channelbox"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _state.value == State.On) _state.value = State.Off
        }
    }

    /** Android asks once whether Channelbox may create a VPN. Null = already allowed. */
    fun permissionIntent(context: Context): Intent? = VpnService.prepare(context)

    /** Checks a config without connecting. Returns an error message, or null if it's fine. */
    fun validate(config: String): String? = try {
        Config.parse(ByteArrayInputStream(config.toByteArray()))
        null
    } catch (e: Exception) {
        e.message ?: "This isn't a valid WireGuard config"
    }

    suspend fun connect(context: Context, config: String, allApps: Boolean): Boolean = withContext(Dispatchers.IO) {
        _state.value = State.Connecting
        try {
            val text = if (allApps) config else onlyThisApp(config, context.packageName)
            val parsed = Config.parse(ByteArrayInputStream(text.toByteArray()))
            val b = backend ?: GoBackend(context.applicationContext).also { backend = it }
            b.setState(tunnel, Tunnel.State.UP, parsed)
            _state.value = State.On
            true
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
        _state.value = State.Off
    }

    /** Adds "IncludedApplications = <us>" unless the config already chooses apps itself. */
    internal fun onlyThisApp(config: String, packageName: String): String {
        if (Regex("(?im)^\\s*(Included|Excluded)Applications\\s*=").containsMatchIn(config)) return config
        val lines = config.lines().toMutableList()
        val index = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (index < 0) return config
        lines.add(index + 1, "IncludedApplications = $packageName")
        return lines.joinToString("\n")
    }
}
