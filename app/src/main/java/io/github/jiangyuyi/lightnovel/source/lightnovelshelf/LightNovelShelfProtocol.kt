package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ShelfBookItem(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val authorName: String?,
    val introduction: String = "",
)

internal data class ShelfBookPage(
    val page: Int,
    val totalPages: Int,
    val items: List<ShelfBookItem>,
)

internal data class ShelfBookChapter(
    val id: Long,
    val title: String,
    val sortNumber: Int? = null,
)

internal data class ShelfBookDetail(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val authorName: String?,
    val introduction: String,
    val tags: List<String>,
    val favoriteCount: Int,
    val chapters: List<ShelfBookChapter>,
)

internal data class ShelfNovelContent(
    val id: Long,
    val bookId: Long,
    val title: String,
    val html: String,
    val fontUrl: String? = null,
    val sortNumber: Int,
    val chapterTitles: List<String>,
)

internal data class ShelfProfile(
    val id: Long,
    val userName: String,
    val coin: Long,
    val signInStreak: Int,
    val signedToday: Boolean,
)

internal data class ShelfCheckInResult(
    val reward: Long,
    val streak: Int,
)

internal enum class ShelfRemoteItemType {
    BOOK,
    FOLDER,
}

internal data class ShelfRemoteItem(
    val type: ShelfRemoteItemType,
    val id: String,
    val index: Int,
    val parents: List<String>,
    val updatedAt: String,
    val title: String = "",
) {
    val bookId: Long?
        get() = if (type == ShelfRemoteItemType.BOOK) id.toLongOrNull() else null
}

internal data class ShelfRemoteSnapshot(
    val version: String?,
    val items: List<ShelfRemoteItem>,
)

internal enum class ShelfBookOrder(val wire: String) {
    LATEST("latest"),
    NEWEST("new"),
    VIEWED("view"),
}

internal interface LightNovelShelfGateway {
    suspend fun listBooks(order: ShelfBookOrder, page: Int, pageSize: Int): ShelfBookPage
    suspend fun rank(days: Int): List<ShelfBookItem>
    suspend fun search(query: String, page: Int, pageSize: Int): ShelfBookPage
    suspend fun getBookDetail(bookId: Long): ShelfBookDetail
    suspend fun getNovelContent(bookId: Long, sortNumber: Int): ShelfNovelContent
    /** Official web client sends an optional conversion mode (null, t2s, or s2t). */
    suspend fun getNovelContent(bookId: Long, sortNumber: Int, convert: String?): ShelfNovelContent =
        getNovelContent(bookId, sortNumber)
    suspend fun saveReadPosition(bookId: Long, chapterId: Long, xpath: String) = Unit
    suspend fun getShelf(): ShelfRemoteSnapshot
    suspend fun saveShelf(snapshot: ShelfRemoteSnapshot)
    suspend fun getBooksByIds(ids: List<Long>): List<ShelfBookItem>
    suspend fun getReadHistory(): List<Long> = emptyList()
    suspend fun getProfile(): ShelfProfile
    suspend fun checkIn(): ShelfCheckInResult
    suspend fun login(email: String, password: String): Boolean
    suspend fun restoreSession(): Boolean
    suspend fun logout()
}

