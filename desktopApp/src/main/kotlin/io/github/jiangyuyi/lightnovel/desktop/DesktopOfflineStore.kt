package io.github.jiangyuyi.lightnovel.desktop

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopOfflineStore(
    private val rootDirectory: File = File(System.getProperty("user.home"), ".mixn/offline"),
) {
    fun save(book: DesktopBook, content: DesktopChapterContent) {
        val directory = File(rootDirectory, safe(book.key))
        directory.mkdirs()
        val temporary = File(directory, "${safe(content.chapter.remoteId)}.tmp")
        val target = File(directory, "${safe(content.chapter.remoteId)}.html")
        temporary.writeText(
            buildString {
                append("<!-- title: ").append(content.chapter.title.replace("--", "")).append(" -->\n")
                append(content.bodyHtml.ifBlank { content.bodyText })
            },
            StandardCharsets.UTF_8,
        )
        runCatching {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun has(book: DesktopBook, chapter: DesktopChapter): Boolean = File(rootDirectory, "${safe(book.key)}/${safe(chapter.remoteId)}.html").isFile

    fun exportEpub(book: DesktopBook, chapters: List<DesktopChapter>, destination: File) {
        val directory = File(rootDirectory, safe(book.key))
        val available = chapters.filter { !it.locked && File(directory, "${safe(it.remoteId)}.html").isFile }
        require(available.isNotEmpty()) { "没有可导出的已下载章节" }
        val temporary = File(destination.parentFile ?: rootDirectory, ".${destination.name}.tmp")
        temporary.parentFile?.mkdirs()
        ZipOutputStream(temporary.outputStream()).use { zip ->
            writeEntry(zip, "mimetype", "application/epub+zip", compress = false)
            writeEntry(zip, "META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            val manifest = available.mapIndexed { index, chapter ->
                "<item id=\"chapter-$index\" href=\"chapter-$index.xhtml\" media-type=\"application/xhtml+xml\"/>"
            }.joinToString("\n")
            val spine = available.indices.joinToString("\n") { index -> "<itemref idref=\"chapter-$index\"/>" }
            writeEntry(zip, "OEBPS/content.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">${escapeXml(book.key)}</dc:identifier><dc:title>${escapeXml(book.title)}</dc:title></metadata>
                  <manifest>$manifest</manifest><spine>$spine</spine>
                </package>
            """.trimIndent())
            available.forEachIndexed { index, chapter ->
                val body = File(directory, "${safe(chapter.remoteId)}.html").readText(StandardCharsets.UTF_8)
                writeEntry(zip, "OEBPS/chapter-$index.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN"><head><title>${escapeXml(chapter.title)}</title></head><body><h1>${escapeXml(chapter.title)}</h1>$body</body></html>
                """.trimIndent())
            }
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, value: String, compress: Boolean = true) {
        val entry = ZipEntry(path)
        if (!compress) entry.method = ZipEntry.STORED
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (!compress) { entry.size = bytes.size.toLong(); entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value }
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
    }

    private fun safe(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
