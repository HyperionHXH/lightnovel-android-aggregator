package io.github.jiangyuyi.lightnovel.feature.reader

internal sealed interface ReaderBlock {
    data class Heading(val text: String) : ReaderBlock
    data class Paragraph(val text: String, val firstLineIndent: Boolean = true) : ReaderBlock
    data class Illustration(val url: String, val width: Int? = null, val height: Int? = null) : ReaderBlock
}

internal object ReaderContentParser {
    private val paragraphRegex = Regex("<p([^>]*)>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val imageRegex = Regex("<img\\b([^>]*)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagRegex = Regex("<[^>]+>")
    private val resourceTagRegex = Regex("\\[res][^]]*?\\[/res]", RegexOption.IGNORE_CASE)

    fun parse(bodyHtml: String, bodyText: String): List<ReaderBlock> {
        val htmlBlocks = parseHtml(bodyHtml)
        if (htmlBlocks.isNotEmpty()) return htmlBlocks

        return bodyText
            .replace(resourceTagRegex, "\n【插图暂时无法加载】\n")
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { ReaderBlock.Paragraph(it) }
            .ifEmpty { listOf(ReaderBlock.Paragraph("本章暂无正文", firstLineIndent = false)) }
    }

    private fun parseHtml(html: String): List<ReaderBlock> {
        if (html.isBlank()) return emptyList()
        val blocks = buildList {
            paragraphRegex.findAll(html).forEach { paragraph ->
                val attributes = paragraph.groupValues[1]
                val content = paragraph.groupValues[2]
                val indent = attributes.contains("text-indent", ignoreCase = true) ||
                    attributes.contains("ln-paragraph--indent", ignoreCase = true)
                appendParagraphContent(content, indent)
            }
        }
        return blocks.ifEmpty {
            buildList { appendParagraphContent(html, firstLineIndent = false) }
        }
    }

    private fun MutableList<ReaderBlock>.appendParagraphContent(content: String, firstLineIndent: Boolean) {
        var cursor = 0
        var firstText = true
        imageRegex.findAll(content).forEach { image ->
            addText(content.substring(cursor, image.range.first), firstLineIndent && firstText)
            firstText = false
            image.toIllustration()?.let(::add)
            cursor = image.range.last + 1
        }
        addText(content.substring(cursor), firstLineIndent && firstText)
    }

    private fun MutableList<ReaderBlock>.addText(raw: String, firstLineIndent: Boolean) {
        val text = raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(tagRegex, "")
            .decodeHtmlEntities()
            .trim()
        if (text.isNotBlank()) add(ReaderBlock.Paragraph(text, firstLineIndent))
    }

    private fun MatchResult.toIllustration(): ReaderBlock.Illustration? {
        val attributes = groupValues[1]
        val src = attributes.attribute("src")?.decodeHtmlEntities()?.trim().orEmpty()
        if (!src.startsWith("https://")) return null
        return ReaderBlock.Illustration(
            url = src,
            width = attributes.attribute("img-width")?.toIntOrNull() ?: attributes.attribute("width")?.toIntOrNull(),
            height = attributes.attribute("img-height")?.toIntOrNull() ?: attributes.attribute("height")?.toIntOrNull(),
        )
    }

    private fun String.attribute(name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.get(1)

    private fun String.decodeHtmlEntities(): String {
        var value = this
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
        value = Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE).replace(value) { match ->
            val raw = match.groupValues[1]
            val codePoint = if (raw.startsWith("x", ignoreCase = true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            codePoint?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: match.value
        }
        return value
    }
}
