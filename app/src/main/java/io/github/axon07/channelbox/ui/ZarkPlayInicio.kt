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
 * e Jogos do dia grandes à direita e uma fileira de atalhos (guia, busca, conta, configurações e atualizar).
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

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D))) {
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
                        Text("Zark", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                        Text("TV", color = Palette.Accent, fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                    }
                    Spacer(Modifier.weight(1f))
                    // Conectado por cabo ou Wi-Fi (o mesmo símbolo do ZarkTV TV).
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = if (compacto) 0.dp else 20.dp)) {
                        AppIcon(
                            when (rede) { 0 -> AppIcons.SemRede; 2 -> AppIcons.Cabo; 3 -> AppIcons.Dados; else -> AppIcons.Wifi },
                            tint = if (rede == 0) Color(0xFFFF5252) else Color.White, size = 22.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(if (rede == 0) "Sem conexão" else "Conectado", color = if (rede == 0) Color(0xFFFF5252) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(when (rede) { 0 -> "Verifique a internet"; 2 -> "Cabo de rede"; 3 -> "Dados móveis"; else -> "Wi-Fi" }, color = Color(0x99FFFFFF), fontSize = 11.sp)
                        }
                    }
                    if (!compacto) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(hora, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text(data, color = Color(0x99FFFFFF), fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(if (compacto) 12.dp else 18.dp))

                val atalhos: List<Triple<String, ImageVector, () -> Unit>> = listOf(
                    Triple("Guia de programação", AppIcons.Guide, onGuia),
                    Triple("Buscar", AppIcons.Search, onBuscar),
                    Triple("Conta", AppIcons.User, onConta),
                    Triple("Configurações", AppIcons.Settings, onAjustes),
                    Triple(if (atualizando) "Atualizando…" else "Atualizar", AppIcons.Refresh, onAtualizar),
                )

                if (retrato) {
                    // Celular em pé: blocos grandes empilhados e atalhos 2 por linha.
                    BlocoZp("Canais ao vivo", "", AppIcons.AoVivo, onAoVivo, true, Modifier.fillMaxWidth().height(130.dp).focusRequester(primeiro))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BlocoZp("Filmes", "", AppIcons.Film, onFilmes, false, Modifier.weight(1f).height(110.dp))
                        BlocoZp("Séries", "", AppIcons.Play, onSeries, false, Modifier.weight(1f).height(110.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    BlocoZp("Jogos do dia", "", AppIcons.Trofeu, onJogos, false, Modifier.fillMaxWidth().height(90.dp))
                    Spacer(Modifier.height(10.dp))
                    atalhos.chunked(2).forEach { par ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            par.forEach { (nome, icone, acao) -> AtalhoZp(nome, icone, acao, Modifier.weight(1f).height(56.dp)) }
                        }
                    }
                } else {
                    val altoBlocos: Dp = if (compacto) 190.dp else 300.dp
                    Row(horizontalArrangement = Arrangement.spacedBy(if (compacto) 10.dp else 16.dp), modifier = Modifier.fillMaxWidth().height(altoBlocos)) {
                        BlocoZp("Canais ao vivo", "", AppIcons.AoVivo, onAoVivo, true, Modifier.weight(1.3f).fillMaxHeight().focusRequester(primeiro))
                        BlocoZp("Filmes", "", AppIcons.Film, onFilmes, false, Modifier.weight(1f).fillMaxHeight())
                        BlocoZp("Séries", "", AppIcons.Play, onSeries, false, Modifier.weight(1f).fillMaxHeight())
                        BlocoZp("Jogos do dia", "", AppIcons.Trofeu, onJogos, false, Modifier.weight(1f).fillMaxHeight())
                    }
                    Spacer(Modifier.height(if (compacto) 10.dp else 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(if (compacto) 8.dp else 12.dp), modifier = Modifier.fillMaxWidth()) {
                        atalhos.forEach { (nome, icone, acao) -> AtalhoZp(nome, icone, acao, Modifier.weight(1f).height(if (compacto) 70.dp else 92.dp)) }
                    }
                }
                if (atualizando) {
                    Spacer(Modifier.height(12.dp))
                    BarrinhaCarregando(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.weight(1f))
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

/** Bloco grande (TV ao vivo, Filmes, Séries): ícone, nome e quantidade. Vermelho no foco. */
@Composable
private fun BlocoZp(nome: String, info: String, icone: ImageVector, onClick: () -> Unit, destaque: Boolean, modifier: Modifier) {
    var foco by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    foco -> Brush.verticalGradient(listOf(Color(0xFFD32F2F), Color(0xFF6A0F0F)))
                    destaque -> Brush.verticalGradient(listOf(Color(0xFF3A1416), Color(0xFF1A0B0C)))
                    else -> Brush.verticalGradient(listOf(Color(0xFF26262B), Color(0xFF151518)))
                }
            )
            .border(if (foco) 3.dp else 1.dp, if (foco) Color.White else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(icone, tint = Color.White, size = if (destaque) 56.dp else 44.dp)
        Spacer(Modifier.height(12.dp))
        Text(nome.uppercase(), color = Color.White, fontSize = if (destaque) 24.sp else 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (info.isNotBlank()) Text(info, color = Color(0xB3FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Atalho pequeno da fileira de baixo. */
@Composable
private fun AtalhoZp(nome: String, icone: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    var foco by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (foco) Color(0xFFC62828) else Color(0xFF1C1C20))
            .border(if (foco) 2.dp else 1.dp, if (foco) Color.White else Color(0x26FFFFFF), RoundedCornerShape(10.dp))
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(icone, tint = Color.White, size = 24.dp)
        Spacer(Modifier.height(4.dp))
        Text(nome, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
