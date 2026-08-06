package io.github.jiangyuyi.lightnovel.feature.auth

import io.github.jiangyuyi.lightnovel.core.network.ApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthErrorMessageTest {
    @Test
    fun `login business code 2 identifies invalid credentials`() {
        val error = ApiException(message = "错误2", httpCode = 200, businessCode = 2)

        assertEquals("账号或密码错误，请检查后重试", authErrorMessage(AuthAction.LOGIN, error))
    }

    @Test
    fun `network internals are hidden from users`() {
        val error = IOException("Exception in CronetUrlRequest: net::ERR_CONNECTION_RESET")

        assertEquals("网络连接失败，请检查网络后重试", authErrorMessage(AuthAction.LOGIN, error))
    }

    @Test
    fun `useful server messages remain visible`() {
        val error = ApiException(message = "该邮箱已经注册", httpCode = 200, businessCode = 7)

        assertEquals("该邮箱已经注册", authErrorMessage(AuthAction.REGISTER, error))
    }

    @Test
    fun `rate limiting has an actionable message`() {
        val error = ApiException(message = "Too Many Requests", httpCode = 429)

        assertEquals("操作过于频繁，请稍后再试", authErrorMessage(AuthAction.SEND_CODE, error))
    }
}
