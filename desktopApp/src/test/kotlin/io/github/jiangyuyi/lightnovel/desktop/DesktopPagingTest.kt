package io.github.jiangyuyi.lightnovel.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopPagingTest {
    @Test
    fun `near bottom gate emits once until it is rearmed`() {
        val gate = DesktopNearBottomGate(threshold = 260)

        assertTrue(gate.update(100))
        assertFalse(gate.update(0))
        assertFalse(gate.update(400))
        assertTrue(gate.update(100))

        gate.arm()
        assertTrue(gate.update(100))
        assertFalse(gate.update(100))
    }

    @Test
    fun `chapter aggregation stops on an empty page despite hasMore`() {
        val requested = mutableListOf<Int>()
        val chapters = loadAllDesktopChapterPages({ page ->
            requested += page
            DesktopChapterPage(
                items = if (page == 1) listOf(chapter("1")) else emptyList(),
                page = page,
                hasMore = true,
            )
        })

        assertEquals(listOf(1, 2), requested)
        assertEquals(listOf("1"), chapters.map { it.remoteId })
    }

    @Test
    fun `chapter aggregation stops when source repeats a page`() {
        val requested = mutableListOf<Int>()
        val chapters = loadAllDesktopChapterPages({ page ->
            requested += page
            DesktopChapterPage(listOf(chapter(page.toString())), page = 1, hasMore = true)
        })

        assertEquals(listOf(1, 2), requested)
        assertEquals(listOf("1"), chapters.map { it.remoteId })
    }

    @Test
    fun `pages append and duplicate source keys are replaced`() {
        val requestedPages = mutableListOf<Int>()
        val loader = DesktopPageLoader { source, _, _, page, pageSize ->
            assertEquals("light_novel_kingdom", source)
            assertEquals(20, pageSize)
            requestedPages += page
            if (page == 1) {
                DesktopPage(
                    items = listOf(book("1", "第一本"), book("2", "第二本")),
                    page = page,
                    total = 3,
                    hasMore = true,
                )
            } else {
                DesktopPage(
                    items = listOf(book("2", "第二本（更新）"), book("3", "第三本")),
                    page = page,
                    total = 3,
                    hasMore = false,
                )
            }
        }
        val paging = DesktopPagingController()

        assertTrue(paging.loadInitial(loader, "light_novel_kingdom", "热门", ""))
        assertTrue(paging.loadMore(loader, "light_novel_kingdom", "热门", ""))
        assertFalse(paging.loadMore(loader, "light_novel_kingdom", "热门", ""))

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(listOf("第一本", "第二本（更新）", "第三本"), paging.items.map { it.title })
        assertEquals(3, paging.total)
        assertFalse(paging.hasMore)
    }

    @Test
    fun `refresh resets pagination and failed append keeps existing items`() {
        var shouldFail = false
        val loader = DesktopPageLoader { _, _, _, page, _ ->
            if (shouldFail && page == 2) error("network")
            DesktopPage(
                items = listOf(book(page.toString(), "第${page}页")),
                page = page,
                total = 2,
                hasMore = page == 1,
            )
        }
        val paging = DesktopPagingController()
        paging.loadInitial(loader, "source", "热门", "")
        shouldFail = true

        assertFailsWith<IllegalStateException> {
            paging.loadMore(loader, "source", "热门", "")
        }
        assertEquals(listOf("第1页"), paging.items.map { it.title })
        assertTrue(paging.hasMore)

        shouldFail = false
        assertTrue(paging.loadInitial(loader, "source", "热门", ""))
        assertEquals(1, paging.page)
        assertEquals(listOf("第1页"), paging.items.map { it.title })
    }

    @Test
    fun `empty feed page is treated as terminal`() {
        val paging = DesktopPagingController()
        assertTrue(paging.loadInitial({ _, _, _, page, _ ->
            DesktopPage(emptyList(), page = page, total = 100, hasMore = true)
        }, "source", "热门", ""))
        assertFalse(paging.hasMore)
        assertFalse(paging.loadMore(UnconfiguredDesktopPageLoader(), "source", "热门", ""))
    }

    private fun book(id: String, title: String) = DesktopBook(
        sourceId = "light_novel_kingdom",
        remoteId = id,
        title = title,
    )

    private fun chapter(id: String) = DesktopChapter(
        sourceId = "light_novel_kingdom",
        remoteId = id,
        bookRemoteId = "book",
        volumeRemoteId = "volume",
        title = "第${id}章",
    )
}
