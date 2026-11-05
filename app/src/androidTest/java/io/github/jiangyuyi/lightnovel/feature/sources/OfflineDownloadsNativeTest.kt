package io.github.jiangyuyi.lightnovel.feature.sources

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.offline.OfflineFileStore
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibrary
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookStore
import io.github.jiangyuyi.lightnovel.core.reader.EmptyChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.source.*
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfScreen
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic native smoke: production screens, view models, library, downloader and file store. */
class OfflineDownloadsNativeTest {
    @get:Rule val compose = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureId = UUID.randomUUID().toString()
    private val root = File(base.cacheDir, "offline_native_$fixtureId").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("${fixtureId}_$name", mode)
    }
    private val source = FixtureSource("offline-native-$fixtureId")
    private val registry = SourceRegistry(listOf(source))
    private val workManager = run {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WorkManager.getInstance(context)
    }
    private val library = OfflineLibrary(context, registry, EmptyChapterFontAccess, workManager = workManager)
    private val store = OfflineFileStore(File(root, "offline_library"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanUp() {
        source.release.complete(Unit)
        library.delete(source.novelKey)
        runBlocking { await { store.readBook(source.novelKey) == null } }
        scope.cancel()
        root.deleteRecursively()
        base.deleteSharedPreferences("${fixtureId}_offline_download_preferences")
    }

    @Test fun completeMarkDeleteAndDownloadAgainThroughProductionUi() {
        lateinit var book: SourceBookViewModel
        lateinit var management: AggregateBookshelfViewModel
        compose.runOnUiThread {
            book = SourceBookViewModel(source.novelKey, registry, library)
            management = AggregateBookshelfViewModel(registry, library, initialDownloadedOnly = true)
        }
        var showManagement by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.statusBarsPadding()) {
                    TextButton(onClick = { showManagement = !showManagement }) { Text(if (showManagement) "返回详情" else "下载管理") }
                    Column(Modifier.weight(1f)) {
                        if (showManagement) AggregateBookshelfScreen(
                            viewModel = management, onBook = {}, onAccounts = {}, title = "下载与导出",
                        ) else SourceBookScreen(book, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        waitFor("下载全书")
        compose.onNodeWithText("下载全书").performScrollTo().performClick()
        finishQueuedDownload()
        waitFor("全书已下载")
        compose.onNodeWithText("全书已下载").assertIsNotEnabled()
        screenshot("01-complete-detail")
        compose.onNodeWithText("章节").performScrollTo().performClick()
        compose.onAllNodesWithText("本卷已下载")[0].performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("已下载").assertIsDisplayed()
        screenshot("02-downloaded-volume-chapter")
        compose.onNodeWithText("下载管理").performClick()
        waitFor("查看下载章节")
        compose.onNodeWithText("查看下载章节").performScrollTo().performClick()
        compose.onNodeWithText("第一章 · 已下载").assertIsDisplayed()
        compose.onNodeWithText("锁定章 · 锁定").assertIsDisplayed()
        screenshot("03-offline-directory")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("删除离线书籍").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        waitFor("还没有已下载的书籍")
        compose.onNodeWithText("返回详情").performClick()
        // The same live view model must reset without refreshing or reopening the book.
        waitFor("下载全书")
        compose.onNodeWithText("下载全书").performScrollTo().assertIsEnabled()
        screenshot("04-deleted-download-enabled")
        source.requests.clear()
        compose.onNodeWithText("下载全书").performClick()
        finishQueuedDownload()
        waitFor("全书已下载")
        assertEquals(listOf("one", "two"), source.requests.toList())
    }

    @Test fun partialMultiVolumeDirectoryRemainsVisibleOfflineAndNewChaptersCanResume() {
        runBlocking {
            library.enqueue(source.novel, source.volumes.first().key)
            finishQueuedDownloadSuspending()
        }
        lateinit var management: AggregateBookshelfViewModel
        compose.runOnUiThread {
            management = AggregateBookshelfViewModel(registry, library, initialDownloadedOnly = true)
        }
        compose.setContent { MaterialTheme { AggregateBookshelfScreen(management, {}, {}, title = "下载与导出") } }
        waitFor("查看下载章节")
        source.offline = true
        compose.onNodeWithText("查看下载章节").performScrollTo().performClick()
        compose.onNodeWithText("第一章 · 已下载").assertIsDisplayed()
        compose.onNodeWithText("第二章 · 未下载").assertIsDisplayed()
        compose.onNodeWithText("锁定章 · 锁定").assertIsDisplayed()
        screenshot("05-partial-multivolume-offline")
        compose.onNodeWithText("关闭").performClick()
        source.offline = false
        source.added = true
        source.failSecond = true
        source.requests.clear()
        compose.onNodeWithContentDescription("继续下载").performScrollTo().performClick()
        assertTrue(runCatching { finishQueuedDownload() }.isFailure)
        compose.onNodeWithText("查看下载章节").performScrollTo().performClick()
        compose.onNodeWithText("第二章 · 下载失败").assertIsDisplayed()
        compose.onNodeWithText("新增章 · 未下载").performScrollTo().assertIsDisplayed()
        screenshot("06-failed-chapter-directory")
        compose.onNodeWithText("关闭").performClick()
        source.failSecond = false
        source.requests.clear()
        compose.onNodeWithContentDescription("重试下载").performScrollTo().performClick()
        finishQueuedDownload()
        compose.onNodeWithText("查看下载章节").performScrollTo().performClick()
        compose.onNodeWithText("新增章 · 已下载").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("two", "new"), source.requests.toList())
        screenshot("07-new-chapter-resumed")
    }

    @Test fun deletionWaitsForNonCooperativeWorkerAndStaleWorkCannotResurrectBook() = runBlocking {
        source.blockChapter = true
        val queued = OfflineBookRecord(source.novel, requestId = "old-request")
        store.writeBook(queued)
        val worker = scope.launch { library.executeDownload(source.novelKey, null, queued.requestId) }
        withTimeout(10_000) { source.started.await() }
        library.delete(source.novelKey)
        assertTrue(library.books.value.none { it.novel.key == source.novelKey })
        assertNull(library.readBook(source.novelKey))
        source.release.complete(Unit)
        worker.join()
        await { store.readBook(source.novelKey) == null }
        source.blockChapter = false
        library.enqueue(source.novel)
        val replacement = awaitQueued()
        assertNotEquals(queued.requestId, replacement.requestId)
        library.executeDownload(source.novelKey, null, queued.requestId)
        assertEquals(OfflineDownloadStatus.QUEUED, store.readBook(source.novelKey)!!.status)
        library.executeDownload(source.novelKey, null, replacement.requestId)
        assertTrue(store.readBook(source.novelKey)!!.isFullyDownloaded())
    }

    @Test fun retryAfterSelectingAnotherVolumeStillDownloadsRetainedFailures() = runBlocking {
        source.failSecond = true
        library.enqueue(source.novel)
        assertTrue(runCatching { finishQueuedDownloadSuspending() }.isFailure)
        source.failSecond = false
        library.enqueue(source.novel, source.volumes.first().key)
        finishQueuedDownloadSuspending()
        val selected = store.readBook(source.novelKey)!!
        assertEquals(OfflineDownloadStatus.FAILED, selected.status)
        assertEquals("first", selected.selectedVolumeId)
        source.requests.clear()
        library.retry(selected)
        assertNull(awaitQueued().selectedVolumeId)
        finishQueuedDownloadSuspending()
        assertEquals(listOf("two"), source.requests.toList())
        assertTrue(store.readBook(source.novelKey)!!.isFullyDownloaded())
    }

    @Test fun startupReloadRetriesConcurrentPublishAndKeepsOtherStoredBooks() = runBlocking {
        val backing = OfflineFileStore(File(root, "reload-fixture"))
        val older = OfflineBookRecord(source.novel.copy(key = NovelKey(source.descriptor.id, "older")))
        backing.writeBook(older)
        val scanned = CompletableDeferred<Unit>()
        val releaseScan = CompletableDeferred<Unit>()
        var first = true
        val gated = object : OfflineBookStore by backing {
            override suspend fun listBooks(): List<OfflineBookRecord> {
                val snapshot = backing.listBooks()
                if (first) {
                    first = false
                    scanned.complete(Unit)
                    releaseScan.await()
                }
                return snapshot
            }
        }
        val local = OfflineLibrary(context, registry, EmptyChapterFontAccess, workManager = workManager, bookStore = gated)
        try {
            withTimeout(10_000) { scanned.await() }
            local.enqueue(source.novel)
            await { local.books.value.any { it.novel.key == source.novelKey } }
            releaseScan.complete(Unit)
            await { local.books.value.map { it.novel.key }.toSet() == setOf(older.novel.key, source.novelKey) }
            local.delete(older.novel.key)
            await { backing.readBook(older.novel.key) == null }
            assertTrue(local.books.value.none { it.novel.key == older.novel.key })
        } finally {
            releaseScan.complete(Unit)
            local.delete(source.novelKey)
            await { backing.readBook(source.novelKey) == null }
        }
    }

    @Test fun refusedDeletionRestoresBookAndDisplaysErrorThroughManagementUi() {
        runBlocking { store.writeBook(OfflineBookRecord(source.novel, status = OfflineDownloadStatus.COMPLETE)) }
        val refusing = object : OfflineBookStore by store {
            override suspend fun deleteBook(key: NovelKey) { error("无法删除离线书籍：测试文件夹拒绝访问") }
        }
        val local = OfflineLibrary(context, registry, EmptyChapterFontAccess, workManager = workManager, bookStore = refusing)
        lateinit var management: AggregateBookshelfViewModel
        compose.runOnUiThread {
            management = AggregateBookshelfViewModel(registry, local, initialDownloadedOnly = true)
        }
        compose.setContent { MaterialTheme { AggregateBookshelfScreen(management, {}, {}, title = "下载与导出") } }
        waitFor("查看下载章节")
        compose.onNodeWithContentDescription("删除离线书籍").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        waitFor("无法删除离线书籍：测试文件夹拒绝访问")
        compose.onNodeWithText("无法删除离线书籍：测试文件夹拒绝访问").assertIsDisplayed()
        assertTrue(runBlocking { store.readBook(source.novelKey) } != null)
        assertEquals(OfflineDownloadStatus.FAILED, local.books.value.single().status)
        screenshot("08-delete-failure-restored")
    }

    @Test fun remoteRemovedVolumeAndAccountLockKeepOfflineManagementAndReadingEntries() {
        runBlocking {
            library.enqueue(source.novel)
            finishQueuedDownloadSuspending()
            source.removeSecondVolume = true
            source.relocked = true
            library.enqueue(source.novel)
            finishQueuedDownloadSuspending()
            assertEquals(setOf("one", "two"), store.readBook(source.novelKey)!!.downloadedChapterIds)
            val output = java.io.ByteArrayOutputStream()
            assertEquals(2, library.exportTxt(source.novelKey, output).exportedChapters)
        }
        lateinit var book: SourceBookViewModel
        lateinit var management: AggregateBookshelfViewModel
        compose.runOnUiThread {
            book = SourceBookViewModel(source.novelKey, registry, library)
            management = AggregateBookshelfViewModel(registry, library, initialDownloadedOnly = true)
        }
        var showDetails by mutableStateOf(false)
        var opened: ChapterKey? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.statusBarsPadding()) {
                TextButton(onClick = { showDetails = !showDetails }) { Text("查看详情") }
                Column(Modifier.weight(1f)) {
                    if (showDetails) SourceBookScreen(book, {}, {}, { opened = it }, {}, {})
                    else AggregateBookshelfScreen(management, {}, {}, title = "下载与导出")
                }
            }
        } }
        waitFor("查看下载章节")
        compose.onNodeWithText("查看下载章节").performScrollTo().performClick()
        compose.onNodeWithText("第一章 · 已下载").assertIsDisplayed()
        compose.onNodeWithText("第二章 · 已下载").assertIsDisplayed()
        screenshot("09-removed-volume-retained")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("查看详情").performClick()
        waitFor("全书已下载")
        compose.onNodeWithText("章节").performScrollTo().performClick()
        compose.onNodeWithText("第一章").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ChapterKey(source.descriptor.id, "one"), opened) }
        screenshot("10-account-locked-checkpoint-readable")
        compose.onNodeWithText("第二卷").performScrollTo().performClick()
        compose.onNodeWithText("第二章").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ChapterKey(source.descriptor.id, "two"), opened) }
    }

    @Test fun sharedPurchasedAccessInvalidatesExistingAndReopenedDetailWithoutDirectoryRefresh() {
        runBlocking { library.enqueue(source.novel); finishQueuedDownloadSuspending() }
        lateinit var book: SourceBookViewModel
        compose.runOnUiThread { book = SourceBookViewModel(source.novelKey, registry, library) }
        compose.waitUntil(10_000) { book.state.value.bookDownloaded }
        val purchased = ChapterKey(source.descriptor.id, "locked")
        runBlocking { library.markChapterUnlocked(store.readBook(source.novelKey)!!.chapters.first { it.key == purchased }) }
        compose.waitUntil(10_000) { !book.state.value.bookDownloaded && book.state.value.offlineRecord?.unlockedChapterIds?.contains("locked") == true }
        lateinit var reopened: SourceBookViewModel
        compose.runOnUiThread { reopened = SourceBookViewModel(source.novelKey, registry, library) }
        compose.waitUntil(10_000) { reopened.state.value.detail != null && reopened.state.value.offlineRecord != null }
        assertFalse(reopened.state.value.bookDownloaded)
        source.requests.clear()
        compose.runOnUiThread { reopened.downloadBook() }
        finishQueuedDownload()
        assertEquals(listOf("locked"), source.requests.toList())
        assertTrue(runBlocking { store.readBook(source.novelKey) }!!.downloadedChapterIds.contains("locked"))
    }

    private fun finishQueuedDownload() = runBlocking { finishQueuedDownloadSuspending() }
    private suspend fun finishQueuedDownloadSuspending() {
        val record = awaitQueued()
        library.executeDownload(source.novelKey, record.selectedVolumeId, record.requestId)
    }
    private suspend fun awaitQueued(): OfflineBookRecord {
        await { store.readBook(source.novelKey)?.status == OfflineDownloadStatus.QUEUED }
        return store.readBook(source.novelKey)!!
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }
    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun screenshot(name: String) {
        val directory = File(base.getExternalFilesDir(null), "offline-download-smoke").apply { mkdirs() }
        compose.waitForIdle()
        // Compose idleness does not include the platform dialog window's enter animation.
        android.os.SystemClock.sleep(500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private class FixtureSource(id: String) : NovelSource, DetailProvider, ReaderProvider {
        override val descriptor = SourceDescriptor(id, "离线测试来源", setOf(SourceCapability.DETAIL, SourceCapability.READER))
        val novelKey = NovelKey(id, "book")
        val novel get() = NovelSummary(novelKey, "离线下载回归书", volumeCount = if (removeSecondVolume) 1 else 2,
            chapterCount = if (removeSecondVolume) 2 else if (added) 4 else 3)
        val volumes get() = listOf(
            VolumeSummary(VolumeKey(descriptor.id, "first"), novelKey, "第一卷", 2),
            VolumeSummary(VolumeKey(descriptor.id, "second"), novelKey, "第二卷", if (added) 2 else 1),
        ).filterNot { removeSecondVolume && it.key.remoteId == "second" }
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        @Volatile var offline = false
        @Volatile var added = false
        @Volatile var blockChapter = false
        @Volatile var failSecond = false
        @Volatile var removeSecondVolume = false
        @Volatile var relocked = false
        private fun online() { check(!offline) { "离线" } }
        private fun chapter(id: String, volume: VolumeSummary, title: String, locked: Boolean = false) =
            ChapterSummary(ChapterKey(descriptor.id, id), novelKey, volume.key, title, locked = locked)
        private fun chapters(volume: VolumeSummary) = if (volume.key.remoteId == "first") listOf(
            chapter("one", volume, "第一章", locked = relocked), chapter("locked", volume, "锁定章", locked = true),
        ) else buildList {
            add(chapter("two", volume, "第二章"))
            if (added) add(chapter("new", volume, "新增章"))
        }
        override suspend fun getNovelDetail(key: NovelKey): NovelDetail { online(); return NovelDetail(novel) }
        override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int): SourcePage<VolumeSummary> {
            online(); return SourcePage(volumes, page, hasMore = false)
        }
        override suspend fun getChapters(novelKey: NovelKey, volumeKey: VolumeKey, page: Int, pageSize: Int): SourcePage<ChapterSummary> {
            online(); return SourcePage(chapters(volumes.first { it.key == volumeKey }), page, hasMore = false)
        }
        override suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent {
            online()
            if (blockChapter) withContext(NonCancellable) { started.complete(Unit); release.await() }
            requests += chapterKey.remoteId
            if (failSecond && chapterKey.remoteId == "two") error("确定性章节失败")
            val chapter = volumes.flatMap(::chapters).first { it.key == chapterKey }
            return ChapterContent(chapter, novel.title, volumes.first { it.key == chapter.volumeKey }.title, "离线正文")
        }
    }
}
