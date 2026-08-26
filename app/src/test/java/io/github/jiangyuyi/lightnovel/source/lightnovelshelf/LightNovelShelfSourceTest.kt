package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.core.source.BuiltInSourceIds
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.DiscoverFeed
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightNovelShelfSourceTest {
    @Test
    fun `popular discover uses viewed order`() = runTest {
        var requestedOrder: ShelfBookOrder? = null
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun listBooks(order: ShelfBookOrder, page: Int, pageSize: Int): ShelfBookPage {
                requestedOrder = order
                return ShelfBookPage(1, 1, listOf(ShelfBookItem(8, "热门书", null, null)))
            }
        })

        val result = source.discover(DiscoverFeed.POPULAR)

        assertEquals(ShelfBookOrder.VIEWED, requestedOrder)
        assertEquals("热门书", result.items.single().title)
    }

    @Test
    fun `weekly rank uses seven day endpoint`() = runTest {
        var requestedDays = 0
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun rank(days: Int): List<ShelfBookItem> {
                requestedDays = days
                return listOf(ShelfBookItem(9, "周榜书", null, null))
            }
        })

        val result = source.discover(DiscoverFeed.WEEKLY_RANK)

        assertEquals(7, requestedDays)
        assertEquals("周榜书", result.items.single().title)
    }

    @Test
    fun `search maps result to shelf scoped key`() = runTest {
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun search(query: String, page: Int, pageSize: Int) = ShelfBookPage(
                page = 1,
                totalPages = 2,
                items = listOf(ShelfBookItem(9, "书名", "https://cover", "作者")),
            )
        })

        val result = source.search("书", 1, 20)

        assertEquals(NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "9"), result.items.single().key)
        assertEquals(listOf("作者"), result.items.single().authors)
        assertEquals(true, result.hasMore)
    }

    @Test
    fun `flat chapter list is paged with stable sort number keys`() = runTest {
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun getBookDetail(bookId: Long) = detail(
                chapters = (1..5).map { ShelfBookChapter(it.toLong() * 10, "第${it}章") },
            )
        })

        val result = source.getChapters(
            NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "1"),
            VolumeKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "default"),
            page = 2,
            pageSize = 2,
        )

        assertEquals(listOf("3", "4"), result.items.map { it.key.remoteId })
        assertEquals(5, result.total)
        assertEquals(true, result.hasMore)
    }

    @Test
    fun `already signed account does not call check in again`() = runTest {
        var checkInCalls = 0
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun getProfile() = ShelfProfile(1, "user", 88, 3, true)
            override suspend fun checkIn(): ShelfCheckInResult {
                checkInCalls += 1
                return ShelfCheckInResult(5, 4)
            }
        })

        val result = source.claimDailyReward()

        assertEquals(0L, result.rewardAmount)
        assertEquals(88L, result.balance)
        assertEquals(0, checkInCalls)
    }

    @Test
    fun `chapter navigation uses sort numbers`() = runTest {
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun getNovelContent(bookId: Long, sortNumber: Int) = ShelfNovelContent(
                id = 30,
                bookId = bookId,
                title = "第三章",
                html = "<p>正文</p>",
                fontUrl = "/fonts/chapter.woff2",
                sortNumber = sortNumber,
                chapterTitles = listOf("一", "二", "三", "四"),
            )
        })

        val chapter = source.getChapter(
            NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "1"),
            ChapterKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "3"),
        )

        assertEquals("2", chapter.previousChapterKey?.remoteId)
        assertEquals("4", chapter.nextChapterKey?.remoteId)
        assertEquals("<p>正文</p>", chapter.bodyHtml)
        assertEquals("/fonts/chapter.woff2", chapter.fontUrl)
    }

    @Test
    fun `remote shelf hydrates book ids in batches`() = runTest {
        val requestedBatches = mutableListOf<List<Long>>()
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun getShelf() = ShelfRemoteSnapshot(
                version = "20220211",
                items = (1L..25L).mapIndexed { index, id -> shelfBook(id, index) },
            )

            override suspend fun getBooksByIds(ids: List<Long>): List<ShelfBookItem> {
                requestedBatches += ids
                return ids.map { id -> ShelfBookItem(id, "书$id", null, null) }
            }
        })

        val books = source.getRemoteShelf()

        assertEquals(listOf(24, 1), requestedBatches.map(List<Long>::size))
        assertEquals(25, books.size)
        assertTrue(books.all { it.inRemoteShelf == true })
    }

    @Test
    fun `reading history pages ids and restores server order`() = runTest {
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun getReadHistory() = listOf(9L, 7L, 5L, 3L, 1L)

            override suspend fun getBooksByIds(ids: List<Long>): List<ShelfBookItem> =
                ids.reversed().map { id -> ShelfBookItem(id, "书$id", null, null) }
        })

        val page = source.getReadingHistory(page = 2, pageSize = 2)

        assertEquals(listOf("5", "3"), page.items.map { it.novel.key.remoteId })
        assertEquals(5, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `adding a book preserves folders when saving shelf`() = runTest {
        var saved: ShelfRemoteSnapshot? = null
        val source = LightNovelShelfSource(object : StubGateway() {
            override suspend fun getShelf() = ShelfRemoteSnapshot(
                version = "20220211",
                items = listOf(
                    ShelfRemoteItem(ShelfRemoteItemType.FOLDER, "folder", 0, emptyList(), "", "分类"),
                    shelfBook(7, 0, parents = listOf("folder")),
                ),
            )

            override suspend fun saveShelf(snapshot: ShelfRemoteSnapshot) {
                saved = snapshot
            }
        })

        val inShelf = source.setInRemoteShelf(
            NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "9"),
            add = true,
        )

        assertTrue(inShelf)
        assertEquals("20220211", saved?.version)
        assertTrue(saved?.items.orEmpty().any { it.type == ShelfRemoteItemType.FOLDER && it.id == "folder" })
        assertEquals(setOf(7L, 9L), saved?.items.orEmpty().mapNotNull { it.bookId }.toSet())
    }

    private open class StubGateway : LightNovelShelfGateway {
        override suspend fun listBooks(order: ShelfBookOrder, page: Int, pageSize: Int): ShelfBookPage = unexpected()
        override suspend fun rank(days: Int): List<ShelfBookItem> = unexpected()
        override suspend fun search(query: String, page: Int, pageSize: Int): ShelfBookPage = unexpected()
        override suspend fun getBookDetail(bookId: Long): ShelfBookDetail = unexpected()
        override suspend fun getNovelContent(bookId: Long, sortNumber: Int): ShelfNovelContent = unexpected()
        override suspend fun getShelf(): ShelfRemoteSnapshot = unexpected()
        override suspend fun saveShelf(snapshot: ShelfRemoteSnapshot): Unit = unexpected()
        override suspend fun getBooksByIds(ids: List<Long>): List<ShelfBookItem> = unexpected()
        override suspend fun getProfile(): ShelfProfile = unexpected()
        override suspend fun checkIn(): ShelfCheckInResult = unexpected()
        override suspend fun login(email: String, password: String): Boolean = false
        override suspend fun restoreSession(): Boolean = false
        override suspend fun logout() = Unit

        protected fun detail(chapters: List<ShelfBookChapter> = emptyList()) = ShelfBookDetail(
            id = 1,
            title = "书",
            coverUrl = null,
            authorName = null,
            introduction = "",
            tags = emptyList(),
            favoriteCount = 0,
            chapters = chapters,
        )

        protected fun shelfBook(
            id: Long,
            index: Int,
            parents: List<String> = emptyList(),
        ) = ShelfRemoteItem(
            type = ShelfRemoteItemType.BOOK,
            id = id.toString(),
            index = index,
            parents = parents,
            updatedAt = "",
        )

        protected fun <T> unexpected(): T = error("unexpected gateway call")
    }
}
