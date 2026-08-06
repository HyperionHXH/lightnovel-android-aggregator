package io.github.jiangyuyi.lightnovel.feature.auth

import io.github.jiangyuyi.lightnovel.core.network.ApiException
import java.io.IOException

internal enum class AuthAction {
    LOGIN,
    SEND_CODE,
    REGISTER,
}

internal fun authErrorMessage(action: AuthAction, error: Throwable): String = when (error) {
    is IllegalArgumentException -> error.message ?: "请检查填写内容"
    is ApiException -> error.toFriendlyMessage(action)
    is IOException -> "网络连接失败，请检查网络后重试"
    else -> "操作失败，请稍后重试"
}

private fun ApiException.toFriendlyMessage(action: AuthAction): String {
    if (httpCode == 429) return "操作过于频繁，请稍后再试"

    if (action == AuthAction.LOGIN && (businessCode == 2 || httpCode == 401)) {
        return "账号或密码错误，请检查后重试"
    }

    if (action == AuthAction.REGISTER && businessCode == 2) {
        return "验证码或注册信息有误，请检查后重试"
    }

    val serverMessage = message.trim()
    if (serverMessage.isNotBlank() && !serverMessage.isOpaqueErrorCode()) return serverMessage

    return when (action) {
        AuthAction.LOGIN -> "登录失败，请稍后重试"
        AuthAction.SEND_CODE -> "验证码发送失败，请稍后重试"
        AuthAction.REGISTER -> "注册失败，请检查填写内容后重试"
    }
}

private fun String.isOpaqueErrorCode(): Boolean {
    val compact = lowercase().replace(" ", "")
    return compact.matches(Regex("^(错误|error|请求失败)[：:]?\\d+$")) || compact.matches(Regex("^\\d+$"))
}
