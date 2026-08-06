package io.github.jiangyuyi.lightnovel.core.data

import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNavigationTest {
    private val chapters = listOf(
        chapter(317743, 1),
        chapter(316297, 2),
        chapter(317744, 3),
    )

    @Test
    fun `resolves both neighbors for a middle chapter`() {
        val neighbors = resolveChapterNeighbors(chapters, 316297)

        assertEquals(317743L, neighbors?.previousChapterId)
        assertEquals(317744L, neighbors?.nextChapterId)
    }

    @Test
    fun `keeps missing side null at volume boundary`() {
        val first = resolveChapterNeighbors(chapters, 317743)
        val last = resolveChapterNeighbors(chapters, 317744)

        assertNull(first?.previousChapterId)
        assertEquals(316297L, first?.nextChapterId)
        assertEquals(316297L, last?.previousChapterId)
        assertNull(last?.nextChapterId)
    }

    @Test
    fun `returns null when current chapter is absent`() {
        assertNull(resolveChapterNeighbors(chapters, 999999))
    }

    private fun chapter(id: Long, order: Int) = ChapterSummary(
        id = id,
        bookId = 11950,
        volumeId = 13121,
        title = "第 $order 章",
        order = order,
    )
}
