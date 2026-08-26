package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LightNovelShelfLiveSmokeTest {
    @Test
    fun `live service accepts handshake and requires search authentication`() = runBlocking {
        assumeTrue(System.getenv("RUN_LNS_SMOKE") == "true")
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        val limiter = ShelfRateLimiter()
        val auth = LightNovelShelfAuthManager(
            api = LightNovelShelfAuthApi(
                transport = OkHttpShelfHttpTransport(client),
                limiter = limiter,
            ),
            tokenStore = InMemoryShelfTokenStore(),
        )
        val hub = OkHttpShelfSignalRConnection(client, auth::accessToken)
        val gateway = DefaultLightNovelShelfGateway(auth, hub, limiter)
        try {
            val error = runCatching {
                gateway.search("刀剑神域", page = 1, pageSize = 5)
            }.exceptionOrNull()
            assertTrue(error is SourceException)
            assertTrue((error as SourceException).kind == SourceErrorKind.AUTHENTICATION)
        } finally {
            hub.reset()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
