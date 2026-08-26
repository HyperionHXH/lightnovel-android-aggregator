package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import android.content.Context
import io.github.jiangyuyi.lightnovel.core.source.AccountProvider
import io.github.jiangyuyi.lightnovel.core.source.BuiltInSourceIds
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.DetailProvider
import io.github.jiangyuyi.lightnovel.core.source.DiscoverFeed
import io.github.jiangyuyi.lightnovel.core.source.DiscoverProvider
import io.github.jiangyuyi.lightnovel.core.source.HistoryProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelDetail
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.PasswordCredentials
import io.github.jiangyuyi.lightnovel.core.source.ReaderProvider
import io.github.jiangyuyi.lightnovel.core.source.ReadingHistoryEntry
import io.github.jiangyuyi.lightnovel.core.source.RewardProvider
import io.github.jiangyuyi.lightnovel.core.source.RewardResult
import io.github.jiangyuyi.lightnovel.core.source.RewardStatus
import io.github.jiangyuyi.lightnovel.core.source.SearchProvider
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.AccountIdentifierKind
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceProfile
import io.github.jiangyuyi.lightnovel.core.source.SourceProfileProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

private const val SOURCE_ID = BuiltInSourceIds.LIGHT_NOVEL_SHELF
private const val DEFAULT_VOLUME_ID = "default"

