package io.github.axon07.channelbox.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity

/**
 * ZarkTV Mobile (celular em pé), aba TV no jeito da UniTV:
 * vídeo em cima na largura toda (com compartilhar, favoritar, transmitir e tela cheia),
 * abas Canais | Favoritos e duas colunas: grupos à esquerda, canais à direita.
 * Toque no canal = abre na janelinha; toque de novo no mesmo canal (ou no vídeo) = tela cheia.
 */
@Composable
fun AbaTvCelular(
    channels: List<ChannelEntity>,
    current: ChannelEntity?,
    favorites: Set<String>,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    onPick: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    onPreviewRect: (Rect?) -> Unit,
    groups: List<Pair<ChannelFilter, String>>,
    currentGroup: ChannelFilter,
    onGroup: (ChannelFilter) -> Unit,
) {
    val ctx = LocalContext.current
    val porNome = androidx.compose.runtime.remember(upcoming) {
        val m = HashMap<String, List<ProgramEntity>>()
        upcoming.forEach { (id, l) -> m.putIfAbsent(chaveEpg(id.substringBeforeLast('.')), l) }
        m
    }
    val vermelho = Color(0xFFE50914)
    Column(Modifier.fillMaxSize()) {
        // ---- Vídeo ----
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .onGloballyPositioned { onPreviewRect(it.boundsInRoot()) }
                .clickable { if (current != null) onFull() }
        ) {
            if (current == null) Text("Escolha um canal na lista", color = Color(0xFFA1A1A6), fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconeVideo("Compartilhar") {
                    runCatching {
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, "Assista TV, filmes e séries no ZarkTV: https://tv.zarktvqx.cfd"),
                                "Compartilhar o ZarkTV",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                if (current != null) IconeVideo(if (current.id in favorites) "★" else "☆") { onFavorite(current) }
                IconeVideo("Transmitir") {
                    runCatching { ctx.startActivity(Intent("android.settings.CAST_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            if (current != null) Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000)).clickable { onFull() }.padding(horizontal = 10.dp, vertical = 6.dp)
            ) { Text("⛶ Tela cheia", color = Color.White, fontSize = 13.sp) }
        }

        // ---- Canais | Favoritos ----
        val favoritos = currentGroup == ChannelFilter.Favorites
        Row(Modifier.fillMaxWidth().background(Color(0xFF19191B)).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Canais", color = if (!favoritos) vermelho else Color(0xFFA1A1A6), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clickable { if (favoritos) onGroup(groups.firstOrNull { it.first != ChannelFilter.Favorites }?.first ?: ChannelFilter.All) },
            )
            Box(Modifier.width(1.dp).height(18.dp).background(Color(0x14EDEDED)))
            Text(
                "Favoritos", color = if (favoritos) vermelho else Color(0xFFA1A1A6), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clickable { onGroup(ChannelFilter.Favorites) },
            )
        }

        // ---- Grupos | Canais ----
        Row(Modifier.fillMaxWidth().weight(1f)) {
            val grupos = groups.filter { it.first != ChannelFilter.Favorites }
            LazyColumn(Modifier.width(118.dp).fillMaxHeight().background(Color(0xFF0F0F10))) {
                items(grupos, key = { it.second }) { (f, nome) ->
                    val sel = f == currentGroup
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).background(if (sel) Color(0xFF232325) else Color.Transparent).clickable { onGroup(f) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(if (sel) vermelho else Color.Transparent))
                        Text(
                            nome.trim(), color = if (sel) Color.White else Color(0xFFA1A1A6), fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
            }
            val estado = rememberLazyListState()
            LaunchedEffect(currentGroup) { runCatching { estado.scrollToItem(0) } }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF141415)), state = estado) {
                if (channels.isEmpty()) item {
                    Text(
                        if (favoritos) "Nenhum favorito ainda. Toque na ☆ do vídeo para favoritar." else "Nenhum canal neste grupo.",
                        color = Color(0xFF85858B), fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                }
                items(channels, key = { it.id }) { ch ->
                    val tocando = ch.id == current?.id
                    // Programação: pelo id do guia e, se o canal não tiver id, pelo nome (Globo → globo.br…).
                    val prog = (ch.epgId?.let { upcoming[it] } ?: porNome[chaveEpg(ch.name)])?.firstOrNull { it.start <= now && it.stop > now }
                    val agora = prog?.let {
                        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
                        f.format(java.util.Date(it.start)) + " – " + f.format(java.util.Date(it.stop)) + "  " + it.title
                    }
                    Row(
                        Modifier.fillMaxWidth().height(72.dp)
                            .background(if (tocando) vermelho else Color.Transparent)
                            .clickable { if (tocando) onFull() else onPick(ch) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 44.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                            ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "%03d  %s".format(ch.number, ch.name), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                agora ?: "Sem programação", color = if (tocando) Color(0xEEFFFFFF) else Color(0xFFA1A1A6), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (prog != null) {
                                Spacer(Modifier.height(6.dp))
                                val frac = ((now - prog.start).toFloat() / (prog.stop - prog.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxWidth(0.9f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0x33FFFFFF))) {
                                    Box(Modifier.fillMaxWidth(frac).height(2.dp).background(if (tocando) Color.White else vermelho))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconeVideo(texto: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x88000000)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(texto, color = Color.White, fontSize = 13.sp) }
}


/** Nome do canal ou id do guia → chave simples ("Globo SP HD" e "GloboSP.br" → "globosp"). */
internal fun chaveEpg(n: String): String = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").lowercase()
    .replace(Regex("""[²³¹]|\b(fhd|uhd|4k|hd|sd|h265|hevc|alt|24h)\b|\(.*?\)|\[.*?\]"""), " ")
    .replace(Regex("[^a-z0-9]"), "")
