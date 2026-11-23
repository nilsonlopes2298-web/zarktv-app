package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.ZarkConfig
import kotlinx.coroutines.delay

/** Perfil no formato da UniTV: conta à esquerda; cards, histórico, favoritos e funções à direita. */
@Composable
fun PerfilUni(
    vm: TvViewModel,
    usuario: String,
    senha: String,
    servidor: String,
    conta: ZarkConfig.Login.Ok?,
    adultoLiberado: Boolean,
    temaNome: String,
    playerFilmes: String,
    onRenovar: () -> Unit,
    onConvidar: () -> Unit,
    onSuporte: () -> Unit,
    onSair: () -> Unit,
    onOutraConta: () -> Unit,
    onAdulto: () -> Unit,
    onControleParental: () -> Unit,
    onConfiguracoes: () -> Unit,
    onPlayer: () -> Unit,
    onAtualizar: () -> Unit,
    onTema: () -> Unit,
    onPlayerFilmes: () -> Unit,
    onFavoritosCanais: () -> Unit,
    onFavoritosVod: () -> Unit,
    onAbrir: (VodEntity) -> Unit,
    onVoltar: () -> Unit,
) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        PerfilCel(vm, usuario, servidor, conta, adultoLiberado, playerFilmes, onRenovar, onConvidar, onSuporte, onSair, onOutraConta,
            onAdulto, onControleParental, onConfiguracoes, onAtualizar, onPlayerFilmes, onFavoritosCanais, onFavoritosVod, onAbrir)
        return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { foco.requestFocus() } }
    var verSenha by remember { mutableStateOf(false) }
    val vence = conta?.expira?.let { java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(it)) }
    val dias = conta?.expira?.let { ZarkConfig.diasRestantes(it) }
    val histFilmes by remember { vm.vodTop(VodKind.MOVIE, VOD_CONTINUE, 10) }.collectAsState(initial = emptyList())
    val histSeries by remember { vm.vodTop(VodKind.SERIES, VOD_CONTINUE, 10) }.collectAsState(initial = emptyList())
    val historico = remember(histFilmes, histSeries) { (histSeries + histFilmes).take(14) }
    androidx.activity.compose.BackHandler { onVoltar() }

    BoxWithConstraints(Modifier.fillMaxSize().background(FundoZark)) {
        val compacto = maxWidth < 700.dp
        val conteudo: @Composable (Modifier) -> Unit = { mod ->
            Column(mod.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                // Cards grandes
                Row(Modifier.fillMaxWidth().height(if (compacto) 120.dp else 170.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardPerfil(
                        if (dias != null && dias < 0) "Renove seu acesso" else "Renovar / Pagar",
                        vence?.let { "Vence em $it" } ?: "Pix na hora",
                        AppIcons.Star, Brush.linearGradient(listOf(Color(0xFF2B2B2B), Color(0xFF000000))), Color(0xFFFFC107),
                        Modifier.weight(2.6f).fillMaxHeight().focusRequester(foco), onRenovar,
                    )
                    CardPerfil("Convidar amigos", "Indique o ZarkTV", AppIcons.Home, Brush.linearGradient(listOf(Color(0xFFFF7043), Color(0xFFD84315))), Color.White, Modifier.weight(1f).fillMaxHeight(), onConvidar)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(if (compacto) 96.dp else 130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardPerfil("Conteúdo adulto", if (adultoLiberado) "Liberado" else "Bloqueado", AppIcons.Lock, Brush.linearGradient(listOf(Color(0xFF1E88E5), Color(0xFF1565C0))), Color.White, Modifier.weight(1f).fillMaxHeight(), onAdulto)
                    CardPerfil("Suporte", "WhatsApp", AppIcons.Phone, Brush.linearGradient(listOf(Color(0xFF43A047), Color(0xFF2E7D32))), Color.White, Modifier.weight(1f).fillMaxHeight(), onSuporte)
                    CardPerfil("Meus favoritos", "Filmes e séries", AppIcons.Star, Brush.linearGradient(listOf(Color(0xFF29B6F6), Color(0xFF0277BD))), Color.White, Modifier.weight(1f).fillMaxHeight(), onFavoritosVod)
                    CardPerfil("Canais favoritos", "TV ao vivo", AppIcons.Tv, Brush.linearGradient(listOf(Color(0xFFFFA726), Color(0xFFEF6C00))), Color.White, Modifier.weight(1f).fillMaxHeight(), onFavoritosCanais)
                }
                if (historico.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Histórico", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(historico, key = { it.id }) { h -> PosterTile(h, 100.dp, 150.dp) { onAbrir(h) } }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Funções mais usadas", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                var layoutNome by remember { mutableStateOf(ZarkLayout.nome(ctx)) }
                val funcoes = listOf(
                    Triple(AppIcons.Settings, "Configurações", onConfiguracoes),
                    Triple(AppIcons.Lock, "Controle parental", onControleParental),
                    Triple(AppIcons.Refresh, "Atualizar versão", onAtualizar),
                    Triple(AppIcons.Captions, "Áudio e player", onPlayer),
                    Triple(AppIcons.Grid, "Tema: $temaNome", onTema),
                    Triple(AppIcons.Tv, "Tela inicial: $layoutNome", {
                        ZarkLayout.proximo(ctx)
                        layoutNome = ZarkLayout.nome(ctx)
                        vm.showToast(if (ZarkLayout.leve.value) "Tela inicial leve (4 cartões)" else "Tela inicial completa (abas)")
                    }),
                    Triple(AppIcons.Film, "Player: $playerFilmes", onPlayerFilmes),
                    Triple(AppIcons.Info, "Centro de ajuda", onSuporte),
                    Triple(AppIcons.Close, "Limpar cache", {
                        runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear(); coil.Coil.imageLoader(ctx).diskCache?.clear() }
                        vm.showToast("Cache limpo")
                    }),
                )
                funcoes.chunked(4).forEach { linha ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        linha.forEach { (ic, nome, acao) -> QuadroFuncao(ic, nome, Modifier.weight(1f), acao) }
                        repeat(4 - linha.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("ZarkTV V ${io.github.axon07.channelbox.BuildConfig.VERSION_NAME}", color = Color(0x88FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        val lateral: @Composable (Modifier) -> Unit = { mod ->
            Column(mod.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF9E9E9E)), contentAlignment = Alignment.Center) {
                    Text(usuario.take(1).uppercase().ifBlank { "Z" }, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(usuario, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Servidor $servidor", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Senha: " + if (verSenha) senha else "•".repeat(senha.length.coerceIn(4, 10)), color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (verSenha) "ocultar" else "ver", color = Color(0xFFFFB74D), fontSize = 13.sp, modifier = Modifier.tap(onClick = { verSenha = !verSenha }).padding(4.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("Sua conta expira em:", color = Color.White, fontSize = 15.sp)
                Text(vence ?: if (conta == null) "conferindo…" else "sem vencimento", color = if (dias != null && dias <= 5) Color(0xFFFFD54F) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (vence != null) Text("(dd-mm-aaaa)", color = Color(0xAAFFFFFF), fontSize = 12.sp)
                Spacer(Modifier.height(22.dp))
                BotaoPilulaPerfil("Sair da conta", onSair)
                Spacer(Modifier.height(12.dp))
                BotaoPilulaPerfil("Entrar com outra conta", onOutraConta)
                Spacer(Modifier.height(12.dp))
                BotaoPilulaPerfil("‹ Voltar", onVoltar)
            }
        }
        if (compacto) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                lateral(Modifier.fillMaxWidth())
                conteudo(Modifier.fillMaxWidth().height(1400.dp))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                lateral(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                Spacer(Modifier.width(16.dp))
                conteudo(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun CardPerfil(titulo: String, sub: String, icone: ImageVector, fundo: Brush, corIcone: Color, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { f = it.isFocused }
            .border(if (f) 3.dp else 0.dp, if (f) Color(0xFFFFE082) else Color.Transparent, RoundedCornerShape(8.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.03f)),
    ) {
        Column(
            Modifier.fillMaxSize().background(fundo, RoundedCornerShape(8.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(icone, tint = corIcone, size = 34.dp)
            Spacer(Modifier.height(8.dp))
            Text(titulo, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(sub, color = Color(0xDDFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuadroFuncao(icone: ImageVector, nome: String, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.height(96.dp).onFocusChanged { f = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x33FFFFFF), focusedContainerColor = Color(0xFFE53935)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.04f)),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xDDFFFFFF)), contentAlignment = Alignment.Center) {
                AppIcon(icone, tint = Color(0xFF8E2A2A), size = 22.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(nome, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BotaoPilulaPerfil(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).onFocusChanged { f = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x66FFFFFF), focusedContainerColor = Color(0xFFE53935)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.03f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 16.sp) }
    }
}