internal class DefaultLightNovelShelfGateway(
    private val auth: LightNovelShelfAuthManager,
    private val hub: ShelfHubConnection,
    private val limiter: ShelfRateLimiter,
    private val decoder: ShelfResponseDecoder = ShelfResponseDecoder(),
) : LightNovelShelfGateway {
    override suspend fun listBooks(order: ShelfBookOrder, page: Int, pageSize: Int): ShelfBookPage {
        val response = invoke(
            "GetBookList",
            buildJsonObject {
                put("Page", page.coerceAtLeast(1))
                put("Size", pageSize.coerceIn(1, 50))
                put("Order", order.wire)
                put("IgnoreJapanese", true)
                put("IgnoreAI", true)
            },
        ).requiredObject("书籍列表").unwrapContainer("Data", "data", "Books", "books")
        return response.toBookPage(page)
    }

    override suspend fun rank(days: Int): List<ShelfBookItem> {
        val response = invoke(
            "GetRank",
            buildJsonObject { put("Days", days.coerceAtLeast(1)) },
        )
        val items = when (response) {
            is JsonArray -> response
            is JsonObject -> response.arrayAny("Data", "data", "Books", "books")
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { item -> (item as? JsonObject)?.toBookItem() }
    }

    override suspend fun search(query: String, page: Int, pageSize: Int): ShelfBookPage {
        val response = invoke(
            "GetBookList",
            buildJsonObject {
                put("KeyWords", query.trim())
                put("Page", page.coerceAtLeast(1))
                put("Size", pageSize.coerceIn(1, 50))
                put("IgnoreJapanese", false)
                put("IgnoreAI", false)
            },
        ).requiredObject("书籍列表").unwrapContainer("Data", "data", "Books", "books")
        return response.toBookPage(page)
    }

    override suspend fun getBookDetail(bookId: Long): ShelfBookDetail {
        val response = invoke(
            "GetBookInfo",
            buildJsonObject { put("Id", bookId) },
        ).requiredObject("书籍详情")
        val book = response.objectValue("Book", "book", "BookInfo", "bookInfo")
            ?: response.objectValue("Data", "data")?.objectValue("Book", "book", "BookInfo", "bookInfo")
            ?: response
        val extra = book.objectValue("Extra", "extra")
        val classification = extra?.get("classification") as? JsonObject
        // Production responses use Chapters; older responses use Chapter. A null
        // or malformed plural field must not hide a valid legacy catalog.
        val chapterArray = listOf(book, response, response.objectValue("Data", "data"))
            .filterNotNull()
            .asSequence()
            .flatMap { container -> sequenceOf("Chapters", "chapters", "Chapter", "chapter", "ChapterList", "chapterList").mapNotNull { container[it] } }
            .mapNotNull { value ->
                when (value) {
                    is JsonArray -> value
                    is JsonObject -> JsonArray(listOf(value))
                    else -> null
                }
            }
            // Some deployments return an empty `Chapters` placeholder and put
            // the real catalog in the legacy `Chapter` field. Prefer a
            // populated array, while still accepting a genuinely empty catalog.
            .toList()
            .let { arrays -> arrays.firstOrNull { it.isNotEmpty() } ?: arrays.firstOrNull() }
            ?: throw SourceException(SourceErrorKind.PARSING, "轻书架书籍详情缺少章节目录")
        val chapters = chapterArray
        val explicitSortNumbers = chapters.mapNotNull { item ->
            (item as? JsonObject)?.int("SortNum", fallback = 0)?.takeIf { it > 0 }
        }.toMutableSet()
        val explicitCount = chapters.mapNotNull { item ->
            (item as? JsonObject)?.int("SortNum", fallback = 0)?.takeIf { it > 0 }
        }.size
        if (explicitSortNumbers.size != explicitCount) {
            throw SourceException(SourceErrorKind.PARSING, "轻书架章节目录包含重复排序号")
        }
        val usedSortNumbers = mutableSetOf<Int>()
        val parsedChapters = chapters.mapIndexedNotNull { index, item ->
            val chapter = item as? JsonObject ?: return@mapIndexedNotNull null
            val explicit = chapter.intAny("SortNum", "sortNum", "SortNumber", "sortNumber", fallback = 0).takeIf { it > 0 }
            var sortNumber = explicit ?: (index + 1)
            while (sortNumber in usedSortNumbers || (explicit == null && sortNumber in explicitSortNumbers)) sortNumber++
            while (!usedSortNumbers.add(sortNumber)) sortNumber++
            ShelfBookChapter(
                id = chapter.longAny("Id", "id"),
                title = chapter.stringAny("Title", "title"),
                sortNumber = sortNumber,
            )
        }.sortedBy { it.sortNumber ?: Int.MAX_VALUE }
        return ShelfBookDetail(
            id = book.longAny("Id", "id"),
            title = book.stringAny("Title", "title"),
            coverUrl = normalizeShelfCoverUrl(book.optionalString("Cover", "cover", "CoverUrl", "coverUrl")),
            authorName = book.optionalString("Author", "author")
                ?: classification?.optionalString("author"),
            introduction = cleanShelfHtml(
                book.optionalText(
                    "Introduction", "introduction", "Synopsis", "synopsis",
                    "Description", "description", "Summary", "summary",
                    "Brief", "brief", "Intro", "intro",
                ).orEmpty(),
            ),
            tags = (classification?.stringListAny("tags", "Tags")
                ?: book.stringListAny("Tags", "tags")).orEmpty(),
            favoriteCount = book.intAny("Favorite", "favorite", "FavoriteCount", "favoriteCount", fallback = 0).coerceAtLeast(0),
            chapters = parsedChapters,
        )
    }

    override suspend fun getNovelContent(bookId: Long, sortNumber: Int): ShelfNovelContent {
        return getNovelContent(bookId, sortNumber, null)
    }

    override suspend fun getNovelContent(bookId: Long, sortNumber: Int, convert: String?): ShelfNovelContent {
        val response = invoke(
            "GetNovelContent",
            buildJsonObject {
                put("Bid", bookId)
                put("SortNum", sortNumber)
                convert?.trim()?.takeIf { it.isNotEmpty() }?.let { put("Convert", it) }
            },
        ).requiredObject("小说正文")
        val chapter = response.objectValue("Chapter", "chapter")
            ?: response.objectValue("Data", "data")?.objectValue("Chapter", "chapter")
            ?: response.objectValue("Result", "result")?.objectValue("Chapter", "chapter")
            ?: response.takeIf { it.value("Content", "content", "Html", "html", "Text", "text") != null }
            ?: throw SourceException(SourceErrorKind.PARSING, "轻书架正文响应缺少章节")
        val content = chapter.value("Content", "content", "Html", "html", "Text", "text")
        return ShelfNovelContent(
            id = chapter.longAny("Id", "id"),
            bookId = chapter.longAny("BookId", "bookId", fallback = bookId),
            title = chapter.stringAny("Title", "title"),
            html = content.asTextContent(),
            fontUrl = chapter.optionalString("Font", "font", "FontUrl", "fontUrl"),
            sortNumber = chapter.intAny("SortNum", "sortNum", "SortNumber", "sortNumber", fallback = sortNumber),
            chapterTitles = chapter.stringListAny("Chapters", "chapters", "ChapterTitles", "chapterTitles"),
        )
    }

    override suspend fun saveReadPosition(bookId: Long, chapterId: Long, xpath: String) {
        invoke(
            "SaveReadPosition",
            buildJsonObject {
                put("Bid", bookId)
                put("Cid", chapterId)
                put("XPath", xpath)
            },
        )
    }

    override suspend fun getShelf(): ShelfRemoteSnapshot =
        invoke("GetBookShelf", JsonNull).toRemoteShelf()

    override suspend fun saveShelf(snapshot: ShelfRemoteSnapshot) {
        invoke(
            "SaveBookShelf",
            buildJsonObject {
                put("data", buildJsonArray {
                    snapshot.items.forEach { add(it.toJson()) }
                })
                put("ver", snapshot.version ?: SHELF_STRUCTURE_VERSION)
            },
        )
    }

    override suspend fun getBooksByIds(ids: List<Long>): List<ShelfBookItem> {
        val uniqueIds = ids.distinct()
        require(uniqueIds.size <= SHELF_BOOK_BATCH_SIZE) {
            "light novel shelf batch size exceeds $SHELF_BOOK_BATCH_SIZE"
        }
        if (uniqueIds.isEmpty()) return emptyList()
        val response = invoke(
            "GetBookListByIds",
            buildJsonObject {
                put("Ids", buildJsonArray { uniqueIds.forEach { add(JsonPrimitive(it)) } })
            },
        )
        val items = when (response) {
            is JsonArray -> response
            is JsonObject -> response.arrayAny("Data", "data", "Books", "books")
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { item -> (item as? JsonObject)?.toBookItem() }
    }

    override suspend fun getReadHistory(): List<Long> {
        val response = invoke("GetReadHistory", JsonNull).requiredObject("阅读历史")
        return response.array("Novel").map { item ->
            val id = item.jsonPrimitive.contentOrNull?.toLongOrNull()
                ?: throw SourceException(SourceErrorKind.PARSING, "轻书架阅读历史包含无效书籍 ID")
            if (id <= 0) {
                throw SourceException(SourceErrorKind.PARSING, "轻书架阅读历史包含无效书籍 ID")
            }
            id
        }.distinct()
    }

    override suspend fun getProfile(): ShelfProfile {
        val response = invoke("GetMyInfo", buildJsonObject {}).requiredObject("用户资料")
        val growth = response["Growth"] as? JsonObject ?: JsonObject(emptyMap())
        return ShelfProfile(
            id = response.long("Id"),
            userName = response.optionalString("UserName").orEmpty(),
            coin = growth.long("Coin", fallback = 0),
            signInStreak = growth.int("SignStreak", fallback = 0),
            signedToday = growth.boolean("TodaySigned", fallback = false),
        )
    }

    override suspend fun checkIn(): ShelfCheckInResult {
        val response = invoke("SignIn", buildJsonObject {}).requiredObject("签到结果")
        return ShelfCheckInResult(
            reward = response.long("Reward", fallback = 0),
            streak = response.int("Streak", fallback = 0),
        )
    }

    override suspend fun login(email: String, password: String): Boolean {
        val loggedIn = auth.login(email, password)
        hub.reset()
        return loggedIn
    }

    override suspend fun restoreSession(): Boolean {
        val restored = auth.restore()
        hub.reset()
        return restored
    }

    override suspend fun logout() {
        auth.logout()
        hub.reset()
    }

    private suspend fun invoke(target: String, params: JsonElement): JsonElement {
        var retried = false
        while (true) {
            try {
                val envelope = limiter.run { hub.invoke(target, params) }
                return decoder.unwrap(envelope)
            } catch (error: SourceException) {
                if (error.kind != SourceErrorKind.AUTHENTICATION || retried || !auth.refresh()) throw error
                retried = true
                hub.reset()
            }
        }
    }
}

internal class ShelfResponseDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun unwrap(value: JsonElement): JsonElement {
        val envelope = value as? JsonObject
            ?: throw SourceException(SourceErrorKind.PARSING, "轻书架返回了无效的响应")
        val success = envelope.value("Success", "success")
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少成功状态")
        if (!success) {
            val status = envelope.value("Status", "status")?.jsonPrimitive?.intOrNull
            val kind = if (status == 401 || status == -100 || status == 1001) {
                SourceErrorKind.AUTHENTICATION
            } else {
                SourceErrorKind.SERVER
            }
            throw SourceException(
                kind,
                envelope.optionalString("Msg", "msg", "Message", "message", "Error", "error")
                    ?: "轻书架请求失败",
            )
        }
        return decompress(envelope.value("Response", "response") ?: JsonPrimitive(""))
    }

    private fun decompress(value: JsonElement): JsonElement {
        val encoded = (value as? JsonPrimitive)?.contentOrNull ?: return value
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return value
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return value
        val inflated = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw SourceException(SourceErrorKind.PARSING, "轻书架返回了无效的压缩响应", error)
        }
        return runCatching { json.parseToJsonElement(inflated) }.getOrElse { error ->
            throw SourceException(SourceErrorKind.PARSING, "轻书架压缩响应不是有效 JSON", error)
        }
    }
}

