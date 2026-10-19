package com.wedfaces.cameraoperator.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenStore {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun save(accessToken: String, refreshToken: String)
    fun clear()
}

class AndroidTokenStore(context: Context) : TokenStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("encrypted_session", Context.MODE_PRIVATE)

    override fun accessToken(): String? = read("access")

    override fun refreshToken(): String? = read("refresh")

    override fun save(accessToken: String, refreshToken: String) {
        preferences.edit()
            .putString("access", encrypt(accessToken))
            .putString("refresh", encrypt(refreshToken))
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun read(key: String): String? {
        val value = preferences.getString(key, null) ?: return null
        return try {
            decrypt(value)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${encode(cipher.iv)}.${encode(ciphertext)}"
    }

    private fun decrypt(value: String): String {
        val pieces = value.split('.', limit = 2)
        require(pieces.size == 2) { "Stored session data is invalid." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(pieces[0], Base64.NO_WRAP)),
        )
        return String(Base64.decode(pieces[1], Base64.NO_WRAP).let(cipher::doFinal), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wedfaces.cameraoperator.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
