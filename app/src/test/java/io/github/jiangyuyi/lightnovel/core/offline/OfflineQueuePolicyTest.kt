package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQueuePolicyTest {
    @Test
    fun `network policy change reschedules only queued downloads and keeps selected volume`() {
        val queued = record("queued", OfflineDownloadStatus.QUEUED, selectedVolumeId = "volume-1")
        val records = listOf(
            queued,
            record("downloading", OfflineDownloadStatus.DOWNLOADING),
            record("complete", OfflineDownloadStatus.COMPLETE),
            record("failed", OfflineDownloadStatus.FAILED),
        )

        val specs = pendingOfflineWorkAfterNetworkPolicyChange(records, wifiOnly = false)

        assertEquals(
            listOf(
                OfflineWorkSpec(
                    novelKey = queued.novel.key,
                    selectedVolumeId = "volume-1",
                    wifiOnly = false,
                ),
            ),
            specs,
        )
    }

    private fun record(
        remoteId: String,
        status: OfflineDownloadStatus,
        selectedVolumeId: String? = null,
    ) = OfflineBookRecord(
        novel = NovelSummary(NovelKey("source", remoteId), "Book $remoteId"),
        selectedVolumeId = selectedVolumeId,
        status = status,
    )
}
