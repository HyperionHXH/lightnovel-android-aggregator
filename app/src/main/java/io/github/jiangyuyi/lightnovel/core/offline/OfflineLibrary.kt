package io.github.jiangyuyi.lightnovel.core.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.source.VolumeKey
import io.github.jiangyuyi.lightnovel.core.epub.EpubExportResult
import io.github.jiangyuyi.lightnovel.core.epub.EpubExportProgress
import io.github.jiangyuyi.lightnovel.core.epub.EpubExporter
import java.io.OutputStream
import java.io.File
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OfflineLibrary(
    context: Context,
    private val registry: SourceRegistry,
    private val chapterFonts: ChapterFontAccess,
    private val coverFetcher: suspend (String) -> ByteArray? = { null },
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) : OfflineLibraryAccess {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultStore = OfflineFileStore(
        File(applicationContext.filesDir, OFFLINE_DIRECTORY),
    )
    @Volatile
    private var store: OfflineBookStore = createStore(preferences.getString(KEY_DOWNLOAD_DIRECTORY, null))
    private val _books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
    override val books: StateFlow<List<OfflineBookRecord>> = _books.asStateFlow()
    private val _wifiOnly = MutableStateFlow(preferences.getBoolean(KEY_WIFI_ONLY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    private val _downloadDirectory = MutableStateFlow(preferences.getString(KEY_DOWNLOAD_DIRECTORY, null))
    override val downloadDirectory: StateFlow<String?> = _downloadDirectory.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override fun setWifiOnly(enabled: Boolean) {
        if (_wifiOnly.value == enabled) return
        preferences.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
        scope.launch {
            pendingOfflineWorkAfterNetworkPolicyChange(store.listBooks(), enabled)
                .forEach { spec -> schedule(spec, ExistingWorkPolicy.REPLACE) }
        }
    }

    override fun setDownloadDirectory(uri: String?) {
        val normalized = uri?.trim()?.takeIf(String::isNotBlank)
        if (normalized == _downloadDirectory.value) return
        scope.launch {
            val nextStore = if (normalized == null) {
                defaultStore
            } else {
                runCatching { OfflineDocumentStore(applicationContext, Uri.parse(normalized)) }
                    .getOrElse { return@launch }
            }
            val previousStore = store
            if (previousStore !== nextStore) {
                migrateBooks(previousStore, nextStore)
            }
            store = nextStore
            preferences.edit().putStringOrRemove(KEY_DOWNLOAD_DIRECTORY, normalized).apply()
            _downloadDirectory.value = normalized
            reload()
        }
    }

    override fun enqueue(novel: NovelSummary, volumeKey: VolumeKey?) {
        require(volumeKey == null || volumeKey.sourceId == novel.key.sourceId)
        scope.launch {
            val activeStore = store
            val existing = activeStore.readBook(novel.key)
            if (existing?.status == OfflineDownloadStatus.QUEUED ||
                existing?.status == OfflineDownloadStatus.DOWNLOADING
            ) {
                return@launch
            }
            val queued = (existing ?: OfflineBookRecord(novel = novel)).copy(
                novel = novel,
                selectedVolumeId = volumeKey?.remoteId,
                status = OfflineDownloadStatus.QUEUED,
                error = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
            activeStore.writeBook(queued)
            publish(queued)
            schedule(
                OfflineWorkSpec(
                    novelKey = novel.key,
                    selectedVolumeId = volumeKey?.remoteId,
                    wifiOnly = _wifiOnly.value,
                ),
                ExistingWorkPolicy.KEEP,
            )
        }
    }

    override fun retry(record: OfflineBookRecord) = enqueue(
        record.novel,
        record.selectedVolumeId?.let { remoteId -> VolumeKey(record.novel.key.sourceId, remoteId) },
    )

    override fun delete(key: NovelKey) {
        workManager.cancelUniqueWork(workName(key))
        scope.launch {
            store.deleteBook(key)
            _books.update { records -> records.filterNot { it.novel.key == key } }
        }
    }

    override suspend fun readBook(key: NovelKey): OfflineBookRecord? = store.readBook(key)

    override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? =
        store.readChapter(novelKey, chapterKey)

    override suspend fun exportEpub(
        key: NovelKey,
        output: OutputStream,
        onProgress: (EpubExportProgress) -> Unit,
    ): EpubExportResult {
        val record = store.readBook(key) ?: error("离线书籍不存在")
        return EpubExporter().export(
            record,
            { chapterKey -> store.readChapter(key, chapterKey) },
            output,
            onProgress,
            coverReader = coverFetcher,
            assetReader = coverFetcher,
        )
    }

    internal suspend fun executeDownload(novelKey: NovelKey, selectedVolumeId: String?) {
        val activeStore = store
        val downloader = OfflineDownloader(registry, activeStore, chapterFonts, onUpdated = ::publish)
        try {
            downloader.download(novelKey, selectedVolumeId)
        } catch (error: Throwable) {
            val current = store.readBook(novelKey)
            if (current != null) {
                val failed = current.copy(
                    status = OfflineDownloadStatus.FAILED,
                    error = error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)
                        .ifBlank { "下载失败" },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                store.writeBook(failed)
                publish(failed)
            }
            throw error
        }
    }

    private suspend fun reload() {
        _books.value = store.listBooks()
    }

    private suspend fun migrateBooks(from: OfflineBookStore, to: OfflineBookStore) {
        from.listBooks().forEach { record ->
            to.writeBook(record)
            record.chapters
                .filter { it.key.remoteId in record.downloadedChapterIds }
                .forEach { chapter ->
                    from.readChapter(record.novel.key, chapter.key)?.let { content ->
                        to.writeChapter(record.novel.key, content)
                    }
                }
        }
    }

    private fun createStore(uri: String?): OfflineBookStore =
        uri?.let { value ->
            runCatching { OfflineDocumentStore(applicationContext, Uri.parse(value)) }.getOrNull()
        } ?: defaultStore

    private fun publish(record: OfflineBookRecord) {
        _books.update { current ->
            (current.filterNot { it.novel.key == record.novel.key } + record)
                .sortedByDescending(OfflineBookRecord::updatedAtMillis)
        }
    }

    private fun schedule(spec: OfflineWorkSpec, existingWorkPolicy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (spec.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(OfflineDownloadWorker.KEY_SOURCE_ID, spec.novelKey.sourceId)
            .putString(OfflineDownloadWorker.KEY_NOVEL_ID, spec.novelKey.remoteId)
            .putString(OfflineDownloadWorker.KEY_VOLUME_ID, spec.selectedVolumeId)
            .build()
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DOWNLOAD_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(spec.novelKey), existingWorkPolicy, request)
    }

    private fun workName(key: NovelKey): String = "offline-${OfflineFileStore.stableHash("${key.sourceId}\u0000${key.remoteId}")}" 

    private companion object {
        const val OFFLINE_DIRECTORY = "offline_library"
        const val PREFERENCES_NAME = "offline_download_preferences"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_DOWNLOAD_DIRECTORY = "download_directory"
        const val DOWNLOAD_TAG = "offline-download"
    }
}

private fun android.content.SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) =
    if (value == null) remove(key) else putString(key, value)
