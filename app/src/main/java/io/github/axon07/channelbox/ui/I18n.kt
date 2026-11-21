package io.github.axon07.channelbox.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.LocalTextStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * App languages. English is built in; each other language is a file in assets/i18n/<code>.json
 * mapping the English text to the translation:
 *
 *   { "Settings": "Paramètres", "{} channels": "{} chaînes" }
 *
 * "{}" stands for a number or name filled in at run time; "{1}", "{2}" in a translation pick which
 * one when a language needs a different order. Anything not in the file stays in English.
 */
object I18n {
    /** Language code in use ("en" = built-in English). */
    var language by mutableStateOf("en")
        private set

    private var exact by mutableStateOf<Map<String, String>>(emptyMap())
    private var templates: List<Pair<Regex, String>> = emptyList()
    private val cache = HashMap<String, String>()

    /** Languages with a translation file, plus English: code to name in that language. */
    fun available(context: Context): List<Pair<String, String>> {
        val files = context.assets.list("i18n").orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
        return listOf("en" to "English") + files.sorted().map { it to (NAMES[it] ?: it) }
    }

    fun load(context: Context, code: String) {
        val table = if (code == "en") emptyMap() else runCatching {
            val text = context.assets.open("i18n/$code.json").bufferedReader().use { it.readText() }
            (Json.parseToJsonElement(text) as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
        }.getOrDefault(emptyMap())
        synchronized(cache) { cache.clear() }
        templates = table.filterKeys { "{}" in it }.map { (k, v) ->
            Regex("^" + k.split("{}").joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL) to v
        }
        exact = table
        language = if (table.isEmpty()) "en" else code
    }

    /** [text] in the chosen language (English if there's no translation). */
    fun tr(text: String): String {
        if (text.isEmpty()) return text
        val table = exact
        if (table.isEmpty()) return text
        table[text]?.let { return it }
        synchronized(cache) { cache[text]?.let { return it } }
        var out = text
        for ((pattern, translation) in templates) {
            val m = pattern.find(text) ?: continue
            val values = m.groupValues.drop(1)
            var i = 0
            out = Regex("""\{(\d*)\}""").replace(translation) { r ->
                val n = r.groupValues[1].toIntOrNull()
                values.getOrElse(if (n != null) n - 1 else i++) { "" }
            }
            break
        }
        synchronized(cache) { if (cache.size < 5_000) cache[text] = out }
        return out
    }

    val NAMES = mapOf(
        "ar" to "العربية", "bg" to "Български", "cs" to "Čeština", "da" to "Dansk", "de" to "Deutsch",
        "el" to "Ελληνικά", "es" to "Español", "fa" to "فارسی", "fi" to "Suomi", "fr" to "Français",
        "he" to "עברית", "hi" to "हिन्दी", "hr" to "Hrvatski", "hu" to "Magyar", "id" to "Bahasa Indonesia",
        "it" to "Italiano", "ja" to "日本語", "ko" to "한국어", "ms" to "Bahasa Melayu", "nl" to "Nederlands",
        "no" to "Norsk", "pl" to "Polski", "pt" to "Português (Portugal)", "pt-BR" to "Português (Brasil)", "ro" to "Română", "ru" to "Русский",
        "sk" to "Slovenčina", "sr" to "Srpski", "sv" to "Svenska", "th" to "ไทย", "tl" to "Filipino (Tagalog)",
        "tr" to "Türkçe", "uk" to "Українська", "ur" to "اردو", "vi" to "Tiếng Việt", "zh" to "中文",
        "pa" to "ਪੰਜਾਬੀ", "bn" to "বাংলা", "ta" to "தமிழ்", "sq" to "Shqip", "so" to "Soomaali",
    )
}

fun tr(text: String) = I18n.tr(text)

/**
 * Text that's shown in the chosen language. Same as the TV Material Text, which the app's screens
 * use through this one, so every label is translated without changing each screen.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.tv.material3.Text(
    I18n.tr(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing,
    textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style,
)

/** Languages written right to left. */
val RTL_LANGUAGES = setOf("ar", "he", "fa", "ur")

/** Lays [content] out left-to-right even in right-to-left languages (guide timeline, player bars). */
@Composable
fun LtrBox(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
        content = content,
    )
