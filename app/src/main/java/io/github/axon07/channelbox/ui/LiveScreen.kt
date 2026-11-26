package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import kotlinx.coroutines.delay

/** Onde fica a prévia do canal na tela de TV ao vivo (a mesma conta é usada para posicionar o vídeo). */
data class LiveGeom(val portrait: Boolean, val x: Dp, val y: Dp, val w: Dp, val h: Dp, val listW: Dp, val gruposW: Dp = 0.dp)

@Composable
fun liveGeom(): LiveGeom {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    val sw = c.screenWidthDp.dp
    val sh = c.screenHeightDp.dp
    return if (c.screenHeightDp > c.screenWidthDp) {
        val w = sw - 24.dp
        LiveGeom(true, 12.dp, 64.dp, w, w * 9f / 16f, sw)
    } else if (ZarkLayout.leve.value) {
        // Layout leve: grupos | canais | prévia (3 colunas, como no Zplay 2).
        val gruposW = sw * 0.22f
        val listW = sw * 0.30f
        val x = 12.dp + gruposW + 8.dp + listW + 10.dp
        var w = sw - x - 12.dp
        var h = w * 9f / 16f
        val maxH = (sh - 64.dp) * 0.55f
        if (h > maxH) { h = maxH; w = h * 16f / 9f }
        LiveGeom(false, x, 64.dp, w, h, listW, gruposW)
    } else {
        val listW = sw * 0.36f
        var w = sw - listW - 52.dp
        var h = w * 9f / 16f
        val maxH = sh - 64.dp - 110.dp
        if (h > maxH) { h = maxH; w = h * 16f / 9f }
        LiveGeom(false, sw - 24.dp - w, 64.dp, w, h, listW)
    }
}

/**
 * TV ao vivo no estilo da UniTV: lista de canais (logo, número e nome) e a prévia do canal ao lado.
 * OK/toque num canal liga ele na prévia; OK de novo (ou "Tela cheia") abre em tela cheia.
 */
