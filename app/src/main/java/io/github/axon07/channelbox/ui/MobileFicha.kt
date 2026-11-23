package io.github.axon07.channelbox.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.VodEntity
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.VodProgressEntity
import io.github.axon07.channelbox.data.ZarkInfo
import io.github.axon07.channelbox.data.ZarkPrefs

// ZarkTV Mobile: ficha de filme e de série no jeito da UniTV Mobile (mapeada no tablet):
// vídeo largo fixo no topo que toca ali mesmo (botão de tela cheia), embaixo título, nota,
// infos, sinopse, temporadas/episódios, ações e "Você também pode gostar" em grade.

private val Vermelho = Color(0xFFE50914)
private val Fundo = Color(0xFF141415)
private val Elevado = Color(0xFF232325)
private val T1 = Color(0xFFEDEDED)
private val T2 = Color(0xFFA1A1A6)
private val T3 = Color(0xFF85858B)
private val Laranja = Color(0xFFFF9800)

/** Vídeo do topo. Parado: imagem + play. Tocando: janela transparente (o TvApp desenha o vídeo atrás). */
@Composable
private fun VideoTopo(b: VodBrowser, imagem: String?, titulo: String, onVoltar: () -> Unit, onTransmitir: () -> Unit, onTocar: () -> Unit) {
    val tocando = b.previewId != null
    DisposableEffect(Unit) { onDispose { b.previewRect = null } }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(if (tocando) Color.Transparent else Color.Black)
            .then(if (tocando) Modifier.onGloballyPositioned { b.previewRect = it.boundsInRoot() } else Modifier)
            .clickable { if (!tocando) onTocar() }
    ) {
        if (!tocando) {
            AsyncImage(imagem, titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color(0x55000000)))
            Box(
                Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) { AppIcon(AppIcons.Play, tint = Color.White, size = 30.dp) }
        }
        Box(
            Modifier.align(Alignment.TopStart).padding(8.dp).size(40.dp).clip(CircleShape).clickable(onClick = onVoltar),
            contentAlignment = Alignment.Center,
        ) { AppIcon(AppIcons.Voltar, tint = Color.White, size = 22.dp) }
        // Transmitir para a TV (DLNA) ou espelhar a tela.
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp).clip(CircleShape).clickable(onClick = onTransmitir),
            contentAlignment = Alignment.Center,
        ) { AppIcon(AppIcons.Transmitir, tint = Color.White, size = 20.dp) }
        if (tocando) Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp).clip(CircleShape).background(Color(0x66000000))
                .clickable { b.acaoTelaCheia?.invoke() },
            contentAlignment = Alignment.Center,
        ) { AppIcon(AppIcons.TelaCheia, tint = Color.White, size = 20.dp) }
    }
}

