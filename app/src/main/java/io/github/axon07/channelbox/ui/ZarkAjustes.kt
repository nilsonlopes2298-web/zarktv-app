package io.github.axon07.channelbox.ui

import androidx.compose.runtime.collectAsState

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.ZarkPrefs
import kotlinx.coroutines.delay

/** Muda quando a legenda é ajustada, para o player redesenhar as legendas. */
object AjustesEstado {
    val legenda = mutableIntStateOf(0)
}

private enum class Secao(val titulo: String) {
    TEMA("Tela inicial"),
    REPRODUCAO("Reprodução"),
    PROPORCAO("Tela e tamanho"),
    PARENTAL("Controle parental"),
    CACHE("Limpar cache"),
    VERSAO("Atualizar versão"),
    DIAGNOSTICO("Diagnóstico"),
    SOBRE("Sobre o ZarkTV"),
    AVANCADO("Ajustes avançados"),
}

/**
 * Configurações no estilo da UniTV: menu à esquerda e as opções em botões à direita.
 * A opção escolhida fica com um triângulo dourado no canto; a selecionada pelo controle ganha borda dourada.
 */
@Composable
fun ZarkAjustes(
    vm: TvViewModel,
    escala: Int,
    versao: String,
    onParental: () -> Unit,
    onAvancado: () -> Unit,
    onAtualizar: () -> Unit,
    onSuporte: () -> Unit,
) {
    val ctx = LocalContext.current
    var secao by remember { mutableStateOf(if (ZarkPlay.ativo) Secao.TEMA else Secao.REPRODUCAO) }
    val primeiro = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { primeiro.requestFocus() } }

    val conteudo: @Composable (Secao) -> Unit = { secao -> ConteudoAjuste(secao, vm, escala, versao, onAtualizar, onSuporte) }
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        AjustesCelular(onParental, onAvancado, conteudo)
        return
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF2A0E0E), Color(0xFF140A0C), Color(0xFF0B0B10)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp)) {
            Text("Configurações", color = Color(0xCCFFFFFF), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxSize()) {
                // ---- Menu ----
                Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Secao.entries.filter { it != Secao.TEMA || ZarkPlay.ativo }.forEachIndexed { i, s ->
                        ItemMenu(
                            s.titulo, s == secao,
                            if (i == 0) Modifier.focusRequester(primeiro) else Modifier,
                            onFoco = { if (s != Secao.PARENTAL && s != Secao.AVANCADO) secao = s },
                        ) {
                            when (s) {
                                Secao.PARENTAL -> onParental()
                                Secao.AVANCADO -> onAvancado()
                                else -> secao = s
                            }
                        }
                    }
                }
                Spacer(Modifier.width(36.dp))
                // ---- Opções ----
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Text(secao.titulo, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    conteudo(secao)
                }
            }
        }
    }
}

@Composable
private fun ItemMenu(texto: String, ativo: Boolean, modifier: Modifier, onFoco: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(6.dp))
            .background(if (f) Color(0xFFE5482F) else if (ativo) Color(0x33E5482F) else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFoco() }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(texto, color = Color.White, fontSize = 17.sp, fontWeight = if (f || ativo) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Grupo(titulo: String) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        Text(titulo, color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
        return
    }
    Text(titulo, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
}

@Composable
private fun <T> Opcoes(opcoes: List<Pair<T, String>>, atual: T, corTexto: (T) -> Color = { Color.White }, onEscolher: (T) -> Unit) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1F))) {
            opcoes.forEachIndexed { i, (v, nome) ->
                val sel = v == atual
                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Color(0x0FFFFFFF)))
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { onEscolher(v) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nome, color = corTexto(v).takeIf { it != Color.White } ?: Color(0xFFEDEDED), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, if (sel) Color(0xFFE50914) else Color(0xFF4A4A4F), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (sel) Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE50914))) }
                }
            }
        }
        return
    }
    LinhaBotoes {
        opcoes.forEach { (v, nome) -> BotaoAjuste(nome, v == atual, corTexto(v)) { onEscolher(v) } }
    }
}

/** Botão de opção: escolhida = triângulo dourado no canto; com o foco = borda dourada e um pouco maior. */
@Composable
private fun BotaoAjuste(texto: String, marcado: Boolean, cor: Color = Color.White, onClick: () -> Unit) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1F))
                .border(1.dp, if (marcado) Color(0xFFE50914) else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(texto, color = if (cor == Color.White) Color(0xFFEDEDED) else cor, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("›", color = Color(0xFF85858B), fontSize = 20.sp)
        }
        return
    }
    var f by remember { mutableStateOf(false) }
    val esc by animateFloatAsState(if (f) ZarkLeve.escala(1.06f) else 1f, label = "escAjuste")
    Box(
        Modifier.width(150.dp).height(50.dp)
            .graphicsLayer { scaleX = esc; scaleY = esc }
            .clip(RoundedCornerShape(6.dp))
            .background(if (f) Color(0xFF5A6070) else Color(0xFF3A3F4B))
            .border(if (f) 2.dp else 0.dp, if (f) Dourado else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = cor, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (marcado) {
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomEnd).size(14.dp)) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(p, Dourado)
            }
        }
    }
}

