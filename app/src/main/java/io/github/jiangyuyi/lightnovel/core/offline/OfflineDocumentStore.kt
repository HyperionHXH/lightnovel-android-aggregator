package io.github.jiangyuyi.lightnovel.core.offline

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class OfflineDocumentStore(
    context: Context,
    treeUri: Uri,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : OfflineBookStore {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val selectedRoot = requireNotNull(DocumentFile.fromTreeUri(appContext, treeUri)) {
        "无法访问下载文件夹"
    }
    private val root = findOrCreateDirectory(selectedRoot, APP_DIRECTORY)
    private val mutex = Mutex()

    override suspend fun listBooks(): List<OfflineBookRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles()
                .filter { it.isDirectory }
                .mapNotNull { directory -> readManifest(directory.findFile(MANIFEST_FILE)) }
                .sortedByDescending(OfflineBookRecord::updatedAtMillis)
        }
    }

    override suspend fun readBook(key: NovelKey): OfflineBookRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readManifest(bookDirectory(key)?.findFile(MANIFEST_FILE))
        }
    }

    override suspend fun writeBook(record: OfflineBookRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = requireNotNull(bookDirectory(record.novel.key, create = true))
            replaceFile(
                directory,
                MANIFEST_FILE,
                "application/json",
                json.encodeToString(record).toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    override suspend fun hasChapter(novelKey: NovelKey, chapterKey: ChapterKey): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock { chapterFile(novelKey, chapterKey) != null }
        }

    override suspend fun readChapter(
        novelKey: NovelKey,
        chapterKey: ChapterKey,
    ): ChapterContent? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = chapterFile(novelKey, chapterKey) ?: return@withLock null
            resolver.openInputStream(file.uri)?.use { input ->
                runCatching {
                    json.decodeFromString<ChapterContent>(input.readBytes().toString(StandardCharsets.UTF_8))
                }.getOrNull()
            }
        }
    }

    override suspend fun writeChapter(novelKey: NovelKey, content: ChapterContent) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(content.chapter.key.sourceId == novelKey.sourceId) {
                    "chapter belongs to another source"
                }
                val directory = chaptersDirectory(novelKey)
                replaceFile(
                    directory,
                    "${OfflineFileStore.stableHash(content.chapter.key.remoteId)}.json",
                    "application/json",
                    json.encodeToString(content).toByteArray(StandardCharsets.UTF_8),
                )
            }
        }

    override suspend fun deleteBook(key: NovelKey) = withContext(Dispatchers.IO) {
        mutex.withLock {
            bookDirectory(key)?.delete()
            Unit
        }
    }

    private fun bookDirectory(key: NovelKey, create: Boolean = false): DocumentFile? {
        val name = OfflineFileStore.stableHash("${key.sourceId}\u0000${key.remoteId}")
        val existing = root.findFile(name)?.takeIf(DocumentFile::isDirectory)
        return existing ?: if (create) findOrCreateDirectory(root, name) else null
    }

    private fun chaptersDirectory(key: NovelKey): DocumentFile =
        findOrCreateDirectory(requireNotNull(bookDirectory(key, create = true)), CHAPTERS_DIRECTORY)

    private fun chapterFile(novelKey: NovelKey, chapterKey: ChapterKey): DocumentFile? {
        require(chapterKey.sourceId == novelKey.sourceId) { "chapter belongs to another source" }
        val directory = bookDirectory(novelKey) ?: return null
        val chapters = directory.findFile(CHAPTERS_DIRECTORY) ?: return null
        return chapters.findFile("${OfflineFileStore.stableHash(chapterKey.remoteId)}.json")
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: requireNotNull(parent.createDirectory(name)) { "无法创建离线存储目录" }

    private fun replaceFile(parent: DocumentFile, name: String, mimeType: String, bytes: ByteArray) {
        parent.findFile(name)?.delete()
        val target = requireNotNull(parent.createFile(mimeType, name)) { "无法创建离线文件" }
        resolver.openOutputStream(target.uri)?.use { it.write(bytes) }
            ?: error("无法写入离线文件")
    }

    private fun readManifest(file: DocumentFile?): OfflineBookRecord? {
        if (file == null || !file.isFile) return null
        return resolver.openInputStream(file.uri)?.use { input ->
            runCatching {
                json.decodeFromString<OfflineBookRecord>(input.readBytes().toString(StandardCharsets.UTF_8))
            }.getOrNull()
        }
    }

    private companion object {
        const val APP_DIRECTORY = "诺阅"
        const val MANIFEST_FILE = "manifest.json"
        const val CHAPTERS_DIRECTORY = "chapters"
    }
}
