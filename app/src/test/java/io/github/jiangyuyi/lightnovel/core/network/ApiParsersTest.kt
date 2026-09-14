package io.github.jiangyuyi.lightnovel.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import io.github.jiangyuyi.lightnovel.core.model.MessageCategory
import io.github.jiangyuyi.lightnovel.core.model.UserSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiParsersTest {
    @Test
    fun `json body preserves structured arrays`() {
        val body = jsonBody(
            "mention_uids" to buildJsonArray {
                add(JsonPrimitive(7))
                add(JsonPrimitive(9))
            },
        )

        assertEquals("[7,9]", body["mention_uids"].toString())
    }

    @Test
    fun `books page prefers live page info when legacy pagination says snapshot ended`() {
        val source = Json.parseToJsonElement(
            """
            {
              "list": [{"book_id": 1, "title": "第一页"}],
              "pagination": {"page": 1, "page_size": 30, "total": 30, "page_count": 1},
              "page_info": {"count": 30, "size": 30, "cur": 1, "next": 2, "has_next": 1}
            }
            """.trimIndent(),
        ).jsonObject

        val page = ApiParsers.booksPage(source)

        assertTrue(page.hasMore)
        assertEquals(1, page.page)
        assertEquals(30, page.total)
    }

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
              "unread_chapter_count": 3,
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
        assertEquals(3, book.unreadChapterCount)
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
    fun `book parser preserves an explicit zero unread count`() {
        val book = ApiParsers.book(
            obj(
                """
                {"book_id": 9, "title": "已读书", "unread_chapter_count": 0}
                """.trimIndent(),
            ),
        )

        assertEquals(0, book.unreadChapterCount)
    }

    @Test
    fun `book parser prefers the official five star score and converts legacy ten point score`() {
        val tenPoint = ApiParsers.book(obj("""{"book_id": 1, "title": "评分", "rating_score": 10.0, "rating_score_10": 10.0}"""))
        val legacy = ApiParsers.book(obj("""{"book_id": 2, "title": "旧评分", "rating_score": 0, "rating_score_10": 8.4}"""))

        assertEquals(5.0, checkNotNull(tenPoint.score), 0.001)
        assertEquals(4.2, checkNotNull(legacy.score), 0.001)
    }

    @Test
    fun `book parser normalizes official rating score to five star display`() {
        val official = ApiParsers.book(
            obj("""{"book_id": 3, "title": "十分制作品", "rating_score": 10.0}"""),
        )

        assertEquals(5.0, checkNotNull(official.score), 0.001)
    }

    @Test
    fun `comment parser maps official time interactions media and reply preview`() {
        val comment = ApiParsers.comment(
            obj(
                """
                {
                  "comment_id": 1351853,
                  "root_comment_id": 0,
                  "author": {"uid": 7, "nickname": "评论者", "avatar": "https://example.test/avatar.jpg"},
                  "content": "很好看 [s:1]",
                  "publish_time": "2026-09-12 17:36:01",
                  "resources": [{"url": "https://example.test/comment.jpg", "width": 640, "height": 480, "res_id": "r-1"}],
                  "stats": {"like_count": 4, "conversation_count": 1},
                  "interaction_state": {"liked": 1},
                  "reply_preview": [{"comment_id": 9, "author": {"uid": 8, "nickname": "回复者"}, "content": "同意", "publish_time": "2026-09-13"}]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1351853L, comment.id)
        assertEquals("2026-09-12 17:36:01", comment.createdAt)
        assertEquals(4, comment.likeCount)
        assertEquals(1, comment.replyCount)
        assertTrue(comment.liked)
        assertEquals("https://example.test/comment.jpg", comment.media.single().url)
        assertEquals("回复者", comment.replies.single().author.nickname)
    }

    @Test
    fun `comment emoji parser distinguishes image urls from unicode text`() {
        val emojis = ApiParsers.commentEmojis(
            obj(
                """
                {
                  "list": [
                    {
                      "name": "官方表情",
                      "items": [
                        {"code": "[s:1]", "url": "https://static.example/s1.gif"},
                        {"code": "{:neko3:}", "url": "/smiley/neko3.gif"}
                      ]
                    },
                    {"code": "😀", "url": "😀"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(3, emojis.size)
        assertEquals("https://static.example/s1.gif", emojis.first { it.code == "[s:1]" }.imageUrl)
        assertEquals("/smiley/neko3.gif", emojis.first { it.code == "{:neko3:}" }.imageUrl)
        val unicode = emojis.first { it.code == "😀" }
        assertEquals(null, unicode.imageUrl)
        assertEquals("😀", unicode.text)
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
    fun `chapter parser reads official coin price`() {
        val chapter = ApiParsers.chapter(
            obj("""{"chapter_id": 20, "book_id": 1, "volume_id": 2, "title": "付费章", "locked": true, "coin_price": 12}"""),
        )
        assertTrue(chapter.locked)
        assertEquals(12, chapter.coinPrice)
    }

    @Test
    fun `chapter parser accepts string and nested coin prices`() {
        val stringPrice = ApiParsers.chapter(
            obj("""{"chapter_id": 21, "title": "字符串价格", "coin_price": "7"}"""),
        )
        val nestedPrice = ApiParsers.chapter(
            obj("""{"chapter_id": 22, "title": "嵌套价格", "price": {"amount": 9}}"""),
        )

        assertEquals(7, stringPrice.coinPrice)
        assertEquals(9, nestedPrice.coinPrice)
    }

    @Test
    fun `chapter parser accepts live object navigation and direct ids`() {
        val liveObject = ApiParsers.chapterDetail(
            obj(
                """
                {
                  "chapter_id": 317743,
                  "book_id": 11950,
                  "volume_id": 13121,
                  "title": "序章",
                  "navigation": {
                    "prev_chapter": [],
                    "next_chapter": {"chapter_id": 316297}
                  }
                }
                """.trimIndent(),
            ),
        )
        val directIds = ApiParsers.chapterDetail(
            obj(
                """
                {
                  "chapter_id": 20,
                  "book_id": 1,
                  "volume_id": 2,
                  "title": "第二章",
                  "prev_chapter_id": "19",
                  "next_chapter_id": 21
                }
                """.trimIndent(),
            ),
        )

        assertNull(liveObject.previousChapterId)
        assertEquals(316297L, liveObject.nextChapterId)
        assertEquals(19L, directIds.previousChapterId)
        assertEquals(21L, directIds.nextChapterId)
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

    @Test
    fun `account profile accepts nested stats and legacy fields`() {
        val profile = ApiParsers.accountProfile(
            obj(
                """
                {
                  "profile": {
                    "uid": 1419000,
                    "nickname": "jyy0736",
                    "avatar_url": "https://example.test/avatar.jpg",
                    "signature": "签名",
                    "user_group": {"name": "骑士"},
                    "balance": {"light_coin": 370}
                  },
                  "stats": {"followers": 8, "following": 12, "publish_articles": 3}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1419000L, profile.user.uid)
        assertEquals("骑士", profile.levelName)
        assertEquals(370, profile.coin)
        assertEquals(8, profile.fansCount)
        assertEquals(12, profile.followingCount)
        assertEquals(3, profile.postCount)
    }

    @Test
    fun `social page parses relationship and page info`() {
        val page = ApiParsers.socialPage(
            obj(
                """
                {
                  "items": [{
                    "uid": 7,
                    "nickname": "用户七",
                    "sign": "你好",
                    "relation": {"is_following": 1},
                    "level": {"name": "骑士"}
                  }],
                  "page_info": {"page": 1, "page_size": 20, "total": 21, "has_more": true}
                }
                """.trimIndent(),
            ),
            requestedPage = 1,
            pageSize = 20,
        )

        assertEquals(7L, page.items.single().user.uid)
        assertTrue(page.items.single().followed)
        assertEquals("骑士", page.items.single().levelName)
        assertEquals(21, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `reading history keeps last chapter and time`() {
        val page = ApiParsers.readingHistoryPage(
            obj(
                """
                {
                  "list": [{
                    "book_id": 10,
                    "title": "历史书籍",
                    "book_cover": "https://example.test/book.jpg",
                    "history": {
                      "chapter_id": 99,
                      "chapter_title": "第九章",
                      "last_read_at": "2026-08-08"
                    }
                  }]
                }
                """.trimIndent(),
            ),
            requestedPage = 1,
            pageSize = 20,
        )

        val item = page.items.single()
        assertEquals(99L, item.lastChapterId)
        assertEquals("第九章", item.lastChapterTitle)
        assertEquals("2026-08-08", item.readAt)
        assertEquals("https://example.test/book.jpg", item.book.coverUrl)
    }

    @Test
    fun `published work maps serial and review status`() {
        val page = ApiParsers.publishedWorksPage(
            obj(
                """
                {
                  "cards": [{
                    "book_id": 88,
                    "title": "作者作品",
                    "status": 1,
                    "word_count": 123456,
                    "meta_json": {"type": "原创", "serialize_status": "已完结"},
                    "review_state": {"review_status": "pending", "progress_text": "审核中"}
                  }],
                  "pageInfo": {"page": 1, "size": 8, "count": 1}
                }
                """.trimIndent(),
            ),
            requestedPage = 1,
            pageSize = 8,
        )

        val work = page.items.single()
        assertEquals(88L, work.bookId)
        assertEquals("已完本", work.status)
        assertEquals("审核中", work.reviewText)
        assertEquals(123456L, work.wordCount)
    }

    @Test
    fun `message summary maps all unread categories`() {
        val summary = ApiParsers.messageSummary(
            obj(
                """
                {
                  "unread_count": 21,
                  "reply_count": 2,
                  "mention_count": 3,
                  "like_count": 4,
                  "system_count": 5,
                  "dm_count": 6,
                  "fan_count": 1
                }
                """.trimIndent(),
            ),
        )

        assertEquals(21, summary.unreadCount)
        assertEquals(2, summary.count(MessageCategory.REPLY))
        assertEquals(3, summary.count(MessageCategory.MENTION))
        assertEquals(4, summary.count(MessageCategory.LIKE))
        assertEquals(5, summary.count(MessageCategory.SYSTEM))
        assertEquals(6, summary.count(MessageCategory.DM))
        assertEquals(1, summary.count(MessageCategory.FAN))
    }

    @Test
    fun `notification parser keeps user quote and reader target`() {
        val page = ApiParsers.messagesPage(
            obj(
                """
                {
                  "list": [{
                    "message_id": "m-1",
                    "user": {"uid": 7, "nickname": "回复者", "avatar_url": "https://example.test/u.jpg"},
                    "title": "回复了你",
                    "content_text": "正文",
                    "quote_text": "被回复内容",
                    "target_book_id": 10,
                    "target_chapter_id": 99,
                    "unread": 1,
                    "created_at": "2026-08-08"
                  }],
                  "page_info": {"page": 0, "page_size": 20, "total": 1}
                }
                """.trimIndent(),
            ),
            category = MessageCategory.REPLY,
            requestedPage = 1,
            pageSize = 20,
        )

        val message = page.items.single()
        assertEquals("m-1", message.id)
        assertEquals("回复者", message.user?.nickname)
        assertEquals("被回复内容", message.quoteText)
        assertEquals(10L, message.targetBookId)
        assertEquals(99L, message.targetChapterId)
        assertTrue(message.unread)
        assertEquals(1, page.page)
    }

    @Test
    fun `dm parsers accept conversation and message aliases`() {
        val source = obj(
            """
            {
              "conversations": [{
                "conversation_id": "c-1",
                "peer_uid": 9,
                "peer_user": {"uid": 9, "nickname": "私信用户"},
                "last_message": {
                  "content": "你好",
                  "created_at": "今天"
                },
                "unread_count": 2
              }]
            }
            """.trimIndent(),
        )
        val messagesSource = obj(
            """
            {
              "messages": [{
                "message_id": "d-1",
                "sender": {"uid": 9, "nickname": "私信用户"},
                "content_text": "测试私信",
                "is_mine": 0,
                "sent_at": "刚刚"
              }]
            }
            """.trimIndent(),
        )

        val conversation = ApiParsers.dmConversations(source).single()
        val message = ApiParsers.dmMessages(messagesSource, UserSummary(9, "私信用户")).single()
        assertEquals(9L, conversation.peerUid)
        assertEquals(2, conversation.unreadCount)
        assertEquals("你好", conversation.lastMessage)
        assertEquals("今天", conversation.updatedAt)
        assertEquals("测试私信", message.content)
        assertFalse(message.mine)
    }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject
}
