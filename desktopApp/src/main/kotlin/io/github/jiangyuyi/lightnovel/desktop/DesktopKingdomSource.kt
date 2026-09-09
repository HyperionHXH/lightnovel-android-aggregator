package io.github.jiangyuyi.lightnovel.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val DESKTOP_KINGDOM_SOURCE_ID = "light_novel_kingdom"
const val DESKTOP_KINGDOM_API_ORIGIN = "https://www.lightnovel.fun/api/pc-proxy/"

class LightNovelKingdomDesktopSource(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val apiOrigin: String = DESKTOP_KINGDOM_API_ORIGIN,
    private val sessionStore: DesktopSessionStore = DesktopSessionStore(),
) : DesktopSource {
    override val id = DESKTOP_KINGDOM_SOURCE_ID
    override val displayName = "轻之国度"
    override val feeds = listOf(
        DesktopFeed.POPULAR,
        DesktopFeed.LATEST,
        DesktopFeed.NEWEST,
        DesktopFeed.ORIGINAL,
        DesktopFeed.FANFIC,
        DesktopFeed.EPUB,
        DesktopFeed.WEEKLY_RANK,
    )

    override fun restoreSession(): DesktopSession {
        val security = sessionStore.read(id)["security"].orEmpty()
        if (security.isBlank()) return DesktopSession(id, false)
        return runCatching {
            val payload = post("api/bff/auth-session-v1", buildJsonObject { put("security_key", security) })
            if (payload.boolean("logged_in") == false) {
                sessionStore.clear(id)
                DesktopSession(id, false)
            } else {
                val values = sessionStore.read(id)
                DesktopSession(id, true, values["account"], values["display"])
            }
        }.getOrElse {
            val values = sessionStore.read(id)
            DesktopSession(id, true, values["account"], values["display"])
        }
    }

    override fun login(identifier: String, password: String): DesktopSession {
        require(identifier.trim().isNotBlank()) { "请输入轻之国度用户名或邮箱" }
        require(password.isNotEmpty()) { "请输入轻之国度密码" }
        val payload = post(
            "api/bff/auth-password-login-v1",
            buildJsonObject {
                put("username", identifier.trim())
                put("password", password)
            },
        )
        val security = payload.findText("security_key", "securityKey", "token")
            ?: throw IllegalStateException("轻之国度登录响应缺少会话信息")
        val account = payload.findText("uid", "user_id", "id")
        val display = payload.findText("nickname", "username", "name")
        sessionStore.write(
            id,
            buildMap {
                put("security", security)
                account?.let { put("account", it) }
                display?.let { put("display", it) }
            },
        )
        return DesktopSession(id, true, account, display)
    }

    override fun logout() = sessionStore.clear(id)

    override fun discover(feed: DesktopFeed, page: Int, pageSize: Int): DesktopPage {
        val body = when (feed) {
            DesktopFeed.NEWEST -> rankBody("daily_fresh", page, pageSize)
            DesktopFeed.WEEKLY_RANK -> rankBody("weekly_hot", page, pageSize)
            DesktopFeed.POPULAR -> feedBody("api/bff/home-feed-v1", page, pageSize)
            DesktopFeed.LATEST -> feedBody("api/bff/home-recent-updates-feed-v1", page, pageSize)
            DesktopFeed.ORIGINAL -> feedBody("api/bff/home-original-feed-v1", page, pageSize)
            DesktopFeed.FANFIC -> feedBody("api/bff/home-fanfic-feed-v1", page, pageSize)
            DesktopFeed.EPUB -> feedBody("api/bff/home-epub-feed-v1", page, pageSize)
            else -> throw IllegalArgumentException("轻之国度不支持分区：${feed.label}")
        }
        val (path, requestBody) = body
        return parseBookPage(post(path, requestBody), page, pageSize)
    }

    override fun search(query: String, page: Int, pageSize: Int): DesktopPage {
        require(query.trim().isNotBlank()) { "搜索关键词不能为空" }
        val payload = post(
            "api/bff/apk-search-result-v1",
            buildJsonObject {
                put("q", query.trim())
                put("scope", "")
                put("source", "")
                put("primary_tag", "")
                put("channel_code", "")
                put("work_type", "")
                put("preset", "")
                put("source_type", "")
                put("filters", buildJsonObject {})
                put("word_count_bucket", "")
                put("status_bucket", "")
                put("page", (page - 1).coerceAtLeast(0))
                put("pageSize", pageSize.coerceIn(1, 50))
                put("sort", "relevance")
            },
        )
        return parseBookPage(payload, page, pageSize)
    }

    override fun detail(remoteId: String): DesktopBookDetail {
        val bookId = remoteId.requirePositiveLong("轻之国度书籍 ID")
        val payload = post(
            "api/new-content-read/get-book-detail",
            withSession(buildJsonObject { put("book_id", bookId); put("with_volumes", 0) }),
        )
        val book = parseBook(payload.obj("book", "book_base") ?: payload)
        return DesktopBookDetail(
            book = book,
            description = book.summary,
            alternateVersions = payload.array("alternate_versions", "alternateVersions")
                .mapNotNull { (it as? JsonObject)?.let(::parseBook) },
            favoriteCount = payload.obj("interaction_stats", "book_interaction")?.int("favorite_count")
                ?: payload.int("favorite_count"),
            commentCount = payload.obj("interaction_stats", "book_interaction")?.int("comment_count")
                ?: payload.int("comment_count"),
        )
    }

    override fun volumes(remoteId: String): List<DesktopVolume> {
        val bookId = remoteId.requirePositiveLong("轻之国度书籍 ID")
        val payload = post(
            "api/new-content-read/get-book-volumes",
            buildJsonObject { put("book_id", bookId); put("page", 1); put("page_size", 50); put("pageSize", 50) },
        )
        return payload.array("list", "volumes").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.long("volume_id", "volumeId", "id").takeIf { it > 0 } ?: return@mapNotNull null
            DesktopVolume(
                sourceId = DESKTOP_KINGDOM_SOURCE_ID,
                remoteId = id.toString(),
                bookRemoteId = bookId.toString(),
                title = item.text("title", "volume_title").ifBlank { "未命名分卷" },
                chapterCount = item.int("chapter_count"),
            )
        }
    }

    override fun chapters(
        remoteId: String,
        volumeRemoteId: String,
        page: Int,
        pageSize: Int,
    ): DesktopChapterPage {
        val bookId = remoteId.requirePositiveLong("轻之国度书籍 ID")
        val volumeId = volumeRemoteId.requirePositiveLong("轻之国度分卷 ID")
        val acceptedSize = pageSize.coerceIn(1, 50)
        val payload = post(
            "api/new-content-read/get-volume-chapters",
            withSession(buildJsonObject {
                put("book_id", bookId)
                put("volume_id", volumeId)
                put("page", page.coerceAtLeast(1))
                put("page_size", acceptedSize)
                put("pageSize", acceptedSize)
            }),
        )
        val items = payload.array("list", "chapters").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val chapterId = item.long("chapter_id", "chapterId", "id").takeIf { it > 0 } ?: return@mapNotNull null
            DesktopChapter(
                sourceId = DESKTOP_KINGDOM_SOURCE_ID,
                remoteId = chapterId.toString(),
                bookRemoteId = bookId.toString(),
                volumeRemoteId = volumeId.toString(),
                title = item.text("title", "chapter_title").ifBlank { "未命名章节" },
                order = item.int("chapter_no", "order_no", "sort_index"),
                locked = item.boolean("locked") == true || item.boolean("unlocked") == false,
                coinPrice = item.coinPrice(),
            )
        }
        val pagination = payload.obj("pagination", "page_info")
        val total = pagination?.int("total", "count")?.takeIf { it >= 0 }
            ?: payload.int("total", "count").takeIf { it > 0 }
            ?: items.size
        val pageCount = pagination?.int("page_count", "pageCount", "pages") ?: 0
        val hasMore = pagination?.boolean("has_more", "hasMore", "has_next", "hasNext")
            ?: payload.boolean("has_more", "hasMore", "has_next", "hasNext")
            ?: ((pagination?.int("next", "next_page", "nextPage") ?: 0) > 0
                || (pageCount > 0 && page < pageCount)
                || page * acceptedSize < total)
        return DesktopChapterPage(items, page, total, hasMore)
    }

    override fun chapter(remoteId: String, chapterRemoteId: String): DesktopChapterContent {
        val bookId = remoteId.requirePositiveLong("轻之国度书籍 ID")
        val chapterId = chapterRemoteId.requirePositiveLong("轻之国度章节 ID")
        val payload = post(
            "api/new-content-read/get-chapter-detail",
            withSession(buildJsonObject { put("book_id", bookId); put("chapter_id", chapterId) }),
        )
        val chapter = parseChapter(payload).copy(
            sourceId = DESKTOP_KINGDOM_SOURCE_ID,
            bookRemoteId = bookId.toString(),
        )
        val body = payload.obj("body_snapshot", "body", "content")
        val navigation = payload.obj("navigation", "chapter_navigation", "nav")
        return DesktopChapterContent(
            chapter = chapter,
            bookTitle = payload.text("book_title"),
            volumeTitle = payload.text("volume_title", "origin_volume_title"),
            bodyText = body?.text("body_text", "text", "content_text").orEmpty(),
            bodyHtml = body?.text("body_html", "html", "content_html").orEmpty(),
            previousChapterId = navigation?.navigationId("prev_chapter", "previous_chapter", "prev")
                ?: payload.navigationId("prev_chapter", "previous_chapter", "prev_chapter_id"),
            nextChapterId = navigation?.navigationId("next_chapter", "next")
                ?: payload.navigationId("next_chapter", "next_chapter_id"),
        )
    }

    override fun unlockChapter(remoteId: String, chapterRemoteId: String): Boolean {
        val chapterId = chapterRemoteId.requirePositiveLong("轻之国度章节 ID")
        post(
            "api/new-content-read/unlock-chapter",
            requireSession { key -> buildJsonObject { put("security_key", key); put("chapter_id", chapterId) } },
        )
        return true
    }

    override fun bookshelf(): List<DesktopBook> {
        val payload = post(
            "api/bff/bookshelf-v1",
            requireSession { key -> buildJsonObject { put("security_key", key); put("page", 1); put("pageSize", 50) } },
        )
        return payload.array("list", "cards", "items", "books").mapNotNull { (it as? JsonObject)?.let(::parseBook) }
    }

    override fun history(): List<DesktopBook> {
        val payload = post(
            "api/bff/history-v1",
            requireSession { key -> buildJsonObject { put("security_key", key); put("page", 1); put("pageSize", 50) } },
        )
        return payload.array("list", "items", "books", "history").mapNotNull { (it as? JsonObject)?.let(::parseBook) }
    }

    override fun setBookshelf(remoteId: String, add: Boolean): Boolean {
        val bookId = remoteId.requirePositiveLong("轻之国度书籍 ID")
        post(
            "api/new-content-read/toggle-book-shelf",
            requireSession { key ->
                buildJsonObject {
                    put("security_key", key)
                    put("book_id", bookId)
                    put("action", if (add) "add" else "remove")
                    put("source", "pc_web")
                }
            },
        )
        return add
    }

    override fun profile(): DesktopProfile {
        val payload = post("api/bff/my-home-v1", requireSession { key -> buildJsonObject { put("security_key", key) } })
        val profile = payload.obj("profile", "user") ?: payload
        val stats = payload.obj("stats") ?: profile.obj("stats") ?: payload
        val balance = profile.obj("balance")
        return DesktopProfile(
            sourceId = id,
            accountId = profile.findText("uid", "user_id", "id"),
            displayName = profile.text("nickname", "username", "name").ifBlank { "已登录用户" },
            avatarUrl = profile.text("avatar", "avatar_url", "avatarUrl").ifBlank { null },
            balance = profile.long("coin", "light_coin", "lightCoin", "balance")
                .takeIf { it > 0 } ?: balance?.long("coin", "light_coin", "lightCoin"),
            levelLabel = profile.text("level_name", "levelName", "level_title", "group_name", "role_name")
                .ifBlank { profile.obj("user_group", "group", "rank")?.text("name", "title") },
            extra = mapOf(
                "关注" to stats.int("following_count", "followingCount").toString(),
                "粉丝" to stats.int("fans_count", "fansCount").toString(),
                "发布" to stats.int("post_count", "postCount").toString(),
            ),
        )
    }

    private fun withSession(body: JsonObject): JsonObject {
        val key = sessionStore.read(id)["security"].orEmpty()
        return if (key.isBlank()) body else buildJsonObject {
            body.forEach { (keyName, value) -> put(keyName, value) }
            put("security_key", key)
        }
    }

    private fun requireSession(body: (String) -> JsonObject): JsonObject {
        val key = sessionStore.read(id)["security"].orEmpty()
        if (key.isBlank()) throw IllegalStateException("请先登录轻之国度")
        return body(key)
    }

    private fun post(path: String, body: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(apiOrigin.trimEnd('/') + "/" + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mixn-Windows/1.4")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("轻之国度网络请求失败：${error.message ?: "连接失败"}", error)
        }
        if (response.statusCode() !in 200..299) throw IllegalStateException("轻之国度返回 HTTP ${response.statusCode()}")
        val root = runCatching { json.parseToJsonElement(response.body()) as? JsonObject }
            .getOrElse { error -> throw IllegalStateException("轻之国度返回了无法识别的数据", error) }
            ?: throw IllegalStateException("轻之国度返回了空响应")
        val code = root.int("code", "status")
        if (code != 0 && (root["code"] != null || root["status"] != null)) {
            throw IllegalStateException("轻之国度：${root.text("message", "msg", "error").ifBlank { "请求失败（$code）" }}")
        }
        return (root["data"] as? JsonObject) ?: (root["d"] as? JsonObject) ?: root
    }

    private fun feedBody(path: String, page: Int, pageSize: Int): Pair<String, JsonObject> = path to buildJsonObject {
        put("page", page.coerceAtLeast(1))
        put("page_size", pageSize.coerceIn(1, 50))
        put("pageSize", pageSize.coerceIn(1, 50))
        put("read_filter", "all")
        put("status_filter", "all")
        put("category_filter", "all")
        sessionStore.read(id)["security"]?.takeIf(String::isNotBlank)?.let { put("security_key", it) }
    }

    private fun rankBody(scene: String, page: Int, pageSize: Int): Pair<String, JsonObject> =
        "api/bff/book-rank-list-v1" to buildJsonObject {
            put("rank_scene", scene)
            put("page", page.coerceAtLeast(1))
            put("page_size", pageSize.coerceIn(1, 50))
            put("pageSize", pageSize.coerceIn(1, 50))
        }

    private fun parseBookPage(payload: JsonObject, requestedPage: Int, pageSize: Int): DesktopPage {
        val list = payload.array("list", "cards", "ranking_list", "items", "books")
            .ifEmpty { (payload.obj("data", "d") ?: JsonObject(emptyMap())).array("list", "cards", "items", "books") }
        val items = list.mapNotNull { (it as? JsonObject)?.let(::parseBook) }
        val pagination = payload.obj("pagination", "page_info")
        val actualPage = pagination?.int("page", "cur", "current_page")?.takeIf { it >= 0 } ?: requestedPage
        val total = pagination?.int("total", "count")?.takeIf { it >= 0 }
            ?: payload.int("total", "count").takeIf { it > 0 }
            ?: items.size
        val actualSize = pagination?.int("page_size", "pageSize", "size")?.takeIf { it > 0 } ?: pageSize
        val next = pagination?.int("next", "next_page", "nextPage") ?: 0
        val pageCount = pagination?.int("page_count", "pageCount", "pages") ?: 0
        val explicitHasMore = pagination?.boolean("has_more", "hasMore", "has_next", "hasNext")
            ?: payload.boolean("has_more", "hasMore", "has_next", "hasNext")
        val hasMore = explicitHasMore
            ?: (next > 0 || (pageCount > 0 && actualPage < pageCount) || actualPage * actualSize < total)
            || (pagination == null && items.size >= pageSize)
        return DesktopPage(items, actualPage, total, hasMore)
    }

    private fun parseBook(payload: JsonObject): DesktopBook {
        val remoteId = payload.long("book_id", "bookId", "id").takeIf { it > 0 }?.toString()
            ?: return DesktopBook(id, "", "未命名作品")
        val readState = payload.obj("read_state", "readState", "reader_state")
        val readable = readState?.boolean("is_readable", "readable", "can_read")
        val readabilityNote = readState?.text("message", "reason_message", "reason", "error")
            ?.takeIf(String::isNotBlank)
        return DesktopBook(
            sourceId = id,
            remoteId = remoteId,
            title = payload.text("title", "book_title", "name").ifBlank { "未命名作品" },
            author = payload.text("author_name", "author", "authorName", "writer"),
            coverUrl = normalizeKingdomCover(payload.text("cover_url", "coverUrl", "cover", "banner_url", "bannerUrl", "image", "book_cover", "pic_url", "pic")),
            summary = payload.text("summary_short", "summary", "content_preview", "intro", "description"),
            inRemoteShelf = payload.boolean("in_shelf", "in_collection", "favorited", "inBookshelf"),
            isReadable = readable,
            readabilityNote = readabilityNote,
            unreadChapterCount = (payload.int("unread_chapter_count", "unreadChapterCount")
                .takeIf { it > 0 } ?: readState?.int("unread_chapter_count", "unreadChapterCount")?.takeIf { it > 0 }),
        )
    }

    private fun parseChapter(payload: JsonObject): DesktopChapter {
        val idValue = payload.long("chapter_id", "chapterId", "id")
        return DesktopChapter(
            sourceId = id,
            remoteId = idValue.toString(),
            bookRemoteId = payload.long("book_id", "bookId").toString(),
            volumeRemoteId = payload.long("volume_id", "volumeId").toString(),
            title = payload.text("title", "chapter_title").ifBlank { "未命名章节" },
            order = payload.int("chapter_no", "order_no", "sort_index"),
            locked = payload.boolean("locked") == true || payload.boolean("unlocked") == false,
            coinPrice = payload.coinPrice(),
        )
    }
}

