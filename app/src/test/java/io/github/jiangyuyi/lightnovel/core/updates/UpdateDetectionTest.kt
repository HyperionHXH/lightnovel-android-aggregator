package io.github.jiangyuyi.lightnovel.core.updates

import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDetectionTest {
    @Test
    fun `unread count increase is newer content`() {
        val novel = novel(unreadChapterCount = 3)

        assertTrue(novel.hasNewerContentThan(previousChapterCount = 10, previousUnreadChapterCount = 2))
        assertFalse(novel.hasNewerContentThan(previousChapterCount = 10, previousUnreadChapterCount = 3))
    }

    @Test
    fun `chapter count increase is used when unread count is unavailable`() {
        val novel = novel(chapterCount = 12, unreadChapterCount = null)

        assertTrue(novel.hasNewerContentThan(previousChapterCount = 10, previousUnreadChapterCount = null))
        assertFalse(novel.hasNewerContentThan(previousChapterCount = 12, previousUnreadChapterCount = null))
    }

    @Test
    fun `zero unread count is not an update`() {
        assertFalse(
            novel(unreadChapterCount = 0).hasNewerContentThan(
                previousChapterCount = 10,
                previousUnreadChapterCount = 0,
            ),
        )
    }

    private fun novel(
        chapterCount: Int = 10,
        unreadChapterCount: Int?,
    ) = NovelSummary(
        key = NovelKey("source", "book"),
        title = "测试书",
        chapterCount = chapterCount,
        unreadChapterCount = unreadChapterCount,
    )
}
