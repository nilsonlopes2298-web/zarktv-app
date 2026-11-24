package io.github.axon07.channelbox.ui

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * PAINEL DE CONFIGURAÇÃO DA ABERTURA (só celular/tablet; a TV continua com a abertura antiga).
 * Mude os valores aqui e gere um APK novo. Nada disso depende de internet.
 */
object ZarkAnimacao {
    /** Liga ou desliga a abertura animada inteira (desligada = abertura antiga, simples). */
    var aberturaAtiva = true
    /** Duração total da abertura, em milissegundos (até 2,8 s). */
    var duracaoMs = 2800
    /** Velocidade: 1 = normal, 1.5 = mais rápida, 0.8 = mais lenta. */
    var velocidade = 1f
    /** Intensidade do reflexo que passa pelo símbolo (0 = sem reflexo, 1 = forte). */
    var intensidadeBrilho = 0.55f
    /** Cor principal dos efeitos (vermelho ZarkTV). */
    var corEfeito = Color(0xFFE50914)
    /** Cor do vermelho luminoso (contorno e raios). */
    var corBrilho = Color(0xFFFF3347)
    /** Quantidade de pontinhos de luz na abertura e no login. */
    var particulas = 22
    /** Pontinhos de luz também no fundo do login (padrão: só na abertura). */
    var particulasNoLogin = false
    /** Liga o efeito elétrico (raios vermelhos) no fundo do login. */
    var efeitoEletrico = true
    /** Nome mostrado na abertura: primeira parte branca, segunda parte vermelha. */
    var nomeParte1 = "Zark"
    var nomeParte2 = "Play"
    /** Frase de apresentação. */
    var frase = "Seu entretenimento começa aqui."
    /** Para depois: true = a TV/TV Box também abre com esta animação (hoje só o celular). */
    var aberturaNaTv = false
    /** A abertura já foi mostrada neste uso do app: girar a tela (tela cheia) não mostra de novo. */
    @Volatile var jaMostrou = false
    /** Força o modo de baixo desempenho (menos efeitos) em qualquer aparelho. */
    var modoLeveForcado = false

    /** Menos efeitos: aparelho fraco (detectado sozinho) ou forçado acima. */
    val leve: Boolean get() = modoLeveForcado || ZarkLeve.ativo

    /** O celular pediu para reduzir/remover animações (Opções do desenvolvedor ou Acessibilidade). */
    fun semMovimento(ctx: Context): Boolean = runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    val fundo = Color(0xFF09090B)
    val fundo2 = Color(0xFF141416)
    val cinza = Color(0xFFA1A1AA)
}

/** true na Android TV / TV Box (lá nada desta tela muda). */
fun ehTv(ctx: Context): Boolean = io.github.axon07.channelbox.system.Aparelho.ehTv(ctx.resources.configuration)

private val suave = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

/** Símbolo play arredondado com o "Z" dentro, no quadrado 0..120 (igual ao ícone do app). */
private fun caminhoPlay(esc: Float, dx: Float, dy: Float) = Path().apply {
    fun p(x: Float, y: Float) = Offset(dx + x * esc, dy + y * esc)
    val a = p(34f, 22f); moveTo(a.x, a.y)
    val c1 = p(28f, 19f); val b = p(28f, 26f); quadraticBezierTo(c1.x, c1.y, b.x, b.y)
    val d = p(28f, 94f); lineTo(d.x, d.y)
    val c2 = p(28f, 101f); val e = p(34f, 98f); quadraticBezierTo(c2.x, c2.y, e.x, e.y)
    val f = p(98f, 64f); lineTo(f.x, f.y)
    val c3 = p(104f, 60f); val g = p(98f, 56f); quadraticBezierTo(c3.x, c3.y, g.x, g.y)
    close()
}

