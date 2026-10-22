package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Public book metadata only. Never persists credentials or remote shelf membership. */
internal class ShelfDetailCache(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxEntries: Int = 64,
    private val maxCharacters: Int = 512 * 1024,
    private val ttlMillis: Long = 5 * 60 * 1_000L,
) {
    private data class Entry(val detail: ShelfBookDetail, val expiresAt: Long, val weight: Int)
    private class Flight(val generation: Long) {
        val mutex = Mutex()
        var users = 0
    }

    private val lock = Any()
    private val entries = LinkedHashMap<Long, Entry>(16, 0.75f, true)
    private val flights = mutableMapOf<Long, Flight>()
    private val activeLoads = mutableSetOf<Job>()
    private val permits = Semaphore(4)
    private var characters = 0
    private var generation = 0L

    fun clear() {
        val jobs = synchronized(lock) {
            generation++
            entries.clear()
            characters = 0
            flights.clear()
            activeLoads.toList()
        }
        jobs.forEach { it.cancel() }
    }

    suspend fun get(bookId: Long, load: suspend () -> ShelfBookDetail): ShelfBookDetail {
        val flight = synchronized(lock) {
            flights.getOrPut(bookId) { Flight(generation) }.also { it.users++ }
        }
        try {
            return flight.mutex.withLock {
                val cached = synchronized(lock) {
                    if (flight.generation != generation) throw CancellationException("metadata cache cleared")
                    entries[bookId]?.also {
                        if (it.expiresAt <= nowMillis()) {
                            entries.remove(bookId)
                            characters -= it.weight
                        }
                    }?.takeIf { it.expiresAt > nowMillis() }
                }
                if (cached != null) return@withLock cached.detail
                permits.withPermit {
                    val job = currentCoroutineContext()[Job]!!
                    synchronized(lock) {
                        if (flight.generation != generation) throw CancellationException("metadata cache cleared")
                        activeLoads += job
                    }
                    try {
                        val detail = load()
                        currentCoroutineContext().ensureActive()
                        val weight = detail.title.length + detail.coverUrl.orEmpty().length +
                            detail.authorName.orEmpty().length + detail.introduction.length +
                            detail.tags.sumOf(String::length) + detail.chapters.sumOf { it.title.length + 32 }
                        synchronized(lock) {
                            if (flight.generation == generation && weight <= maxCharacters) {
                                entries.remove(bookId)?.let { characters -= it.weight }
                                entries[bookId] = Entry(detail, nowMillis() + ttlMillis, weight)
                                characters += weight
                                while (entries.size > maxEntries || characters > maxCharacters) {
                                    val eldest = entries.entries.iterator()
                                    characters -= eldest.next().value.weight
                                    eldest.remove()
                                }
                            }
                        }
                        detail
                    } finally {
                        synchronized(lock) { activeLoads -= job }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                flight.users--
                if (flight.users == 0 && flights[bookId] === flight) flights.remove(bookId)
            }
        }
    }
}
