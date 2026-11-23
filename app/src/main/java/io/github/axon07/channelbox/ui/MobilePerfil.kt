package io.github.axon07.channelbox.ui

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
    val dias = conta?.expira?.let { ZarkConfig.diasRestantes(it) }
    val vence = conta?.expira?.let { ZarkConfig.dataBr(it) }
    LazyColumn(Modifier.fillMaxSize().background(PFundo), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)) {
        // ---- Conta ----
        item {
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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CartaoTopo("Renovar", "Pague com Pix", PVermelho, Modifier.weight(1f), onRenovar)
                CartaoTopo("Convidar amigos", "Compartilhe o app", Color(0xFF2A2A2E), Modifier.weight(1f), onConvidar)
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- Atalhos ----
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao).padding(vertical = 12.dp)) {
                Atalho(AppIcons.Estrela, "Favoritos", Modifier.weight(1f), onFavoritosVod)
                Atalho(AppIcons.Tv, "Canais favoritos", Modifier.weight(1f), onFavoritosCanais)
                Atalho(AppIcons.Play, "Suporte", Modifier.weight(1f), onSuporte)
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- Histórico ----
        if (historico.isNotEmpty()) item {
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
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao)) {
                Linha(AppIcons.User, "Trocar de conta", null, onOutraConta)
                Linha(AppIcons.Estrela, "Conteúdo adulto", if (adultoLiberado) "Liberado" else "Bloqueado", onAdulto)
                Linha(AppIcons.Calendario, "Controle parental", null, onControleParental)
                Linha(AppIcons.Play, "Player de filmes", playerFilmes, onPlayerFilmes)
                Linha(AppIcons.Resolucao, "Configurações", null, onConfiguracoes)
                Linha(AppIcons.Externo, "Verificar atualização", "v" + vm.appVersion, onAtualizar, ultima = true)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Sair da conta", color = PVermelho, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PCartao).clickable(onClick = onSair)
                    .padding(vertical = 14.dp).let { it },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
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
