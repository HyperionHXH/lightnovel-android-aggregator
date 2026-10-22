package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.source.AggregateSearchCoordinator
import io.github.jiangyuyi.lightnovel.core.source.BuiltInSourceIds
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfViewModel
import io.github.jiangyuyi.lightnovel.feature.discover.AggregateDiscoverViewModel
import io.github.jiangyuyi.lightnovel.feature.search.AggregateSearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfListMetadataIntegrationTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `real adapter progressively hydrates all three card callsites with one shared detail request`() =
        runTest(mainDispatcherRule.dispatcher) {
            val ready = CompletableDeferred<Unit>()
            val gateway = MetadataGateway { id -> ready.await(); detail(id) }
            val source = LightNovelShelfSource(gateway)
            val registry = SourceRegistry(listOf(source))
            val discover = AggregateDiscoverViewModel(registry)
            val search = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry, debounceMillis = 0)
            val shelf = AggregateBookshelfViewModel(registry)
            search.setQuery("book")
            runCurrent()

            assertFalse(discover.state.value.sources.single().loading)
            assertFalse(shelf.state.value.sources.single().loading)
            assertFalse(search.state.value.sources.single().loading)
            assertTrue(discover.state.value.sources.single().items.single().authors.isEmpty())
            assertTrue(search.state.value.sources.single().items.single().authors.isEmpty())
            assertTrue(shelf.state.value.sources.single().items.single().authors.isEmpty())
            val key = NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "7")
            assertEquals(setOf(key), discover.state.value.sources.single().pendingMetadata)
            assertEquals(setOf(key), search.state.value.sources.single().pendingMetadata)
            assertEquals(setOf(key), shelf.state.value.sources.single().pendingMetadata)
            assertEquals(listOf(7L), gateway.detailCalls)

            ready.complete(Unit)
            advanceUntilIdle()
            val cards = listOf(
                discover.state.value.sources.single().items.single(),
                search.state.value.sources.single().items.single(),
                shelf.state.value.sources.single().items.single(),
            )
            cards.forEach { card ->
                assertEquals(listOf("真实作者"), card.authors)
                assertEquals("列表标题", card.title)
                assertEquals("https://list-cover", card.coverUrl)
                assertEquals("列表简介", card.synopsis)
                assertEquals(NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "7"), card.key)
            }
            assertEquals(true, cards.last().inRemoteShelf)
            assertTrue(discover.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(search.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(shelf.state.value.sources.single().pendingMetadata.isEmpty())
            source.getNovelDetail(cards.first().key)
            discover.refresh()
            search.searchNow()
            shelf.refresh()
            runCurrent()
            assertTrue(discover.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(search.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(shelf.state.value.sources.single().pendingMetadata.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf(7L), gateway.detailCalls)
        }

    @Test
    fun `stale detail returning after canceled refresh cannot overwrite current discover search or shelf cards`() =
        runTest(mainDispatcherRule.dispatcher) {
            suspend fun staleThenFresh(id: Long, calls: Int): ShelfBookDetail {
                if (calls == 1) withContext(NonCancellable) { delay(100) }
                return detail(id, author = if (calls == 1) "旧作者" else "新作者")
            }
            for (screen in listOf("discover", "search", "shelf")) {
                var calls = 0
                val gateway = MetadataGateway { id -> staleThenFresh(id, ++calls) }
                val registry = SourceRegistry(listOf(LightNovelShelfSource(gateway)))
                when (screen) {
                    "discover" -> {
                        val model = AggregateDiscoverViewModel(registry)
                        runCurrent()
                        model.refresh()
                        advanceUntilIdle()
                        assertEquals(listOf("新作者"), model.state.value.sources.single().items.single().authors)
                    }
                    "search" -> {
                        val model = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry, 0)
                        model.setQuery("same")
                        runCurrent()
                        model.searchNow()
                        advanceUntilIdle()
                        assertEquals(listOf("新作者"), model.state.value.sources.single().items.single().authors)
                    }
                    else -> {
                        val model = AggregateBookshelfViewModel(registry)
                        runCurrent()
                        model.refresh()
                        advanceUntilIdle()
                        assertEquals(listOf("新作者"), model.state.value.sources.single().items.single().authors)
                    }
                }
                assertEquals("$screen must discard stale detail rather than caching it", 2, calls)
            }
        }

    @Test
    fun `missing true author remains empty and is negative cached across cards and refresh`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gateway = MetadataGateway { id -> detail(id, author = null) }
            val registry = SourceRegistry(listOf(LightNovelShelfSource(gateway)))
            val search = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry, 0)
            search.setQuery("book")
            advanceUntilIdle()
            search.searchNow()
            advanceUntilIdle()
            assertTrue(search.state.value.sources.single().items.single().authors.isEmpty())
            assertTrue(search.state.value.sources.single().pendingMetadata.isEmpty())
            assertEquals(listOf(7L), gateway.detailCalls)
        }

    @Test
    fun `discover and search keep paginated cards enriched without refetching previous details`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gateway = MetadataGateway(twoPages = true) { id -> detail(id, "作者$id") }
            val registry = SourceRegistry(listOf(LightNovelShelfSource(gateway)))
            val discover = AggregateDiscoverViewModel(registry)
            val search = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry, 0)
            search.setQuery("book")
            advanceUntilIdle()
            discover.loadMore()
            search.loadMore()
            advanceUntilIdle()
            val cards = listOf(discover.state.value.sources.single().items, search.state.value.sources.single().items)
            cards.forEach { items ->
                assertEquals(listOf("7", "8"), items.map { it.key.remoteId })
                assertEquals(listOf(listOf("作者7"), listOf("作者8")), items.map { it.authors })
                assertEquals(listOf("列表标题", "列表标题"), items.map { it.title })
            }
            assertEquals(2, discover.state.value.sources.single().page)
            assertEquals(2, search.state.value.sources.single().page)
            assertFalse(discover.state.value.sources.single().hasMore)
            assertFalse(search.state.value.sources.single().hasMore)
            assertEquals(listOf(7L, 8L), gateway.detailCalls)
        }

    @Test
    fun `metadata authentication failure stays visible alongside loaded lists for each callsite`() =
        runTest(mainDispatcherRule.dispatcher) {
            val registry = SourceRegistry(listOf(LightNovelShelfSource(MetadataGateway {
                throw SourceException(SourceErrorKind.AUTHENTICATION, "expired")
            })))
            val discover = AggregateDiscoverViewModel(registry)
            val shelf = AggregateBookshelfViewModel(registry)
            val search = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry, 0)
            search.setQuery("book")
            advanceUntilIdle()
            assertEquals(SourceErrorKind.AUTHENTICATION, discover.state.value.sources.single().errorKind)
            assertEquals(SourceErrorKind.AUTHENTICATION, search.state.value.sources.single().errorKind)
            assertEquals(SourceErrorKind.AUTHENTICATION, shelf.state.value.sources.single().errorKind)
            assertEquals("列表标题", discover.state.value.sources.single().items.single().title)
            assertEquals("列表标题", search.state.value.sources.single().items.single().title)
            assertEquals("列表标题", shelf.state.value.sources.single().items.single().title)
            assertTrue(discover.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(search.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(shelf.state.value.sources.single().pendingMetadata.isEmpty())
        }

    @Test
    fun `detail cache coalesces twenty callers expires evicts LRU and clears`() = runTest {
        var now = 0L
        var calls = 0
        val cache = ShelfDetailCache(nowMillis = { now }, maxEntries = 2, ttlMillis = 10)
        suspend fun get(id: Long) = cache.get(id) { calls++; delay(1); detail(id) }
        val results = List(20) { async { get(7) } }
        results.forEach { assertEquals("真实作者", it.await().authorName) }
        assertEquals(1, calls)
        get(8)
        get(7) // access 7 so 8 is the eldest
        get(9)
        get(8)
        assertEquals(4, calls)
        now = 11
        get(8)
        assertEquals(5, calls)
        cache.clear()
        get(8)
        assertEquals(6, calls)
        val oversized = ShelfDetailCache(maxCharacters = 1)
        repeat(2) { oversized.get(1) { calls++; detail(1) } }
        assertEquals(8, calls)
    }

    @Test
    fun `clearing metadata cancels active loads and never repopulates cleared entries`() = runTest {
        val cache = ShelfDetailCache()
        val entered = CompletableDeferred<Unit>()
        val pending = async {
            cache.get(7) {
                entered.complete(Unit)
                withContext(NonCancellable) { delay(100) }
                detail(7, "清理前作者")
            }
        }
        entered.await()
        cache.clear()
        advanceUntilIdle()
        assertTrue(pending.isCancelled)
        var calls = 0
        assertEquals("清理后作者", cache.get(7) {
            calls++
            detail(7, "清理后作者")
        }.authorName)
        assertEquals(1, calls)
    }

    @Test
    fun `one stalled detail does not hide ready cards or leave a permanent placeholder after timeout`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gateway = MetadataGateway(itemsPerPage = 2) { id ->
                if (id == 7L) delay(20_000)
                detail(id, "作者$id")
            }
            val registry = SourceRegistry(listOf(LightNovelShelfSource(gateway)))
            val discover = AggregateDiscoverViewModel(registry)
            val search = AggregateSearchViewModel(AggregateSearchCoordinator(registry), registry, 0)
            val shelf = AggregateBookshelfViewModel(registry)
            search.setQuery("book")
            runCurrent()
            val pendingKey = NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "7")
            val readyKey = NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "8")
            val loading = listOf(
                discover.state.value.sources.single().pendingMetadata,
                search.state.value.sources.single().pendingMetadata,
                shelf.state.value.sources.single().pendingMetadata,
            )
            loading.forEach { assertEquals(setOf(pendingKey), it) }
            listOf(discover.state.value.sources.single().items, search.state.value.sources.single().items,
                shelf.state.value.sources.single().items).forEach { items ->
                assertEquals(listOf(pendingKey, readyKey), items.map { it.key })
                assertEquals(listOf("作者8"), items.last().authors)
            }
            advanceUntilIdle()
            assertEquals(SourceErrorKind.TIMEOUT, discover.state.value.sources.single().errorKind)
            assertEquals(SourceErrorKind.TIMEOUT, search.state.value.sources.single().errorKind)
            assertEquals(SourceErrorKind.TIMEOUT, shelf.state.value.sources.single().errorKind)
            assertTrue(discover.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(search.state.value.sources.single().pendingMetadata.isEmpty())
            assertTrue(shelf.state.value.sources.single().pendingMetadata.isEmpty())
            assertEquals(2, discover.state.value.sources.single().items.size)
        }

    private class MetadataGateway(
        private val twoPages: Boolean = false,
        private val itemsPerPage: Int = 1,
        private val detail: suspend (Long) -> ShelfBookDetail,
    ) : LightNovelShelfGateway {
        val detailCalls = mutableListOf<Long>()
        private fun books(page: Int = 1) = List(itemsPerPage) { index ->
            ShelfBookItem(if (twoPages && page > 1) 8 else 7L + index, "列表标题", "https://list-cover", null, "列表简介")
        }
        override suspend fun listBooks(order: ShelfBookOrder, page: Int, pageSize: Int) =
            ShelfBookPage(page, if (twoPages) 2 else 1, books(page))
        override suspend fun rank(days: Int) = books()
        override suspend fun search(query: String, page: Int, pageSize: Int) =
            ShelfBookPage(page, if (twoPages) 2 else 1, books(page))
        override suspend fun getBookDetail(bookId: Long): ShelfBookDetail {
            detailCalls += bookId
            return detail(bookId)
        }
        override suspend fun getShelf() = ShelfRemoteSnapshot(null, books().map { book ->
            ShelfRemoteItem(ShelfRemoteItemType.BOOK, book.id.toString(), 0, emptyList(), "")
        })
        override suspend fun getBooksByIds(ids: List<Long>) = books().filter { it.id in ids }
        override suspend fun getNovelContent(bookId: Long, sortNumber: Int): ShelfNovelContent = error("unexpected")
        override suspend fun saveShelf(snapshot: ShelfRemoteSnapshot) = Unit
        override suspend fun getProfile(): ShelfProfile = error("unexpected")
        override suspend fun checkIn(): ShelfCheckInResult = error("unexpected")
        override suspend fun login(email: String, password: String) = false
        override suspend fun restoreSession() = false
        override suspend fun logout() = Unit
    }

    private fun detail(id: Long, author: String? = "真实作者") = ShelfBookDetail(
        id, "详情标题不得覆盖列表", "https://detail-cover", author, "详情简介", emptyList(), 1, emptyList(),
    )
}
