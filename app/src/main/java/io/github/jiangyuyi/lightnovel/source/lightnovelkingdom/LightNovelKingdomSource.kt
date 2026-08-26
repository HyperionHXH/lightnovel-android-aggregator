package io.github.jiangyuyi.lightnovel.source.lightnovelkingdom

import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.model.BookDetail
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import io.github.jiangyuyi.lightnovel.core.model.AccountProfile
import io.github.jiangyuyi.lightnovel.core.model.ChapterDetail
import io.github.jiangyuyi.lightnovel.core.model.DiscoverChannel
import io.github.jiangyuyi.lightnovel.core.model.Page
import io.github.jiangyuyi.lightnovel.core.model.ReadingHistoryItem
import io.github.jiangyuyi.lightnovel.core.model.Session
import io.github.jiangyuyi.lightnovel.core.model.Volume
import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary as KingdomChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.AccountProvider
import io.github.jiangyuyi.lightnovel.core.source.BuiltInSourceIds
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.DetailProvider
import io.github.jiangyuyi.lightnovel.core.source.DiscoverFeed
import io.github.jiangyuyi.lightnovel.core.source.DiscoverProvider
import io.github.jiangyuyi.lightnovel.core.source.HistoryProvider
import io.github.jiangyuyi.lightnovel.core.source.HistoryMutationProvider
import io.github.jiangyuyi.lightnovel.core.source.NovelDetail
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSource
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.PasswordCredentials
import io.github.jiangyuyi.lightnovel.core.source.ReaderProvider
import io.github.jiangyuyi.lightnovel.core.source.ReadingHistoryEntry
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgressSyncProvider
import io.github.jiangyuyi.lightnovel.core.source.SearchProvider
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceCapability
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourcePage
import io.github.jiangyuyi.lightnovel.core.source.SourceProfile
import io.github.jiangyuyi.lightnovel.core.source.SourceProfileProvider
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary

private const val SOURCE_ID = BuiltInSourceIds.LIGHT_NOVEL_KINGDOM

internal interface LightNovelKingdomGateway {
    suspend fun discover(channel: DiscoverChannel, page: Int, pageSize: Int): Page<BookSummary>
    suspend fun search(query: String, page: Int, pageSize: Int): Page<BookSummary>
    suspend fun bookDetail(bookId: Long): BookDetail
    suspend fun volumes(bookId: Long, page: Int, pageSize: Int): Page<Volume>
    suspend fun chapters(bookId: Long, volumeId: Long, page: Int, pageSize: Int): Page<KingdomChapterSummary>
    suspend fun chapter(bookId: Long, chapterId: Long): ChapterDetail
    suspend fun restoreSession(): Session
    suspend fun profile(): AccountProfile = error("profile is not implemented")
    suspend fun login(username: String, password: String): Session
    suspend fun logout()
    suspend fun bookshelf(): List<BookSummary>
    suspend fun setBookshelf(bookId: Long, add: Boolean): Boolean
    suspend fun readingHistory(page: Int, pageSize: Int): Page<ReadingHistoryItem>
    suspend fun deleteReadingHistory(bookId: Long)
    suspend fun saveReadingProgress(
        bookId: Long,
        volumeId: Long,
        chapterId: Long,
        paragraphIndex: Int,
        percent: Int,
    )
}

private class RepositoryLightNovelKingdomGateway(
    private val repository: LightNovelRepository,
) : LightNovelKingdomGateway {
    override suspend fun discover(channel: DiscoverChannel, page: Int, pageSize: Int) =
        repository.discover(channel, page, pageSize)

    override suspend fun search(query: String, page: Int, pageSize: Int) =
        repository.search(query = query, page = page, pageSize = pageSize)

    override suspend fun bookDetail(bookId: Long) = repository.bookDetail(bookId)
    override suspend fun volumes(bookId: Long, page: Int, pageSize: Int) = repository.volumes(bookId, page, pageSize)
    override suspend fun chapters(bookId: Long, volumeId: Long, page: Int, pageSize: Int) =
        repository.chapters(bookId, volumeId, page, pageSize)

    override suspend fun chapter(bookId: Long, chapterId: Long) = repository.chapter(bookId, chapterId)
    override suspend fun restoreSession() = repository.restoreSession()
    override suspend fun profile() = repository.myProfile()
    override suspend fun login(username: String, password: String) = repository.login(username, password)
    override suspend fun logout() = repository.logout()
    override suspend fun bookshelf() = repository.bookshelf()
    override suspend fun setBookshelf(bookId: Long, add: Boolean) = repository.setBookshelf(bookId, add)
    override suspend fun readingHistory(page: Int, pageSize: Int) = repository.readingHistory(page, pageSize)
    override suspend fun deleteReadingHistory(bookId: Long) = repository.deleteReadingHistory(bookId)
    override suspend fun saveReadingProgress(
        bookId: Long,
        volumeId: Long,
        chapterId: Long,
        paragraphIndex: Int,
        percent: Int,
    ) = repository.saveReadingProgress(bookId, volumeId, chapterId, paragraphIndex, percent)
}