class LightNovelShelfSource internal constructor(
    private val gateway: LightNovelShelfGateway,
) : NovelSource,
    DiscoverProvider,
    SearchProvider,
    DetailProvider,
    ReaderProvider,
    AccountProvider,
    RewardProvider,
    ShelfProvider,
    HistoryProvider,
    SourceProfileProvider {

    private val rewardMutex = Mutex()
    private val shelfMutex = Mutex()

    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "轻书架",
        capabilities = setOf(
            SourceCapability.DISCOVER,
            SourceCapability.SEARCH,
            SourceCapability.DETAIL,
            SourceCapability.READER,
            SourceCapability.ACCOUNT,
            SourceCapability.REMOTE_SHELF,
            SourceCapability.DAILY_REWARD,
            SourceCapability.HISTORY,
        ),
        accountIdentifierKind = AccountIdentifierKind.EMAIL,
    )

    override val discoverFeeds = listOf(
        DiscoverFeed.POPULAR,
        DiscoverFeed.LATEST,
        DiscoverFeed.NEWEST,
        DiscoverFeed.DAILY_RANK,
        DiscoverFeed.WEEKLY_RANK,
        DiscoverFeed.MONTHLY_RANK,
    )

    override suspend fun discover(feed: DiscoverFeed, page: Int, pageSize: Int): SourcePage<NovelSummary> {
        require(feed in discoverFeeds) { "unsupported light novel shelf feed: $feed" }
        val rankedDays = when (feed) {
            DiscoverFeed.DAILY_RANK -> 1
            DiscoverFeed.WEEKLY_RANK -> 7
            DiscoverFeed.MONTHLY_RANK -> 31
            else -> null
        }
        if (rankedDays != null) {
            val items = if (page == 1) gateway.rank(rankedDays).take(pageSize) else emptyList()
            return SourcePage(items.map(ShelfBookItem::toSource), page, total = items.size, hasMore = false)
        }
        val order = when (feed) {
            DiscoverFeed.POPULAR -> ShelfBookOrder.VIEWED
            DiscoverFeed.LATEST -> ShelfBookOrder.LATEST
            DiscoverFeed.NEWEST -> ShelfBookOrder.NEWEST
            else -> error("unsupported light novel shelf feed: $feed")
        }
        val result = gateway.listBooks(order, page, pageSize)
        return result.toSourcePage(pageSize)
    }

    override suspend fun search(query: String, page: Int, pageSize: Int): SourcePage<NovelSummary> {
        val result = gateway.search(query, page, pageSize)
        return result.toSourcePage(pageSize)
    }

    override suspend fun getNovelDetail(key: NovelKey): NovelDetail {
        val detail = gateway.getBookDetail(key.requireShelfBookId())
        return NovelDetail(
            novel = detail.toSummary(),
            favoriteCount = detail.favoriteCount,
        )
    }

    override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int): SourcePage<VolumeSummary> {
        val detail = gateway.getBookDetail(key.requireShelfBookId())
        val volumes = if (page == 1) {
            listOf(
                VolumeSummary(
                    key = VolumeKey(SOURCE_ID, DEFAULT_VOLUME_ID),
                    novelKey = NovelKey(SOURCE_ID, detail.id.toString()),
                    title = "正文",
                    chapterCount = detail.chapters.size,
                ),
            )
        } else {
            emptyList()
        }
        return SourcePage(volumes, page, total = 1, hasMore = false)
    }

    override suspend fun getChapters(
        novelKey: NovelKey,
        volumeKey: VolumeKey,
        page: Int,
        pageSize: Int,
    ): SourcePage<ChapterSummary> {
        val bookId = novelKey.requireShelfBookId()
        volumeKey.requireDefaultVolume()
        val detail = gateway.getBookDetail(bookId)
        val effectivePage = page.coerceAtLeast(1)
        val effectiveSize = pageSize.coerceIn(1, 50)
        val fromIndex = ((effectivePage - 1) * effectiveSize).coerceAtMost(detail.chapters.size)
        val toIndex = (fromIndex + effectiveSize).coerceAtMost(detail.chapters.size)
        val items = detail.chapters.subList(fromIndex, toIndex).mapIndexed { index, chapter ->
            val sortNumber = fromIndex + index + 1
            ChapterSummary(
                key = ChapterKey(SOURCE_ID, sortNumber.toString()),
                novelKey = NovelKey(SOURCE_ID, bookId.toString()),
                volumeKey = VolumeKey(SOURCE_ID, DEFAULT_VOLUME_ID),
                title = chapter.title,
                order = sortNumber,
            )
        }
        return SourcePage(
            items = items,
            page = effectivePage,
            total = detail.chapters.size,
            hasMore = toIndex < detail.chapters.size,
        )
    }

    override suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent {
        val bookId = novelKey.requireShelfBookId()
        val sortNumber = chapterKey.requireSortNumber()
        val content = gateway.getNovelContent(bookId, sortNumber)
        val totalChapters = content.chapterTitles.size
        val summary = ChapterSummary(
            key = ChapterKey(SOURCE_ID, content.sortNumber.toString()),
            novelKey = NovelKey(SOURCE_ID, content.bookId.toString()),
            volumeKey = VolumeKey(SOURCE_ID, DEFAULT_VOLUME_ID),
            title = content.title,
            order = content.sortNumber,
        )
        return ChapterContent(
            chapter = summary,
            novelTitle = "",
            volumeTitle = "正文",
            bodyText = "",
            bodyHtml = content.html,
            fontUrl = content.fontUrl,
            previousChapterKey = (content.sortNumber - 1)
                .takeIf { it >= 1 }
                ?.let { ChapterKey(SOURCE_ID, it.toString()) },
            nextChapterKey = (content.sortNumber + 1)
                .takeIf { totalChapters == 0 || it <= totalChapters }
                ?.let { ChapterKey(SOURCE_ID, it.toString()) },
        )
    }

    override suspend fun restoreSession(): SourceSession = SourceSession(
        loggedIn = gateway.restoreSession(),
    )

    override suspend fun login(credentials: PasswordCredentials): SourceSession = SourceSession(
        loggedIn = gateway.login(credentials.identifier, credentials.password),
    )

    override suspend fun logout() = gateway.logout()

    override suspend fun getProfile(): SourceProfile = gateway.getProfile().toSourceProfile()

    override suspend fun getRemoteShelf(): List<NovelSummary> {
        val shelf = gateway.getShelf()
        val orderedIds = shelf.items.mapNotNull(ShelfRemoteItem::bookId).distinct()
        val books = orderedIds.chunked(SHELF_BOOK_BATCH_SIZE).flatMap { gateway.getBooksByIds(it) }
        val booksById = books.associateBy(ShelfBookItem::id)
        return orderedIds.mapNotNull(booksById::get).map { it.toSource(inRemoteShelf = true) }
    }

    override suspend fun isInRemoteShelf(key: NovelKey): Boolean {
        val bookId = key.requireShelfBookId()
        return gateway.getShelf().items.any { it.bookId == bookId }
    }

    override suspend fun setInRemoteShelf(key: NovelKey, add: Boolean): Boolean = shelfMutex.withLock {
        val bookId = key.requireShelfBookId()
        val shelf = gateway.getShelf()
        val currentlyAdded = shelf.items.any { it.bookId == bookId }
        if (currentlyAdded == add) return@withLock currentlyAdded
        val nextItems = if (add) {
            listOf(
                ShelfRemoteItem(
                    type = ShelfRemoteItemType.BOOK,
                    id = bookId.toString(),
                    index = -1,
                    parents = emptyList(),
                    updatedAt = Instant.now().toString(),
                ),
            ) + shelf.items
        } else {
            shelf.items.filterNot { it.bookId == bookId }
        }
        gateway.saveShelf(shelf.copy(items = normalizeShelfItems(nextItems)))
        add
    }

    override suspend fun getReadingHistory(page: Int, pageSize: Int): SourcePage<ReadingHistoryEntry> {
        val ids = gateway.getReadHistory()
        val effectivePage = page.coerceAtLeast(1)
        val effectiveSize = pageSize.coerceIn(1, SHELF_BOOK_BATCH_SIZE)
        val fromIndex = ((effectivePage - 1) * effectiveSize).coerceAtMost(ids.size)
        val toIndex = (fromIndex + effectiveSize).coerceAtMost(ids.size)
        val pageIds = ids.subList(fromIndex, toIndex)
        val booksById = gateway.getBooksByIds(pageIds).associateBy(ShelfBookItem::id)
        return SourcePage(
            items = pageIds.mapNotNull(booksById::get).map { book ->
                ReadingHistoryEntry(novel = book.toSource())
            },
            page = effectivePage,
            total = ids.size,
            hasMore = toIndex < ids.size,
        )
    }

    override suspend fun getRewardStatus(): RewardStatus {
        val profile = gateway.getProfile()
        return RewardStatus(
            claimedToday = profile.signedToday,
            balance = profile.coin,
            streakDays = profile.signInStreak,
        )
    }

    override suspend fun claimDailyReward(): RewardResult = rewardMutex.withLock {
        val before = gateway.getProfile()
        if (before.signedToday) {
            return@withLock RewardResult(
                rewardAmount = 0,
                balance = before.coin,
                streakDays = before.signInStreak,
            )
        }
        val result = gateway.checkIn()
        RewardResult(
            rewardAmount = result.reward,
            balance = before.coin + result.reward,
            streakDays = result.streak,
        )
    }

    companion object {
        fun create(context: Context): LightNovelShelfSource {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
            val tokenStore = AndroidShelfTokenStore(context.applicationContext)
            val limiter = ShelfRateLimiter()
            val auth = LightNovelShelfAuthManager(
                api = LightNovelShelfAuthApi(
                    transport = OkHttpShelfHttpTransport(client),
                    limiter = limiter,
                ),
                tokenStore = tokenStore,
            )
            val hub = OkHttpShelfSignalRConnection(
                client = client,
                accessToken = auth::accessToken,
            )
            return LightNovelShelfSource(
                DefaultLightNovelShelfGateway(
                    auth = auth,
                    hub = hub,
                    limiter = limiter,
                ),
            )
        }
    }
}

