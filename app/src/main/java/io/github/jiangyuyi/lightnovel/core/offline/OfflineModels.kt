package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.source.VolumeSummary
import io.github.jiangyuyi.lightnovel.core.epub.EpubExportResult
import io.github.jiangyuyi.lightnovel.core.epub.EpubExportProgress
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class OfflineDownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETE,
    FAILED,
}

@Serializable
data class OfflineBookRecord(
    val novel: NovelSummary,
    val selectedVolumeId: String? = null,
    val volumes: List<VolumeSummary> = emptyList(),
    val chapters: List<ChapterSummary> = emptyList(),
    val downloadedChapterIds: Set<String> = emptySet(),
    val failedChapters: Map<String, String> = emptyMap(),
    val status: OfflineDownloadStatus = OfflineDownloadStatus.QUEUED,
    val error: String? = null,
    val updatedAtMillis: Long = 0,
) {
    val totalChapters: Int
        get() = chapters.count { !it.locked }

    val completedChapters: Int
        get() = downloadedChapterIds.count { remoteId -> chapters.any { it.key.remoteId == remoteId } }
}

internal data class OfflineWorkSpec(
    val novelKey: NovelKey,
    val selectedVolumeId: String?,
    val wifiOnly: Boolean,
)

internal fun pendingOfflineWorkAfterNetworkPolicyChange(
    records: List<OfflineBookRecord>,
    wifiOnly: Boolean,
): List<OfflineWorkSpec> = records
    .filter { it.status == OfflineDownloadStatus.QUEUED }
    .map { record ->
        OfflineWorkSpec(
            novelKey = record.novel.key,
            selectedVolumeId = record.selectedVolumeId,
            wifiOnly = wifiOnly,
        )
    }

interface OfflineLibraryAccess {
    val books: StateFlow<List<OfflineBookRecord>>
    val wifiOnly: StateFlow<Boolean>
    fun setWifiOnly(enabled: Boolean)
    fun enqueue(novel: NovelSummary, volumeKey: VolumeKey? = null)
    fun retry(record: OfflineBookRecord)
    fun delete(key: NovelKey)
    suspend fun readBook(key: NovelKey): OfflineBookRecord?
    suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent?
    suspend fun exportEpub(
        key: NovelKey,
        output: OutputStream,
        onProgress: (EpubExportProgress) -> Unit = {},
    ): EpubExportResult? = null
}

object EmptyOfflineLibraryAccess : OfflineLibraryAccess {
    override val books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
    override val wifiOnly = MutableStateFlow(true)
    override fun setWifiOnly(enabled: Boolean) = Unit
    override fun enqueue(novel: NovelSummary, volumeKey: VolumeKey?) = Unit
    override fun retry(record: OfflineBookRecord) = Unit
    override fun delete(key: NovelKey) = Unit
    override suspend fun readBook(key: NovelKey): OfflineBookRecord? = null
    override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? = null
}