class LightNovelKingdomSource internal constructor(
    private val gateway: LightNovelKingdomGateway,
) : NovelSource,
    DiscoverProvider,
    SearchProvider,
    DetailProvider,
    ReaderProvider,
    AccountProvider,
    ShelfProvider,
    HistoryProvider,
    HistoryMutationProvider,
    ReadingProgressSyncProvider,
    SourceProfileProvider {

    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "轻之国度",
        capabilities = setOf(
            SourceCapability.DISCOVER,
            SourceCapability.SEARCH,
            SourceCapability.DETAIL,
            SourceCapability.READER,
            SourceCapability.ACCOUNT,
            SourceCapability.REMOTE_SHELF,
            SourceCapability.HISTORY,
        ),
    )

    override val discoverFeeds = listOf(
        DiscoverFeed.POPULAR,
        DiscoverFeed.WEEKLY_RANK,
        DiscoverFeed.NEWEST,
        DiscoverFeed.ORIGINAL,
        DiscoverFeed.FANFIC,
        DiscoverFeed.EPUB,
        DiscoverFeed.LATEST,
    )

    override suspend fun discover(feed: DiscoverFeed, page: Int, pageSize: Int): SourcePage<NovelSummary> {
        require(feed in discoverFeeds) { "unsupported light novel kingdom feed: $feed" }
        return gateway.discover(feed.toKingdomChannel(), page, pageSize).toSourcePage(BookSummary::toSource)
    }

    override suspend fun search(query: String, page: Int, pageSize: Int): SourcePage<NovelSummary> =
        gateway.search(query, page, pageSize).toSourcePage(BookSummary::toSource)

    override suspend fun getNovelDetail(key: NovelKey): NovelDetail {
        val detail = gateway.bookDetail(key.requireKingdomId())
        return NovelDetail(
            novel = detail.book.toSource(),
            alternateVersions = detail.alternateVersions.map(BookSummary::toSource),
            publisherName = detail.publisher?.nickname,
            favoriteCount = detail.favoriteCount,
            commentCount = detail.commentCount,
        )
    }

    override suspend fun getVolumes(key: NovelKey, page: Int, pageSize: Int): SourcePage<VolumeSummary> =
        gateway.volumes(key.requireKingdomId(), page, pageSize).toSourcePage(Volume::toSource)

    override suspend fun getChapters(
        novelKey: NovelKey,
        volumeKey: VolumeKey,
        page: Int,
        pageSize: Int,
    ): SourcePage<ChapterSummary> = gateway.chapters(
        novelKey.requireKingdomId(),
        volumeKey.requireKingdomId(),
        page,
        pageSize,
    ).toSourcePage(KingdomChapterSummary::toSource)

    override suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent =
        gateway.chapter(novelKey.requireKingdomId(), chapterKey.requireKingdomId()).toSource()

    override suspend fun restoreSession(): SourceSession = gateway.restoreSession().toSource()

    override suspend fun login(credentials: PasswordCredentials): SourceSession =
        gateway.login(credentials.identifier.trim(), credentials.password).toSource()

    override suspend fun logout() = gateway.logout()

    override suspend fun getProfile(): SourceProfile = gateway.profile().toSourceProfile()

    override suspend fun getRemoteShelf(): List<NovelSummary> = gateway.bookshelf().map(BookSummary::toSource)

    override suspend fun setInRemoteShelf(key: NovelKey, add: Boolean): Boolean =
        gateway.setBookshelf(key.requireKingdomId(), add)

    override suspend fun getReadingHistory(page: Int, pageSize: Int): SourcePage<ReadingHistoryEntry> =
        gateway.readingHistory(page, pageSize).toSourcePage { history ->
            ReadingHistoryEntry(
                novel = history.book.toSource(),
                lastChapterKey = history.lastChapterId?.toChapterKey(),
                lastChapterTitle = history.lastChapterTitle,
                readAt = history.readAt,
            )
        }

    override suspend fun deleteReadingHistory(novelKey: NovelKey) =
        gateway.deleteReadingHistory(novelKey.requireKingdomId())

    override suspend fun saveReadingProgress(progress: ReadingProgress) {
        gateway.saveReadingProgress(
            bookId = progress.novelKey.requireKingdomId(),
            volumeId = progress.volumeKey?.requireKingdomId() ?: 0,
            chapterId = progress.chapterKey.requireKingdomId(),
            paragraphIndex = progress.paragraphIndex.coerceAtLeast(0),
            percent = progress.percent.coerceIn(0, 100),
        )
    }

    companion object {
        fun from(repository: LightNovelRepository): LightNovelKingdomSource =
            LightNovelKingdomSource(RepositoryLightNovelKingdomGateway(repository))
    }
}

