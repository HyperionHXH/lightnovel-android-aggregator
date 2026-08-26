package io.github.jiangyuyi.lightnovel.core.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalLibraryIndexingTest {
    @Test
    fun loadedItemsArePublishedBeforeTheSlowestItemFinishes() = runTest {
        val fast = CompletableDeferred<Unit>()
        val slow = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val job = launch {
            progressivelyLoad(
                items = listOf("fast", "slow"),
                maxConcurrency = 2,
                load = { item ->
                    if (item == "fast") fast.await() else slow.await()
                    item
                },
                onLoaded = published::add,
            )
        }

        runCurrent()
        fast.complete(Unit)
        runCurrent()

        assertEquals(listOf("fast"), published)
        assertTrue(job.isActive)
        slow.complete(Unit)
        job.join()
        assertEquals(listOf("fast", "slow"), published)
    }

    @Test
    fun metadataCacheOnlyHitsWhenTheFileFingerprintMatches() {
        val values = mutableMapOf<String, String>()
        val cache = LocalBookMetadataCache(values::get, values::putAll)
        val record = LocalBookRecord(
            id = "id",
            uri = "content://book",
            title = "缓存书",
            format = LocalBookFormat.EPUB,
            sizeBytes = 12,
            lastModified = 34,
        )

        cache.put(record)

        assertEquals(record, cache.get(record.uri, 12, 34))
        assertEquals(null, cache.get(record.uri, 13, 34))
        assertEquals(null, cache.get(record.uri, 12, 35))
        cache.flush()
        assertTrue(values.isNotEmpty())
    }
}
