package io.github.axon07.channelbox.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axon07.channelbox.data.StreamSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * ZarkTV (como na UniTV): ao avançar/voltar o filme pelo controle, mostra a cena do ponto escolhido
 * acima da barra. O quadro é tirado do próprio arquivo do filme (sem baixar tudo).
 */
class PreviaAvanco {
    private val fila: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var leitor: MediaMetadataRetriever? = null
    private var urlAberta: String? = null
    /** Este arquivo não deu prévia (formato/servidor não aceita): não tenta de novo. */
    private var urlSemPrevia: String? = null
    private val cache = LinkedHashMap<Long, Bitmap>()

    suspend fun quadro(fonte: StreamSpec, posMs: Long): Bitmap? = withContext(fila) {
        if (fonte.url == urlSemPrevia) return@withContext null
        val chave = posMs / 10_000 // um quadro a cada 10 s basta
        cache[chave]?.let { return@withContext it }
        runCatching {
            if (urlAberta != fonte.url) {
                runCatching { leitor?.release() }
                cache.clear()
                leitor = MediaMetadataRetriever().apply { setDataSource(fonte.url, fonte.headers) }
                urlAberta = fonte.url
            }
            val l = leitor ?: return@runCatching null
            val us = chave * 10_000_000L
            val b = if (Build.VERSION.SDK_INT >= 27) l.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            else l.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 320, 180, true) }
            if (b != null) {
                cache[chave] = b
                if (cache.size > 40) cache.remove(cache.keys.first())
            }
            b
        }.getOrElse {
            android.util.Log.w("ZarkPlayer", "prévia do avanço indisponível: ${it.message}")
            urlSemPrevia = fonte.url
            runCatching { leitor?.release() }
            leitor = null; urlAberta = null
            null
        }
    }

    fun fechar() {
        runCatching { fila.executor.execute { runCatching { leitor?.release() }; leitor = null } }
        fila.close()
    }
}

/** Quadro da prévia com o horário embaixo. */
@Composable
internal fun PreviaQuadro(previa: PreviaAvanco, fonte: StreamSpec?, alvoMs: Long, modifier: Modifier = Modifier) {
    var imagem by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(alvoMs / 10_000, fonte?.url) {
        if (fonte == null) return@LaunchedEffect
        delay(150) // espera parar de apertar um instante
        previa.quadro(fonte, alvoMs)?.let { imagem = it }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(240.dp, 135.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC000000)).border(2.dp, Color.White, RoundedCornerShape(6.dp)),
        ) {
            imagem?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(240.dp, 135.dp)) }
        }
        Text(
            formatClock(alvoMs), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun rememberPreviaAvanco(): PreviaAvanco {
    val p = remember { PreviaAvanco() }
    DisposableEffect(Unit) { onDispose { p.fechar() } }
    return p
}
