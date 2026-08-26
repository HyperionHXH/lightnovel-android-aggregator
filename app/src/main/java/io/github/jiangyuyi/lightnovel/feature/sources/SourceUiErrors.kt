package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import kotlinx.coroutines.CancellationException

internal fun Throwable.toSourceUiMessage(default: String): String = when (this) {
    is SourceException -> when (kind) {
        SourceErrorKind.AUTHENTICATION -> "请先登录该来源"
        SourceErrorKind.TIMEOUT -> "请求超时，请稍后重试"
        SourceErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        SourceErrorKind.NETWORK -> "网络连接失败，请检查网络"
        SourceErrorKind.SERVER -> "来源服务暂时不可用"
        SourceErrorKind.PARSING -> "来源数据格式已变化"
        SourceErrorKind.UNKNOWN -> message.orEmpty().ifBlank { default }
    }

    else -> message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160).ifBlank { default }
}

internal suspend fun <T> runSourceCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