private fun JsonElement.requiredObject(name: String): JsonObject = this as? JsonObject
    ?: throw SourceException(SourceErrorKind.PARSING, "轻书架返回了无效的$name")

private fun JsonObject.toBookItem(): ShelfBookItem = ShelfBookItem(
    id = longAny("Id", "id", "BookId", "bookId"),
    title = stringAny("Title", "title", "Name", "name"),
    coverUrl = normalizeShelfCoverUrl(optionalString("Cover", "cover", "CoverUrl", "coverUrl")),
    authorName = optionalString("UserName", "userName", "Author", "author"),
    introduction = cleanShelfHtml(
        optionalText(
            "Introduction", "introduction",
            "Synopsis", "synopsis",
            "Description", "description",
            "Summary", "summary",
            "Brief", "brief", "Intro", "intro",
        ).orEmpty(),
    ),
)

private fun JsonObject.toBookPage(requestedPage: Int): ShelfBookPage = ShelfBookPage(
    page = intAny("Page", "page", fallback = requestedPage.coerceAtLeast(1)),
    totalPages = intAny("TotalPages", "totalPages", "PageCount", "pageCount", fallback = 1).coerceAtLeast(1),
    items = arrayAny("Data", "data", "Books", "books", "Items", "items").mapNotNull { item -> (item as? JsonObject)?.toBookItem() },
)

