package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.axon07.channelbox.data.ZarkConfig
import kotlinx.coroutines.delay

/**
 * Tela inicial do ZarkTV: blocões grandes (Canais ao vivo, Filmes, Séries, Configurações)
 * e a faixa "Minha conta" (vencimento, pagar/renovar, senha de adulto, player, suporte).
 */
@Composable
fun HomeScreen(
    serverName: String,
    userName: String,
    conta: ZarkConfig.Login.Ok?,
    channelCount: Int,
    movieCount: Int,
    showCount: Int,
    onLive: () -> Unit,
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onFavorites: () -> Unit,
    onPay: () -> Unit,
    onSupport: () -> Unit,
    onExit: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    var agora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            agora = System.currentTimeMillis()
        }
    }
    val hora = remember(agora) { java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(agora)) }
    val dias = conta?.expira?.let { ZarkConfig.diasRestantes(it) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF240507), Palette.Background, Color(0xFF0B0B0B))))
    ) {
        val compacto = maxWidth < 640.dp
        val pad = if (compacto) 16.dp else 40.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pad, vertical = if (compacto) 16.dp else 28.dp),
        ) {
            // Topo: marca, conta e hora.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▶ ", color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Zark", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("TV", color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                if (!compacto) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(userName.ifBlank { "Minha conta" }, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Servidor $serverName", color = Palette.TextDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(20.dp))
                }
                Text(hora, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }

            // Aviso de vencimento: "faltam X dias, fale com o suporte".
            if (dias != null && dias <= 5) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (dias < 0) Color(0xFF5A0A0E) else Color(0xFF3A2A08), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            dias < 0 -> "Seu acesso venceu. Renove para continuar assistindo."
                            dias == 0 -> "Seu acesso vence hoje! Renove para não ficar sem."
                            dias == 1 -> "Falta 1 dia para o seu acesso vencer."
                            else -> "Faltam $dias dias para o seu acesso vencer."
                        },
                        color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    PillButton("Renovar agora", primary = true, onClick = onPay)
                }
            }

            Spacer(Modifier.height(if (compacto) 16.dp else 26.dp))

            // Os blocões: TV grande, Filmes e Séries (como no app antigo).
            val tv = Bloco("TV ao vivo", if (channelCount > 0) "$channelCount canais" else "Canais", AppIcons.Tv, Color(0xFFE50914), Color(0xFF7A0409), onLive)
            val filmes = Bloco("Filmes", if (movieCount > 0) "$movieCount filmes" else "Filmes", AppIcons.Film, Color(0xFF3A3A3A), Color(0xFF161616), onMovies)
            val series = Bloco("Séries", if (showCount > 0) "$showCount séries" else "Séries", AppIcons.Grid, Color(0xFF3A3A3A), Color(0xFF161616), onSeries)
            if (compacto) {
                BlocoTile(tv, 170.dp, Modifier.fillMaxWidth().focusRequester(first))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    BlocoTile(filmes, 130.dp, Modifier.weight(1f))
                    BlocoTile(series, 130.dp, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    BlocoTile(tv, 250.dp, Modifier.weight(1.5f).focusRequester(first))
                    BlocoTile(filmes, 250.dp, Modifier.weight(1f))
                    BlocoTile(series, 250.dp, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))
            // Atalhos pequenos.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AtalhoPerfil(AppIcons.Star, "Favoritos", onFavorites, Modifier.weight(1f))
                AtalhoPerfil(AppIcons.Home, "Perfil", onProfile, Modifier.weight(1f))
                AtalhoPerfil(AppIcons.Settings, "Configurações", onSettings, Modifier.weight(1f))
                if (!compacto) AtalhoPerfil(AppIcons.Phone, "Suporte", onSupport, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    conta == null -> userName.ifBlank { "" }
                    conta.expira == null -> "$userName · sem vencimento"
                    else -> "$userName · vence em " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(conta.expira))
                },
                color = Palette.TextDim, fontSize = 12.sp,
            )
        }
    }
}

private class Bloco(
    val titulo: String,
    val sub: String,
    val icone: ImageVector,
    val cor1: Color,
    val cor2: Color,
    val onClick: () -> Unit,
)

