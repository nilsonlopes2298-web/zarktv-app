package io.github.axon07.channelbox.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.Dlna
import kotlinx.coroutines.launch

/**
 * ZarkTV: "Transmitir" (como na UniTV): Smart TV pela rede (DLNA — Samsung, LG, TV Box) ou
 * espelhar a tela pelo Android (Chromecast / Smart View).
 */
@Composable
fun TransmitirDialog(vm: TvViewModel, url: String?, titulo: String, aoVivo: Boolean = false, capa: String? = null, onFechar: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tvs by remember { mutableStateOf<List<Dlna.Tv>?>(null) }
    var procurando by remember { mutableStateOf(true) }
    var busca by remember { mutableIntStateOf(0) }
    var enviando by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busca) { procurando = true; tvs = Dlna.procurar(ctx, vm.zarkHttp); procurando = false }
    // Chromecast / Google TV (Google Cast), achados pelo próprio Android.
    var casts by remember { mutableStateOf<List<androidx.mediarouter.media.MediaRouter.RouteInfo>>(emptyList()) }
    val temCast = remember { io.github.axon07.channelbox.data.Chromecast.contexto(ctx) != null }
    if (temCast) androidx.compose.runtime.DisposableEffect(Unit) {
        val router = androidx.mediarouter.media.MediaRouter.getInstance(ctx)
        val sel = io.github.axon07.channelbox.data.Chromecast.seletor
        fun atualizar() { casts = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) } }
        val cb = object : androidx.mediarouter.media.MediaRouter.Callback() {
            override fun onRouteAdded(r: androidx.mediarouter.media.MediaRouter, i: androidx.mediarouter.media.MediaRouter.RouteInfo) = atualizar()
            override fun onRouteChanged(r: androidx.mediarouter.media.MediaRouter, i: androidx.mediarouter.media.MediaRouter.RouteInfo) = atualizar()
            override fun onRouteRemoved(r: androidx.mediarouter.media.MediaRouter, i: androidx.mediarouter.media.MediaRouter.RouteInfo) = atualizar()
        }
        router.addCallback(sel, cb, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        atualizar()
        onDispose { router.removeCallback(cb) }
    }
    val t1 = Color(0xFFEDEDED); val t2 = Color(0xFFA1A1A6); val t3 = Color(0xFF85858B)
    Dialog(onDismissRequest = onFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onFechar), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF19191B))
                    .clickable(enabled = false) {}.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transmitir para a TV", color = t1, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("✕", color = t2, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onFechar).padding(4.dp))
                }
                Text("A TV e o celular precisam estar no mesmo Wi-Fi.", color = t3, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                if (temCast) {
                    Text("Chromecast e Google TV", color = t2, fontSize = 13.sp)
                    if (casts.isEmpty()) Text("Procurando…", color = t3, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                    casts.forEach { rota ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232325))
                                .clickable(enabled = enviando == null && url != null) {
                                    enviando = rota.name
                                    scope.launch {
                                        val final = vm.zarkUrlAcessivel(url!!)
                                        io.github.axon07.channelbox.data.Chromecast.tocar(ctx, rota, final, titulo, capa, aoVivo) { ok ->
                                            enviando = null
                                            vm.showToast(if (ok) "Tocando em ${rota.name}" else "${rota.name} não conseguiu abrir este vídeo")
                                            if (ok) onFechar()
                                        }
                                    }
                                }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(AppIcons.Transmitir, tint = t2, size = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(rota.name, color = t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (enviando == rota.name) Text("Conectando…", color = t3, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text("Smart TV (DLNA)", color = t2, fontSize = 13.sp)
                when {
                    procurando -> Text("Procurando TVs na rede…", color = t3, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                    tvs.isNullOrEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nenhuma TV encontrada.", color = t3, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("Procurar de novo", color = Color(0xFFE50914), fontSize = 13.sp, modifier = Modifier.clickable { busca++ }.padding(8.dp))
                    }
                    else -> tvs!!.forEach { tv ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232325))
                                .clickable(enabled = enviando == null && url != null) {
                                    enviando = tv.nome
                                    scope.launch {
                                        val final = vm.zarkUrlAcessivel(url!!)
                                        val ok = Dlna.tocar(vm.zarkHttp, tv, final, titulo)
                                        enviando = null
                                        vm.showToast(if (ok) "Tocando em ${tv.nome}" else "A TV ${tv.nome} não aceitou este vídeo")
                                        if (ok) onFechar()
                                    }
                                }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(AppIcons.Tv, tint = t2, size = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(tv.nome, color = t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (enviando == tv.nome) Text("Enviando…", color = t3, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232325)).clickable {
                        runCatching { ctx.startActivity(Intent("android.settings.CAST_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        onFechar()
                    }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(AppIcons.Transmitir, tint = t2, size = 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Espelhar a tela", color = t1, fontSize = 15.sp)
                        Text("Chromecast, Smart View e parecidos", color = t3, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
