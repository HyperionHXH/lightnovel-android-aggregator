package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightNovelShelfAuthTest {
    @Test
    fun `password hash uses lowercase sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    @Test
    fun `login hashes password before transport and decodes tokens`() = runTest {
        var capturedUrl = ""
        var capturedBody = ""
        val api = authApi { url, body ->
            capturedUrl = url
            capturedBody = body
            ShelfHttpResponse(
                200,
                """{"Success":true,"Response":{"Token":"access","RefreshToken":"refresh"}}""",
            )
        }

        val tokens = api.login(" user@example.com ", "plain-password")
        val request = Json.parseToJsonElement(capturedBody).jsonObject

        assertEquals("https://test.invalid/api/user/login", capturedUrl)
        assertEquals("user@example.com", request["email"]?.jsonPrimitive?.content)
        assertEquals(sha256Hex("plain-password"), request["password"]?.jsonPrimitive?.content)
        assertFalse(capturedBody.contains("plain-password"))
        assertEquals(ShelfTokens("access", "refresh"), tokens)
    }

    @Test
    fun `invalid refresh clears stored tokens`() = runTest {
        val store = InMemoryShelfTokenStore(ShelfTokens("old-access", "expired-refresh"))
        val manager = LightNovelShelfAuthManager(
            api = authApi { _, _ -> ShelfHttpResponse(404, """{"Success":false,"Msg":"expired"}""") },
            tokenStore = store,
        )

        assertFalse(manager.restore())
        assertNull(store.read())
    }

    @Test
    fun `server refresh failure keeps stored tokens`() = runTest {
        val original = ShelfTokens("old-access", "refresh")
        val store = InMemoryShelfTokenStore(original)
        val manager = LightNovelShelfAuthManager(
            api = authApi { _, _ -> ShelfHttpResponse(503, """{"Success":false,"Msg":"maintenance"}""") },
            tokenStore = store,
        )

        val error = runCatching { manager.restore() }.exceptionOrNull()

        assertTrue(error is SourceException)
        assertEquals(SourceErrorKind.SERVER, (error as SourceException).kind)
        assertEquals(original, store.read())
    }

    @Test
    fun `failed login does not destroy previous valid session`() = runTest {
        val original = ShelfTokens("old-access", "old-refresh")
        val store = InMemoryShelfTokenStore(original)
        val manager = LightNovelShelfAuthManager(
            api = authApi { _, _ -> ShelfHttpResponse(401, """{"Success":false,"Msg":"wrong password"}""") },
            tokenStore = store,
        )

        val error = runCatching { manager.login("user@example.com", "wrong") }.exceptionOrNull()

        assertTrue(error is SourceException)
        assertEquals(SourceErrorKind.AUTHENTICATION, (error as SourceException).kind)
        assertEquals(original, store.read())
    }

    private fun authApi(handler: suspend (String, String) -> ShelfHttpResponse) = LightNovelShelfAuthApi(
        transport = ShelfHttpTransport(handler),
        limiter = ShelfRateLimiter(),
        apiOrigin = "https://test.invalid",
    )
}

internal class InMemoryShelfTokenStore(
    initial: ShelfTokens? = null,
) : ShelfTokenStore {
    private var tokens = initial

    override fun read(): ShelfTokens? = tokens
    override fun save(tokens: ShelfTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}
