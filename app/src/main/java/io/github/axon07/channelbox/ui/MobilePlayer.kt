package io.github.axon07.channelbox.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.VodEntity
import kotlinx.coroutines.delay

/**
 * ZarkTV Mobile: controles da tela cheia de filme/série, simples como o YouTube e com o que a UniTV tem:
 * em cima voltar, título, partilhar, baixar, transmitir e legenda; no meio −10 s, play/pausa, +10 s;
 * embaixo a barra de tempo, Áudio e legenda e Bloquear. Arrastar para cima/baixo do lado esquerdo
 * muda o brilho e do lado direito o volume.
 */
@Composable
fun BoxScope.PlaybackCel(
    vm: TvViewModel,
    ui: PlaybackUi,
    item: VodEntity?,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOptions: () -> Unit,
    onClose: () -> Unit,
    onCaptions: () -> Unit,
    captionsOn: Boolean,
) {
    val ctx = LocalContext.current
    TelaCelular.ler(ctx)
    var visivel by remember { mutableStateOf(true) }
    var toque by remember { mutableIntStateOf(0) }
    var bloqueado by remember { mutableStateOf(false) }
    var transmitir by remember { mutableStateOf(false) }
    // Aviso do lado (brilho/volume) enquanto arrasta.
    var ajuste by remember { mutableStateOf<String?>(null) }
    var largura by remember { mutableIntStateOf(1) }
    LaunchedEffect(toque, visivel, ui.playing) { if (visivel && ui.playing) { delay(4_000); visivel = false } }
    LaunchedEffect(ajuste) { if (ajuste != null) { delay(900); ajuste = null } }
    if (transmitir) TransmitirDialog(vm, item?.url, ui.title) { transmitir = false }

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var brilho by remember { mutableFloatStateOf(((ctx as? Activity)?.window?.attributes?.screenBrightness ?: -1f).let { if (it < 0) 0.5f else it }) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }

    // Camada de toques: toque mostra/esconde; dois toques nos lados pulam 10 s; arrastar = brilho/volume.
    Box(
        Modifier.fillMaxSize().onSizeChanged { largura = it.width.coerceAtLeast(1) }
            .pointerInput(bloqueado) {
                detectTapGestures(
                    onTap = { visivel = !visivel; toque++ },
                    onDoubleTap = { o -> if (!bloqueado) { if (o.x < largura / 2f) onSeek(-10_000) else onSeek(10_000) } },
                )
            }
            .pointerInput(bloqueado) {
                if (bloqueado) return@pointerInput
                var esquerda = true
                detectVerticalDragGestures(
                    onDragStart = { o -> esquerda = o.x < largura / 2f },
                ) { _, dy ->
                    val passo = -dy / (size.height.coerceAtLeast(1) * 0.8f)
                    if (esquerda) {
                        brilho = (brilho + passo).coerceIn(0.02f, 1f)
                        (ctx as? Activity)?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brilho } }
                        ajuste = "Brilho ${(brilho * 100).toInt()}%"
                    } else {
                        volume = (volume + passo).coerceIn(0f, 1f)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
                        ajuste = "Volume ${(volume * 100).toInt()}%"
                    }
                }
            }
    )
    ajuste?.let {
        Text(it, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp))
            .background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 8.dp))
    }
    // Carregando: abrindo o filme (ainda sem duração) ou esperando dados — mostra a rodinha.
    val carregando = (ui.buffering && ui.playing) || (ui.duration <= 0L && ui.position <= 0L && !ui.ended)
    if (carregando && ajuste == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingRing()
        if (ui.duration <= 0L) Text("Abrindo…", color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    }

    if (bloqueado) {
        if (visivel) Box(
            Modifier.align(Alignment.CenterStart).padding(24.dp).size(48.dp).clip(CircleShape).background(Color(0x88000000))
                .clickable { bloqueado = false; toque++ },
            contentAlignment = Alignment.Center,
        ) { AppIcon(AppIcons.Lock, tint = Color.White, size = 22.dp) }
        return
    }
    if (!visivel && ui.playing) return

    // ---- Em cima ----
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotaoCel(AppIcons.Voltar, onClose)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(ui.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.subtitle?.let { Text(it, color = Color(0xBBFFFFFF), fontSize = 12.sp, maxLines = 1) }
        }
        BotaoCel(AppIcons.Partilhar) {
            runCatching {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Assista \"${ui.title}\" no ZarkTV: https://tv.zarktvqx.cfd"), "Partilhar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (item != null && item.url?.startsWith("file:") != true) BotaoCel(AppIcons.Download) { vm.zarkBaixar(item, ui.title, ui.poster) }
        BotaoCel(AppIcons.Transmitir) { transmitir = true }
        // Encher a tela toda (corta um pouquinho das bordas) ou mostrar o vídeo inteiro.
        BotaoCel(if (TelaCelular.encher.value) AppIcons.Resolucao else AppIcons.TelaCheia) { TelaCelular.alternar(ctx); toque++ }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onCaptions(); toque++ }, contentAlignment = Alignment.Center) {
            Text("CC", color = if (captionsOn) Color(0xFFE50914) else Color.White, fontSize = 14.sp)
        }
    }

    // ---- No meio ----
    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x66000000)).clickable { onSeek(-10_000); toque++ }, contentAlignment = Alignment.Center) {
            Text("−10", color = Color.White, fontSize = 14.sp)
        }
        if (!carregando) Box(
            Modifier.size(68.dp).clip(CircleShape).background(Color(0x66000000)).clickable { onToggle(); toque++ },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.playing && !ui.ended) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(6.dp).height(24.dp).background(Color.White)); Box(Modifier.width(6.dp).height(24.dp).background(Color.White))
            } else AppIcon(if (ui.ended) AppIcons.Refresh else AppIcons.Play, tint = Color.White, size = 32.dp)
        } else Spacer(Modifier.size(68.dp))
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x66000000)).clickable { onSeek(10_000); toque++ }, contentAlignment = Alignment.Center) {
            Text("+10", color = Color.White, fontSize = 14.sp)
        }
    }

    // ---- Embaixo ----
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hora(ui.position), color = Color.White, fontSize = 12.sp)
            BarraTempo(ui, Modifier.weight(1f).padding(horizontal = 12.dp)) { onSeekTo(it); toque++ }
            Text(hora(ui.duration), color = Color.White, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AcaoBaixo(AppIcons.Audio, "Áudio e legenda") { onOptions() }
            AcaoBaixo(AppIcons.Lock, "Bloquear") { bloqueado = true; visivel = true; toque++ }
        }
    }
}

