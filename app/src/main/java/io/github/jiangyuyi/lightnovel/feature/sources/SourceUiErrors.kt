package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.network.ApiException
import kotlinx.coroutines.CancellationException

internal fun Throwable.toSourceUiMessage(default: String): String {
    if (this is ApiException) {
        if (businessCode == 20) {
            return when {
                default.contains("评论") -> "评论服务返回数据失败，请稍后重试"
                default.contains("登录") -> "账号或密码错误，请检查后重试"
                else -> "请先登录轻之国度账号"
            }
        }
        if (default.contains("评论") && (httpCode == null || httpCode !in 200..299)) {
            return "评论链接错误，请检查网络"
        }
    }
    if (default.contains("登录") && message.orEmpty().lineSequence().firstOrNull().orEmpty().isOpaqueAuthCode()) {
        return "账号或密码错误，请检查后重试"
    }
    return when (this) {
        is SourceException -> when (kind) {
            SourceErrorKind.AUTHENTICATION -> authenticationMessage(default, message)
            SourceErrorKind.TIMEOUT -> "请求超时，请稍后重试"
            SourceErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
            SourceErrorKind.NETWORK -> "网络连接失败，请检查网络"
            SourceErrorKind.SERVER -> "来源服务暂时不可用"
            SourceErrorKind.PARSING -> "来源数据格式已变化"
            SourceErrorKind.UNKNOWN -> message.orEmpty().ifBlank { default }
        }

        else -> {
            val detail = message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)
            if (detail.isOpaqueAuthCode()) {
                when {
                    default.contains("评论") -> "评论服务暂时不可用，请稍后重试"
                    default.contains("登录") -> "账号或密码错误，请检查后重试"
                    else -> default
                }
            } else {
                detail.ifBlank { default }
            }
        }
    }
}

private fun authenticationMessage(default: String, detail: String?): String {
    val message = detail.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)
    if (default.contains("登录") && message.isOpaqueAuthCode()) {
        return "账号或密码错误，请检查后重试"
    }
    if (default.contains("登录") && message.isActionableAuthMessage()) {
        return message
    }
    return "请先登录该来源"
}

private fun String.isActionableAuthMessage(): Boolean {
    val normalized = lowercase()
    return contains("密码") || contains("账号") || contains("邮箱") || contains("用户") ||
        contains("登录") || contains("认证") || contains("password") || contains("credential") ||
        contains("unauthorized") || contains("invalid")
}

private fun String.isOpaqueAuthCode(): Boolean {
    val compact = lowercase().replace(" ", "")
    return compact.isBlank() || compact.matches(Regex("^(错误|error|请求失败)?[：:]?\\d+$")) ||
        compact.matches(Regex("^(服务器返回|轻书架服务器返回)\\s*\\d+$"))
}

internal suspend fun <T> runSourceCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
