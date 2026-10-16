package com.trendpilot.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secureStore: DataStore<Preferences> by preferencesDataStore(name = "secure_store")

enum class Secret(val key: String, val label: String) {
    BINANCE_API_KEY("binance_api_key", "Binance API key"),
    BINANCE_SECRET("binance_secret", "Binance secret"),
    ANTHROPIC_API_KEY("anthropic_api_key", "Anthropic (Claude) API key"),
    GEMINI_API_KEY("gemini_api_key", "Google Gemini API key"),
    OPENAI_API_KEY("openai_api_key", "OpenAI API key"),
    COINGECKO_DEMO_KEY("coingecko_demo_key", "CoinGecko demo key (opcional)"),
}

/**
 * Secrets encrypted with AES-256-GCM (authenticated encryption) under a non-exportable Android
 * Keystore key. Ciphertext lives in a DataStore excluded from backups. The key does NOT require
 * user authentication because the background engine must use the credentials while the screen is
 * off; viewing/editing them in the UI is gated by biometrics/PIN instead.
 * Limitation: on a rooted or compromised device a running process can still be inspected.
 */
class SecureSettings(private val context: Context) {
    private val alias = "trendpilot_secrets_v1"
    private val cache = mutableMapOf<Secret, String?>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val (iv, ct) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    suspend fun set(secret: Secret, value: String?) {
        val k = stringPreferencesKey(secret.key)
        context.secureStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(k) else prefs[k] = encrypt(value.trim())
        }
        synchronized(cache) { cache[secret] = value?.trim()?.takeIf { it.isNotEmpty() } }
    }

    suspend fun get(secret: Secret): String? {
        synchronized(cache) { if (cache.containsKey(secret)) return cache[secret] }
        val raw = context.secureStore.data.map { it[stringPreferencesKey(secret.key)] }.first()
        val v = raw?.let(::decrypt)
        synchronized(cache) { cache[secret] = v }
        return v
    }

    /** Blocking accessor for SDK callbacks that are not suspend-aware (always off the main thread). */
    fun getBlocking(secret: Secret): String? = runBlocking { get(secret) }

    suspend fun isSet(secret: Secret) = !get(secret).isNullOrBlank()

    /** Only the last 4 characters are ever displayed. */
    suspend fun masked(secret: Secret): String = get(secret)?.let { if (it.length <= 4) "••••" else "••••••" + it.takeLast(4) } ?: "no configurada"
}
