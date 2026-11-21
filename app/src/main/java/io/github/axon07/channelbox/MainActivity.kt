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
        telaCheia()
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

    /** ZarkTV: tela cheia em qualquer aparelho – esconde as barras do sistema (a barrinha branca de baixo). */
    private fun telaCheia() {
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) telaCheia()
    }

    /** Home pressed while watching: keep playing in a small window if that's switched on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ZarkTV: ao sair do app nada continua tocando (nem em janelinha). Ao voltar, volta a tocar.
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
        runCatching { vm.player.exo.playWhenReady = false; vm.player.exo.pause() }
        vm.onBackground()
        super.onStop()
    }

    companion object {
        /** True while Channelbox is on screen (so boot / wake don't start it twice). */
        @Volatile
        var onScreen = false
    }
}
