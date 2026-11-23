package io.github.axon07.channelbox.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Marquinha "ZarkTV" discreta (cabeçalho dos avisos, carregamento). */
@Composable
fun MarcaPequena(tamanho: Int = 13) {
    Row {
        Text("Zark", color = Color(0xCCFFFFFF), fontSize = tamanho.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        Text("TV", color = Palette.Accent, fontSize = tamanho.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
    }
}

/** Quatro bolinhas vermelhas girando (carregamento, como nos apps de IPTV). */
@Composable
fun BolinhasCarregando(modifier: Modifier = Modifier.size(56.dp)) {
    val t = rememberInfiniteTransition(label = "bolinhas")
    val ang by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    Canvas(modifier) {
        val r = size.minDimension / 2 * 0.7f
        val c = center
        for (i in 0 until 4) {
            val a = Math.toRadians((ang + i * 28f).toDouble())
            val tam = size.minDimension * (0.07f + i * 0.018f)
            drawCircle(
                Color(0xFFE53935).copy(alpha = 0.45f + i * 0.18f), tam,
                Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat()),
            )
        }
    }
}

/**
 * "Começar a partir de 00:33:19" com contagem: se ninguém escolher em 8 s, continua sozinho
 * (como na UniTV). Continuar já vem selecionado.
 */
@Composable
fun DialogoContinuar(titulo: String, detalhe: String?, posicao: Long, onContinuar: () -> Unit, onInicio: () -> Unit) {
    var falta by remember { mutableIntStateOf(8) }
    LaunchedEffect(Unit) {
        while (falta > 0) { delay(1000); falta-- }
        onContinuar()
    }
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { foco.requestFocus() } }
    val s = posicao / 1000
    val hhmmss = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    // Celular: aviso pequeno embaixo, sem tampar a tela.
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xF0232325))
                    .border(1.dp, Color(0x14EDEDED), RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Continuar de $hhmmss?", color = Color(0xFFEDEDED), fontSize = 14.sp)
                    Text("Continua sozinho em ${falta}s", color = Color(0xFF85858B), fontSize = 12.sp)
                }
                Text("Do início", color = Color(0xFFA1A1A6), fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onInicio).padding(horizontal = 10.dp, vertical = 8.dp))
                Text("Continuar", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFE50914)).clickable(onClick = onContinuar).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2262A33), Color(0xF2161820))))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MarcaPequena()
                Spacer(Modifier.weight(1f))
                Text("Continuar assistindo", color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text(titulo, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            detalhe?.let { Text(it, color = Color(0xB3FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
            Spacer(Modifier.height(10.dp))
            Text("Começar a partir de $hhmmss", color = Color.White, fontSize = 16.sp)
            Text("$falta", color = Color(0xFFFFC107), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BotaoDialogo("Começar do início", false, Modifier.weight(1f), onInicio)
                BotaoDialogo("Continuar", true, Modifier.weight(1f).focusRequester(foco), onContinuar)
            }
        }
    }
}

@Composable
private fun BotaoDialogo(texto: String, principal: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(
                when {
                    principal -> Brush.horizontalGradient(listOf(Color(0xFFFF5A36), Color(0xFFE53935)))
                    f -> Brush.horizontalGradient(listOf(Color(0x55FFFFFF), Color(0x55FFFFFF)))
                    else -> Brush.horizontalGradient(listOf(Color(0x26FFFFFF), Color(0x26FFFFFF)))
                }
            )
            .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(23.dp))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}


/** Confirmação de saída: cartão centralizado, com a marca e dois botões grandes lado a lado. */
@Composable
fun DialogoSair(onSair: () -> Unit, onCancelar: () -> Unit) {
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { foco.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(400.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF2262A33), Color(0xF2161820))))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MarcaPequena()
            Spacer(Modifier.height(18.dp))
            Text("Sair do ZarkTV?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("O vídeo para e o app fecha.", color = Color(0xB3FFFFFF), fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BotaoDialogo("Cancelar", false, Modifier.weight(1f), onCancelar)
                BotaoDialogo("Sair", true, Modifier.weight(1f).focusRequester(foco), onSair)
            }
        }
    }
}


/** Conta vencida: bloqueia o app inteiro, avisa e mostra como renovar. */
@Composable
fun TelaVencida(
    venceuEm: Long?,
    usuario: String,
    onPagar: () -> Unit,
    onConferir: () -> Unit,
    onSuporte: () -> Unit,
    onTrocarConta: () -> Unit,
) {
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { foco.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xF20B0B10)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A1416), Color(0xFF15161C))))
                .border(1.dp, Color(0x33FF4D57), RoundedCornerShape(18.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MarcaPequena()
            Spacer(Modifier.height(18.dp))
            Text("Sua conta venceu", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val quando = if (venceuEm != null && venceuEm > 1) " em " + io.github.axon07.channelbox.data.ZarkConfig.dataBr(venceuEm) else ""
            Text(
                "A conta $usuario venceu$quando. Renove para voltar a assistir canais, filmes e séries.",
                color = Color(0xCCFFFFFF), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(24.dp))
            BotaoDialogo("Pagar agora no Pix", true, Modifier.fillMaxWidth().focusRequester(foco), onPagar)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotaoDialogo("Já paguei", false, Modifier.weight(1f), onConferir)
                BotaoDialogo("Suporte", false, Modifier.weight(1f), onSuporte)
                BotaoDialogo("Trocar conta", false, Modifier.weight(1f), onTrocarConta)
            }
        }
    }
}
