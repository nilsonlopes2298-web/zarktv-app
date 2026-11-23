package io.github.axon07.channelbox.ui

import androidx.compose.animation.core.animateFloatAsState
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
    REPRODUCAO("Reprodução"),
    PROPORCAO("Proporção da tela"),
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
    var secao by remember { mutableStateOf(Secao.REPRODUCAO) }
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
                    Secao.entries.forEachIndexed { i, s ->
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
    Text(titulo, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
}

@Composable
private fun <T> Opcoes(opcoes: List<Pair<T, String>>, atual: T, corTexto: (T) -> Color = { Color.White }, onEscolher: (T) -> Unit) {
    LinhaBotoes {
        opcoes.forEach { (v, nome) -> BotaoAjuste(nome, v == atual, corTexto(v)) { onEscolher(v) } }
    }
}

/** Botão de opção: escolhida = triângulo dourado no canto; com o foco = borda dourada e um pouco maior. */
@Composable
private fun BotaoAjuste(texto: String, marcado: Boolean, cor: Color = Color.White, onClick: () -> Unit) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular) {
        Box(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                .background(if (marcado) Color(0x33E50914) else Color(0xFF232325))
                .border(1.dp, if (marcado) Color(0xFFE50914) else Color(0x14EDEDED), RoundedCornerShape(10.dp))
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(texto, color = if (cor == Color.White) Color(0xFFEDEDED) else cor, fontSize = 14.sp, maxLines = 1) }
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
        else -> Unit
    }
    }
}

/** Celular: lista de itens; tocando abre a tela do item inteira, com seta para voltar. */
@Composable
private fun AjustesCelular(onParental: () -> Unit, onAvancado: () -> Unit, conteudo: @Composable (Secao) -> Unit) {
    var aberta by remember { mutableStateOf<Secao?>(null) }
    androidx.activity.compose.BackHandler(enabled = aberta != null) { aberta = null }
    val voltar = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val fundo = Color(0xFF141416)
    Column(Modifier.fillMaxSize().background(fundo).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { if (aberta != null) aberta = null else voltar?.onBackPressed() }, contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.Voltar, tint = Color(0xFFEDEDED), size = 22.dp)
            }
            Text(aberta?.titulo ?: "Configurações", color = Color(0xFFEDEDED), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        val s = aberta
        if (s == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Secao.entries.forEach { item ->
                    val desc = when (item) {
                        Secao.REPRODUCAO -> "Áudio dublado ou original e legendas"
                        Secao.PROPORCAO -> "Como o vídeo ocupa a tela"
                        Secao.PARENTAL -> "Senha para o conteúdo adulto"
                        Secao.CACHE -> "Libera espaço apagando capas guardadas"
                        Secao.VERSAO -> "Ver se há versão nova"
                        Secao.DIAGNOSTICO -> "Como os canais estão abrindo"
                        Secao.SOBRE -> "Versão e suporte"
                        Secao.AVANCADO -> "Rede, VPN e reprodução"
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable {
                            when (item) { Secao.PARENTAL -> onParental(); Secao.AVANCADO -> onAvancado(); else -> aberta = item }
                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.titulo, color = Color(0xFFEDEDED), fontSize = 16.sp)
                            Text(desc, color = Color(0xFF85858B), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text("›", color = Color(0xFF85858B), fontSize = 22.sp)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                conteudo(s)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinhaBotoes(content: @Composable () -> Unit) {
    if (io.github.axon07.channelbox.system.Aparelho.soCelular)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