private fun JsonElement.toRemoteShelf(): ShelfRemoteSnapshot {
    val container = this as? JsonObject
    val rawItems = when (this) {
        is JsonArray -> this
        is JsonObject -> (value("data", "Data") as? JsonArray) ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    val version = container?.value("ver", "Ver")?.jsonPrimitive?.contentOrNull
    return ShelfRemoteSnapshot(
        version = version,
        items = rawItems.map { element ->
            (element as? JsonObject)?.toRemoteShelfItem()
                ?: throw SourceException(SourceErrorKind.PARSING, "轻书架返回了无效的书架条目")
        },
    )
}

private fun JsonObject.toRemoteShelfItem(): ShelfRemoteItem {
    val type = when (value("type", "Type")?.jsonPrimitive?.contentOrNull?.uppercase()) {
        "BOOK", "0" -> ShelfRemoteItemType.BOOK
        "FOLDER", "1" -> ShelfRemoteItemType.FOLDER
        else -> throw SourceException(SourceErrorKind.PARSING, "轻书架返回了未知的书架条目类型")
    }
    val rawId = value("id", "Id")?.jsonPrimitive?.contentOrNull
        ?: throw SourceException(SourceErrorKind.PARSING, "轻书架书架条目缺少 ID")
    if (type == ShelfRemoteItemType.BOOK && rawId.toLongOrNull() == null) {
        throw SourceException(SourceErrorKind.PARSING, "轻书架书架条目包含无效书籍 ID")
    }
    val parentValues = value("parents", "Parents") as? JsonArray ?: JsonArray(emptyList())
    return ShelfRemoteItem(
        type = type,
        id = rawId,
        index = value("index", "Index")?.jsonPrimitive?.intOrNull ?: 0,
        parents = parentValues.mapNotNull { it.jsonPrimitive.contentOrNull },
        updatedAt = value("updateAt", "UpdateAt")?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = value("title", "Title")?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun ShelfRemoteItem.toJson(): JsonObject = buildJsonObject {
    if (type == ShelfRemoteItemType.BOOK) {
        put("id", requireNotNull(bookId))
    } else {
        put("id", id)
    }
    put("index", index)
    put("parents", buildJsonArray { parents.forEach { add(JsonPrimitive(it)) } })
    if (type == ShelfRemoteItemType.FOLDER) put("title", title)
    put("type", type.name)
    put("updateAt", updatedAt)
}

internal const val SHELF_BOOK_BATCH_SIZE = 24
private const val SHELF_STRUCTURE_VERSION = "20220211"

internal fun normalizeShelfCoverUrl(value: String?): String? {
    val url = value?.takeIf(String::isNotBlank) ?: return null
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url

    var pairStart = queryStart + 1
    while (pairStart <= url.length) {
        val pairEndCandidate = url.indexOf('&', pairStart)
        val pairEnd = if (pairEndCandidate < 0) url.length else pairEndCandidate
        val separator = url.indexOf('=', pairStart)
        if (separator in pairStart until pairEnd &&
            url.substring(pairStart, separator) == "placeholder"
        ) {
            val valueStart = separator + 1
            val rawPlaceholder = url.substring(valueStart, pairEnd)
            if ('#' !in rawPlaceholder) return url
            return url.replaceRange(
                valueStart,
                pairEnd,
                rawPlaceholder.replace("#", "%23"),
            )
        }
        if (pairEndCandidate < 0) break
        pairStart = pairEnd + 1
    }
    return url
}

private fun JsonObject.string(key: String): String = optionalString(key)
    ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 $key 字段")

private fun JsonObject.int(key: String, fallback: Int? = null): Int {
    val element = get(key)?.jsonPrimitive
    return element?.intOrNull
        ?: element?.contentOrNull?.toIntOrNull()
        ?: fallback
        ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 $key 字段")
}

private fun JsonObject.stringAny(vararg keys: String): String = optionalString(*keys)
    ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 ${keys.firstOrNull().orEmpty()} 字段")

private fun JsonObject.intAny(vararg keys: String, fallback: Int? = null): Int {
    return keys.asSequence().mapNotNull { key ->
        get(key)?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    }.firstOrNull() ?: fallback
    ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 ${keys.firstOrNull().orEmpty()} 字段")
}

private fun JsonObject.longAny(vararg keys: String, fallback: Long? = null): Long {
    return keys.asSequence().mapNotNull { key -> get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() }
        .firstOrNull() ?: fallback
        ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 ${keys.firstOrNull().orEmpty()} 字段")
}

private fun JsonObject.long(key: String, fallback: Long? = null): Long {
    val element = get(key)?.jsonPrimitive
    return element?.contentOrNull?.toLongOrNull()
        ?: fallback
        ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 $key 字段")
}

private fun JsonObject.boolean(key: String, fallback: Boolean? = null): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull
        ?: fallback
        ?: throw SourceException(SourceErrorKind.PARSING, "轻书架响应缺少 $key 字段")

private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.arrayAny(vararg keys: String): JsonArray = keys.asSequence()
    .mapNotNull { get(it) as? JsonArray }
    .firstOrNull()
    ?: JsonArray(emptyList())

/** List and detail endpoints use both plain strings and small text wrapper objects. */
private fun JsonObject.optionalText(vararg keys: String): String? {
    val element = value(*keys) ?: return null
    return when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.optionalString(
            "Html", "html", "Content", "content", "Text", "text",
            "Value", "value", "Description", "description",
        )
        else -> null
    }?.takeIf(String::isNotBlank)
}

private fun JsonObject.unwrapContainer(vararg keys: String): JsonObject {
    val nested = keys.asSequence().mapNotNull { get(it) as? JsonObject }.firstOrNull()
    return nested ?: this
}

private fun JsonObject.stringList(key: String): List<String> = array(key).mapNotNull { item ->
    (item as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

private fun JsonObject.stringListAny(vararg keys: String): List<String> = keys.asSequence()
    .mapNotNull { key -> get(key) as? JsonArray }
    .firstOrNull()
    ?.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf(String::isNotBlank)
            is JsonObject -> item.optionalString("Name", "name", "Title", "title")
            else -> null
        }
    }
    .orEmpty()

private fun JsonElement?.asTextContent(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonObject -> optionalString("Html", "html", "Content", "content", "Text", "text").orEmpty()
    else -> ""
}

/** Details are rendered with plain Compose Text; remove markup returned by the web editor. */
private fun cleanShelfHtml(value: String): String = value
    .replace("\\r\\n", "\n")
    .replace("\\n", "\n")
    .replace("\\r", "")
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("(?i)</(p|div|h[1-6]|li)>"), "\n")
    .replace(Regex("(?i)<(p|div|h[1-6]|li)\\b[^>]*>"), "")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
