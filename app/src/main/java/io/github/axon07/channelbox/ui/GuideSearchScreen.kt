package io.github.axon07.channelbox.ui

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay

/** What the guide search shows; kept outside the screen so it survives watching a result. */
@Stable
class GuideSearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<TvViewModel.GuideHit>?>(null)
    var focusIndex = 0
}

/** Search programme titles in the TV guide. OK: watch (on now), catch-up (earlier) or record (later). */
@Composable
fun GuideSearchScreen(
    state: GuideSearchState,
    vm: TvViewModel,
    now: Long,
    recordingKeys: Set<String>,
    timeFormat: (Long) -> String,
    onPick: (TvViewModel.GuideHit) -> Unit,
    /** ZarkTV: abre um filme ou série encontrado (null = não mostra filmes/séries). */
    onOpenVod: ((io.github.axon07.channelbox.data.VodEntity) -> Unit)? = null,
    /** ZarkTV: abre um canal encontrado pelo nome (null = não mostra canais). */
    onCanal: ((io.github.axon07.channelbox.data.ChannelEntity) -> Unit)? = null,
) {
    // ZarkTV: busca única — canais, filmes e séries junto com a programação.
    val q2 = state.query.trim()
    val filmes by remember(q2) {
        if (q2.length < 2 || onOpenVod == null) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.vodItems(io.github.axon07.channelbox.data.VodKind.MOVIE, null, q2)
    }.collectAsState(initial = emptyList())
    val series by remember(q2) {
        if (q2.length < 2 || onOpenVod == null) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.vodItems(io.github.axon07.channelbox.data.VodKind.SERIES, null, q2)
    }.collectAsState(initial = emptyList())
    val todosCanais by vm.allChannels.collectAsState()
    val canais = remember(q2, todosCanais) {
        if (q2.length < 2 || onCanal == null) emptyList() else todosCanais.filter { it.name.contains(q2, ignoreCase = true) }.take(40)
    }
    LaunchedEffect(state.query) {
        val q = state.query.trim()
        if (q.length < 2) {
            state.results = null
            return@LaunchedEffect
        }
        delay(300) // wait for typing to pause
        state.results = vm.searchGuide(q)
    }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { if (state.results.isNullOrEmpty()) field.requestFocus() else firstResult.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.Search, tint = Palette.Text, size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Text("Buscar", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        Spacer(Modifier.height(16.dp))
        // Busca por voz: o botão do microfone usa o reconhecimento de voz do aparelho (Android TV, Fire Stick, celular).
        val ctxVoz = androidx.compose.ui.platform.LocalContext.current
        val intentVoz = remember {
            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Fale o nome do filme, série, canal ou programa")
        }
        val temVoz = remember { runCatching { ctxVoz.packageManager.queryIntentActivities(intentVoz, 0).isNotEmpty() }.getOrDefault(false) }
        val ouvir = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { state.query = it }
        }
        Row(Modifier.widthIn(max = 820.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                TvTextField("Filme, série, canal ou programa", state.query, { state.query = it }, fieldModifier = Modifier.focusRequester(field))
            }
            if (temVoz) {
                Spacer(Modifier.width(12.dp))
                PillButton("Falar", primary = true) { runCatching { ouvir.launch(intentVoz) } }
            }
        }
        Spacer(Modifier.height(16.dp))
        val results = state.results
        val temOutros = canais.isNotEmpty() || filmes.isNotEmpty() || series.isNotEmpty()
        if (state.query.trim().length < 2) {
            Hint("Digite pelo menos 2 letras. Procura canais, filmes, séries e a programação.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                if (canais.isNotEmpty()) {
                    item { Secao("Canais (${canais.size})") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                            items(canais.size) { i -> CanalAchado(canais[i]) { onCanal?.invoke(canais[i]) } }
                        }
                    }
                }
                if (filmes.isNotEmpty()) {
                    item { Secao("Filmes (${filmes.size})") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                            items(minOf(filmes.size, 40)) { i -> PosterTile(filmes[i], 110.dp, 160.dp) { onOpenVod?.invoke(filmes[i]) } }
                        }
                    }
                }
                if (series.isNotEmpty()) {
                    item { Secao("Séries (${series.size})") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                            items(minOf(series.size, 40)) { i -> PosterTile(series[i], 110.dp, 160.dp) { onOpenVod?.invoke(series[i]) } }
                        }
                    }
                }
                when {
                    results == null -> item { Hint("Buscando na programação…") }
                    results.isEmpty() -> item { Hint(if (temOutros) "Nada na programação com “${state.query.trim()}”." else "Nada encontrado com “${state.query.trim()}”.") }
                    else -> {
                        item { Secao("Na programação (${results.size})") }
                        items(results.size) { i ->
                            val hit = results[i]
                            ResultRow(
                                hit = hit,
                                now = now,
                                recording = TvViewModel.recordingKey(hit.channel.id, hit.program.start) in recordingKeys,
                                canCatchUp = vm.canCatchUp(hit.channel, hit.program),
                                timeFormat = timeFormat,
                                modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                                onClick = {
                                    state.focusIndex = i
                                    onPick(hit)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Secao(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.padding(top = 10.dp))
}

/** Canal encontrado pelo nome: logo + nome, OK abre o canal. */
@Composable
private fun CanalAchado(ch: io.github.axon07.channelbox.data.ChannelEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(170.dp).tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0FFFFFFF),
            contentColor = Palette.Text,
            focusedContainerColor = Color(0x29FFFFFF),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = RoundedCornerShape(8.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ChannelLogo(ch.logoUrl, ch.name, Modifier.size(width = 96.dp, height = 56.dp))
            Spacer(Modifier.height(6.dp))
            Text(ch.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = Palette.TextDim)
}

@Composable
private fun ResultRow(
    hit: TvViewModel.GuideHit,
    now: Long,
    recording: Boolean,
    canCatchUp: Boolean,
    timeFormat: (Long) -> String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = hit.program
    val onNow = p.start <= now && now < p.stop
    val past = p.stop <= now
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0FFFFFFF),
            contentColor = Palette.Text,
            focusedContainerColor = Color(0x29FFFFFF),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = RoundedCornerShape(8.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(hit.channel.logoUrl, hit.channel.name, Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${hit.channel.number}  ${hit.channel.name}  ·  ${dayLabel(p.start, now)} ${timeFormat(p.start)} – ${timeFormat(p.stop)}",
                    fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                p.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            val (label, color) = when {
                onNow -> "Agora" to Palette.Accent
                past && canCatchUp -> "Já passou" to Color(0x33FFFFFF)
                past -> "Terminou" to Color(0x1AFFFFFF)
                recording -> "● Gravando" to Palette.Recording.copy(alpha = 0.8f)
                else -> "Mais tarde" to Color(0x33FFFFFF)
            }
            Text(
                label, fontSize = 12.sp, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** "Today", "Tomorrow", "Yesterday" or the weekday. */
private fun dayLabel(time: Long, now: Long): String {
    fun dayOf(t: Long) = (t + java.util.TimeZone.getDefault().getOffset(t)) / 86_400_000L
    return when ((dayOf(time) - dayOf(now)).toInt()) {
        0 -> "Hoje"
        1 -> "Amanhã"
        -1 -> "Ontem"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale("pt", "BR")).format(java.util.Date(time))
    }
}
