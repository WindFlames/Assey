package io.savant.assey.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加密本地敏感值（MinerU Token / 翻译 Key）。
 * 密文格式：base64(iv + ciphertext)。
 */
class SecureStore {

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val body = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "assay.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
