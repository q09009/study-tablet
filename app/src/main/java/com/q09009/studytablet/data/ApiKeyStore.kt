package com.q09009.studytablet.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the OpenAI API key encrypted with an Android Keystore AES key. */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openai", Context.MODE_PRIVATE)
    private val _key = MutableStateFlow(load())
    val key: StateFlow<String?> = _key

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val enc = cipher.doFinal(apiKey.toByteArray())
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("key", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
        _key.value = apiKey
    }

    fun clear() {
        prefs.edit().remove("iv").remove("key").apply()
        _key.value = null
    }

    private fun load(): String? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val enc = Base64.decode(prefs.getString("key", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(enc))
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "openai_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
