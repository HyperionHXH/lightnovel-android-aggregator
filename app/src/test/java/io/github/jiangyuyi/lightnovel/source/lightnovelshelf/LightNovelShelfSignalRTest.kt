package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LightNovelShelfSignalRTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `websocket performs json handshake and correlates invocation result`() = runBlocking {
        val received = mutableListOf<JsonObject>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = text.removeSuffix("\u001e")
                    val message = Json.parseToJsonElement(frame) as JsonObject
                    if (message["protocol"] != null) {
                        webSocket.send("{}\u001e")
                        return
                    }
                    received += message
                    val id = message["invocationId"]?.jsonPrimitive?.content
                    webSocket.send(
                        """{"type":3,"invocationId":"$id","result":{"answer":42}}""" + '\u001e',
                    )
                }
            }),
        )
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val connection = OkHttpShelfSignalRConnection(
            client = client,
            accessToken = { "secret-token" },
            hubUrl = server.url("/hub/api").toString(),
            handshakeTimeoutMillis = 5_000,
            invocationTimeoutMillis = 5_000,
        )

        val result = connection.invoke("GetBookList", buildJsonObject { put("Page", 1) }) as JsonObject
        val request = server.takeRequest(5, TimeUnit.SECONDS)

        assertEquals(42, result["answer"]?.jsonPrimitive?.content?.toInt())
        assertEquals("GetBookList", received.single()["target"]?.jsonPrimitive?.content)
        assertEquals("secret-token", request?.requestUrl?.queryParameter("access_token"))
        assertEquals("Bearer secret-token", request?.getHeader("Authorization"))
        assertTrue(received.single()["arguments"].toString().contains("UseGzip"))
        connection.reset()
        client.dispatcher.executorService.shutdown()
    }
    @Test
    fun `caller timeout does not retry a pending invocation on the fallback`() = runBlocking {
        val invoked = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text.removeSuffix("\u001e")) as JsonObject
                if (message["protocol"] != null) webSocket.send("{}\u001e")
                else invoked.complete(Unit)
            }
        }))
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val primary = OkHttpShelfSignalRConnection(
            client = client,
            accessToken = { null },
            hubUrl = server.url("/hub/api").toString(),
        )
        val fallbackCalls = AtomicInteger()
        val fallback = object : ShelfHubConnection {
            override suspend fun invoke(target: String, params: JsonElement): JsonElement {
                fallbackCalls.incrementAndGet()
                return JsonNull
            }
            override fun reset() = Unit
        }
        try {
            val error = runCatching {
                withTimeout(1_000) {
                    FallbackShelfHubConnection(primary, fallback).invoke("GetBookList", JsonNull)
                }
            }.exceptionOrNull()
            assertTrue("fixture must receive the invocation before the timeout", invoked.isCompleted)
            assertTrue("caller cancellation must remain observable", error is TimeoutCancellationException)
            assertEquals("cancelled reads must not be replayed", 0, fallbackCalls.get())
        } finally {
            primary.reset()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    @Test
    fun `caller timeout during handshake closes the socket without fallback`() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                opened.complete(Unit)
                // Deliberately leave the SignalR handshake pending.
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        }))
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val primary = OkHttpShelfSignalRConnection(
            client = client,
            accessToken = { null },
            hubUrl = server.url("/hub/api").toString(),
        )
        val fallbackCalls = AtomicInteger()
        val fallback = object : ShelfHubConnection {
            override suspend fun invoke(target: String, params: JsonElement): JsonElement {
                fallbackCalls.incrementAndGet()
                return JsonNull
            }
            override fun reset() = Unit
        }
        try {
            val error = runCatching {
                withTimeout(1_000) {
                    FallbackShelfHubConnection(primary, fallback).invoke("GetBookList", JsonNull)
                }
            }.exceptionOrNull()
            assertTrue("fixture must receive the handshake", opened.isCompleted)
            assertTrue("caller cancellation must remain observable", error is TimeoutCancellationException)
            assertEquals(0, fallbackCalls.get())
            withTimeout(2_000) { closed.await() }
        } finally {
            primary.reset()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }


}
