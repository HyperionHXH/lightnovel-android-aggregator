package io.github.jiangyuyi.lightnovel.desktop

data class DesktopPage(
    val items: List<DesktopBook>,
    val page: Int,
    val total: Int = items.size,
    val hasMore: Boolean = false,
)

fun interface DesktopPageLoader {
    fun load(sourceId: String, feed: String, query: String, page: Int, pageSize: Int): DesktopPage
}

/**
 * Keeps pagination behavior independent from Swing. A real source adapter can be plugged in without
 * changing the scroll listener or list rendering code.
 */
class DesktopPagingController(
    private val pageSize: Int = 20,
) {
    init {
        require(pageSize > 0) { "page size must be positive" }
    }

    private val mutableItems = linkedMapOf<String, DesktopBook>()
    var page: Int = 0
        private set
    var total: Int = 0
        private set
    var hasMore: Boolean = false
        private set
    var loading: Boolean = false
        private set

    val items: List<DesktopBook> get() = mutableItems.values.toList()

    fun reset() {
        mutableItems.clear()
        page = 0
        total = 0
        hasMore = false
        loading = false
    }

    fun loadInitial(loader: DesktopPageLoader, sourceId: String, feed: String, query: String): Boolean {
        if (loading) return false
        reset()
        return load(loader, sourceId, feed, query, 1)
    }

    fun loadMore(loader: DesktopPageLoader, sourceId: String, feed: String, query: String): Boolean {
        if (loading || !hasMore) return false
        return load(loader, sourceId, feed, query, page + 1)
    }

    private fun load(
        loader: DesktopPageLoader,
        sourceId: String,
        feed: String,
        query: String,
        requestedPage: Int,
    ): Boolean {
        loading = true
        return try {
            val result = loader.load(sourceId, feed, query, requestedPage, pageSize)
            require(result.page > 0) { "source returned an invalid page: ${result.page}" }
            require(result.items.size <= pageSize) {
                "source returned ${result.items.size} items for page size $pageSize"
            }
            result.items.forEach { item -> mutableItems[item.key] = item }
            page = result.page
            total = result.total
            // Empty pages are terminal even when a stale backend flag says otherwise.
            hasMore = result.hasMore && result.items.isNotEmpty() && result.page >= requestedPage
            true
        } finally {
            loading = false
        }
    }
}

/**
 * Edge-triggered gate used by scroll pagination. A caller may arm it again after
 * appending items; while the viewport stays near the edge it emits only once.
 */
class DesktopNearBottomGate(private val threshold: Int = 260) {
    init { require(threshold >= 0) { "threshold must be non-negative" } }

    private var armed = true

    fun arm() { armed = true }

    fun update(distanceToBottom: Int): Boolean {
        if (distanceToBottom >= threshold) {
            armed = true
            return false
        }
        if (!armed) return false
        armed = false
        return true
    }
}

/**
 * Defensive chapter-page aggregation. Some source endpoints have returned an
 * empty page or repeated page number while still reporting hasMore=true.
 */
fun loadAllDesktopChapterPages(
    load: (page: Int) -> DesktopChapterPage,
    firstPage: Int = 1,
    maxPages: Int = 200,
): List<DesktopChapter> {
    require(firstPage > 0) { "firstPage must be positive" }
    require(maxPages > 0) { "maxPages must be positive" }
    val result = mutableListOf<DesktopChapter>()
    val visitedPages = mutableSetOf<Int>()
    var requestedPage = firstPage
    repeat(maxPages) {
        if (!visitedPages.add(requestedPage)) return result
        val page = load(requestedPage)
        require(page.page > 0) { "source returned an invalid chapter page: ${page.page}" }
        if (page.page < requestedPage) return result
        result += page.items
        if (!page.hasMore || page.items.isEmpty()) return result
        val nextPage = page.page + 1
        if (nextPage <= requestedPage) return result
        requestedPage = nextPage
    }
    return result
}

/** The shell is intentionally honest while the shared source extraction is in progress. */
class UnconfiguredDesktopPageLoader : DesktopPageLoader {
    override fun load(sourceId: String, feed: String, query: String, page: Int, pageSize: Int): DesktopPage {
        throw IllegalStateException("桌面端来源适配器尚未配置：$sourceId")
    }
}
