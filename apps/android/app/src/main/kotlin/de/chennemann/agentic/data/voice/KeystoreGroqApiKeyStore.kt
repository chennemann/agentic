package de.chennemann.agentic.data.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.chennemann.agentic.domain.voice.GroqApiKeyStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KeystoreGroqApiKeyStore(
    context: Context,
) : GroqApiKeyStore {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val mutableConfigured = MutableStateFlow(preferences.contains(ApiKeyPreference))

    override val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    override suspend fun read(): String? {
        val encoded = preferences.getString(ApiKeyPreference, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IvSizeBytes)
            val ciphertext = bytes.copyOfRange(IvSizeBytes, bytes.size)
            val cipher = Cipher.getInstance(CipherTransformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagBits, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun write(apiKey: String) {
        require(apiKey.isNotBlank()) { "Enter a Groq API key." }
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = Base64.encodeToString(
            cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        preferences.edit().putString(ApiKeyPreference, encoded).apply()
        mutableConfigured.value = true
    }

    override suspend fun remove() {
        preferences.edit().remove(ApiKeyPreference).apply()
        mutableConfigured.value = false
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PreferencesName = "agentic_groq_credentials"
        const val ApiKeyPreference = "api_key"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "agentic-groq-api-key-v1"
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val IvSizeBytes = 12
        const val GcmTagBits = 128
    }
}
