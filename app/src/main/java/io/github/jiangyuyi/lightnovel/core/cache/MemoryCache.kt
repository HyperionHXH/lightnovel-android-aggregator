package io.github.jiangyuyi.lightnovel.core.cache

/** Access-ordered, byte-bounded serialized values. Callers serialize access. */
internal class MemoryCache(private val maxBytes: Long) {
    private val entries = LinkedHashMap<String, SerializedCacheEntry>(16, 0.75f, true)
    var sizeBytes: Long = 0
        private set

    operator fun get(key: String): SerializedCacheEntry? = entries[key]

    fun put(key: String, entry: SerializedCacheEntry) {
        remove(key)
        val bytes = sizeOf(key, entry)
        if (bytes > maxBytes) return
        entries[key] = entry
        sizeBytes += bytes
        val iterator = entries.entries.iterator()
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            sizeBytes -= sizeOf(oldest.key, oldest.value)
            iterator.remove()
        }
    }

    fun remove(key: String) {
        entries.remove(key)?.let { sizeBytes -= sizeOf(key, it) }
    }

    fun removeMatching(predicate: (String) -> Boolean) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                sizeBytes -= sizeOf(entry.key, entry.value)
                iterator.remove()
            }
        }
    }

    fun clear() {
        entries.clear()
        sizeBytes = 0
    }

    private fun sizeOf(key: String, entry: SerializedCacheEntry): Long =
        (key.length.toLong() + entry.payload.length) * 2L + 128L
}
