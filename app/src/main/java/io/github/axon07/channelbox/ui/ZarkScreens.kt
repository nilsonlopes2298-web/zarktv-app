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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.collectAsState
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
                            epgUrl = ZarkConfig.epgDoServidor(context, server.name),
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

    // Proporção por aparelho: TV/tablet deitado = marca à esquerda e cartão compacto à direita;
    // celular em pé = marca em cima e cartão na largura da tela.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val deitado = cfg.screenWidthDp > cfg.screenHeightDp
    val baixo = cfg.screenHeightDp < 600
    val formulario: @Composable () -> Unit = {
        Column(
            Modifier
                .background(Color(0xF0121212), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                .padding(horizontal = 22.dp, vertical = if (baixo) 16.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (baixo) 8.dp else 12.dp),
        ) {
            Text("Entrar", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Use o usuário e a senha que você recebeu.", color = Palette.TextDim, fontSize = 12.sp)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caixinha("Lembrar de mim", lembrar) { lembrar = !lembrar }
                Spacer(Modifier.weight(1f))
                Text(
                    if (manual) "Servidor automático" else "Escolher servidor",
                    color = Palette.TextDim, fontSize = 12.sp,
                    modifier = Modifier.tap(onClick = { manual = !manual }),
                )
            }
            if (manual) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(servers.size) { i ->
                        PillButton(servers[i].name, primary = i == sel) { selName = servers[i].name }
                    }
                }
            }

            erro?.let { Text(it, color = Palette.Error, fontSize = 13.sp) }
            if (submitted && refreshState is RefreshState.Failed) {
                Text(refreshState.message, color = Palette.Error, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }

            BotaoCheio(if (testing) "Conferindo…" else "Entrar", Palette.Accent, enabled = canEnter, altura = if (baixo) 44 else 48, onClick = entrar)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    BotaoContorno("Ver planos", cor = Color(0x44FFFFFF), altura = if (baixo) 40 else 44) {
                        runCatching { uri.openUri(ZarkConfig.checkoutUrl(context, user.trim(), servers.getOrNull(sel)?.name.orEmpty())) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    BotaoContorno("Suporte", cor = Color(0x44FFFFFF), altura = if (baixo) 40 else 44) {
                        runCatching { uri.openUri(ZarkConfig.WHATSAPP) }
                    }
                }
            }
            if (!manual) Text("O servidor é escolhido sozinho.", color = Color(0x66FFFFFF), fontSize = 11.sp)
            Text(
                "Opções avançadas",
                color = Color(0x55FFFFFF), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().tap(onClick = onAdvanced),
            )
        }
    }
    val versao = io.github.axon07.channelbox.BuildConfig.VERSION_NAME
    // Capas passando atrás (filmes, séries e banners do painel) para a entrada ficar bonita.
    val capasVod by remember { vm.vodTop(io.github.axon07.channelbox.data.VodKind.MOVIE, null, 24) }.collectAsState(initial = emptyList())
    val capas = remember(capasVod) {
        val painel = io.github.axon07.channelbox.data.ZarkPainel.extras(context)
        (painel.banners.map { it.imagem } + listOfNotNull(painel.fundo) + capasVod.mapNotNull { it.poster }).distinct().take(30)
    }

    ZarkFundo {
        ParedeDeCapas(capas)
        if (deitado) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 32.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Zark", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                        Text("TV", color = Palette.Accent, fontSize = 64.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Canais ao vivo, esportes, filmes e séries\nno celular, na TV Box e na Smart TV.", color = Color(0xE6FFFFFF), fontSize = 18.sp, lineHeight = 25.sp)
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.width(48.dp).height(3.dp).background(Palette.Accent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(18.dp))
                    Text("Versão $versao", color = Color(0x66FFFFFF), fontSize = 12.sp)
                }
                Box(Modifier.width(340.dp).verticalScroll(rememberScrollState())) { formulario() }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZarkMarca(tamanho = 42)
                Spacer(Modifier.height(6.dp))
                Text("Canais, esportes, filmes e séries", color = Color(0xCCFFFFFF), fontSize = 15.sp)
                Spacer(Modifier.height(24.dp))
                Box(Modifier.widthIn(max = 400.dp).fillMaxWidth()) { formulario() }
                Spacer(Modifier.height(16.dp))
                Text("Versão $versao", color = Color(0x55FFFFFF), fontSize = 11.sp)
            }
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
fun BotaoCheio(label: String, cor: Color, enabled: Boolean = true, altura: Int = 52, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(altura.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) cor else cor.copy(alpha = 0.4f),
            focusedContainerColor = if (enabled) cor else cor.copy(alpha = 0.4f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.02f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun BotaoContorno(label: String, cor: Color, texto: Color = Color.White, fundo: Color = Color(0x14FFFFFF), altura: Int = 50, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(altura.dp).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.5.dp, if (focused) Color.White else cor, RoundedCornerShape(12.dp))
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = fundo, focusedContainerColor = fundo),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.02f)),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, color = texto, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Spacer(Modifier.height(28.dp))
                BarrinhaCarregando(Modifier.width(240.dp))
                Spacer(Modifier.height(12.dp))
                Text("Baixando os canais, filmes e séries do dia…", color = Palette.TextDim, fontSize = 13.sp)
                Text("Isso acontece uma vez por dia.", color = Color(0x66FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}


/** Barrinha fina com um trecho vermelho passando (leve até em TV Box fraca). */
@Composable
fun BarrinhaCarregando(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "barra")
    val x by t.animateFloat(
        -0.35f, 1f,
        infiniteRepeatable(tween(if (ZarkLeve.ativo) 1800 else 1200, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    Canvas(modifier.height(3.dp)) {
        drawRect(Color(0x33FFFFFF))
        val w = size.width * 0.35f
        val ini = (size.width * x).coerceAtLeast(0f)
        val fim = (size.width * x + w).coerceAtMost(size.width)
        if (fim > ini) drawRect(Palette.Accent, topLeft = androidx.compose.ui.geometry.Offset(ini, 0f), size = androidx.compose.ui.geometry.Size(fim - ini, size.height))
    }
}


/**
 * Parede de capas atrás da entrada: três faixas de capas andando devagar, bem escurecidas.
 * Em TV Box fraca fica parada (sem animação).
 */
@Composable
fun ParedeDeCapas(capas: List<String>) {
    if (capas.isEmpty()) return
    val t = rememberInfiniteTransition(label = "capas")
    val desloc by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "d",
    )
    val mover = if (ZarkLeve.ativo) 0f else desloc
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val altura = maxHeight / 3
        val largura = altura * 0.68f
        val porLinha = (maxWidth / largura).toInt() + 3
        Column(Modifier.fillMaxSize()) {
            repeat(3) { linha ->
                val sentido = if (linha % 2 == 0) -1f else 1f
                Row(
                    Modifier.height(altura).graphicsLayer { translationX = sentido * mover * largura.toPx() * 2 - largura.toPx() },
                ) {
                    repeat(porLinha) { i ->
                        val url = capas[(linha * 7 + i) % capas.size]
                        coil.compose.AsyncImage(
                            url, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.width(largura).fillMaxSize().padding(3.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }
        // Escurece para o texto e o cartão ficarem legíveis.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE60A0A0A), Color(0xB30A0A0A), Color(0xD90A0A0A)))))
    }
}
