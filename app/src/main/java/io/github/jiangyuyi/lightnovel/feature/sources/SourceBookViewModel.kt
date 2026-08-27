package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.offline.EmptyOfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.preferences.EmptyReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelDetail
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class VolumeChaptersState(
    val items: List<ChapterSummary> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)

data class SourceBookState(
    val source: SourceDescriptor? = null,
    val detail: NovelDetail? = null,
    val volumes: List<VolumeSummary> = emptyList(),
    val expandedVolumeKey: VolumeKey? = null,
    val chapters: Map<VolumeKey, VolumeChaptersState> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val startingReader: Boolean = false,
    val shelfSupported: Boolean = false,
    val unlockSupported: Boolean = false,
    val unlockingChapterKey: ChapterKey? = null,
    val unlockError: String? = null,
    val inRemoteShelf: Boolean? = null,
    val shelfLoading: Boolean = false,
    val shelfError: String? = null,
    val offlineRecord: OfflineBookRecord? = null,
    val readingProgress: ReadingProgress? = null,
    val error: String? = null,
    val directoryError: String? = null,
)

class SourceBookViewModel(
    private val novelKey: NovelKey,
    private val registry: SourceRegistry,
    private val offlineLibrary: OfflineLibraryAccess = EmptyOfflineLibraryAccess,
    private val preferenceStore: ReaderPreferencesAccess = EmptyReaderPreferencesAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SourceBookState(
            source = registry.get(novelKey.sourceId)?.descriptor,
            shelfSupported = registry.shelfProvider(novelKey.sourceId) != null,
            unlockSupported = registry.unlockProvider(novelKey.sourceId) != null,
        ),
    )
    val state: StateFlow<SourceBookState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private val chapterJobs = mutableMapOf<VolumeKey, Job>()

    init {
        viewModelScope.launch {
            offlineLibrary.books.collect { books ->
                _state.value = _state.value.copy(
                    offlineRecord = books.firstOrNull { it.novel.key == novelKey },
                )
            }
        }
        viewModelScope.launch {
            preferenceStore.sourceProgress(novelKey).collect { progress ->
                _state.value = _state.value.copy(readingProgress = progress)
            }
        }
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        val detailProvider = registry.detailProvider(novelKey.sourceId)
        val readerProvider = registry.readerProvider(novelKey.sourceId)
        val shelfProvider = registry.shelfProvider(novelKey.sourceId)
        if (detailProvider == null || readerProvider == null) {
            _state.value = _state.value.copy(loading = false, error = "该来源不支持书籍详情或阅读")
            return
        }
        loadJob?.cancel()
        chapterJobs.values.forEach(Job::cancel)
        chapterJobs.clear()
        val hasContent = _state.value.detail != null
        _state.value = _state.value.copy(
            loading = !hasContent,
            refreshing = hasContent && forceRefresh,
            error = null,
            directoryError = null,
            // A book screen can be revisited with the same ViewModel after a purchase.
            // Re-read chapter summaries so the server's unlocked state is reflected.
            chapters = emptyMap(),
        )
        loadJob = viewModelScope.launch {
            supervisorScope {
                val detailRequest = async { detailProvider.getNovelDetail(novelKey) }
                val volumesRequest = async { readerProvider.getVolumes(novelKey, page = 1, pageSize = 50) }
                val shelfRequest = shelfProvider?.let { provider ->
                    async { provider.isInRemoteShelf(novelKey) }
                }
                val offlineRequest = async { offlineLibrary.readBook(novelKey) }
                val detailResult = runSourceCatching { detailRequest.await() }
                val volumeResult = runSourceCatching { volumesRequest.await() }
                val shelfResult = shelfRequest?.let { request -> runSourceCatching { request.await() } }
                val offlineRecord = offlineRequest.await()
                val detail = detailResult.getOrNull()
                    ?: offlineRecord?.let { NovelDetail(novel = it.novel) }
                val volumes = volumeResult.getOrNull()?.items ?: offlineRecord?.volumes.orEmpty()
                val firstVolume = volumes.firstOrNull()?.key
                _state.value = _state.value.copy(
                    detail = detail ?: _state.value.detail,
                    volumes = volumes.ifEmpty { _state.value.volumes },
                    expandedVolumeKey = firstVolume ?: _state.value.expandedVolumeKey,
                    loading = false,
                    refreshing = false,
                    inRemoteShelf = detailResult.getOrNull()?.novel?.inRemoteShelf
                        ?: shelfResult?.getOrNull()
                        ?: _state.value.inRemoteShelf,
                    shelfError = shelfResult?.exceptionOrNull()?.toSourceUiMessage("书架状态加载失败"),
                    offlineRecord = offlineRecord ?: _state.value.offlineRecord,
                    error = detailResult.exceptionOrNull()
                        ?.takeIf { detail == null }
                        ?.toSourceUiMessage("书籍详情加载失败"),
                    directoryError = volumeResult.exceptionOrNull()
                        ?.takeIf { volumes.isEmpty() }
                        ?.toSourceUiMessage("目录加载失败"),
                )
                if (firstVolume != null) loadMoreChapters(firstVolume)
            }
        }
    }

    fun toggleShelf(onLoginRequired: () -> Unit) {
        val provider = registry.shelfProvider(novelKey.sourceId) ?: return
        if (_state.value.shelfLoading) return
        val target = _state.value.inRemoteShelf != true
        _state.value = _state.value.copy(shelfLoading = true, shelfError = null)
        viewModelScope.launch {
            runSourceCatching { provider.setInRemoteShelf(novelKey, target) }
                .onSuccess { inShelf ->
                    _state.value = _state.value.copy(
                        inRemoteShelf = inShelf,
                        shelfLoading = false,
                        shelfError = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        shelfLoading = false,
                        shelfError = error.toSourceUiMessage("书架操作失败"),
                    )
                    if (error is SourceException && error.kind == SourceErrorKind.AUTHENTICATION) {
                        onLoginRequired()
                    }
                }
        }
    }

    fun unlockChapter(
        chapter: ChapterSummary,
        onLoginRequired: () -> Unit,
        onUnlocked: () -> Unit,
    ) {
        val provider = registry.unlockProvider(novelKey.sourceId) ?: return
        if (_state.value.unlockingChapterKey != null) return
        _state.value = _state.value.copy(unlockingChapterKey = chapter.key, unlockError = null)
        viewModelScope.launch {
            runSourceCatching { provider.unlockChapter(chapter.key) }
                .onSuccess {
                    updateChapters(chapter.volumeKey) { current ->
                        current.copy(items = current.items.map { item -> if (item.key == chapter.key) item.copy(locked = false) else item })
                    }
                    _state.value = _state.value.copy(unlockingChapterKey = null, unlockError = null)
                    onUnlocked()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        unlockingChapterKey = null,
                        unlockError = error.toSourceUiMessage("章节解锁失败"),
                    )
                    if (error is SourceException && error.kind == SourceErrorKind.AUTHENTICATION) onLoginRequired()
                }
        }
    }

    fun downloadBook() {
        val novel = _state.value.detail?.novel ?: return
        if (_state.value.offlineRecord?.status.isActiveDownload()) return
        offlineLibrary.enqueue(novel)
    }

    fun downloadVolume(volumeKey: VolumeKey) {
        val novel = _state.value.detail?.novel ?: return
        if (_state.value.offlineRecord?.status.isActiveDownload()) return
        offlineLibrary.enqueue(novel, volumeKey)
    }

    fun toggleVolume(volumeKey: VolumeKey) {
        val expanding = _state.value.expandedVolumeKey != volumeKey
        _state.value = _state.value.copy(expandedVolumeKey = volumeKey.takeIf { expanding })
        if (expanding && _state.value.chapters[volumeKey]?.items.isNullOrEmpty()) {
            loadMoreChapters(volumeKey)
        }
    }

    fun loadMoreChapters(volumeKey: VolumeKey) {
        val provider = registry.readerProvider(novelKey.sourceId) ?: return
        val current = _state.value.chapters[volumeKey] ?: VolumeChaptersState()
        if (current.loading || (!current.hasMore && current.page > 0)) return
        chapterJobs[volumeKey]?.cancel()
        updateChapters(volumeKey) { current.copy(loading = true, error = null) }
        chapterJobs[volumeKey] = viewModelScope.launch {
            val onlineResult = runSourceCatching {
                provider.getChapters(
                    novelKey = novelKey,
                    volumeKey = volumeKey,
                    page = current.page + 1,
                    pageSize = 50,
                )
            }
            val pageResult = if (onlineResult.isSuccess) {
                onlineResult
            } else {
                runSourceCatching {
                    val offline = offlineLibrary.readBook(novelKey)
                    val items = offline?.chapters.orEmpty().filter { it.volumeKey == volumeKey }
                    val from = (current.page * 50).coerceAtMost(items.size)
                    val to = (from + 50).coerceAtMost(items.size)
                    SourcePage(
                        items = items.subList(from, to),
                        page = current.page + 1,
                        total = items.size,
                        hasMore = to < items.size,
                    )
                }
            }
            pageResult.onSuccess { page ->
                updateChapters(volumeKey) { latest ->
                    latest.copy(
                        items = (latest.items + page.items).distinctBy { it.key },
                        page = page.page,
                        hasMore = page.hasMore,
                        loading = false,
                    )
                }
            }.onFailure { error ->
                updateChapters(volumeKey) {
                    it.copy(loading = false, error = error.toSourceUiMessage("章节加载失败"))
                }
            }
        }
    }

    fun startReading(onReady: (ChapterKey) -> Unit) {
        if (_state.value.startingReader) return
        _state.value.readingProgress?.chapterKey?.let { chapterKey ->
            onReady(chapterKey)
            return
        }
        val firstVolume = _state.value.volumes.firstOrNull()?.key ?: return
        val loadedTarget = _state.value.chapters[firstVolume]?.items?.firstOrNull { !it.locked }?.key
        if (loadedTarget != null) {
            onReady(loadedTarget)
            return
        }
        val provider = registry.readerProvider(novelKey.sourceId) ?: return
        _state.value = _state.value.copy(startingReader = true)
        viewModelScope.launch {
            runSourceCatching { provider.getChapters(novelKey, firstVolume, page = 1, pageSize = 50) }
                .onSuccess { page ->
                    updateChapters(firstVolume) {
                        it.copy(items = page.items, page = page.page, hasMore = page.hasMore, loading = false)
                    }
                    _state.value = _state.value.copy(startingReader = false)
                    page.items.firstOrNull { !it.locked }?.key?.let(onReady)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        startingReader = false,
                        directoryError = error.toSourceUiMessage("章节加载失败"),
                    )
                }
        }
    }

    private fun updateChapters(
        volumeKey: VolumeKey,
        transform: (VolumeChaptersState) -> VolumeChaptersState,
    ) {
        val current = _state.value.chapters[volumeKey] ?: VolumeChaptersState()
        _state.value = _state.value.copy(
            chapters = _state.value.chapters + (volumeKey to transform(current)),
        )
    }
}

private fun OfflineDownloadStatus?.isActiveDownload(): Boolean =
    this == OfflineDownloadStatus.QUEUED || this == OfflineDownloadStatus.DOWNLOADING
