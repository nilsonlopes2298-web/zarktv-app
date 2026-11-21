package io.github.axon07.channelbox.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.axon07.channelbox.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Opens live TV when the box powers on, if "Start on boot" is enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.container.settings.current().startOnBoot) {
                    // The stock launcher starts at the same time and can cover us: keep trying for ~25 s.
                    SetTopBox.bringToFront(context, listOf(3_000L, 4_000L, 6_000L, 12_000L))
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
