package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal const val LIGHT_NOVEL_SHELF_API_ORIGIN = "https://api.lightnovel.life"
internal const val LIGHT_NOVEL_SHELF_HUB_URL = "$LIGHT_NOVEL_SHELF_API_ORIGIN/hub/api"

internal data class ShelfTokens(
    val accessToken: String,
    val refreshToken: String,
) {
    init {
        require(accessToken.isNotBlank()) { "access token must not be blank" }
        require(refreshToken.isNotBlank()) { "refresh token must not be blank" }
    }
}

internal interface ShelfTokenStore {
    fun read(): ShelfTokens?
    fun save(tokens: ShelfTokens)
    fun clear()
}

internal class AndroidShelfTokenStore(context: Context) : ShelfTokenStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): ShelfTokens? {
        val access = decryptStored(KEY_ACCESS_TOKEN) ?: return null
        val refresh = decryptStored(KEY_REFRESH_TOKEN) ?: return null
        return runCatching { ShelfTokens(access, refresh) }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    override fun save(tokens: ShelfTokens) {
        val access = encrypt(tokens.accessToken)
        val refresh = encrypt(tokens.refreshToken)
        preferences.edit()
            .putString("${KEY_ACCESS_TOKEN}_payload", access.payload)
            .putString("${KEY_ACCESS_TOKEN}_iv", access.iv)
            .putString("${KEY_REFRESH_TOKEN}_payload", refresh.payload)
            .putString("${KEY_REFRESH_TOKEN}_iv", refresh.iv)
            .apply()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun decryptStored(key: String): String? {
        val payload = preferences.getString("${key}_payload", null) ?: return null
        val iv = preferences.getString("${key}_iv", null) ?: return null
        return runCatching { decrypt(payload, iv) }.getOrElse {
            clear()
            null
        }
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
        const val PREFERENCES_NAME = "credentials_lightnovelshelf"
        const val KEY_ALIAS = "credentials_lightnovelshelf_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

internal data class ShelfHttpResponse(
    val code: Int,
    val body: String,
)

internal fun interface ShelfHttpTransport {
    suspend fun postJson(url: String, body: String): ShelfHttpResponse
}

internal class OkHttpShelfHttpTransport(
    private val client: OkHttpClient,
) : ShelfHttpTransport {
    override suspend fun postJson(url: String, body: String): ShelfHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) {
                            continuation.resume(ShelfHttpResponse(it.code, it.body?.string().orEmpty()))
                        }
                    }
                }
            })
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal class LightNovelShelfAuthApi(
    private val transport: ShelfHttpTransport,
    private val limiter: ShelfRateLimiter,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val apiOrigin: String = LIGHT_NOVEL_SHELF_API_ORIGIN,
) {
    suspend fun login(email: String, password: String): ShelfTokens {
        val normalizedEmail = email.trim()
        require(normalizedEmail.isNotBlank()) { "email must not be blank" }
        require(password.isNotEmpty()) { "password must not be blank" }
        val body = buildJsonObject {
            put("email", normalizedEmail)
            put("password", sha256Hex(password))
        }
        val root = post("/api/user/login", body, authStatuses = setOf(401))
        val response = root.objectValue("Response", "response") ?: root
        return ShelfTokens(
            accessToken = response.requiredString("Token", "token"),
            refreshToken = response.requiredString("RefreshToken", "refreshToken"),
        )
    }

    suspend fun refresh(refreshToken: String): String {
        require(refreshToken.isNotBlank()) { "refresh token must not be blank" }
        val root = post(
            path = "/api/user/refresh_token",
            body = buildJsonObject { put("token", refreshToken) },
            authStatuses = setOf(401, 404),
        )
        val response = root.value("Response", "response", "Token", "token")
        return when (response) {
            is JsonPrimitive -> response.contentOrNull
            is JsonObject -> response.optionalString("Token", "token")
            else -> null
        }?.takeIf(String::isNotBlank)
            ?: throw SourceException(SourceErrorKind.PARSING, "轻书架刷新响应缺少会话令牌")
    }

    private suspend fun post(path: String, body: JsonObject, authStatuses: Set<Int>): JsonObject {
        val response = try {
            limiter.run {
                transport.postJson(apiOrigin.trimEnd('/') + path, body.toString())
            }
        } catch (error: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "无法连接轻书架", error)
        }
        val root = response.body.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
        if (response.code !in 200..299) {
            val kind = when (response.code) {
                in authStatuses -> SourceErrorKind.AUTHENTICATION
                429 -> SourceErrorKind.RATE_LIMITED
                in 500..599 -> SourceErrorKind.SERVER
                else -> SourceErrorKind.NETWORK
            }
            val message = root?.optionalString("Msg", "msg", "message")
                ?: "轻书架服务器返回 ${response.code}"
            throw SourceException(kind, message)
        }
        root ?: throw SourceException(SourceErrorKind.PARSING, "轻书架返回了无法识别的数据")
        root.throwIfFailed()
        return root
    }
}

internal class LightNovelShelfAuthManager(
    private val api: LightNovelShelfAuthApi,
    private val tokenStore: ShelfTokenStore,
) {
    private val mutex = Mutex()

    fun accessToken(): String? = tokenStore.read()?.accessToken

    suspend fun login(email: String, password: String): Boolean = mutex.withLock {
        val newTokens = api.login(email, password)
        tokenStore.save(newTokens)
        true
    }

    suspend fun restore(): Boolean = mutex.withLock {
        refreshLocked(clearInvalidSession = true)
    }

    suspend fun refresh(): Boolean = mutex.withLock {
        refreshLocked(clearInvalidSession = true)
    }

    suspend fun logout() = mutex.withLock {
        tokenStore.clear()
    }

    private suspend fun refreshLocked(clearInvalidSession: Boolean): Boolean {
        val current = tokenStore.read() ?: return false
        return try {
            val accessToken = api.refresh(current.refreshToken)
            tokenStore.save(current.copy(accessToken = accessToken))
            true
        } catch (error: SourceException) {
            if (clearInvalidSession && error.kind == SourceErrorKind.AUTHENTICATION) {
                tokenStore.clear()
                false
            } else {
                throw error
            }
        }
    }
}

internal fun sha256Hex(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun JsonObject.throwIfFailed() {
    val success = value("Success", "success")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    if (success != false) return
    val status = value("Status", "status")?.jsonPrimitive?.intOrNull
    val kind = if (status == 401 || status == -100) {
        SourceErrorKind.AUTHENTICATION
    } else {
        SourceErrorKind.SERVER
    }
    throw SourceException(
        kind,
        optionalString("Msg", "msg") ?: "轻书架请求失败",
    )
}

internal fun JsonObject.value(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull(::get)

internal fun JsonObject.objectValue(vararg keys: String): JsonObject? = value(*keys) as? JsonObject

internal fun JsonObject.optionalString(vararg keys: String): String? =
    (value(*keys) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun JsonObject.requiredString(vararg keys: String): String =
    optionalString(*keys) ?: throw SourceException(
        SourceErrorKind.PARSING,
        "轻书架响应缺少 ${keys.firstOrNull().orEmpty()} 字段",
    )
