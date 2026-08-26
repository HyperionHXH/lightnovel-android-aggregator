package io.github.jiangyuyi.lightnovel.core.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class SourceErrorKind {
    TIMEOUT,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    PARSING,
    UNKNOWN,
}

class SourceException(
    val kind: SourceErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

sealed interface SourceSearchEvent {
    val source: SourceDescriptor

    data class Loading(
        override val source: SourceDescriptor,
    ) : SourceSearchEvent

    data class Success(
        override val source: SourceDescriptor,
        val page: SourcePage<NovelSummary>,
    ) : SourceSearchEvent

    data class Failure(
        override val source: SourceDescriptor,
        val kind: SourceErrorKind,
        val message: String,
    ) : SourceSearchEvent
}

class AggregateSearchCoordinator(
    private val registry: SourceRegistry,
    private val perSourceTimeoutMillis: Long = 10_000,
) {
    init {
        require(perSourceTimeoutMillis > 0) { "search timeout must be positive" }
    }

    fun search(query: String, page: Int = 1, pageSize: Int = 20): Flow<SourceSearchEvent> = channelFlow {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "search query must not be blank" }
        require(page > 0) { "page must be positive" }
        require(pageSize > 0) { "page size must be positive" }

        registry.searchProviders().forEach { provider ->
            launch {
                send(SourceSearchEvent.Loading(provider.descriptor))
                try {
                    val result = withTimeout(perSourceTimeoutMillis) {
                        provider.search(normalizedQuery, page, pageSize)
                    }
                    send(SourceSearchEvent.Success(provider.descriptor, result))
                } catch (error: TimeoutCancellationException) {
                    send(error.toFailure(provider.descriptor))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val failure = error.toFailure(provider.descriptor)
                    send(failure)
                }
            }
        }
    }
}

private fun Throwable.toFailure(source: SourceDescriptor): SourceSearchEvent.Failure {
    val kind = when (this) {
        is TimeoutCancellationException -> SourceErrorKind.TIMEOUT
        is SourceException -> kind
        else -> SourceErrorKind.UNKNOWN
    }
    val safeMessage = when (kind) {
        SourceErrorKind.TIMEOUT -> "${source.displayName} 请求超时"
        else -> message?.takeIf { it.isNotBlank() } ?: "${source.displayName} 请求失败"
    }
    return SourceSearchEvent.Failure(source, kind, safeMessage)
}
