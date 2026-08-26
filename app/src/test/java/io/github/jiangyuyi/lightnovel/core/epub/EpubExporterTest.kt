package io.github.jiangyuyi.lightnovel.core.epub

import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class EpubExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exports mimetype navigation metadata and downloaded chapters`() = runTest {
        val book = book()
        val first = book.chapters.first()
        val output = ByteArrayOutputStream()

        val result = EpubExporter().export(
            book = book,
            chapterReader = { key ->
                ChapterContent(first, book.novel.title, "第一卷", "第一段")
                    .takeIf { key == first.key }
            },
            output = output,
        )

        assertEquals(1, result.exportedChapters)
        val file = temporaryFolder.newFile("book.epub")
        file.writeBytes(output.toByteArray())
        ZipFile(file).use { zip ->
            assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype")).reader().readText())
            assertTrue(zip.getEntry("META-INF/container.xml") != null)
            assertTrue(zip.getEntry("OEBPS/content.opf") != null)
            assertTrue(zip.getEntry("OEBPS/nav.xhtml") != null)
            assertTrue(zip.getEntry("OEBPS/chapter-0001.xhtml") != null)
            assertTrue(zip.getEntry("OEBPS/chapter-0002.xhtml") == null)
        }
    }

    @Test
    fun `falls back to escaped text when chapter html is empty`() = runTest {
        val book = book().copy(chapters = book().chapters.take(1))
        val chapter = book.chapters.single()
        val output = ByteArrayOutputStream()
        EpubExporter().export(
            book = book,
            chapterReader = { ChapterContent(chapter, book.novel.title, "第一卷", "<安全> & 内容") },
            output = output,
        )

        val file = temporaryFolder.newFile("escaped.epub")
        file.writeBytes(output.toByteArray())
        ZipFile(file).use { zip ->
            val html = zip.getInputStream(zip.getEntry("OEBPS/chapter-0001.xhtml")).reader().readText()
            assertTrue(html.contains("&lt;安全&gt; &amp; 内容"))
        }
    }

    @Test
    fun `embeds a fetched cover and advertises cover image`() = runTest {
        val book = book().copy(novel = book().novel.copy(coverUrl = "https://example.test/cover.png"))
        val output = ByteArrayOutputStream()
        EpubExporter().export(
            book = book,
            chapterReader = { chapterKey ->
                ChapterContent(book.chapters.first { it.key == chapterKey }, book.novel.title, "第一卷", "正文")
            },
            output = output,
            coverReader = { byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) },
        )

        val file = temporaryFolder.newFile("cover.epub")
        file.writeBytes(output.toByteArray())
        ZipFile(file).use { zip ->
            assertTrue(zip.getEntry("OEBPS/images/cover.png") != null)
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).reader().readText()
            assertTrue(opf.contains("properties=\"cover-image\""))
        }
    }

    @Test
    fun `embeds chapter image and rewrites relative epub path`() = runTest {
        val book = book().copy(chapters = book().chapters.take(1))
        val chapter = book.chapters.single()
        val output = ByteArrayOutputStream()
        EpubExporter().export(
            book = book,
            chapterReader = {
                ChapterContent(
                    chapter,
                    book.novel.title,
                    "第一卷",
                    "正文",
                    bodyHtml = "<p>正文</p><img src=\"https://example.test/a.png\" />",
                )
            },
            output = output,
            assetReader = { byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) },
        )

        val file = temporaryFolder.newFile("image.epub")
        file.writeBytes(output.toByteArray())
        ZipFile(file).use { zip ->
            val chapterHtml = zip.getInputStream(zip.getEntry("OEBPS/chapter-0001.xhtml")).reader().readText()
            assertTrue(chapterHtml.contains("images/image-"))
            assertTrue(zip.entries().asSequence().any { it.name.startsWith("OEBPS/images/image-") })
        }
    }

    private fun book(): OfflineBookRecord {
        val novelKey = NovelKey("source", "book")
        val volumeKey = VolumeKey("source", "volume")
        val firstKey = ChapterKey("source", "first")
        val secondKey = ChapterKey("source", "second")
        return OfflineBookRecord(
            novel = NovelSummary(novelKey, "测试书", authors = listOf("作者")),
            chapters = listOf(
                ChapterSummary(firstKey, novelKey, volumeKey, "第一章", order = 1),
                ChapterSummary(secondKey, novelKey, volumeKey, "第二章", order = 2),
            ),
            downloadedChapterIds = setOf(firstKey.remoteId),
            status = OfflineDownloadStatus.COMPLETE,
        )
    }
}
