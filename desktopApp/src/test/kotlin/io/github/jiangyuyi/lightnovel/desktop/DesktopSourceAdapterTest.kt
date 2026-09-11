package io.github.jiangyuyi.lightnovel.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSourceAdapterTest {
    @Test
    fun `shelf catalog reads plural chapters and non contiguous sort numbers`() {
        val source = LightNovelShelfDesktopSource()
        val book = Json.parseToJsonElement(
            """{"Chapters":[{"Id":1,"Title":"第一章","SortNum":2},{"Id":2,"Title":"第二章","SortNum":5},{"Id":3,"Title":"第三章","SortNum":9}]}""",
        ) as JsonObject

        val chapters = source.parseShelfChapters(book)

        assertEquals(listOf(2, 5, 9), chapters.map { it.sortNumber })
        assertEquals(listOf("第一章", "第二章", "第三章"), chapters.map { it.title })
    }

    @Test
    fun `shelf catalog falls back to legacy chapter field and avoids sort collisions`() {
        val source = LightNovelShelfDesktopSource()
        val book = Json.parseToJsonElement(
            """{"Chapters":null,"Chapter":[{"Id":1,"Title":"显式","SortNum":2},{"Id":2,"Title":"回退"}]}""",
        ) as JsonObject

        val chapters = source.parseShelfChapters(book)

        assertEquals(listOf(2, 3), chapters.map { it.sortNumber })
    }

    @Test
    fun `signalr converts http hub urls to websocket schemes`() {
        assertEquals("wss://api.example.test/hub/api?access_token=a%2Bb", desktopSignalRWebSocketUrl("https://api.example.test/hub/api?access_token=a%2Bb"))
        assertEquals("ws://127.0.0.1/hub", desktopSignalRWebSocketUrl("http://127.0.0.1/hub"))
    }

    @Test
    fun `kingdom adapter maps catalog details chapters and session`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val requests = mutableListOf<String>()
        server.createContext("/api/pc-proxy/") { exchange ->
            val path = exchange.requestURI.path
            requests += path
            val body = when {
                path.endsWith("home-feed-v1") -> """{"code":0,"data":{"items":[{"book_id":101,"title":"测试书","author_name":"作者","cover_url":"//cover/a.jpg","read_state":{"is_readable":0,"message":"没有默认章节","unread_chapter_count":2}}],"pagination":{"page":1,"total":1,"page_size":20,"has_next":false}}}"""
                "get-book-detail" in path -> """{"code":0,"data":{"book_id":101,"title":"测试书","summary":"简介","volume_count":1,"chapter_count":1,"cover_url":"https://cover/a.jpg","alternate_versions":[]}}"""
                "get-book-volumes" in path -> """{"code":0,"data":{"list":[{"volume_id":7,"book_id":101,"title":"第一卷","chapter_count":1}],"pagination":{"total":1}}}"""
                "get-volume-chapters" in path -> """{"code":0,"data":{"list":[{"chapter_id":9,"book_id":101,"volume_id":7,"title":"第一章","chapter_no":1,"locked":false}],"pagination":{"total":1}}}"""
                "get-chapter-detail" in path -> """{"code":0,"data":{"chapter_id":9,"book_id":101,"volume_id":7,"title":"第一章","chapter_no":1,"body":{"body_html":"<p>正文</p>"},"book_title":"测试书","volume_title":"第一卷"}}"""
                "auth-password-login-v1" in path -> """{"code":0,"data":{"security_key":"desktop-session","user":{"uid":88,"nickname":"测试用户"}}}"""
                "auth-session-v1" in path -> """{"code":0,"data":{"logged_in":true}}"""
                else -> """{"code":0,"data":{}}"""
            }
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val preferences = Preferences.userRoot().node("io/github/jiangyuyi/mixn/test/kingdom-${System.nanoTime()}")
        try {
            val store = DesktopSessionStore(preferences)
            val source = LightNovelKingdomDesktopSource(
                apiOrigin = "http://127.0.0.1:${server.address.port}/api/pc-proxy/",
                sessionStore = store,
            )

            val page = source.discover(DesktopFeed.POPULAR, 1, 20)
            assertEquals("测试书", page.items.single().title)
            assertEquals("101", page.items.single().remoteId)
            assertEquals("作者", page.items.single().author)
            assertEquals("https://cover/a.jpg", page.items.single().coverUrl)
            assertEquals(false, page.items.single().isReadable)
            assertEquals("没有默认章节", page.items.single().readabilityNote)
            assertEquals(2, page.items.single().unreadChapterCount)

            val detail = source.detail("101")
            assertEquals("简介", detail.description)
            val volume = source.volumes("101").single()
            val chapter = source.chapters("101", volume.remoteId, 1, 50).items.single()
            assertEquals("9", chapter.remoteId)
            val content = source.chapter("101", chapter.remoteId)
            assertTrue(content.bodyHtml.contains("正文"))

            val session = source.login("user", "password")
            assertEquals("测试用户", session.displayName)
            assertTrue(source.restoreSession().loggedIn)
            assertEquals("desktop-session", store.read(DESKTOP_KINGDOM_SOURCE_ID)["security"])
            assertTrue(requests.any { it.endsWith("get-chapter-detail") })
        } finally {
            preferences.removeNode()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `shelf login hashes password and encrypts stored tokens`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        var requestBody = ""
        server.createContext("/api/user/login") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val body = """{"Success":true,"Response":{"Token":"access-token","RefreshToken":"refresh-token"}}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val preferences = Preferences.userRoot().node("io/github/jiangyuyi/mixn/test/shelf-${System.nanoTime()}")
        try {
            val store = DesktopSessionStore(preferences)
            val source = LightNovelShelfDesktopSource(
                apiOrigin = "http://127.0.0.1:${server.address.port}",
                sessionStore = store,
            )
            source.login("user@example.com", "password")

            assertTrue(requestBody.contains("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"))
            assertFalse(requestBody.contains("\"password\":\"password\""))
            assertNotEquals("access-token", preferences.get("$DESKTOP_SHELF_SOURCE_ID.access", null))
            assertEquals("access-token", store.read(DESKTOP_SHELF_SOURCE_ID)["access"])
        } finally {
            preferences.removeNode()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
