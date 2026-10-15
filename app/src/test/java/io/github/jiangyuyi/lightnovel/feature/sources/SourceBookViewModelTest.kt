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
import io.github.jiangyuyi.lightnovel.core.source.ChapterUnlockProvider
import io.github.jiangyuyi.lightnovel.core.source.CommentProvider
import io.github.jiangyuyi.lightnovel.core.source.CommentSort
import io.github.jiangyuyi.lightnovel.core.source.DetailProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelDetail
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ReaderProvider
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceComment
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun `detail and first chapters appear without waiting for remote shelf`() =
        runTest(mainDispatcherRule.dispatcher) {
            val catalog = CompletableDeferred<SourcePage<VolumeSummary>>()
            val shelf = CompletableDeferred<List<NovelSummary>>()
            val source = object : FakeReadableSource() {
                override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) = catalog.await()
                override suspend fun getRemoteShelf(): List<NovelSummary> = shelf.await()
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))

            try {
                runCurrent()

                assertEquals("测试书", viewModel.state.value.detail?.novel?.title)
                assertFalse(viewModel.state.value.loading)
                assertTrue(viewModel.state.value.volumes.isEmpty())
                assertFalse(shelf.isCompleted)

                catalog.complete(
                    SourcePage(
                        items = listOf(VolumeSummary(source.volumeKey, source.novelKey, "正文")),
                        page = 1,
                    ),
                )
                runCurrent()

                assertEquals(source.volumeKey, viewModel.state.value.expandedVolumeKey)
                assertEquals(
                    listOf("1"),
                    viewModel.state.value.chapters.getValue(source.volumeKey).items.map { it.key.remoteId },
                )
                assertFalse(shelf.isCompleted)

                shelf.complete(listOf(NovelSummary(source.novelKey, "测试书")))
                runCurrent()

                assertTrue(viewModel.state.value.inRemoteShelf == true)
                assertFalse(viewModel.state.value.loading)
                assertNull(viewModel.state.value.error)
            } finally {
                catalog.complete(SourcePage(emptyList(), page = 1))
                shelf.complete(emptyList())
                runCurrent()
            }
        }

    @Test
    fun `slow offline lookup and secondary failures do not hide available detail`() =
        runTest(mainDispatcherRule.dispatcher) {
            val offlineResult = CompletableDeferred<OfflineBookRecord?>()
            val source = object : FakeReadableSource() {
                override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int): SourcePage<VolumeSummary> =
                    throw SourceException(SourceErrorKind.AUTHENTICATION, "请先登录")

                override suspend fun getRemoteShelf(): List<NovelSummary> =
                    throw SourceException(SourceErrorKind.TIMEOUT, "超时")
            }
            val offline = object : FakeOfflineLibrary() {
                override suspend fun readBook(key: NovelKey) = offlineResult.await()
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)), offline)

            try {
                runCurrent()

                assertEquals("测试书", viewModel.state.value.detail?.novel?.title)
                assertFalse(viewModel.state.value.loading)
                assertEquals("请求超时，请稍后重试", viewModel.state.value.shelfError)
                assertFalse(viewModel.state.value.shelfLoading)
                assertNull(viewModel.state.value.inRemoteShelf)
                assertNull(viewModel.state.value.error)

                offlineResult.complete(null)
                runCurrent()

                assertEquals("请先登录该来源", viewModel.state.value.directoryError)
                assertEquals("测试书", viewModel.state.value.detail?.novel?.title)
                assertFalse(viewModel.state.value.loading)
                assertNull(viewModel.state.value.error)
            } finally {
                offlineResult.complete(null)
                runCurrent()
            }
        }

    @Test
    fun `offline detail directory and chapters remain available after online failures`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = object : FakeReadableSource() {
                override suspend fun getNovelDetail(key: NovelKey): NovelDetail =
                    throw SourceException(SourceErrorKind.NETWORK, "离线")

                override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int): SourcePage<VolumeSummary> =
                    throw SourceException(SourceErrorKind.NETWORK, "离线")

                override suspend fun getChapters(
                    novelKey: NovelKey,
                    volumeKey: VolumeKey,
                    page: Int,
                    pageSize: Int,
                ): SourcePage<ChapterSummary> = throw SourceException(SourceErrorKind.NETWORK, "离线")
            }
            val record = OfflineBookRecord(
                novel = NovelSummary(source.novelKey, "离线书"),
                volumes = listOf(VolumeSummary(source.volumeKey, source.novelKey, "离线卷")),
                chapters = listOf(
                    ChapterSummary(
                        ChapterKey(source.descriptor.id, "cached"),
                        source.novelKey,
                        source.volumeKey,
                        "离线章",
                    ),
                ),
            )
            val offline = object : FakeOfflineLibrary() {
                override suspend fun readBook(key: NovelKey) = record
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)), offline)

            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(record.novel, state.detail?.novel)
            assertEquals(record.volumes, state.volumes)
            assertEquals(record.chapters, state.chapters.getValue(source.volumeKey).items)
            assertEquals(record, state.offlineRecord)
            assertFalse(state.loading)
            assertNull(state.error)
            assertNull(state.directoryError)
        }

    @Test
    fun `refresh keeps visible content until replacement chapters reflect unlocked state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val detail = CompletableDeferred<NovelDetail>()
            val catalog = CompletableDeferred<SourcePage<VolumeSummary>>()
            val chapters = CompletableDeferred<SourcePage<ChapterSummary>>()
            var refreshing = false
            val source = object : FakeReadableSource() {
                override suspend fun getNovelDetail(key: NovelKey) =
                    if (refreshing) detail.await() else super.getNovelDetail(key)

                override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) =
                    if (refreshing) catalog.await() else super.getVolumes(key, page, pageSize)

                override suspend fun getChapters(novelKey: NovelKey, volumeKey: VolumeKey, page: Int, pageSize: Int) =
                    if (refreshing) chapters.await() else super.getChapters(novelKey, volumeKey, page, pageSize).let {
                        it.copy(items = it.items.map { chapter -> chapter.copy(locked = true) })
                    }
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
            advanceUntilIdle()
            val previous = viewModel.state.value
            val unlocked = previous.chapters.getValue(source.volumeKey).items.first().copy(locked = false)

            try {
                refreshing = true
                viewModel.load(forceRefresh = true)
                runCurrent()

                assertEquals(previous.detail, viewModel.state.value.detail)
                assertEquals(previous.volumes, viewModel.state.value.volumes)
                assertEquals(
                    previous.chapters.getValue(source.volumeKey).items,
                    viewModel.state.value.chapters.getValue(source.volumeKey).items,
                )
                assertTrue(viewModel.state.value.refreshing)
                assertFalse(viewModel.state.value.loading)

                detail.complete(NovelDetail(NovelSummary(source.novelKey, "更新后的书")))
                runCurrent()
                assertEquals("更新后的书", viewModel.state.value.detail?.novel?.title)

                catalog.complete(SourcePage(previous.volumes, page = 1))
                runCurrent()
                assertTrue(viewModel.state.value.chapters.getValue(source.volumeKey).items.first().locked)
                assertTrue(viewModel.state.value.chapters.getValue(source.volumeKey).loading)

                chapters.complete(SourcePage(listOf(unlocked), page = 1))
                runCurrent()

                assertEquals(listOf(unlocked), viewModel.state.value.chapters.getValue(source.volumeKey).items)
                assertEquals(1, viewModel.state.value.chapters.getValue(source.volumeKey).page)
                assertFalse(viewModel.state.value.refreshing)
            } finally {
                detail.complete(checkNotNull(previous.detail))
                catalog.complete(SourcePage(previous.volumes, page = 1))
                chapters.complete(SourcePage(listOf(unlocked), page = 1))
                runCurrent()
            }
        }

    @Test
    fun `refresh ignores superseded detail catalog shelf and offline responses`() =
        runTest(mainDispatcherRule.dispatcher) {
            val oldDetail = CompletableDeferred<NovelDetail>()
            val oldCatalog = CompletableDeferred<SourcePage<VolumeSummary>>()
            val oldShelf = CompletableDeferred<List<NovelSummary>>()
            val oldOffline = CompletableDeferred<OfflineBookRecord?>()
            var detailRequests = 0
            var catalogRequests = 0
            var shelfRequests = 0
            var offlineRequests = 0
            val source = object : FakeReadableSource() {
                override suspend fun getNovelDetail(key: NovelKey) =
                    if (detailRequests++ == 0) withContext(NonCancellable) { oldDetail.await() }
                    else NovelDetail(NovelSummary(key, "新详情"))

                override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int) =
                    if (catalogRequests++ == 0) withContext(NonCancellable) { oldCatalog.await() }
                    else super.getVolumes(key, page, pageSize)

                override suspend fun getRemoteShelf(): List<NovelSummary> =
                    if (shelfRequests++ == 0) withContext(NonCancellable) { oldShelf.await() }
                    else emptyList()
            }
            val latestOffline = OfflineBookRecord(NovelSummary(source.novelKey, "新离线记录"))
            val offline = object : FakeOfflineLibrary() {
                override suspend fun readBook(key: NovelKey) =
                    if (offlineRequests++ == 0) withContext(NonCancellable) { oldOffline.await() }
                    else latestOffline
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)), offline)
            val staleVolume = VolumeSummary(VolumeKey(source.descriptor.id, "old"), source.novelKey, "旧目录")
            val staleDetail = NovelDetail(NovelSummary(source.novelKey, "旧详情", inRemoteShelf = true))

            try {
                runCurrent()
                viewModel.load(forceRefresh = true)
                runCurrent()

                oldDetail.complete(staleDetail)
                oldCatalog.complete(SourcePage(listOf(staleVolume), page = 1))
                oldShelf.complete(listOf(staleDetail.novel))
                oldOffline.complete(OfflineBookRecord(staleDetail.novel))
                runCurrent()

                val state = viewModel.state.value
                assertEquals("新详情", state.detail?.novel?.title)
                assertEquals(listOf(source.volumeKey), state.volumes.map { it.key })
                assertEquals(source.volumeKey, state.expandedVolumeKey)
                assertEquals(listOf("1"), state.chapters.getValue(source.volumeKey).items.map { it.key.remoteId })
                assertEquals(false, state.inRemoteShelf)
                assertEquals(latestOffline, state.offlineRecord)
                assertFalse(state.loading)
                assertFalse(state.refreshing)
                assertNull(state.error)
                assertNull(state.directoryError)
                assertNull(state.shelfError)
            } finally {
                oldDetail.complete(staleDetail)
                oldCatalog.complete(SourcePage(listOf(staleVolume), page = 1))
                oldShelf.complete(emptyList())
                oldOffline.complete(null)
                runCurrent()
            }
        }

    @Test
    fun `refresh ignores superseded first chapter response`() =
        runTest(mainDispatcherRule.dispatcher) {
            val oldChapters = CompletableDeferred<SourcePage<ChapterSummary>>()
            var chapterRequests = 0
            val source = object : FakeReadableSource() {
                override suspend fun getChapters(novelKey: NovelKey, volumeKey: VolumeKey, page: Int, pageSize: Int) =
                    if (chapterRequests++ == 0) withContext(NonCancellable) { oldChapters.await() }
                    else super.getChapters(novelKey, volumeKey, page, pageSize)
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
            val stale = ChapterSummary(ChapterKey(source.descriptor.id, "stale"), source.novelKey, source.volumeKey, "旧章节")

            try {
                runCurrent()
                viewModel.load(forceRefresh = true)
                runCurrent()

                oldChapters.complete(SourcePage(listOf(stale), page = 1))
                runCurrent()

                val chapters = viewModel.state.value.chapters.getValue(source.volumeKey)
                assertEquals(listOf("1"), chapters.items.map { it.key.remoteId })
                assertFalse(chapters.loading)
                assertNull(chapters.error)
            } finally {
                oldChapters.complete(SourcePage(listOf(stale), page = 1))
                runCurrent()
            }
        }

    @Test
    fun `failed chapter refresh keeps old chapters and reports the real failure without offline data`() =
        runTest(mainDispatcherRule.dispatcher) {
            var failChapters = false
            val source = object : FakeReadableSource() {
                override suspend fun getChapters(novelKey: NovelKey, volumeKey: VolumeKey, page: Int, pageSize: Int) =
                    if (failChapters) throw SourceException(SourceErrorKind.TIMEOUT, "超时")
                    else super.getChapters(novelKey, volumeKey, page, pageSize)
            }
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
            advanceUntilIdle()
            val previous = viewModel.state.value.chapters.getValue(source.volumeKey).items

            failChapters = true
            viewModel.load(forceRefresh = true)
            advanceUntilIdle()

            val chapters = viewModel.state.value.chapters.getValue(source.volumeKey)
            assertEquals(previous, chapters.items)
            assertEquals("请求超时，请稍后重试", chapters.error)
            assertFalse(chapters.loading)
            assertFalse(viewModel.state.value.loading)
            assertNull(viewModel.state.value.error)
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

    @Test
    fun `unlocking a chapter updates its lock state and invokes completion`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReadableSource()
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
            advanceUntilIdle()

            var completed = false
            val lockedChapter = viewModel.state.value.chapters.getValue(source.volumeKey).items
                .first()
                .copy(locked = true, coinPrice = 3)
            viewModel.unlockChapter(lockedChapter, onLoginRequired = {}, onUnlocked = { completed = true })
            advanceUntilIdle()

            assertEquals(listOf(lockedChapter.key), source.unlockedChapters)
            assertFalse(viewModel.state.value.chapters.getValue(source.volumeKey).items.first().locked)
            assertTrue(completed)
            assertNull(viewModel.state.value.unlockError)
        }

    @Test
    fun `authentication failure from unlock requests account login`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeReadableSource(unlockError = SourceException(SourceErrorKind.AUTHENTICATION, "请先登录"))
            val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
            advanceUntilIdle()

            var loginRequested = false
            val chapter = viewModel.state.value.chapters.getValue(source.volumeKey).items.first()
            viewModel.unlockChapter(chapter, onLoginRequired = { loginRequested = true }, onUnlocked = {})
            advanceUntilIdle()

            assertTrue(loginRequested)
            assertEquals("请先登录该来源", viewModel.state.value.unlockError)
        }

    @Test
    fun `comments expose sorting pagination and publish state`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeCommentSource()
        val viewModel = SourceBookViewModel(source.novelKey, SourceRegistry(listOf(source)))
        advanceUntilIdle()

        assertEquals(listOf("hot-1"), viewModel.state.value.comments.map { it.id })
        viewModel.selectCommentSort(CommentSort.LATEST)
        advanceUntilIdle()
        assertEquals(CommentSort.LATEST, source.lastSort)
        assertEquals(listOf("latest-1"), viewModel.state.value.comments.map { it.id })
        viewModel.publishComment("  新评论  ", onLoginRequired = {}, onPublished = {})
        advanceUntilIdle()
        assertEquals("新评论", source.published)
        assertFalse(viewModel.state.value.publishingComment)
    }

    private open class FakeReadableSource(
        private val unlockError: Throwable? = null,
        sourceId: String = "source",
        extraCapabilities: Set<SourceCapability> = emptySet(),
    ) : NovelSource, DetailProvider, ReaderProvider, ShelfProvider, ChapterUnlockProvider {
        override val descriptor = SourceDescriptor(
            id = sourceId,
            displayName = "来源",
            capabilities = setOf(
                SourceCapability.DETAIL,
                SourceCapability.READER,
                SourceCapability.REMOTE_SHELF,
            ) + extraCapabilities,
        )
        val novelKey = NovelKey(descriptor.id, "book")
        val volumeKey = VolumeKey(descriptor.id, "volume")
        val shelfMutations = mutableListOf<Boolean>()
        val unlockedChapters = mutableListOf<ChapterKey>()

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

        override suspend fun unlockChapter(chapterKey: ChapterKey) {
            unlockError?.let { throw it }
            unlockedChapters += chapterKey
        }

        private fun chapter(number: Int) = ChapterSummary(
            key = ChapterKey(descriptor.id, number.toString()),
            novelKey = novelKey,
            volumeKey = volumeKey,
            title = "第 $number 章",
            order = number,
        )
    }

    private class FakeCommentSource : FakeReadableSource(
        sourceId = "source-comments",
        extraCapabilities = setOf(SourceCapability.COMMENTS),
    ), CommentProvider {
        var lastSort: CommentSort? = null
        var published: String? = null

        override suspend fun getComments(
            novelKey: NovelKey,
            sort: CommentSort,
            page: Int,
            pageSize: Int,
        ): SourcePage<SourceComment> {
            lastSort = sort
            return SourcePage(
                items = listOf(
                    SourceComment(id = "${sort.name.lowercase()}-$page", authorName = "用户", content = "内容"),
                ),
                page = page,
                total = 2,
                hasMore = page == 1,
            )
        }

        override suspend fun publishComment(novelKey: NovelKey, content: String, ratingStars: Int): SourceComment {
            published = content
            return SourceComment(id = "published", authorName = "用户", content = content, ratingStars = ratingStars.takeIf { it > 0 })
        }
    }

    private open class FakeOfflineLibrary : OfflineLibraryAccess {
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
