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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.VodKind
import kotlinx.coroutines.delay

/**
 * ZarkPlay: tema da tela inicial. "blocos" = os blocos grandes de sempre; "cinema" = fundo com o
 * filme/série em cartaz, capas no meio e os botões de categoria embaixo. Escolhido em Configurações.
 */
object ZarkPlayTema {
    val atual = mutableStateOf("blocos")
    fun carregar(c: android.content.Context) {
        atual.value = c.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE).getString("zp_tema", "blocos") ?: "blocos"
    }
    fun salvar(c: android.content.Context, v: String) {
        atual.value = v
        c.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE).edit().putString("zp_tema", v).apply()
    }
}

/**
 * Tema "Cinema" do ZarkPlay: por trás passa o filme ou série em cartaz (troca sozinho), à esquerda
 * o nome e a sinopse, no meio as capas (escolher uma mostra ela no fundo; OK abre) e embaixo os
 * botões Canais ao vivo, Filmes, Séries e Jogos do dia. Em cima: atualizar, configurações, conta,
 * conexão, data e hora. Mesmas funções do tema de blocos.
 */
@Composable
fun InicioCinemaZp(
    vm: TvViewModel,
    usuario: String,
    vencimento: String?,
    avisoVencimento: String?,
    atualizando: Boolean,
    onAoVivo: () -> Unit,
    onFilmes: () -> Unit,
    onSeries: () -> Unit,
    onJogos: () -> Unit,
    onConta: () -> Unit,
    onAjustes: () -> Unit,
    onAtualizar: () -> Unit,
    onAbrirItem: (VodEntity) -> Unit,
) {
    val filmes by remember { vm.vodTop(VodKind.MOVIE, null, 10) }.collectAsState(initial = emptyList())
    val series by remember { vm.vodTop(VodKind.SERIES, null, 10) }.collectAsState(initial = emptyList())
    // Intercala filmes e séries, só com capa.
    val capas = remember(filmes, series) {
        val f = filmes.filter { !it.poster.isNullOrBlank() }.take(10)
        val s = series.filter { !it.poster.isNullOrBlank() }.take(10)
        buildList { for (i in 0 until maxOf(f.size, s.size)) { f.getOrNull(i)?.let(::add); s.getOrNull(i)?.let(::add) } }
    }
    var destaque by remember { mutableIntStateOf(0) }
    var focoNasCapas by remember { mutableStateOf(false) }
    // Sem ninguém mexendo nas capas, o destaque troca sozinho a cada 8 segundos.
    LaunchedEffect(capas.size, focoNasCapas) {
        while (!focoNasCapas && capas.isNotEmpty()) { delay(8_000); destaque = (destaque + 1) % capas.size }
    }
    val item = capas.getOrNull(destaque.coerceAtMost((capas.size - 1).coerceAtLeast(0)))

    val primeiro = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { primeiro.requestFocus() } }
    var hora by remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())) }
    var data by remember { mutableStateOf(java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale("pt", "BR")).format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
            data = java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale("pt", "BR")).format(java.util.Date())
        }
    }
    val ctxRede = androidx.compose.ui.platform.LocalContext.current
    var rede by remember { mutableIntStateOf(redeZp(ctxRede)) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); rede = redeZp(ctxRede) } }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D))) {
        // Fundo: a capa em destaque, com escurecido à esquerda e embaixo para o texto aparecer.
        androidx.compose.animation.Crossfade(targetState = item?.poster, label = "fundo") { url ->
            if (url != null) coil.compose.AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF20B0B0D), 0.45f to Color(0xB30B0B0D), 1f to Color(0x330B0B0D))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x990B0B0D), 0.25f to Color.Transparent, 0.55f to Color(0x660B0B0D), 1f to Color(0xF50B0B0D))))

        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 22.dp)) {
            // Topo
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Zark", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Text("TV", color = Color(0xFFE50914), fontSize = 32.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                IconeCinema(AppIcons.Refresh, if (atualizando) "Atualizando" else "Atualizar", onAtualizar)
                Spacer(Modifier.width(10.dp))
                IconeCinema(AppIcons.Settings, "Configurações", onAjustes)
                Spacer(Modifier.width(10.dp))
                IconeCinema(AppIcons.User, "Conta", onConta)
                Spacer(Modifier.width(18.dp))
                AppIcon(
                    when (rede) { 0 -> AppIcons.SemRede; 2 -> AppIcons.Cabo; 3 -> AppIcons.Dados; else -> AppIcons.Wifi },
                    tint = if (rede == 0) Color(0xFFFF5252) else Color.White, size = 24.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(data, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(hora, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.height(16.dp))
            // Destaque: nome, ano/nota e sinopse
            Column(Modifier.width(560.dp)) {
                Text(
                    item?.title ?: "ZarkTV", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                    lineHeight = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(item?.year?.takeIf { it.isNotBlank() }, item?.category?.takeIf { it.isNotBlank() }, item?.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }, item?.let { if (it.kind == VodKind.SERIES) "Série" else "Filme" })
                if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Color(0xFFFFD60A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                item?.plot?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xD9FFFFFF), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            // Capas em cartaz
            if (capas.isNotEmpty()) {
                Text("Em cartaz", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                    items(capas.size) { i ->
                        CapaCinema(capas[i], onFoco = { f -> if (f) { destaque = i; focoNasCapas = true } else focoNasCapas = false }) { onAbrirItem(capas[i]) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            // Categorias
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                CategoriaCinema("Canais ao vivo", AppIcons.AoVivo, onAoVivo, Modifier.weight(1f).focusRequester(primeiro))
                CategoriaCinema("Filmes", AppIcons.Film, onFilmes, Modifier.weight(1f))
                CategoriaCinema("Séries", AppIcons.Play, onSeries, Modifier.weight(1f))
                CategoriaCinema("Jogos do dia", AppIcons.Trofeu, onJogos, Modifier.weight(1f))
            }
            if (atualizando) { Spacer(Modifier.height(10.dp)); BarrinhaCarregando(Modifier.fillMaxWidth()) }
            Text(
                listOfNotNull(usuario.takeIf { it.isNotBlank() }?.let { "Usuário: $it" }, vencimento?.let { "Vence em: $it" }).joinToString("   ·   "),
                color = Color(0x80FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        avisoVencimento?.let { AvisoVencimentoCinema(it) }
    }
}

@Composable
private fun CapaCinema(v: VodEntity, onFoco: (Boolean) -> Unit, onClick: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by androidx.compose.animation.core.animateFloatAsState(if (foco) 1.1f else 1f, label = "capa")
    val forma = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(width = 104.dp, height = 152.dp)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(forma)
            .background(Color(0xFF1C1C21))
            .border(if (foco) 3.dp else 0.dp, if (foco) Color.White else Color.Transparent, forma)
            .onFocusChanged { foco = it.isFocused; onFoco(it.isFocused) }
            .clickable(onClickLabel = v.title, onClick = onClick),
    ) {
        coil.compose.AsyncImage(v.poster, v.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CategoriaCinema(nome: String, icone: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    var foco by remember { mutableStateOf(false) }
    val escala by androidx.compose.animation.core.animateFloatAsState(if (foco) 1.05f else 1f, label = "cat")
    val forma = RoundedCornerShape(16.dp)
    Row(
        modifier.height(64.dp)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(forma)
            .background(if (foco) Brush.horizontalGradient(listOf(Color(0xFFFF4D5A), Color(0xFFC70818))) else Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF))))
            .border(if (foco) 3.dp else 1.dp, if (foco) Color.White else Color(0x33FFFFFF), forma)
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icone, tint = Color.White, size = 26.dp)
        Spacer(Modifier.width(12.dp))
        Text(nome, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconeCinema(icone: ImageVector, nome: String, onClick: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (foco) Color(0x40FFFFFF) else Color(0x1AFFFFFF))
            .border(if (foco) 3.dp else 0.dp, if (foco) Color.White else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
            .onFocusChanged { foco = it.isFocused }
            .clickable(onClickLabel = nome, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AppIcon(icone, tint = if (foco) Color(0xFFFFD60A) else Color.White, size = 22.dp) }
}

/** Mesmo aviso de vencimento do tema de blocos (canto de baixo à direita, tela escurece 3 s). */
@Composable
private fun AvisoVencimentoCinema(texto: String) {
    var visivel by remember { mutableStateOf(true) }
    var escuro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(3_000); escuro = false; delay(5_000); visivel = false }
    if (!visivel) return
    Box(Modifier.fillMaxSize().background(if (escuro) Color(0x99000000) else Color.Transparent)) {
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 100.dp).width(360.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF0141418))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFC300)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(texto, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Renove pelo menu Conta ou fale com seu revendedor.", color = Color(0xB3FFFFFF), fontSize = 12.sp)
            }
        }
    }
}

private fun redeZp(c: android.content.Context): Int = runCatching {
    val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    when {
        caps == null -> 0
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> 1
        else -> 3
    }
}.getOrDefault(1)
