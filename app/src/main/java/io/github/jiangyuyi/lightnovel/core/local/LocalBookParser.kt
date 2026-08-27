package io.github.jiangyuyi.lightnovel.core.local

import android.content.ContentResolver
import android.net.Uri
import android.text.Html
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class LocalBookParser(private val resolver: ContentResolver) {
    suspend fun parse(uri: Uri): LocalBookDocument = withContext(Dispatchers.IO) {
        val format = formatOf(uri)
        when (format) {
            LocalBookFormat.TXT -> parseTxt(uri)
            LocalBookFormat.HTML -> parseHtml(uri)
            LocalBookFormat.FB2 -> parseFb2(uri)
            LocalBookFormat.EPUB -> parseEpub(uri)
        }
    }

    suspend fun scan(uri: Uri, sizeBytes: Long, lastModified: Long): LocalBookRecord =
        withContext(Dispatchers.IO) {
            val format = formatOf(uri)
            when (format) {
                LocalBookFormat.TXT -> LocalBookRecord(
                    id = stableId(uri.toString()),
                    uri = uri.toString(),
                    title = displayName(uri).substringBeforeLast('.', displayName(uri)),
                    format = format,
                    sizeBytes = sizeBytes,
                    lastModified = lastModified,
                )
                LocalBookFormat.HTML -> {
                    val document = parseHtml(uri)
                    document.record.copy(sizeBytes = sizeBytes, lastModified = lastModified)
                }
                LocalBookFormat.FB2 -> {
                    val document = parseFb2(uri)
                    document.record.copy(sizeBytes = sizeBytes, lastModified = lastModified)
                }
                LocalBookFormat.EPUB -> {
                    scanEpub(uri, sizeBytes, lastModified)
                }
            }
        }

    /** Builds a book card without opening the file contents. Metadata is filled in asynchronously. */
    fun stub(uri: Uri, sizeBytes: Long, lastModified: Long): LocalBookRecord {
        val format = formatOf(uri)
        val name = displayName(uri).substringBeforeLast('.', displayName(uri))
        return LocalBookRecord(
            id = stableId(uri.toString()),
            uri = uri.toString(),
            title = name,
            format = format,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            chapterCount = if (format == LocalBookFormat.TXT) 1 else 0,
        )
    }

    private fun parseTxt(uri: Uri): LocalBookDocument {
        val name = displayName(uri).substringBeforeLast('.', displayName(uri))
        val text = resolver.openInputStream(uri)?.use(::readText) ?: error("无法读取本地文件")
        val record = LocalBookRecord(stableId(uri.toString()), uri.toString(), name, format = LocalBookFormat.TXT)
        val chapter = LocalChapterRef("chapter-1", name, "text")
        return LocalBookDocument(record, listOf(chapter), mapOf("text" to text.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun parseHtml(uri: Uri): LocalBookDocument {
        val name = displayName(uri).substringBeforeLast('.', displayName(uri))
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取本地文件")
        val raw = bytes.toString(StandardCharsets.UTF_8)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(raw)?.groupValues?.getOrNull(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            ?.takeIf { it.isNotBlank() } ?: name
        val record = LocalBookRecord(stableId(uri.toString()), uri.toString(), title, format = LocalBookFormat.HTML)
        val chapter = LocalChapterRef("chapter-1", title, "html")
        return LocalBookDocument(record, listOf(chapter), mapOf("html" to bytes))
    }

    private fun parseFb2(uri: Uri): LocalBookDocument {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取本地文件")
        val xml = bytes.toString(StandardCharsets.UTF_8)
        val parser = newParser(xml)
        var bookTitle: String? = null
        var author = StringBuilder()
        var sectionDepth = 0
        var sectionTitle = ""
        var sectionText = StringBuilder()
        var sectionIndex = 0
        var captureTag: String? = null
        var capture = StringBuilder()
        val chapters = mutableListOf<LocalChapterRef>()
        val entries = linkedMapOf<String, ByteArray>()

        fun finishCapture(tag: String) {
            if (captureTag != tag) return
            val value = capture.toString().trim()
            when (tag) {
                "book-title" -> bookTitle = value.takeIf { it.isNotBlank() }
                "first-name", "middle-name", "last-name", "nickname" -> {
                    if (value.isNotBlank()) {
                        if (author.isNotEmpty()) author.append(' ')
                        author.append(value)
                    }
                }
                "section-title" -> sectionTitle = value
                "paragraph" -> if (value.isNotBlank()) {
                    if (sectionText.isNotEmpty()) sectionText.append("\n\n")
                    sectionText.append(value)
                }
            }
            captureTag = null
            capture = StringBuilder()
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfterLast(':').orEmpty()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        tag == "section" -> {
                            if (sectionDepth == 0) {
                                sectionTitle = ""
                                sectionText = StringBuilder()
                            }
                            sectionDepth++
                        }
                        tag == "book-title" && sectionDepth == 0 -> {
                            captureTag = tag
                            capture = StringBuilder()
                        }
                        tag in setOf("first-name", "middle-name", "last-name", "nickname") && sectionDepth == 0 -> {
                            captureTag = tag
                            capture = StringBuilder()
                        }
                        tag == "title" && sectionDepth == 1 -> {
                            captureTag = "section-title"
                            capture = StringBuilder()
                        }
                        tag == "p" && sectionDepth >= 1 && captureTag != "section-title" -> {
                            captureTag = "paragraph"
                            capture = StringBuilder()
                        }
                    }
                }
                XmlPullParser.TEXT -> if (captureTag != null) capture.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when {
                        tag == "title" && captureTag == "section-title" -> finishCapture("section-title")
                        tag == "p" && captureTag == "paragraph" -> finishCapture("paragraph")
                        else -> finishCapture(tag)
                    }
                    if (tag == "section") {
                        sectionDepth--
                        if (sectionDepth == 0 && sectionText.isNotBlank()) {
                            sectionIndex++
                            val id = "section-$sectionIndex"
                            val title = sectionTitle.ifBlank { "第 $sectionIndex 章" }
                            chapters += LocalChapterRef(id, title, id)
                            entries[id] = sectionText.toString().toByteArray(StandardCharsets.UTF_8)
                        }
                    }
                }
            }
        }
        if (chapters.isEmpty()) error("FB2 没有可读取的章节")
        val fallbackTitle = displayName(uri).substringBeforeLast('.', displayName(uri))
        val record = LocalBookRecord(
            id = stableId(uri.toString()),
            uri = uri.toString(),
            title = bookTitle?.ifBlank { null } ?: fallbackTitle,
            author = author.toString().trim(),
            format = LocalBookFormat.FB2,
            chapterCount = chapters.size,
        )
        return LocalBookDocument(record, chapters, entries)
    }

    private fun scanEpub(uri: Uri, sizeBytes: Long, lastModified: Long): LocalBookRecord {
        val metadata = readEpubMetadata(uri)
        val chapters = chapterPaths(metadata)
        if (chapters.isEmpty()) error("EPUB 没有可读取的章节")
        val fallbackTitle = displayName(uri).substringBeforeLast('.', displayName(uri))
        return LocalBookRecord(
            id = stableId(uri.toString()),
            uri = uri.toString(),
            title = metadata.title?.ifBlank { null } ?: fallbackTitle,
            author = metadata.author.orEmpty(),
            format = LocalBookFormat.EPUB,
            coverPath = metadata.coverPath,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            chapterCount = chapters.size,
        )
    }

    private fun parseEpub(uri: Uri): LocalBookDocument {
        val metadata = readEpubMetadata(uri)
        val chapterPaths = chapterPaths(metadata)
        val wanted = chapterPaths.toMutableSet().apply {
            metadata.coverPath?.let(::add)
            metadata.manifest.values
                .filter { it.mediaType.startsWith("image/", ignoreCase = true) }
                .mapTo(this) { resolvePath(metadata.base, it.href) }
        }
        val entries = resolver.openInputStream(uri)?.use { readZipEntries(it, wanted) }
            ?: error("无法读取 EPUB 文件")
        val chapters = chapterPaths.mapIndexedNotNull { index, path ->
            if (entries[path] == null) return@mapIndexedNotNull null
            LocalChapterRef(
                "chapter-${index + 1}",
                path.substringAfterLast('/').substringBeforeLast('.'),
                path,
            )
        }
        if (chapters.isEmpty()) error("EPUB 没有可读取的章节")
        val fallbackTitle = displayName(uri).substringBeforeLast('.', displayName(uri))
        val record = LocalBookRecord(
            id = stableId(uri.toString()),
            uri = uri.toString(),
            title = metadata.title?.ifBlank { null } ?: fallbackTitle,
            author = metadata.author.orEmpty(),
            format = LocalBookFormat.EPUB,
            coverPath = metadata.coverPath?.takeIf(entries::containsKey),
            chapterCount = chapters.size,
        )
        return LocalBookDocument(record, chapters, entries)
    }

    suspend fun readCover(uri: Uri, coverPath: String?): ByteArray? = withContext(Dispatchers.IO) {
        val path = coverPath?.takeIf(String::isNotBlank) ?: return@withContext null
        resolver.openInputStream(uri)?.use { input ->
            readZipEntries(input, setOf(path))[path]
        }
    }

    private fun readEpubMetadata(uri: Uri): EpubMetadata {
        var container: String? = null
        var rootFile: String? = null
        var opf: String? = null
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = normalizeEntryName(entry.name)
                    if (!entry.isDirectory && !name.startsWith("/") && !name.contains("../")) {
                        when {
                            name == "META-INF/container.xml" -> {
                                container = readCurrentZipEntry(zip).toString(StandardCharsets.UTF_8)
                                rootFile = parseRootFile(requireNotNull(container))
                            }
                            rootFile != null && name == rootFile -> {
                                opf = readCurrentZipEntry(zip).toString(StandardCharsets.UTF_8)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("无法读取 EPUB 文件")
        val containerXml = container ?: error("EPUB 缺少 container.xml")
        val resolvedRootFile = rootFile ?: parseRootFile(containerXml)
        val opfXml = opf ?: resolver.openInputStream(uri)?.use {
            readZipEntries(it, setOf(resolvedRootFile))[resolvedRootFile]
        }?.toString(StandardCharsets.UTF_8)
        val opfContent = opfXml ?: error("EPUB 缺少 OPF 文件")
        val metadata = parseOpf(opfContent)
        val base = resolvedRootFile.substringBeforeLast('/', "")
        return EpubMetadata(
            title = metadata.title,
            author = metadata.author,
            manifest = metadata.manifest,
            spine = metadata.spine,
            base = base,
            coverPath = metadata.coverHref?.let { resolvePath(base, it) },
        )
    }

    private fun chapterPaths(metadata: EpubMetadata): List<String> = metadata.spine.mapNotNull { id ->
        metadata.manifest[id]?.let { resolvePath(metadata.base, it.href) }
    }

    private fun readZipEntries(input: InputStream, wanted: Set<String>? = null): Map<String, ByteArray> {
        val normalizedWanted = wanted?.mapTo(hashSetOf(), ::normalizeEntryName)
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = normalizeEntryName(entry.name)
                if (name.startsWith("/") || name.contains("../")) continue
                if (normalizedWanted != null && name !in normalizedWanted) {
                    zip.closeEntry()
                    continue
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count <= 0) break
                    total += count
                    if (total > MAX_ENTRY_BYTES) error("EPUB 章节资源过大")
                    totalBytes += count
                    if (totalBytes > MAX_TOTAL_BYTES) error("EPUB 文件资源过大")
                    output.write(buffer, 0, count)
                }
                result[name] = output.toByteArray()
            }
        }
        return result
    }

    private fun readCurrentZipEntry(zip: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = zip.read(buffer)
            if (count <= 0) break
            if (output.size() + count > MAX_ENTRY_BYTES) error("EPUB 元数据资源过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readText(input: InputStream): String {
        val bytes = input.readBytes()
        val (charset, offset) = when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> StandardCharsets.UTF_8 to 3
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> StandardCharsets.UTF_16LE to 2
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> StandardCharsets.UTF_16BE to 2
            isUtf8(bytes) -> StandardCharsets.UTF_8 to 0
            else -> runCatching { Charset.forName("GB18030") }.getOrDefault(Charset.defaultCharset()) to 0
        }
        return bytes.copyOfRange(offset, bytes.size).toString(charset)
    }

    private fun parseRootFile(xml: String): String {
        val parser = newParser(xml)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path") ?: error("container.xml 缺少 rootfile")
            }
        }
        error("container.xml 缺少 rootfile")
    }

    private fun parseOpf(xml: String): OpfMetadata {
        val parser = newParser(xml)
        val manifest = linkedMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        var title: String? = null
        var author: String? = null
        var legacyCoverRef: String? = null
        var propertyCoverHref: String? = null
        var currentText: StringBuilder? = null
        var currentTag: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfterLast(':')) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                        val properties = parser.getAttributeValue(null, "properties").orEmpty()
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            manifest[id] = ManifestItem(
                                href = decodeHref(href),
                                title = null,
                                mediaType = mediaType,
                            )
                            if (properties.split(' ').contains("cover-image")) {
                                propertyCoverHref = decodeHref(href)
                            }
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let(spine::add)
                    "meta" -> {
                        if (parser.getAttributeValue(null, "name").equals("cover", ignoreCase = true)) {
                            legacyCoverRef = parser.getAttributeValue(null, "content")
                        }
                    }
                    "title", "creator" -> {
                        currentTag = parser.name.substringAfterLast(':')
                        currentText = StringBuilder()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> currentText?.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfterLast(':')
                    if (tag == currentTag) {
                        val value = currentText?.toString()?.trim().orEmpty()
                        if (tag == "title" && value.isNotBlank()) title = value
                        if (tag == "creator" && value.isNotBlank()) author = value
                        currentTag = null
                        currentText = null
                    }
                }
            }
        }
        val coverHref = legacyCoverRef?.let { reference ->
            manifest[reference]?.href
                ?: manifest.values.firstOrNull { item ->
                    item.href == reference || item.href.substringAfterLast('/') == reference.substringAfterLast('/')
                }?.href
        } ?: propertyCoverHref ?: manifest.values.firstOrNull { item ->
            item.mediaType.startsWith("image/") &&
                item.href.substringAfterLast('/').substringBeforeLast('.').equals("cover", ignoreCase = true)
        }?.href
        return OpfMetadata(title, author, manifest, spine, coverHref)
    }

    private fun newParser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(xml.reader())
    }

    fun displayNameOf(uri: Uri): String = resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "本地书籍"
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "本地书籍"

    private fun displayName(uri: Uri): String = displayNameOf(uri)

    fun formatOf(uri: Uri): LocalBookFormat = when (uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase()) {
        "epub" -> LocalBookFormat.EPUB
        "html", "htm", "xhtml" -> LocalBookFormat.HTML
        "fb2" -> LocalBookFormat.FB2
        "txt", "md", "markdown" -> LocalBookFormat.TXT
        else -> error("暂不支持的本地格式")
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024

        fun stableId(uri: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun resolvePath(base: String, href: String): String {
            val decoded = Uri.decode(href).substringBefore('#')
            val parts = (if (base.isBlank()) decoded else "$base/$decoded").split('/')
            val normalized = ArrayDeque<String>()
            parts.forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
                    else -> normalized.addLast(part)
                }
            }
            return normalized.joinToString("/")
        }

        private fun decodeHref(value: String): String = Uri.decode(value).substringBefore('#')

        private fun normalizeEntryName(value: String): String = value.replace('\\', '/')

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        private fun isUtf8(bytes: ByteArray): Boolean {
            var index = 0
            while (index < bytes.size) {
                val value = bytes[index].toInt() and 0xFF
                val width = when {
                    value <= 0x7F -> 1
                    value in 0xC2..0xDF -> 2
                    value in 0xE0..0xEF -> 3
                    value in 0xF0..0xF4 -> 4
                    else -> return false
                }
                if (index + width > bytes.size) return false
                for (offset in 1 until width) {
                    if ((bytes[index + offset].toInt() and 0xC0) != 0x80) return false
                }
                index += width
            }
            return true
        }
    }

    private data class OpfMetadata(
        val title: String?,
        val author: String?,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val coverHref: String?,
    )

    private data class ManifestItem(
        val href: String,
        val title: String?,
        val mediaType: String,
    )

    private data class EpubMetadata(
        val title: String?,
        val author: String?,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val base: String,
        val coverPath: String?,
    )
}