@Composable
fun LiveScreen(
    channels: List<ChannelEntity>,
    groupLabel: String,
    groups: List<Pair<ChannelFilter, String>>,
    currentGroup: ChannelFilter,
    current: ChannelEntity?,
    favorites: Set<String>,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    timeFormat: (Long) -> String,
    onPrevGroup: () -> Unit,
    onNextGroup: () -> Unit,
    onGroup: (ChannelFilter) -> Unit,
    onPick: (ChannelEntity) -> Unit,
    onFull: () -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    onBack: () -> Unit,
    /** Quantos canais tem cada grupo (layout leve mostra ao lado do nome). */
    contagem: (ChannelFilter) -> Int? = { null },
) {
    val g = liveGeom()
    var showGroups by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusCurrent = remember { FocusRequester() }
    val startIndex = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(channels) {
        runCatching { listState.scrollToItem((startIndex - 2).coerceAtLeast(0)) }
        delay(120)
        runCatching { focusCurrent.requestFocus() }
    }
    val hora = remember(now) { java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(now)) }

    // O fundo é desenhado atrás do vídeo (TvApp), para a prévia do canal aparecer.
    Box(Modifier.fillMaxSize()) {
        // Topo: voltar, marca, hora.
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("‹ Início", onClick = onBack)
            Spacer(Modifier.width(14.dp))
            Text("▶ ", color = Palette.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("TV ao vivo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(hora, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        // Moldura da prévia (o vídeo é desenhado por baixo, no mesmo lugar) + informações do canal.
        Box(
            Modifier.offset(g.x, g.y).size(g.w, g.h)
                .border(2.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
        ) {
            if (current != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .tap(onClick = onFull)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("⛶ Tela cheia", color = Color.White, fontSize = 12.sp) }
            } else {
                Text("Escolha um canal", color = Palette.TextDim, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Column(Modifier.offset(g.x, g.y + g.h + 8.dp).width(g.w)) {
            Text(current?.name.orEmpty(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val progs = current?.epgId?.let { upcoming[it] }.orEmpty()
            val atual = progs.firstOrNull { it.start <= now && it.stop > now }
            val proximo = progs.firstOrNull { it.start > now }
            if (g.gruposW > 0.dp) {
                // Programação em lista (agora em amarelo, próximos abaixo).
                progs.filter { it.stop > now }.take(6).forEach { p ->
                    val agora = p.start <= now
                    Text(
                        "${timeFormat(p.start)} – ${timeFormat(p.stop)}   ${p.title}",
                        color = if (agora) Color(0xFFFFD34D) else Color(0xCCFFFFFF), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                atual?.let { Text("Agora: ${timeFormat(it.start)}  ${it.title}", color = Color(0xFFFFD34D), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                proximo?.let { Text("Depois: ${timeFormat(it.start)}  ${it.title}", color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (current != null && atual == null) Text("Toque de novo no canal (ou OK) para tela cheia", color = Palette.TextDim, fontSize = 12.sp)
        }

        // Layout leve: coluna fixa de grupos à esquerda, com a contagem de canais.
        if (g.gruposW > 0.dp) {
            Column(
                Modifier.offset(12.dp, 64.dp).width(g.gruposW).fillMaxHeight().padding(bottom = 72.dp)
                    .background(Color(0x66000000), RoundedCornerShape(10.dp))
            ) {
                LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(groups) { _, (f, label) ->
                        LinhaFocavel(selecionado = f == currentGroup, onClick = { onGroup(f) }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label.trim(), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                contagem(f)?.let { Text("$it", color = Palette.TextDim, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }

        // Lista de canais.
        val listTop = if (g.portrait) g.y + g.h + 64.dp else 64.dp
        Column(
            Modifier
                .offset(if (g.portrait) 0.dp else 12.dp + (if (g.gruposW > 0.dp) g.gruposW + 8.dp else 0.dp), listTop)
                .width(if (g.portrait) g.listW else g.listW)
                .fillMaxHeight()
                .padding(bottom = listTop + 8.dp, start = if (g.portrait) 12.dp else 0.dp, end = if (g.portrait) 12.dp else 0.dp)
                .background(Color(0x66000000), RoundedCornerShape(10.dp))
        ) {
            // Grupo: ◀ nome ▶ (tocar no nome abre a lista de grupos). No layout leve os grupos já ficam à esquerda.
            if (g.gruposW > 0.dp) Text(groupLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
            else Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("◀", onClick = onPrevGroup)
                Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    LinhaFocavel(onClick = { showGroups = !showGroups }) {
                        Text(
                            groupLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                PillButton("▶", onClick = onNextGroup)
            }
            if (showGroups) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(groups) { _, (f, label) ->
                        LinhaFocavel(selecionado = f == currentGroup, onClick = { onGroup(f); showGroups = false }) {
                            Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                        }
                    }
                }
            } else if (channels.isEmpty()) {
                Text("Nenhum canal neste grupo", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                        val tocando = ch.id == current?.id
                        LinhaFocavel(
                            selecionado = tocando,
                            modifier = if (i == startIndex) Modifier.focusRequester(focusCurrent) else Modifier,
                            onLongClick = { onFavorite(ch) },
                            onClick = { if (tocando) onFull() else onPick(ch) },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                                    ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxSize().padding(3.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("${ch.number}", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                                Text(
                                    ch.name, color = Color.White, fontSize = 15.sp, fontWeight = if (tocando) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                if (ch.id in favorites) Text("★", color = Palette.Favorite, fontSize = 14.sp)
                                if (tocando) Text("  ▶", color = Palette.Accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Text("Segure OK num canal para favoritar", color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** Linha que acende com o foco do controle (e funciona no toque). */
@Composable
fun LinhaFocavel(
    selecionado: Boolean = false,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    barra: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    if (barra) {
        // Estilo leve: fundo cinza um pouco mais claro e uma barra vermelha fina à esquerda.
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.tap(onLongClick = onLongClick, onClick = onClick),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (selecionado) Color(0x1AFFFFFF) else Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color(0x33FFFFFF),
                focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Box {
                content()
                if (focused) Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(Palette.Accent))
            }
        }
        return
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.tap(onLongClick = onLongClick, onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selecionado) Color(0x55E50914) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Palette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) { content() }
}


/** Coluna de grupos que abre ao lado da lista de canais na tela cheia (◀ no controle). */
@Composable
fun GruposPanel(grupos: List<String>, selecionado: Int, atual: Int, foco: Boolean = true, onEscolher: (Int) -> Unit) {
    val state = rememberLazyListState()
    val marcado = if (foco) selecionado else atual
    LaunchedEffect(marcado) { runCatching { state.animateScrollToItem((marcado - 3).coerceAtLeast(0)) } }
    Column(
        Modifier.fillMaxHeight().width(230.dp).background(Color(0xE6080C18)).padding(vertical = 14.dp)
    ) {
        Text("Grupos", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            itemsIndexed(grupos) { i, nome ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(
                            when {
                                i == marcado && foco -> Palette.Accent
                                i == marcado -> Color(0xAAE50914)
                                else -> Color.Transparent
                            }
                        )
                        .tap(onClick = { onEscolher(i) })
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        nome, color = Color.White,
                        fontSize = 16.sp, fontWeight = if (i == marcado) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(if (foco) "▲▼ escolher · OK abrir · ▶ canais" else "◀ ver os grupos", color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
