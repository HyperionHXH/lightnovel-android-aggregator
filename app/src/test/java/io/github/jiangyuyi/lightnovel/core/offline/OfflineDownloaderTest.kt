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
        assertEquals(fonts.requests, fonts.retained)
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

    @Test
    fun `volume download retains full directory and completing another volume skips checkpoints`() = runTest {
        val source = FakeDownloadSource(multipleVolumes = true)
        val store = OfflineFileStore(temporaryFolder.newFolder("volumes"))
        val downloader = OfflineDownloader(SourceRegistry(listOf(source)), store, RecordingChapterFonts())

        val partial = downloader.download(source.novelKey, "volume")
        assertEquals(2, partial.volumes.size)
        assertEquals(4, partial.chapters.size)
        assertFalse(partial.isFullyDownloaded())
        assertEquals(OfflineChapterStatus.NOT_DOWNLOADED, partial.chapterStatus(source.chapter("4", secondVolume = true)))

        source.chapterRequests.clear()
        val complete = downloader.download(source.novelKey)
        assertEquals(listOf("4"), source.chapterRequests)
        assertTrue(complete.isFullyDownloaded())
        assertEquals(OfflineChapterStatus.LOCKED, complete.chapterStatus(source.chapter("3", locked = true)))

        source.addedChapter = true
        source.chapterRequests.clear()
        val updated = downloader.download(source.novelKey)
        assertEquals(listOf("5"), source.chapterRequests)
        assertTrue(updated.isFullyDownloaded())
        store.deleteBook(source.novelKey)
        source.chapterRequests.clear()
        downloader.download(source.novelKey)
        assertEquals(listOf("1", "2", "4", "5"), source.chapterRequests)
    }

    @Test
    fun `missing checkpoint body is fetched again and equal remote ids stay source isolated`() = runTest {
        val first = FakeDownloadSource()
        val second = FakeDownloadSource(sourceId = "other")
        val store = OfflineFileStore(temporaryFolder.newFolder("isolated"))
        val downloader = OfflineDownloader(SourceRegistry(listOf(first, second)), store, RecordingChapterFonts())
        val complete = downloader.download(first.novelKey)
        store.deleteBook(first.novelKey)
        store.writeBook(complete)
        first.chapterRequests.clear()
        downloader.download(first.novelKey)
        assertEquals(listOf("1", "2"), first.chapterRequests)
        downloader.download(second.novelKey)
        store.deleteBook(first.novelKey)
        assertNotNull(store.readChapter(second.novelKey, second.chapterKey("1")))
        assertTrue(store.readBook(second.novelKey)!!.isFullyDownloaded())
    }

    @Test
    fun `fresh directory unlocks prior locked chapter and repairs corrupt checkpoint`() = runTest {
        val source = FakeDownloadSource()
        val root = temporaryFolder.newFolder("corrupt")
        val store = OfflineFileStore(root)
        val downloader = OfflineDownloader(SourceRegistry(listOf(source)), store, RecordingChapterFonts())
        downloader.download(source.novelKey)
        val bookDirectory = java.io.File(root, OfflineFileStore.stableHash("${source.novelKey.sourceId}\u0000${source.novelKey.remoteId}"))
        java.io.File(bookDirectory, "chapters/${OfflineFileStore.stableHash("1")}.json").writeText("broken json")
        source.unlocked = true
        source.chapterRequests.clear()
        val record = downloader.download(source.novelKey)
        assertEquals(listOf("1", "3"), source.chapterRequests)
        assertEquals(setOf("1", "2", "3"), record.downloadedChapterIds)
        assertFalse(record.chapters.first { it.key.remoteId == "3" }.locked)
        assertNotNull(store.readChapter(source.novelKey, source.chapterKey("1")))
    }

    @Test
    fun `remote removals and account locks preserve downloaded entries bodies and both exports`() = runTest {
        val source = FakeDownloadSource(multipleVolumes = true)
        val store = OfflineFileStore(temporaryFolder.newFolder("retained"))
        val downloader = OfflineDownloader(SourceRegistry(listOf(source)), store, RecordingChapterFonts())
        downloader.download(source.novelKey)
        source.removeSecondVolume = true
        source.removeFirstChapter = true
        source.relocked = true
        source.chapterRequests.clear()
        val refreshed = downloader.download(source.novelKey)
        assertEquals(setOf("1", "2", "4"), refreshed.downloadedChapterIds)
        assertEquals(setOf("volume", "second"), refreshed.volumes.map { it.key.remoteId }.toSet())
        assertEquals(setOf("1", "2", "3", "4"), refreshed.chapters.map { it.key.remoteId }.toSet())
        assertEquals(3, refreshed.completedChapters)
        assertTrue(source.chapterRequests.isEmpty())
        assertNotNull(store.readChapter(source.novelKey, source.chapterKey("1")))
        assertNotNull(store.readChapter(source.novelKey, source.chapterKey("4")))
        val legacyLocked = refreshed.copy(chapters = refreshed.chapters.map { it.copy(locked = true) })
        assertEquals(3, legacyLocked.completedChapters)
        assertEquals(3, legacyLocked.totalChapters)
        assertEquals(OfflineChapterStatus.DOWNLOADED, legacyLocked.chapterStatus(legacyLocked.chapters.first { it.key.remoteId == "2" }))
        val txt = java.io.ByteArrayOutputStream()
        val textResult = io.github.jiangyuyi.lightnovel.core.txt.TxtExporter().export(
            legacyLocked, { store.readChapter(source.novelKey, it) }, txt,
        )
        assertEquals(3, textResult.exportedChapters)
        assertTrue(txt.toString(Charsets.UTF_8.name()).contains("章节 4"))
        val epub = java.io.ByteArrayOutputStream()
        val epubResult = io.github.jiangyuyi.lightnovel.core.epub.EpubExporter().export(
            legacyLocked, { store.readChapter(source.novelKey, it) }, epub,
        )
        assertEquals(3, epubResult.exportedChapters)
    }

    private class FakeDownloadSource(
        var failChapterTwo: Boolean = false,
        private val multipleVolumes: Boolean = false,
        sourceId: String = "source",
    ) : NovelSource, DetailProvider, ReaderProvider {
        override val descriptor = SourceDescriptor(
            id = sourceId,
            displayName = "来源",
            capabilities = setOf(SourceCapability.DETAIL, SourceCapability.READER),
        )
        val novelKey = NovelKey(descriptor.id, "book")
        private val volumeKey = VolumeKey(descriptor.id, "volume")
        val chapterRequests = mutableListOf<String>()
        var addedChapter = false
        var unlocked = false
        var removeSecondVolume = false
        var removeFirstChapter = false
        var relocked = false

        override suspend fun getNovelDetail(key: NovelKey) = NovelDetail(
            NovelSummary(key, "测试书"),
        )

        override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) = SourcePage(
            items = if (page == 1) buildList {
                add(VolumeSummary(volumeKey, novelKey, "正文", 3))
                if (multipleVolumes && !removeSecondVolume) add(VolumeSummary(VolumeKey(descriptor.id, "second"), novelKey, "第二卷", if (addedChapter) 2 else 1))
            } else emptyList(),
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
                if (volumeKey.remoteId == "second") buildList {
                    add(chapter("4", secondVolume = true))
                    if (addedChapter) add(chapter("5", secondVolume = true))
                } else listOfNotNull(
                    chapter("1").takeUnless { removeFirstChapter },
                    chapter("2", locked = relocked), chapter("3", locked = true),
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
                chapter = chapter(chapterKey.remoteId, secondVolume = chapterKey.remoteId in setOf("4", "5")),
                novelTitle = "测试书",
                volumeTitle = "正文",
                bodyText = "章节 ${chapterKey.remoteId}",
                fontUrl = "/fonts/chapter.woff2",
            )
        }

        fun chapterKey(remoteId: String) = ChapterKey(descriptor.id, remoteId)

        fun chapter(remoteId: String, locked: Boolean = false, secondVolume: Boolean = false) = ChapterSummary(
            key = chapterKey(remoteId),
            novelKey = novelKey,
            volumeKey = if (secondVolume) VolumeKey(descriptor.id, "second") else volumeKey,
            title = "第 $remoteId 章",
            order = remoteId.toInt(),
            locked = locked && !unlocked,
        )
    }

    private class RecordingChapterFonts : ChapterFontAccess {
        val requests = mutableListOf<String?>()
        val retained = mutableListOf<String?>()

        override suspend fun load(fontUrl: String?): FontFamily? {
            requests += fontUrl
            return FontFamily.Serif
        }

        override suspend fun retainForOffline(fontUrl: String?) {
            load(fontUrl)
            retained += fontUrl
        }
    }
}
