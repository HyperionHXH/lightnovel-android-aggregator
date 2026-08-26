package io.github.jiangyuyi.lightnovel.feature.history

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.model.LocalReadingProgress
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.HistoryMutationProvider
import io.github.jiangyuyi.lightnovel.core.source.HistoryProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ReadingHistoryEntry
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AggregateHistoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `histories interleave and local progress supplies continue chapter`() =
        runTest(mainDispatcherRule.dispatcher) {
            val first = FakeHistorySource("first", listOf(entry("first", "1"), entry("first", "2")))
            val second = FakeHistorySource("second", listOf(entry("second", "1")))
            val progress = ReadingProgress(
                novelKey = NovelKey("second", "1"),
                volumeKey = null,
                chapterKey = ChapterKey("second", "chapter-7"),
                paragraphIndex = 3,
                percent = 20,
            )
            val registry = SourceRegistry(listOf(first, second))
            val viewModel = AggregateHistoryViewModel(registry, FakePreferences(listOf(progress)))

            advanceUntilIdle()

            val results = aggregateHistoryResults(viewModel.state.value, registry)
            assertEquals(listOf("first-1", "second-1", "first-2"), results.map { it.entry.novel.title })
            assertEquals("chapter-7", results[1].entry.lastChapterKey?.remoteId)
        }

    @Test
    fun `one history failure does not hide another source`() =
        runTest(mainDispatcherRule.dispatcher) {
            val working = FakeHistorySource("working", listOf(entry("working", "1")))
            val failed = FakeHistorySource("failed", emptyList(), fail = true)
            val viewModel = AggregateHistoryViewModel(
                SourceRegistry(listOf(working, failed)),
                FakePreferences(),
            )

            advanceUntilIdle()

            assertEquals(1, viewModel.state.value.sources.first { it.descriptor.id == "working" }.items.size)
            assertEquals(
                SourceErrorKind.NETWORK,
                viewModel.state.value.sources.first { it.descriptor.id == "failed" }.errorKind,
            )
        }

    @Test
    fun `load more appends each source page without duplicates`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeHistorySource(
                "source",
                listOf(entry("source", "1"), entry("source", "2")),
                pageSize = 1,
            )
            val viewModel = AggregateHistoryViewModel(SourceRegistry(listOf(source)), FakePreferences())
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("source-1", "source-2"), viewModel.state.value.sources.single().items.map {
                it.novel.title
            })
        }

    @Test
    fun `deletable source removes completed history item`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = MutableHistorySource("source", listOf(entry("source", "1")))
            val registry = SourceRegistry(listOf(source))
            val viewModel = AggregateHistoryViewModel(registry, FakePreferences())
            advanceUntilIdle()
            val result = aggregateHistoryResults(viewModel.state.value, registry).single()

            viewModel.delete(result)
            advanceUntilIdle()

            assertEquals(listOf(NovelKey("source", "1")), source.deleted)
            assertEquals(emptyList<ReadingHistoryEntry>(), viewModel.state.value.sources.single().items)
            assertNull(viewModel.state.value.actionError)
        }

    private open class FakeHistorySource(
        id: String,
        private val history: List<ReadingHistoryEntry>,
        private val fail: Boolean = false,
        private val pageSize: Int = Int.MAX_VALUE,
    ) : NovelSource, HistoryProvider {
        override val descriptor = SourceDescriptor(id, id, setOf(SourceCapability.HISTORY))

        override suspend fun getReadingHistory(page: Int, pageSize: Int): SourcePage<ReadingHistoryEntry> {
            if (fail) throw SourceException(SourceErrorKind.NETWORK, "offline")
            val size = this.pageSize.coerceAtMost(pageSize)
            val from = ((page - 1) * size).coerceAtMost(history.size)
            val to = (from + size).coerceAtMost(history.size)
            return SourcePage(history.subList(from, to), page, history.size, to < history.size)
        }
    }

    private class MutableHistorySource(
        id: String,
        history: List<ReadingHistoryEntry>,
    ) : FakeHistorySource(id, history), HistoryMutationProvider {
        val deleted = mutableListOf<NovelKey>()

        override suspend fun deleteReadingHistory(novelKey: NovelKey) {
            deleted += novelKey
        }
    }

    private class FakePreferences(
        private val sourceProgress: List<ReadingProgress> = emptyList(),
    ) : ReaderPreferencesAccess {
        override val preferences: Flow<ReaderPreferences> = flowOf(ReaderPreferences())
        override suspend fun update(value: ReaderPreferences) = Unit
        override fun progress(bookId: Long): Flow<LocalReadingProgress?> = flowOf(null)
        override suspend fun saveProgress(bookId: Long, progress: LocalReadingProgress) = Unit
        override fun sourceProgress(novelKey: NovelKey): Flow<ReadingProgress?> =
            flowOf(sourceProgress.firstOrNull { it.novelKey == novelKey })

        override fun allSourceProgress(): Flow<List<ReadingProgress>> = flowOf(sourceProgress)
        override suspend fun saveSourceProgress(progress: ReadingProgress) = Unit
    }

    private fun entry(sourceId: String, remoteId: String) = ReadingHistoryEntry(
        novel = NovelSummary(
            key = NovelKey(sourceId, remoteId),
            title = "$sourceId-$remoteId",
        ),
    )
}
