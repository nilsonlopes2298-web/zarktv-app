package io.github.axon07.channelbox.system

import android.content.res.Configuration
import io.github.axon07.channelbox.BuildConfig

/**
 * ZarkTV: em qual tipo de aparelho este APK roda.
 *
 *  - "tv"     (ZarkTV TV): sempre modo controle remoto, mesmo em TV Box barata que se diz "celular".
 *  - "mobile" (ZarkTV Mobile): sempre modo toque.
 *  - "auto"   (ZarkTV de sempre): descobre sozinho, como já fazia.
 */
object Aparelho {
    const val MODO: String = BuildConfig.APARELHO

    val soTv: Boolean get() = MODO == "tv"
    val soCelular: Boolean get() = MODO == "mobile"

    /** Usar no lugar de olhar o uiMode direto: respeita a versão do app. */
    fun ehTv(config: Configuration): Boolean = when (MODO) {
        "tv" -> true
        "mobile" -> false
        else -> (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