private fun caminhoZ(esc: Float, dx: Float, dy: Float) = Path().apply {
    moveTo(dx + 44f * esc, dy + 44f * esc)
    lineTo(dx + 68f * esc, dy + 44f * esc)
    lineTo(dx + 44f * esc, dy + 76f * esc)
    lineTo(dx + 68f * esc, dy + 76f * esc)
}

/**
 * Abertura cinematográfica do celular: pontos de luz → contorno desenhado → preenchimento →
 * reflexo → nome → frase → sai com escala e transparência, mostrando a tela que já carregou por baixo
 * (login ou tela inicial). Não segura o carregamento do app: tudo carrega em paralelo.
 */
@Composable
fun ZarkAberturaAnimada(onFim: () -> Unit) {
    val ctx = LocalContext.current
    val semMov = remember { ZarkAnimacao.semMovimento(ctx) }
    val leve = ZarkAnimacao.leve
    val total = remember {
        val base = if (leve) minOf(ZarkAnimacao.duracaoMs, 2000) else ZarkAnimacao.duracaoMs
        (base / ZarkAnimacao.velocidade.coerceIn(0.5f, 3f)).toInt().coerceIn(600, 2800)
    }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (semMov) { t.snapTo(0.8f); delay(600); onFim(); return@LaunchedEffect }
        t.animateTo(1f, tween(total, easing = LinearEasing))
        onFim()
    }
    val pontos = remember {
        val n = if (leve) ZarkAnimacao.particulas / 3 else ZarkAnimacao.particulas
        List(n) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 1f + Random.nextFloat() * 2.5f, Random.nextFloat() * 0.18f) }
    }
    fun fase(a: Float, b: Float) = suave.transform(((t.value - a) / (b - a)).coerceIn(0f, 1f))

    val saida = fase(0.875f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { val z = 1f + 0.03f * fase(0.45f, 0.87f) + 0.08f * saida; alpha = 1f - saida; scaleX = z; scaleY = z }
            .background(ZarkAnimacao.fundo)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val abre = fase(0.40f, 0.62f)
            val apaga = 1f - fase(0.70f, 0.88f)
            if (!leve && abre > 0f && apaga > 0f) {
                val cx = size.width / 2f
                for (k in -3..3) {
                    val dist = kotlin.math.abs(k) / 3f
                    val x = cx + k * size.width * 0.07f * abre
                    val larg = size.width * (0.006f + 0.014f * (1f - dist))
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, ZarkAnimacao.corEfeito.copy(alpha = 0.38f * apaga * (1f - dist * 0.7f)), Color.Transparent)),
                        topLeft = Offset(x - larg / 2f, 0f), size = androidx.compose.ui.geometry.Size(larg, size.height),
                    )
                }
            }
            pontos.forEach { (x, y, r, atraso) ->
                val a = fase(atraso, atraso + 0.3f)
                val alfa = if (a < 1f) a * 0.8f else 0.8f - 0.6f * fase(atraso + 0.3f, 0.6f)
                drawCircle(if ((x * 10).toInt() % 3 == 0) ZarkAnimacao.corBrilho else Color.White, r * density / 2f, Offset(x * size.width, y * size.height), alpha = alfa)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val zoom = 0.9f + 0.1f * fase(0.07f, 0.57f)
            Canvas(Modifier.size(110.dp).graphicsLayer { scaleX = zoom; scaleY = zoom }) {
                val esc = size.minDimension / 120f
                val tri = caminhoPlay(esc, 0f, 0f)
                val z = caminhoZ(esc, 0f, 0f)
                val mTri = PathMeasure().apply { setPath(tri, false) }
                val mZ = PathMeasure().apply { setPath(z, false) }
                // 1) contorno sendo desenhado (traçado vetorial)
                val pTri = Path().also { mTri.getSegment(0f, mTri.length * fase(0.07f, 0.39f), it, true) }
                drawPath(pTri, ZarkAnimacao.corBrilho, style = Stroke(2.5f * esc, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // 2) preenchimento com a cor original
                val enche = fase(0.37f, 0.52f)
                if (enche > 0f) drawPath(tri, ZarkAnimacao.corEfeito, alpha = enche)
                // 3) "Z" branco
                val pZ = Path().also { mZ.getSegment(0f, mZ.length * fase(0.21f, 0.43f), it, true) }
                drawPath(pZ, Color.White, style = Stroke(7f * esc, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // 4) reflexo de luz percorrendo o símbolo
                val r = fase(0.48f, 0.68f)
                if (!leve && ZarkAnimacao.intensidadeBrilho > 0f && r > 0f && r < 1f) {
                    val x = -40f * esc + r * 200f * esc
                    clipPath(tri) {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = ZarkAnimacao.intensidadeBrilho), Color.Transparent),
                                start = Offset(x - 22f * esc, 0f), end = Offset(x + 22f * esc, 12f * esc),
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val nome = fase(0.55f, 0.71f)
            Row(Modifier.graphicsLayer { alpha = nome; translationY = (1f - nome) * 8.dp.toPx() }) {
                Text(ZarkAnimacao.nomeParte1, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (1f + 5f * (1f - nome)).sp)
                Text(ZarkAnimacao.nomeParte2, color = ZarkAnimacao.corEfeito, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (1f + 5f * (1f - nome)).sp)
            }
            Spacer(Modifier.height(8.dp))
            val frase = fase(0.64f, 0.79f)
            Text(ZarkAnimacao.frase, color = ZarkAnimacao.cinza, fontSize = 13.sp, modifier = Modifier.graphicsLayer { alpha = frase })
        }
    }
}

/**
 * Fundo do login no celular: preto com gradiente discreto e raios vermelhos que piscam.
 * Leve: redesenha ~8 vezes por segundo (não a 60 FPS). Em aparelho fraco, com animações
 * desligadas ou com o efeito desligado no painel acima, fica só o fundo parado.
 */
@Composable
fun FundoEletrico(sobreposto: Boolean = false) {
    val ctx = LocalContext.current
    val anima = remember { ZarkAnimacao.efeitoEletrico && !ZarkAnimacao.leve && !ZarkAnimacao.semMovimento(ctx) }
    var semente by remember { mutableLongStateOf(1L) }
    val ciclo = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    if (anima) LaunchedEffect(Unit) {
        // Só anima com o app aberto na frente: minimizado não gasta bateria.
        ciclo.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { delay(160); semente = Random.nextLong() }
        }
    }
    val pontos = remember {
        val n = if (ZarkAnimacao.leve || !ZarkAnimacao.particulasNoLogin) 0 else ZarkAnimacao.particulas / 2
        List(n) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 1f + Random.nextFloat() * 2f) }
    }
    Box(if (sobreposto) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(ZarkAnimacao.fundo)) {
        if (!sobreposto) FundoRiscosVermelhos()
        if (anima) Canvas(Modifier.fillMaxSize()) {
            val rnd = Random(semente)
            val w = size.width
            fun raio(y0: Float, larg: Float, cor: Color) {
                val p = Path().apply { moveTo(0f, y0) }
                var x = 0f
                while (x < w) {
                    x += w * (0.04f + rnd.nextFloat() * 0.06f)
                    p.lineTo(x, y0 + (rnd.nextFloat() - 0.5f) * size.height * 0.1f)
                }
                val alfa = 0.15f + rnd.nextFloat() * 0.3f
                drawPath(p, cor.copy(alpha = alfa), style = Stroke(larg))
            }
            raio(size.height * (0.24f + rnd.nextFloat() * 0.06f), 1.6f * density, ZarkAnimacao.corEfeito)
            raio(size.height * (0.32f + rnd.nextFloat() * 0.08f), 1f * density, ZarkAnimacao.corBrilho)
            pontos.forEach { (x, y, r) ->
                drawCircle(Color.White, r * density / 2f, Offset(x * w, y * size.height), alpha = 0.1f + rnd.nextFloat() * 0.3f)
            }
        }
    }
}
