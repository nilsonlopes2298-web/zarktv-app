package io.github.axon07.channelbox.ui

import androidx.compose.foundation.border
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 4

/**
 * 4-digit PIN pad: remote number keys, the on-screen keypad (D-pad + OK), or touch.
 * [setup]: choosing a new PIN – asked twice. [onSubmit] returns false for a wrong PIN.
 */
@Composable
fun PinDialog(title: String, setup: Boolean, onSubmit: (String) -> Boolean, onCancel: () -> Unit, onEsqueci: (() -> Unit)? = null) {
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) } // setup: the first entry
    var message by remember { mutableStateOf<String?>(null) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstKey.requestFocus() }
    }

    fun complete(entered: String) {
        if (setup && first == null) {
            first = entered
            pin = ""
            message = null
            return
        }
        if (setup && entered != first) {
            first = null
            pin = ""
            message = "The PINs didn't match – try again"
            return
        }
        if (!onSubmit(entered)) {
            pin = ""
            message = "Wrong PIN"
        }
    }

    fun type(d: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += d
        message = null
        if (pin.length == PIN_LENGTH) complete(pin)
    }

    fun back() {
        pin = pin.dropLast(1)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .tap { }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.key.nativeKeyCode
                when (code) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        type('0' + (code - AndroidKeyEvent.KEYCODE_0)); true
                    }
                    AndroidKeyEvent.KEYCODE_DEL -> {
                        back(); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(DialogStyle.Shape)
                .background(DialogStyle.Card)
                .border(1.dp, DialogStyle.Edge, DialogStyle.Shape)
                .padding(horizontal = 26.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(AppIcons.Lock, tint = Palette.TextDim, size = 28.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (setup && first != null) "Enter the same PIN again" else title,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (i < pin.length) Palette.Accent else Color(0x33FFFFFF))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(message ?: " ", fontSize = 13.sp, color = Palette.Error)
            Spacer(Modifier.height(10.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
            keys.chunked(3).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    chunk.forEachIndexed { col, k ->
                        if (k.isEmpty()) {
                            Spacer(Modifier.size(width = 72.dp, height = 52.dp))
                            return@forEachIndexed
                        }
                        val action = when (k) {
                            "⌫" -> ::back
                            else -> ({ type(k[0]) })
                        }
                        Surface(
                            onClick = action,
                            modifier = (if (row == 0 && col == 0) Modifier.focusRequester(firstKey) else Modifier)
                                .size(width = 72.dp, height = 52.dp)
                                .tap(onClick = action),
                            shape = ClickableSurfaceDefaults.shape(DialogStyle.ItemShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = DialogStyle.Rest,
                                contentColor = Palette.Text,
                                focusedContainerColor = DialogStyle.Focus,
                                focusedContentColor = DialogStyle.OnFocus,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = ZarkLeve.escala(1.03f)),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(k, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PillButton("Cancel", onClick = onCancel)
            if (onEsqueci != null) {
                Text(
                    "Esqueci a senha", fontSize = 14.sp, color = Color(0xFFA1A1A6),
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).tap(onClick = onEsqueci).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * "Esqueci a senha do adulto": a pessoa confirma com o usuário e a senha da conta
 * (os mesmos que usa para entrar nos canais) e cria uma senha nova.
 */
@Composable
fun EsqueciSenhaDialog(onConfirmar: (String, String) -> Boolean, onCancel: () -> Unit) {
    var usuario by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { onCancel() }
    val campo = @Composable { valor: String, dica: String, oculto: Boolean, muda: (String) -> Unit ->
        androidx.compose.foundation.text.BasicTextField(
            value = valor, onValueChange = { muda(it); erro = false }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFEDEDED), fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE50914)),
            visualTransformation = if (oculto) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (oculto) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232325)),
            decorationBox = { dentro ->
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (valor.isEmpty()) Text(dica, fontSize = 15.sp, color = Color(0xFF85858B))
                    dentro()
                }
            },
        )
    }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).tap { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 380.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1F))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(AppIcons.Lock, tint = Color(0xFFA1A1A6), size = 26.dp)
            Spacer(Modifier.height(10.dp))
            Text("Esqueceu a senha?", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEDEDED))
            Text(
                "Confirme com o usuário e a senha que você usa para entrar no app. Depois é só criar uma senha nova.",
                fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFA1A1A6), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            campo(usuario, "Usuário", false) { usuario = it }
            Spacer(Modifier.height(10.dp))
            campo(senha, "Senha", true) { senha = it }
            Text(if (erro) "Usuário ou senha não conferem" else " ", fontSize = 13.sp, color = Color(0xFFE5484D), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2F)).tap(onClick = onCancel), contentAlignment = Alignment.Center) {
                    Text("Cancelar", fontSize = 15.sp, color = Color(0xFFEDEDED))
                }
                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE50914)).tap { if (!onConfirmar(usuario, senha)) erro = true }, contentAlignment = Alignment.Center) {
                    Text("Confirmar", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** One-line text entry (rename, channel number) with Save / Cancel. Empty + Save = reset. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    numeric: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { field.requestFocus() }
    }
    DialogCard(width = 480.dp) {
        run {
            DialogTitle(title, hint)
            Spacer(Modifier.height(14.dp))
            TvTextField(
                "", text, { text = if (numeric) it.filter(Char::isDigit).take(5) else it },
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                fieldModifier = Modifier.focusRequester(field),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Save", primary = true) { onSave(text) }
                PillButton("Cancel", onClick = onCancel)
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
