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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.VodEntity
import kotlinx.coroutines.delay

// ---- Tela cheia: painel de canais no estilo UniTV ------------------------------------------------

/**
 * Por cima do vídeo: barra de ícones (buscar, favoritos, lista) e a "Lista de canais" com logo, número,
 * nome e o programa atual. ◀ abre a barra com os grupos (ícones + nomes), como na UniTV.
 */
@Composable
fun CanaisOverlay(
    titulo: String,
    channels: List<ChannelEntity>,
    selected: Int,
    listState: LazyListState,
    playingId: String?,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    grupos: List<String>,
    grupoSel: Int,
    grupoAtual: Int,
    gruposAbertos: Boolean,
    onCanal: (Int) -> Unit,
    onFavoritar: (Int) -> Unit,
    onGrupo: (Int) -> Unit,
    onAbrirGrupos: () -> Unit,
    onFavoritos: () -> Unit,
    onBuscar: () -> Unit,
    onVoltar: () -> Unit,
) {
    Row(Modifier.fillMaxHeight()) {
        // Barra de ícones (fechada) ou ícones + grupos (aberta).
        Column(
            Modifier.fillMaxHeight().width(if (gruposAbertos) 250.dp else 56.dp)
                .background(Color(0xF0080A14)).tap { }.padding(vertical = 14.dp),
        ) {
            ItemRail(AppIcons.Search, "Buscar", gruposAbertos, false, onBuscar)
            ItemRail(AppIcons.Star, "Favoritos", gruposAbertos, false, onFavoritos)
            ItemRail(AppIcons.List, "Lista de canais", gruposAbertos, !gruposAbertos, onAbrirGrupos)
            ItemRail(AppIcons.Close, "Voltar ao início", gruposAbertos, false, onVoltar)
            if (gruposAbertos) {
                Spacer(Modifier.height(8.dp))
                val st = rememberLazyListState()
                LaunchedEffect(grupoSel) { runCatching { st.animateScrollToItem((grupoSel - 3).coerceAtLeast(0)) } }
                LazyColumn(state = st, modifier = Modifier.weight(1f)) {
                    itemsIndexed(grupos) { i, nome ->
                        Box(
                            Modifier.fillMaxWidth()
                                .background(
                                    when {
                                        i == grupoSel -> Palette.Accent
                                        i == grupoAtual -> Color(0x55E50914)
                                        else -> Color.Transparent
                                    }
                                )
                                .tap(onClick = { onGrupo(i) })
                                .padding(start = 26.dp, end = 10.dp, top = 13.dp, bottom = 13.dp)
                        ) {
                            Text(nome, color = Color.White, fontSize = 17.sp, fontWeight = if (i == grupoSel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        // Lista de canais.
        Column(
            Modifier.fillMaxHeight().width(340.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xE60B1230), Color(0xCC0B1230))))
                .tap { },
        ) {
            Text(
                if (gruposAbertos) titulo else "Lista de canais · $titulo",
                color = Color(0xDDFFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                    val sel = i == selected && !gruposAbertos
                    val tocando = ch.id == playingId
                    val prog = ch.epgId?.let { upcoming[it] }?.firstOrNull { it.start <= now && it.stop > now }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (sel) Palette.Accent else Color.Transparent)
                            .tap(onLongClick = { onFavoritar(i) }, onClick = { onCanal(i) })
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 46.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                            ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (tocando) "▶ " else "${ch.number}  ") + ch.name.uppercase(),
                                color = if (tocando && !sel) Color(0xFFFF5A5F) else Color.White,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(prog?.title ?: "Sem dados", color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF)))
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("☰", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text("Segure [OK]: favoritar · ◀ grupos", color = Color(0xCCFFFFFF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ItemRail(icone: ImageVector, nome: String, aberto: Boolean, ativo: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icone, tint = if (ativo) Color(0xFFFF4D57) else Color.White, size = 22.dp)
        if (aberto) {
            Spacer(Modifier.width(12.dp))
            Text(nome, color = Color.White, fontSize = 17.sp, maxLines = 1)
        }
    }
}

// ---- Catálogo: banner grande + blocos coloridos + capas altas (Filmes / Séries) ------------------

data class BlocoCor(val nome: String, val cor: Color, val imagem: String?, val acao: () -> Unit)

/**
 * Topo das abas Filmes e Séries, igual à UniTV: banner que troca sozinho (com o nome e as bolinhas),
 * quatro blocos coloridos embaixo e duas capas altas à direita.
 */
@Composable
fun HeroCatalogo(
    vm: TvViewModel,
    destaques: List<VodEntity>,
    blocos: List<BlocoCor>,
    altura: Dp,
    focoInicial: FocusRequester? = null,
    onOpen: (VodEntity) -> Unit,
) {
    if (destaques.isEmpty()) return
    val banner = destaques.take(4)
    val altos = destaques.drop(4).take(2)
    var i by remember(banner) { mutableIntStateOf(0) }
    LaunchedEffect(banner) {
        while (banner.size > 1) {
            delay(if (ZarkLeve.ativo) 9000 else 5000)
            i = (i + 1) % banner.size
        }
    }
    // Imagem de fundo de verdade (backdrop) quando o servidor tiver; senão a capa.
    val fundos = remember(banner) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(banner) {
        banner.forEach { b ->
            vm.zarkFicha(b)?.fundo?.let { url -> fundos.value = fundos.value + (b.id to url) }
        }
    }
    Row(Modifier.fillMaxWidth().height(altura), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(2.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val atual = banner[i % banner.size]
            TileImagem(
                imagem = fundos.value[atual.id] ?: atual.poster,
                titulo = atual.title,
                modifier = Modifier.fillMaxWidth().weight(1.75f).let { m -> focoInicial?.let { m.focusRequester(it) } ?: m },
                tituloGrande = true,
                bolinhas = banner.size to (i % banner.size),
            ) { onOpen(atual) }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                blocos.forEach { b ->
                    TileImagem(b.imagem, b.nome, Modifier.weight(1f).fillMaxHeight(), cor = b.cor, rotulo = true) { b.acao() }
                }
            }
        }
        altos.forEach { a ->
            TileImagem(a.poster, null, Modifier.weight(1f).fillMaxHeight()) { onOpen(a) }
        }
    }
}

/** Mosaico (Destaques, Kids, Anime): um grande, colunas de dois e uma fileira embaixo. */
@Composable
fun Mosaico(itens: List<VodEntity>, altura: Dp, focoInicial: FocusRequester? = null, onMais: (() -> Unit)? = null, onOpen: (VodEntity) -> Unit) {
    if (itens.isEmpty()) return
    fun t(k: Int) = itens.getOrNull(k)
    Column(Modifier.fillMaxWidth().height(altura), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().weight(1.9f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            t(0)?.let { a -> TileImagem(a.poster, a.title, Modifier.weight(2f).fillMaxHeight().let { m -> focoInicial?.let { m.focusRequester(it) } ?: m }) { onOpen(a) } }
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                t(1)?.let { a ->
                    if (onMais != null) TileImagem(a.poster, "Mais", Modifier.fillMaxWidth().weight(1f), rotulo = true) { onMais() }
                    else TileImagem(a.poster, a.title, Modifier.fillMaxWidth().weight(1f)) { onOpen(a) }
                }
                t(2)?.let { a -> TileImagem(a.poster, a.title, Modifier.fillMaxWidth().weight(1f)) { onOpen(a) } }
            }
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOfNotNull(t(3), t(4)).forEach { a -> TileImagem(a.poster, a.title, Modifier.fillMaxWidth().weight(1f)) { onOpen(a) } }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (5..9).mapNotNull { t(it) }.forEach { a -> TileImagem(a.poster, a.title, Modifier.weight(1f).fillMaxHeight()) { onOpen(a) } }
        }
    }
}

/** Quadro com imagem que acende no foco (borda clara), com nome por cima. */
@Composable
fun TileImagem(
    imagem: String?,
    titulo: String?,
    modifier: Modifier,
    cor: Color? = null,
    rotulo: Boolean = false,
    tituloGrande: Boolean = false,
    bolinhas: Pair<Int, Int>? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color(0xFFFFE082) else Color.Transparent, RoundedCornerShape(8.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = cor ?: Color(0xFF2A0A0E), focusedContainerColor = cor ?: Color(0xFF2A0A0E)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.03f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (titulo != null && !rotulo && imagem.isNullOrBlank()) {
                Text(titulo, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (!imagem.isNullOrBlank()) {
                // Capa preenchendo o quadrado inteiro; a cor do atalho fica numa faixa por baixo do nome.
                AsyncImage(imagem, titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (cor != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, cor.copy(alpha = 0.85f)))))
            }
            if (rotulo && titulo != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)
                        .background(Color(0xAA1A1A1A), RoundedCornerShape(50)).padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(titulo, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            } else if (tituloGrande && titulo != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(14.dp)) {
                    Text(titulo.uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            bolinhas?.let { (n, at) ->
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color(0x66000000), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(n) { k ->
                        Box(Modifier.size(width = if (k == at) 16.dp else 6.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(if (k == at) Palette.Accent else Color(0x99FFFFFF)))
                    }
                }
            }
        }
    }
}

// ---- Tutorial da primeira vez ----------------------------------------------------------------------

private data class PassoTutorial(val numero: String, val titulo: String, val itens: List<String>)

private val PASSOS = listOf(
    PassoTutorial(
        "1", "TV AO VIVO",
        listOf(
            "Escolha o canal na lista: ele abre na janelinha ao lado.",
            "OK de novo (ou toque na janelinha) para tela cheia.",
            "Na tela cheia, aperte OK para ver a lista de canais e ◀ para os grupos.",
            "Segure OK num canal para colocar nos Favoritos.",
        ),
    ),
    PassoTutorial(
        "2", "FILMES E SÉRIES",
        listOf(
            "Escolha pela capa, pelos blocos coloridos (Gênero, Lançamentos, Todos…) ou pela busca 🔍.",
            "Na ficha você vê a sinopse, o elenco e o botão Assistir.",
            "Se algum vídeo não abrir, use \"Player externo\" (VLC, MX Player).",
        ),
    ),
    PassoTutorial(
        "3", "SUA CONTA",
        listOf(
            "Os avisos de vencimento aparecem no cantinho da tela.",
            "Renove na hora pelo Pix em Perfil → Renovar / Pagar.",
            "No Perfil: senha de adulto, tema do app, player e suporte no WhatsApp.",
        ),
    ),
)

@Composable
fun ZarkTutorial(onFim: () -> Unit) {
    var passo by remember { mutableIntStateOf(0) }
    val botao = remember { FocusRequester() }
    LaunchedEffect(passo) {
        delay(120)
        runCatching { botao.requestFocus() }
    }
    androidx.activity.compose.BackHandler { onFim() }
    val p = PASSOS[passo]
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1B1F3A), Color(0xFF2A0A1E), Color(0xFF0B0B12))))
            .tap { },
    ) {
        val compacto = maxWidth < 700.dp
        Column(Modifier.fillMaxSize().padding(if (compacto) 20.dp else 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.numero, color = Color(0xFFFFB300), fontSize = if (compacto) 48.sp else 72.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Spacer(Modifier.width(16.dp))
                Text(p.titulo, color = Color(0xFFFFB300), fontSize = if (compacto) 22.sp else 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            p.itens.forEach {
                Text("•  $it", color = Color.White, fontSize = if (compacto) 15.sp else 19.sp, modifier = Modifier.padding(vertical = 5.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PASSOS.indices.forEach { k ->
                        Box(Modifier.size(width = if (k == passo) 22.dp else 8.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(if (k == passo) Palette.Accent else Color(0x66FFFFFF)))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(120.dp)) { BotaoCheio("Pular", Color(0x33FFFFFF), altura = 48, onClick = onFim) }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(240.dp)) {
                    BotaoCheio(if (passo < PASSOS.lastIndex) "Próximo" else "Experimente já", Palette.Accent, altura = 48, modifier = Modifier.focusRequester(botao)) {
                        if (passo < PASSOS.lastIndex) passo++ else onFim()
                    }
                }
            }
        }
    }
}


// ---- Atualização: janela "Nova versão" (como na UniTV) ------------------------------------------

@Composable
fun NovaVersaoDialog(
    versao: String,
    atual: String,
    conta: String,
    notas: String,
    obrigatoria: Boolean,
    onAtualizar: () -> Unit,
    onCancelar: () -> Unit,
) {
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { foco.requestFocus() }
    }
    val itens = buildList {
        add("Antes de atualizar, verifique se há espaço de memória suficiente.")
        notas.lineSequence().map { it.trim().removePrefix("-").removePrefix("#").trim() }
            .filter { it.isNotEmpty() && !it.contains("http") && !it.startsWith("APK") && !it.startsWith("Link") }
            .take(2).forEach { add(it) }
        add("Melhorias na experiência e correções de problemas.")
    }
    // ZarkTV Mobile: aviso pequeno, embaixo da tela (o da TV ocupava o celular inteiro).
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).tap { }, contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 48.dp).background(Color(0xFF232325), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x14EDEDED), RoundedCornerShape(16.dp)).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nova versão disponível", color = Color(0xFFEDEDED), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("$atual → $versao", color = Color(0xFF85858B), fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    itens.drop(1).firstOrNull() ?: "Melhorias e correções.",
                    color = Color(0xFFA1A1A6), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                )
                if (obrigatoria) Text("Atualização obrigatória.", color = Color(0xFFE5484D), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    if (!obrigatoria) Text("Agora não", color = Color(0xFFA1A1A6), fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).tap(onClick = onCancelar).padding(horizontal = 14.dp, vertical = 10.dp))
                    Text("Atualizar", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFE50914)).tap(onClick = onAtualizar).padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).tap { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(Color(0xFF34343A), RoundedCornerShape(6.dp)).padding(bottom = 14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF45454C), Color(0xFF34343A))), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Nova versão", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
            Column(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Novas funções", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("Versão: $versao", color = Color(0xFFFFB300), fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                itens.forEachIndexed { i, t ->
                    Text("${i + 1}. $t", color = Color(0xDDFFFFFF), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
                if (obrigatoria) Text("Esta atualização é obrigatória.", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!obrigatoria) Box(Modifier.weight(1f)) { BotaoPilula("Cancelar", onCancelar) }
                    Box(Modifier.weight(1f).focusRequester(foco)) { BotaoPilula("Atualizar", onAtualizar) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Versão atual: V $atual" + if (conta.isNotBlank()) "      Conta: $conta" else "",
                    color = Color(0x99FFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BotaoPilula(label: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).onFocusChanged { f = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF55555C), focusedContainerColor = Color(0xFFE53935)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.04f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 17.sp)
        }
    }
}


