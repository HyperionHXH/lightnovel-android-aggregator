package io.github.jiangyuyi.lightnovel.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.source.DiscoverFeed
import io.github.jiangyuyi.lightnovel.core.source.DiscoverProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
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

data class DiscoverSourceOption(
    val descriptor: SourceDescriptor,
    val feeds: List<DiscoverFeed>,
)

data class DiscoverSourceUiState(
    val descriptor: SourceDescriptor,
    val items: List<NovelSummary> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val errorKind: SourceErrorKind? = null,
    val errorMessage: String? = null,
)

data class AggregateDiscoverState(
    val sourceOptions: List<DiscoverSourceOption> = emptyList(),
    val selectedSourceId: String? = null,
    val feeds: List<DiscoverFeed> = emptyList(),
    val selectedFeed: DiscoverFeed? = null,
    val sources: List<DiscoverSourceUiState> = emptyList(),
)

class AggregateDiscoverViewModel(
    private val registry: SourceRegistry,
    private val perSourceTimeoutMillis: Long = 10_000,
) : ViewModel() {
    private val providers = registry.discoverProviders()
    private val sourceOptions = providers.map { provider ->
        DiscoverSourceOption(provider.descriptor, provider.discoverFeeds)
    }
    private val initialProvider = providers.firstOrNull()
    private val _state = MutableStateFlow(
        AggregateDiscoverState(
            sourceOptions = sourceOptions,
            selectedSourceId = initialProvider?.descriptor?.id,
            feeds = initialProvider?.discoverFeeds.orEmpty(),
            selectedFeed = initialProvider?.discoverFeeds?.firstOrNull(),
        ),
    )
    val state: StateFlow<AggregateDiscoverState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var requestId = 0L

    init {
        require(perSourceTimeoutMillis > 0) { "discover timeout must be positive" }
        load()
    }

    fun selectSource(sourceId: String) {
        if (sourceId == _state.value.selectedSourceId) return
        val feeds = sourceOptions.firstOrNull { it.descriptor.id == sourceId }?.feeds ?: return
        val selectedFeed = _state.value.selectedFeed?.takeIf { it in feeds } ?: feeds.firstOrNull()
        _state.value = _state.value.copy(
            selectedSourceId = sourceId,
            feeds = feeds,
            selectedFeed = selectedFeed,
            sources = emptyList(),
        )
        load()
    }

    fun selectFeed(feed: DiscoverFeed) {
        if (feed !in _state.value.feeds || feed == _state.value.selectedFeed) return
        _state.value = _state.value.copy(selectedFeed = feed, sources = emptyList())
        load()
    }

    fun refresh() = load()

    fun retry() = load()

    private fun load() {
        val state = _state.value
        val feed = state.selectedFeed ?: return
        val targets = providers.filter { provider ->
            feed in provider.discoverFeeds &&
                provider.descriptor.id == state.selectedSourceId
        }
        loadJob?.cancel()
        val currentRequestId = ++requestId
        _state.value = state.copy(
            sources = targets.map { provider ->
                DiscoverSourceUiState(descriptor = provider.descriptor, loading = true)
            },
        )
        loadJob = viewModelScope.launch {
            supervisorScope {
                targets.forEach { provider ->
                    launch { loadSource(provider, feed, currentRequestId) }
                }
            }
        }
    }

    private suspend fun loadSource(
        provider: DiscoverProvider,
        feed: DiscoverFeed,
        currentRequestId: Long,
    ) {
        try {
            val page = withTimeout(perSourceTimeoutMillis) {
                provider.discover(feed = feed, page = 1, pageSize = 20)
            }
            updateSource(provider.descriptor.id, currentRequestId) { current ->
                current.copy(
                    items = page.items,
                    loading = false,
                    loaded = true,
                    errorKind = null,
                    errorMessage = null,
                )
            }
        } catch (error: TimeoutCancellationException) {
            updateSourceFailure(provider, currentRequestId, SourceErrorKind.TIMEOUT, "请求超时，请稍后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val kind = (error as? SourceException)?.kind ?: SourceErrorKind.UNKNOWN
            updateSourceFailure(provider, currentRequestId, kind, error.toDiscoverUiMessage(kind))
        }
    }

    private fun updateSourceFailure(
        provider: DiscoverProvider,
        currentRequestId: Long,
        kind: SourceErrorKind,
        message: String,
    ) {
        updateSource(provider.descriptor.id, currentRequestId) { current ->
            current.copy(
                loading = false,
                loaded = true,
                errorKind = kind,
                errorMessage = message,
            )
        }
    }

    private fun updateSource(
        sourceId: String,
        currentRequestId: Long,
        transform: (DiscoverSourceUiState) -> DiscoverSourceUiState,
    ) {
        if (requestId != currentRequestId) return
        _state.value = _state.value.copy(
            sources = _state.value.sources.map { source ->
                if (source.descriptor.id == sourceId) transform(source) else source
            },
        )
    }
}

private fun Throwable.toDiscoverUiMessage(kind: SourceErrorKind): String = when (kind) {
    SourceErrorKind.AUTHENTICATION -> "请先登录该来源"
    SourceErrorKind.TIMEOUT -> "请求超时，请稍后重试"
    SourceErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
    SourceErrorKind.NETWORK -> "网络连接失败，请检查网络"
    SourceErrorKind.SERVER -> "来源服务暂时不可用"
    SourceErrorKind.PARSING -> "来源数据格式已变化"
    SourceErrorKind.UNKNOWN -> message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)
        .ifBlank { "加载失败" }
}
