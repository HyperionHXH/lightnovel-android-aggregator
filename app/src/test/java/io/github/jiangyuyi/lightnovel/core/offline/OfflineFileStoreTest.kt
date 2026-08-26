package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `manifest and chapters survive a new store instance`() = runTest {
        val root = temporaryFolder.newFolder("offline")
        val first = OfflineFileStore(root)
        val novelKey = NovelKey("source", "book/with:unsafe-path")
        val chapterKey = ChapterKey("source", "chapter/1")
        val volumeKey = VolumeKey("source", "volume")
        val chapter = ChapterSummary(chapterKey, novelKey, volumeKey, "第一章")
        val record = OfflineBookRecord(
            novel = NovelSummary(novelKey, "测试书"),
            chapters = listOf(chapter),
            downloadedChapterIds = setOf(chapterKey.remoteId),
            status = OfflineDownloadStatus.COMPLETE,
            updatedAtMillis = 12,
        )
        val content = ChapterContent(chapter, "测试书", "正文", "内容")

        first.writeBook(record)
        first.writeChapter(novelKey, content)
        val reopened = OfflineFileStore(root)

        assertEquals(record, reopened.readBook(novelKey))
        assertEquals(content, reopened.readChapter(novelKey, chapterKey))
        assertEquals(listOf(record), reopened.listBooks())
        assertTrue(root.listFiles().orEmpty().single().name.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `deleting one book does not remove another book`() = runTest {
        val root = temporaryFolder.newFolder("offline-delete")
        val store = OfflineFileStore(root)
        val first = NovelSummary(NovelKey("source", "1"), "第一本")
        val second = NovelSummary(NovelKey("source", "2"), "第二本")
        store.writeBook(OfflineBookRecord(first))
        store.writeBook(OfflineBookRecord(second))

        store.deleteBook(first.key)

        assertNull(store.readBook(first.key))
        assertEquals(second, store.readBook(second.key)?.novel)
        assertFalse(root.listFiles().isNullOrEmpty())
    }
}
