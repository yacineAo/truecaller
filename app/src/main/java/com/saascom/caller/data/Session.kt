package com.saascom.caller.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.saascom.caller.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SessionUser(val id: Long, val name: String, val role: String)

/**
 * The logged-in user and their API token.
 *
 * The token is encrypted with an AES key that never leaves the Android Keystore. It is kept
 * in memory too, because the screening service needs it instantly while the phone rings.
 */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val cipher = TokenCipher()

    @Volatile
    var token: String? = prefs.getString(KEY_TOKEN, null)?.let(cipher::decrypt)
        private set

    private val _user = MutableStateFlow(if (token != null) loadUser() else null)

    /** null when logged out; the UI goes back to the login screen. */
    val user: StateFlow<SessionUser?> = _user.asStateFlow()

    val isLoggedIn: Boolean get() = token != null

    /** CRM address. Fixed by the build (BuildConfig.CRM_URL) unless set on the login screen. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CRM_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    fun save(token: String, user: SessionUser) {
        prefs.edit()
            .putString(KEY_TOKEN, cipher.encrypt(token))
            .putLong(KEY_USER_ID, user.id)
            .putString(KEY_USER_NAME, user.name)
            .putString(KEY_USER_ROLE, user.role)
            .apply()
        this.token = token
        _user.value = user
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_ROLE)
            .apply()
        token = null
        _user.value = null
    }

    private fun loadUser() = SessionUser(
        id = prefs.getLong(KEY_USER_ID, 0),
        name = prefs.getString(KEY_USER_NAME, null).orEmpty(),
        role = prefs.getString(KEY_USER_ROLE, null).orEmpty(),
    )

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_ROLE = "user_role"
        const val KEY_BASE_URL = "base_url"
    }
}

/** AES-256-GCM with a key stored in the Android Keystore. Output: Base64(iv + ciphertext). */
private class TokenCipher(private val alias: String = "saascom_session") {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** null when the data cannot be read (e.g. key wiped after a restore): the user logs in again. */
    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }.getOrNull()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
