package de.chennemann.agentic.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    suspend fun read(environmentId: String): String?

    suspend fun write(
        environmentId: String,
        bearerToken: String,
    )

    suspend fun remove(environmentId: String)
}

class KeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override suspend fun read(environmentId: String): String? {
        val encoded = preferences.getString(preferenceKey(environmentId), null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IvSizeBytes)
            val ciphertext = bytes.copyOfRange(IvSizeBytes, bytes.size)
            val cipher = Cipher.getInstance(CipherTransformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagBits, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun write(
        environmentId: String,
        bearerToken: String,
    ) {
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = Base64.encodeToString(
            cipher.iv + cipher.doFinal(bearerToken.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        preferences.edit().putString(preferenceKey(environmentId), encoded).apply()
    }

    override suspend fun remove(environmentId: String) {
        preferences.edit().remove(preferenceKey(environmentId)).apply()
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

    private fun preferenceKey(environmentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(environmentId.toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val PreferencesName = "agentic_t3_credentials"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "agentic-t3-bearer-key-v1"
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val IvSizeBytes = 12
        const val GcmTagBits = 128
    }
}
