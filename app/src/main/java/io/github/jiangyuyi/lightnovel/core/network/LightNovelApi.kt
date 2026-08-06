package io.github.jiangyuyi.lightnovel.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(
    override val message: String,
    val httpCode: Int? = null,
    val businessCode: Int? = null,
) : IOException(message)

class LightNovelApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun post(path: String, body: JsonObject, commentApi: Boolean = false): JsonObject =
        withContext(Dispatchers.IO) {
            val base = if (commentApi) COMMENT_BASE_URL else WEB_BFF_BASE_URL
            val request = Request.Builder()
                .url(base + path.removePrefix("/"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.lightnovel.fun")
                .header("Referer", "https://www.lightnovel.fun/")
                .header("User-Agent", "LightNovel-Android/0.1")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException("服务器返回 ${response.code}", response.code)
                }
                val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                    ?: throw ApiException("服务器返回了无法识别的数据")
                val code = root.int("code")
                if (code != 0) {
                    val message = root.string("message", "msg", "error").ifBlank { "请求失败（$code）" }
                    throw ApiException(message, response.code, code)
                }
                root.obj("data", "d") ?: buildJsonObject {
                    root.forEach { (key, value) -> if (key !in ENVELOPE_KEYS) put(key, value) }
                }
            }
        }

    companion object {
        const val WEB_BFF_BASE_URL = "https://www.lightnovel.fun/api/pc-proxy/"
        const val COMMENT_BASE_URL = "https://api.lightnovel.fun/pc-comment-proxy/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ENVELOPE_KEYS = setOf("code", "message", "msg", "t")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun jsonBody(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is String -> put(key, JsonPrimitive(value))
            is Number -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            is JsonObject -> put(key, value)
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}

