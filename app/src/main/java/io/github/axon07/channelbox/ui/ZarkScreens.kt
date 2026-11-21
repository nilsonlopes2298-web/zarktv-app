package io.github.axon07.channelbox.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.axon07.channelbox.data.Playlist
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import io.github.axon07.channelbox.data.ZarkConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fundo escuro com brilho vermelho, igual ao webplay. */
@Composable
fun ZarkFundo(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .background(Brush.radialGradient(listOf(Color(0x66E50914), Color.Transparent), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** "▶ ZarkTV" grande e inclinado, como no webplay. */
@Composable
fun ZarkMarca(tamanho: Int = 34) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Text("▷", color = Palette.Accent, fontSize = (tamanho + 6).sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        Text("Zark", color = Color.White, fontSize = tamanho.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        Text("TV", color = Palette.Accent, fontSize = tamanho.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
    }
}

/**
 * Entrada do ZarkTV igual ao webplay: usuário, senha (com olho), "Lembrar de mim",
 * servidor automático (testa todos os servidores do painel) ou escolhido à mão, Entrar e Ver planos.
 * Depois de entrar mostra "Carregando conteúdo".
 */
@Composable
fun ZarkLoginScreen(
    vm: TvViewModel,
    refreshState: RefreshState,
    onAdvanced: () -> Unit,
    onDone: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lembrado = remember { ZarkConfig.lembrado(context) }
    var servers by remember { mutableStateOf(ZarkConfig.cached(context)) }
    LaunchedEffect(Unit) { servers = ZarkConfig.servers(context, vm.zarkHttp) }
    var user by rememberSaveable { mutableStateOf(lembrado.second) }
    var pass by rememberSaveable { mutableStateOf(lembrado.third) }
    var lembrar by rememberSaveable { mutableStateOf(lembrado.first) }
    var verSenha by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var selName by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var sawRunning by remember { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshState, submitted) {
        if (!submitted) return@LaunchedEffect
        if (refreshState is RefreshState.Running && !refreshState.background) sawRunning = true
        val pronto = refreshState is RefreshState.Done || (refreshState as? RefreshState.Running)?.background == true
        if (sawRunning && pronto) onDone()
    }

    // Já entrou: tela de carregamento até os canais, filmes e séries chegarem.
    if (submitted && refreshState !is RefreshState.Failed) {
        ZarkCarregando(refreshState)
        return
    }

    val sel = servers.indexOfFirst { it.name == selName }.coerceAtLeast(0)
    val canEnter = !testing && user.isNotBlank() && pass.isNotBlank() && servers.isNotEmpty()
    val entrar: () -> Unit = entrar@{
        if (!canEnter) return@entrar
        testing = true
        erro = null
        scope.launch {
            val (server, r) = if (manual) servers[sel] to ZarkConfig.login(vm.zarkHttp, servers[sel], user.trim(), pass.trim())
            else ZarkConfig.loginAuto(vm.zarkHttp, servers, user.trim(), pass.trim())
            testing = false
            when (r) {
                is ZarkConfig.Login.Ok -> {
                    ZarkConfig.lembrar(context, if (lembrar) user.trim() else null, if (lembrar) pass.trim() else null)
                    vm.zarkConta.value = r
                    submitted = true
                    sawRunning = false
                    vm.savePlaylist(
                        Playlist(
                            id = Playlist.newId(),
                            name = server.name,
                            type = SourceType.XTREAM,
                            xtServer = r.dns,
                            xtUser = user.trim(),
                            xtPass = pass.trim(),
                        )
                    )
                }
                ZarkConfig.Login.SenhaErrada -> erro =
                    if (manual) "Usuário ou senha incorretos no servidor ${server.name}." else "Usuário ou senha incorretos. Confira e tente de novo."
                ZarkConfig.Login.SemResposta -> erro =
                    if (manual) "O servidor ${server.name} não respondeu. Tente outro servidor." else "Nenhum servidor respondeu. Verifique sua internet."
            }
        }
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }

    ZarkFundo {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .padding(20.dp)
                .background(Color(0xE6141414), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                .padding(horizontal = 26.dp, vertical = 26.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZarkMarca()
            Text("Login na sua conta", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Box(Modifier.focusRequester(first)) {
                TvTextField("Usuário", user, { user = it; erro = null })
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    TvTextField("Senha", pass, { pass = it; erro = null }, password = !verSenha)
                }
                Spacer(Modifier.width(8.dp))
                PillButton(if (verSenha) "Ocultar" else "Mostrar") { verSenha = !verSenha }
            }

            Caixinha("Lembrar de mim", lembrar) { lembrar = !lembrar }

            BotaoContorno(
                if (manual) "Servidor: ${servers.getOrNull(sel)?.name ?: "—"} (toque para automático)" else "Escolher o servidor manualmente",
                cor = Color(0x33FFFFFF),
            ) { manual = !manual }
            if (manual) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(servers.size) { i ->
                        PillButton(servers[i].name, primary = i == sel) { selName = servers[i].name }
                    }
                }
            } else {
                Text("O servidor é escolhido automaticamente.", color = Palette.TextDim, fontSize = 12.sp)
            }

            erro?.let { Text(it, color = Palette.Error, fontSize = 13.sp) }
            if (submitted && refreshState is RefreshState.Failed) {
                Text(refreshState.message, color = Palette.Error, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }

            BotaoCheio(if (testing) "Conferindo…" else "Entrar", Palette.Accent, enabled = canEnter, onClick = entrar)
            BotaoContorno("Ver planos", cor = Color(0xFFD4A017), texto = Color(0xFFFFD34D), fundo = Color(0x33D4A017)) {
                runCatching { uri.openUri(ZarkConfig.checkoutUrl(context, user.trim(), servers.getOrNull(sel)?.name.orEmpty())) }
            }
            Text(
                "Opções avançadas",
                color = Color(0x66FFFFFF), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().tap(onClick = onAdvanced),
            )
        }
    }
}

/** Tela "Carregando conteúdo": Canais, Filmes e Séries, Guia — como no app antigo, com a marca ZarkTV. */
@Composable
fun ZarkCarregando(refreshState: RefreshState) {
    // ZarkTV: o cliente vê só a abertura (logo + versão); o conteúdo carrega por trás.
    ZarkAbertura(io.github.axon07.channelbox.BuildConfig.VERSION_NAME, carregando = true)
}

@Composable
private fun ZarkCarregandoEtapas(refreshState: RefreshState) {
    val msg = (refreshState as? RefreshState.Running)?.message.orEmpty()
    val etapa = when {
        refreshState is RefreshState.Done -> 3
        (refreshState as? RefreshState.Running)?.background == true -> 2
        msg.contains("movies", ignoreCase = true) || msg.contains("filmes", ignoreCase = true) -> 1
        else -> 0
    }
    val numero = Regex("""(\d+)\s*$""").find(msg)?.groupValues?.get(1)
    val giro = rememberInfiniteTransition(label = "giro")
    val angulo by giro.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "a")

    ZarkFundo {
        Column(
            Modifier.widthIn(max = 760.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZarkMarca(30)
            Spacer(Modifier.height(22.dp))
            Text("Atualizando o conteúdo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("CANAIS", "FILMES E SÉRIES", "GUIA").forEachIndexed { i, nome ->
                    Column(Modifier.weight(1f)) {
                        Box(
                            Modifier.fillMaxWidth().background(Color(0xFF1C1C1C), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(nome, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Box(
                            Modifier.fillMaxWidth().background(
                                when {
                                    i < etapa -> Color(0xFF1E7D32)
                                    i == etapa -> Palette.Accent
                                    else -> Color(0xFF2A2A2A)
                                },
                                RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                            ).padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when {
                                    i < etapa -> "Concluído ✓"
                                    i == etapa -> "Atualizando…"
                                    else -> "Aguardando…"
                                },
                                color = Color.White, fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Canvas(Modifier.size(46.dp)) {
                drawArc(Palette.Accent, angulo, 270f, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                when (etapa) {
                    0 -> "Carregando canais" + (numero?.let { " ($it)" } ?: "") + "… aguarde"
                    1 -> "Carregando filmes e séries" + (numero?.let { " ($it)" } ?: "") + "… aguarde"
                    else -> "Carregando o guia de programação… aguarde"
                },
                color = Color.White, fontSize = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text("Na primeira vez pode demorar um pouquinho. Depois atualiza sozinho uma vez por dia.", color = Palette.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Caixinha(label: String, marcado: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x22FFFFFF)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).background(if (marcado) Palette.Accent else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(2.dp, if (marcado) Palette.Accent else Color(0x88FFFFFF), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) { if (marcado) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun BotaoCheio(label: String, cor: Color, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) cor else cor.copy(alpha = 0.4f),
            focusedContainerColor = if (enabled) cor else cor.copy(alpha = 0.4f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BotaoContorno(label: String, cor: Color, texto: Color = Color.White, fundo: Color = Color(0x14FFFFFF), onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.5.dp, if (focused) Color.White else cor, RoundedCornerShape(12.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = fundo, focusedContainerColor = fundo),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, color = texto, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Abertura do app: logo e versão no centro, como na UniTV. */
@Composable
fun ZarkAbertura(versao: String, carregando: Boolean = false) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .background(Brush.radialGradient(listOf(Color(0x99E50914), Color(0x22E50914), Color.Transparent), radius = 1100f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(io.github.axon07.channelbox.R.drawable.ic_launcher_foreground),
                contentDescription = "ZarkTV",
                modifier = Modifier.size(110.dp),
            )
            Spacer(Modifier.height(18.dp))
            ZarkMarca(30)
            Spacer(Modifier.height(8.dp))
            Text("V $versao", color = Palette.TextDim, fontSize = 14.sp)
            if (carregando) {
                Spacer(Modifier.height(26.dp))
                val giro = rememberInfiniteTransition(label = "abertura")
                val ang by giro.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "g")
                Canvas(Modifier.size(34.dp)) {
                    drawArc(Palette.Accent, ang, 270f, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}
