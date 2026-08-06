package io.github.jiangyuyi.lightnovel.core.data

import io.github.jiangyuyi.lightnovel.core.model.BookDetail
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import io.github.jiangyuyi.lightnovel.core.model.ChapterDetail
import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.model.Comment
import io.github.jiangyuyi.lightnovel.core.model.DiscoverChannel
import io.github.jiangyuyi.lightnovel.core.model.Page
import io.github.jiangyuyi.lightnovel.core.model.ReaderBootstrap
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.SearchTaxonomy
import io.github.jiangyuyi.lightnovel.core.model.Session
import io.github.jiangyuyi.lightnovel.core.model.Volume
import io.github.jiangyuyi.lightnovel.core.network.ApiParsers
import io.github.jiangyuyi.lightnovel.core.network.LightNovelApi
import io.github.jiangyuyi.lightnovel.core.network.array
import io.github.jiangyuyi.lightnovel.core.network.bool
import io.github.jiangyuyi.lightnovel.core.network.int
import io.github.jiangyuyi.lightnovel.core.network.jsonBody
import io.github.jiangyuyi.lightnovel.core.network.long
import io.github.jiangyuyi.lightnovel.core.network.obj
import io.github.jiangyuyi.lightnovel.core.network.string
import io.github.jiangyuyi.lightnovel.core.session.SessionStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

