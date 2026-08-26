package io.github.jiangyuyi.lightnovel.source.lightnovelkingdom

import io.github.jiangyuyi.lightnovel.core.model.BookDetail
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import io.github.jiangyuyi.lightnovel.core.model.ChapterDetail
import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.model.DiscoverChannel
import io.github.jiangyuyi.lightnovel.core.model.Page
import io.github.jiangyuyi.lightnovel.core.model.ReadingHistoryItem
import io.github.jiangyuyi.lightnovel.core.model.Session
import io.github.jiangyuyi.lightnovel.core.model.Volume
import io.github.jiangyuyi.lightnovel.core.source.BuiltInSourceIds
import io.github.jiangyuyi.lightnovel.core.source.DiscoverFeed
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightNovelKingdomSourceTest {
    @Test
    fun `discover maps shared feed to kingdom channel`() = runTest {
        var requestedChannel: DiscoverChannel? = null
        val source = LightNovelKingdomSource(object : StubGateway() {
            override suspend fun discover(channel: DiscoverChannel, page: Int, pageSize: Int): Page<BookSummary> {
                requestedChannel = channel
                return Page(listOf(BookSummary(7, "热门书")), page)
            }
        })

        val result = source.discover(DiscoverFeed.POPULAR)

        assertEquals(DiscoverChannel.HOT, requestedChannel)
        assertEquals("热门书", result.items.single().title)
    }

    @Test
    fun `search maps numeric id to source scoped key`() = runTest {
        val source = LightNovelKingdomSource(object : StubGateway() {
            override suspend fun search(query: String, page: Int, pageSize: Int) = Page(
                items = listOf(
                    BookSummary(
                        id = 42,
                        title = "测试书",
                        author = "作者",
                        inBookshelf = true,
                        unreadChapterCount = 2,
                    ),
                ),
                page = page,
                total = 1,
            )
        })

        val result = source.search("测试", page = 1, pageSize = 20)
        val novel = result.items.single()

        assertEquals(NovelKey(BuiltInSourceIds.LIGHT_NOVEL_KINGDOM, "42"), novel.key)
        assertEquals(listOf("作者"), novel.authors)
        assertEquals(true, novel.inRemoteShelf)
        assertEquals(2, novel.unreadChapterCount)
    }

    @Test
    fun `adapter rejects a key owned by another source before network call`() = runTest {
        var networkCalled = false
        val source = LightNovelKingdomSource(object : StubGateway() {
            override suspend fun bookDetail(bookId: Long): BookDetail {
                networkCalled = true
                return BookDetail(BookSummary(bookId, "unexpected"))
            }
        })

        val error = runCatching {
            source.getNovelDetail(NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "42"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(networkCalled)
    }

    @Test
    fun `unconfirmed daily reward capability is not advertised`() {
        val source = LightNovelKingdomSource(StubGateway())

        assertFalse(SourceCapability.DAILY_REWARD in source.descriptor.capabilities)
    }

    private open class StubGateway : LightNovelKingdomGateway {
        override suspend fun discover(channel: DiscoverChannel, page: Int, pageSize: Int): Page<BookSummary> = unexpected()
        override suspend fun search(query: String, page: Int, pageSize: Int): Page<BookSummary> = unexpected()
        override suspend fun bookDetail(bookId: Long): BookDetail = unexpected()
        override suspend fun volumes(bookId: Long, page: Int, pageSize: Int): Page<Volume> = unexpected()
        override suspend fun chapters(
            bookId: Long,
            volumeId: Long,
            page: Int,
            pageSize: Int,
        ): Page<ChapterSummary> = unexpected()

        override suspend fun chapter(bookId: Long, chapterId: Long): ChapterDetail = unexpected()
        override suspend fun restoreSession(): Session = Session()
        override suspend fun login(username: String, password: String): Session = unexpected()
        override suspend fun logout() = Unit
        override suspend fun bookshelf(): List<BookSummary> = emptyList()
        override suspend fun setBookshelf(bookId: Long, add: Boolean): Boolean = unexpected()
        override suspend fun readingHistory(page: Int, pageSize: Int): Page<ReadingHistoryItem> = unexpected()
        override suspend fun deleteReadingHistory(bookId: Long) = Unit
        override suspend fun saveReadingProgress(
            bookId: Long,
            volumeId: Long,
            chapterId: Long,
            paragraphIndex: Int,
            percent: Int,
        ) = Unit

        protected fun <T> unexpected(): T = error("unexpected gateway call")
    }
}
