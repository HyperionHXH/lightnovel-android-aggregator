package io.github.jiangyuyi.lightnovel.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jiangyuyi.lightnovel.core.model.Session
import io.github.jiangyuyi.lightnovel.core.model.UserSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(readStoredSession())
    val session: StateFlow<Session> = _session.asStateFlow()

    fun securityKey(): String = _session.value.securityKey

    fun save(session: Session) {
        require(session.securityKey.isNotBlank()) { "securityKey must not be blank" }
        val encrypted = encrypt(session.securityKey)
        preferences.edit()
            .putString(KEY_TOKEN, encrypted.payload)
            .putString(KEY_IV, encrypted.iv)
            .putLong(KEY_UID, session.uid)
            .putString(KEY_NICKNAME, session.user?.nickname.orEmpty())
            .putString(KEY_AVATAR, session.user?.avatarUrl.orEmpty())
            .apply()
        _session.value = session.copy(loggedIn = true)
    }

    fun clear() {
        preferences.edit().clear().apply()
        _session.value = Session()
    }

    private fun readStoredSession(): Session {
        val payload = preferences.getString(KEY_TOKEN, null) ?: return Session()
        val iv = preferences.getString(KEY_IV, null) ?: return Session()
        val token = runCatching { decrypt(payload, iv) }.getOrElse {
            preferences.edit().clear().apply()
            return Session()
        }
        if (token.isBlank()) return Session()
        val uid = preferences.getLong(KEY_UID, 0)
        val nickname = preferences.getString(KEY_NICKNAME, "").orEmpty()
        val avatar = preferences.getString(KEY_AVATAR, "").orEmpty().ifBlank { null }
        return Session(
            loggedIn = true,
            securityKey = token,
            uid = uid,
            user = if (uid > 0 || nickname.isNotBlank()) UserSummary(uid, nickname.ifBlank { "用户$uid" }, avatar) else null,
        )
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedValue(
            payload = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decrypt(payload: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
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
                    .build(),
            )
            generateKey()
        }
    }

    private data class EncryptedValue(val payload: String, val iv: String)

    private companion object {
        const val PREFERENCES_NAME = "secure_session"
        const val KEY_ALIAS = "lightnovel_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_TOKEN = "token"
        const val KEY_IV = "iv"
        const val KEY_UID = "uid"
        const val KEY_NICKNAME = "nickname"
        const val KEY_AVATAR = "avatar"
    }
}