class LightNovelRepository(
    private val api: LightNovelApi,
    private val sessionStore: SessionStore,
) {
    val session = sessionStore.session

    suspend fun restoreSession(): Session {
        val key = sessionStore.securityKey()
        if (key.isBlank()) return Session()
        return runCatching {
            val data = api.post("api/bff/auth-session-v1", jsonBody("security_key" to key))
            if (data.bool("logged_in") != true) {
                sessionStore.clear()
                Session()
            } else {
                parseAndSaveSession(data, key)
            }
        }.getOrElse {
            // A transient network failure must not discard a valid encrypted session.
            sessionStore.session.value
        }
    }

    suspend fun login(username: String, password: String): Session {
        val data = api.post(
            "api/bff/auth-password-login-v1",
            jsonBody("username" to username.trim(), "password" to password),
        )
        return parseAndSaveSession(data)
    }

    suspend fun sendRegistrationCode(email: String) {
        val normalized = email.trim()
        val status = api.post("api/bff/auth-email-status-v1", jsonBody("email" to normalized))
        if (status.bool("registered") == true) error("该邮箱已经注册，请直接登录")
        api.post("api/bff/auth-email-register-captcha-send-v1", jsonBody("email" to normalized))
    }

    suspend fun register(email: String, captcha: String, nickname: String, password: String): Session {
        val data = api.post(
            "api/bff/auth-email-register-v1",
            jsonBody(
                "email" to email.trim(),
                "captcha" to captcha.trim(),
                "code" to captcha.trim(),
                "nickname" to nickname.trim(),
                "password" to password,
            ),
        )
        return parseAndSaveSession(data)
    }

    fun logout() = sessionStore.clear()

    suspend fun discover(channel: DiscoverChannel, page: Int = 1, pageSize: Int = 20): Page<BookSummary> {
        if (channel == DiscoverChannel.NEW) return rank("daily_fresh", page, pageSize)
        if (channel == DiscoverChannel.RANK) return rank("weekly_hot", page, pageSize)
        val path = when (channel) {
            DiscoverChannel.HOT -> "api/bff/home-feed-v1"
            DiscoverChannel.ORIGINAL -> "api/bff/home-original-feed-v1"
            DiscoverChannel.FANFIC -> "api/bff/home-fanfic-feed-v1"
            DiscoverChannel.EPUB -> "api/bff/home-epub-feed-v1"
            DiscoverChannel.UPDATED -> "api/bff/home-recent-updates-feed-v1"
            DiscoverChannel.NEW, DiscoverChannel.RANK, DiscoverChannel.COLLECTION -> error("handled before endpoint selection")
        }
        val data = api.post(
            path,
            withOptionalSession(
                "page" to page,
                "page_size" to pageSize,
                "pageSize" to pageSize,
                "read_filter" to "all",
                "status_filter" to "all",
                "category_filter" to "all",
            ),
        )
        return ApiParsers.booksPage(data, page)
    }

    suspend fun rank(scene: String = "weekly_hot", page: Int = 1, pageSize: Int = 30): Page<BookSummary> {
        val data = api.post(
            "api/bff/book-rank-list-v1",
            jsonBody("rank_scene" to scene, "page" to page, "page_size" to pageSize, "pageSize" to pageSize),
        )
        return ApiParsers.booksPage(data, page)
    }

    suspend fun taxonomy(): SearchTaxonomy =
        ApiParsers.taxonomy(api.post("api/bff/apk-search-taxonomy-v1", jsonBody()))

    suspend fun search(
        query: String,
        workType: String = "",
        primaryTag: String = "",
        page: Int = 1,
        pageSize: Int = 20,
    ): Page<BookSummary> {
        if (query.isBlank() && primaryTag.isBlank() && workType.isNotBlank()) {
            // The site's search BFF currently returns HTTP 500 for every non-empty
            // channel/work_type filter. Keep category browsing usable through the
            // equivalent, stable discovery endpoints until that branch recovers.
            return when (workType) {
                "original" -> discover(DiscoverChannel.ORIGINAL, page, pageSize)
                "fanfic" -> discover(DiscoverChannel.FANFIC, page, pageSize)
                "epub" -> discover(DiscoverChannel.EPUB, page, pageSize)
                else -> rank("weekly_hot", page, pageSize)
            }
        }
        val data = api.post(
            "api/bff/apk-search-result-v1",
            jsonBody(
                "q" to query.trim(),
                "scope" to "",
                "source" to "",
                "primary_tag" to primaryTag,
                "channel_code" to workType,
                "work_type" to "",
                "preset" to if (query.isBlank() && workType.isBlank() && primaryTag.isBlank()) "default" else "",
                "source_type" to "",
                "filters" to buildJsonObject { },
                "word_count_bucket" to "",
                "status_bucket" to "",
                "page" to (page - 1).coerceAtLeast(0),
                "pageSize" to pageSize,
                "sort" to "relevance",
            ),
        )
        return ApiParsers.booksPage(data, page)
    }

    suspend fun bookDetail(bookId: Long): BookDetail {
        val data = api.post(
            "api/new-content-read/get-book-detail",
            withOptionalSession("book_id" to bookId, "with_volumes" to 0),
        )
        return ApiParsers.bookDetail(data)
    }

    suspend fun readerBootstrap(bookId: Long): ReaderBootstrap {
        val data = api.post("api/bff/reader-bootstrap-v1", withOptionalSession("book_id" to bookId))
        val bookSource = data.obj("book") ?: data.obj("book_base") ?: data
        val target = data.obj("effective_read_target", "read_target")
        val readState = data.obj("library_state")
        val chapterId = target?.long("chapter_id")?.takeIf { it > 0 }
            ?: bookSource.long("default_chapter_id").takeIf { it > 0 }
            ?: error("这本书暂无可读章节")
        return ReaderBootstrap(
            book = ApiParsers.book(bookSource),
            chapterId = chapterId,
            volumeId = target?.long("volume_id")?.takeIf { it > 0 },
            inBookshelf = readState?.bool("in_shelf") == true,
            resumeAvailable = target?.bool("resume_available") == true,
        )
    }

    suspend fun volumes(bookId: Long, page: Int = 1, pageSize: Int = 50): Page<Volume> {
        val data = api.post(
            "api/new-content-read/get-book-volumes",
            jsonBody("book_id" to bookId, "page" to page, "page_size" to pageSize, "pageSize" to pageSize),
        )
        val list = data.array("list", "volumes")
            .mapNotNull { (it as? JsonObject)?.let(ApiParsers::volume) }
            .filter { it.id > 0 }
        val pagination = data.obj("pagination", "page_info")
        val total = pagination?.int("total", "count") ?: list.size
        return Page(list, page, total, page * pageSize < total)
    }

    suspend fun chapters(bookId: Long, volumeId: Long, page: Int = 1, pageSize: Int = 50): Page<ChapterSummary> {
        val acceptedPageSize = pageSize.coerceIn(1, 50)
        val data = api.post(
            "api/new-content-read/get-volume-chapters",
            jsonBody(
                "book_id" to bookId,
                "volume_id" to volumeId,
                "page" to page,
                "page_size" to acceptedPageSize,
                "pageSize" to acceptedPageSize,
            ),
        )
        val list = data.array("list", "chapters")
            .mapNotNull { (it as? JsonObject)?.let(ApiParsers::chapter) }
            .filter { it.id > 0 }
        val pagination = data.obj("pagination", "page_info")
        val total = pagination?.int("total", "count") ?: list.size
        return Page(list, page, total, page * acceptedPageSize < total)
    }

    suspend fun chapter(bookId: Long, chapterId: Long): ChapterDetail {
        val data = api.post(
            "api/new-content-read/get-chapter-detail",
            withOptionalSession("book_id" to bookId, "chapter_id" to chapterId),
        )
        return ApiParsers.chapterDetail(data)
    }

    suspend fun bookshelf(): List<BookSummary> {
        val key = requireSession()
        val data = api.post("api/bff/bookshelf-v1", jsonBody("security_key" to key, "page" to 1, "pageSize" to 50))
        return ApiParsers.booksPage(data).items
    }

    suspend fun isInBookshelf(bookId: Long): Boolean {
        val key = sessionStore.securityKey()
        if (key.isBlank()) return false
        val data = api.post(
            "api/new-content-read/get-book-library-state",
            jsonBody("security_key" to key, "book_id" to bookId),
        )
        return data.bool("in_shelf", "already_fav", "in_collection", "favorite") == true
    }

    suspend fun setBookshelf(bookId: Long, add: Boolean): Boolean {
        val key = requireSession()
        api.post(
            "api/new-content-read/toggle-book-shelf",
            jsonBody(
                "security_key" to key,
                "book_id" to bookId,
                "action" to if (add) "add" else "remove",
                "source" to "pc_web",
            ),
        )
        return isInBookshelf(bookId)
    }

    suspend fun saveReadingProgress(
        bookId: Long,
        volumeId: Long,
        chapterId: Long,
        paragraphIndex: Int,
        percent: Int,
    ) {
        val key = sessionStore.securityKey()
        if (key.isBlank()) return
        api.post(
            "api/new-content-read/save-book-history",
            jsonBody(
                "security_key" to key,
                "book_id" to bookId,
                "volume_id" to volumeId.takeIf { it > 0 },
                "chapter_id" to chapterId,
                "progress_percent" to percent.coerceIn(0, 100),
                "last_position" to paragraphIndex.coerceAtLeast(0),
                "read_finished" to if (percent >= 98) 1 else 0,
            ),
        )
    }

    suspend fun saveReaderSettings(preferences: ReaderPreferences) {
        val key = sessionStore.securityKey()
        if (key.isBlank()) return
        api.post(
            "api/bff/save-my-reader-settings-v1",
            jsonBody(
                "security_key" to key,
                "font_size" to preferences.fontSize.roundToInt(),
                "line_height" to preferences.lineHeight,
                "theme" to preferences.theme.name.lowercase(),
                "page_mode" to if (preferences.mode.name == "PAGED") "page" else "scroll",
                "traditional_chinese" to 0,
                "updated_at" to System.currentTimeMillis() / 1000,
            ),
        )
    }

    suspend fun comments(bookId: Long, page: Int = 1, pageSize: Int = 20): Page<Comment> {
        val data = api.post(
            "api/new-content-read/get-book-comments",
            withOptionalSession(
                "book_id" to bookId,
                "volume_id" to 0,
                "chapter_id" to 0,
                "view" to "",
                "comment_id" to 0,
                "page" to page,
                "pageSize" to pageSize,
                "rating_filter" to "all",
                "include_user_interactions" to 1,
            ),
            commentApi = true,
        )
        val list = data.array("list", "root_comment", "items")
            .mapNotNull { (it as? JsonObject)?.let(ApiParsers::comment) }
            .filter { it.content.isNotBlank() }
        val pageInfo = data.obj("page_info", "pagination")
        return Page(
            items = list,
            page = page,
            total = pageInfo?.int("count", "total") ?: list.size,
            hasMore = (pageInfo?.int("next") ?: 0) > 0 || pageInfo?.bool("has_next") == true,
        )
    }

    private fun parseAndSaveSession(data: JsonObject, fallbackKey: String = ""): Session {
        val auth = data.obj("auth") ?: buildJsonObject { }
        val key = auth.string("security_key", "securityKey", "token")
            .ifBlank { data.string("security_key", "securityKey", "token") }
            .ifBlank { fallbackKey }
        val uid = auth.long("uid").takeIf { it > 0 } ?: data.long("uid")
        val user = ApiParsers.user(data.obj("user"))
        if (key.isBlank()) error("登录响应缺少会话令牌")
        val session = Session(true, key, uid, user)
        sessionStore.save(session)
        return session
    }

    private fun requireSession(): String = sessionStore.securityKey().ifBlank { error("请先登录") }

    private fun withOptionalSession(vararg pairs: Pair<String, Any?>): JsonObject {
        val token = sessionStore.securityKey()
        return jsonBody(*pairs, "security_key" to token.takeIf { it.isNotBlank() })
    }
}
