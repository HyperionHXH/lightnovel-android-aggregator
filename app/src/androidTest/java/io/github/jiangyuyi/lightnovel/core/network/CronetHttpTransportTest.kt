package io.github.jiangyuyi.lightnovel.core.network

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CronetHttpTransportTest {
    @Test
    fun outerTimeoutCancelsOnlyTheActiveCronetRequest() = runBlocking {
        LocalServer { it == 1 }.use { server ->
            val transport = CronetHttpTransport(testContext())

            // Build and cache the first Cronet engine outside the short timeout under test.
            val warmup = transport.getBytes(server.url)
            assertEquals(200, warmup.code)
            assertEquals("ok", warmup.body.toString(Charsets.UTF_8))

            val startedAt = SystemClock.elapsedRealtime()
            val failure = runCatching {
                withTimeout(750) { transport.getBytes(server.url) }
            }.exceptionOrNull()
            assertTrue("the caller's cancellation must remain observable", failure is TimeoutCancellationException)
            assertTrue("cancellation must be prompt", SystemClock.elapsedRealtime() - startedAt < 3_000)

            awaitCondition { server.stalledRequestsClosed.get() == 1 }
            delay(300)
            assertEquals("warmup plus only one pending request may reach the fixture", 2, server.requestCount.get())
        }
    }

    @Test
    fun apiFallbackCompletesBeforeThePagesTenSecondDeadline() = runBlocking {
        LocalServer { it >= 2 }.use { server ->
            val transport = CronetHttpTransport(testContext())
            val response = withTimeout(10_000) { transport.postJson(server.url, "{}") }
            assertEquals(200, response.code)
            assertEquals("ok", response.body)
            assertEquals(2, server.requestCount.get())
            assertEquals(1, server.stalledRequestsClosed.get())
        }
    }

    @Test
    fun routeTimeoutStillCancelsTheRequestAndRetriesTheNextRoute() = runBlocking {
        LocalServer { it >= 2 }.use { server ->
            val transport = CronetHttpTransport(testContext())

            // Keep the production 12s deadline: no test-only transport API is needed.
            val response = withTimeout(20_000) { transport.getBytes(server.url) }

            assertEquals(200, response.code)
            assertEquals("ok", response.body.toString(Charsets.UTF_8))
            assertEquals("a genuine route timeout must try the next route", 2, server.requestCount.get())
            assertEquals("the timed-out Cronet request must actually close", 1, server.stalledRequestsClosed.get())
            val startedAt = SystemClock.elapsedRealtime()
            val nextResponse = withTimeout(3_000) { transport.getBytes(server.url) }
            assertEquals(200, nextResponse.code)
            assertTrue("a successful connection must remain reusable", SystemClock.elapsedRealtime() - startedAt < 3_000)
        }
    }

    private fun testContext(): Context =
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
        }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(20)
        }
    }

    private class LocalServer(private val responds: (Int) -> Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val serving = AtomicBoolean(true)
        private val activeSocket = AtomicReference<Socket?>()
        val requestCount = AtomicInteger()
        val stalledRequestsClosed = AtomicInteger()
        val url = "http://127.0.0.1:${server.localPort}/fixture"

        private val thread = Thread {
            while (serving.get()) {
                try {
                    server.accept().use { socket ->
                        activeSocket.set(socket)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        if (responds(requestCount.incrementAndGet())) {
                            socket.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                            "Content-Length: 2\r\n" +
                                            "Connection: close\r\n\r\n" +
                                            "ok"
                                    ).toByteArray(Charsets.US_ASCII),
                                )
                                flush()
                            }
                        } else {
                            // Only EOF proves Cronet cancelled; a socket timeout is not success.
                            while (reader.read() != -1) Unit
                            stalledRequestsClosed.incrementAndGet()
                        }
                    }
                } catch (_: IOException) {
                    // Fixture teardown closes accept/read; it must not count as cancellation.
                } finally {
                    activeSocket.set(null)
                }
            }
        }.apply {
            isDaemon = true
            name = "cronet-local-http-fixture"
            start()
        }

        override fun close() {
            serving.set(false)
            activeSocket.getAndSet(null)?.close()
            server.close()
            thread.join(2_000)
        }
    }
}
