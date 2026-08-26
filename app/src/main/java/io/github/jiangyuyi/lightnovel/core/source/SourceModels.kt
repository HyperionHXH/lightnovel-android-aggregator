package io.github.jiangyuyi.lightnovel.core.source

import kotlinx.serialization.Serializable

object BuiltInSourceIds {
    const val LIGHT_NOVEL_KINGDOM = "light_novel_kingdom"
    const val LIGHT_NOVEL_SHELF = "light_novel_shelf"
}

@Serializable
enum class SourceCapability {
    DISCOVER,
    SEARCH,
    DETAIL,
    READER,
    ACCOUNT,
    REMOTE_SHELF,
    HISTORY,
    DAILY_REWARD,
}

@Serializable
enum class DiscoverFeed(val label: String) {
    POPULAR("热门"),
    LATEST("最近更新"),
    NEWEST("新书"),
    DAILY_RANK("日榜"),
    WEEKLY_RANK("周榜"),
    MONTHLY_RANK("月榜"),
    ORIGINAL("原创"),
    FANFIC("同人"),
    EPUB("EPUB"),
}

@Serializable
enum class AccountIdentifierKind {
    USERNAME_OR_EMAIL,
    EMAIL,
}

@Serializable
data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<SourceCapability>,
    val accountIdentifierKind: AccountIdentifierKind = AccountIdentifierKind.USERNAME_OR_EMAIL,
) {
    init {
        require(id.isNotBlank()) { "source id must not be blank" }
        require(displayName.isNotBlank()) { "source display name must not be blank" }
    }
}

@Serializable
data class NovelKey(
    val sourceId: String,
    val remoteId: String,
) {
    init {
        require(sourceId.isNotBlank()) { "source id must not be blank" }
        require(remoteId.isNotBlank()) { "remote id must not be blank" }
    }
}

@Serializable
data class VolumeKey(
    val sourceId: String,
    val remoteId: String,
) {
    init {
        require(sourceId.isNotBlank()) { "source id must not be blank" }
        require(remoteId.isNotBlank()) { "remote id must not be blank" }
    }
}

@Serializable
data class ChapterKey(
    val sourceId: String,
    val remoteId: String,
) {
    init {
        require(sourceId.isNotBlank()) { "source id must not be blank" }
        require(remoteId.isNotBlank()) { "remote id must not be blank" }
    }
}

@Serializable
data class SourcePage<T>(
    val items: List<T>,
    val page: Int,
    val total: Int = items.size,
    val hasMore: Boolean = false,
)

@Serializable
data class NovelSummary(
    val key: NovelKey,
    val title: String,
    val authors: List<String> = emptyList(),
    val synopsis: String = "",
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    val volumeCount: Int = 0,
    val chapterCount: Int = 0,
    val wordCount: Long = 0,
    val score: Double? = null,
    val inRemoteShelf: Boolean? = null,
    /** Account-scoped unread count, when the source provides a trustworthy value. */
    val unreadChapterCount: Int? = null,
)

@Serializable
data class NovelDetail(
    val novel: NovelSummary,
    val alternateVersions: List<NovelSummary> = emptyList(),
    val publisherName: String? = null,
    val favoriteCount: Int = 0,
    val commentCount: Int = 0,
)

@Serializable
data class VolumeSummary(
    val key: VolumeKey,
    val novelKey: NovelKey,
    val title: String,
    val chapterCount: Int = 0,
)

@Serializable
data class ChapterSummary(
    val key: ChapterKey,
    val novelKey: NovelKey,
    val volumeKey: VolumeKey,
    val title: String,
    val order: Int = 0,
    val wordCount: Long = 0,
    val locked: Boolean = false,
)

@Serializable
data class ChapterContent(
    val chapter: ChapterSummary,
    val novelTitle: String,
    val volumeTitle: String,
    val bodyText: String,
    val bodyHtml: String = "",
    val fontUrl: String? = null,
    val previousChapterKey: ChapterKey? = null,
    val nextChapterKey: ChapterKey? = null,
)

class PasswordCredentials(
    val identifier: String,
    val password: String,
) {
    override fun toString(): String = "PasswordCredentials(identifier=<redacted>, password=<redacted>)"
}

@Serializable
data class SourceSession(
    val loggedIn: Boolean,
    val accountId: String? = null,
    val displayName: String? = null,
)

@Serializable
data class SourceProfile(
    val sourceId: String,
    val accountId: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val balance: Long? = null,
    val levelLabel: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

@Serializable
data class ReadingHistoryEntry(
    val novel: NovelSummary,
    val lastChapterKey: ChapterKey? = null,
    val lastChapterTitle: String = "",
    val readAt: String = "",
)

@Serializable
data class ReadingProgress(
    val novelKey: NovelKey,
    val volumeKey: VolumeKey?,
    val chapterKey: ChapterKey,
    val paragraphIndex: Int,
    val percent: Int,
)

@Serializable
data class RewardStatus(
    val claimedToday: Boolean,
    val balance: Long? = null,
    val streakDays: Int? = null,
)

@Serializable
data class RewardResult(
    val rewardAmount: Long? = null,
    val balance: Long? = null,
    val streakDays: Int? = null,
)
