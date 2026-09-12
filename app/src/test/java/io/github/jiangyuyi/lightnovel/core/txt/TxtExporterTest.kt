package io.github.jiangyuyi.lightnovel.core.txt

import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtExporterTest {
    @Test
    fun `exports downloaded chapters as utf8 text and skips unavailable chapters`() = runTest {
        val novelKey = NovelKey("source", "book")
        val volumeKey = VolumeKey("source", "volume")
        val first = ChapterSummary(ChapterKey("source", "first"), novelKey, volumeKey, "第一章", order = 1)
        val second = ChapterSummary(ChapterKey("source", "second"), novelKey, volumeKey, "第二章", order = 2)
        val book = OfflineBookRecord(
            novel = NovelSummary(novelKey, "测试书", authors = listOf("作者")),
            chapters = listOf(first, second),
            downloadedChapterIds = setOf(first.key.remoteId, second.key.remoteId),
            status = OfflineDownloadStatus.COMPLETE,
        )
        val output = ByteArrayOutputStream()
        val result = TxtExporter().export(
            book,
            chapterReader = { key ->
                ChapterContent(
                    chapter = if (key == first.key) first else second,
                    novelTitle = "测试书",
                    volumeTitle = "第一卷",
                    bodyText = if (key == first.key) "正文一" else "",
                    bodyHtml = if (key == second.key) "<p>正文二 &amp; <b>加粗</b></p><img src=\"x\"/>" else "",
                )
            },
            output = output,
        )

        val text = output.toString(Charsets.UTF_8.name())
        assertEquals(2, result.exportedChapters)
        assertTrue(text.contains("测试书\n作者：作者"))
        assertTrue(text.contains("第一章"))
        assertTrue(text.contains("正文二 & 加粗"))
        assertTrue(text.contains("[插图]"))
    }

    @Test
    fun `html fallback decodes common entities and preserves paragraphs`() {
        val text = TxtExporter().htmlToText("<p>甲&nbsp;&amp;乙</p><p>丙<br/>丁</p>")
        assertEquals("甲 &乙\n\n丙\n丁", text)
    }
}
