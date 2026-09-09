package io.github.jiangyuyi.lightnovel.desktop

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val DESKTOP_SHELF_SOURCE_ID = "light_novel_shelf"
const val DESKTOP_SHELF_API_ORIGIN = "https://api.lightnovel.life"
const val DESKTOP_SHELF_HUB_URL = "$DESKTOP_SHELF_API_ORIGIN/hub/api"

class LightNovelShelfDesktopSource(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val apiOrigin: String = DESKTOP_SHELF_API_ORIGIN,
    private val hubUrl: String = DESKTOP_SHELF_HUB_URL,
    private val sessionStore: DesktopSessionStore = DesktopSessionStore(),
) : DesktopSource {
    override val id = DESKTOP_SHELF_SOURCE_ID
    override val displayName = "轻书架"
    override val feeds = listOf(
        DesktopFeed.POPULAR,
        DesktopFeed.LATEST,
        DesktopFeed.NEWEST,
        DesktopFeed.DAILY_RANK,
        DesktopFeed.WEEKLY_RANK,
        DesktopFeed.MONTHLY_RANK,
    )

    private val signalR = DesktopShelfSignalR(client, json, hubUrl) {
        sessionStore.read(id)["access"].orEmpty()
    }

    override fun restoreSession(): DesktopSession {
        val values = sessionStore.read(id)
        if (values["access"].isNullOrBlank() && values["refresh"].isNullOrBlank()) return DesktopSession(id, false)
        return runCatching {
            refreshTokenIfNeeded()
            DesktopSession(id, true, values["account"], values["display"])
        }.getOrElse {
            sessionStore.clear(id)
            DesktopSession(id, false)
        }
    }

    override fun login(identifier: String, password: String): DesktopSession {
        val email = identifier.trim()
        require(email.isNotBlank()) { "请输入轻书架邮箱" }
        require(password.isNotEmpty()) { "请输入轻书架密码" }
        val root = post(
            "/api/user/login",
            buildJsonObject {
                put("email", email)
                put("password", sha256Hex(password))
            },
        )
        val response = root.obj("Response", "response") ?: root
        val access = response.text("Token", "token") ?: throw IllegalStateException("轻书架登录响应缺少 Token")
        val refresh = response.text("RefreshToken", "refreshToken")
            ?: throw IllegalStateException("轻书架登录响应缺少 RefreshToken")
        sessionStore.write(id, mapOf("access" to access, "refresh" to refresh, "display" to email))
        signalR.reset()
        return DesktopSession(id, true, displayName = email)
    }

    override fun logout() {
        sessionStore.clear(id)
        signalR.reset()
    }

    override fun discover(feed: DesktopFeed, page: Int, pageSize: Int): DesktopPage {
        require(feed in feeds) { "轻书架不支持分区：${feed.label}" }
        val acceptedPage = page.coerceAtLeast(1)
        val acceptedSize = pageSize.coerceIn(1, 50)
        if (feed == DesktopFeed.DAILY_RANK || feed == DesktopFeed.WEEKLY_RANK || feed == DesktopFeed.MONTHLY_RANK) {
            val days = when (feed) {
                DesktopFeed.DAILY_RANK -> 1
                DesktopFeed.WEEKLY_RANK -> 7
                else -> 31
            }
            val all = invoke("GetRank", buildJsonObject { put("Days", days) }).asShelfBooks()
            return all.toPage(acceptedPage, acceptedSize)
        }
        val order = when (feed) {
            DesktopFeed.POPULAR -> "view"
            DesktopFeed.LATEST -> "latest"
            DesktopFeed.NEWEST -> "new"
            else -> error("unsupported feed")
        }
        return invoke(
            "GetBookList",
            buildJsonObject {
                put("Page", acceptedPage)
                put("Size", acceptedSize)
                put("Order", order)
                put("IgnoreJapanese", true)
                put("IgnoreAI", true)
            },
        ).asShelfPage(acceptedPage, acceptedSize).toDesktopPage()
    }

    override fun search(query: String, page: Int, pageSize: Int): DesktopPage {
        require(query.trim().isNotBlank()) { "搜索关键词不能为空" }
        val acceptedPage = page.coerceAtLeast(1)
        val acceptedSize = pageSize.coerceIn(1, 50)
        return invoke(
            "GetBookList",
            buildJsonObject {
                put("KeyWords", query.trim())
                put("Page", acceptedPage)
                put("Size", acceptedSize)
                put("IgnoreJapanese", false)
                put("IgnoreAI", false)
            },
        ).asShelfPage(acceptedPage, acceptedSize).toDesktopPage()
    }

    override fun detail(remoteId: String): DesktopBookDetail {
        val book = invoke("GetBookInfo", buildJsonObject { put("Id", remoteId.requirePositiveLong("轻书架书籍 ID")) })
            .asBookDetail()
        return DesktopBookDetail(
            book = book.toDesktopBook(),
            description = book.introduction,
            favoriteCount = book.favoriteCount,
        )
    }

    override fun volumes(remoteId: String): List<DesktopVolume> {
        val bookId = remoteId.requirePositiveLong("轻书架书籍 ID")
        val detail = invoke("GetBookInfo", buildJsonObject { put("Id", bookId) }).asBookDetail()
        return listOf(DesktopVolume(id, "default", bookId.toString(), "正文", detail.chapters.size))
    }

    override fun chapters(remoteId: String, volumeRemoteId: String, page: Int, pageSize: Int): DesktopChapterPage {
        require(volumeRemoteId == "default") { "轻书架未知分卷" }
        val bookId = remoteId.requirePositiveLong("轻书架书籍 ID")
        val detail = invoke("GetBookInfo", buildJsonObject { put("Id", bookId) }).asBookDetail()
        val acceptedPage = page.coerceAtLeast(1)
        val acceptedSize = pageSize.coerceIn(1, 50)
        val from = ((acceptedPage - 1) * acceptedSize).coerceAtMost(detail.chapters.size)
        val items = detail.chapters.drop(from).take(acceptedSize).mapIndexed { index, chapter ->
            val order = from + index + 1
            DesktopChapter(id, order.toString(), bookId.toString(), "default", chapter.title, order)
        }
        return DesktopChapterPage(items, acceptedPage, detail.chapters.size, from + items.size < detail.chapters.size)
    }

    override fun chapter(remoteId: String, chapterRemoteId: String): DesktopChapterContent {
        val bookId = remoteId.requirePositiveLong("轻书架书籍 ID")
        val sortNumber = chapterRemoteId.requirePositiveLong("轻书架章节编号").toInt()
        val content = invoke("GetNovelContent", buildJsonObject {
            put("Bid", bookId)
            put("SortNum", sortNumber)
        }).asNovelContent(bookId, sortNumber)
        val chapter = DesktopChapter(id, content.sortNumber.toString(), bookId.toString(), "default", content.title, content.sortNumber)
        return DesktopChapterContent(
            chapter = chapter,
            bookTitle = "",
            volumeTitle = "正文",
            bodyHtml = content.html,
            previousChapterId = (content.sortNumber - 1).takeIf { it > 0 }?.toString(),
            nextChapterId = (content.sortNumber + 1).takeIf { content.chapterTitles.isEmpty() || it <= content.chapterTitles.size }?.toString(),
        )
    }

    override fun bookshelf(): List<DesktopBook> {
        val shelf = invoke("GetBookShelf", JsonNull).asShelfSnapshot()
        val ids = shelf.items.mapNotNull { if (it.type == ShelfItemType.BOOK) it.id.toLongOrNull() else null }.distinct()
        return ids.chunked(24).flatMap { batch ->
            if (batch.isEmpty()) emptyList() else invoke("GetBookListByIds", buildJsonObject {
                put("Ids", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } })
            }).asShelfBooks()
        }.map { it.toDesktopBook().copy(inRemoteShelf = true) }
    }

    override fun history(): List<DesktopBook> {
        val ids = invoke("GetReadHistory", JsonNull).asReadHistory()
        return ids.chunked(24).flatMap { batch ->
            if (batch.isEmpty()) emptyList() else invoke("GetBookListByIds", buildJsonObject {
                put("Ids", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } })
            }).asShelfBooks()
        }.map(ShelfBook::toDesktopBook)
    }

    override fun setBookshelf(remoteId: String, add: Boolean): Boolean {
        val bookId = remoteId.requirePositiveLong("轻书架书籍 ID")
        val shelf = invoke("GetBookShelf", JsonNull).asShelfSnapshot()
        val current = shelf.items.filterNot { it.type == ShelfItemType.BOOK && it.id == bookId.toString() }
        val next = if (add) listOf(ShelfItem(ShelfItemType.BOOK, bookId.toString(), 0, emptyList(), "")) + current else current
        invoke("SaveBookShelf", buildJsonObject {
            put("data", buildJsonArray { next.forEach { add(it.toJson()) } })
            put("ver", shelf.version ?: "20220211")
        })
        return add
    }

    override fun profile(): DesktopProfile {
        val profile = invoke("GetMyInfo", buildJsonObject {}).asShelfProfile()
        return DesktopProfile(
            sourceId = id,
            accountId = profile.accountId,
            displayName = profile.displayName,
            balance = profile.balance,
            extra = mapOf(
                "连续签到" to "${profile.streakDays} 天",
                "今日签到" to if (profile.claimedToday) "已完成" else "未完成",
            ),
        )
    }

    override fun rewardStatus(): DesktopReward {
        val profile = profile()
        return DesktopReward(claimedToday = profile.extra["今日签到"] == "已完成", balance = profile.balance,
            streakDays = profile.extra["连续签到"]?.filter(Char::isDigit)?.toIntOrNull())
    }

    override fun claimDailyReward(): DesktopReward {
        val before = profile()
        if (before.extra["今日签到"] == "已完成") return DesktopReward(0, before.balance, before.extra["连续签到"]?.filter(Char::isDigit)?.toIntOrNull(), true)
        val result = invoke("SignIn", buildJsonObject {}).asReward()
        return DesktopReward(result.amount, before.balance?.plus(result.amount), result.streak, true)
    }

    private fun refreshTokenIfNeeded() {
        val values = sessionStore.read(id)
        val refresh = values["refresh"].orEmpty()
        if (refresh.isBlank()) return
        val root = post("/api/user/refresh_token", buildJsonObject { put("token", refresh) })
        val access = root.findTextValue("Response", "response", "Token", "token")
            ?: throw IllegalStateException("轻书架刷新响应缺少会话令牌")
        sessionStore.write(id, values + ("access" to access))
    }

    private fun invoke(target: String, params: JsonElement): JsonElement {
        var retried = false
        while (true) {
            try {
                return unwrap(signalR.invoke(target, params))
            } catch (error: DesktopShelfException) {
                if (error.authentication && !retried && sessionStore.read(id)["refresh"].orEmpty().isNotBlank()) {
                    retried = true
                    refreshTokenIfNeeded()
                    signalR.reset()
                } else throw error
            }
        }
    }

    private fun post(path: String, body: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(apiOrigin.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        catch (error: Exception) { throw IllegalStateException("轻书架网络请求失败：${error.message ?: "连接失败"}", error) }
        if (response.statusCode() !in 200..299) throw IllegalStateException("轻书架返回 HTTP ${response.statusCode()}")
        val root = runCatching { json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("轻书架返回了无法识别的数据")
        if (root.boolean("Success", "success") == false) throw DesktopShelfException(root.text("Msg", "msg") ?: "轻书架请求失败", root.int("Status", "status") in setOf(401, -100))
        return root
    }

    private fun unwrap(value: JsonElement): JsonElement {
        val envelope = value as? JsonObject ?: throw IllegalStateException("轻书架返回了无效响应")
        if (envelope.boolean("Success", "success") == false) throw DesktopShelfException(envelope.text("Msg", "msg") ?: "轻书架请求失败", envelope.int("Status", "status") in setOf(401, -100))
        val response = envelope["Response"] ?: envelope["response"] ?: envelope["Result"] ?: envelope["result"] ?: JsonNull
        return response.inflateIfGzip()
    }

    private fun JsonElement.inflateIfGzip(): JsonElement {
        val encoded = (this as? JsonPrimitive)?.contentOrNull ?: return this
        val compressed = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return this
        if (compressed.size < 2 || compressed[0] != 0x1f.toByte() || compressed[1] != 0x8b.toByte()) return this
        val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes().toString(Charsets.UTF_8) }
        return json.parseToJsonElement(raw)
    }

    private fun JsonElement.asShelfBooks(): List<ShelfBook> = when (this) {
        is JsonArray -> mapNotNull { (it as? JsonObject)?.toShelfBook() }
        is JsonObject -> array("Data", "data", "Books", "books", "Items", "items")
            .mapNotNull { (it as? JsonObject)?.toShelfBook() }
        else -> emptyList()
    }

    private fun JsonElement.asReadHistory(): List<Long> {
        val root = this as? JsonObject ?: return emptyList()
        return root.array("Novel", "novel", "History", "history", "Items", "items", "Data", "data").mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
                ?: (element as? JsonObject)?.long("Id", "id", "BookId", "bookId")?.takeIf { it > 0 }
        }.distinct()
    }

    private fun JsonElement.asShelfPage(requestedPage: Int, pageSize: Int): ShelfPage {
        val objectValue = this as? JsonObject ?: JsonObject(emptyMap())
        val totalItems = objectValue.int("TotalCount", "totalCount", "Count", "count").takeIf { it > 0 }
        val declaredPages = objectValue.int("TotalPages", "totalPages", "PageCount", "pageCount", "Pages", "pages")
            .takeIf { it > 0 }
        return ShelfPage(
            page = objectValue.int("Page", "page", "CurrentPage", "currentPage").takeIf { it > 0 } ?: requestedPage,
            totalPages = declaredPages ?: totalItems?.let { (it + pageSize - 1) / pageSize }?.coerceAtLeast(1) ?: 1,
            items = objectValue.array("Data", "data", "Items", "items", "Books", "books")
                .mapNotNull { (it as? JsonObject)?.toShelfBook() },
            pageSize = pageSize,
            totalItems = totalItems,
        )
    }

    private fun JsonElement.asBookDetail(): ShelfDetail {
        val root = this as? JsonObject ?: throw IllegalStateException("轻书架详情响应无效")
        val book = root.obj("Book", "book") ?: root
        val extra = book.obj("Extra", "extra")
        val classification = extra?.obj("classification")
        return ShelfDetail(
            id = book.long("Id", "id"),
            title = book.text("Title", "title") ?: "未命名作品",
            coverUrl = normalizeShelfCover(book.text("Cover", "cover")),
            author = book.text("Author", "author") ?: classification?.text("author"),
            introduction = book.text("Introduction", "introduction", "Description", "description").orEmpty(),
            tags = classification?.array("tags")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            favoriteCount = book.int("Favorite", "favorite"),
            chapters = book.array("Chapter", "chapter").mapNotNull { (it as? JsonObject)?.let { chapter -> ShelfChapter(chapter.long("Id", "id"), chapter.text("Title", "title") ?: "未命名章节") } },
        )
    }

    private fun JsonElement.asNovelContent(bookId: Long, sortNumber: Int): ShelfContent {
        val root = this as? JsonObject ?: throw IllegalStateException("轻书架正文响应无效")
        val chapter = root.obj("Chapter", "chapter") ?: root
        return ShelfContent(
            bookId = chapter.long("BookId", "bookId").takeIf { it > 0 } ?: bookId,
            title = chapter.text("Title", "title") ?: "未命名章节",
            html = chapter.text("Content", "content").orEmpty(),
            sortNumber = chapter.int("SortNum", "sortNum").takeIf { it > 0 } ?: sortNumber,
            chapterTitles = chapter.array("Chapters", "chapters").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    private fun JsonElement.asShelfSnapshot(): ShelfSnapshot {
        val root = this as? JsonObject
        val values = when (this) {
            is JsonArray -> this
            is JsonObject -> (this["Data"] ?: this["data"] ?: JsonArray(emptyList())) as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return ShelfSnapshot(root?.text("Ver", "ver"), values.mapNotNull { (it as? JsonObject)?.toShelfItem() })
    }

    private fun JsonElement.asShelfProfile(): ShelfProfile {
        val root = this as? JsonObject ?: throw IllegalStateException("轻书架资料响应无效")
        val growth = root.obj("Growth", "growth") ?: JsonObject(emptyMap())
        return ShelfProfile(
            accountId = root.long("Id", "id").takeIf { it > 0 }?.toString(),
            displayName = root.text("UserName", "userName", "username") ?: "轻书架用户",
            balance = growth.long("Coin", "coin"),
            streakDays = growth.int("SignStreak", "signStreak"),
            claimedToday = growth.boolean("TodaySigned", "todaySigned") == true,
        )
    }

    private fun JsonElement.asReward(): RewardWire {
        val root = this as? JsonObject ?: JsonObject(emptyMap())
        return RewardWire(root.long("Reward", "reward"), root.int("Streak", "streak"))
    }

    internal data class ShelfBook(val id: Long, val title: String, val coverUrl: String?, val author: String?) {
        fun toDesktopBook() = DesktopBook(DESKTOP_SHELF_SOURCE_ID, id.toString(), title.ifBlank { "未命名作品" }, author.orEmpty(), coverUrl)
    }

    private data class ShelfPage(
        val page: Int,
        val totalPages: Int,
        val items: List<ShelfBook>,
        val pageSize: Int,
        val totalItems: Int? = null,
    ) {
        fun toDesktopPage() = DesktopPage(
            items.map(ShelfBook::toDesktopBook),
            page,
            totalItems ?: totalPages * pageSize,
            page < totalPages,
        )
    }

    private data class ShelfDetail(val id: Long, val title: String, val coverUrl: String?, val author: String?, val introduction: String, val tags: List<String>, val favoriteCount: Int, val chapters: List<ShelfChapter>) {
        fun toDesktopBook() = DesktopBook(DESKTOP_SHELF_SOURCE_ID, id.toString(), title, author.orEmpty(), coverUrl, introduction)
    }

    private data class ShelfChapter(val id: Long, val title: String)
    private data class ShelfContent(val bookId: Long, val title: String, val html: String, val sortNumber: Int, val chapterTitles: List<String>)
    private data class ShelfProfile(val accountId: String?, val displayName: String, val balance: Long, val streakDays: Int, val claimedToday: Boolean)
    private data class RewardWire(val amount: Long, val streak: Int)
    internal enum class ShelfItemType { BOOK, FOLDER }
    internal data class ShelfItem(val type: ShelfItemType, val id: String, val index: Int, val parents: List<String>, val title: String) {
        fun toJson() = buildJsonObject {
            if (type == ShelfItemType.BOOK) put("id", id.toLong()) else put("id", id)
            put("index", index)
            put("parents", buildJsonArray { parents.forEach { add(JsonPrimitive(it)) } })
            put("type", type.name)
            if (type == ShelfItemType.FOLDER) put("title", title)
            put("updateAt", "")
        }
    }
    private data class ShelfSnapshot(val version: String?, val items: List<ShelfItem>)
}

private class DesktopShelfException(message: String, val authentication: Boolean) : IllegalStateException(message)

private class DesktopShelfSignalR(
    private val client: HttpClient,
    private val json: Json,
    private val hubUrl: String,
    private val token: () -> String,
) {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonElement>>()
    private var socket: WebSocket? = null
    private var buffer = StringBuilder()
    private val separator = '\u001e'

    fun invoke(target: String, params: JsonElement): JsonElement {
        ensureConnected()
        val id = sequence.incrementAndGet().toString()
        val result = CompletableFuture<JsonElement>()
        pending[id] = result
        val message = buildJsonObject {
            put("type", 1)
            put("invocationId", id)
            put("target", target)
            put("arguments", JsonArray(listOf(params, buildJsonObject { put("UseGzip", true) })))
        }.toString() + separator
        val active = synchronized(lock) { socket }
        if (active == null) {
            pending.remove(id)
            throw DesktopShelfException("无法向轻书架发送请求", false)
        }
        try {
            active.sendText(message, true).join()
        } catch (error: Exception) {
            pending.remove(id)
            throw DesktopShelfException("无法向轻书架发送请求", false)
        }
        return try {
            result.get(35, TimeUnit.SECONDS)
        } catch (error: Exception) {
            throw DesktopShelfException("轻书架请求超时", false)
        } finally {
            pending.remove(id)
        }
    }

    fun reset() {
        val active = synchronized(lock) {
            val old = socket
            socket = null
            buffer = StringBuilder()
            old
        }
        active?.abort()
        pending.values.forEach { it.completeExceptionally(DesktopShelfException("轻书架连接已重置", false)) }
        pending.clear()
    }

    private fun ensureConnected() {
        synchronized(lock) { if (socket != null) return }
        val handshake = CompletableFuture<Unit>()
        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.sendText(buildJsonObject { put("protocol", "json"); put("version", 1) }.toString() + separator, true)
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
                synchronized(lock) { buffer.append(data) }
                drainFrames(handshake)
                webSocket.request(1)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                handshake.completeExceptionally(error)
                failPending(DesktopShelfException("轻书架连接失败", false))
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
                synchronized(lock) { if (socket === webSocket) socket = null }
                handshake.completeExceptionally(DesktopShelfException("轻书架连接已关闭", false))
                failPending(DesktopShelfException("轻书架连接已关闭", false))
                return null
            }
        }
        val builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
        val access = token()
        val url = if (access.isBlank()) hubUrl else "$hubUrl?access_token=${java.net.URLEncoder.encode(access, Charsets.UTF_8)}"
        if (access.isNotBlank()) builder.header("Authorization", "Bearer $access")
        val created = try { builder.buildAsync(URI.create(desktopSignalRWebSocketUrl(url)), listener).join() }
        catch (error: Exception) { throw DesktopShelfException("无法连接轻书架", false) }
        synchronized(lock) { socket = created }
        try { handshake.get(15, TimeUnit.SECONDS) }
        catch (error: Exception) { reset(); throw DesktopShelfException("轻书架握手超时", false) }
    }

    private fun drainFrames(handshake: CompletableFuture<Unit>) {
        val frames = mutableListOf<String>()
        synchronized(lock) {
            while (true) {
                val index = buffer.indexOf(separator)
                if (index < 0) break
                frames += buffer.substring(0, index)
                buffer.delete(0, index + 1)
            }
        }
        frames.filter(String::isNotBlank).forEach { frame ->
            val message = runCatching { json.parseToJsonElement(frame) as? JsonObject }.getOrNull() ?: return@forEach
            if (!handshake.isDone) {
                if (message["error"] != null) handshake.completeExceptionally(DesktopShelfException("轻书架握手失败", true)) else handshake.complete(Unit)
                return@forEach
            }
            if (message["type"]?.jsonPrimitive?.intOrNull == 3) {
                val id = message["invocationId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val deferred = pending.remove(id) ?: return@forEach
                val error = message["error"]?.jsonPrimitive?.contentOrNull
                if (error != null) {
                    deferred.completeExceptionally(
                        DesktopShelfException(
                            error,
                            error.contains("unauthorized", ignoreCase = true) || error.contains("401"),
                        ),
                    )
                } else {
                    deferred.complete(message["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}

private fun JsonObject.element(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
private fun JsonObject.obj(vararg keys: String): JsonObject? = element(*keys) as? JsonObject
private fun JsonObject.array(vararg keys: String): JsonArray = (element(*keys) as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.text(vararg keys: String): String? = (element(*keys) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.int(vararg keys: String): Int = (element(*keys) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
private fun JsonObject.long(vararg keys: String): Long = (element(*keys) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
private fun JsonObject.boolean(vararg keys: String): Boolean? = (element(*keys) as? JsonPrimitive)?.contentOrNull?.let { value -> value.toBooleanStrictOrNull() ?: value.toIntOrNull()?.let { it != 0 } }
private fun JsonObject.toShelfBook(): LightNovelShelfDesktopSource.ShelfBook? {
    val id = long("Id", "id").takeIf { it > 0 } ?: return null
    return LightNovelShelfDesktopSource.ShelfBook(
        id = id,
        title = text("Title", "title").orEmpty(),
        coverUrl = normalizeShelfCover(text("Cover", "cover")),
        author = text("UserName", "userName", "username", "Author", "author"),
    )
}

private fun JsonObject.toShelfItem(): LightNovelShelfDesktopSource.ShelfItem? {
    val rawType = text("type", "Type")?.uppercase() ?: return null
    val type = when (rawType) {
        "BOOK", "0" -> LightNovelShelfDesktopSource.ShelfItemType.BOOK
        "FOLDER", "1" -> LightNovelShelfDesktopSource.ShelfItemType.FOLDER
        else -> return null
    }
    val rawId = text("id", "Id") ?: return null
    if (type == LightNovelShelfDesktopSource.ShelfItemType.BOOK && rawId.toLongOrNull() == null) return null
    val parents = (this["parents"] ?: this["Parents"]) as? JsonArray ?: JsonArray(emptyList())
    return LightNovelShelfDesktopSource.ShelfItem(
        type = type,
        id = rawId,
        index = int("index", "Index"),
        parents = parents.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        title = text("title", "Title").orEmpty(),
    )
}

private fun JsonObject.findTextValue(vararg keys: String): String? = keys.asSequence().mapNotNull { key ->
    when (val value = this[key]) {
        is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
        is JsonObject -> value.text("value", "id", "uid", "token", "security_key")
        else -> null
    }
}.firstOrNull()

private fun String.requirePositiveLong(label: String): Long = toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$label 无效")
private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
private fun normalizeShelfCover(value: String?): String? = value?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
    val url = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "https://api.lightnovel.life$raw"
        else -> raw
    }
    if ('#' in url && "placeholder=" in url) url.replace("#", "%23") else url
}

internal fun desktopSignalRWebSocketUrl(url: String): String = url
    .replaceFirst("^https://".toRegex(), "wss://")
    .replaceFirst("^http://".toRegex(), "ws://")

private fun List<LightNovelShelfDesktopSource.ShelfBook>.toPage(page: Int, pageSize: Int): DesktopPage {
    val from = ((page - 1) * pageSize).coerceAtMost(size)
    val items = drop(from).take(pageSize)
    return DesktopPage(items.map { it.toDesktopBook() }, page, size, from + items.size < size)
}
