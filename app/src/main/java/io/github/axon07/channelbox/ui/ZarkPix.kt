package io.github.axon07.channelbox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pagar agora (Perfil): escolhe o plano, gera o Pix no painel (Mercado Pago) e mostra o QR Code
 * na própria tela. Confere o pagamento sozinho a cada 5 s e avisa quando cair.
 */
@Composable
fun PixDialogo(
    http: OkHttpClient,
    checkout: String,
    usuario: String,
    servidor: String,
    onFechar: () -> Unit,
    onAbrirPagina: () -> Unit,
    onSuporte: () -> Unit,
) {
    data class Plano(val id: String, val nome: String, val dias: Int, val valor: Double)
    data class Pix(val id: String, val plano: String, val valor: Double, val copia: String?, val link: String?)

    var planos by remember { mutableStateOf<List<Plano>?>(null) }
    var pix by remember { mutableStateOf<Pix?>(null) }
    var gerando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var pago by remember { mutableStateOf(false) }
    var copiado by remember { mutableStateOf(false) }
    val cel = io.github.axon07.channelbox.system.Aparelho.soCelular
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val brl = { v: Double -> "R$ " + String.format(java.util.Locale("pt", "BR"), "%.2f", v) }

    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }

    suspend fun pegar(url: String, corpo: String? = null): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val b = Request.Builder().url(url)
            if (corpo != null) b.post(corpo.toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use { r -> r.body?.string()?.let { Json.parseToJsonElement(it) as? JsonObject } }
        }.getOrNull()
    }

    fun gerar(plano: Plano) {
        gerando = true
        erro = null
        scope.launch {
            val corpo = """{"usuario":${JsonPrimitive(usuario)},"servidor":${JsonPrimitive(servidor)},"plano":${JsonPrimitive(plano.id)}}"""
            val r = pegar("$checkout/api/pix", corpo)
            gerando = false
            val id = r?.s("id")
            if (id == null) {
                erro = r?.s("erro") ?: "Não foi possível gerar o Pix agora."
                return@launch
            }
            pix = Pix(id, r.s("plano") ?: plano.nome, r.s("valor")?.toDoubleOrNull() ?: plano.valor, r.s("pixCopiaCola"), r.s("link"))
        }
    }

    LaunchedEffect(Unit) {
        val r = pegar("$checkout/api/planos")
        val lista = (r?.get("planos") as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.let { o ->
                Plano(o.s("id") ?: return@let null, o.s("nome") ?: "Plano", o.s("dias")?.toIntOrNull() ?: 30, o.s("valor")?.toDoubleOrNull() ?: 0.0)
            }
        }
        if (lista.isNullOrEmpty()) erro = "Pagamento pelo app em desenvolvimento. Fale com o suporte para renovar." else {
            planos = lista
            if (lista.size == 1) gerar(lista.first())
        }
    }
    // Confere o pagamento enquanto o QR está na tela.
    LaunchedEffect(pix?.id) {
        val id = pix?.id ?: return@LaunchedEffect
        while (!pago) {
            delay(5000)
            val st = pegar("$checkout/api/pedido/$id")?.s("status")
            if (st == "pago") pago = true
        }
    }

    val foco = remember { FocusRequester() }
    LaunchedEffect(planos, pix, erro, pago) { delay(100); runCatching { foco.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            (if (cel) Modifier.fillMaxWidth().padding(horizontal = 20.dp) else Modifier.width(440.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xF2181A20))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MarcaPequena(15)
                Spacer(Modifier.weight(1f))
                Text("Pagar com Pix", color = Color(0xB3FFFFFF), fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            when {
                pago -> {
                    Text("Pagamento confirmado!", color = Color(0xFF66BB6A), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Seu acesso será renovado em instantes. Obrigado!", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(16.dp))
                    BotaoPix("Fechar", true, Modifier.fillMaxWidth().focusRequester(foco), onFechar)
                }
                erro != null -> {
                    Text(erro!!, color = if (cel) Color(0xFFEDEDED) else Color(0xFFFF8A80), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    BotaoPix("Abrir página de pagamento", true, Modifier.fillMaxWidth().focusRequester(foco), onAbrirPagina)
                    Spacer(Modifier.height(8.dp))
                    BotaoPix("Falar com o suporte", false, Modifier.fillMaxWidth(), onSuporte)
                    Spacer(Modifier.height(8.dp))
                    BotaoPix("Fechar", false, Modifier.fillMaxWidth(), onFechar)
                }
                pix != null -> {
                    val p = pix!!
                    Text("${p.plano} · ${brl(p.valor)}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Usuário $usuario", color = Color(0x99FFFFFF), fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    if (p.copia != null && cel) {
                        // Celular: o cliente paga no próprio aparelho, então o principal é o Copia e Cola.
                        BotaoPix(if (copiado) "Código copiado" else "Copiar código Pix", true, Modifier.fillMaxWidth()) {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Pix", p.copia))
                            copiado = true
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (copiado) "Agora abra o app do seu banco, entre em Pix → Copia e Cola e cole o código."
                            else "Copie o código e cole no app do seu banco em Pix → Copia e Cola.",
                            color = Color(0xFFEDEDED), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                        )
                    } else if (p.copia != null) {
                        val qr = remember(p.copia) { runCatching { io.github.axon07.channelbox.system.PhoneLink.qrCode(p.copia, 520).asImageBitmap() }.getOrNull() }
                        qr?.let {
                            Box(Modifier.size(220.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp)) {
                                Image(it, "QR Code do Pix", modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Abra o app do seu banco, escolha Pix → Ler QR Code e aponte para a tela.", color = Color(0xCCFFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        BotaoPix("Copiar código Pix", false, Modifier.fillMaxWidth()) {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Pix", p.copia))
                        }
                    } else if (p.link != null) {
                        BotaoPix("Abrir página do Pix", true, Modifier.fillMaxWidth()) { onAbrirPagina() }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BarrinhaCarregando(Modifier.width(80.dp))
                        Text("  Aguardando o pagamento…", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    BotaoPix("Fechar", false, Modifier.fillMaxWidth().focusRequester(foco), onFechar)
                }
                planos != null && !gerando -> {
                    Text("Escolha o plano", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    planos!!.forEachIndexed { i, pl ->
                        BotaoPix("${pl.nome} · ${pl.dias} dias · ${brl(pl.valor)}", i == 0, Modifier.fillMaxWidth().padding(bottom = 8.dp).let { if (i == 0) it.focusRequester(foco) else it }) { gerar(pl) }
                    }
                    BotaoPix("Fechar", false, Modifier.fillMaxWidth(), onFechar)
                }
                else -> {
                    Text(if (gerando) "Gerando o Pix…" else "Carregando os planos…", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    BolinhasCarregando(Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    BotaoPix("Cancelar", false, Modifier.fillMaxWidth().focusRequester(foco), onFechar)
                }
            }
        }
    }
}

@Composable
private fun BotaoPix(texto: String, principal: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(if (principal) Palette.Accent else if (f) Color(0x55FFFFFF) else Color(0x26FFFFFF))
            .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(23.dp))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}
