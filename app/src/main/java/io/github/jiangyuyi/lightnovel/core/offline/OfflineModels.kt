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
import io.github.jiangyuyi.lightnovel.core.txt.TxtExportProgress
import io.github.jiangyuyi.lightnovel.core.txt.TxtExportResult
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
        get() = downloadedChapterIds.count { remoteId ->
            chapters.any { chapter -> chapter.key.remoteId == remoteId && !chapter.locked }
        }

    /** A volume is counted only after every unlocked chapter in that volume is present. */
    val completedVolumes: Int
        get() = knownVolumeKeys.count { volumeKey ->
            val volumeChapters = chapters.filter { it.volumeKey == volumeKey && !it.locked }
            volumeChapters.isNotEmpty() && volumeChapters.all { it.key.remoteId in downloadedChapterIds }
        }

    val totalVolumes: Int
        get() = knownVolumeKeys.size.coerceAtLeast(novel.volumeCount)

    private val knownVolumeKeys
        get() = (volumes.map(VolumeSummary::key) + chapters.map(ChapterSummary::volumeKey)).distinct()
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
    val downloadDirectory: StateFlow<String?>
        get() = EMPTY_DOWNLOAD_DIRECTORY
    fun setWifiOnly(enabled: Boolean)
    fun setDownloadDirectory(uri: String?) {}
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
    suspend fun exportTxt(
        key: NovelKey,
        output: OutputStream,
        onProgress: (TxtExportProgress) -> Unit = {},
    ): TxtExportResult? = null
    suspend fun exportEpubToDownloadDirectory(
        key: NovelKey,
        onProgress: (EpubExportProgress) -> Unit = {},
    ): OfflineFileExportResult? = null
    suspend fun exportTxtToDownloadDirectory(
        key: NovelKey,
        onProgress: (TxtExportProgress) -> Unit = {},
    ): OfflineFileExportResult? = null
}

data class OfflineFileExportResult(
    val fileName: String,
    val exportedChapters: Int,
    val skippedChapters: Int,
)

private val EMPTY_DOWNLOAD_DIRECTORY = MutableStateFlow<String?>(null)

object EmptyOfflineLibraryAccess : OfflineLibraryAccess {
    override val books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
    override val wifiOnly = MutableStateFlow(true)
    override val downloadDirectory: StateFlow<String?> = EMPTY_DOWNLOAD_DIRECTORY
    override fun setWifiOnly(enabled: Boolean) = Unit
    override fun enqueue(novel: NovelSummary, volumeKey: VolumeKey?) = Unit
    override fun retry(record: OfflineBookRecord) = Unit
    override fun delete(key: NovelKey) = Unit
    override suspend fun readBook(key: NovelKey): OfflineBookRecord? = null
    override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? = null
}
