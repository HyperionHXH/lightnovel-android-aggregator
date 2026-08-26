package io.github.jiangyuyi.lightnovel.core.local

import kotlinx.serialization.Serializable

@Serializable
enum class LocalBookFormat(val label: String) {
    EPUB("EPUB"),
    HTML("HTML"),
    FB2("FB2"),
    TXT("TXT"),
}

@Serializable
data class LocalBookRecord(
    val id: String,
    val uri: String,
    val title: String,
    val author: String = "",
    val format: LocalBookFormat,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0,
    val chapterCount: Int = 1,
    val available: Boolean = true,
)

data class LocalChapterRef(
    val id: String,
    val title: String,
    val path: String,
)

data class LocalBookDocument(
    val record: LocalBookRecord,
    val chapters: List<LocalChapterRef>,
    internal val epubEntries: Map<String, ByteArray> = emptyMap(),
)

data class LocalChapterContent(
    val chapter: LocalChapterRef,
    val text: String,
)
