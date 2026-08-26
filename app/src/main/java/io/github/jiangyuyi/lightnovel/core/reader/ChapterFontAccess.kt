package io.github.jiangyuyi.lightnovel.core.reader

import androidx.compose.ui.text.font.FontFamily

interface ChapterFontAccess {
    suspend fun load(fontUrl: String?): FontFamily?
}

object EmptyChapterFontAccess : ChapterFontAccess {
    override suspend fun load(fontUrl: String?): FontFamily? {
        check(fontUrl.isNullOrBlank()) { "章节字体服务未配置" }
        return null
    }
}
