package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightNovelShelfProtocolTest {
    @Test
    fun `gzip response is decompressed and parsed`() {
        val payload = """{"Page":1,"TotalPages":2,"Data":[]}"""
        val envelope = Json.parseToJsonElement(
            """{"Success":true,"Response":"${gzipBase64(payload)}"}""",
        )

        val result = ShelfResponseDecoder().unwrap(envelope) as JsonObject

        assertEquals(2, result["TotalPages"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `search sends documented method and maps response`() = runTest {
        val legacyCover =
            "https://img.example/7.webp?placeholder=LEHV6nWB2yk8pyo0adR*#.7kCMdnj&t=signed-token"
        val hub = RecordingHub(
            responses = ArrayDeque(
                listOf(
                    envelope(
                        """{"Page":2,"TotalPages":3,"Data":[{"Id":7,"Title":"书名","Cover":"$legacyCover","UserName":"作者"}]}""",
                    ),
                ),
            ),
        )
        val gateway = gateway(hub)

        val result = gateway.search("关键字", page = 2, pageSize = 20)

        assertEquals("GetBookList", hub.calls.single().first)
        assertEquals("关键字", (hub.calls.single().second as JsonObject)["KeyWords"]?.jsonPrimitive?.content)
        assertEquals(7, result.items.single().id)
        assertEquals("作者", result.items.single().authorName)
        assertEquals(
            "https://img.example/7.webp?placeholder=LEHV6nWB2yk8pyo0adR*%23.7kCMdnj&t=signed-token",
            result.items.single().coverUrl,
        )
    }

    @Test
    fun `cover normalization changes only raw hash inside placeholder`() {
        val encoded = "https://img.example/7.webp?placeholder=L%23abc&t=signed-token"
        val unrelatedFragment = "https://img.example/7.webp?t=signed-token#preview"

        assertEquals(encoded, normalizeShelfCoverUrl(encoded))
        assertEquals(unrelatedFragment, normalizeShelfCoverUrl(unrelatedFragment))
        assertNull(normalizeShelfCoverUrl(""))
        assertNull(normalizeShelfCoverUrl(null))
    }

    @Test
    fun `novel content preserves chapter font url`() = runTest {
        val hub = RecordingHub(
            responses = ArrayDeque(
                listOf(
                    envelope(
                        """{"Chapter":{"Id":30,"BookId":7,"Title":"第三章","Content":"<p>正文</p>","Font":"/fonts/chapter.woff2","SortNum":3,"Chapters":["一","二","三"]}}""",
                    ),
                ),
            ),
        )

        val result = gateway(hub).getNovelContent(bookId = 7, sortNumber = 3)

        assertEquals("/fonts/chapter.woff2", result.fontUrl)
    }

    @Test
    fun `remote shelf keeps folders order and version`() = runTest {
        val hub = RecordingHub(
            responses = ArrayDeque(
                listOf(
                    envelope(
                        """{"Data":[{"Type":"FOLDER","Id":"folder-1","Index":0,"Parents":[],"Title":"收藏"},{"Type":"BOOK","Id":7,"Index":0,"Parents":["folder-1"]}],"Ver":"20220211"}""",
                    ),
                    envelope("{}"),
                ),
            ),
        )
        val gateway = gateway(hub)

        val shelf = gateway.getShelf()
        gateway.saveShelf(shelf)

        assertEquals("GetBookShelf", hub.calls.first().first)
        assertEquals(JsonNull, hub.calls.first().second)
        assertEquals("20220211", shelf.version)
        assertEquals(listOf(ShelfRemoteItemType.FOLDER, ShelfRemoteItemType.BOOK), shelf.items.map { it.type })
        assertEquals(listOf("folder-1"), shelf.items.last().parents)
        val saved = hub.calls.last().second as JsonObject
        assertEquals("20220211", saved["ver"]?.jsonPrimitive?.content)
        assertEquals(2, (saved["data"] as JsonArray).size)
    }

    @Test
    fun `book ids are sent through the shelf batch endpoint`() = runTest {
        val hub = RecordingHub(
            responses = ArrayDeque(
                listOf(envelope("""[{"Id":7,"Title":"书名","UserName":"作者"}]""")),
            ),
        )
        val gateway = gateway(hub)

        val books = gateway.getBooksByIds(listOf(7, 7))

        assertEquals("GetBookListByIds", hub.calls.single().first)
        assertEquals(1, ((hub.calls.single().second as JsonObject)["Ids"] as JsonArray).size)
        assertEquals("书名", books.single().title)
    }

    @Test
    fun `reading history keeps novel id order and removes duplicates`() = runTest {
        val hub = RecordingHub(
            responses = ArrayDeque(
                listOf(envelope("""{"Novel":[9,7,9,3],"Comic":[]}""")),
            ),
        )

        val ids = gateway(hub).getReadHistory()

        assertEquals("GetReadHistory", hub.calls.single().first)
        assertEquals(JsonNull, hub.calls.single().second)
        assertEquals(listOf(9L, 7L, 3L), ids)
    }

    @Test
    fun `authentication failure refreshes token and retries once`() = runTest {
        val store = InMemoryShelfTokenStore(ShelfTokens("old-access", "refresh"))
        val auth = LightNovelShelfAuthManager(
            api = LightNovelShelfAuthApi(
                transport = ShelfHttpTransport { _, _ ->
                    ShelfHttpResponse(200, """{"Success":true,"Response":"new-access"}""")
                },
                limiter = ShelfRateLimiter(),
                apiOrigin = "https://test.invalid",
            ),
            tokenStore = store,
        )
        val hub = RecordingHub(
            responses = ArrayDeque(
                listOf(
                    SourceException(SourceErrorKind.AUTHENTICATION, "401"),
                    envelope("""{"Page":1,"TotalPages":1,"Data":[]}"""),
                ),
            ),
        )
        val gateway = DefaultLightNovelShelfGateway(auth, hub, ShelfRateLimiter())

        gateway.search("test", 1, 20)

        assertEquals("new-access", store.read()?.accessToken)
        assertEquals(2, hub.calls.size)
        assertEquals(1, hub.resetCount)
    }

    private fun gateway(hub: RecordingHub): DefaultLightNovelShelfGateway {
        val auth = LightNovelShelfAuthManager(
            api = LightNovelShelfAuthApi(
                transport = ShelfHttpTransport { _, _ -> error("unexpected auth request") },
                limiter = ShelfRateLimiter(),
                apiOrigin = "https://test.invalid",
            ),
            tokenStore = InMemoryShelfTokenStore(),
        )
        return DefaultLightNovelShelfGateway(auth, hub, ShelfRateLimiter())
    }

    private fun envelope(responseJson: String): JsonElement = Json.parseToJsonElement(
        """{"Success":true,"Response":$responseJson}""",
    )

    private fun gzipBase64(value: String): String {
        val bytes = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(value.toByteArray()) }
            output.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private class RecordingHub(
        private val responses: ArrayDeque<Any>,
    ) : ShelfHubConnection {
        val calls = mutableListOf<Pair<String, JsonElement>>()
        var resetCount = 0

        override suspend fun invoke(target: String, params: JsonElement): JsonElement {
            calls += target to params
            val response = responses.removeFirst()
            if (response is Throwable) throw response
            return response as JsonElement
        }

        override fun reset() {
            resetCount += 1
        }
    }
}