private fun AccountProfile.toSourceProfile() = SourceProfile(
    sourceId = SOURCE_ID,
    accountId = user.uid.takeIf { it > 0 }?.toString(),
    displayName = user.nickname,
    avatarUrl = user.avatarUrl,
    balance = coin.toLong(),
    levelLabel = levelName.takeIf { it.isNotBlank() },
    extra = mapOf(
        "关注" to followingCount.toString(),
        "粉丝" to fansCount.toString(),
        "发布" to postCount.toString(),
    ),
)

private fun DiscoverFeed.toKingdomChannel(): DiscoverChannel = when (this) {
    DiscoverFeed.POPULAR -> DiscoverChannel.HOT
    DiscoverFeed.LATEST -> DiscoverChannel.UPDATED
    DiscoverFeed.NEWEST -> DiscoverChannel.NEW
    DiscoverFeed.WEEKLY_RANK -> DiscoverChannel.RANK
    DiscoverFeed.ORIGINAL -> DiscoverChannel.ORIGINAL
    DiscoverFeed.FANFIC -> DiscoverChannel.FANFIC
    DiscoverFeed.EPUB -> DiscoverChannel.EPUB
    DiscoverFeed.DAILY_RANK,
    DiscoverFeed.MONTHLY_RANK,
    -> error("unsupported light novel kingdom feed: $this")
}

private fun BookSummary.toSource() = NovelSummary(
    key = NovelKey(SOURCE_ID, id.toString()),
    title = title,
    authors = author.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
    synopsis = summary,
    coverUrl = coverUrl,
    tags = tags,
    volumeCount = volumeCount,
    chapterCount = chapterCount,
    wordCount = wordCount,
    score = score,
    inRemoteShelf = inBookshelf,
    unreadChapterCount = unreadChapterCount,
)

private fun Volume.toSource() = VolumeSummary(
    key = VolumeKey(SOURCE_ID, id.toString()),
    novelKey = NovelKey(SOURCE_ID, bookId.toString()),
    title = title,
    chapterCount = chapterCount,
)

private fun KingdomChapterSummary.toSource() = ChapterSummary(
    key = id.toChapterKey(),
    novelKey = NovelKey(SOURCE_ID, bookId.toString()),
    volumeKey = VolumeKey(SOURCE_ID, volumeId.toString()),
    title = title,
    order = order,
    wordCount = wordCount,
    locked = locked,
)

private fun ChapterDetail.toSource() = ChapterContent(
    chapter = chapter.toSource(),
    novelTitle = bookTitle,
    volumeTitle = volumeTitle,
    bodyText = bodyText,
    bodyHtml = bodyHtml,
    previousChapterKey = previousChapterId?.toChapterKey(),
    nextChapterKey = nextChapterId?.toChapterKey(),
)

private fun Session.toSource() = SourceSession(
    loggedIn = loggedIn,
    accountId = uid.takeIf { it > 0 }?.toString(),
    displayName = user?.nickname,
)

private fun Long.toChapterKey() = ChapterKey(SOURCE_ID, toString())

private fun NovelKey.requireKingdomId(): Long = requireKingdomId(sourceId, remoteId)
private fun VolumeKey.requireKingdomId(): Long = requireKingdomId(sourceId, remoteId)
private fun ChapterKey.requireKingdomId(): Long = requireKingdomId(sourceId, remoteId)

private fun requireKingdomId(sourceId: String, remoteId: String): Long {
    require(sourceId == SOURCE_ID) { "resource belongs to $sourceId, not $SOURCE_ID" }
    return requireNotNull(remoteId.toLongOrNull()) { "invalid light novel kingdom id: $remoteId" }
}

private fun <T, R> Page<T>.toSourcePage(transform: (T) -> R) = SourcePage(
    items = items.map(transform),
    page = page,
    total = total,
    hasMore = hasMore,
)
