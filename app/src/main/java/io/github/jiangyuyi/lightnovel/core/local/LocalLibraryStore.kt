package io.github.jiangyuyi.lightnovel.core.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** Kept for migration compatibility with versions that stored folder permissions. */
    @Deprecated("Folder authorization is no longer the primary import flow")
    val roots: StateFlow<List<String>> = MutableStateFlow(readRoots())

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

    fun removeFile(uri: String) {
        val updated = _imports.value.filterNot { it == uri }
        preferences.edit().putStringSet(IMPORTS, updated.toSet()).apply()
        _imports.value = updated
        reindex()
    }

    /** Rebuilds metadata only for files the user explicitly imported. */
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

    @Deprecated("Use addFiles")
    fun addTree(uri: Uri) {
        scope.launch {
            val files = listTreeFiles(uri)
            addFiles(files)
        }
    }

    @Deprecated("Use removeFile")
    fun removeTree(uri: String) {
        preferences.edit().remove(ROOTS).apply()
        scope.launch {
            val remaining = _imports.value.filterNot { it == uri || it.startsWith("$uri/") }
            preferences.edit().putStringSet(IMPORTS, remaining.toSet()).apply()
            _imports.value = remaining
            reindex()
        }
    }

    fun readProgress(bookId: String): Int = preferences.getInt("progress_$bookId", 0)

    fun saveProgress(bookId: String, chapterIndex: Int) {
        preferences.edit().putInt("progress_$bookId", chapterIndex.coerceAtLeast(0)).apply()
    }

    suspend fun read(record: LocalBookRecord): LocalBookDocument? = withContext(Dispatchers.IO) {
        runCatching { parser.parse(Uri.parse(record.uri)) }.getOrNull()
    }

    private suspend fun reindexInternal() = withContext(Dispatchers.IO) {
        val found = _imports.value.map { uriString ->
            val uri = Uri.parse(uriString)
            val file = DocumentFile.fromSingleUri(appContext, uri)
            val size = file?.length() ?: 0L
            val modified = file?.lastModified() ?: 0L
            runCatching { parser.scan(uri, size, modified) }
                .getOrElse { missingRecord(uri, size, modified) }
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

    private suspend fun migrateLegacyRoots() {
        if (_imports.value.isNotEmpty()) return
        val legacyRoots = readRoots()
        if (legacyRoots.isEmpty()) return
        val files = legacyRoots.flatMap { listTreeFiles(Uri.parse(it)) }.distinct().take(MAX_BOOKS)
        if (files.isNotEmpty()) {
            preferences.edit().putStringSet(IMPORTS, files.map(Uri::toString).toSet()).apply()
            _imports.value = files.map(Uri::toString)
        }
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

    private companion object {
        const val PREFERENCES = "local_library"
        const val IMPORTS = "imported_file_uris"
        const val ROOTS = "tree_uris"
        const val MAX_BOOKS = 2_000
    }
}
