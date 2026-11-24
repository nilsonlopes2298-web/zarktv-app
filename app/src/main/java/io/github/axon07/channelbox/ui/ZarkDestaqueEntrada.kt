package io.github.axon07.channelbox.ui

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.ZarkJogos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Destaque do momento" da tela de entrada (estilo grandes streamings): filme novo, série em alta
 * e o jogo importante do dia, trocando devagar. Os filmes/séries vêm do catálogo do cliente,
 * guardados da última vez que ele entrou; o jogo vem da agenda de jogos do dia (não precisa de login).
 * Sem nada guardado (primeira instalação), mostra a parede de capas de sempre.
 */
data class SlideEntrada(
    val rotulo: String,
    val titulo: String,
    val meta: String = "",
    val imagem: String? = null,
    val jogo: ZarkJogos.Jogo? = null,
)

object DestaquesEntrada {
    private const val CHAVE = "destaques_entrada_v1"

    fun ler(ctx: Context): List<SlideEntrada> = runCatching {
        val txt = ctx.getSharedPreferences("zarktv", Context.MODE_PRIVATE).getString(CHAVE, null) ?: return emptyList()
        (Json.parseToJsonElement(txt) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content
            SlideEntrada(s("r") ?: return@mapNotNull null, s("t") ?: return@mapNotNull null, s("m").orEmpty(), s("i"))
        }
    }.getOrDefault(emptyList())

    private fun salvar(ctx: Context, lista: List<SlideEntrada>) {
        val json = buildJsonArray {
            lista.forEach { add(buildJsonObject { put("r", it.rotulo); put("t", it.titulo); put("m", it.meta); it.imagem?.let { i -> put("i", i) } }) }
        }
        ctx.getSharedPreferences("zarktv", Context.MODE_PRIVATE).edit().putString(CHAVE, json.toString()).apply()
    }

    /** Chamado com o catálogo já carregado: guarda 1 ou 2 "em alta" e 1 novidade, com a imagem larga. */
    suspend fun atualizar(ctx: Context, vm: TvViewModel) {
        val lista = vm.vodDestaquesWeb().first { it.isNotEmpty() }
        val alta = lista.filter { it.second.contains("ALTA", true) }.take(2)
        val novo = lista.filter { it.second.contains("NOVO", true) }.take(1)
        val escolhidos = (alta + novo).ifEmpty { lista.take(3) }
        val slides = escolhidos.map { (item, rotulo) ->
            val ficha = runCatching { vm.zarkFicha(item) }.getOrNull()
            val tipo = if (item.kind == VodKind.SERIES) "Série" else "Filme"
            val meta = listOfNotNull(
                ficha?.data?.take(4)?.takeIf { it.all(Char::isDigit) },
                tipo,
                ficha?.genero?.split(',', '/')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() },
                ficha?.duracao?.takeIf { it.isNotBlank() && item.kind != VodKind.SERIES },
            ).joinToString(" · ")
            SlideEntrada(
                rotulo = if (rotulo.contains("ALTA", true)) "Em alta" else "Novo no catálogo",
                titulo = item.title,
                meta = meta,
                imagem = ficha?.fundo ?: item.poster,
            )
        }
        if (slides.isNotEmpty()) salvar(ctx, slides)
    }
}

/** Jogo ao vivo agora; se não tiver, o próximo do dia. */
private fun jogoDoMomento(jogos: List<ZarkJogos.Jogo>): ZarkJogos.Jogo? =
    jogos.firstOrNull { it.estado == "in" }
        ?: jogos.filter { it.estado == "pre" && it.inicio > System.currentTimeMillis() }.minByOrNull { it.inicio }

