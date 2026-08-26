package io.github.jiangyuyi.lightnovel.feature.bookshelf

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.updates.SourceUpdateSnapshot
import io.github.jiangyuyi.lightnovel.core.updates.SourceUpdateSnapshotAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AggregateBookshelfViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `both shelves load and all filter interleaves their books`() =
        runTest(mainDispatcherRule.dispatcher) {
            val first = FakeShelfSource("first") {
                listOf(novel("first", "1"), novel("first", "2"))
            }
            val second = FakeShelfSource("second") {
                listOf(novel("second", "1"), novel("second", "2"))
            }
            val viewModel = AggregateBookshelfViewModel(SourceRegistry(listOf(first, second)))

            advanceUntilIdle()

            val visible = visibleBookshelfSources(viewModel.state.value)
            assertTrue(visible.all { it.loaded })
            assertEquals(
                listOf("first-1", "second-1", "first-2", "second-2"),
                interleaveBookshelfResults(visible).map { it.novel.title },
            )
        }

    @Test
    fun `authentication failure remains isolated from another shelf`() =
        runTest(mainDispatcherRule.dispatcher) {
            val working = FakeShelfSource("working") { listOf(novel("working", "1")) }
            val protected = FakeShelfSource("protected") {
                throw SourceException(SourceErrorKind.AUTHENTICATION, "expired")
            }
            val viewModel = AggregateBookshelfViewModel(SourceRegistry(listOf(working, protected)))

            advanceUntilIdle()

            val workingState = viewModel.state.value.sources.first { it.descriptor.id == "working" }
            val protectedState = viewModel.state.value.sources.first { it.descriptor.id == "protected" }
            assertEquals(1, workingState.items.size)
            assertFalse(workingState.loading)
            assertEquals(SourceErrorKind.AUTHENTICATION, protectedState.errorKind)
            assertEquals("请先登录该来源", protectedState.errorMessage)
        }

    @Test
    fun `downloaded and all filters change visible shelves without reloading`() =
        runTest(mainDispatcherRule.dispatcher) {
            var calls = 0
            val first = FakeShelfSource("first") { calls += 1; listOf(novel("first", "1")) }
            val second = FakeShelfSource("second") { calls += 1; listOf(novel("second", "1")) }
            val viewModel = AggregateBookshelfViewModel(SourceRegistry(listOf(first, second)))
            advanceUntilIdle()

            viewModel.selectDownloaded()
            assertTrue(visibleBookshelfSources(viewModel.state.value).isEmpty())
            viewModel.selectAll()
            assertEquals(2, visibleBookshelfSources(viewModel.state.value).size)
            assertEquals(2, calls)
        }

    @Test
    fun `slow shelf is reported as timed out`() = runTest(mainDispatcherRule.dispatcher) {
        val slow = FakeShelfSource("slow") {
            delay(1_000)
            emptyList()
        }
        val viewModel = AggregateBookshelfViewModel(
            registry = SourceRegistry(listOf(slow)),
            perSourceTimeoutMillis = 100,
        )

        advanceUntilIdle()

        assertEquals(SourceErrorKind.TIMEOUT, viewModel.state.value.sources.single().errorKind)
    }

    @Test
    fun `downloaded filter observes private offline library`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeShelfSource("source") { emptyList() }
        val offline = FakeOfflineLibrary()
        val viewModel = AggregateBookshelfViewModel(SourceRegistry(listOf(source)), offline)
        advanceUntilIdle()

            offline.books.value = listOf(OfflineBookRecord(novel("source", "offline")))
            offline.wifiOnly.value = false
            advanceUntilIdle()
            viewModel.selectDownloaded()

            assertEquals("source-offline", viewModel.state.value.offlineBooks.single().novel.title)
            assertFalse(offline.wifiOnly.value)
    }

    @Test
    fun `chapter count increase is detected after the first snapshot baseline`() =
        runTest(mainDispatcherRule.dispatcher) {
            var chapterCount = 3
            val source = FakeShelfSource("source") {
                listOf(novel("source", "book").copy(chapterCount = chapterCount))
            }
            val snapshots = FakeSourceUpdateSnapshots()
            val viewModel = AggregateBookshelfViewModel(
                registry = SourceRegistry(listOf(source)),
                sourceUpdateSnapshots = snapshots,
            )

            advanceUntilIdle()

            assertTrue(viewModel.state.value.updatedBooks.isEmpty())
            assertEquals(3, snapshots.snapshots.value.single().chapterCount)

            chapterCount = 4
            viewModel.refresh()
            advanceUntilIdle()

            val key = NovelKey("source", "book")
            assertTrue(key in viewModel.state.value.updatedBooks)
            assertTrue(
                interleaveBookshelfResults(
                    visibleBookshelfSources(viewModel.state.value),
                    viewModel.state.value.updatedBooks,
                ).single().hasUpdates,
            )
            assertEquals(4, snapshots.snapshots.value.single().chapterCount)
        }

    @Test
    fun `source unread count is an update signal on the first observation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeShelfSource("source") {
                listOf(novel("source", "book").copy(unreadChapterCount = 2))
            }
            val viewModel = AggregateBookshelfViewModel(SourceRegistry(listOf(source)))

            advanceUntilIdle()

            assertTrue(NovelKey("source", "book") in viewModel.state.value.updatedBooks)
        }

    @Test
    fun `marking updates seen suppresses the same unread count until it increases`() =
        runTest(mainDispatcherRule.dispatcher) {
            var unreadCount = 2
            val source = FakeShelfSource("source") {
                listOf(novel("source", "book").copy(unreadChapterCount = unreadCount))
            }
            val snapshots = FakeSourceUpdateSnapshots()
            val viewModel = AggregateBookshelfViewModel(
                registry = SourceRegistry(listOf(source)),
                sourceUpdateSnapshots = snapshots,
            )
            advanceUntilIdle()

            viewModel.markAllUpdatesSeen()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.updatedBooks.isEmpty())

            viewModel.refresh()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.updatedBooks.isEmpty())

            unreadCount = 3
            viewModel.refresh()
            advanceUntilIdle()
            assertTrue(NovelKey("source", "book") in viewModel.state.value.updatedBooks)
        }

    @Test
    fun `explicit zero unread count does not create an update marker`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeShelfSource("source") {
                listOf(novel("source", "book").copy(unreadChapterCount = 0))
            }
            val viewModel = AggregateBookshelfViewModel(SourceRegistry(listOf(source)))

            advanceUntilIdle()

            assertTrue(viewModel.state.value.updatedBooks.isEmpty())
        }

    @Test
    fun `update summary groups by source and keeps same remote ids separate`() {
        val first = SourceDescriptor("first", "轻之国度", setOf(SourceCapability.REMOTE_SHELF))
        val second = SourceDescriptor("second", "轻书架", setOf(SourceCapability.REMOTE_SHELF))
        val state = AggregateBookshelfState(
            sourceOptions = listOf(first, second),
            updatedBooks = setOf(
                NovelKey("first", "same-id"),
                NovelKey("second", "same-id"),
                NovelKey("second", "another-id"),
            ),
        )

        assertEquals(
            BookshelfUpdateSummary(
                totalBooks = 3,
                bySource = listOf(
                    BookshelfSourceUpdateCount("first", "轻之国度", 1),
                    BookshelfSourceUpdateCount("second", "轻书架", 2),
                ),
            ),
            bookshelfUpdateSummary(state),
        )
    }

    @Test
    fun `empty update summary is hidden`() {
        assertEquals(null, bookshelfUpdateSummary(AggregateBookshelfState()))
    }

    private class FakeShelfSource(
        id: String,
        private val response: suspend () -> List<NovelSummary>,
    ) : NovelSource, ShelfProvider {
        override val descriptor = SourceDescriptor(id, id, setOf(SourceCapability.REMOTE_SHELF))

        override suspend fun getRemoteShelf(): List<NovelSummary> = response()

        override suspend fun setInRemoteShelf(key: NovelKey, add: Boolean): Boolean = add
    }

    private class FakeOfflineLibrary : OfflineLibraryAccess {
        override val books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
        override val wifiOnly = MutableStateFlow(true)
        override fun setWifiOnly(enabled: Boolean) { wifiOnly.value = enabled }
        override fun enqueue(novel: NovelSummary, volumeKey: VolumeKey?) = Unit
        override fun retry(record: OfflineBookRecord) = Unit
        override fun delete(key: NovelKey) = Unit
        override suspend fun readBook(key: NovelKey): OfflineBookRecord? = null
        override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? = null
    }

    private class FakeSourceUpdateSnapshots(
        initial: List<SourceUpdateSnapshot> = emptyList(),
    ) : SourceUpdateSnapshotAccess {
        override val snapshots = MutableStateFlow(initial)

        override suspend fun saveAll(values: List<SourceUpdateSnapshot>) {
            snapshots.value = (snapshots.value + values)
                .associateBy(SourceUpdateSnapshot::novelKey)
                .values
                .toList()
        }
    }

    private fun novel(sourceId: String, remoteId: String) = NovelSummary(
        key = NovelKey(sourceId, remoteId),
        title = "$sourceId-$remoteId",
        inRemoteShelf = true,
    )
}
