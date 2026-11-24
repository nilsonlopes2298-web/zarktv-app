package io.github.axon07.channelbox.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.ZarkConfig

// ZarkTV Mobile: Perfil no jeito da UniTV Mobile — conta no topo, Renovar e Convidar,
// atalhos (Favoritos, Histórico, Canais), histórico e a lista de opções, tudo rolando até o fim.

private val PVermelho = Color(0xFFE50914)
private val PFundo = Color(0xFF0F0F10)
private val PCartao = Color(0xFF19191B)
private val PT1 = Color(0xFFEDEDED)
private val PT2 = Color(0xFFA1A1A6)
private val PT3 = Color(0xFF85858B)
private val PLinha = Color(0x14EDEDED)

@Composable
fun PerfilCel(
    vm: TvViewModel,
    usuario: String,
    servidor: String,
    conta: ZarkConfig.Login.Ok?,
    adultoLiberado: Boolean,
    playerFilmes: String,
    onRenovar: () -> Unit,
    onConvidar: () -> Unit,
    onSuporte: () -> Unit,
    onSair: () -> Unit,
    onOutraConta: () -> Unit,
    onAdulto: () -> Unit,
    onControleParental: () -> Unit,
    onConfiguracoes: () -> Unit,
    onAtualizar: () -> Unit,
    onPlayerFilmes: () -> Unit,
    onFavoritosCanais: () -> Unit,
    onFavoritosVod: () -> Unit,
    onAbrir: (VodEntity) -> Unit,
) {
    val historico by remember { vm.vodContinuar() }.collectAsState(initial = emptyList())
    var verBaixados by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (verBaixados) { BaixadosCel(onAbrir) { verBaixados = false }; return }
    val dias = conta?.expira?.let { ZarkConfig.diasRestantes(it) }
    val vence = conta?.expira?.let { ZarkConfig.dataBr(it) }
    // Tela toda: conteúdo em cima e "Sair da conta" sempre no rodapé (rola se não couber).
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(PFundo)) {
    val alturaPerfil = maxHeight
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .heightIn(min = alturaPerfil).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
    ) {
        // ---- Conta ----
        run {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(PVermelho), contentAlignment = Alignment.Center) {
                    Text(usuario.take(1).uppercase().ifBlank { "Z" }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(usuario.ifBlank { "Minha conta" }, color = PT1, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(servidor.ifBlank { "ZarkTV" }, color = PT3, fontSize = 13.sp, maxLines = 1)
                    if (vence != null) Text(
                        if (dias != null && dias < 0) "Vencido em $vence" else "Vence em $vence" + (dias?.let { "  ·  faltam $it dias" } ?: ""),
                        color = if (dias != null && dias <= 5) PVermelho else PT2, fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- Renovar · Convidar ----
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Renovar em dourado (chama atenção sem parecer erro), com quantos dias faltam.
                val subRenovar = when {
                    dias == null -> "Pix na hora"
                    dias < 0 -> "Venceu · reative agora"
                    dias <= 5 -> "Faltam $dias dias · Pix na hora"
                    else -> "Pix na hora · ${dias} dias restantes"
                }
                Column(
                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFFC83D), Color(0xFFF59E0B))))
                        .clickable(onClick = onRenovar).padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Renovar agora", color = Color(0xFF231800), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(subRenovar, color = Color(0xCC231800), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CartaoTopo("Convidar amigos", "Compartilhe o app", Color(0xFF2A2A2E), Modifier.weight(1f), onConvidar)
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- Atalhos ----
        run {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao).padding(vertical = 12.dp)) {
                Atalho(AppIcons.Estrela, "Favoritos", Modifier.weight(1f), onFavoritosVod)
                Atalho(AppIcons.Tv, "Canais favoritos", Modifier.weight(1f), onFavoritosCanais)
                Atalho(AppIcons.Download, "Baixados", Modifier.weight(1f)) { verBaixados = true }
                Atalho(AppIcons.Headset, "Suporte", Modifier.weight(1f), onSuporte)
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- Histórico ----
        if (historico.isNotEmpty()) run {
            Text("Histórico", color = PT1, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(historico, key = { it.id }) { h ->
                    Column(Modifier.width(96.dp).clickable { onAbrir(h) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(PCartao)) {
                            if (!h.poster.isNullOrBlank()) AsyncImage(h.poster, h.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(h.title, color = PT2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- Opções ----
        run {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao)) {
                Linha(AppIcons.User, "Trocar de conta", null, onOutraConta)
                Linha(AppIcons.Estrela, "Conteúdo Hot (+18)", if (adultoLiberado) "Liberado" else "Bloqueado", onAdulto)
                Linha(AppIcons.Calendario, "Controle parental", null, onControleParental)
                Linha(AppIcons.Play, "Player de filmes", playerFilmes, onPlayerFilmes)
                Linha(AppIcons.Resolucao, "Configurações", null, onConfiguracoes)
                Linha(AppIcons.Externo, "Verificar atualização", "v" + vm.appVersion, onAtualizar, ultima = true)
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
        run {
            Text(
                "Sair da conta", color = PVermelho, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao).clickable(onClick = onSair)
                    .padding(vertical = 14.dp).let { it },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    }
}

@Composable
private fun CartaoTopo(titulo: String, sub: String, cor: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(72.dp).clip(RoundedCornerShape(12.dp)).background(cor).clickable(onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.Center) {
        Text(titulo, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = Color(0xCCFFFFFF), fontSize = 12.sp)
    }
}

@Composable
private fun Atalho(icone: ImageVector, nome: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(icone, tint = PVermelho, size = 22.dp)
        Spacer(Modifier.height(6.dp))
        Text(nome, color = PT1, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun Linha(icone: ImageVector, nome: String, valor: String?, onClick: () -> Unit, ultima: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icone, tint = PT3, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Text(nome, color = PT1, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (valor != null) Text(valor, color = PT3, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text("›", color = PT3, fontSize = 18.sp)
        }
        if (!ultima) Box(Modifier.fillMaxWidth().padding(start = 46.dp).height(1.dp).background(PLinha))
    }
}


/** Perfil → Baixados: o que foi baixado para ver sem internet, com o andamento. */
@Composable
private fun BaixadosCel(onAbrir: (VodEntity) -> Unit, onVoltar: () -> Unit) {
    androidx.activity.compose.BackHandler { onVoltar() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var itens by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(io.github.axon07.channelbox.data.Baixados.lista(ctx)) }
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2_000); tick++ } }
    Column(Modifier.fillMaxSize().background(PFundo)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onVoltar), contentAlignment = Alignment.Center) { AppIcon(AppIcons.Voltar, tint = PT1, size = 22.dp) }
            Text("Baixados", color = PT1, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        if (itens.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Nada baixado ainda. Na ficha do filme ou da série, toque em Baixar.", color = PT3, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(itens, key = { it.id }) { b ->
                val p = androidx.compose.runtime.remember(tick, b.id) { io.github.axon07.channelbox.data.Baixados.progresso(ctx, b) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao).clickable(enabled = p == 100) {
                        onAbrir(VodEntity(b.id, "baixado", io.github.axon07.channelbox.data.VodKind.MOVIE, b.titulo, "", b.capa, "file://" + b.arquivo,
                            null, null, 0, 0, 0, 0, null, null, null))
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF232325))) {
                        if (!b.capa.isNullOrBlank()) AsyncImage(b.capa, b.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.titulo, color = PT1, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when { p == 100 -> "Pronto · toque para assistir"; p < 0 -> "Em desenvolvimento para este título"; else -> "Baixando… $p%" },
                            color = when { p == 100 -> Color(0xFF4CC38A); p < 0 -> PT3; else -> PT2 }, fontSize = 12.sp,
                        )
                        if (p in 0..99) {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
                                Box(Modifier.fillMaxWidth(p / 100f).height(3.dp).background(PVermelho))
                            }
                        }
                    }
                    Text("Apagar", color = PT3, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        io.github.axon07.channelbox.data.Baixados.apagar(ctx, b.id); itens = io.github.axon07.channelbox.data.Baixados.lista(ctx)
                    }.padding(8.dp))
                }
            }
        }
    }
}
