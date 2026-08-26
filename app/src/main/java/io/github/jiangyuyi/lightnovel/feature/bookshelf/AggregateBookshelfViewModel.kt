package io.github.jiangyuyi.lightnovel.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.offline.EmptyOfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.updates.EmptySourceUpdateSnapshotAccess
import io.github.jiangyuyi.lightnovel.core.updates.SourceUpdateSnapshot
import io.github.jiangyuyi.lightnovel.core.updates.SourceUpdateSnapshotAccess
import io.github.jiangyuyi.lightnovel.core.updates.isUpdatedComparedTo
import io.github.jiangyuyi.lightnovel.core.epub.EpubExportResult
import io.github.jiangyuyi.lightnovel.core.epub.EpubExportProgress
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class BookshelfSourceUiState(
    val descriptor: SourceDescriptor,
    val items: List<NovelSummary> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val errorKind: SourceErrorKind? = null,
    val errorMessage: String? = null,
)

data class AggregateBookshelfState(
    val sourceOptions: List<SourceDescriptor> = emptyList(),
    val downloadedOnly: Boolean = false,
    val sources: List<BookshelfSourceUiState> = emptyList(),
    val offlineBooks: List<OfflineBookRecord> = emptyList(),
    val updateSnapshots: List<SourceUpdateSnapshot> = emptyList(),
    val updatedBooks: Set<NovelKey> = emptySet(),
)

data class BookshelfSourceUpdateCount(
    val sourceId: String,
    val sourceName: String,
    val count: Int,
)

data class BookshelfUpdateSummary(
    val totalBooks: Int,
    val bySource: List<BookshelfSourceUpdateCount>,
)

