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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** ZarkPlay: este APK é a versão no estilo XCIPTV (menu de blocos). */
object ZarkPlay {
    val ativo: Boolean get() = io.github.axon07.channelbox.BuildConfig.PRODUTO == "zarkplay"
}

/**
 * Tela inicial do ZarkPlay, no jeito do XCIPTV: TV ao vivo em destaque à esquerda, Filmes e Séries
 * e Jogos do dia grandes à direita; Atualizar, Configurações e Conta como ícones no canto de cima.
 * Catch up, multitela e gravações saíram da tela inicial a pedido (tela mais simples).
 * Funciona no controle remoto (foco com borda branca) e no toque.
 */
@Composable
fun InicioZarkPlay(
    usuario: String,
    vencimento: String?,
    versao: String,
    logoUrl: String?,
    fundoUrl: String?,
    canais: Int,
    filmes: Int,
    series: Int,
    atualizando: Boolean,
    avisoVencimento: String?,
    onAoVivo: () -> Unit,
    onFilmes: () -> Unit,
    onSeries: () -> Unit,
    onGuia: () -> Unit,
    onJogos: () -> Unit,
    onBuscar: () -> Unit,
    onConta: () -> Unit,
    onAjustes: () -> Unit,
    onAtualizar: () -> Unit,
) {
    val primeiro = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { primeiro.requestFocus() }
    }
    var hora by remember { mutableStateOf(agoraZp("HH:mm")) }
    var data by remember { mutableStateOf(agoraZp("EEE, d 'de' MMM")) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); hora = agoraZp("HH:mm"); data = agoraZp("EEE, d 'de' MMM") } }
    // Conexão do aparelho (cabo, Wi-Fi, dados ou sem rede), conferida de novo a cada 5 segundos.
    val ctxRede = androidx.compose.ui.platform.LocalContext.current
    var rede by remember { mutableStateOf(tipoRedeZp(ctxRede)) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); rede = tipoRedeZp(ctxRede) } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0B0D), Color(0xFF141418))))) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x55E50914), Color.Transparent), center = androidx.compose.ui.geometry.Offset(260f, 0f), radius = 1100f)))
        fundoUrl?.let {
            coil.compose.AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color(0xC00A0A0A)))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val retrato = maxHeight > maxWidth
            val compacto = maxWidth < 700.dp
            val margem = if (compacto) 14.dp else 32.dp
            Column(Modifier.fillMaxSize().padding(horizontal = margem, vertical = if (compacto) 14.dp else 22.dp)) {
                // Topo: marca, data/hora, conta.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (logoUrl != null) {
                        coil.compose.AsyncImage(logoUrl, "ZarkTV", contentScale = ContentScale.Fit, modifier = Modifier.height(46.dp).width(180.dp))
                    } else {
                        Text("Zark", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text("TV", color = Color(0xFFE50914), fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.weight(1f))
                    // Conectado por cabo ou Wi-Fi (o mesmo símbolo do ZarkTV TV).
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = if (compacto) 0.dp else 20.dp)) {
                        AppIcon(
                            when (rede) { 0 -> AppIcons.SemRede; 2 -> AppIcons.Cabo; 3 -> AppIcons.Dados; else -> AppIcons.Wifi },
                            tint = if (rede == 0) Color(0xFFFF5252) else Color(0xFFFFD60A), size = 26.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(if (rede == 0) "Sem conexão" else "Conectado", color = if (rede == 0) Color(0xFFFF5252) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(when (rede) { 0 -> "Verifique a internet"; 2 -> "Cabo de rede"; 3 -> "Dados móveis"; else -> "Wi-Fi" }, color = Color(0x99FFFFFF), fontSize = 11.sp)
                        }
                    }
                    BotaoIconeZp(AppIcons.Refresh, if (atualizando) "Atualizando" else "Atualizar", onAtualizar)
                    Spacer(Modifier.width(10.dp))
                    BotaoIconeZp(AppIcons.Settings, "Configurações", onAjustes)
                    Spacer(Modifier.width(10.dp))
                    BotaoIconeZp(AppIcons.User, "Conta", onConta)
                    Spacer(Modifier.width(if (compacto) 0.dp else 20.dp))
                    if (!compacto) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(hora, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                            Text(data, color = Color(0x99FFFFFF), fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(if (compacto) 12.dp else 18.dp))

                if (retrato) {
                    // Celular em pé: blocos grandes empilhados e atalhos 2 por linha.
                    BlocoZp("Canais ao vivo", "Assista agora", AppIcons.AoVivo, onAoVivo, true, Modifier.fillMaxWidth().height(150.dp).focusRequester(primeiro))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BlocoZp("Filmes", "Catálogo completo", AppIcons.Film, onFilmes, false, Modifier.weight(1f).height(120.dp))
                        BlocoZp("Séries", "Temporadas e episódios", AppIcons.Play, onSeries, false, Modifier.weight(1f).height(120.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    BlocoZp("Jogos do dia", "Futebol e mais", AppIcons.Trofeu, onJogos, false, Modifier.fillMaxWidth().height(110.dp))
                } else {
                    val esp = if (compacto) 10.dp else 18.dp
                    Row(horizontalArrangement = Arrangement.spacedBy(esp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        BlocoZp("Canais ao vivo", "Assista agora", AppIcons.AoVivo, onAoVivo, true, Modifier.weight(1.6f).fillMaxHeight().focusRequester(primeiro))
                        Column(verticalArrangement = Arrangement.spacedBy(esp), modifier = Modifier.weight(2f).fillMaxHeight()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(esp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                                BlocoZp("Filmes", "Catálogo completo", AppIcons.Film, onFilmes, false, Modifier.weight(1f).fillMaxHeight())
                                BlocoZp("Séries", "Temporadas e episódios", AppIcons.Play, onSeries, false, Modifier.weight(1f).fillMaxHeight())
                            }
                            BlocoZp("Jogos do dia", "Futebol e mais", AppIcons.Trofeu, onJogos, false, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
                if (atualizando) {
                    Spacer(Modifier.height(12.dp))
                    BarrinhaCarregando(Modifier.fillMaxWidth())
                }
                if (retrato) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
                // Rodapé: conta, vencimento e versão (como no XCIPTV).
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        listOfNotNull(usuario.takeIf { it.isNotBlank() }?.let { "Usuário: $it" }, vencimento?.let { "Vence em: $it" }).joinToString("   ·   "),
                        color = Color(0x99FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("ZarkTV $versao", color = Color(0x66FFFFFF), fontSize = 12.sp)
                }
            }
            avisoVencimento?.let { AvisoVencimentoZp(it) }
        }
    }
}

/**
 * Bloco grande no estilo "vidro" (desenho aprovado no Lovable): ícone em cima, nome grande embaixo.
 * Canais ao vivo em vermelho; os outros em vidro escuro com brilho no canto. No foco cresce e ganha
 * borda branca com brilho amarelo.
 */
@Composable
private fun BlocoZp(nome: String, info: String, icone: ImageVector, onClick: () -> Unit, destaque: Boolean, modifier: Modifier) {
    var foco by remember { mutableStateOf(false) }
    val escala by androidx.compose.animation.core.animateFloatAsState(if (foco) 1.045f else 1f, label = "escala")
    val forma = RoundedCornerShape(26.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .border(if (foco) 4.dp else 1.dp, if (foco) Color.White else Color(0x24FFFFFF), forma)
            .clip(forma)
            .background(
                if (destaque) Brush.linearGradient(listOf(Color(0xFFFF4D5A), Color(0xFFC70818)))
                else Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x08FFFFFF)))
            )
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick),
    ) {
        // brilho de vidro no canto de cima
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(0f to Color(0x00FFFFFF), 0.55f to Color(0x00FFFFFF), 0.8f to Color(0x24FFFFFF), 1f to Color(0x00FFFFFF), start = androidx.compose.ui.geometry.Offset(0f, 600f), end = androidx.compose.ui.geometry.Offset(900f, 0f))))
        if (foco) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x26FFD60A)))))
        AppIcon(icone, tint = Color.White, size = if (destaque) 64.dp else 44.dp, modifier = Modifier.align(Alignment.TopStart).padding(22.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Text(nome, color = Color.White, fontSize = if (destaque) 34.sp else 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (destaque) 38.sp else 28.sp)
            if (info.isNotBlank()) Text(info, color = Color(0xB3FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Atalho em pílula: ícone e nome lado a lado. No foco, borda branca e fundo um pouco mais claro. */
@Composable
private fun AtalhoZp(nome: String, icone: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    var foco by remember { mutableStateOf(false) }
    val escala by androidx.compose.animation.core.animateFloatAsState(if (foco) 1.04f else 1f, label = "escala")
    val forma = RoundedCornerShape(18.dp)
    Row(
        modifier
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(forma)
            .background(if (foco) Color(0x29FFFFFF) else Color(0x10FFFFFF))
            .border(if (foco) 3.dp else 1.dp, if (foco) Color.White else Color(0x1AFFFFFF), forma)
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icone, tint = if (foco) Color(0xFFFFD60A) else Color.White, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(nome, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun agoraZp(formato: String): String =
    java.text.SimpleDateFormat(formato, java.util.Locale("pt", "BR")).format(java.util.Date())

/** 0 = sem rede, 1 = Wi-Fi, 2 = cabo, 3 = dados móveis. */
private fun tipoRedeZp(c: android.content.Context): Int = runCatching {
    val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    when {
        caps == null -> 0
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> 1
        else -> 3
    }
}.getOrDefault(1)

/**
 * ZarkPlay: texto do aviso de vencimento só nos marcos combinados — faltando 5 dias, 3 dias,
 * no dia, faltando 1 hora e depois de vencido. Fora disso, nada.
 */
fun textoVencimentoZp(expira: Long?, agora: Long = System.currentTimeMillis()): String? {
    if (expira == null || expira <= 0) return null
    val falta = expira - agora
    val hora = 60L * 60 * 1000
    val dias = falta.floorDiv(24 * hora).toInt()
    val venceHoje = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale("pt", "BR")).let { it.format(java.util.Date(expira)) == it.format(java.util.Date(agora)) }
    return when {
        falta <= 0 -> "Seu acesso venceu. Renove para continuar assistindo."
        falta <= hora -> "Falta menos de 1 hora para o seu acesso vencer."
        venceHoje -> "Seu acesso vence hoje."
        dias == 3 -> "Faltam 3 dias para o seu acesso vencer."
        dias == 5 -> "Faltam 5 dias para o seu acesso vencer."
        else -> null
    }
}

/** Já mostrou o aviso nesta abertura do app (não repete a cada volta ao Início). */
private val avisoZpMostrado = androidx.compose.runtime.mutableStateOf(false)

/**
 * Aviso discreto no canto de baixo à direita: a tela escurece por uns 3 segundos para chamar
 * a atenção e o recadinho fica mais um pouco no canto, depois some sozinho.
 */
@Composable
private fun AvisoVencimentoZp(texto: String) {
    if (avisoZpMostrado.value) return
    var escuro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3_000); escuro = false
        delay(5_000); avisoZpMostrado.value = true
    }
    val fundo by androidx.compose.animation.animateColorAsState(if (escuro) Color(0x99000000) else Color(0x00000000), label = "escuro")
    Box(Modifier.fillMaxSize().background(fundo)) {
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 44.dp).width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF0141418))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFC300)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(texto, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                Text("Renove pelo menu Conta ou fale com seu revendedor.", color = Color(0xB3FFFFFF), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Botão redondo só com o ícone (Configurações e Conta, no canto de cima). */
@Composable
private fun BotaoIconeZp(icone: ImageVector, nome: String, onClick: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by androidx.compose.animation.core.animateFloatAsState(if (foco) 1.12f else 1f, label = "escala")
    Box(
        Modifier.size(48.dp)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (foco) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (foco) 3.dp else 1.dp, if (foco) Color.White else Color(0x1AFFFFFF), androidx.compose.foundation.shape.CircleShape)
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AppIcon(icone, tint = if (foco) Color(0xFFFFD60A) else Color.White, size = 24.dp) }
}
