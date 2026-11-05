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
import io.github.jiangyuyi.lightnovel.core.txt.TxtExporter
import io.github.jiangyuyi.lightnovel.core.txt.TxtExportProgress
import io.github.jiangyuyi.lightnovel.core.txt.TxtExportResult
import java.io.OutputStream
import java.io.File
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class OfflineLibrary internal constructor(
    context: Context,
    private val registry: SourceRegistry,
    private val chapterFonts: ChapterFontAccess,
    private val coverFetcher: suspend (String) -> ByteArray? = { null },
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    bookStore: OfflineBookStore? = null,
) : OfflineLibraryAccess {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultStore = OfflineFileStore(
        File(applicationContext.filesDir, OFFLINE_DIRECTORY),
    )
    @Volatile
    private var store: OfflineBookStore = bookStore ?: createStore(preferences.getString(KEY_DOWNLOAD_DIRECTORY, null))
    private val _books = MutableStateFlow<List<OfflineBookRecord>>(emptyList())
    override val books: StateFlow<List<OfflineBookRecord>> = _books.asStateFlow()
    private val _wifiOnly = MutableStateFlow(preferences.getBoolean(KEY_WIFI_ONLY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    private val _downloadDirectory = MutableStateFlow(preferences.getString(KEY_DOWNLOAD_DIRECTORY, null))
    override val downloadDirectory: StateFlow<String?> = _downloadDirectory.asStateFlow()
    private val bookLocks = ConcurrentHashMap<NovelKey, Mutex>()
    private val deleting = ConcurrentHashMap.newKeySet<NovelKey>()
    private val bookRevision = AtomicLong()
    private fun bookLock(key: NovelKey): Mutex = bookLocks.getOrPut(key) { Mutex() }

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
        if (_books.value.any { it.novel.key == novel.key &&
                (it.status == OfflineDownloadStatus.QUEUED || it.status == OfflineDownloadStatus.DOWNLOADING) }) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { bookLock(novel.key).withLock {
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
                requestId = UUID.randomUUID().toString(),
            )
            activeStore.writeBook(queued)
            publish(queued)
            schedule(
                OfflineWorkSpec(
                    novelKey = novel.key,
                    selectedVolumeId = volumeKey?.remoteId,
                    wifiOnly = _wifiOnly.value,
                    requestId = queued.requestId,
                ),
                ExistingWorkPolicy.REPLACE,
            )
        } }
    }

    override fun retry(record: OfflineBookRecord) = enqueue(record.novel, record.retryVolumeKey())

    override fun delete(key: NovelKey) {
        val previous = _books.value.firstOrNull { it.novel.key == key }
        deleting.add(key)
        bookRevision.incrementAndGet()
        _books.update { records -> records.filterNot { it.novel.key == key } }
        val cancellation = runCatching { workManager.cancelUniqueWork(workName(key)) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { bookLock(key).withLock {
            val activeStore = store
            var restored: OfflineBookRecord? = null
            try {
                // Cancellation is not a join: the book lock waits for in-flight writes too.
                withContext(Dispatchers.IO) { cancellation.getOrThrow().result.get() }
                activeStore.deleteBook(key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val remaining = runCatching { activeStore.readBook(key) }.getOrNull() ?: previous
                restored = remaining?.copy(
                    status = OfflineDownloadStatus.FAILED,
                    error = error.message?.take(160)?.takeIf(String::isNotBlank) ?: "无法删除离线书籍",
                    updatedAtMillis = System.currentTimeMillis(),
                )
                restored?.let { record -> runCatching { activeStore.writeBook(record) } }
            } finally {
                deleting.remove(key)
            }
            restored?.let(::publish)
        } }
    }

    override suspend fun markChapterUnlocked(chapter: io.github.jiangyuyi.lightnovel.core.source.ChapterSummary) = bookLock(chapter.novelKey).withLock {
        val novelKey = chapter.novelKey
        require(chapter.key.sourceId == novelKey.sourceId)
        if (novelKey in deleting) return@withLock
        val record = store.readBook(novelKey) ?: return@withLock
        if (chapter.key.remoteId in record.unlockedChapterIds) return@withLock
        val updated = record.copy(
            chapters = (record.chapters + chapter.copy(locked = false)).associateBy { it.key }.values.toList(),
            unlockedChapterIds = record.unlockedChapterIds + chapter.key.remoteId,
            updatedAtMillis = System.currentTimeMillis(),
        )
        store.writeBook(updated)
        publish(updated)
    }

    override suspend fun readBook(key: NovelKey): OfflineBookRecord? =
        if (key in deleting) null else store.readBook(key)

    override suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? =
        store.readChapter(novelKey, chapterKey)

    /** Includes the old internal library when a SAF download folder is selected. */
    suspend fun downloadedChapterFontUrls(): Set<String> = buildSet {
        listOf(store, defaultStore).distinct().forEach { bookStore ->
            bookStore.listBooks().forEach { record ->
                record.chapters.filter { it.key.remoteId in record.downloadedChapterIds }
                    .forEach { chapter ->
                        bookStore.readChapter(record.novel.key, chapter.key)?.fontUrl
                            ?.takeIf(String::isNotBlank)?.let(::add)
                    }
            }
        }
    }

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

    override suspend fun exportTxt(
        key: NovelKey,
        output: OutputStream,
        onProgress: (TxtExportProgress) -> Unit,
    ): TxtExportResult {
        val record = store.readBook(key) ?: error("离线书籍不存在")
        return TxtExporter().export(
            record,
            { chapterKey -> store.readChapter(key, chapterKey) },
            output,
            onProgress,
        )
    }

    override suspend fun exportEpubToDownloadDirectory(
        key: NovelKey,
        onProgress: (EpubExportProgress) -> Unit,
    ): OfflineFileExportResult = exportToDownloadDirectory(
        key = key,
        extension = "epub",
        mimeType = "application/epub+zip",
    ) { output ->
        val result = exportEpub(key, output, onProgress)
        result.exportedChapters to result.skippedChapters
    }

    override suspend fun exportTxtToDownloadDirectory(
        key: NovelKey,
        onProgress: (TxtExportProgress) -> Unit,
    ): OfflineFileExportResult = exportToDownloadDirectory(
        key = key,
        extension = "txt",
        mimeType = "text/plain",
    ) { output ->
        val record = store.readBook(key) ?: error("离线书籍不存在")
        val result = TxtExporter().export(
            record,
            { chapterKey -> store.readChapter(key, chapterKey) },
            output,
            onProgress,
        )
        result.exportedChapters to result.skippedChapters
    }

    internal suspend fun executeDownload(novelKey: NovelKey, selectedVolumeId: String?, requestId: String = ""): Unit = bookLock(novelKey).withLock {
        coroutineContext.ensureActive()
        val queued = store.readBook(novelKey)
        if (novelKey in deleting || queued == null || queued.requestId != requestId) return@withLock
        val activeStore = store
        val downloader = OfflineDownloader(registry, activeStore, chapterFonts, onUpdated = ::publish)
        try {
            downloader.download(novelKey, selectedVolumeId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val current = activeStore.readBook(novelKey)?.takeUnless { novelKey in deleting }
            if (current != null) {
                val failed = current.copy(
                    status = OfflineDownloadStatus.FAILED,
                    error = error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)
                        .ifBlank { "下载失败" },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                activeStore.writeBook(failed)
                publish(failed)
            }
            throw error
        }
    }

    private suspend fun reload() {
        while (true) {
            coroutineContext.ensureActive()
            val activeStore = store
            val revision = bookRevision.get()
            val records = activeStore.listBooks()
            var accepted = false
            _books.update { current ->
                accepted = bookRevision.get() == revision && store === activeStore
                if (accepted) records.filterNot { it.novel.key in deleting } else current
            }
            if (accepted) return
        }
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

    private suspend fun exportToDownloadDirectory(
        key: NovelKey,
        extension: String,
        mimeType: String,
        writer: suspend (OutputStream) -> Pair<Int, Int>,
    ): OfflineFileExportResult {
        val record = store.readBook(key) ?: error("离线书籍不存在")
        val base = record.novel.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "novel" }
            .take(100)
        val fileName = "$base.$extension"
        val (exported, skipped) = if (_downloadDirectory.value == null) {
            val directory = File(applicationContext.filesDir, "$OFFLINE_DIRECTORY/exports")
                .apply { mkdirs() }
            val target = File(directory, fileName)
            val output = target.outputStream()
            try {
                writer(output)
            } catch (error: Throwable) {
                target.delete()
                throw error
            } finally {
                output.close()
            }
        } else {
            val selectedRoot = requireNotNull(
                DocumentFile.fromTreeUri(applicationContext, Uri.parse(_downloadDirectory.value)),
            ) { "无法访问下载文件夹，请重新选择" }
            val appDirectory = selectedRoot.findFile("Mixn")?.takeIf(DocumentFile::isDirectory)
                ?: selectedRoot.findFile("诺阅")?.takeIf(DocumentFile::isDirectory)
                ?: requireNotNull(selectedRoot.createDirectory("Mixn")) { "无法创建 Mixn 文件夹" }
            val exportDirectory = appDirectory.findFile("exports")?.takeIf(DocumentFile::isDirectory)
                ?: requireNotNull(appDirectory.createDirectory("exports")) { "无法创建导出文件夹" }
            exportDirectory.findFile(fileName)?.delete()
            val target = requireNotNull(exportDirectory.createFile(mimeType, fileName)) { "无法创建导出文件" }
            try {
                val output = applicationContext.contentResolver.openOutputStream(target.uri)
                    ?: error("无法写入导出文件")
                try {
                    writer(output)
                } finally {
                    output.close()
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        return OfflineFileExportResult(fileName, exported, skipped)
    }

    private fun publish(record: OfflineBookRecord) {
        if (record.novel.key in deleting) return
        bookRevision.incrementAndGet()
        _books.update { current ->
            if (record.novel.key in deleting) return@update current
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
            .putString(OfflineDownloadWorker.KEY_REQUEST_ID, spec.requestId)
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