@Composable
private fun BlocoTile(b: Bloco, altura: Dp, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = b.onClick,
        modifier = modifier
            .height(altura)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x22FFFFFF), shape)
            .tap(onClick = b.onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.05f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(if (focused) b.cor1 else b.cor1.copy(alpha = 0.85f), b.cor2)), shape)
                .padding(18.dp),
        ) {
            AppIcon(b.icone, tint = Color.White, size = 40.dp, modifier = Modifier.align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(b.titulo, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(b.sub, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/**
 * Perfil do ZarkTV, no estilo do perfil da UniTV: cartões Renovar e Convidar amigos,
 * atalhos (Favoritos, Ao vivo) e a lista de opções da conta.
 */
@Composable
fun ProfileScreen(
    serverName: String,
    userName: String,
    conta: ZarkConfig.Login.Ok?,
    adultoBloqueado: Boolean,
    onPay: () -> Unit,
    onInvite: () -> Unit,
    onFavorites: () -> Unit,
    onLive: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
    onParental: () -> Unit,
    onPlayer: () -> Unit,
    onSupport: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
    temaNome: String = ZarkTema.atual.nome,
    onTema: () -> Unit = {},
    playerFilmes: String = "Interno",
    onPlayerFilmes: () -> Unit = {},
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    var contaAberta by remember { mutableStateOf(false) }
    val dias = conta?.expira?.let { ZarkConfig.diasRestantes(it) }
    val venc = when {
        conta == null -> "Conferindo…"
        conta.expira == null -> "Sem vencimento"
        else -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(conta.expira))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(FundoZark)) {
        val largura = if (maxWidth > 760.dp) 720.dp else maxWidth
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .width(largura)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("‹ Voltar", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Text("Perfil", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            // Cartões grandes.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CartaoPerfil(
                    "Renovar / Pagar",
                    if (dias == null) "Pix na hora" else if (dias < 0) "Acesso vencido" else "Vence em $venc",
                    Color(0xFFFF8A00), Color(0xFFE50914), onPay,
                    Modifier.weight(1f).focusRequester(first),
                )
                CartaoPerfil("Convidar amigos", "Indique o ZarkTV", Color(0xFF7B2FF7), Color(0xFF3A1C9E), onInvite, Modifier.weight(1f))
            }

            // Atalhos.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AtalhoPerfil(AppIcons.Star, "Favoritos", onFavorites, Modifier.weight(1f))
                AtalhoPerfil(AppIcons.Tv, "Ao vivo", onLive, Modifier.weight(1f))
            }

            // Lista de opções.
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF17171B), RoundedCornerShape(12.dp)).padding(vertical = 4.dp)
            ) {
                LinhaPerfil(AppIcons.Lock, "Gestão de conta", if (contaAberta) "▾" else userName) { contaAberta = !contaAberta }
                if (contaAberta) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoConta("Conta", userName)
                        InfoConta("Servidor", serverName)
                        InfoConta("Vencimento", venc)
                        conta?.status?.takeIf { it.isNotBlank() }?.let { InfoConta("Situação", if (it.equals("Active", true)) "Ativa" else it) }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Trocar de conta", onClick = onSwitchAccount)
                            PillButton("Sair da conta", onClick = onLogout)
                        }
                    }
                }
                LinhaPerfil(AppIcons.Shield, "Conteúdo adulto", if (adultoBloqueado) "Bloqueado · toque para liberar" else "Liberado · toque para bloquear", onParental)
                LinhaPerfil(AppIcons.Captions, "Áudio, legenda e player", null, onPlayer)
                LinhaPerfil(AppIcons.Info, "Ajuda e suporte", "WhatsApp", onSupport)
                LinhaPerfil(AppIcons.Grid, "Tema do app", temaNome, onTema)
                LinhaPerfil(AppIcons.Film, "Player de filmes e séries", playerFilmes, onPlayerFilmes)
                LinhaPerfil(AppIcons.Settings, "Configurações", null, onSettings)
                LinhaPerfil(AppIcons.Globe, "Compartilhar o app", null, onShare)
            }
        }
    }
}

@Composable
private fun CartaoPerfil(titulo: String, sub: String, c1: Color, c2: Color, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(96.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.04f)),
    ) {
        Column(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c1, c2)), shape).padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(titulo, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Color(0xE6FFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AtalhoPerfil(icone: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp).onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF17171B), focusedContainerColor = Color(0xFFECECEC)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.03f)),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            AppIcon(icone, tint = Palette.Accent, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, color = if (focused) Color.Black else Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LinhaPerfil(icone: ImageVector, titulo: String, valor: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(58.dp).onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFFECECEC)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icone, tint = if (focused) Color.Black else Palette.Text, size = 22.dp)
            Spacer(Modifier.width(16.dp))
            Text(titulo, color = if (focused) Color.Black else Palette.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            valor?.let { Text(it, color = if (focused) Color(0xFF444444) else Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.width(8.dp))
            Text("›", color = if (focused) Color.Black else Palette.TextDim, fontSize = 22.sp)
        }
    }
}

@Composable
private fun InfoConta(label: String, valor: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(valor.ifBlank { "—" }, color = Palette.Text, fontSize = 13.sp)
    }
}
