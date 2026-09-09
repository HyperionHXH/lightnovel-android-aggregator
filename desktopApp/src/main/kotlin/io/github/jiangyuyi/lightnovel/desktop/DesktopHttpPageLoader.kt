package io.github.jiangyuyi.lightnovel.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val KINGDOM_SOURCE_ID = "light_novel_kingdom"
private const val SHELF_SOURCE_ID = "light_novel_shelf"
private const val KINGDOM_API_ORIGIN = "https://www.lightnovel.fun/api/pc-proxy/"

/**
 * Compatibility loader for callers that only need the public Light Novel Kingdom catalog.
 * The Mixn window uses [DesktopSourcePageLoader] so both built-in sources share the same path.
 */
class DesktopHttpPageLoader(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val apiOrigin: String = KINGDOM_API_ORIGIN,
) : DesktopPageLoader {
    override fun load(sourceId: String, feed: String, query: String, page: Int, pageSize: Int): DesktopPage {
        require(page > 0) { "page must be positive" }
        require(pageSize > 0) { "page size must be positive" }
        return when {
            sourceId == KINGDOM_SOURCE_ID -> loadKingdom(feed, query, page, pageSize)
            sourceId == "aggregate" -> loadKingdom("", query, page, pageSize)
            sourceId == SHELF_SOURCE_ID -> throw IllegalStateException("请通过 DesktopSourcePageLoader 使用轻书架适配器")
            else -> throw IllegalArgumentException("未知桌面来源：$sourceId")
        }
    }

    private fun loadKingdom(feed: String, query: String, page: Int, pageSize: Int): DesktopPage {
        val request = if (query.isNotBlank()) {
            RequestSpec(
                path = "api/bff/apk-search-result-v1",
                body = buildJsonObject {
                    put("q", query.trim())
                    put("scope", "")
                    put("source", "")
                    put("primary_tag", "")
                    put("channel_code", "")
                    put("work_type", "")
                    put("preset", "")
                    put("source_type", "")
                    put("page", (page - 1).coerceAtLeast(0))
                    put("pageSize", pageSize)
                    put("sort", "relevance")
                },
            )
        } else {
            discoveryRequest(feed, page, pageSize)
        }
        return parsePage(post(request), page, pageSize)
    }

    private fun discoveryRequest(feed: String, page: Int, pageSize: Int): RequestSpec {
        val (path, body) = when (feed) {
            "新书" -> "api/bff/book-rank-list-v1" to buildJsonObject {
                put("rank_scene", "daily_fresh")
                put("page", page)
                put("page_size", pageSize)
                put("pageSize", pageSize)
            }

            "周榜" -> "api/bff/book-rank-list-v1" to buildJsonObject {
                put("rank_scene", "weekly_hot")
                put("page", page)
                put("page_size", pageSize)
                put("pageSize", pageSize)
            }

            "最近更新" -> "api/bff/home-recent-updates-feed-v1" to feedBody(page, pageSize)
            else -> "api/bff/home-feed-v1" to feedBody(page, pageSize)
        }
        return RequestSpec(path, body)
    }

    private fun feedBody(page: Int, pageSize: Int) = buildJsonObject {
        put("page", page)
        put("page_size", pageSize)
        put("pageSize", pageSize)
        put("read_filter", "all")
        put("status_filter", "all")
        put("category_filter", "all")
    }

    private fun post(request: RequestSpec): JsonObject {
        val httpRequest = HttpRequest.newBuilder(URI.create(apiOrigin.trimEnd('/') + "/" + request.path))
            .timeout(Duration.ofSeconds(25))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mixn-Windows/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(request.body.toString()))
            .build()
        val response = try {
            client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("轻之国度网络请求失败：${error.message ?: "连接失败"}", error)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("轻之国度返回 HTTP ${response.statusCode()}")
        }
        val root = runCatching { json.parseToJsonElement(response.body()).jsonObject }
            .getOrElse { error -> throw IllegalStateException("轻之国度返回了无法识别的数据", error) }
        val code = root["code"]?.jsonPrimitive?.intOrNull
        if (code != null && code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["msg"]?.jsonPrimitive?.contentOrNull
                ?: "请求失败（$code）"
            throw IllegalStateException("轻之国度：$message")
        }
        return (root["data"] as? JsonObject)
            ?: (root["d"] as? JsonObject)
            ?: root
    }

    private fun parsePage(response: JsonObject, requestedPage: Int, pageSize: Int): DesktopPage {
        val list = sequenceOf("list", "cards", "ranking_list", "items", "books")
            .mapNotNull { response[it] as? JsonArray }
            .firstOrNull { it.isNotEmpty() }
            ?: (response["data"] as? JsonArray)
            ?: JsonArray(emptyList())
        val items = list.mapNotNull { (it as? JsonObject)?.toBook() }
        val pagination = (response["pagination"] as? JsonObject)
            ?: (response["page_info"] as? JsonObject)
        val actualPage = pagination?.number("page", "cur", "current_page") ?: requestedPage
        val total = pagination?.number("total", "count") ?: response.number("total") ?: items.size
        val actualSize = pagination?.number("page_size", "size")?.takeIf { it > 0 } ?: pageSize
        val hasMore = pagination?.boolean("has_next", "has_more", "hasMore")
            ?: ((pagination?.number("next") ?: 0) > 0 || actualPage * actualSize < total)
            || (pagination == null && items.size >= pageSize)
        return DesktopPage(items, actualPage, total, hasMore)
    }

    private fun JsonObject.toBook(): DesktopBook? {
        val id = longNumber("id", "book_id", "bookId")?.takeIf { it > 0 } ?: return null
        return DesktopBook(
            sourceId = KINGDOM_SOURCE_ID,
            remoteId = id.toString(),
            title = text("title", "book_title", "name").ifBlank { "未命名作品" },
            author = text("author", "author_name", "writer", "nickname"),
            coverUrl = text("cover_url", "coverUrl", "cover", "image").takeIf(String::isNotBlank),
            summary = text("summary", "intro", "description"),
        )
    }

    private fun JsonObject.text(vararg keys: String): String = keys.asSequence()
        .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull(String::isNotBlank)
        .orEmpty()

    private fun JsonObject.number(vararg keys: String): Int? = keys.asSequence()
        .mapNotNull { key ->
            val value = this[key]?.jsonPrimitive ?: return@mapNotNull null
            value.intOrNull ?: value.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        }
        .firstOrNull()

    private fun JsonObject.longNumber(vararg keys: String): Long? = keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.longOrNull }
        .firstOrNull()

    private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }
        .firstOrNull()

    private data class RequestSpec(val path: String, val body: JsonObject)
}