@Composable
fun DestaqueEntrada(vm: TvViewModel, tv: Boolean, modifier: Modifier = Modifier, rodapeTv: String? = null) {
    val ctx = LocalContext.current
    val guardados = remember { DestaquesEntrada.ler(ctx) }
    var jogo by remember { mutableStateOf<ZarkJogos.Jogo?>(null) }
    LaunchedEffect(Unit) { jogo = runCatching { jogoDoMomento(ZarkJogos.hoje(vm.zarkHttp)) }.getOrNull() }
    val slides = remember(guardados, jogo) {
        val j = jogo?.let { SlideEntrada(if (it.estado == "in") "Ao vivo" else "Hoje às " + horaBr(it.inicio), it.liga, jogo = it) }
        // Ordem: em alta, jogo, novidade, outro em alta…
        if (j == null) guardados else (guardados.take(1) + j + guardados.drop(1))
    }
    if (slides.isEmpty()) {
        Box(modifier) { ParedeCapas() }
        return
    }
    val parado = remember { ZarkAnimacao.leve || ZarkAnimacao.semMovimento(ctx) }
    var atual by remember { mutableIntStateOf(0) }
    if (!parado && slides.size > 1) LaunchedEffect(slides.size) {
        while (true) { delay(8_000); atual = (atual + 1) % slides.size }
    }
    val zoomAnim = rememberInfiniteTransition(label = "kenburns")
    val zoom by zoomAnim.animateFloat(1.02f, 1.12f, infiniteRepeatable(tween(9_600, easing = LinearEasing), RepeatMode.Reverse), label = "z")

    Box(modifier.background(ZarkAnimacao.fundo)) {
        Crossfade(slides[atual.coerceIn(0, slides.lastIndex)], animationSpec = tween(1_400), label = "destaque") { s ->
            Box(Modifier.fillMaxSize()) {
                if (s.jogo == null) {
                    AsyncImage(
                        s.imagem, s.titulo, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().graphicsLayer { if (!parado) { scaleX = zoom; scaleY = zoom } },
                    )
                } else {
                    // Jogo: estádio à noite desenhado (escuro e vermelho) atrás dos escudos.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A0608), Color(0xFF120607), ZarkAnimacao.fundo))))
                    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x55E50914), Color.Transparent), radius = 900f)))
                }
                // Sombra para o texto e o login sempre ficarem legíveis.
                if (tv) {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE609090B), Color(0x6609090B), Color(0xCC09090B)))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to ZarkAnimacao.fundo)))
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C09090B), 0.3f to Color.Transparent, 0.7f to Color(0xB309090B), 1f to ZarkAnimacao.fundo)))
                }
                InfoDestaque(s, tv, rodapeTv)
            }
        }
        if (slides.size > 1) Row(
            Modifier.align(if (tv) Alignment.BottomStart else Alignment.TopEnd)
                .padding(start = if (tv) 64.dp else 0.dp, end = if (tv) 0.dp else 20.dp, top = if (tv) 0.dp else 28.dp, bottom = if (tv) 96.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            slides.indices.forEach { k ->
                val sel = k == atual
                Box(Modifier.size(width = if (sel) (if (tv) 32.dp else 20.dp) else 6.dp, height = 6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (sel) Color.White else Color(0x59FFFFFF)))
            }
        }
    }
}

@Composable
private fun InfoDestaque(s: SlideEntrada, tv: Boolean, rodapeTv: String?) {
    val aoVivo = s.jogo?.estado == "in"
    Box(Modifier.fillMaxSize()) {
        Column(
            if (tv) Modifier.align(Alignment.CenterStart).padding(start = 64.dp).widthIn(max = 520.dp)
            else Modifier.align(Alignment.TopStart).padding(start = 24.dp, end = 24.dp, top = 76.dp),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (aoVivo) ZarkAnimacao.corEfeito else Color(0x33FFFFFF))
                    .padding(horizontal = if (tv) 14.dp else 10.dp, vertical = if (tv) 6.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (aoVivo) { Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White)); Spacer(Modifier.width(6.dp)) }
                Text(s.rotulo, color = Color.White, fontSize = if (tv) 15.sp else 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(if (tv) 16.dp else 12.dp))
            val j = s.jogo
            if (j == null) {
                Text(s.titulo, color = Color.White, fontSize = if (tv) 44.sp else 28.sp, fontWeight = FontWeight.Bold, lineHeight = if (tv) 48.sp else 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (s.meta.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.meta, color = ZarkAnimacao.cinza, fontSize = if (tv) 18.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(j.liga, color = ZarkAnimacao.cinza, fontSize = if (tv) 18.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(if (tv) 16.dp else 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Escudo(j.casa, tv)
                    Text(
                        if (j.estado == "in" || j.estado == "post") "${j.casa.placar ?: 0}  x  ${j.fora.placar ?: 0}" else "x",
                        color = Color.White, fontSize = if (tv) 40.sp else 26.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = if (tv) 24.dp else 16.dp),
                    )
                    Escudo(j.fora, tv)
                }
                Spacer(Modifier.height(if (tv) 14.dp else 10.dp))
                Text("${j.casa.nome}  x  ${j.fora.nome}", color = Color.White, fontSize = if (tv) 20.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (aoVivo && j.detalhe.isNotBlank()) Text(j.detalhe, color = ZarkAnimacao.corBrilho, fontSize = if (tv) 15.sp else 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (tv && rodapeTv != null) {
                Spacer(Modifier.height(28.dp))
                Text(rodapeTv, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Escudo(t: ZarkJogos.Time, tv: Boolean) {
    val tam = if (tv) 72.dp else 52.dp
    Box(
        Modifier.size(tam).clip(CircleShape).background(Color(0xFF1C1C1F)).border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (t.logo != null) AsyncImage(t.logo, t.nome, contentScale = ContentScale.Fit, modifier = Modifier.size(tam * 0.66f))
        else Text(t.nome.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, color = Color.White, fontSize = if (tv) 20.sp else 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun horaBr(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(ms))
