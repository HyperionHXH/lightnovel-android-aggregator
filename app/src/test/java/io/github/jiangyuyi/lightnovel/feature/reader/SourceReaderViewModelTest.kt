package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.ui.text.font.FontFamily
import io.github.jiangyuyi.lightnovel.MainDispatcherRule
import io.github.jiangyuyi.lightnovel.core.model.LocalReadingProgress
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ReaderProvider
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceReaderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `local chapter is used without requesting the remote source`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReaderSource()
            val localChapter = source.chapter("本地正文")
            val viewModel = SourceReaderViewModel(
                novelKey = source.novelKey,
                initialChapterKey = source.chapterKey,
                registry = SourceRegistry(listOf(source)),
                preferenceStore = FakeReaderPreferences(),
                offlineLibrary = FakeOfflineLibrary(localChapter),
            )

            advanceUntilIdle()

            assertEquals("本地正文", viewModel.state.value.chapter?.bodyText)
            assertEquals(0, source.chapterRequests)
        }

    @Test
    fun `missing local chapter falls back to the remote source`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReaderSource()
            val viewModel = SourceReaderViewModel(
                novelKey = source.novelKey,
                initialChapterKey = source.chapterKey,
                registry = SourceRegistry(listOf(source)),
                preferenceStore = FakeReaderPreferences(),
                offlineLibrary = FakeOfflineLibrary(null),
            )

            advanceUntilIdle()

            assertEquals("远端正文", viewModel.state.value.chapter?.bodyText)
            assertEquals(1, source.chapterRequests)
        }

    @Test
    fun `chapter font is loaded before exposing shelf content`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReaderSource()
            val chapter = source.chapter("混淆正文", fontUrl = "/fonts/chapter.woff2")
            val fonts = RecordingChapterFonts(FontFamily.Serif)
            val viewModel = SourceReaderViewModel(
                novelKey = source.novelKey,
                initialChapterKey = source.chapterKey,
                registry = SourceRegistry(listOf(source)),
                preferenceStore = FakeReaderPreferences(),
                offlineLibrary = FakeOfflineLibrary(chapter),
                chapterFonts = fonts,
            )

            advanceUntilIdle()

            assertEquals(listOf("/fonts/chapter.woff2"), fonts.requests)
            assertEquals(FontFamily.Serif, viewModel.state.value.chapterFontFamily)
            assertEquals("混淆正文", viewModel.state.value.chapter?.bodyText)
        }

    @Test
    fun `font failure keeps scrambled chapter out of the reader`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReaderSource()
            val chapter = source.chapter("混淆正文", fontUrl = "/fonts/broken.woff2")
            val viewModel = SourceReaderViewModel(
                novelKey = source.novelKey,
                initialChapterKey = source.chapterKey,
                registry = SourceRegistry(listOf(source)),
                preferenceStore = FakeReaderPreferences(),
                offlineLibrary = FakeOfflineLibrary(chapter),
                chapterFonts = FailingChapterFonts(),
            )

            advanceUntilIdle()

            assertNull(viewModel.state.value.chapter)
            assertEquals("章节字体加载失败", viewModel.state.value.error)
        }

    private class FakeReaderSource : NovelSource, ReaderProvider {
        override val descriptor = SourceDescriptor(
            id = "source",
            displayName = "来源",
            capabilities = setOf(SourceCapability.READER),
        )
        val novelKey = NovelKey(descriptor.id, "book")
        private val volumeKey = VolumeKey(descriptor.id, "volume")
        val chapterKey = ChapterKey(descriptor.id, "chapter")
        var chapterRequests = 0

        override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) =
            SourcePage(listOf(VolumeSummary(volumeKey, novelKey, "正文", 1)), page)

        override suspend fun getChapters(
            novelKey: NovelKey,
            volumeKey: VolumeKey,
            page: Int,
            pageSize: Int,
        ) = SourcePage(listOf(chapter("远端正文").chapter), page)

        override suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent {
            chapterRequests += 1
            return chapter("远端正文")
        }

        fun chapter(body: String, fontUrl: String? = null) = ChapterContent(
            chapter = ChapterSummary(chapterKey, novelKey, volumeKey, "第一章", 1),
            novelTitle = "测试书",
            volumeTitle = "正文",
            bodyText = body,
            fontUrl = fontUrl,
        )
    }

    private class RecordingChapterFonts(
        private val family: FontFamily?,
    ) : ChapterFontAccess {
        val requests = mutableListOf<String?>()

        override suspend fun load(fontUrl: String?): FontFamily? {
            requests += fontUrl
            return family
        }
    }

    private class FailingChapterFonts : ChapterFontAccess {
        override suspend fun load(fontUrl: String?): FontFamily? =
            error("章节字体加载失败")
    }

    private class FakeOfflineLibrary(
        private val content: ChapterContent?,
    ) : OfflineLibraryAccess {
        override val books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
        override val wifiOnly = MutableStateFlow(true)
        override fun setWifiOnly(enabled: Boolean) = Unit
        override fun enqueue(novel: NovelSummary, volumeKey: VolumeKey?) = Unit
        override fun retry(record: OfflineBookRecord) = Unit
        override fun delete(key: NovelKey) = Unit
        override suspend fun readBook(key: NovelKey): OfflineBookRecord? = null
        override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey) = content
    }

    private class FakeReaderPreferences : ReaderPreferencesAccess {
        override val preferences: Flow<ReaderPreferences> = flowOf(ReaderPreferences())
        override suspend fun update(value: ReaderPreferences) = Unit
        override fun progress(bookId: Long): Flow<LocalReadingProgress?> = flowOf(null)
        override suspend fun saveProgress(bookId: Long, progress: LocalReadingProgress) = Unit
        override fun sourceProgress(novelKey: NovelKey): Flow<ReadingProgress?> = flowOf(null)
        override suspend fun saveSourceProgress(progress: ReadingProgress) = Unit
    }
}
