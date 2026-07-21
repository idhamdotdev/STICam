package com.sticam.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the pairing credential encrypted by a non-exportable Android Keystore key. */
object PairingKeyStore {
    private const val PREFS = "sticam_connection"
    private const val LEGACY_KEY = "pairing_key"
    private const val ENCRYPTED_KEY = "pairing_key_v2"
    private const val KEY_ALIAS = "com.sticam.pairing.v2"
    private val aad = "STICam Android pairing key v2".toByteArray(StandardCharsets.UTF_8)

    fun load(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = preferences.getString(ENCRYPTED_KEY, null)
        if (encrypted != null) return decrypt(encrypted)

        val legacy = preferences.getString(LEGACY_KEY, null)
            ?.trim()
            ?.uppercase()
            .orEmpty()
        if (legacy.isValidPairingKey()) {
            save(context, legacy)
            return legacy
        }
        return ""
    }

    fun save(context: Context, pairingKey: String) {
        require(pairingKey.isValidPairingKey()) { "Invalid pairing key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(pairingKey.toByteArray(StandardCharsets.US_ASCII))
        val stored = listOf(
            "1",
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(".")
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ENCRYPTED_KEY, stored)
            .remove(LEGACY_KEY)
            .commit()
        check(committed) { "Unable to persist pairing key" }
    }

    fun clear(context: Context) {
        val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ENCRYPTED_KEY)
            .remove(LEGACY_KEY)
            .commit()
        check(committed) { "Unable to clear pairing key" }
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split('.')
        require(parts.size == 3 && parts[0] == "1") { "Unsupported pairing-key format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)),
        )
        cipher.updateAAD(aad)
        val value = String(
            cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)),
            StandardCharsets.US_ASCII,
        ).trim().uppercase()
        require(value.isValidPairingKey()) { "Stored pairing key is invalid" }
        return value
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun String.isValidPairingKey(): Boolean =
        length == 32 && all { it in '0'..'9' || it in 'A'..'F' }
}
