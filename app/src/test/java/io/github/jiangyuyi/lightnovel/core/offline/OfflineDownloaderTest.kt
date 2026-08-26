package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.DetailProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelDetail
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ReaderProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import androidx.compose.ui.text.font.FontFamily

class OfflineDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `download stores readable unlocked chapters and reports progress`() = runTest {
        val source = FakeDownloadSource()
        val store = OfflineFileStore(temporaryFolder.newFolder("download"))
        val fonts = RecordingChapterFonts()
        val updates = mutableListOf<OfflineBookRecord>()
        val downloader = OfflineDownloader(
            registry = SourceRegistry(listOf(source)),
            store = store,
            chapterFonts = fonts,
            onUpdated = updates::add,
            now = { updates.size.toLong() },
        )

        val result = downloader.download(source.novelKey)

        assertEquals(OfflineDownloadStatus.COMPLETE, result.status)
        assertEquals(2, result.totalChapters)
        assertEquals(2, result.completedChapters)
        assertEquals(listOf("1", "2"), source.chapterRequests)
        assertEquals(listOf("/fonts/chapter.woff2", "/fonts/chapter.woff2"), fonts.requests)
        assertNotNull(store.readChapter(source.novelKey, source.chapterKey("1")))
        assertFalse(store.hasChapter(source.novelKey, source.chapterKey("3")))
        assertTrue(updates.any { it.status == OfflineDownloadStatus.DOWNLOADING })
    }

    @Test
    fun `retry resumes after chapters already stored`() = runTest {
        val source = FakeDownloadSource(failChapterTwo = true)
        val store = OfflineFileStore(temporaryFolder.newFolder("resume"))
        val downloader = OfflineDownloader(SourceRegistry(listOf(source)), store, RecordingChapterFonts())

        runCatching { downloader.download(source.novelKey) }
        assertEquals(setOf("1"), store.readBook(source.novelKey)?.downloadedChapterIds)
        source.failChapterTwo = false
        source.chapterRequests.clear()

        val result = downloader.download(source.novelKey)

        assertEquals(listOf("2"), source.chapterRequests)
        assertEquals(OfflineDownloadStatus.COMPLETE, result.status)
        assertEquals(setOf("1", "2"), result.downloadedChapterIds)
    }

    @Test
    fun `font failure does not store an unreadable chapter`() = runTest {
        val source = FakeDownloadSource()
        val store = OfflineFileStore(temporaryFolder.newFolder("font-failure"))
        val downloader = OfflineDownloader(
            SourceRegistry(listOf(source)),
            store,
            object : ChapterFontAccess {
                override suspend fun load(fontUrl: String?): FontFamily? =
                    error("章节字体转换失败")
            },
        )

        runCatching { downloader.download(source.novelKey) }

        assertFalse(store.hasChapter(source.novelKey, source.chapterKey("1")))
        assertEquals(OfflineDownloadStatus.FAILED, store.readBook(source.novelKey)?.status)
    }

    private class FakeDownloadSource(
        var failChapterTwo: Boolean = false,
    ) : NovelSource, DetailProvider, ReaderProvider {
        override val descriptor = SourceDescriptor(
            id = "source",
            displayName = "来源",
            capabilities = setOf(SourceCapability.DETAIL, SourceCapability.READER),
        )
        val novelKey = NovelKey(descriptor.id, "book")
        private val volumeKey = VolumeKey(descriptor.id, "volume")
        val chapterRequests = mutableListOf<String>()

        override suspend fun getNovelDetail(key: NovelKey) = NovelDetail(
            NovelSummary(key, "测试书"),
        )

        override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) = SourcePage(
            items = if (page == 1) listOf(VolumeSummary(volumeKey, novelKey, "正文", 3)) else emptyList(),
            page = page,
            hasMore = false,
        )

        override suspend fun getChapters(
            novelKey: NovelKey,
            volumeKey: VolumeKey,
            page: Int,
            pageSize: Int,
        ) = SourcePage(
            items = if (page == 1) {
                listOf(
                    chapter("1"),
                    chapter("2"),
                    chapter("3", locked = true),
                )
            } else {
                emptyList()
            },
            page = page,
            hasMore = false,
        )

        override suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent {
            chapterRequests += chapterKey.remoteId
            if (failChapterTwo && chapterKey.remoteId == "2") {
                throw SourceException(SourceErrorKind.NETWORK, "offline")
            }
            return ChapterContent(
                chapter = chapter(chapterKey.remoteId),
                novelTitle = "测试书",
                volumeTitle = "正文",
                bodyText = "章节 ${chapterKey.remoteId}",
                fontUrl = "/fonts/chapter.woff2",
            )
        }

        fun chapterKey(remoteId: String) = ChapterKey(descriptor.id, remoteId)

        private fun chapter(remoteId: String, locked: Boolean = false) = ChapterSummary(
            key = chapterKey(remoteId),
            novelKey = novelKey,
            volumeKey = volumeKey,
            title = "第 $remoteId 章",
            order = remoteId.toInt(),
            locked = locked,
        )
    }

    private class RecordingChapterFonts : ChapterFontAccess {
        val requests = mutableListOf<String?>()

        override suspend fun load(fontUrl: String?): FontFamily? {
            requests += fontUrl
            return FontFamily.Serif
        }
    }
}