fun LocalBookDocument.chapterContent(chapter: LocalChapterRef): LocalChapterContent {
    val bytes = epubEntries[chapter.path] ?: error("找不到章节文件")
    val raw = bytes.toString(StandardCharsets.UTF_8)
    val blocks = mutableListOf<LocalContentBlock>()
    val tokenRegex = Regex("<p\\b[^>]*>.*?</p>|<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    var cursor = 0
    fun appendText(value: String) {
        val text = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim()
        if (text.isNotBlank()) blocks += LocalContentBlock.Paragraph(text)
    }
    tokenRegex.findAll(raw).forEach { token ->
        val before = raw.substring(cursor, token.range.first)
        if (token.value.startsWith("<img", ignoreCase = true)) {
            appendText(before)
            val src = Regex("\\bsrc\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
                .find(token.value)?.groupValues?.getOrNull(1)
            val path = src?.let { resolveRelativeAssetPath(chapter.path, it) }
            path?.let { epubEntries[it] }?.let { blocks += LocalContentBlock.Image(it) }
        } else {
            appendText(token.value)
        }
        cursor = token.range.last + 1
    }
    appendText(raw.substring(cursor))
    if (blocks.isEmpty()) blocks += LocalContentBlock.Paragraph("本章暂无正文")
    return LocalChapterContent(chapter, blocks)
}

private fun resolveRelativeAssetPath(chapterPath: String, href: String): String {
    val decoded = android.net.Uri.decode(href)
        .replace(Regex("\\s+"), "")
        .substringBefore('#')
        .replace('\\', '/')
    val parts = (chapterPath.substringBeforeLast('/', "") + "/" + decoded).split('/')
    val normalized = ArrayDeque<String>()
    parts.forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
            else -> normalized.addLast(part)
        }
    }
    return normalized.joinToString("/")
}
