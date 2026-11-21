package io.github.axon07.channelbox.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * What HDR the TV (the screen the box is plugged into) and the box itself can show.
 * The player uses it to decide whether Dolby Vision is played as Dolby Vision or as HDR10.
 */
object Hdr {

    /** HDR types the connected screen accepts (Display.HdrCapabilities.HDR_TYPE_*). */
    fun displayTypes(context: Context): Set<Int> {
        if (Build.VERSION.SDK_INT < 24) return emptySet()
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return emptySet()
        val types = mutableSetOf<Int>()
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes?.let { types += it.toList() }
        if (Build.VERSION.SDK_INT >= 34) {
            display.supportedModes.forEach { mode -> types += mode.supportedHdrTypes.toList() }
        }
        return types
    }

    fun displaySupportsDolbyVision(context: Context) =
        Build.VERSION.SDK_INT >= 24 && Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayTypes(context)

    /** True when the box has a Dolby Vision video decoder. */
    fun hasDolbyVisionDecoder(): Boolean = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/dolby-vision", ignoreCase = true) }
        }
    } catch (_: Exception) {
        false
    }

    /** "Dolby Vision · HDR10 · HLG", or null when the screen is SDR only. */
    fun displayLabel(context: Context): String? {
        if (Build.VERSION.SDK_INT < 24) return null
        val types = displayTypes(context)
        return listOfNotNull(
            "Dolby Vision".takeIf { Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types },
            "HDR10".takeIf { Display.HdrCapabilities.HDR_TYPE_HDR10 in types },
            "HDR10+".takeIf { Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types },
            "HLG".takeIf { Display.HdrCapabilities.HDR_TYPE_HLG in types },
        ).joinToString(" · ").ifEmpty { null }
    }
}
