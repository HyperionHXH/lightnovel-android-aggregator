package io.github.jiangyuyi.lightnovel.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiParsersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `book parser accepts current bff fields`() {
        val source = obj(
            """
            {
              "book_id": 7506,
              "title": "上海灯蛾",
              "author_name": "上田早夕里",
              "summary_short": "简介",
              "cover_url": "https://example.test/cover.jpg",
              "word_count": 218791,
              "visible_tags": ["历史", "文学"],
              "read_state": {
                "default_volume_id": 11334,
                "default_chapter_id": 218231,
                "volume_count": 1,
                "published_chapter_count": 9
              }
            }
            """.trimIndent(),
        )

        val book = ApiParsers.book(source)

        assertEquals(7506L, book.id)
        assertEquals("上海灯蛾", book.title)
        assertEquals("上田早夕里", book.author)
        assertEquals(11334L, book.defaultVolumeId)
        assertEquals(218231L, book.defaultChapterId)
        assertEquals(9, book.chapterCount)
        assertEquals(listOf("历史", "文学"), book.tags)
    }

    @Test
    fun `book page reads list and pagination`() {
        val source = obj(
            """
            {
              "list": [
                {"book_id": 1, "title": "第一本"},
                {"book_id": 2, "title": "第二本"}
              ],
              "pagination": {"page": 1, "page_size": 2, "total": 5}
            }
            """.trimIndent(),
        )

        val page = ApiParsers.booksPage(source)

        assertEquals(listOf(1L, 2L), page.items.map { it.id })
        assertEquals(5, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `chapter parser reads body and navigation arrays`() {
        val source = obj(
            """
            {
              "chapter_id": 20,
              "book_id": 1,
              "volume_id": 2,
              "title": "第二章",
              "book_title": "示例书",
              "volume_title": "第一卷",
              "body_snapshot": {"body_text": "第一段\n第二段"},
              "navigation": {
                "prev_chapter": [{"chapter_id": 19}],
                "next_chapter": [{"chapter_id": 21}]
              }
            }
            """.trimIndent(),
        )

        val chapter = ApiParsers.chapterDetail(source)

        assertEquals("第一段\n第二段", chapter.bodyText)
        assertEquals(19L, chapter.previousChapterId)
        assertEquals(21L, chapter.nextChapterId)
        assertFalse(chapter.chapter.locked)
    }

    @Test
    fun `taxonomy parser keeps channels and nested tags`() {
        val source = obj(
            """
            {
              "channels": [
                {"code": "original", "label": "原创", "filter": {"work_type": "original"}}
              ],
              "tabs": [{
                "groups": [{
                  "sections": [{
                    "tag_items": [{"title": "奇幻", "jump_value": "奇幻"}]
                  }]
                }]
              }]
            }
            """.trimIndent(),
        )

        val taxonomy = ApiParsers.taxonomy(source)

        assertEquals("original", taxonomy.channels.single().workType)
        assertEquals("奇幻", taxonomy.tags.single().label)
    }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject
}

