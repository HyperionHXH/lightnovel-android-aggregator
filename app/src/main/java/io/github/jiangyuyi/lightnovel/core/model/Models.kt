package io.github.jiangyuyi.lightnovel.core.model

data class UserSummary(
    val uid: Long,
    val nickname: String,
    val avatarUrl: String? = null,
)

data class Session(
    val loggedIn: Boolean = false,
    val securityKey: String = "",
    val uid: Long = 0,
    val user: UserSummary? = null,
)

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

data class BookDetail(
    val book: BookSummary,
    val publisher: UserSummary? = null,
    val alternateVersions: List<BookSummary> = emptyList(),
    val commentCount: Int = 0,
    val favoriteCount: Int = 0,
)

data class Volume(
    val id: Long,
    val bookId: Long,
    val title: String,
    val chapterCount: Int = 0,
    val firstChapterId: Long? = null,
    val lastChapterId: Long? = null,
)

data class ChapterSummary(
    val id: Long,
    val bookId: Long,
    val volumeId: Long,
    val title: String,
    val order: Int = 0,
    val wordCount: Long = 0,
    val locked: Boolean = false,
)

data class ChapterDetail(
    val chapter: ChapterSummary,
    val bookTitle: String,
    val volumeTitle: String,
    val bodyText: String,
    val bodyHtml: String = "",
    val previousChapterId: Long? = null,
    val nextChapterId: Long? = null,
)

data class ReaderBootstrap(
    val book: BookSummary,
    val chapterId: Long,
    val volumeId: Long? = null,
    val inBookshelf: Boolean = false,
    val resumeAvailable: Boolean = false,
)

data class Comment(
    val id: Long,
    val author: UserSummary,
    val content: String,
    val createdAt: String = "",
    val likeCount: Int = 0,
    val replyCount: Int = 0,
)

data class Page<T>(
    val items: List<T>,
    val page: Int = 1,
    val total: Int = items.size,
    val hasMore: Boolean = false,
)

data class SearchTaxonomy(
    val channels: List<SearchOption>,
    val tags: List<SearchOption>,
)

data class SearchOption(
    val id: String,
    val label: String,
    val workType: String = "",
)

enum class DiscoverChannel(val label: String) {
    HOT("热门"),
    RANK("排行"),
    NEW("新书"),
    ORIGINAL("原创"),
    FANFIC("同人"),
    EPUB("EPUB"),
    UPDATED("最近更新"),
    COLLECTION("合集"),
}

enum class ReaderFont(val label: String) {
    SANS("无衬线"),
    SERIF("衬线"),
    MONO("等宽"),
}

enum class ReaderTheme(val label: String) {
    WHITE("白色"),
    SEPIA("米黄"),
    GREEN("护眼绿"),
    DARK("深色"),
}

enum class ReaderMode(val label: String) {
    PAGED("左右翻页"),
    SCROLL("上下滚动"),
}

data class ReaderPreferences(
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.7f,
    val horizontalPadding: Int = 22,
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val mode: ReaderMode = ReaderMode.PAGED,
)

data class LocalReadingProgress(
    val chapterId: Long,
    val paragraphIndex: Int,
    val percent: Int,
)
