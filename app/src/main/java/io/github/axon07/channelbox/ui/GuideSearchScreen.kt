package io.github.axon07.channelbox.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
) {
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
            Text("Buscar na programação", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.widthIn(max = 640.dp)) {
            TvTextField("Nome do programa ou do canal", state.query, { state.query = it }, fieldModifier = Modifier.focusRequester(field))
        }
        Spacer(Modifier.height(16.dp))
        val results = state.results
        when {
            state.query.trim().length < 2 -> Hint("Digite pelo menos 2 letras. Mostra o que passa agora, mais tarde e antes.")
            results == null -> Hint("Buscando…")
            results.isEmpty() -> Hint("Nada na programação com “${state.query.trim()}”")
            else -> {
                Text("${results.size} encontrado(s)", fontSize = 13.sp, color = Palette.TextDim)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
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
                onNow -> "On now" to Palette.Accent
                past && canCatchUp -> "Catch-up" to Color(0x33FFFFFF)
                past -> "Finished" to Color(0x1AFFFFFF)
                recording -> "● Recording" to Palette.Recording.copy(alpha = 0.8f)
                else -> "Later" to Color(0x33FFFFFF)
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
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(time))
    }
}
