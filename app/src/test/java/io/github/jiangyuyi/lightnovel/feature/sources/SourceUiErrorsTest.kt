package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import io.github.jiangyuyi.lightnovel.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceUiErrorsTest {
    @Test
    fun `comment business code twenty is shown as link error instead of opaque status`() {
        val error = ApiException("请求失败（20）", httpCode = 200, businessCode = 20)

        assertEquals("评论链接错误，请稍后重试", error.toSourceUiMessage("评论加载失败"))
    }
    @Test
    fun `opaque login status becomes actionable credential message`() {
        val error = SourceException(SourceErrorKind.AUTHENTICATION, "1001")

        assertEquals("账号或密码错误，请检查后重试", error.toSourceUiMessage("登录失败"))
    }

    @Test
    fun `server-shaped opaque login status also becomes credential message`() {
        val error = SourceException(SourceErrorKind.SERVER, "1001")

        assertEquals("账号或密码错误，请检查后重试", error.toSourceUiMessage("登录失败"))
    }

    @Test
    fun `source login detail is preserved when it explains the failure`() {
        val error = SourceException(SourceErrorKind.AUTHENTICATION, "邮箱或密码错误")

        assertEquals("邮箱或密码错误", error.toSourceUiMessage("登录失败"))
    }

    @Test
    fun `auth errors outside login keep the sign in guidance`() {
        val error = SourceException(SourceErrorKind.AUTHENTICATION, "会话已过期")

        assertEquals("请先登录该来源", error.toSourceUiMessage("章节加载失败"))
    }
}
