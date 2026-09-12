package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import org.junit.Assert.assertEquals
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
    fun `locked chapters are excluded from downloaded progress`() {
        val novelKey = NovelKey("test", "book")
        val volumeKey = VolumeKey("test", "v1")
        val locked = chapter(novelKey, volumeKey, "locked").copy(locked = true)
        val open = chapter(novelKey, volumeKey, "open")
        val record = OfflineBookRecord(
            novel = NovelSummary(novelKey, "测试", volumeCount = 1),
            chapters = listOf(locked, open),
            downloadedChapterIds = setOf("locked", "open"),
        )

        assertEquals(1, record.completedChapters)
        assertEquals(1, record.totalChapters)
    }

    private fun chapter(novelKey: NovelKey, volumeKey: VolumeKey, id: String) = ChapterSummary(
        key = ChapterKey("test", id),
        novelKey = novelKey,
        volumeKey = volumeKey,
        title = id,
    )
}
