package io.github.axon07.channelbox.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
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
            // Conta vencida: não entra, avisa e mostra como renovar.
            val venc: Long? = when (r) {
                is ZarkConfig.Login.Vencida -> r.expira ?: 0L
                is ZarkConfig.Login.Ok -> if (ZarkConfig.vencida(r)) (r.expira ?: 0L) else null
                else -> null
            }
            if (venc != null) {
                erro = "Sua conta venceu" + (if (venc > 0) " em " + ZarkConfig.dataBr(venc) else "") +
                    ". Renove para continuar assistindo: toque em Ver planos ou fale com o suporte."
                return@launch
            }
            when (r) {
                is ZarkConfig.Login.Vencida -> Unit
                is ZarkConfig.Login.Ok -> {
                    ZarkConfig.salvarVencimento(context, r.expira)
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

    // ZarkTV Mobile: entrada pequena e limpa, pensada para o celular em pé.
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        val t1 = Color(0xFFEDEDED); val t2 = Color(0xFFA1A1A6); val t3 = Color(0xFF85858B)
        val campo: @Composable (String, String, (String) -> Unit, Boolean, androidx.compose.ui.graphics.vector.ImageVector, (@Composable () -> Unit)?) -> Unit =
            { rotulo, valor, mudar, senha, icone, fim ->
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1F))
                        .border(1.dp, Color(0x14EDEDED), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(icone, tint = t3, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (valor.isEmpty()) Text(rotulo, color = t3, fontSize = 14.sp)
                        androidx.compose.foundation.text.BasicTextField(
                            valor, mudar, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = t1, fontSize = 14.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                            visualTransformation = if (senha) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = if (senha) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
                                autoCorrect = false,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    fim?.invoke()
                }
            }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0F0F10))) {
            val alturaTela = maxHeight
            ParedeCapas()
            // Tudo centralizado no meio da tela (rola se o teclado apertar).
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = alturaTela).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.Play, tint = Palette.Accent, size = 24.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("Zark", color = t1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("TV", color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text("Canais ao vivo, jogos, filmes e séries", color = t2, fontSize = 13.sp)
                Spacer(Modifier.height(32.dp))
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    campo("Usuário", user, { user = it; erro = null }, false, AppIcons.User, null)
                    campo("Senha", pass, { pass = it; erro = null }, !verSenha, AppIcons.Lock) {
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { verSenha = !verSenha }.padding(6.dp)) {
                            AppIcon(AppIcons.Eye, tint = if (verSenha) Palette.Accent else t3, size = 18.dp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { lembrar = !lembrar }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(if (lembrar) Palette.Accent else Color.Transparent)
                                    .border(1.5.dp, if (lembrar) Palette.Accent else t3, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center,
                            ) { if (lembrar) Text("✓", color = Color.White, fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            Text("Lembrar de mim", color = t2, fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (manual) "Servidor: ${servers.getOrNull(sel)?.name.orEmpty()}" else "Servidor: automático",
                            color = t2, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { manual = !manual }.padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                    if (manual) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(servers.size) { i ->
                            val s = i == sel
                            Text(
                                servers[i].name, color = if (s) Color.White else t1, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (s) Palette.Accent else Color(0xFF232325))
                                    .clickable { selName = servers[i].name }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                    erro?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
                    if (submitted && refreshState is RefreshState.Failed) Text(refreshState.message, color = Color(0xFFE5484D), fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (canEnter) Palette.Accent else Color(0xFF3A1A1C)).clickable(enabled = canEnter) { entrar() },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (testing) "Conferindo…" else "Entrar", color = if (canEnter) Color.White else t3, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("Ver planos", color = t1, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            runCatching { uri.openUri(ZarkConfig.checkoutUrl(context, user.trim(), servers.getOrNull(sel)?.name.orEmpty())) }
                        }.padding(10.dp))
                        Text("·", color = t3, fontSize = 14.sp)
                        Text("Suporte", color = t1, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            runCatching { uri.openUri(ZarkConfig.WHATSAPP) }
                        }.padding(10.dp))
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text("Versão ${io.github.axon07.channelbox.BuildConfig.VERSION_NAME}", color = Color(0x66EDEDED), fontSize = 11.sp)
                Text("Opções avançadas", color = Color(0x33EDEDED), fontSize = 10.sp, modifier = Modifier.tap(onClick = onAdvanced).padding(8.dp))
                Spacer(Modifier.height(16.dp))
            }
        }
        return
    }

    // Proporção por aparelho: TV/tablet deitado = marca à esquerda e cartão compacto à direita;
    // celular em pé = marca em cima e cartão na largura da tela.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val deitado = cfg.screenWidthDp > cfg.screenHeightDp
    val baixo = cfg.screenHeightDp < 600
    val formulario: @Composable () -> Unit = {
        Column(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xE61A0E10), Color(0xF20C0C0E))))
                .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xCCFF2D2D), Color(0x55FF2D2D))), RoundedCornerShape(22.dp))
                .padding(horizontal = 26.dp, vertical = if (baixo) 18.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (baixo) 10.dp else 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(if (baixo) 40.dp else 48.dp).background(Palette.Accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Entrar", color = Color.White, fontSize = if (baixo) 24.sp else 30.sp, fontWeight = FontWeight.Bold)
                    Text("Use o usuário e a senha que você recebeu.", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                }
            }
            // O foco do controle já começa no campo Usuário (contorno vermelho mostra onde está).
            TvTextField("Usuário", user, { user = it; erro = null }, placeholder = "Digite seu usuário", icone = AppIcons.User, raio = 12.dp, fieldModifier = Modifier.focusRequester(first))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    TvTextField("Senha", pass, { pass = it; erro = null }, password = !verSenha, placeholder = "Digite sua senha", icone = AppIcons.Lock, raio = 12.dp)
                }
                Spacer(Modifier.width(10.dp))
                BotaoIconeTexto(if (verSenha) "Ocultar" else "Mostrar", AppIcons.Eye, Modifier.height(46.dp)) { verSenha = !verSenha }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caixinha("Lembrar de mim", lembrar) { lembrar = !lembrar }
                Spacer(Modifier.weight(1f))
                // Botão curto, numa linha só (antes quebrava em duas e ficava desproporcional).
                BotaoIconeTexto(if (manual) "Automático" else "Servidor", AppIcons.Globe, Modifier.height(40.dp)) { manual = !manual }
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

            BotaoEntrar(if (testing) "Conferindo…" else "Entrar", canEnter, if (baixo) 48 else 54, entrar)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BotaoIconeTexto("Ver planos", AppIcons.Diamond, Modifier.weight(1f).height(if (baixo) 42.dp else 48.dp)) {
                    runCatching { uri.openUri(ZarkConfig.checkoutUrl(context, user.trim(), servers.getOrNull(sel)?.name.orEmpty())) }
                }
                BotaoIconeTexto("Suporte", AppIcons.Headset, Modifier.weight(1f).height(if (baixo) 42.dp else 48.dp)) {
                    runCatching { uri.openUri(ZarkConfig.WHATSAPP) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcons.Refresh, tint = Color(0x99FFFFFF), size = 14.dp)
                Text(
                    if (manual) "  Escolha o servidor acima." else "  O servidor é escolhido automaticamente.",
                    color = Color(0x99FFFFFF), fontSize = 12.sp,
                )
            }
            Text(
                "Opções avançadas",
                color = Color(0x40FFFFFF), fontSize = 10.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().tap(onClick = onAdvanced),
            )
        }
    }
    val versao = io.github.axon07.channelbox.BuildConfig.VERSION_NAME
    // Entrada só com o nome ZarkTV (o ícone fica só no atalho do aparelho).
    val logoApp: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { _ -> }

    Box(Modifier.fillMaxSize().background(Color(0xFF070607))) {
        FundoRiscosVermelhos()
        if (deitado) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 36.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        logoApp(if (baixo) 72.dp else 96.dp)
                        Text("Zark", color = Color.White, fontSize = if (baixo) 48.sp else 64.sp, fontWeight = FontWeight.Bold)
                        Text("TV", color = Palette.Accent, fontSize = if (baixo) 48.sp else 64.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(56.dp).height(4.dp).background(Palette.Accent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(16.dp))
                    Text("Canais ao vivo, esportes, filmes e séries\nno celular, na TV Box e na Smart TV.", color = Color(0xEBFFFFFF), fontSize = if (baixo) 16.sp else 20.sp, lineHeight = if (baixo) 22.sp else 28.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(AppIcons.Phone, tint = Color(0xCCFFFFFF), size = 26.dp)
                        Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(20.dp).background(Color(0x44FFFFFF)))
                        AppIcon(AppIcons.Box, tint = Color(0xCCFFFFFF), size = 26.dp)
                        Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(20.dp).background(Color(0x44FFFFFF)))
                        AppIcon(AppIcons.Tv, tint = Color(0xCCFFFFFF), size = 28.dp)
                    }
                    Spacer(Modifier.height(if (baixo) 18.dp else 36.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(2.dp).height(14.dp).background(Palette.Accent))
                        Text("  Versão $versao", color = Color(0x99FFFFFF), fontSize = 12.sp)
                    }
                }
                Box(Modifier.width(if (baixo) 360.dp else 420.dp).verticalScroll(rememberScrollState())) { formulario() }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    logoApp(60.dp)
                    Text("Zark", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("TV", color = Palette.Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text("Canais ao vivo, esportes, filmes e séries", color = Color(0xCCFFFFFF), fontSize = 14.sp)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth()) { formulario() }
                Spacer(Modifier.height(16.dp))
                Text("Versão $versao", color = Color(0x66FFFFFF), fontSize = 11.sp)
            }
        }
    }
}

