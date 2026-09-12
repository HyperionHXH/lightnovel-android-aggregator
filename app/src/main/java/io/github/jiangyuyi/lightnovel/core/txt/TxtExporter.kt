package io.github.jiangyuyi.lightnovel.core.txt

import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class TxtExportResult(
    val exportedChapters: Int,
    val skippedChapters: Int,
)

data class TxtExportProgress(
    val completed: Int,
    val total: Int,
)

/** Writes downloaded chapters as a UTF-8 plain-text book for external readers. */
class TxtExporter {
    suspend fun export(
        book: OfflineBookRecord,
        chapterReader: suspend (ChapterKey) -> ChapterContent?,
        output: OutputStream,
        onProgress: (TxtExportProgress) -> Unit = {},
    ): TxtExportResult {
        val candidates = book.chapters
            .asSequence()
            .filterNot { it.locked }
            .filter { it.key.remoteId in book.downloadedChapterIds }
            .sortedWith(compareBy({ it.order }, { it.key.remoteId }))
            .toList()
        val chapters = buildList {
            onProgress(TxtExportProgress(0, candidates.size))
            candidates.forEach { chapter ->
                coroutineContext.ensureActive()
                chapterReader(chapter.key)?.let { content -> add(chapter to content) }
                onProgress(TxtExportProgress(size, candidates.size))
            }
        }
        if (chapters.isEmpty()) error("没有可导出的离线章节")

        val text = buildString {
            append(book.novel.title.trim().ifBlank { "未命名小说" })
            book.novel.authors.joinToString("、").trim().takeIf(String::isNotBlank)?.let {
                append("\n作者：").append(it)
            }
            append("\n\n")
            chapters.forEachIndexed { index, (chapter, content) ->
                coroutineContext.ensureActive()
                if (index > 0) append("\n\n")
                append("【").append(content.volumeTitle.ifBlank { "正文" }).append("】\n")
                append(chapter.title).append("\n\n")
                append(plainText(content))
            }
            append('\n')
        }
        output.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        return TxtExportResult(
            exportedChapters = chapters.size,
            skippedChapters = book.chapters.size - chapters.size,
        )
    }

    private fun plainText(content: ChapterContent): String {
        val text = content.bodyText.trim()
        if (text.isNotBlank()) return text
        return htmlToText(content.bodyHtml)
    }

    internal fun htmlToText(html: String): String = html
        .replace(Regex("(?i)<img\\b[^>]*>"), "\n[插图]\n")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)<(p|div|h[1-6]|li)\\b[^>]*>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
