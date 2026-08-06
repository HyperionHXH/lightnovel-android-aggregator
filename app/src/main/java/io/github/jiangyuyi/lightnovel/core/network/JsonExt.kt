package io.github.jiangyuyi.lightnovel.core.network

import io.github.jiangyuyi.lightnovel.core.model.BookDetail
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import io.github.jiangyuyi.lightnovel.core.model.ChapterDetail
import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.model.Comment
import io.github.jiangyuyi.lightnovel.core.model.Page
import io.github.jiangyuyi.lightnovel.core.model.SearchOption
import io.github.jiangyuyi.lightnovel.core.model.SearchTaxonomy
import io.github.jiangyuyi.lightnovel.core.model.UserSummary
import io.github.jiangyuyi.lightnovel.core.model.Volume
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.element(vararg keys: String): JsonElement? =
    keys.firstNotNullOfOrNull { key -> this[key]?.takeUnless { it is JsonNull } }

internal fun JsonObject.obj(vararg keys: String): JsonObject? = element(*keys) as? JsonObject

internal fun JsonObject.array(vararg keys: String): JsonArray =
    (element(*keys) as? JsonArray) ?: JsonArray(emptyList())

internal fun JsonObject.string(vararg keys: String): String =
    (element(*keys) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

internal fun JsonObject.long(vararg keys: String): Long =
    (element(*keys) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

internal fun JsonObject.int(vararg keys: String): Int =
    (element(*keys) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

internal fun JsonObject.double(vararg keys: String): Double? =
    (element(*keys) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

internal fun JsonObject.bool(vararg keys: String): Boolean? {
    val primitive = element(*keys) as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    primitive.intOrNull?.let { return it != 0 }
    return when (primitive.contentOrNull?.lowercase()) {
        "1", "true", "yes", "add", "in_shelf" -> true
        "0", "false", "no", "remove" -> false
        else -> null
    }
}

object ApiParsers {
    fun user(source: JsonObject?): UserSummary? {
        source ?: return null
        val uid = source.long("uid", "id", "user_id")
        val nickname = source.string("nickname", "name", "username")
        if (uid == 0L && nickname.isBlank()) return null
        return UserSummary(
            uid = uid,
            nickname = nickname.ifBlank { "用户$uid" },
            avatarUrl = source.string("avatar_url", "avatar", "avatarUrl").ifBlank { null },
        )
    }

    fun book(source: JsonObject): BookSummary {
        val readState = source.obj("read_state", "readState")
        val stats = source.obj("stats")
        val rating = source.obj("rating")
        val tags = source.array("visible_tags", "tags", "reason_tags")
            .mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> element.string("label", "name", "text").ifBlank { null }
                    else -> null
                }
            }
            .distinct()
        return BookSummary(
            id = source.long("book_id", "id"),
            title = source.string("title", "book_title").ifBlank { "未命名作品" },
            author = source.string("author_name", "author", "authorName"),
            summary = source.string("summary_short", "summary", "content_preview", "intro"),
            coverUrl = source.string("cover_url", "cover", "banner_url").ifBlank { null },
            tags = tags,
            volumeCount = source.int("volume_count").takeIf { it > 0 }
                ?: stats?.int("volume_count")
                ?: readState?.int("volume_count")
                ?: 0,
            chapterCount = source.int("chapter_count", "published_chapter_count").takeIf { it > 0 }
                ?: stats?.int("chapter_count", "published_chapter_count")
                ?: readState?.int("published_chapter_count")
                ?: 0,
            wordCount = source.long("word_count").takeIf { it > 0 }
                ?: stats?.long("word_count")
                ?: 0,
            score = source.double("rating_score_10", "rating_stars_average", "score")
                ?: rating?.double("score_10", "stars_average", "score"),
            rank = source.int("rank_position").takeIf { it > 0 },
            defaultVolumeId = source.long("default_volume_id").takeIf { it > 0 }
                ?: readState?.long("default_volume_id")?.takeIf { it > 0 },
            defaultChapterId = source.long("default_chapter_id", "last_read_chapter_id").takeIf { it > 0 }
                ?: readState?.long("default_chapter_id")?.takeIf { it > 0 },
            inBookshelf = source.bool("in_shelf", "in_collection", "favorited", "inBookshelf"),
            unreadChapterCount = source.int("unread_chapter_count").takeIf { it > 0 },
        )
    }

    fun bookDetail(source: JsonObject): BookDetail {
        val interaction = source.obj("interaction_stats", "book_interaction")
        val alternate = source.array("alternate_versions", "alternateVersions")
            .mapNotNull { (it as? JsonObject)?.let(::book) }
            .filter { it.id > 0 }
        return BookDetail(
            book = book(source),
            publisher = user(source.obj("poster_user", "publisher", "user")),
            alternateVersions = alternate,
            commentCount = interaction?.int("comment_count") ?: source.int("comment_count"),
            favoriteCount = interaction?.int("favorite_count") ?: source.int("favorite_count"),
        )
    }

    fun volume(source: JsonObject): Volume = Volume(
        id = source.long("volume_id", "id"),
        bookId = source.long("book_id"),
        title = source.string("title", "volume_title").ifBlank { "未命名分卷" },
        chapterCount = source.int("chapter_count"),
        firstChapterId = source.long("first_chapter_id").takeIf { it > 0 },
        lastChapterId = source.long("last_chapter_id").takeIf { it > 0 },
    )

    fun chapter(source: JsonObject): ChapterSummary = ChapterSummary(
        id = source.long("chapter_id", "id"),
        bookId = source.long("book_id"),
        volumeId = source.long("volume_id"),
        title = source.string("title", "chapter_title").ifBlank { "未命名章节" },
        order = source.int("chapter_no", "order_no", "sort_index"),
        wordCount = source.long("word_count"),
        locked = source.bool("locked") == true || source.bool("unlocked") == false,
    )

    fun chapterDetail(source: JsonObject): ChapterDetail {
        val body = source.obj("body_snapshot", "body", "content")
        val navigation = source.obj("navigation")
        return ChapterDetail(
            chapter = chapter(source),
            bookTitle = source.string("book_title"),
            volumeTitle = source.string("volume_title", "origin_volume_title"),
            bodyText = body?.string("body_text", "text", "content_text").orEmpty(),
            bodyHtml = body?.string("body_html", "html", "content_html").orEmpty(),
            previousChapterId = navigation?.navigationId("prev_chapter", "previous_chapter"),
            nextChapterId = navigation?.navigationId("next_chapter"),
        )
    }

    private fun JsonObject.navigationId(vararg keys: String): Long? {
        val element = element(*keys) ?: return null
        val candidate = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return null
        return candidate.long("chapter_id", "id").takeIf { it > 0 }
    }

    fun comment(source: JsonObject): Comment {
        val author = user(source.obj("user", "author", "sender", "poster_user"))
            ?: UserSummary(0, "匿名用户")
        return Comment(
            id = source.long("comment_id", "id"),
            author = author,
            content = source.string("content", "content_text", "body", "text"),
            createdAt = source.string("created_at", "time", "createdAt"),
            likeCount = source.int("like_count", "likes"),
            replyCount = source.int("reply_count", "replies"),
        )
    }

    fun booksPage(source: JsonObject, requestedPage: Int = 1): Page<BookSummary> {
        val list = sequenceOf("list", "cards", "ranking_list", "items", "books")
            .map { source.array(it) }
            .firstOrNull { it.isNotEmpty() }
            ?: JsonArray(emptyList())
        val pagination = source.obj("pagination", "page_info")
        val page = pagination?.int("page", "cur")?.takeIf { it >= 0 } ?: requestedPage
        val total = pagination?.int("total", "count") ?: list.size
        val nextPage = pagination?.int("next") ?: 0
        val pageSize = pagination?.int("page_size", "size") ?: list.size.coerceAtLeast(1)
        val hasMore = pagination?.bool("has_next")
            ?: (nextPage > 0 || page * pageSize < total)
        return Page(
            items = list.mapNotNull { (it as? JsonObject)?.let(::book) }.filter { it.id > 0 },
            page = page,
            total = total,
            hasMore = hasMore,
        )
    }

    fun taxonomy(source: JsonObject): SearchTaxonomy {
        val channels = source.array("channels").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("code", "id", "jump_value")
            val label = obj.string("label", "title", "name")
            if (id.isBlank() || label.isBlank()) null else SearchOption(id, label, obj.obj("filter")?.string("work_type").orEmpty())
        }
        val tags = source.array("tabs").flatMap { tab ->
            (tab as? JsonObject)?.array("groups").orEmpty().flatMap { group ->
                (group as? JsonObject)?.array("sections").orEmpty().flatMap { section ->
                    (section as? JsonObject)?.array("tag_items").orEmpty().mapNotNull { tag ->
                        val obj = tag as? JsonObject ?: return@mapNotNull null
                        val id = obj.string("jump_value", "code", "alias", "title", "name", "label")
                        val label = obj.string("title", "name", "label", "tag_name")
                        if (id.isBlank() || label.isBlank()) null else SearchOption(id, label)
                    }
                }
            }
        }.distinctBy { it.id }.take(30)
        return SearchTaxonomy(channels, tags)
    }
}
