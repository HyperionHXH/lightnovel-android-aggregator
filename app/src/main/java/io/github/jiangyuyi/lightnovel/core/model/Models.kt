package io.github.jiangyuyi.lightnovel.core.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSummary(
    val uid: Long,
    val nickname: String,
    val avatarUrl: String? = null,
)

@Serializable
data class AccountProfile(
    val user: UserSummary,
    val signature: String = "",
    val levelName: String = "",
    val level: Int? = null,
    val coin: Int = 0,
    val fansCount: Int = 0,
    val followingCount: Int = 0,
    val postCount: Int = 0,
)

@Serializable
data class SocialUser(
    val user: UserSummary,
    val signature: String = "",
    val levelName: String = "",
    val followed: Boolean = false,
    val relationState: String = "",
)

@Serializable
data class Session(
    val loggedIn: Boolean = false,
    val securityKey: String = "",
    val uid: Long = 0,
    val user: UserSummary? = null,
)

@Serializable
data class BookSummary(
    val id: Long,
    val title: String,
    val author: String = "",
    val summary: String = "",
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    val volumeCount: Int = 0,
    val chapterCount: Int = 0,
    val wordCount: Long = 0,
    val score: Double? = null,
    val rank: Int? = null,
    val defaultVolumeId: Long? = null,
    val defaultChapterId: Long? = null,
    val inBookshelf: Boolean? = null,
    val unreadChapterCount: Int? = null,
)

@Serializable
data class BookDetail(
    val book: BookSummary,
    val publisher: UserSummary? = null,
    val alternateVersions: List<BookSummary> = emptyList(),
    val commentCount: Int = 0,
    val favoriteCount: Int = 0,
)

@Serializable
data class Volume(
    val id: Long,
    val bookId: Long,
    val title: String,
    val chapterCount: Int = 0,
    val firstChapterId: Long? = null,
    val lastChapterId: Long? = null,
)

@Serializable
data class ChapterSummary(
    val id: Long,
    val bookId: Long,
    val volumeId: Long,
    val title: String,
    val order: Int = 0,
    val wordCount: Long = 0,
    val locked: Boolean = false,
    val coinPrice: Int? = null,
)

@Serializable
data class ChapterDetail(
    val chapter: ChapterSummary,
    val bookTitle: String,
    val volumeTitle: String,
    val bodyText: String,
    val bodyHtml: String = "",
    val previousChapterId: Long? = null,
    val nextChapterId: Long? = null,
)

@Serializable
data class ReaderBootstrap(
    val book: BookSummary,
    val chapterId: Long,
    val volumeId: Long? = null,
    val inBookshelf: Boolean = false,
    val resumeAvailable: Boolean = false,
)

@Serializable
data class Comment(
    val id: Long,
    val author: UserSummary,
    val content: String,
    val createdAt: String = "",
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val ratingStars: Int? = null,
)

@Serializable
data class Page<T>(
    val items: List<T>,
    val page: Int = 1,
    val total: Int = items.size,
    val hasMore: Boolean = false,
)

@Serializable
data class SearchTaxonomy(
    val channels: List<SearchOption>,
    val tags: List<SearchOption>,
)

@Serializable
data class SearchOption(
    val id: String,
    val label: String,
    val workType: String = "",
)

@Serializable
enum class DiscoverChannel(val label: String) {
    HOT("热门"),
    DAILY_RANK("日榜"),
    RANK("排行"),
    NEW("新书"),
    ORIGINAL("原创"),
    FANFIC("同人"),
    EPUB("EPUB"),
    UPDATED("最近更新"),
    COLLECTION("合集"),
}

@Serializable
enum class ReaderFont(val label: String) {
    DEFAULT("系统默认"),
    SANS("系统黑体"),
    SERIF("系统宋体"),
    MONO("等宽字体"),
    CURSIVE("手写体"),
    CONDENSED("紧凑黑体"),
    ROUNDED("圆体"),
}

@Serializable
enum class ReaderTheme(val label: String) {
    WHITE("白色"),
    SEPIA("米黄"),
    GREEN("护眼绿"),
    DARK("深色"),
}

@Serializable
enum class ReaderMode(val label: String) {
    PAGED("左右翻页"),
    SCROLL("上下滚动"),
}

@Serializable
enum class ReaderTapZone(val label: String) {
    DEFAULT("默认"),
    L_SHAPE("L 形"),
    KINDLE("Kindle"),
    BOTH_SIDES("两侧"),
    LEFT_RIGHT("左右"),
    DISABLED("关闭"),
}

@Serializable
enum class ReaderTapInversion(val label: String) {
    NONE("无"),
    LEFT_RIGHT("左右"),
    UP_DOWN("上下"),
    ALL("全部"),
}

@Serializable
enum class ReaderOrientation(val label: String) {
    DEFAULT("跟随系统"),
    PORTRAIT("竖屏"),
    LANDSCAPE("横屏"),
}