/** Destaques (como na UniTV): 3 capas altas + 2 empilhadas, só os mais novos, com o nome embaixo. */
@Composable
fun DestaquesGrade(itens: List<VodEntity>, altura: Dp, onOpen: (VodEntity) -> Unit) {
    if (itens.isEmpty()) return
    val foco = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().height(altura), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itens.take(3).forEachIndexed { i, a ->
            TileImagem(a.poster, a.title, Modifier.weight(1f).fillMaxHeight().let { if (i == 0) it.focusRequester(foco) else it }, rotulo = true) { onOpen(a) }
        }
        val resto = itens.drop(3).take(2)
        if (resto.isNotEmpty()) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            resto.forEach { a -> TileImagem(a.poster, a.title, Modifier.fillMaxWidth().weight(1f), rotulo = true) { onOpen(a) } }
        }
    }
}


/** Anime (como na UniTV): 3 capas altas + 2 empilhadas, a de cima é "Todos". */
@Composable
fun GradeComTodos(itens: List<VodEntity>, altura: Dp, rotuloTodos: String, onOpen: (VodEntity) -> Unit, onTodos: () -> Unit) {
    if (itens.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(altura), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itens.take(3).forEach { a -> TileImagem(a.poster, a.title, Modifier.weight(1f).fillMaxHeight()) { onOpen(a) } }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileImagem(itens.getOrNull(3)?.poster, rotuloTodos, Modifier.fillMaxWidth().weight(1f), rotulo = true) { onTodos() }
            itens.getOrNull(4)?.let { a -> TileImagem(a.poster, a.title, Modifier.fillMaxWidth().weight(1f)) { onOpen(a) } }
        }
    }
}
