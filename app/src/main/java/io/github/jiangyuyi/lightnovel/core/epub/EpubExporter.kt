package io.github.jiangyuyi.lightnovel.core.epub

import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class EpubExportResult(
    val exportedChapters: Int,
    val skippedChapters: Int,
)

data class EpubExportProgress(
    val completed: Int,
    val total: Int,
)

private data class EpubAsset(
    val path: String,
    val mediaType: String,
    val bytes: ByteArray,
)

class EpubExporter {
    suspend fun export(
        book: OfflineBookRecord,
        chapterReader: suspend (ChapterKey) -> ChapterContent?,
        output: OutputStream,
        onProgress: (EpubExportProgress) -> Unit = {},
        coverReader: suspend (String) -> ByteArray? = { null },
        assetReader: suspend (String) -> ByteArray? = { null },
    ): EpubExportResult {
        val candidates = book.chapters
            .asSequence()
            .filterNot { it.locked }
            .filter { it.key.remoteId in book.downloadedChapterIds }
            .sortedWith(compareBy({ it.order }, { it.key.remoteId }))
            .toList()
        val chapters = buildList {
            onProgress(EpubExportProgress(0, candidates.size))
            candidates.forEach { chapter ->
                coroutineContext.ensureActive()
                chapterReader(chapter.key)?.let { content -> add(chapter to content) }
                onProgress(EpubExportProgress(size, candidates.size))
            }
        }
        if (chapters.isEmpty()) error("没有可导出的离线章节")
        val cover = book.novel.coverUrl
            ?.takeIf(String::isNotBlank)
            ?.let { url -> runCatching { coverReader(url) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { bytes -> EpubAsset(coverPath(bytes), mediaType(bytes), bytes) }
        val assets = linkedMapOf<String, EpubAsset>()
        cover?.let { assets[it.path] = it }
        val rewrittenChapters = chapters.map { (chapter, content) ->
            val body = content.bodyHtml.trim().ifBlank {
                content.bodyText
                    .split("\n")
                    .joinToString("\n") { paragraph -> "<p>${escapeXml(paragraph)}</p>" }
            }
            chapter to rewriteImages(body, assetReader, assets)
        }

        ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
            putStored(zip, MIMETYPE_PATH, MIMETYPE)
            putText(zip, "META-INF/container.xml", containerXml())
            putText(zip, "OEBPS/styles.css", STYLESHEET)
            assets.values.forEach { asset -> putBytes(zip, asset.path, asset.bytes) }

            rewrittenChapters.forEachIndexed { index, (chapter, body) ->
                coroutineContext.ensureActive()
                putText(
                    zip,
                    chapterPath(index),
                    chapterXhtml(book.novel.title, chapter.title, body),
                )
            }
            putText(zip, "OEBPS/nav.xhtml", navigationXhtml(book.novel.title, chapters))
            putText(zip, "OEBPS/content.opf", contentOpf(book, chapters.size, assets.values.toList()))
        }

        return EpubExportResult(
            exportedChapters = chapters.size,
            skippedChapters = book.chapters.size - chapters.size,
        )
    }

    private fun putText(zip: ZipOutputStream, path: String, value: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(value.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun putStored(zip: ZipOutputStream, path: String, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        val checksum = CRC32().apply { update(bytes) }
        zip.putNextEntry(
            ZipEntry(path).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                crc = checksum.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, path: String, value: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(value)
        zip.closeEntry()
    }

    private fun chapterXhtml(bookTitle: String, chapterTitle: String, body: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">
            |<head><title>${escapeXml(chapterTitle)}</title><link rel="stylesheet" type="text/css" href="styles.css" /></head>
            |<body><h1>${escapeXml(bookTitle)}</h1><h2>${escapeXml(chapterTitle)}</h2>$body</body>
            |</html>
        """.trimMargin()
    }

    private fun navigationXhtml(
        title: String,
        chapters: List<Pair<io.github.jiangyuyi.lightnovel.core.source.ChapterSummary, ChapterContent>>,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
            |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
            |<head><title>${escapeXml(title)}</title></head><body><nav epub:type="toc" id="toc"><h1>${escapeXml(title)}</h1><ol>
        """.trimMargin())
        chapters.forEachIndexed { index, (chapter, _) ->
            append("<li><a href=\"${chapterPath(index)}\">${escapeXml(chapter.title)}</a></li>")
        }
        append("</ol></nav></body></html>")
    }

    private fun contentOpf(book: OfflineBookRecord, chapterCount: Int, assets: List<EpubAsset>): String = buildString {
        val title = escapeXml(book.novel.title)
        val authors = book.novel.authors.ifEmpty { listOf("未知作者") }
        append("""<?xml version="1.0" encoding="UTF-8"?>
            |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">${identifier(book)}</dc:identifier><dc:title>$title</dc:title>
        """.trimMargin())
        authors.forEach { author -> append("<dc:creator>${escapeXml(author)}</dc:creator>") }
        append("""</metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="styles.css" media-type="text/css"/>""")
        assets.forEachIndexed { index, asset ->
            val coverProperty = if (index == 0 && asset.path.contains("/cover.")) " properties=\"cover-image\"" else ""
            append("<item id=\"asset-$index\" href=\"${asset.path.removePrefix("OEBPS/")}\" media-type=\"${asset.mediaType}\"$coverProperty/>")
        }
        repeat(chapterCount) { index ->
            append("<item id=\"chapter-$index\" href=\"${chapterPath(index)}\" media-type=\"application/xhtml+xml\"/>")
        }
        append("</manifest><spine>")
        repeat(chapterCount) { index -> append("<itemref idref=\"chapter-$index\"/>") }
        append("</spine></package>")
    }

    private fun identifier(book: OfflineBookRecord): String =
        "urn:noval:${book.novel.key.sourceId}:${book.novel.key.remoteId}".let(::escapeXml)

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun chapterPath(index: Int): String = "OEBPS/chapter-${"%04d".format(index + 1)}.xhtml"

    private fun mediaType(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(StandardCharsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun coverPath(bytes: ByteArray): String =
        "OEBPS/images/cover${extension(mediaType(bytes))}"

    private suspend fun rewriteImages(
        body: String,
        assetReader: suspend (String) -> ByteArray?,
        assets: MutableMap<String, EpubAsset>,
    ): String {
        val pattern = Regex("(?i)(<img\\b[^>]*\\bsrc\\s*=\\s*[\\\"'])([^\\\"']+)([\\\"'])")
        var rewritten = body
        pattern.findAll(body).forEach { match ->
            val url = match.groupValues[2].trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            val bytes = runCatching { assetReader(url) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val path = "OEBPS/images/image-${hash(url)}${extension(mediaType(bytes))}"
            assets.putIfAbsent(path, EpubAsset(path, mediaType(bytes), bytes))
            rewritten = rewritten.replace(match.groupValues[0], "${match.groupValues[1]}images/image-${hash(url)}${extension(mediaType(bytes))}${match.groupValues[3]}")
        }
        return rewritten
    }

    private fun extension(mediaType: String): String = when (mediaType) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }

    private fun hash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

    private fun containerXml() = """<?xml version="1.0" encoding="UTF-8"?>
        |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
    """.trimMargin()

    private companion object {
        const val MIMETYPE_PATH = "mimetype"
        const val MIMETYPE = "application/epub+zip"
        const val STYLESHEET = "body { line-height: 1.65; } h1, h2 { text-align: center; } p { text-indent: 2em; }"
    }
}