@Serializable
enum class ReaderImageScale(val label: String) {
    FIT("适应屏幕"),
    FILL("拉伸"),
    FIT_WIDTH("适应宽度"),
    FIT_HEIGHT("适应高度"),
    ORIGINAL("原始大小"),
    SMART("智能填充"),
}

@Serializable
data class ReaderPreferences(
    val font: ReaderFont = ReaderFont.SERIF,
    val customFontId: String? = null,
    val fontSize: Float = 21f,
    val lineHeight: Float = 1.75f,
    val horizontalPadding: Int = 28,
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val mode: ReaderMode = ReaderMode.PAGED,
    val tapZone: ReaderTapZone = ReaderTapZone.DEFAULT,
    val tapInversion: ReaderTapInversion = ReaderTapInversion.NONE,
    val orientation: ReaderOrientation = ReaderOrientation.DEFAULT,
    val imageScale: ReaderImageScale = ReaderImageScale.FIT,
    val volumeKeys: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showProgressBar: Boolean = true,
)

@Serializable
data class LocalReadingProgress(
    val chapterId: Long,
    val paragraphIndex: Int,
    val percent: Int,
)

@Serializable
data class ReadingHistoryItem(
    val book: BookSummary,
    val lastChapterId: Long? = null,
    val lastChapterTitle: String = "",
    val readAt: String = "",
)

@Serializable
data class PublishedWork(
    val bookId: Long,
    val title: String,
    val coverUrl: String? = null,
    val author: String = "",
    val summary: String = "",
    val type: String = "",
    val status: String = "",
    val reviewStatus: String = "",
    val reviewText: String = "",
    val volumeCount: Int = 0,
    val chapterCount: Int = 0,
    val wordCount: Long = 0,
    val updatedAt: String = "",
)

@Serializable
enum class MessageCategory(val code: String, val label: String) {
    DM("dm", "私信"),
    REPLY("reply", "回复"),
    MENTION("mention", "@我"),
    LIKE("like", "点赞"),
    FAN("fan", "新粉丝"),
    SYSTEM("system", "系统"),
}

@Serializable
data class MessageSummary(
    val unreadCount: Int = 0,
    val replyCount: Int = 0,
    val mentionCount: Int = 0,
    val likeCount: Int = 0,
    val systemCount: Int = 0,
    val dmCount: Int = 0,
    val fanCount: Int = 0,
) {
    fun count(category: MessageCategory): Int = when (category) {
        MessageCategory.DM -> dmCount
        MessageCategory.REPLY -> replyCount
        MessageCategory.MENTION -> mentionCount
        MessageCategory.LIKE -> likeCount
        MessageCategory.FAN -> fanCount
        MessageCategory.SYSTEM -> systemCount
    }

    fun clear(category: MessageCategory): MessageSummary {
        val removed = count(category)
        return when (category) {
            MessageCategory.DM -> copy(unreadCount = (unreadCount - removed).coerceAtLeast(0), dmCount = 0)
            MessageCategory.REPLY -> copy(unreadCount = (unreadCount - removed).coerceAtLeast(0), replyCount = 0)
            MessageCategory.MENTION -> copy(unreadCount = (unreadCount - removed).coerceAtLeast(0), mentionCount = 0)
            MessageCategory.LIKE -> copy(unreadCount = (unreadCount - removed).coerceAtLeast(0), likeCount = 0)
            MessageCategory.FAN -> copy(unreadCount = (unreadCount - removed).coerceAtLeast(0), fanCount = 0)
            MessageCategory.SYSTEM -> copy(unreadCount = (unreadCount - removed).coerceAtLeast(0), systemCount = 0)
        }
    }
}

@Serializable
data class NotificationMessage(
    val id: String,
    val category: MessageCategory,
    val user: UserSummary? = null,
    val sourceName: String = "",
    val sourceAvatarUrl: String? = null,
    val title: String,
    val content: String,
    val quoteText: String = "",
    val relatedTitle: String = "",
    val createdAt: String = "",
    val unread: Boolean = false,
    val targetBookId: Long? = null,
    val targetVolumeId: Long? = null,
    val targetChapterId: Long? = null,
    val targetDynamicId: Long? = null,
    val targetCommentId: Long? = null,
    val targetReplyId: Long? = null,
    val targetUrl: String = "",
)

@Serializable
data class DmConversation(
    val id: String,
    val peerUid: Long,
    val user: UserSummary,
    val lastMessage: String = "",
    val unreadCount: Int = 0,
    val updatedAt: String = "",
    val canSend: Boolean = false,
    val denyReason: String = "",
)

@Serializable
data class DmMessage(
    val id: String,
    val conversationId: String = "",
    val sender: UserSummary,
    val content: String,
    val createdAt: String = "",
    val mine: Boolean = false,
)