class AggregateBookshelfViewModel(
    private val registry: SourceRegistry,
    private val offlineLibrary: OfflineLibraryAccess = EmptyOfflineLibraryAccess,
    private val sourceUpdateSnapshots: SourceUpdateSnapshotAccess = EmptySourceUpdateSnapshotAccess,
    private val perSourceTimeoutMillis: Long = 10_000,
    initialDownloadedOnly: Boolean = false,
) : ViewModel() {
    private val providers = registry.shelfProviders()
    private val initialSources = providers.map { BookshelfSourceUiState(it.descriptor) }
    private val _state = MutableStateFlow(
        AggregateBookshelfState(
            sourceOptions = providers.map(ShelfProvider::descriptor),
            sources = initialSources,
            downloadedOnly = initialDownloadedOnly,
        ),
    )
    val state: StateFlow<AggregateBookshelfState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var requestId = 0L
    private var screenShown = false

    init {
        require(perSourceTimeoutMillis > 0) { "bookshelf timeout must be positive" }
        viewModelScope.launch {
            offlineLibrary.books.collect { books ->
                _state.value = _state.value.copy(offlineBooks = books)
            }
        }
        viewModelScope.launch {
            sourceUpdateSnapshots.snapshots.collect { snapshots ->
                _state.value = _state.value.copy(updateSnapshots = snapshots)
            }
        }
        refresh()
    }

    fun onScreenShown() {
        if (screenShown) {
            refresh()
        } else {
            screenShown = true
        }
    }

    fun selectAll() {
        _state.value = _state.value.copy(downloadedOnly = false)
        if (_state.value.sources.any { !it.loaded && !it.loading }) refresh()
    }

    fun selectDownloaded() {
        _state.value = _state.value.copy(downloadedOnly = true)
    }

    fun retryDownload(record: OfflineBookRecord) = offlineLibrary.retry(record)

    fun deleteDownload(record: OfflineBookRecord) = offlineLibrary.delete(record.novel.key)

    suspend fun exportEpub(
        record: OfflineBookRecord,
        output: OutputStream,
        onProgress: (EpubExportProgress) -> Unit = {},
    ): EpubExportResult? = offlineLibrary.exportEpub(record.novel.key, output, onProgress)

    fun markAllUpdatesSeen() {
        val keys = _state.value.updatedBooks
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val snapshots = _state.value.updateSnapshots.associateBy(SourceUpdateSnapshot::novelKey)
            val acknowledged = keys.mapNotNull { key ->
                snapshots[key]?.let { snapshot ->
                    snapshot.copy(
                        acknowledgedChapterCount = snapshot.chapterCount,
                        acknowledgedUnreadChapterCount = snapshot.unreadChapterCount,
                        observedAtEpochMillis = System.currentTimeMillis(),
                    )
                }
            }
            if (acknowledged.isEmpty()) return@launch
            try {
                sourceUpdateSnapshots.saveAll(acknowledged)
                val acknowledgedKeys = acknowledged.mapTo(linkedSetOf()) { it.novelKey }
                _state.value = _state.value.copy(
                    updatedBooks = _state.value.updatedBooks - acknowledgedKeys,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Keep the marker when the local acknowledgement cannot be persisted.
            }
        }
    }

    fun refresh() {
        if (_state.value.downloadedOnly) return
        val targets = providers
        if (targets.isEmpty()) return
        loadJob?.cancel()
        val currentRequestId = ++requestId
        val targetIds = targets.mapTo(mutableSetOf()) { it.descriptor.id }
        _state.value = _state.value.copy(
            sources = _state.value.sources.map { source ->
                if (source.descriptor.id !in targetIds) {
                    source.copy(loading = false, refreshing = false)
                } else {
                    source.copy(
                        loading = source.items.isEmpty(),
                        refreshing = source.items.isNotEmpty(),
                        errorKind = null,
                        errorMessage = null,
                    )
                }
            },
        )
        loadJob = viewModelScope.launch {
            supervisorScope {
                targets.forEach { provider ->
                    launch { loadSource(provider, currentRequestId) }
                }
            }
        }
    }

    private suspend fun loadSource(provider: ShelfProvider, currentRequestId: Long) {
        try {
            val books = withTimeout(perSourceTimeoutMillis) { provider.getRemoteShelf() }
            val previousSnapshots = _state.value.updateSnapshots.associateBy { it.novelKey }
            val updatedKeys = books.asSequence()
                .filter { it.isUpdatedComparedTo(previousSnapshots[it.key]) }
                .mapTo(linkedSetOf()) { it.key }
            updateSource(provider.descriptor.id, currentRequestId) { current ->
                current.copy(
                    items = books,
                    loading = false,
                    refreshing = false,
                    loaded = true,
                    errorKind = null,
                    errorMessage = null,
                )
            }
            updateDetectedBooks(provider.descriptor.id, currentRequestId, updatedKeys)
            try {
                sourceUpdateSnapshots.saveAll(
                    books.map { novel ->
                        SourceUpdateSnapshot(
                            novelKey = novel.key,
                            chapterCount = novel.chapterCount.coerceAtLeast(0),
                            unreadChapterCount = novel.unreadChapterCount?.takeIf { it >= 0 },
                            acknowledgedChapterCount = previousSnapshots[novel.key]?.acknowledgedChapterCount,
                            acknowledgedUnreadChapterCount = previousSnapshots[novel.key]?.acknowledgedUnreadChapterCount,
                            observedAtEpochMillis = System.currentTimeMillis(),
                        )
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A local snapshot failure must not hide a successfully loaded remote shelf.
            }
        } catch (error: TimeoutCancellationException) {
            updateFailure(provider, currentRequestId, SourceErrorKind.TIMEOUT, "请求超时，请稍后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val kind = (error as? SourceException)?.kind ?: SourceErrorKind.UNKNOWN
            updateFailure(provider, currentRequestId, kind, error.toBookshelfUiMessage(kind))
        }
    }

    private fun updateFailure(
        provider: ShelfProvider,
        currentRequestId: Long,
        kind: SourceErrorKind,
        message: String,
    ) {
        updateSource(provider.descriptor.id, currentRequestId) { current ->
            current.copy(
                loading = false,
                refreshing = false,
                loaded = true,
                errorKind = kind,
                errorMessage = message,
            )
        }
    }

    private fun updateSource(
        sourceId: String,
        currentRequestId: Long,
        transform: (BookshelfSourceUiState) -> BookshelfSourceUiState,
    ) {
        if (requestId != currentRequestId) return
        _state.value = _state.value.copy(
            sources = _state.value.sources.map { source ->
                if (source.descriptor.id == sourceId) transform(source) else source
            },
        )
    }

    private fun updateDetectedBooks(
        sourceId: String,
        currentRequestId: Long,
        updatedKeys: Set<NovelKey>,
    ) {
        if (requestId != currentRequestId) return
        val otherSources = _state.value.updatedBooks.filterTo(linkedSetOf()) { it.sourceId != sourceId }
        _state.value = _state.value.copy(updatedBooks = otherSources + updatedKeys)
    }
}

internal data class BookshelfNovelResult(
    val source: SourceDescriptor,
    val novel: NovelSummary,
    val hasUpdates: Boolean = false,
)

internal fun visibleBookshelfSources(state: AggregateBookshelfState): List<BookshelfSourceUiState> = when {
    state.downloadedOnly -> emptyList()
    else -> state.sources
}

internal fun bookshelfUpdateSummary(state: AggregateBookshelfState): BookshelfUpdateSummary? {
    if (state.updatedBooks.isEmpty()) return null
    val sourceNames = state.sourceOptions.associate { it.id to it.displayName }
    val counts = state.updatedBooks
        .groupingBy(NovelKey::sourceId)
        .eachCount()
        .entries
        .sortedWith(compareBy({ sourceNames[it.key] ?: it.key }, { it.key }))
        .map { (sourceId, count) ->
            BookshelfSourceUpdateCount(
                sourceId = sourceId,
                sourceName = sourceNames[sourceId] ?: sourceId,
                count = count,
            )
        }
    return BookshelfUpdateSummary(
        totalBooks = state.updatedBooks.size,
        bySource = counts,
    )
}

internal fun interleaveBookshelfResults(
    sources: List<BookshelfSourceUiState>,
    updatedBooks: Set<NovelKey> = emptySet(),
): List<BookshelfNovelResult> {
    val largestSource = sources.maxOfOrNull { it.items.size } ?: return emptyList()
    return buildList {
        repeat(largestSource) { index ->
            sources.forEach { source ->
                source.items.getOrNull(index)?.let { novel ->
                    add(BookshelfNovelResult(source.descriptor, novel, novel.key in updatedBooks))
                }
            }
        }
    }
}

private fun Throwable.toBookshelfUiMessage(kind: SourceErrorKind): String = when (kind) {
    SourceErrorKind.AUTHENTICATION -> "请先登录该来源"
    SourceErrorKind.TIMEOUT -> "请求超时，请稍后重试"
    SourceErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
    SourceErrorKind.NETWORK -> "网络连接失败，请检查网络"
    SourceErrorKind.SERVER -> "来源服务暂时不可用"
    SourceErrorKind.PARSING -> "来源数据格式已变化"
    SourceErrorKind.UNKNOWN -> message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)
        .ifBlank { "书架加载失败" }
}
