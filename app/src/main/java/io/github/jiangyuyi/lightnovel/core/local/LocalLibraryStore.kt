package io.github.jiangyuyi.lightnovel.core.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class LocalLibraryStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val parser = LocalBookParser(resolver)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reindexRequests = Channel<Unit>(Channel.CONFLATED)
    private val _indexing = MutableStateFlow(true)
    val isIndexing: StateFlow<Boolean> = _indexing.asStateFlow()
    private val _imports = MutableStateFlow(readImportedFiles())
    val importedFiles: StateFlow<List<String>> = _imports.asStateFlow()
    private val _books = MutableStateFlow<List<LocalBookRecord>>(emptyList())
    val books: StateFlow<List<LocalBookRecord>> = _books.asStateFlow()
    private val coverCache = object : LruCache<String, ByteArray>(COVER_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val coverSemaphore = Semaphore(permits = 2)
    private val coverDirectory by lazy { File(appContext.cacheDir, "local-cover-cache") }
    private val metadataCache = LocalBookMetadataCache(
        readValue = { key -> preferences.getString("$CACHE_PREFIX$key", null) },
        writeValues = { values ->
            preferences.edit().apply {
                values.forEach { (key, value) -> putString("$CACHE_PREFIX$key", value) }
            }.apply()
        },
    )

    private val _roots = MutableStateFlow(readRoots())
    val roots: StateFlow<List<String>> = _roots.asStateFlow()

    init {
        scope.launch {
            var initialIndex = true
            for (request in reindexRequests) {
                _indexing.value = true
                try {
                    if (initialIndex) {
                        migrateLegacyRoots()
                        initialIndex = false
                    }
                    reindexInternal()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep the worker alive so a later refresh can recover from a provider failure.
                } finally {
                    _indexing.value = false
                }
            }
        }
        reindexRequests.trySend(Unit)
    }

    fun addFiles(uris: List<Uri>) {
        val updated = (_imports.value + uris.map(Uri::toString)).distinct().take(MAX_BOOKS)
        preferences.edit().putStringSet(IMPORTS, updated.toSet()).apply()
        _imports.value = updated
        reindex()
    }

    fun addFolder(uri: Uri) {
        val value = uri.toString()
        val updated = (_roots.value + value).distinct()
        preferences.edit().putStringSet(ROOTS, updated.toSet()).apply()
        _roots.value = updated
        reindex()
    }

    fun removeFile(uri: String) {
        val updated = _imports.value.filterNot { it == uri }
        preferences.edit().putStringSet(IMPORTS, updated.toSet()).apply()
        _imports.value = updated
        reindex()
    }

    fun removeFolder(uri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val updatedRoots = _roots.value.filterNot { it == uri }
        preferences.edit().putStringSet(ROOTS, updatedRoots.toSet()).apply()
        _roots.value = updatedRoots
        val remaining = _imports.value.filterNot { isInFolder(Uri.parse(it), Uri.parse(uri)) }
        preferences.edit().putStringSet(IMPORTS, remaining.toSet()).apply()
        _imports.value = remaining
        reindex()
    }

    /** Rebuilds metadata for explicitly imported files and files under authorized folders. */
    fun reindex() {
        reindexRequests.trySend(Unit)
    }

    @Deprecated("Use reindex")
    fun scan() = reindex()

    @Deprecated("Use addFolder")
    fun addTree(uri: Uri) = addFolder(uri)

    @Deprecated("Use removeFolder")
    fun removeTree(uri: String) = removeFolder(uri)

    fun readProgress(bookId: String): Int = preferences.getInt("progress_$bookId", 0)

    fun saveProgress(bookId: String, chapterIndex: Int) {
        preferences.edit().putInt("progress_$bookId", chapterIndex.coerceAtLeast(0)).apply()
    }

    suspend fun read(record: LocalBookRecord): LocalBookDocument? = withContext(Dispatchers.IO) {
        runCatching { parser.parse(Uri.parse(record.uri)) }.getOrNull()
    }

    suspend fun readCover(record: LocalBookRecord): ByteArray? {
        return withContext(Dispatchers.IO) {
            val key = "${record.id}:${record.sizeBytes}:${record.lastModified}:${record.coverPath.orEmpty()}"
            coverCache.get(key)?.let { return@withContext it }
            val bytes = coverSemaphore.withPermit {
                runCatching {
                    readCachedCover(key) ?: parser.readCover(Uri.parse(record.uri), record.coverPath)
                }.getOrNull()
            }
            bytes?.let {
                coverCache.put(key, it)
                writeCachedCover(key, it)
            }
            bytes
        }
    }

    private fun readCachedCover(key: String): ByteArray? = runCatching {
        coverFile(key).takeIf(File::isFile)?.readBytes()
    }.getOrNull()

    private fun writeCachedCover(key: String, bytes: ByteArray) {
        runCatching {
            if (!coverDirectory.exists()) coverDirectory.mkdirs()
            val target = coverFile(key)
            val temporary = File(coverDirectory, "${target.name}.tmp")
            temporary.outputStream().use { it.write(bytes) }
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    private fun coverFile(key: String): File =
        File(coverDirectory, LocalBookParser.stableId(key))

    private suspend fun reindexInternal() = withContext(Dispatchers.IO) {
        refreshFolderImports()
        val entries = _imports.value
            .mapNotNull(::prepareEntry)
            .distinctBy(IndexedLocalFile::id)

        // Publish filename-based records immediately. Cached metadata is available on the first frame
        // after a restart, while new files are upgraded in the background one by one.
        _books.value = entries
            .map { it.cached ?: it.placeholder }
            .sortedBy { it.title.lowercase() }

        progressivelyLoad(
            items = entries.filter { it.cached == null },
            maxConcurrency = INDEX_CONCURRENCY,
            load = { entry ->
                val record = try {
                    parser.scan(entry.uri, entry.sizeBytes, entry.lastModified)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    missingRecord(entry.uri, entry.sizeBytes, entry.lastModified)
                }
                metadataCache.put(record)
                record
            },
            onLoaded = ::publishLoaded,
        )
        metadataCache.flush()
        _books.update { records -> records.sortedBy { it.title.lowercase() } }
    }

    private fun prepareEntry(uriString: String): IndexedLocalFile? {
        val uri = Uri.parse(uriString)
        val file = DocumentFile.fromSingleUri(appContext, uri)
        val size = file?.length() ?: 0L
        val modified = file?.lastModified() ?: 0L
        val placeholder = runCatching {
            parser.stub(uri, size, modified)
        }.getOrElse {
            missingRecord(uri, size, modified)
        }
        val cached = file?.let { metadataCache.get(uriString, size, modified) }
        return IndexedLocalFile(
            id = placeholder.id,
            uri = uri,
            sizeBytes = size,
            lastModified = modified,
            placeholder = placeholder,
            cached = cached,
        )
    }

    private fun publishLoaded(record: LocalBookRecord) {
        _books.update { current ->
            val index = current.indexOfFirst { it.id == record.id }
            val updated = current.toMutableList()
            if (index >= 0) updated[index] = record else updated += record
            updated
        }
    }

    private fun missingRecord(uri: Uri, size: Long, modified: Long): LocalBookRecord {
        val format = runCatching { parser.formatOf(uri) }.getOrDefault(LocalBookFormat.TXT)
        val fileName = parser.displayNameOf(uri)
        return LocalBookRecord(
            id = LocalBookParser.stableId(uri.toString()),
            uri = uri.toString(),
            title = fileName.substringBeforeLast('.', fileName),
            format = format,
            sizeBytes = size,
            lastModified = modified,
            chapterCount = 0,
            available = false,
        )
    }

    private suspend fun migrateLegacyRoots() = Unit

    private suspend fun refreshFolderImports() {
        if (_roots.value.isEmpty()) return
        val folderFiles = _roots.value
            .flatMap { listTreeFiles(Uri.parse(it)) }
            .map(Uri::toString)
        val directFiles = _imports.value.filterNot { file ->
            _roots.value.any { root -> isInFolder(Uri.parse(file), Uri.parse(root)) }
        }
        val updated = (directFiles + folderFiles).distinct().take(MAX_BOOKS)
        if (updated == _imports.value) return
        preferences.edit().putStringSet(IMPORTS, updated.toSet()).apply()
        _imports.value = updated
    }

    private suspend fun listTreeFiles(uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, uri) ?: return@withContext emptyList()
        val result = mutableListOf<Uri>()
        suspend fun visit(directory: DocumentFile) {
            directory.listFiles().forEach { child ->
                if (result.size >= MAX_BOOKS) return
                if (child.isDirectory) visit(child)
                else if (child.isFile && runCatching { parser.formatOf(child.uri) }.isSuccess) result += child.uri
            }
        }
        visit(root)
        result
    }

    private fun readImportedFiles(): List<String> = preferences.getStringSet(IMPORTS, emptySet()).orEmpty().toList().sorted()

    private fun readRoots(): List<String> = preferences.getStringSet(ROOTS, emptySet()).orEmpty().toList().sorted()

    private fun isInFolder(file: Uri, root: Uri): Boolean = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(root)
        val fileId = DocumentsContract.getDocumentId(file)
        fileId == rootId || fileId.startsWith("$rootId/")
    }.getOrElse {
        file.toString().startsWith(root.toString())
    }

    private data class IndexedLocalFile(
        val id: String,
        val uri: Uri,
        val sizeBytes: Long,
        val lastModified: Long,
        val placeholder: LocalBookRecord,
        val cached: LocalBookRecord?,
    )

    private companion object {
        const val PREFERENCES = "local_library"
        const val IMPORTS = "imported_file_uris"
        const val ROOTS = "tree_uris"
        const val CACHE_PREFIX = "metadata_"
        const val INDEX_CONCURRENCY = 6
        const val MAX_BOOKS = 2_000
        const val COVER_MEMORY_CACHE_BYTES = 8 * 1024 * 1024
    }
}