private fun ShelfProfile.toSourceProfile() = SourceProfile(
    sourceId = SOURCE_ID,
    accountId = id.takeIf { it > 0 }?.toString(),
    displayName = userName,
    balance = coin,
    extra = mapOf(
        "连续签到" to "${signInStreak} 天",
        "今日签到" to if (signedToday) "已完成" else "未完成",
    ),
)

private fun ShelfBookPage.toSourcePage(pageSize: Int): SourcePage<NovelSummary> {
    val effectiveSize = pageSize.coerceIn(1, 50)
    val estimatedTotal = if (page >= totalPages) {
        (page - 1) * effectiveSize + items.size
    } else {
        totalPages * effectiveSize
    }
    return SourcePage(
        items = items.map(ShelfBookItem::toSource),
        page = page,
        total = estimatedTotal,
        hasMore = page < totalPages,
    )
}

private fun ShelfBookItem.toSource(inRemoteShelf: Boolean? = null) = NovelSummary(
    key = NovelKey(SOURCE_ID, id.toString()),
    title = title,
    authors = authorName?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
    coverUrl = coverUrl,
    inRemoteShelf = inRemoteShelf,
)

private fun ShelfBookDetail.toSummary() = NovelSummary(
    key = NovelKey(SOURCE_ID, id.toString()),
    title = title,
    authors = authorName?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
    synopsis = introduction,
    coverUrl = coverUrl,
    tags = tags,
    volumeCount = 1,
    chapterCount = chapters.size,
)

private fun NovelKey.requireShelfBookId(): Long {
    require(sourceId == SOURCE_ID) { "resource belongs to $sourceId, not $SOURCE_ID" }
    return requireNotNull(remoteId.toLongOrNull()) { "invalid light novel shelf book id: $remoteId" }
}

private fun normalizeShelfItems(items: List<ShelfRemoteItem>): List<ShelfRemoteItem> {
    val nextIndexByParents = mutableMapOf<List<String>, Int>()
    return items.sortedWith(compareBy(ShelfRemoteItem::index, { it.parents.size })).map { item ->
        val parents = item.parents.toList()
        val index = nextIndexByParents[parents] ?: 0
        nextIndexByParents[parents] = index + 1
        item.copy(index = index)
    }
}

private fun VolumeKey.requireDefaultVolume() {
    require(sourceId == SOURCE_ID) { "resource belongs to $sourceId, not $SOURCE_ID" }
    require(remoteId == DEFAULT_VOLUME_ID) { "unknown light novel shelf volume: $remoteId" }
}

private fun ChapterKey.requireSortNumber(): Int {
    require(sourceId == SOURCE_ID) { "resource belongs to $sourceId, not $SOURCE_ID" }
    return requireNotNull(remoteId.toIntOrNull()?.takeIf { it > 0 }) {
        "invalid light novel shelf chapter number: $remoteId"
    }
}
