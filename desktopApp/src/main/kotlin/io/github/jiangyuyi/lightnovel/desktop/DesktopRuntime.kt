package io.github.jiangyuyi.lightnovel.desktop

import java.util.prefs.Preferences

class DesktopSourcePageLoader(
    private val registry: DesktopSourceRegistry,
) : DesktopPageLoader {
    override fun load(sourceId: String, feed: String, query: String, page: Int, pageSize: Int): DesktopPage {
        val source = registry.get(sourceId) ?: throw IllegalArgumentException("未知来源：$sourceId")
        if (query.isNotBlank()) return source.search(query, page, pageSize)
        val selectedFeed = DesktopFeed.entries.firstOrNull { it.label == feed }
            ?: throw IllegalArgumentException("未知分区：$feed")
        return source.discover(selectedFeed, page, pageSize)
    }
}

class DesktopReadingProgressStore(
    private val preferences: Preferences = Preferences.userRoot().node("io/github/jiangyuyi/mixn/progress"),
) {
    fun read(book: DesktopBook): String? = preferences.get(book.key, null)

    fun readState(book: DesktopBook): DesktopSavedProgress? {
        val parts = read(book)?.split('|', limit = 2) ?: return null
        if (parts.size != 2 || parts[0].isBlank()) return null
        val percent = parts[1].toIntOrNull()?.coerceIn(0, 100) ?: return null
        return DesktopSavedProgress(parts[0], percent)
    }

    fun write(book: DesktopBook, chapterId: String, percent: Int) {
        preferences.put(book.key, "$chapterId|${percent.coerceIn(0, 100)}")
        preferences.flush()
    }
}

data class DesktopSavedProgress(val chapterId: String, val percent: Int)

class DesktopPreferencesStore(
    private val preferences: Preferences = Preferences.userRoot().node("io/github/jiangyuyi/mixn/settings"),
) {
    var fontName: String
        get() = preferences.get("font", "Serif")
        set(value) = preferences.put("font", value)

    var fontSize: Int
        get() = preferences.getInt("fontSize", 20)
        set(value) = preferences.putInt("fontSize", value.coerceIn(14, 32))

    var readerMode: String
        get() = preferences.get("readerMode", "scroll")
        set(value) = preferences.put("readerMode", value)

    var lineHeight: Double
        get() = preferences.getDouble("lineHeight", 1.75).coerceIn(1.2, 2.2)
        set(value) = preferences.putDouble("lineHeight", value.coerceIn(1.2, 2.2))

    var horizontalPadding: Int
        get() = preferences.getInt("horizontalPadding", 34).coerceIn(16, 96)
        set(value) = preferences.putInt("horizontalPadding", value.coerceIn(16, 96))

    var readerTheme: String
        get() = preferences.get("readerTheme", "sepia")
        set(value) = preferences.put("readerTheme", value)

    var showProgressBar: Boolean
        get() = preferences.getBoolean("showProgressBar", true)
        set(value) = preferences.putBoolean("showProgressBar", value)

    var downloadDirectory: String
        get() = preferences.get("downloadDirectory", "")
        set(value) = preferences.put("downloadDirectory", value)

    fun flush() = preferences.flush()
}

fun DesktopBook.displayLabel(): String = buildString {
    append(title)
    author.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
    append("  [").append(if (sourceId == DESKTOP_KINGDOM_SOURCE_ID) "轻之国度" else "轻书架").append(']')
}
