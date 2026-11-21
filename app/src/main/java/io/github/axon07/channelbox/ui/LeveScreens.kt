package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Início leve (estilo Zplay 2): quatro cartões grandes — Canais, Filmes, Séries, Atualizar —,
 * busca/perfil/ajustes no topo. Sem prévia de vídeo nem carrossel: roda liso em qualquer TV Box.
 */
@Composable
fun InicioLeve(
    usuario: String,
    vencimento: String?,
    versao: String,
    logoUrl: String?,
    fundoUrl: String?,
    atualizando: Boolean,
    avisoVencimento: String?,
    onCanais: () -> Unit,
    onFilmes: () -> Unit,
    onSeries: () -> Unit,
    onAtualizar: () -> Unit,
    onBuscar: () -> Unit,
    onPerfil: () -> Unit,
    onAjustes: () -> Unit,
) {
    val primeiro = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { primeiro.requestFocus() }
    }
    var hora by remember { mutableStateOf(horaAgora()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); hora = horaAgora() } }

    Box(Modifier.fillMaxSize()) {
        fundoUrl?.let {
            coil.compose.AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color(0xB30A0A0A)))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val retrato = maxHeight > maxWidth
            val compacto = maxWidth < 700.dp
            Column(Modifier.fillMaxSize().padding(start = if (compacto) 14.dp else 36.dp, end = if (compacto) 14.dp else 36.dp, top = if (compacto) 18.dp else 24.dp, bottom = if (compacto) 12.dp else 20.dp)) {
                // Topo: marca à esquerda; busca, perfil, ajustes e hora à direita.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (logoUrl != null) {
                        coil.compose.AsyncImage(logoUrl, "ZarkTV", contentScale = ContentScale.Fit, modifier = Modifier.height(40.dp).width(140.dp))
                    } else {
                        Text("Zark", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                        Text("TV", color = Palette.Accent, fontSize = 24.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                    }
                    Spacer(Modifier.weight(1f))
                    IconeLeve(AppIcons.Search, "Buscar", onBuscar)
                    Spacer(Modifier.width(10.dp))
                    IconeLeve(AppIcons.User, "Perfil", onPerfil)
                    Spacer(Modifier.width(10.dp))
                    IconeLeve(AppIcons.Settings, "Ajustes", onAjustes)
                    if (!compacto) {
                        Spacer(Modifier.width(16.dp))
                        Text(hora, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.weight(if (retrato) 0.4f else 1f))
                val cartoes: List<Triple<String, ImageVector, () -> Unit>> = listOf(
                    Triple("Canais", AppIcons.Tv, onCanais),
                    Triple("Filmes", AppIcons.Film, onFilmes),
                    Triple("Séries", AppIcons.Play, onSeries),
                    Triple(if (atualizando) "Atualizando…" else "Atualizar", AppIcons.Refresh, onAtualizar),
                )
                if (retrato) {
                    // Celular em pé: 2 × 2.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        cartoes.chunked(2).forEachIndexed { linha, par ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                par.forEachIndexed { i, (nome, icone, acao) ->
                                    CartaoLeve(
                                        nome, icone, acao,
                                        Modifier.weight(1f).height(150.dp)
                                            .then(if (linha == 0 && i == 0) Modifier.focusRequester(primeiro) else Modifier),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (compacto) 10.dp else 18.dp), modifier = Modifier.fillMaxWidth()) {
                        cartoes.forEachIndexed { i, (nome, icone, acao) ->
                            CartaoLeve(
                                nome, icone, acao,
                                Modifier.weight(1f).height(if (compacto) 170.dp else 240.dp)
                                    .then(if (i == 0) Modifier.focusRequester(primeiro) else Modifier),
                            )
                        }
                    }
                }
                if (atualizando) {
                    Spacer(Modifier.height(14.dp))
                    BarrinhaCarregando(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.weight(1f))

                // Rodapé: conta, vencimento e versão.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        listOfNotNull(usuario.takeIf { it.isNotBlank() }?.let { "Conta: $it" }, vencimento?.let { "vence em $it" }).joinToString(" · "),
                        color = Color(0x99FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("V $versao", color = Color(0x66FFFFFF), fontSize = 12.sp)
                }
            }
            avisoVencimento?.let { AvisoQueSome(it, Modifier.align(Alignment.BottomEnd).padding(16.dp)) }
        }
    }
}

/** Cartão grande da tela inicial leve: grafite, contorno vermelho e um pouco mais claro no foco. */
@Composable
private fun CartaoLeve(nome: String, icone: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    var foco by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (foco) Brush.verticalGradient(listOf(Color(0xFFB71C1C), Color(0xFF5A0E0E)))
                else Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF17171A)))
            )
            .border(if (foco) 3.dp else 1.dp, if (foco) Color.White else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (foco) Color(0x33FFFFFF) else Color(0x22E53935)),
            contentAlignment = Alignment.Center,
        ) { AppIcon(icone, tint = Color.White, size = 32.dp) }
        Spacer(Modifier.height(14.dp))
        Text(nome, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconeLeve(icone: ImageVector, descricao: String, onClick: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (foco) Color.White else Color(0x26FFFFFF))
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = descricao, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AppIcon(icone, tint = if (foco) Palette.Accent else Color.White, size = 20.dp) }
}

/** Aviso no canto que aparece uma vez por abertura e some sozinho (sem botão). */
@Composable
fun AvisoQueSome(texto: String, modifier: Modifier = Modifier) {
    if (avisoMostrado.value) return
    LaunchedEffect(Unit) {
        delay(9000)
        avisoMostrado.value = true
    }
    Column(
        modifier.width(320.dp)
            .background(Color(0xE6141414), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xAAFFB300), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text("Aviso de vencimento", color = Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(texto, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun horaAgora(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())

/** Texto do aviso de vencimento (null quando faltam mais de 5 dias). */
fun textoVencimento(dias: Int?): String? = when {
    dias == null || dias > 5 -> null
    dias < 0 -> "Seu acesso venceu. Renove agora ou fale com seu revendedor."
    dias == 0 -> "Seu acesso vence hoje. Renove agora ou fale com seu revendedor."
    dias == 1 -> "Falta 1 dia para o seu acesso vencer. Renove agora ou fale com seu revendedor."
    else -> "Faltam $dias dias para o seu acesso vencer. Renove agora ou fale com seu revendedor."
}