private fun String.requirePositiveLong(label: String): Long = toLongOrNull()?.takeIf { it > 0 }
    ?: throw IllegalArgumentException("$label 无效")

private fun JsonObject.element(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
private fun JsonObject.obj(vararg keys: String): JsonObject? = element(*keys) as? JsonObject
private fun JsonObject.array(vararg keys: String): JsonArray = (element(*keys) as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.text(vararg keys: String): String = (element(*keys) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
private fun JsonObject.int(vararg keys: String): Int = (element(*keys) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
private fun JsonObject.long(vararg keys: String): Long = (element(*keys) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
private fun JsonObject.boolean(vararg keys: String): Boolean? = (element(*keys) as? JsonPrimitive)?.let { it.contentOrNull?.toBooleanStrictOrNull() ?: it.intOrNull?.let { number -> number != 0 } }
private fun JsonObject.findText(vararg keys: String): String? {
    fun find(element: JsonElement): String? = when (element) {
        is JsonObject -> {
            keys.asSequence().mapNotNull { key ->
                when (val value = element[key]) {
                    is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
                    is JsonObject, is JsonArray -> find(value)
                    else -> null
                }
            }.firstOrNull() ?: element.values.asSequence().mapNotNull(::find).firstOrNull()
        }
        is JsonArray -> element.asSequence().mapNotNull(::find).firstOrNull()
        else -> null
    }
    return find(this)
}

private fun JsonObject.coinPrice(): Long? {
    fun parse(value: JsonElement?): Long? = when (value) {
        is JsonPrimitive -> value.longOrNull ?: value.contentOrNull?.toLongOrNull()
        is JsonObject -> parse(value.element("value", "amount", "coins", "coin_price", "price", "cost"))
        else -> null
    }
    return parse(element("coin_price", "coinPrice", "price", "cost"))?.takeIf { it > 0 }
}

private fun JsonObject.navigationId(vararg keys: String): String? {
    fun parse(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }?.toString()
        is JsonObject -> parse(value.element("chapter_id", "id", "chapterId", "value"))
        is JsonArray -> value.firstNotNullOfOrNull(::parse)
        else -> null
    }
    return parse(element(*keys))
}

private fun normalizeKingdomCover(value: String): String? = value.trim().takeIf(String::isNotBlank)?.let { url ->
    when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.lightnovel.fun$url"
        else -> url
    }
}
