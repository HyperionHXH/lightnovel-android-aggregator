package io.github.jiangyuyi.lightnovel.core.cache

import coil.ImageLoader
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibrary
import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Clears only explicitly owned, reproducible online-reading data. */
class TemporaryReadingCache(
    private val text: SqliteCacheStore,
    private val dataSource: CachedDataSource,
    private val chapterFonts: ChapterFontRepository,
    private val offlineLibrary: OfflineLibrary,
    private val imageLoader: () -> ImageLoader,
    private val imageMemory: ImageMemoryCacheOwner,
    private val clearSourceMetadata: () -> Unit,
) {
    private val mutex = Mutex()
    private var prepared = false

    suspend fun prepare() = withContext(Dispatchers.IO) {
        mutex.withLock { prepareLocked() }
    }

    suspend fun usage(): ReadingCacheUsage = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareLocked()
            usageLocked()
        }
    }

    suspend fun clear(): ReadingCacheClearResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareLocked()
            val before = usageLocked()
            clearSourceMetadata()
            dataSource.clearAll()
            // Owner APIs only: unrelated network owners/decoders may have open cacheDir files.
            imageMemory.clear(imageLoader())
            chapterFonts.clearTemporary()
            val after = usageLocked()
            ReadingCacheClearResult(
                removedDiskBytes = (before.diskBytes - after.diskBytes).coerceAtLeast(0),
                remaining = after,
            )
        }
    }

    private suspend fun prepareLocked() {
        if (prepared) return
        chapterFonts.migrateLegacyFonts(offlineLibrary.downloadedChapterFontUrls())
        prepared = true
    }

    private suspend fun usageLocked(): ReadingCacheUsage {
        val textUsage = text.usage()
        val fontUsage = chapterFonts.usage()
        return ReadingCacheUsage(
            textDiskBytes = textUsage.diskBytes,
            textMemoryBytes = textUsage.memoryBytes,
            imageMemoryBytes = imageLoader().memoryCache?.size?.toLong() ?: 0L,
            temporaryFontBytes = fontUsage.temporaryBytes,
            offlineFontBytes = fontUsage.offlineBytes,
        )
    }

    companion object {
        const val MAX_IMAGE_MEMORY_BYTES = 32 * 1024 * 1024
    }
}

data class ReadingCacheUsage(
    val textDiskBytes: Long,
    val textMemoryBytes: Long,
    val imageMemoryBytes: Long,
    val temporaryFontBytes: Long,
    val offlineFontBytes: Long,
) {
    val diskBytes: Long get() = textDiskBytes + temporaryFontBytes
}

data class ReadingCacheClearResult(val removedDiskBytes: Long, val remaining: ReadingCacheUsage)
