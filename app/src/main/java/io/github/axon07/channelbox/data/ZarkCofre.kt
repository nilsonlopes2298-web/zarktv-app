package io.github.axon07.channelbox.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cofre do Android (Keystore): criptografa a senha do "Lembrar de mim" no celular.
 * A chave fica no próprio aparelho e não sai dele. Se o aparelho não tiver cofre
 * (Android antigo ou com defeito), devolve null e o app segue como antes.
 */
object ZarkCofre {
    private const val ALIAS = "zarktv_cofre_v1"

    private fun chave(): SecretKey? {
        if (Build.VERSION.SDK_INT < 23) return null
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun cifrar(texto: String): String? = runCatching {
        val k = chave() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val dados = c.iv + c.doFinal(texto.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(dados, Base64.NO_WRAP)
    }.getOrNull()

    fun decifrar(guardado: String): String? = runCatching {
        val k = chave() ?: return null
        val dados = Base64.decode(guardado, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, dados, 0, 12)) }
        String(c.doFinal(dados, 12, dados.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
