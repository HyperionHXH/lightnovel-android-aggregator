package io.github.jiangyuyi.lightnovel.core.offline

import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OfflineFileStore(
    private val root: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val mutex = Mutex()

    suspend fun listBooks(): List<OfflineBookRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles()
                ?.filter(File::isDirectory)
                .orEmpty()
                .mapNotNull { directory -> readManifest(File(directory, MANIFEST_FILE)) }
                .sortedByDescending(OfflineBookRecord::updatedAtMillis)
        }
    }

    suspend fun readBook(key: NovelKey): OfflineBookRecord? = withContext(Dispatchers.IO) {
        mutex.withLock { readManifest(File(bookDirectory(key), MANIFEST_FILE)) }
    }

    suspend fun writeBook(record: OfflineBookRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(bookDirectory(record.novel.key), MANIFEST_FILE)
            atomicWrite(file, json.encodeToString(record))
        }
    }

    suspend fun hasChapter(novelKey: NovelKey, chapterKey: ChapterKey): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { chapterFile(novelKey, chapterKey).isFile }
    }

    suspend fun readChapter(novelKey: NovelKey, chapterKey: ChapterKey): ChapterContent? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = chapterFile(novelKey, chapterKey)
                if (!file.isFile) return@withLock null
                runCatching { json.decodeFromString<ChapterContent>(file.readText()) }.getOrNull()
            }
        }

    suspend fun writeChapter(novelKey: NovelKey, content: ChapterContent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(content.chapter.key.sourceId == novelKey.sourceId) { "chapter belongs to another source" }
            atomicWrite(
                chapterFile(novelKey, content.chapter.key),
                json.encodeToString(content),
            )
        }
    }

    suspend fun deleteBook(key: NovelKey) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = bookDirectory(key)
            check(directory.canonicalFile.parentFile == root.canonicalFile) {
                "offline book directory escaped its root"
            }
            if (directory.exists() && !directory.deleteRecursively()) {
                error("无法删除离线书籍")
            }
        }
    }

    private fun readManifest(file: File): OfflineBookRecord? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<OfflineBookRecord>(file.readText()) }.getOrNull()
    }

    private fun bookDirectory(key: NovelKey): File = File(root, stableHash("${key.sourceId}\u0000${key.remoteId}"))

    private fun chapterFile(novelKey: NovelKey, chapterKey: ChapterKey): File {
        require(chapterKey.sourceId == novelKey.sourceId) { "chapter belongs to another source" }
        return File(File(bookDirectory(novelKey), CHAPTERS_DIRECTORY), "${stableHash(chapterKey.remoteId)}.json")
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(text)
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
        private const val CHAPTERS_DIRECTORY = "chapters"

        internal fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