@Composable
private fun ConteudoAjuste(secao: Secao, vm: TvViewModel, escala: Int, versao: String, onAtualizar: () -> Unit, onSuporte: () -> Unit) {
    val ctx = LocalContext.current
    Column {
    when (secao) {
        Secao.TEMA -> {
            var tema by remember { mutableStateOf(ZarkPlayTema.atual.value) }
            Grupo("Visual da tela inicial")
            Opcoes(listOf("blocos" to "Blocos", "cinema" to "Cinema"), tema) { tema = it; ZarkPlayTema.salvar(ctx, it) }
            Text(
                "Blocos: os quatro blocos grandes. Cinema: filme ou série em cartaz passando no fundo, capas no meio e os botões embaixo. Os botões fazem a mesma coisa nos dois.",
                color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Secao.REPRODUCAO -> {
            var idioma by remember { mutableStateOf(ZarkPrefs.idioma(ctx)) }
            var leg by remember { mutableStateOf(ZarkPrefs.legIdioma(ctx)) }
            var tam by remember { mutableIntStateOf(ZarkPrefs.legTamanho(ctx)) }
            var est by remember { mutableIntStateOf(ZarkPrefs.legEstilo(ctx)) }
            Grupo("Áudio dos filmes e séries")
            Opcoes(listOf("auto" to "Automático", "pt" to "Dublado", "leg" to "Original"), idioma) {
                idioma = it; ZarkPrefs.setIdioma(ctx, it); vm.zarkAplicarPreferencias()
            }
            Grupo("Legendas")
            Opcoes(listOf("auto" to "Automático", "off" to "Desligar", "pt" to "Português", "es" to "Espanhol", "en" to "Inglês"), leg) {
                leg = it; ZarkPrefs.setLegIdioma(ctx, it); vm.zarkAplicarPreferencias()
            }
            Grupo("Tamanho da legenda")
            Opcoes(listOf(0 to "Pequeno", 1 to "Normal", 2 to "Grande"), tam) {
                tam = it; ZarkPrefs.setLegTamanho(ctx, it); AjustesEstado.legenda.intValue++
            }
            Grupo("Estilo da legenda")
            Opcoes(listOf(0 to "Branca", 1 to "Amarela", 2 to "Com fundo"), est, corTexto = { v -> if (v == 1) Color(0xFFFFD54F) else Color.White }) {
                est = it; ZarkPrefs.setLegEstilo(ctx, it); AjustesEstado.legenda.intValue++
            }
        }
        Secao.PROPORCAO -> {
            Grupo("Como a imagem ocupa a tela")
            Opcoes(listOf(0 to "Normal", 1 to "Tela cheia", 2 to "Esticar"), escala) { vm.setVideoScale(it) }
            // Tamanho de tudo na tela (botões, letras, capas): para TV ou monitor onde fica grande ou pequeno demais.
            Grupo("Tamanho da tela")
            Opcoes(io.github.axon07.channelbox.TamanhoTela.opcoes, io.github.axon07.channelbox.TamanhoTela.escala.floatValue) {
                io.github.axon07.channelbox.TamanhoTela.salvar(ctx, it)
            }
            if (io.github.axon07.channelbox.player.TvPlayer.FIRE_MEDIATEK) {
                // Teste do Fire Stick 4K: qual decodificador usar (feche e abra o app depois de trocar).
                var modo by remember { mutableStateOf(io.github.axon07.channelbox.player.TvPlayer.modoFire(ctx)) }
                Grupo("Decodificador do Fire Stick (teste)")
                Opcoes(listOf(0 to "Software", 1 to "Aparelho", 2 to "Aparelho 2"), modo) {
                    modo = it; io.github.axon07.channelbox.player.TvPlayer.salvarModoFire(ctx, it)
                }
            }
            Text(
                "Normal mostra a imagem inteira. Tela cheia corta um pouco das bordas. Esticar ocupa tudo, deformando a imagem.",
                color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Secao.CACHE -> {
            Text("Apaga as imagens guardadas (capas e logos). O app baixa de novo quando precisar.", color = Color(0xCCFFFFFF), fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            BotaoAjuste("Limpar agora", false) {
                runCatching { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
                vm.showToast("Cache limpo")
            }
        }
        Secao.VERSAO -> {
            if (io.github.axon07.channelbox.system.Aparelho.soCelular) VerificarCelular(vm, versao)
            var toques by remember { mutableIntStateOf(0) }
            var beta by remember { mutableStateOf(io.github.axon07.channelbox.data.Updater.canalBeta(ctx)) }
            // 5 toques na versão mostram a opção de versões de teste (só para o dono).
            Text(
                "Versão instalada: $versao" + if (beta) "  ·  recebendo versões de teste" else "",
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier.clickable { toques++ },
            )
            Spacer(Modifier.height(16.dp))
            LinhaBotoes {
                BotaoAjuste("Procurar atualização", false) { toques++; onAtualizar() }
                if (toques >= 5 || beta) BotaoAjuste(if (beta) "Teste: ligado" else "Teste: desligado", beta) {
                    beta = !beta
                    io.github.axon07.channelbox.data.Updater.setCanalBeta(ctx, beta)
                    vm.showToast(if (beta) "Vai receber as versões de teste" else "Voltou para a versão dos clientes")
                }
            }
        }
        Secao.DIAGNOSTICO -> {
            var versaoDiag by remember { mutableIntStateOf(0) }
            val texto = remember(versaoDiag) {
                io.github.axon07.channelbox.player.ZarkDiag.iniciar(ctx)
                io.github.axon07.channelbox.player.ZarkDiag.texto(ctx, versao, vm.player.velocidadeMBs())
            }
            Text("Mostra como os canais e filmes estão abrindo neste aparelho. Nenhuma senha é guardada.", color = Color(0x99FFFFFF), fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).padding(16.dp)) {
                Text(texto, color = Color.White, fontSize = 15.sp, lineHeight = 24.sp)
            }
            Spacer(Modifier.height(16.dp))
            LinhaBotoes {
                BotaoAjuste("Enviar ao suporte", false) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(io.github.axon07.channelbox.data.ZarkConfig.WHATSAPP + "?text=" + android.net.Uri.encode(texto)))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                BotaoAjuste("Atualizar", false) { versaoDiag++ }
                BotaoAjuste("Zerar", false) { io.github.axon07.channelbox.player.ZarkDiag.zerar(); versaoDiag++ }
            }
        }
        Secao.SOBRE -> {
            Text("ZarkTV $versao", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("TV ao vivo, filmes e séries em um só app.", color = Color(0xCCFFFFFF), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            BotaoAjuste("Falar com o suporte", false) { onSuporte() }
        }
        Secao.PARENTAL -> if (io.github.axon07.channelbox.system.Aparelho.soCelular) ParentalCel(vm)
        else -> Unit
    }
    }
}

/** Celular: lista de itens; tocando abre a tela do item inteira, com seta para voltar. */
@Composable
private fun AjustesCelular(onParental: () -> Unit, onAvancado: () -> Unit, conteudo: @Composable (Secao) -> Unit) {
    // Celular: tudo numa tela só. Cada seção abre ali mesmo (sanfona), sem ir para outra tela;
    // só "Ajustes avançados" leva para a tela completa de rede e reprodução.
    var aberta by remember { mutableStateOf<Secao?>(null) }
    val voltar = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val t1 = Color(0xFFEDEDED); val t3 = Color(0xFF85858B)
    Column(Modifier.fillMaxSize().background(Color(0xFF141416)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { voltar?.onBackPressed() }, contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.Voltar, tint = t1, size = 22.dp)
            }
            Text("Configurações", color = t1, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Secao.entries.filter { it != Secao.AVANCADO && (it != Secao.TEMA || ZarkPlay.ativo) }.forEach { item ->
                val desc = when (item) {
                    Secao.TEMA -> "Visual da tela inicial: Blocos ou Cinema"
                    Secao.REPRODUCAO -> "Áudio dublado ou original e legendas"
                    Secao.PROPORCAO -> "Como o vídeo ocupa a tela e tamanho dos botões"
                    Secao.PARENTAL -> "Senha para o conteúdo adulto e bloqueios"
                    Secao.CACHE -> "Libera espaço apagando capas guardadas"
                    Secao.VERSAO -> "Ver se há versão nova"
                    Secao.DIAGNOSTICO -> "Como os canais estão abrindo"
                    Secao.SOBRE -> "Versão e suporte"
                    Secao.AVANCADO -> ""
                }
                val aqui = aberta == item
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1F))) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { aberta = if (aqui) null else item }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.titulo, color = t1, fontSize = 16.sp, fontWeight = if (aqui) FontWeight.Medium else FontWeight.Normal)
                            if (!aqui) Text(desc, color = t3, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(if (aqui) "–" else "+", color = t3, fontSize = 20.sp)
                    }
                    androidx.compose.animation.AnimatedVisibility(aqui) {
                        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) { conteudo(item) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1F)).clickable(onClick = onAvancado).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ajustes avançados", color = t1, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("›", color = t3, fontSize = 22.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Controle parental do celular: mesmas linhas e botões das outras seções. */
@Composable
private fun ParentalCel(vm: TvViewModel) {
    val settings by vm.settings.collectAsState()
    val locked by vm.lockedItems.collectAsState()
    val temPin = settings?.pinHash != null
    val t1 = Color(0xFFEDEDED); val t3 = Color(0xFF85858B)
    @Composable
    fun linha(titulo: String, sub: String?, acao: String, perigo: Boolean = false, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(titulo, color = t1, fontSize = 15.sp)
                if (sub != null) Text(sub, color = t3, fontSize = 12.sp)
            }
            Text(acao, color = if (perigo) Color(0xFFFF6B6B) else Color(0xFFE50914), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
    Text("Com uma senha (PIN) você bloqueia o conteúdo adulto e, segurando o dedo num canal ou categoria, bloqueia ou esconde.", color = t3, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    linha(if (temPin) "Trocar a senha" else "Criar a senha", null, if (temPin) "Trocar" else "Criar") { vm.changePin() }
    if (temPin) {
        linha("Esqueci a senha", "Confirma com o usuário e a senha da conta", "Recuperar") { vm.esqueciPin.value = true }
        linha("Pedir senha para abrir as Configurações", null, if (settings?.pinForSettings == true) "Ligado" else "Desligado") { vm.setPinForSettings(settings?.pinForSettings != true) }
        linha("Bloquear de novo agora", "Depois da senha tudo fica aberto por 2 horas", "Bloquear") { vm.lockNow() }
        linha("Remover a senha", "Desliga todos os bloqueios", "Remover", perigo = true) { vm.removePin() }
    }
    if (locked.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Bloqueados", color = t1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        locked.forEach { item -> linha(item.label, null, "Desbloquear") { vm.unlockItem(item) } }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinhaBotoes(content: @Composable () -> Unit) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

/** Celular: cartão com animação enquanto procura a atualização e o resultado claro no fim. */
@Composable
private fun VerificarCelular(vm: TvViewModel, versao: String) {
    val estado by vm.update.collectAsStateWithLifecycle()
    var desde by remember { mutableStateOf(0L) }
    var segura by remember { mutableStateOf(false) }
    // Mostra o "Verificando…" por pelo menos 1,5 s, para dar tempo de ver.
    LaunchedEffect(estado) {
        if (estado is TvViewModel.UpdateState.Checking) { desde = System.currentTimeMillis(); segura = true }
        else if (segura) { delay((1_500 - (System.currentTimeMillis() - desde)).coerceAtLeast(0)); segura = false }
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1F)).padding(vertical = 22.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val e = estado
        when {
            segura || e is TvViewModel.UpdateState.Checking -> {
                val gira = rememberInfiniteTransition(label = "verifica")
                val ang by gira.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ang")
                androidx.compose.foundation.Canvas(Modifier.size(56.dp)) {
                    drawCircle(Color(0x22FFFFFF), style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx()))
                    drawArc(Color(0xFFE50914), ang, 90f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Text("Verificando atualizações…", color = Color(0xFFEDEDED), fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp))
                Text("Versão instalada: $versao", color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            e is TvViewModel.UpdateState.UpToDate -> {
                PagoAnimado(64.dp)
                Text("Você está na versão mais recente", color = Color(0xFFEDEDED), fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                Text("Versão $versao", color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            e is TvViewModel.UpdateState.Available || e is TvViewModel.UpdateState.Downloading -> {
                Text("Nova versão disponível", color = Color(0xFFEDEDED), fontSize = 16.sp)
                Text("Toque em Atualizar no aviso para instalar.", color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            e is TvViewModel.UpdateState.Failed -> {
                Text("Não deu para verificar agora", color = Color(0xFFEDEDED), fontSize = 16.sp)
                Text("Confira a internet e tente de novo.", color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            else -> {
                Text("Versão instalada: $versao", color = Color(0xFFEDEDED), fontSize = 16.sp)
                Text("Toque em Procurar atualização para conferir.", color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Celular (Perfil → Verificar atualização): o mesmo cartão animado, no meio da tela. */
@Composable
fun VerificandoAtualizacao(vm: TvViewModel, versao: String, onFechar: () -> Unit) {
    val estado by vm.update.collectAsStateWithLifecycle()
    // Achou versão nova: o aviso de atualização assume.
    LaunchedEffect(estado) { if (estado is TvViewModel.UpdateState.Available) { delay(1_600); onFechar() } }
    androidx.activity.compose.BackHandler { onFechar() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            VerificarCelular(vm, versao)
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2F)).clickable(onClick = onFechar),
                contentAlignment = Alignment.Center,
            ) { Text("Fechar", color = Color(0xFFEDEDED), fontSize = 15.sp) }
        }
    }
}
