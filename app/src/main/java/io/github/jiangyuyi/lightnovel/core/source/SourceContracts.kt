package io.github.jiangyuyi.lightnovel.core.source

interface SourceProvider {
    val descriptor: SourceDescriptor
}

interface NovelSource : SourceProvider

interface DiscoverProvider : SourceProvider {
    val discoverFeeds: List<DiscoverFeed>
    suspend fun discover(feed: DiscoverFeed, page: Int = 1, pageSize: Int = 20): SourcePage<NovelSummary>
}

interface SearchProvider : SourceProvider {
    suspend fun search(query: String, page: Int = 1, pageSize: Int = 20): SourcePage<NovelSummary>
}

interface DetailProvider : SourceProvider {
    suspend fun getNovelDetail(key: NovelKey): NovelDetail
}

interface ReaderProvider : SourceProvider {
    suspend fun getVolumes(key: NovelKey, page: Int = 1, pageSize: Int = 50): SourcePage<VolumeSummary>

    suspend fun getChapters(
        novelKey: NovelKey,
        volumeKey: VolumeKey,
        page: Int = 1,
        pageSize: Int = 50,
    ): SourcePage<ChapterSummary>

    suspend fun getChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent
}

interface ChapterUnlockProvider : SourceProvider {
    suspend fun unlockChapter(chapterKey: ChapterKey)
}

interface AccountProvider : SourceProvider {
    suspend fun restoreSession(): SourceSession
    suspend fun login(credentials: PasswordCredentials): SourceSession
    suspend fun logout()
}

/** Optional account details shown in the unified profile screen. */
interface SourceProfileProvider : SourceProvider {
    suspend fun getProfile(): SourceProfile
}

interface ShelfProvider : SourceProvider {
    suspend fun getRemoteShelf(): List<NovelSummary>
    suspend fun isInRemoteShelf(key: NovelKey): Boolean = getRemoteShelf().any { it.key == key }
    suspend fun setInRemoteShelf(key: NovelKey, add: Boolean): Boolean
}

interface HistoryProvider : SourceProvider {
    suspend fun getReadingHistory(page: Int = 1, pageSize: Int = 20): SourcePage<ReadingHistoryEntry>
}

interface HistoryMutationProvider : SourceProvider {
    suspend fun deleteReadingHistory(novelKey: NovelKey)
}

interface ReadingProgressSyncProvider : SourceProvider {
    suspend fun saveReadingProgress(progress: ReadingProgress)
}

interface RewardProvider : SourceProvider {
    suspend fun getRewardStatus(): RewardStatus
    suspend fun claimDailyReward(): RewardResult
}

interface RewardCenterProvider : RewardProvider {
    suspend fun getRewardCenter(): RewardCenter
}

interface CommentProvider : SourceProvider {
    suspend fun getComments(
        novelKey: NovelKey,
        sort: CommentSort = CommentSort.HOT,
        page: Int = 1,
        pageSize: Int = 20,
    ): SourcePage<SourceComment>

    suspend fun publishComment(novelKey: NovelKey, content: String, ratingStars: Int = 0): SourceComment

    /** Optional rich-comment capabilities. Sources can keep the defaults when unsupported. */
    suspend fun getCommentEmojis(): List<SourceCommentEmoji> = emptyList()

    suspend fun getMentionCandidates(novelKey: NovelKey, query: String = ""): List<SourceMentionCandidate> = emptyList()

    suspend fun uploadCommentImage(
        novelKey: NovelKey,
        bytes: ByteArray,
        fileName: String = "comment-image.jpg",
        mimeType: String = "image/jpeg",
    ): SourceCommentMedia = error("comment image upload is not supported")

    suspend fun publishComment(
        novelKey: NovelKey,
        content: String,
        ratingStars: Int = 0,
        rootCommentId: String? = null,
        replyCommentId: String? = null,
        mentionUids: List<Long> = emptyList(),
        media: List<SourceCommentMedia> = emptyList(),
    ): SourceComment = publishComment(novelKey, content, ratingStars)

    suspend fun toggleCommentLike(
        novelKey: NovelKey,
        commentId: String,
        currentlyLiked: Boolean,
    ): SourceCommentInteraction = error("comment likes are not supported")

    suspend fun rateNovel(novelKey: NovelKey, ratingStars: Int): SourceCommentInteraction =
        error("novel ratings are not supported")
}
