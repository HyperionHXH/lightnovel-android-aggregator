package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString

private const val RECORD_SEPARATOR = '\u001e'

internal class ShelfRateLimiter(
    private val maxRequests: Int = 9,
    private val windowMillis: Long = 5_500,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()

    init {
        require(maxRequests > 0) { "max requests must be positive" }
        require(windowMillis > 0) { "rate limit window must be positive" }
    }

    suspend fun <T> run(operation: suspend () -> T): T {
        acquire()
        return operation()
    }

    private suspend fun acquire() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = nowMillis()
                while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMillis) {
                    timestamps.removeFirst()
                }
                if (timestamps.size < maxRequests) {
                    timestamps.addLast(now)
                    0L
                } else {
                    (windowMillis - (now - timestamps.first())).coerceAtLeast(1)
                }
            }
            if (waitMillis == 0L) return
            sleep(waitMillis)
        }
    }
}

internal interface ShelfHubConnection {
    suspend fun invoke(target: String, params: JsonElement): JsonElement
    fun reset()
}

internal class OkHttpShelfSignalRConnection(
    private val client: OkHttpClient,
    private val accessToken: () -> String?,
    private val hubUrl: String = LIGHT_NOVEL_SHELF_HUB_URL,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val handshakeTimeoutMillis: Long = 15_000,
    private val invocationTimeoutMillis: Long = 30_000,
) : ShelfHubConnection {
    private val connectionMutex = Mutex()
    private val stateLock = Any()
    private val frameBuffer = StringBuilder()
    private val invocationIds = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    private var socket: WebSocket? = null
    private var handshake: CompletableDeferred<Unit>? = null
    private var connected = false

    override suspend fun invoke(target: String, params: JsonElement): JsonElement {
        connect()
        val id = invocationIds.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        val message = buildJsonObject {
            put("type", 1)
            put("invocationId", id)
            put("target", target)
            put(
                "arguments",
                JsonArray(
                    listOf(
                        params,
                        buildJsonObject { put("UseGzip", true) },
                    ),
                ),
            )
        }
        val activeSocket = synchronized(stateLock) { socket.takeIf { connected } }
            ?: throw SourceException(SourceErrorKind.NETWORK, "轻书架连接尚未建立")
        if (!activeSocket.send(message.toString() + RECORD_SEPARATOR)) {
            pending.remove(id)
            throw SourceException(SourceErrorKind.NETWORK, "无法向轻书架发送请求")
        }
        return try {
            withTimeout(invocationTimeoutMillis) { result.await() }
        } catch (error: TimeoutCancellationException) {
            throw SourceException(SourceErrorKind.NETWORK, "轻书架请求超时", error)
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun connect() {
        if (synchronized(stateLock) { connected }) return
        connectionMutex.withLock {
            if (synchronized(stateLock) { connected }) return
            val ready = CompletableDeferred<Unit>()
            val token = accessToken().orEmpty()
            val url = hubUrl.toHttpUrl().newBuilder().apply {
                if (token.isNotBlank()) addQueryParameter("access_token", token)
            }.build()
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (token.isNotBlank()) header("Authorization", "Bearer $token")
                }
                .build()
            val listener = Listener(ready)
            val newSocket = client.newWebSocket(request, listener)
            synchronized(stateLock) {
                socket = newSocket
                handshake = ready
                connected = false
                frameBuffer.clear()
            }
            try {
                withTimeout(handshakeTimeoutMillis) { ready.await() }
            } catch (error: TimeoutCancellationException) {
                reset()
                throw SourceException(SourceErrorKind.NETWORK, "连接轻书架超时", error)
            } catch (error: SourceException) {
                reset()
                throw error
            }
        }
    }

    override fun reset() {
        val activeSocket: WebSocket?
        val activeHandshake: CompletableDeferred<Unit>?
        synchronized(stateLock) {
            activeSocket = socket
            activeHandshake = handshake
            socket = null
            handshake = null
            connected = false
            frameBuffer.clear()
        }
        activeSocket?.cancel()
        val error = SourceException(SourceErrorKind.NETWORK, "轻书架连接已重置")
        if (activeHandshake?.isCompleted == false) activeHandshake.completeExceptionally(error)
        failPending(error)
    }

    private fun handlePayload(payload: String) {
        val frames = synchronized(stateLock) {
            frameBuffer.append(payload)
            buildList {
                while (true) {
                    val separator = frameBuffer.indexOf(RECORD_SEPARATOR.toString())
                    if (separator < 0) break
                    add(frameBuffer.substring(0, separator))
                    frameBuffer.delete(0, separator + 1)
                }
            }
        }
        frames.filter(String::isNotBlank).forEach(::handleFrame)
    }

    private fun handleFrame(frame: String) {
        val message = runCatching { json.parseToJsonElement(frame) as? JsonObject }.getOrNull() ?: return
        val activeHandshake = synchronized(stateLock) { handshake }
        if (activeHandshake != null && !activeHandshake.isCompleted) {
            val error = message.optionalString("error")
            if (error != null) {
                activeHandshake.completeExceptionally(error.toHubException())
            } else {
                synchronized(stateLock) { connected = true }
                activeHandshake.complete(Unit)
            }
            return
        }
        when (message["type"]?.jsonPrimitive?.intOrNull) {
            3 -> completeInvocation(message)
            7 -> {
                val reason = message.optionalString("error") ?: "轻书架关闭了连接"
                failConnection(reason.toHubException())
            }
        }
    }

    private fun completeInvocation(message: JsonObject) {
        val id = message.optionalString("invocationId") ?: return
        val deferred = pending.remove(id) ?: return
        val error = message.optionalString("error")
        if (error != null) {
            deferred.completeExceptionally(error.toHubException())
        } else {
            deferred.complete(message["result"] ?: JsonNull)
        }
    }

    private fun failConnection(error: SourceException, failedSocket: WebSocket? = null) {
        val activeHandshake: CompletableDeferred<Unit>?
        synchronized(stateLock) {
            if (failedSocket != null && socket !== failedSocket) return
            socket = null
            connected = false
            activeHandshake = handshake
            handshake = null
            frameBuffer.clear()
        }
        if (activeHandshake?.isCompleted == false) activeHandshake.completeExceptionally(error)
        failPending(error)
    }

    private fun failPending(error: SourceException) {
        pending.values.forEach { deferred ->
            if (!deferred.isCompleted) deferred.completeExceptionally(error)
        }
        pending.clear()
    }

    private inner class Listener(
        private val ready: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val handshakeFrame = buildJsonObject {
                put("protocol", "json")
                put("version", 1)
            }.toString() + RECORD_SEPARATOR
            if (!webSocket.send(handshakeFrame) && !ready.isCompleted) {
                ready.completeExceptionally(SourceException(SourceErrorKind.NETWORK, "无法开始轻书架握手"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handlePayload(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handlePayload(bytes.utf8())

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            failConnection(SourceException(SourceErrorKind.NETWORK, reason.ifBlank { "轻书架连接已关闭" }), webSocket)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            failConnection(SourceException(SourceErrorKind.NETWORK, "轻书架连接失败", error), webSocket)
        }
    }
}

private fun String.toHubException(): SourceException {
    val lower = lowercase()
    val kind = if ("unauthorized" in lower || "401" in lower) {
        SourceErrorKind.AUTHENTICATION
    } else {
        SourceErrorKind.SERVER
    }
    return SourceException(kind, this)
}
