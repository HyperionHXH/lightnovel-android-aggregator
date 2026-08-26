package io.github.jiangyuyi.lightnovel.core.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val indexing = AtomicBoolean(false)
    private val _indexing = MutableStateFlow(true)
    val isIndexing: StateFlow<Boolean> = _indexing.asStateFlow()
    private val _imports = MutableStateFlow(readImportedFiles())
    val importedFiles: StateFlow<List<String>> = _imports.asStateFlow()
    private val _books = MutableStateFlow<List<LocalBookRecord>>(emptyList())
    val books: StateFlow<List<LocalBookRecord>> = _books.asStateFlow()
    private val coverCache = ConcurrentHashMap<String, Result<ByteArray?>>()

    private val _roots = MutableStateFlow(readRoots())
    val roots: StateFlow<List<String>> = _roots.asStateFlow()

    init {
        indexing.set(true)
        scope.launch {
            try {
                migrateLegacyRoots()
                reindexInternal()
            } finally {
                indexing.set(false)
                _indexing.value = false
            }
        }
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
        if (!indexing.compareAndSet(false, true)) return
        _indexing.value = true
        scope.launch {
            try {
                reindexInternal()
            } finally {
                indexing.set(false)
                _indexing.value = false
            }
        }
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
        val key = "${record.id}:${record.coverPath.orEmpty()}"
        coverCache[key]?.let { return it.getOrNull() }
        val result = runCatching {
            parser.readCover(Uri.parse(record.uri), record.coverPath)
        }
        coverCache[key] = result
        return result.getOrNull()
    }

    private suspend fun reindexInternal() = withContext(Dispatchers.IO) {
        refreshFolderImports()
        val semaphore = Semaphore(permits = 4)
        val found = coroutineScope {
            _imports.value.map { uriString ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val uri = Uri.parse(uriString)
                        val file = DocumentFile.fromSingleUri(appContext, uri)
                        val size = file?.length() ?: 0L
                        val modified = file?.lastModified() ?: 0L
                        runCatching { parser.scan(uri, size, modified) }
                            .getOrElse { missingRecord(uri, size, modified) }
                    }
                }
            }.awaitAll()
        }.distinctBy(LocalBookRecord::id).sortedBy { it.title.lowercase() }
        _books.value = found
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

    private companion object {
        const val PREFERENCES = "local_library"
        const val IMPORTS = "imported_file_uris"
        const val ROOTS = "tree_uris"
        const val MAX_BOOKS = 2_000
    }
}
