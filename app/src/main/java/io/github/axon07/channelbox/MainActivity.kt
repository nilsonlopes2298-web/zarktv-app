package io.github.axon07.channelbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.content.res.Configuration
import io.github.axon07.channelbox.system.AutoFrameRate
import io.github.axon07.channelbox.system.SetTopBox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.axon07.channelbox.ui.ChannelboxTheme
import io.github.axon07.channelbox.ui.TvApp
import io.github.axon07.channelbox.ui.TvViewModel

class MainActivity : ComponentActivity() {

    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ChannelboxTheme {
                TvApp(vm)
            }
        }
        // Auto frame rate: follow the playing video's frame rate when switched on (waits a moment
        // so flicking through channels doesn't make the TV switch modes for every one).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.player.frameRate, vm.settings) { fps, s -> if (s?.autoFrameRate == true) fps else 0f }
                    .distinctUntilChanged()
                    .collectLatest { fps ->
                        if (fps > 0f) delay(1_500)
                        AutoFrameRate.apply(this@MainActivity, fps)
                    }
            }
        }
    }

    /** Home pressed while watching: keep playing in a small window if that's switched on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.settings.value?.pipOnLeave == true && vm.current.value != null && vm.player.exo.isPlaying) {
            SetTopBox.enterPip(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip.value = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        onScreen = true
        vm.onForeground()
        vm.resumePendingUpdate()
    }

    override fun onStop() {
        // Stop the stream when the user leaves (Home, screensaver) so it doesn't keep downloading.
        onScreen = false
        vm.onBackground()
        super.onStop()
    }

    companion object {
        /** True while Channelbox is on screen (so boot / wake don't start it twice). */
        @Volatile
        var onScreen = false
    }
}
