package io.github.jiangyuyi.lightnovel.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.prefs.Preferences

class DesktopRuntimeTest {
    @Test
    fun `reading progress state is parsed and clamped`() {
        val preferences = Preferences.userRoot().node("io/github/jiangyuyi/mixn/test/progress-${System.nanoTime()}")
        try {
            val store = DesktopReadingProgressStore(preferences)
            val book = DesktopBook("fake", "book", "书")
            store.write(book, "chapter", 145)
            assertEquals(DesktopSavedProgress("chapter", 100), store.readState(book))
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun `search does not require a discovery feed`() {
        val source = FakeSource()
        val loader = DesktopSourcePageLoader(DesktopSourceRegistry(listOf(source)))

        val result = loader.load(source.id, "", "标题", 1, 20)

        assertEquals(listOf("search"), source.calls)
        assertEquals("搜索结果", result.items.single().title)
    }

    @Test
    fun `blank query still validates discovery feed`() {
        val source = FakeSource()
        val loader = DesktopSourcePageLoader(DesktopSourceRegistry(listOf(source)))

        assertFailsWith<IllegalArgumentException> { loader.load(source.id, "不存在", "", 1, 20) }
        assertEquals(emptyList(), source.calls)
    }

    private class FakeSource : DesktopSource {
        override val id = "fake"
        override val displayName = "测试来源"
        override val feeds = listOf(DesktopFeed.POPULAR)
        val calls = mutableListOf<String>()
        private val book = DesktopBook(id, "1", "搜索结果")

        override fun restoreSession() = DesktopSession(id, false)
        override fun login(identifier: String, password: String) = DesktopSession(id, true)
        override fun logout() = Unit
        override fun discover(feed: DesktopFeed, page: Int, pageSize: Int): DesktopPage {
            calls += "discover"
            return DesktopPage(listOf(book), page)
        }
        override fun search(query: String, page: Int, pageSize: Int): DesktopPage {
            calls += "search"
            return DesktopPage(listOf(book), page)
        }
        override fun detail(remoteId: String) = DesktopBookDetail(book)
        override fun volumes(remoteId: String) = emptyList<DesktopVolume>()
        override fun chapters(remoteId: String, volumeRemoteId: String, page: Int, pageSize: Int) = DesktopChapterPage(emptyList(), page)
        override fun chapter(remoteId: String, chapterRemoteId: String) = DesktopChapterContent(DesktopChapter(id, chapterRemoteId, remoteId, "v", "章节"), "书", "卷")
        override fun bookshelf() = emptyList<DesktopBook>()
        override fun setBookshelf(remoteId: String, add: Boolean) = add
        override fun profile() = DesktopProfile(id)
    }
}
