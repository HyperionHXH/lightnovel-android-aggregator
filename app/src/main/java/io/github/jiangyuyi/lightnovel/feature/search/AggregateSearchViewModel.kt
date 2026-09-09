package io.github.jiangyuyi.lightnovel.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.source.AggregateSearchCoordinator
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.SourceSearchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SourceSearchUiState(
    val descriptor: SourceDescriptor,
    val items: List<NovelSummary> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val searched: Boolean = false,
    val errorKind: SourceErrorKind? = null,
    val errorMessage: String? = null,
)

data class AggregateSearchState(
    val query: String = "",
    val searchedQuery: String = "",
    val sources: List<SourceSearchUiState> = emptyList(),
)

class AggregateSearchViewModel(
    private val coordinator: AggregateSearchCoordinator,
    registry: SourceRegistry,
    private val debounceMillis: Long = 400,
) : ViewModel() {
    private val initialSources = registry.searchProviders().map { SourceSearchUiState(it.descriptor) }
    private val _state = MutableStateFlow(AggregateSearchState(sources = initialSources))
    val state: StateFlow<AggregateSearchState> = _state.asStateFlow()
    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    init {
        require(debounceMillis >= 0) { "debounce must not be negative" }
    }

    fun setQuery(value: String) {
        if (value == _state.value.query) return
        debounceJob?.cancel()
        searchJob?.cancel()
        _state.value = _state.value.copy(
            query = value,
            searchedQuery = "",
            sources = initialSources,
        )
        val normalized = value.trim()
        if (normalized.isEmpty()) return
        debounceJob = viewModelScope.launch {
            delay(debounceMillis)
            startSearch(normalized)
        }
    }

    fun searchNow() {
        debounceJob?.cancel()
        val normalized = _state.value.query.trim()
        if (normalized.isEmpty()) {
            searchJob?.cancel()
            _state.value = _state.value.copy(searchedQuery = "", sources = initialSources)
            return
        }
        startSearch(normalized)
    }

    fun retry() = searchNow()

    fun loadMore() {
        val query = _state.value.searchedQuery.trim()
        if (query.isEmpty() || _state.value.query.trim() != query) return
        if (_state.value.sources.any { it.loading || it.refreshing || it.loadingMore }) return
        val targets = _state.value.sources.filter {
            it.hasMore
        }
        if (targets.isEmpty()) return
        val pageBySourceId = targets.associate { it.descriptor.id to (it.page + 1).coerceAtLeast(1) }
        searchJob?.cancel()
        val targetIds = targets.mapTo(mutableSetOf()) { it.descriptor.id }
        _state.value = _state.value.copy(
            sources = _state.value.sources.map { source ->
                if (source.descriptor.id in targetIds) {
                    source.copy(loadingMore = true, errorKind = null, errorMessage = null)
                } else source
            },
        )
        searchJob = viewModelScope.launch {
            collectSearch(
                query,
                page = 1,
                sourceIds = targetIds,
                pageBySourceId = pageBySourceId,
                append = true,
            )
        }
    }

    private fun startSearch(query: String) {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchedQuery = query,
            sources = initialSources.map { it.copy(loading = true, refreshing = true) },
        )
        searchJob = viewModelScope.launch {
            collectSearch(query, page = 1, sourceIds = null, pageBySourceId = emptyMap(), append = false)
        }
    }

    private suspend fun collectSearch(
        query: String,
        page: Int,
        sourceIds: Set<String>?,
        pageBySourceId: Map<String, Int>,
        append: Boolean,
    ) {
        try {
            coordinator.search(
                query,
                page = page,
                pageSize = PAGE_SIZE,
                sourceIds = sourceIds,
                pageBySourceId = pageBySourceId,
            ).collect { event ->
                if (_state.value.query.trim() != query) return@collect
                updateSource(event.source.id) { current ->
                    when (event) {
                        is SourceSearchEvent.Loading -> current.copy(
                            loading = true,
                            refreshing = !append,
                            loadingMore = append,
                            searched = if (append) current.searched else false,
                            errorKind = null,
                            errorMessage = null,
                        )

                        is SourceSearchEvent.Success -> current.copy(
                            items = if (append) {
                                (current.items + event.page.items).distinctBy { it.key }
                            } else event.page.items.distinctBy { it.key },
                            page = event.page.page,
                            total = event.page.total,
                            hasMore = event.page.hasMore,
                            loading = false,
                            refreshing = false,
                            loadingMore = false,
                            searched = true,
                            errorKind = null,
                            errorMessage = null,
                        )

                        is SourceSearchEvent.Failure -> current.copy(
                            loading = false,
                            refreshing = false,
                            loadingMore = false,
                            searched = true,
                            errorKind = event.kind,
                            errorMessage = event.toUiMessage(),
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            if (_state.value.query.trim() == query) {
                _state.value = _state.value.copy(
                    sources = _state.value.sources.map { source ->
                        if (sourceIds == null || source.descriptor.id in sourceIds) {
                            source.copy(loading = false, refreshing = false, loadingMore = false)
                        } else source
                    },
                )
            }
        }
    }

    private fun updateSource(
        sourceId: String,
        transform: (SourceSearchUiState) -> SourceSearchUiState,
    ) {
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

private fun SourceSearchEvent.Failure.toUiMessage(): String = when (kind) {
    SourceErrorKind.AUTHENTICATION -> "请先登录该来源"
    SourceErrorKind.TIMEOUT -> "请求超时，请稍后重试"
    SourceErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
    SourceErrorKind.NETWORK -> "网络连接失败，请检查网络"
    SourceErrorKind.SERVER -> "来源服务暂时不可用"
    SourceErrorKind.PARSING -> "来源数据格式已变化"
    SourceErrorKind.UNKNOWN -> message.lineSequence().firstOrNull().orEmpty().take(160).ifBlank { "搜索失败" }
}
