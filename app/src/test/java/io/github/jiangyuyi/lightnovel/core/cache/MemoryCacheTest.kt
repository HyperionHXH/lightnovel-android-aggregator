package io.github.jiangyuyi.lightnovel.core.cache

import org.junit.Assert.*
import org.junit.Test

class MemoryCacheTest {
    @Test fun `access promotes LRU and oversized replacement cannot exceed bound`() {
        val cache = MemoryCache(300)
        val value = SerializedCacheEntry("x", 1)
        cache.put("a", value)
        cache.put("b", value)
        assertEquals(value, cache["a"])
        cache.put("c", value)
        assertNull(cache["b"])
        assertEquals(value, cache["a"])
        assertEquals(value, cache["c"])
        cache.put("a", SerializedCacheEntry("x".repeat(200), 2))
        assertNull(cache["a"])
        assertTrue(cache.sizeBytes <= 300)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
        assertNull(cache["c"])
    }
}
