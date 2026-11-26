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
        // Segurança: no celular e no tablet ninguém tira print nem grava a tela (sai tudo preto).
        if (!io.github.axon07.channelbox.system.Aparelho.ehTv(resources.configuration)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        telaCheia()
        setContent {
            ChannelboxTheme {
                TamanhoProporcional { TvApp(vm) }
            }
        }
        intent?.getStringExtra("zark_canal")?.let { vm.canalPedido.value = it }
        // O logo da janela (zark_janela) só serve para o instante do toque no ícone; depois o fundo volta a ser preto.
        window.decorView.postDelayed({ window.setBackgroundDrawableResource(android.R.color.black) }, 1500)
        // Usuário desativado (no servidor ou bloqueado no painel): o app fecha na hora, sem aviso.
        lifecycleScope.launch {
            io.github.axon07.channelbox.data.ZarkConfig.desativado.collect { if (it) finishAndRemoveTask() }
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
        // Exceção: no ZarkTV Mobile, se estiver passando algo, vira janelinha (PiP) no celular.
        if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
            val tocando = runCatching { vm.player.exo.isPlaying }.getOrDefault(false)
            if (tocando) SetTopBox.enterPip(this)
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


    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("zark_canal")?.let { vm.canalPedido.value = it }
    }

    override fun onStop() {
        // Stop the stream when the user leaves (Home, screensaver) so it doesn't keep downloading.
        onScreen = false
        runCatching { vm.player.exo.playWhenReady = false; vm.player.exo.pause() }
        vm.onBackground()
        // Ícone das datas comemorativas (Natal, Ano Novo, São João, Carnaval): troca só fora da tela.
        io.github.axon07.channelbox.system.IconeSazonal.aplicar(applicationContext)
        super.onStop()
    }

    companion object {
        /** True while Channelbox is on screen (so boot / wake don't start it twice). */
        @Volatile
        var onScreen = false
    }
}


/**
 * ZarkTV: deixa tudo proporcional à tela. As telas foram desenhadas para uma TV (960 × 540 "dp");
 * celular deitado fica um pouco menor, tablet e telas grandes um pouco maiores — nem gigante nem miúdo.
 * Ajusta a densidade e o tamanho de tela informado juntos, para as contas de posição continuarem certas.
 */
@androidx.compose.runtime.Composable
fun TamanhoProporcional(conteudo: @androidx.compose.runtime.Composable () -> Unit) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val w = cfg.screenWidthDp.toFloat()
    val h = cfg.screenHeightDp.toFloat()
    val fatorCalc = if (w >= h) minOf(w / 960f, h / 540f).coerceIn(0.8f, 1.3f) else (w / 411f).coerceIn(0.85f, 1.2f)
    val fator = if (kotlin.math.abs(fatorCalc - 1f) < 0.03f) 1f else fatorCalc
    // Sempre o MESMO formato (sempre com o CompositionLocalProvider): antes, com fator ≈ 1 o conteúdo
    // era montado sem ele, e ao girar o celular (tela cheia) o Android jogava fora toda a tela aberta —
    // voltava para o início, girava de volta e o vídeo continuava tocando por trás.
    val novaCfg = android.content.res.Configuration(cfg).apply {
        screenWidthDp = (w / fator).toInt()
        screenHeightDp = (h / fator).toInt()
        smallestScreenWidthDp = (cfg.smallestScreenWidthDp / fator).toInt()
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(dens.density * fator, dens.fontScale),
        androidx.compose.ui.platform.LocalConfiguration provides novaCfg,
    ) { conteudo() }
}