/** Título com a nota em laranja, linha de infos, sinopse com "Mais", direção e elenco. */
@Composable
private fun CabecalhoCel(titulo: String, ficha: ZarkInfo?, plot: String?, rating: String?, year: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(titulo, color = T1, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp, modifier = Modifier.weight(1f, fill = false))
        val nota = (ficha?.nota ?: rating)?.toDoubleOrNull()?.takeIf { it > 0 }
        if (nota != null) {
            Spacer(Modifier.width(8.dp))
            Text(String.format(java.util.Locale.US, "%.1f", nota), color = Laranja, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
    val infos = listOfNotNull((ficha?.data ?: year)?.take(4)?.takeIf { it.isNotBlank() }, ficha?.genero?.takeIf { it.isNotBlank() }, ficha?.duracao?.takeIf { it.isNotBlank() })
    if (infos.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(infos.joinToString(" | "), color = T2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val sinopse = (ficha?.sinopse ?: plot)?.takeIf { it.isNotBlank() }
    if (sinopse != null) {
        var aberta by remember(titulo) { mutableStateOf(false) }
        Spacer(Modifier.height(12.dp))
        Text(sinopse, color = T1, fontSize = 14.sp, lineHeight = 20.sp, maxLines = if (aberta) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        if (!aberta && sinopse.length > 140) Text("Mais", color = Vermelho, fontSize = 13.sp, modifier = Modifier.clickable { aberta = true }.padding(vertical = 4.dp))
    }
    ficha?.diretor?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(8.dp)); Text("Direção: $it", color = T2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    ficha?.elenco?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(4.dp)); Text("Elenco: $it", color = T2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/** Baixar · Partilhar · Favoritos (como na UniTV). [baixar] = o arquivo que vai ser baixado (filme ou episódio). */
@Composable
private fun AcoesCel(vm: TvViewModel, item: VodEntity, baixar: VodEntity?) {
    val ctx = LocalContext.current
    var fav by remember(item.id) { mutableStateOf(ZarkPrefs.ehFavorito(ctx, item.id)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        AcaoCel(AppIcons.Download, "Baixar", Modifier.weight(1f)) {
            val alvo = baixar ?: item
            val titulo = if (alvo.id != item.id) "${item.title} T${alvo.season}E${alvo.episode}" else item.title
            vm.zarkBaixar(alvo, titulo, item.poster)
        }
        AcaoCel(AppIcons.Partilhar, "Partilhar", Modifier.weight(1f)) {
            runCatching {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Assista \"${item.title}\" no ZarkTV: https://tv.zarktvqx.cfd"), "Partilhar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        AcaoCel(if (fav) AppIcons.EstrelaCheia else AppIcons.Estrela, "Favoritos", Modifier.weight(1f), if (fav) Vermelho else T1) { fav = vm.zarkFavoritar(item) }
    }
}

@Composable
private fun AcaoCel(icone: androidx.compose.ui.graphics.vector.ImageVector, nome: String, modifier: Modifier, cor: Color = T1, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(icone, tint = cor, size = 22.dp)
        Spacer(Modifier.height(6.dp))
        Text(nome, color = T2, fontSize = 12.sp)
    }
}

/** "Você também pode gostar": grade de 3 capas, com a nota no canto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParecidosCel(vm: TvViewModel, atual: VodEntity, onOpen: (VodEntity) -> Unit) {
    val parecidos by remember(atual.id) {
        vm.vodTop(atual.kind, atual.category, 13).let { f ->
            kotlinx.coroutines.flow.flow { f.collect { l -> emit(l.filter { it.id != atual.id }.take(9)) } }
        }
    }.collectAsState(initial = emptyList())
    if (parecidos.isEmpty()) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14EDEDED)))
    Spacer(Modifier.height(16.dp))
    Text("Você também pode gostar", color = T1, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp), maxItemsInEachRow = 3) {
        parecidos.forEach { p ->
            Column(Modifier.weight(1f).clickable { onOpen(p) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Elevado)) {
                    if (!p.poster.isNullOrBlank()) AsyncImage(p.poster, p.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    p.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                        Text(String.format(java.util.Locale.US, "%.1f", it), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.background(Laranja, RoundedCornerShape(bottomEnd = 6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(p.title, color = T1, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            }
        }
        repeat((3 - parecidos.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
    }
}

/** Ficha do filme (celular). */
@Composable
fun FichaFilmeCel(b: VodBrowser, movie: VodEntity, progress: VodProgressEntity?, vm: TvViewModel, onPlay: (VodEntity, VodEntity?) -> Unit) {
    var ficha by remember(movie.id) { mutableStateOf<ZarkInfo?>(null) }
    LaunchedEffect(movie.id) { ficha = vm.zarkFicha(movie) }
    LaunchedEffect(ficha, movie.id) { b.fundoUrl = null }
    val rol = rememberScrollState()
    LaunchedEffect(movie.id) { rol.scrollTo(0) }
    val voltar = { b.movie = null; b.focusToken++; b.voltarInicio?.invoke(); Unit }
    var transmitir by remember { mutableStateOf(false) }
    if (transmitir) TransmitirDialog(vm, movie.url, movie.title) { transmitir = false }
    Column(Modifier.fillMaxSize()) {
        VideoTopo(b, ficha?.fundo ?: movie.poster, movie.title, voltar, { transmitir = true }) { b.acaoPreview?.invoke(movie, null) ?: onPlay(movie, null) }
        Column(Modifier.fillMaxWidth().weight(1f).background(Fundo).verticalScroll(rol).padding(horizontal = 16.dp, vertical = 16.dp)) {
            CabecalhoCel(movie.title, ficha, movie.plot, movie.rating, movie.year)
            AcoesCel(vm, movie, movie)
            ParecidosCel(vm, movie) { b.movie = it }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Ficha da série (celular): temporada em lista que sobe, episódios em quadradinhos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FichaSerieCel(b: VodBrowser, show: VodEntity, progress: Map<String, VodProgressEntity>, vm: TvViewModel, onPlay: (VodEntity, VodEntity?) -> Unit) {
    LaunchedEffect(show.id) {
        if (b.episodes == null) {
            val list = vm.episodes(show)
            b.episodes = list
            val last = list.filter { it.id in progress }.maxByOrNull { progress.getValue(it.id).updated }
            b.season = last?.season ?: list.firstOrNull()?.season
        }
    }
    var ficha by remember(show.id) { mutableStateOf<ZarkInfo?>(null) }
    LaunchedEffect(show.id) { ficha = vm.zarkFicha(show) }
    LaunchedEffect(show.id) { b.fundoUrl = null }
    val episodes = b.episodes
    val seasons = episodes?.map { it.season }?.distinct().orEmpty()
    val inSeason = episodes?.filter { it.season == b.season }.orEmpty()
    val ultimo = episodes?.filter { it.id in progress }?.maxByOrNull { progress.getValue(it.id).updated }
    var listaTemporadas by remember { mutableStateOf(false) }
    val tocar: (VodEntity) -> Unit = { ep -> b.focusId = ep.id; b.acaoPreview?.invoke(ep, show) ?: onPlay(ep, show) }
    val voltar = { b.closeShow(); b.voltarInicio?.invoke(); Unit }
    val rol = rememberScrollState()
    var transmitir by remember { mutableStateOf(false) }
    val epAtual = episodes?.firstOrNull { it.id == b.previewId } ?: ultimo ?: inSeason.firstOrNull()
    if (transmitir) TransmitirDialog(vm, epAtual?.url, show.title + (epAtual?.let { " T${it.season}E${it.episode}" } ?: "")) { transmitir = false }
    Column(Modifier.fillMaxSize()) {
        VideoTopo(b, ficha?.fundo ?: show.poster, show.title, voltar, { transmitir = true }) {
            (ultimo ?: inSeason.firstOrNull() ?: episodes?.firstOrNull())?.let(tocar)
        }
        Column(Modifier.fillMaxWidth().weight(1f).background(Fundo).verticalScroll(rol).padding(horizontal = 16.dp, vertical = 16.dp)) {
            CabecalhoCel(show.title, ficha, show.plot, show.rating, show.year)
            Spacer(Modifier.height(16.dp))
            when {
                episodes == null -> Text("Carregando episódios…", color = T2, fontSize = 14.sp)
                episodes.isEmpty() -> Text("Nenhum episódio encontrado", color = T2, fontSize = 14.sp)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = seasons.size > 1) { listaTemporadas = true }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Temporada ${b.season ?: 1}", color = T1, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            if (seasons.size > 1) Text("  ▾", color = T1, fontSize = 14.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${inSeason.size} episódios", color = T3, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        inSeason.forEach { ep ->
                            val atual = ep.id == b.previewId || (b.previewId == null && ep.id == ultimo?.id)
                            val visto = progress[ep.id]?.let { it.duration > 0 && it.position >= it.duration * 0.9 } == true
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (atual) Vermelho else Elevado)
                                    .clickable { if (ep.id == b.previewId) b.acaoTelaCheia?.invoke() else tocar(ep) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (atual) AppIcon(AppIcons.Play, tint = Color.White, size = 20.dp)
                                else Text("${if (ep.episode > 0) ep.episode else inSeason.indexOf(ep) + 1}", color = if (visto) T3 else T1, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            AcoesCel(vm, show, b.episodes?.firstOrNull { it.id == b.previewId } ?: ultimo ?: inSeason.firstOrNull())
            ParecidosCel(vm, show) { b.closeShow(); b.openShow(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (listaTemporadas) {
        // Lista de temporadas que sobe de baixo, a escolhida em vermelho.
        Dialog(onDismissRequest = { listaTemporadas = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().clickable { listaTemporadas = false }, contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF232325))
                        .clickable(enabled = false) {}.padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Text("✕", color = T1, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterEnd).clickable { listaTemporadas = false }.padding(horizontal = 20.dp, vertical = 4.dp))
                    }
                    seasons.forEach { s ->
                        Text(
                            "Temporada $s", color = if (s == b.season) Vermelho else T1, fontSize = 17.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { b.season = s; listaTemporadas = false }.padding(vertical = 14.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
