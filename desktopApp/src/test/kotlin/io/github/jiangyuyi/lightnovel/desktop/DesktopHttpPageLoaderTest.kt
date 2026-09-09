package io.github.jiangyuyi.lightnovel.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopHttpPageLoaderTest {
    @Test
    fun `kingdom catalog response is parsed with pagination metadata`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        var requestPath = ""
        var requestBody = ""
        server.createContext("/") { exchange ->
            requestPath = exchange.requestURI.path
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val body = """
                {"code":0,"data":{"items":[
                  {"id":101,"title":"测试书","author":"测试作者","cover_url":"https://cover.test/a.jpg"}
                ],"pagination":{"page":2,"total":41,"page_size":20,"has_next":true}}}
            """.trimIndent()
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val loader = DesktopHttpPageLoader(
                apiOrigin = "http://127.0.0.1:${server.address.port}/api/pc-proxy/",
            )
            val result = loader.load("light_novel_kingdom", "热门", "", page = 2, pageSize = 20)

            assertEquals("/api/pc-proxy/api/bff/home-feed-v1", requestPath)
            assertTrue(requestBody.contains("\"page\":2"))
            assertEquals(2, result.page)
            assertEquals(41, result.total)
            assertTrue(result.hasMore)
            assertEquals("测试书", result.items.single().title)
            assertEquals("101", result.items.single().remoteId)
            assertEquals("测试作者", result.items.single().author)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
