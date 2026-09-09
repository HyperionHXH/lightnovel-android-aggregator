package io.github.jiangyuyi.lightnovel.desktop

import java.security.MessageDigest
import java.util.Base64
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DesktopFeed(val label: String) {
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

data class DesktopBook(
    val sourceId: String,
    val remoteId: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val summary: String = "",
    val inRemoteShelf: Boolean? = null,
    /** Public catalogue state, when a source exposes it. Null means unknown. */
    val isReadable: Boolean? = null,
    val readabilityNote: String? = null,
    val unreadChapterCount: Int? = null,
) {
    val key: String get() = "$sourceId:$remoteId"
}

data class DesktopBookDetail(
    val book: DesktopBook,
    val description: String = book.summary,
    val alternateVersions: List<DesktopBook> = emptyList(),
    val favoriteCount: Int = 0,
    val commentCount: Int = 0,
)

data class DesktopVolume(
    val sourceId: String,
    val remoteId: String,
    val bookRemoteId: String,
    val title: String,
    val chapterCount: Int = 0,
)

data class DesktopChapter(
    val sourceId: String,
    val remoteId: String,
    val bookRemoteId: String,
    val volumeRemoteId: String,
    val title: String,
    val order: Int = 0,
    val locked: Boolean = false,
    val coinPrice: Long? = null,
)

data class DesktopChapterPage(
    val items: List<DesktopChapter>,
    val page: Int,
    val total: Int = items.size,
    val hasMore: Boolean = false,
)

data class DesktopChapterContent(
    val chapter: DesktopChapter,
    val bookTitle: String,
    val volumeTitle: String,
    val bodyText: String = "",
    val bodyHtml: String = "",
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
)

data class DesktopSession(
    val sourceId: String,
    val loggedIn: Boolean,
    val accountId: String? = null,
    val displayName: String? = null,
)

data class DesktopProfile(
    val sourceId: String,
    val accountId: String? = null,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val balance: Long? = null,
    val levelLabel: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

data class DesktopReward(
    val amount: Long = 0,
    val balance: Long? = null,
    val streakDays: Int? = null,
    val claimedToday: Boolean = false,
)

interface DesktopSource {
    val id: String
    val displayName: String
    val feeds: List<DesktopFeed>

    fun restoreSession(): DesktopSession
    fun login(identifier: String, password: String): DesktopSession
    fun logout()
    fun discover(feed: DesktopFeed, page: Int, pageSize: Int): DesktopPage
    fun search(query: String, page: Int, pageSize: Int): DesktopPage
    fun detail(remoteId: String): DesktopBookDetail
    fun volumes(remoteId: String): List<DesktopVolume>
    fun chapters(remoteId: String, volumeRemoteId: String, page: Int, pageSize: Int): DesktopChapterPage
    fun chapter(remoteId: String, chapterRemoteId: String): DesktopChapterContent
    fun unlockChapter(remoteId: String, chapterRemoteId: String): Boolean = false
    fun bookshelf(): List<DesktopBook>
    fun history(): List<DesktopBook> = emptyList()
    fun setBookshelf(remoteId: String, add: Boolean): Boolean
    fun profile(): DesktopProfile
    fun rewardStatus(): DesktopReward = DesktopReward()
    fun claimDailyReward(): DesktopReward = error("$displayName 不支持每日签到")
}

class DesktopSourceRegistry(sources: Iterable<DesktopSource>) {
    private val byId = sources.toList().also { list ->
        val duplicateIds = list.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "重复来源 ID：${duplicateIds.sorted().joinToString()}" }
    }.associateBy { it.id }

    fun all(): List<DesktopSource> = byId.values.toList()
    fun get(id: String): DesktopSource? = byId[id]
}

/**
 * Desktop sessions are kept in the user's preferences namespace, never in source code or logs.
 * Passwords are deliberately never persisted. A later Windows release can replace this store
 * with Credential Manager without changing source adapters.
 */
class DesktopSessionStore(
    private val preferences: Preferences = Preferences.userRoot().node("io/github/jiangyuyi/mixn/sessions"),
) {
    fun read(sourceId: String): Map<String, String> = listOf("account", "display", "security", "access", "refresh")
        .mapNotNull { key ->
            val value = preferences.get("$sourceId.$key", "").takeIf(String::isNotBlank) ?: return@mapNotNull null
            runCatching { key to decrypt(value) }.getOrNull()
        }
        .toMap()
        .filterValues(String::isNotBlank)

    fun write(sourceId: String, values: Map<String, String>) {
        clear(sourceId)
        values.forEach { (key, value) ->
            if (value.isNotBlank()) preferences.put("$sourceId.$key", encrypt(value))
        }
        preferences.flush()
    }

    fun clear(sourceId: String) {
        listOf("account", "display", "security", "access", "refresh").forEach { key ->
            preferences.remove("$sourceId.$key")
        }
        preferences.flush()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(payload)}"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "invalid encrypted session value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])),
        )
        return cipher.doFinal(Base64.getDecoder().decode(parts[1])).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKeySpec {
        val material = listOf(
            System.getProperty("user.name").orEmpty(),
            System.getProperty("user.home").orEmpty(),
            "mixn-desktop-session-v1",
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

fun DesktopSource.sessionFromStore(store: DesktopSessionStore): DesktopSession {
    val values = store.read(id)
    return DesktopSession(
        sourceId = id,
        loggedIn = values.isNotEmpty(),
        accountId = values["account"],
        displayName = values["display"],
    )
}
