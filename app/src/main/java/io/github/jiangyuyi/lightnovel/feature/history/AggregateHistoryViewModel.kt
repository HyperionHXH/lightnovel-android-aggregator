package io.github.jiangyuyi.lightnovel.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.source.HistoryProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.ReadingHistoryEntry
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class HistorySourceUiState(
    val descriptor: SourceDescriptor,
    val items: List<ReadingHistoryEntry> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val errorKind: SourceErrorKind? = null,
    val errorMessage: String? = null,
)

data class AggregateHistoryState(
    val sources: List<HistorySourceUiState> = emptyList(),
    val localProgress: Map<NovelKey, ReadingProgress> = emptyMap(),
    val deleting: Set<NovelKey> = emptySet(),
    val actionError: String? = null,
)

class AggregateHistoryViewModel(
    private val registry: SourceRegistry,
    preferenceStore: ReaderPreferencesAccess,
    private val perSourceTimeoutMillis: Long = 10_000,
) : ViewModel() {
    private val providers = registry.historyProviders()
    private val _state = MutableStateFlow(
        AggregateHistoryState(
            sources = providers.map { HistorySourceUiState(it.descriptor) },
        ),
    )
    val state: StateFlow<AggregateHistoryState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var requestId = 0L

    init {
        require(perSourceTimeoutMillis > 0) { "history timeout must be positive" }
        viewModelScope.launch {
            preferenceStore.allSourceProgress().collect { progress ->
                _state.value = _state.value.copy(
                    localProgress = progress.associateBy(ReadingProgress::novelKey),
                )
            }
        }
        refresh()
    }

    fun refresh() = load(append = false)

    fun loadMore() = load(append = true)

    fun results(state: AggregateHistoryState): List<AggregateHistoryResult> =
        aggregateHistoryResults(state, registry)

    fun delete(result: AggregateHistoryResult) {
        val provider = registry.historyMutationProvider(result.source.id) ?: return
        val key = result.entry.novel.key
        if (key in _state.value.deleting) return
        _state.value = _state.value.copy(
            deleting = _state.value.deleting + key,
            actionError = null,
        )
        viewModelScope.launch {
            try {
                provider.deleteReadingHistory(key)
                _state.value = _state.value.copy(
                    sources = _state.value.sources.map { source ->
                        if (source.descriptor.id == key.sourceId) {
                            source.copy(
                                items = source.items.filterNot { it.novel.key == key },
                                total = (source.total - 1).coerceAtLeast(0),
                            )
                        } else {
                            source
                        }
                    },
                    deleting = _state.value.deleting - key,
                    actionError = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    deleting = _state.value.deleting - key,
                    actionError = error.toHistoryUiMessage("阅读记录删除失败"),
                )
            }
        }
    }

    private fun load(append: Boolean) {
        if (_state.value.sources.any { it.loading || it.refreshing || it.loadingMore }) return
        val targets = if (append) {
            providers.filter { provider ->
                _state.value.sources.any { it.descriptor.id == provider.descriptor.id && it.hasMore }
            }
        } else {
            providers
        }
        if (targets.isEmpty()) return
        loadJob?.cancel()
        val currentRequestId = if (append) requestId else ++requestId
        val targetIds = targets.mapTo(mutableSetOf()) { it.descriptor.id }
        _state.value = _state.value.copy(
            actionError = null,
            sources = _state.value.sources.map { source ->
                if (source.descriptor.id !in targetIds) {
                    source
                } else if (append) {
                    source.copy(loadingMore = true, errorKind = null, errorMessage = null)
                } else {
                    source.copy(
                        loading = source.items.isEmpty(),
                        refreshing = source.items.isNotEmpty(),
                        loadingMore = false,
                        errorKind = null,
                        errorMessage = null,
                    )
                }
            },
        )
        loadJob = viewModelScope.launch {
            supervisorScope {
                targets.forEach { provider ->
                    launch { loadSource(provider, append, currentRequestId) }
                }
            }
        }
    }

    private suspend fun loadSource(
        provider: HistoryProvider,
        append: Boolean,
        currentRequestId: Long,
    ) {
        val current = _state.value.sources.first { it.descriptor.id == provider.descriptor.id }
        val pageNumber = if (append) current.page + 1 else 1
        try {
            val page = withTimeout(perSourceTimeoutMillis) {
                provider.getReadingHistory(pageNumber, PAGE_SIZE)
            }
            updateSource(provider.descriptor.id, currentRequestId) { latest ->
                latest.copy(
                    items = if (append) {
                        (latest.items + page.items).distinctBy { it.novel.key }
                    } else {
                        page.items
                    },
                    page = page.page,
                    total = page.total,
                    hasMore = page.hasMore,
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    loaded = true,
                    errorKind = null,
                    errorMessage = null,
                )
            }
        } catch (error: TimeoutCancellationException) {
            updateFailure(provider, currentRequestId, SourceErrorKind.TIMEOUT, "请求超时，请稍后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val kind = (error as? SourceException)?.kind ?: SourceErrorKind.UNKNOWN
            updateFailure(provider, currentRequestId, kind, error.toHistoryUiMessage("阅读历史加载失败"))
        }
    }

    private fun updateFailure(
        provider: HistoryProvider,
        currentRequestId: Long,
        kind: SourceErrorKind,
        message: String,
    ) {
        updateSource(provider.descriptor.id, currentRequestId) { current ->
            current.copy(
                loading = false,
                refreshing = false,
                loadingMore = false,
                loaded = true,
                errorKind = kind,
                errorMessage = message,
            )
        }
    }

    private fun updateSource(
        sourceId: String,
        currentRequestId: Long,
        transform: (HistorySourceUiState) -> HistorySourceUiState,
    ) {
        if (requestId != currentRequestId) return
        _state.value = _state.value.copy(
            sources = _state.value.sources.map { source ->
                if (source.descriptor.id == sourceId) transform(source) else source
            },
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

data class AggregateHistoryResult(
    val source: SourceDescriptor,
    val entry: ReadingHistoryEntry,
    val canDelete: Boolean,
)

internal fun aggregateHistoryResults(
    state: AggregateHistoryState,
    registry: SourceRegistry,
): List<AggregateHistoryResult> {
    val largestSource = state.sources.maxOfOrNull { it.items.size } ?: return emptyList()
    return buildList {
        repeat(largestSource) { index ->
            state.sources.forEach { source ->
                source.items.getOrNull(index)?.let { remoteEntry ->
                    val local = state.localProgress[remoteEntry.novel.key]
                    val chapterKey = local?.chapterKey ?: remoteEntry.lastChapterKey
                    val chapterTitle = remoteEntry.lastChapterTitle.takeIf {
                        remoteEntry.lastChapterKey == chapterKey
                    }.orEmpty()
                    add(
                        AggregateHistoryResult(
                            source = source.descriptor,
                            entry = remoteEntry.copy(
                                lastChapterKey = chapterKey,
                                lastChapterTitle = chapterTitle,
                            ),
                            canDelete = registry.historyMutationProvider(source.descriptor.id) != null,
                        ),
                    )
                }
            }
        }
    }
}

private fun Throwable.toHistoryUiMessage(fallback: String): String = when (this) {
    is SourceException -> when (kind) {
        SourceErrorKind.AUTHENTICATION -> "请先登录该来源"
        SourceErrorKind.TIMEOUT -> "请求超时，请稍后重试"
        SourceErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        SourceErrorKind.NETWORK -> "网络连接失败，请检查网络"
        SourceErrorKind.SERVER -> "来源服务暂时不可用"
        SourceErrorKind.PARSING -> "来源数据格式已变化"
        SourceErrorKind.UNKNOWN -> message.orEmpty().ifBlank { fallback }
    }

    else -> message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160).ifBlank { fallback }
}