@Composable
private fun BotaoCel(icone: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AppIcon(icone, tint = Color.White, size = 20.dp)
    }
}

@Composable
private fun AcaoBaixo(icone: ImageVector, nome: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icone, tint = Color.White, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(nome, color = Color.White, fontSize = 13.sp)
    }
}

/** Barra de tempo: toque ou arraste para ir a outro ponto. */
@Composable
private fun BarraTempo(ui: PlaybackUi, modifier: Modifier, onSeekTo: (Long) -> Unit) {
    var w by remember { mutableIntStateOf(1) }
    var arrastando by remember { mutableStateOf<Float?>(null) }
    val dur = ui.duration.coerceAtLeast(1)
    val frac = arrastando ?: (ui.position.toFloat() / dur).coerceIn(0f, 1f)
    Box(
        modifier.height(28.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(dur) { detectTapGestures { o -> onSeekTo((o.x / w * dur).toLong()) } }
            .pointerInput(dur) {
                detectDragGestures(
                    onDragStart = { o -> arrastando = (o.x / w).coerceIn(0f, 1f) },
                    onDragEnd = { arrastando?.let { onSeekTo((it * dur).toLong()) }; arrastando = null },
                    onDragCancel = { arrastando = null },
                ) { ch, _ -> arrastando = (ch.position.x / w).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF)))
        Box(Modifier.fillMaxWidth((ui.buffered.toFloat() / dur).coerceIn(0f, 1f)).height(3.dp).background(Color(0x77FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(3.dp).background(Color(0xFFE50914)))
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFE50914)))
        }
    }
}

private fun hora(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}


/** Tela cheia do celular: encher a tela toda (padrão) ou caber o vídeo inteiro. Fica guardado. */
object TelaCelular {
    val encher = mutableStateOf(true)
    private var lido = false
    fun ler(ctx: Context) { if (!lido) { lido = true; encher.value = ctx.getSharedPreferences("zarktv", Context.MODE_PRIVATE).getBoolean("encher_tela", true) } }
    fun alternar(ctx: Context) {
        encher.value = !encher.value
        ctx.getSharedPreferences("zarktv", Context.MODE_PRIVATE).edit().putBoolean("encher_tela", encher.value).apply()
    }
}
