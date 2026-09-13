package io.github.jiangyuyi.lightnovel.core.network

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.UUID

class ApiException(
    override val message: String,
    val httpCode: Int? = null,
    val businessCode: Int? = null,
) : IOException(message)

class LightNovelApi internal constructor(
    context: Context,
    private val transport: HttpTransport = CronetHttpTransport(context),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    internal suspend fun getBytes(url: String): ByteArray {
        val response = transport.getBytes(url)
        if (response.code !in 200..299) throw ApiException("图片服务器返回 ${response.code}", response.code)
        return response.body
    }

    suspend fun post(path: String, body: JsonObject, commentApi: Boolean = false): JsonObject =
        run {
            val base = if (commentApi) COMMENT_BASE_URL else WEB_BFF_BASE_URL
            val response = transport.postJson(base + path.removePrefix("/"), body.toString())
            parseResponse(response)
        }

    suspend fun postMultipart(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
    ): JsonObject {
        val boundary = "----MixnComment-${UUID.randomUUID()}"
        val line = "\r\n"
        val body = ByteArrayOutputStream()
        fun writeText(value: String) = body.write(value.toByteArray(Charsets.UTF_8))
        fields.forEach { (name, value) ->
            writeText("--$boundary$line")
            writeText("Content-Disposition: form-data; name=\"$name\"$line$line")
            writeText(value)
            writeText(line)
        }
        writeText("--$boundary$line")
        writeText("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$line")
        writeText("Content-Type: $mimeType$line$line")
        body.write(fileBytes)
        writeText(line)
        writeText("--$boundary--$line")
        val response = transport.postMultipart(
            WEB_BFF_BASE_URL + path.removePrefix("/"),
            body.toByteArray(),
            "multipart/form-data; boundary=$boundary",
        )
        return parseResponse(response)
    }

    private fun parseResponse(response: HttpResponse): JsonObject {
            val raw = response.body
            if (response.code !in 200..299) {
                throw ApiException("服务器返回 ${response.code}", response.code)
            }
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: throw ApiException("服务器返回了无法识别的数据")
            val code = root.int("code")
            if (code != 0) {
                val message = root.string("message", "msg", "error").ifBlank { "请求失败（$code）" }
                throw ApiException(message, response.code, code)
            }
            return root.obj("data", "d") ?: buildJsonObject {
                root.forEach { (key, value) -> if (key !in ENVELOPE_KEYS) put(key, value) }
            }
    }

    companion object {
        const val WEB_BFF_BASE_URL = "https://www.lightnovel.fun/api/pc-proxy/"
        const val COMMENT_BASE_URL = "https://api.lightnovel.fun/pc-comment-proxy/"
        private val ENVELOPE_KEYS = setOf("code", "message", "msg", "t")
    }
}

internal fun jsonBody(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is String -> put(key, JsonPrimitive(value))
            is Number -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            // Preserve structured values such as mention UID arrays. Falling
            // back to `toString()` would send them as a JSON string instead of
            // the array expected by the official comment endpoint.
            is JsonElement -> put(key, value)
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}