/** Fundo da entrada: preto com faixas de luz vermelha curvas (sem imagem, leve). */
@Composable
fun FundoRiscosVermelhos() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(listOf(Color(0x55B00000), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.45f), radius = w * 0.55f))
        fun risco(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, larg: Float, alfa: Float) {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(x0, y0); quadraticBezierTo(cx, cy, x1, y1) }
            drawPath(p, Color(0xFFFF2020).copy(alpha = alfa * 0.25f), style = Stroke(larg * 6))
            drawPath(p, Color(0xFFFF3030).copy(alpha = alfa), style = Stroke(larg))
        }
        risco(-w * 0.05f, h * 0.30f, w * 0.20f, -h * 0.05f, w * 0.45f, -h * 0.02f, 3f, 0.55f)
        risco(w * 0.35f, h * 1.05f, w * 0.65f, h * 0.70f, w * 1.05f, h * 0.62f, 3f, 0.45f)
        risco(-w * 0.02f, h * 0.95f, w * 0.30f, h * 0.88f, w * 0.55f, h * 0.70f, 1.5f, 0.35f)
    }
}

/** Botão vermelho grande "▶ Entrar". */
@Composable
private fun BotaoEntrar(texto: String, ativo: Boolean, altura: Int, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(altura.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(if (ativo) listOf(Color(0xFFFF2A2A), Color(0xFFC40000)) else listOf(Color(0x66FF2A2A), Color(0x66C40000))))
            .border(if (f) 2.5.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.Play, tint = Color.White, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(texto, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Botão escuro com ícone (Mostrar, Ver planos, Suporte). */
@Composable
private fun BotaoIconeTexto(texto: String, icone: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (f) Color(0x40FFFFFF) else Color(0x1FFFFFFF))
            .border(if (f) 2.dp else 1.dp, if (f) Color.White else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icone, tint = Color.White, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(texto, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        // Celular: carregando simples — marca, o que está carregando e uma barrinha que vai e volta.
        val vai by giro.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "barra")
        Box(Modifier.fillMaxSize().background(Color(0xFF0F0F10)), contentAlignment = Alignment.Center) {
            ParedeCapas()
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.Play, tint = Palette.Accent, size = 24.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("Zark", color = Color(0xFFEDEDED), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("TV", color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(28.dp))
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.offset(x = (maxWidth - 80.dp) * vai).width(80.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    when (etapa) {
                        0 -> "Carregando canais" + (numero?.let { " ($it)" } ?: "") + "…"
                        1 -> "Carregando filmes e séries" + (numero?.let { " ($it)" } ?: "") + "…"
                        else -> "Carregando a programação…"
                    },
                    color = Color(0xFFA1A1A6), fontSize = 13.sp,
                )
            }
        }
        return
    }

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
fun BotaoCheio(label: String, cor: Color, enabled: Boolean = true, altura: Int = 52, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(altura.dp).onFocusChanged { focused = it.isFocused }
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
            // Abertura só com o nome ZarkTV (o ícone fica só no atalho do aparelho).
            ZarkMarca(46)
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


/**
 * ZarkTV Mobile: fundo animado da entrada — capas de filmes e séries (as últimas que o app viu)
 * deslizando devagar em fileiras, bem escurecidas. Sem capas guardadas, quadros com ícones.
 */
@Composable
fun ParedeCapas() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val capas = remember {
        ctx.getSharedPreferences("zarktv", android.content.Context.MODE_PRIVATE).getString("capas_parede", "").orEmpty()
            .split('\n').filter { it.startsWith("http") }
    }
    val anda = rememberInfiniteTransition(label = "parede")
    val desloca by anda.animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "d")
    val icones = listOf(AppIcons.Trofeu, AppIcons.Play, AppIcons.Tv, AppIcons.AoVivo)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val tile = 96.dp
        val passo = tile + 8.dp
        val porLinha = (maxWidth / passo).toInt() + 3
        val linhas = (maxHeight / (tile * 1.5f + 8.dp)).toInt() + 1
        Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(linhas) { linha ->
                val dir = if (linha % 2 == 0) -1f else 1f
                Row(
                    Modifier.offset(x = passo * ((desloca * 3f * dir) % 1f) - passo).wrapContentWidth(Alignment.Start, unbounded = true),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(porLinha) { k ->
                        val i = linha * porLinha + k
                        Box(
                            Modifier.width(tile).height(tile * 1.5f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C1F)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (capas.isNotEmpty()) coil.compose.AsyncImage(capas[i % capas.size], null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else AppIcon(icones[i % icones.size], tint = Color(0x33FFFFFF), size = 28.dp)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xE60F0F10), Color(0xCC0F0F10), Color(0xF20F0F10)))))
    }
}
