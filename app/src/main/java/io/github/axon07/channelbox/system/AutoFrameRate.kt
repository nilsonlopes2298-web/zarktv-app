package io.github.axon07.channelbox.system

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto frame rate: asks the TV for a display mode whose refresh rate matches the video
 * (24p film at 23.976 / 24 Hz, 25 / 50 fps at 50 Hz, 29.97 / 59.94 fps at 59.94 Hz), at the
 * same resolution as now. Uses the window's preferred display mode, which Android TV and
 * Fire TV boxes honour; nothing happens on boxes that offer only one refresh rate.
 */
object AutoFrameRate {

    /** Switches to the best mode for [fps], or back to the box's default when [fps] is 0. */
    fun apply(activity: Activity, fps: Float) {
        if (Build.VERSION.SDK_INT < 23) return
        val window = activity.window ?: return
        val display = activity.windowManager.defaultDisplay ?: return
        val wanted = if (fps > 0f) bestMode(display, fps)?.modeId ?: 0 else 0
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == wanted) return
        attrs.preferredDisplayModeId = wanted
        window.attributes = attrs
    }

    private fun bestMode(display: Display, fps: Float): Display.Mode? {
        if (Build.VERSION.SDK_INT < 23) return null
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // A refresh rate that's a whole multiple of the frame rate (1×, 2×, …), lowest first.
        return sameSize
            .mapNotNull { mode ->
                val ratio = mode.refreshRate / fps
                val whole = ratio.roundToInt()
                if (whole in 1..3 && abs(ratio - whole) < 0.01f) mode to whole else null
            }
            .sortedWith(compareBy({ it.second }, { abs(it.first.refreshRate - current.refreshRate) }))
            .firstOrNull()?.first
    }
}
