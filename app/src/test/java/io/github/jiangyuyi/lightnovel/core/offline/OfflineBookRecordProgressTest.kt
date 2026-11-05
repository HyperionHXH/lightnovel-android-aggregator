package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineBookRecordProgressTest {
    @Test
    fun `completed volumes require every unlocked chapter`() {
        val novelKey = NovelKey("test", "book")
        val firstVolume = VolumeKey("test", "v1")
        val secondVolume = VolumeKey("test", "v2")
        val chapters = listOf(
            chapter(novelKey, firstVolume, "c1"),
            chapter(novelKey, firstVolume, "c2"),
            chapter(novelKey, secondVolume, "c3"),
        )
        val record = OfflineBookRecord(
            novel = NovelSummary(novelKey, "测试", volumeCount = 2),
            volumes = listOf(
                VolumeSummary(firstVolume, novelKey, "第一卷", 2),
                VolumeSummary(secondVolume, novelKey, "第二卷", 1),
            ),
            chapters = chapters,
            downloadedChapterIds = setOf("c1", "c2"),
        )

        assertEquals(1, record.completedVolumes)
        assertEquals(2, record.totalVolumes)
        assertEquals(2, record.completedChapters)
        assertEquals(3, record.totalChapters)
    }

    @Test
    fun `locked chapters without checkpoints are excluded from downloaded progress`() {
        val novelKey = NovelKey("test", "book")
        val volumeKey = VolumeKey("test", "v1")
        val locked = chapter(novelKey, volumeKey, "locked").copy(locked = true)
        val open = chapter(novelKey, volumeKey, "open")
        val record = OfflineBookRecord(
            novel = NovelSummary(novelKey, "测试", volumeCount = 1),
            chapters = listOf(locked, open),
            downloadedChapterIds = setOf("open"),
        )

        assertEquals(1, record.completedChapters)
        assertEquals(1, record.totalChapters)
    }

    @Test
    fun `locked volume must cover refreshed count even with duplicate known chapter keys`() {
        val key = NovelKey("test", "book")
        val openVolume = VolumeSummary(VolumeKey("test", "open"), key, "正文", 1)
        val lockedVolume = VolumeSummary(VolumeKey("test", "locked"), key, "锁定卷", 1)
        val open = chapter(key, openVolume.key, "open")
        val locked = chapter(key, lockedVolume.key, "locked").copy(locked = true)
        val record = OfflineBookRecord(
            novel = NovelSummary(key, "测试"), volumes = listOf(openVolume, lockedVolume),
            chapters = listOf(open, locked), downloadedChapterIds = setOf("open"),
            completeDirectoryVolumeIds = setOf("open", "locked"),
        )
        assertTrue(record.isFullyDownloaded())
        assertFalse(record.isFullyDownloaded(listOf(openVolume, lockedVolume.copy(chapterCount = 2)), listOf(locked)))
    }

    @Test
    fun `scanned empty volumes allow complete books but cannot complete an unreadable book`() {
        val key = NovelKey("test", "book")
        val openVolume = VolumeSummary(VolumeKey("test", "open"), key, "正文", 1)
        val emptyVolume = VolumeSummary(VolumeKey("test", "empty"), key, "空卷", 0)
        val open = chapter(key, openVolume.key, "open")
        val record = OfflineBookRecord(
            novel = NovelSummary(key, "测试"), volumes = listOf(openVolume, emptyVolume),
            chapters = listOf(open), downloadedChapterIds = setOf("open"),
            completeDirectoryVolumeIds = setOf("open", "empty"),
        )
        assertTrue(record.isFullyDownloaded())
        assertFalse(record.copy(completeDirectoryVolumeIds = setOf("open")).isFullyDownloaded())
        assertFalse(record.copy(chapters = emptyList(), downloadedChapterIds = emptySet()).isFullyDownloaded())
        assertTrue(record.isFullyDownloaded(knownChapters = listOf(open.copy(locked = true))))
    }

    @Test
    fun `retry expands beyond selected volume when retained failures belong elsewhere`() {
        val key = NovelKey("test", "book")
        val first = VolumeKey("test", "first")
        val second = VolumeKey("test", "second")
        val record = OfflineBookRecord(
            novel = NovelSummary(key, "测试"), selectedVolumeId = first.remoteId,
            chapters = listOf(chapter(key, first, "one"), chapter(key, second, "two")),
            failedChapters = mapOf("two" to "网络错误"), status = OfflineDownloadStatus.FAILED,
        )
        assertNull(record.retryVolumeKey())
        assertEquals(first, record.copy(failedChapters = mapOf("one" to "错误")).retryVolumeKey())
        assertNull(record.copy(failedChapters = mapOf("unknown" to "错误")).retryVolumeKey())
    }

    private fun chapter(novelKey: NovelKey, volumeKey: VolumeKey, id: String) = ChapterSummary(
        key = ChapterKey("test", id),
        novelKey = novelKey,
        volumeKey = volumeKey,
        title = id,
    )
}
