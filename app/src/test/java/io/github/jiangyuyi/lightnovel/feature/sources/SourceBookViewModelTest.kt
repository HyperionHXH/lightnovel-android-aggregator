package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.model.LocalReadingProgress
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.DetailProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelDetail
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ReaderProvider
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceBookViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `detail volume and first chapter page form a source scoped catalog`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReadableSource()
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))

            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("测试书", state.detail?.novel?.title)
            assertEquals(source.volumeKey, state.expandedVolumeKey)
            assertEquals(listOf("1"), state.chapters.getValue(source.volumeKey).items.map { it.key.remoteId })
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun `load more appends chapters without changing their source key`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReadableSource()
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
            advanceUntilIdle()

            viewModel.loadMoreChapters(source.volumeKey)
            advanceUntilIdle()

            val chapterState = viewModel.state.value.chapters.getValue(source.volumeKey)
            assertEquals(listOf("1", "2"), chapterState.items.map { it.key.remoteId })
            assertFalse(chapterState.hasMore)
        }

    @Test
    fun `remote shelf membership loads and can be toggled`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeReadableSource()
        val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.inRemoteShelf == true)
        viewModel.toggleShelf(onLoginRequired = {})
        advanceUntilIdle()

        assertTrue(viewModel.state.value.inRemoteShelf == true)
        assertEquals(listOf(true), source.shelfMutations)
    }

    @Test
    fun `download book queues current source novel`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeReadableSource()
        val offline = FakeOfflineLibrary()
        val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)), offline)
        advanceUntilIdle()

        viewModel.downloadBook()

        assertEquals(listOf(source.novelKey to null), offline.enqueued)
    }

    @Test
    fun `start reading resumes the locally saved source chapter`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReadableSource()
            val savedChapter = ChapterKey(source.descriptor.id, "saved")
            val preferences = FakeReaderPreferences(
                ReadingProgress(
                    novelKey = source.novelKey,
                    volumeKey = source.volumeKey,
                    chapterKey = savedChapter,
                    paragraphIndex = 12,
                    percent = 48,
                ),
            )
            val viewModel = SourceBookViewModel(
                source.novelKey,
                SourceRegistry(listOf(source)),
                preferenceStore = preferences,
            )
            advanceUntilIdle()

            var openedChapter: ChapterKey? = null
            viewModel.startReading { openedChapter = it }

            assertEquals(savedChapter, viewModel.state.value.readingProgress?.chapterKey)
            assertEquals(savedChapter, openedChapter)
        }

    private class FakeReadableSource : NovelSource, DetailProvider, ReaderProvider, ShelfProvider {
        override val descriptor = SourceDescriptor(
            id = "source",
            displayName = "来源",
            capabilities = setOf(
                SourceCapability.DETAIL,
                SourceCapability.READER,
                SourceCapability.REMOTE_SHELF,
            ),
        )
        val novelKey = NovelKey(descriptor.id, "book")
        val volumeKey = VolumeKey(descriptor.id, "volume")
        val shelfMutations = mutableListOf<Boolean>()

        override suspend fun getNovelDetail(key: NovelKey) = NovelDetail(
            novel = NovelSummary(key, "测试书"),
        )

        override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) = SourcePage(
            items = listOf(VolumeSummary(volumeKey, novelKey, "正文", chapterCount = 2)),
            page = page,
        )

        override suspend fun getChapters(
            novelKey: NovelKey,
            volumeKey: VolumeKey,
            page: Int,
            pageSize: Int,
        ) = SourcePage(
            items = listOf(chapter(page)),
            page = page,
            total = 2,
            hasMore = page == 1,
        )

        override suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent =
            error("not used")

        override suspend fun getRemoteShelf(): List<NovelSummary> = emptyList()

        override suspend fun setInRemoteShelf(key: NovelKey, add: Boolean): Boolean {
            shelfMutations += add
            return add
        }

        private fun chapter(number: Int) = ChapterSummary(
            key = ChapterKey(descriptor.id, number.toString()),
            novelKey = novelKey,
            volumeKey = volumeKey,
            title = "第 $number 章",
            order = number,
        )
    }

    private class FakeOfflineLibrary : OfflineLibraryAccess {
        override val books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
        override val wifiOnly = MutableStateFlow(true)
        val enqueued = mutableListOf<Pair<NovelKey, VolumeKey?>>()
        override fun setWifiOnly(enabled: Boolean) { wifiOnly.value = enabled }
        override fun enqueue(novel: NovelSummary, volumeKey: VolumeKey?) {
            enqueued += novel.key to volumeKey
        }
        override fun retry(record: OfflineBookRecord) = Unit
        override fun delete(key: NovelKey) = Unit
        override suspend fun readBook(key: NovelKey): OfflineBookRecord? = null
        override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? = null
    }

    private class FakeReaderPreferences(
        private val savedProgress: ReadingProgress,
    ) : ReaderPreferencesAccess {
        override val preferences: Flow<ReaderPreferences> = flowOf(ReaderPreferences())
        override suspend fun update(value: ReaderPreferences) = Unit
        override fun progress(bookId: Long): Flow<LocalReadingProgress?> = flowOf(null)
        override suspend fun saveProgress(bookId: Long, progress: LocalReadingProgress) = Unit
        override fun sourceProgress(novelKey: NovelKey): Flow<ReadingProgress?> = flowOf(
            savedProgress.takeIf { it.novelKey == novelKey },
        )
        override suspend fun saveSourceProgress(progress: ReadingProgress) = Unit
    }
}
