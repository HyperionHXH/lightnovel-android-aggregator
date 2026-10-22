package io.github.jiangyuyi.lightnovel.core.cache

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class CachedDataSource(
    private val store: CacheStore,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val requestLocks = Array(64) { Mutex() }
    private val writes = Mutex()
    private val generation = AtomicLong()

    fun <T> updates(
        scope: String,
        key: String,
        policy: CachePolicy,
        serializer: KSerializer<T>,
        forceRefresh: Boolean = false,
        fetch: suspend () -> T,
    ): Flow<CacheUpdate<T>> = flow {
        val requestGeneration = generation.get()
        var cached = read(scope, key, policy, serializer)
        val startedAt = now()
        val cacheIsFresh = cached?.let { policy.isFresh(it.savedAtMillis, startedAt) } == true

        if (cached != null) {
            emit(
                CacheUpdate(
                    data = cached.data,
                    source = CacheSource.CACHE,
                    refreshing = forceRefresh || !cacheIsFresh,
                    savedAtMillis = cached.savedAtMillis,
                ),
            )
        }
        if (cacheIsFresh && !forceRefresh) return@flow

        val requestKey = "$scope\u0000$key"
        val lock = requestLocks[(requestKey.hashCode() and Int.MAX_VALUE) % requestLocks.size]
        lock.withLock {
            val latest = read(scope, key, policy, serializer)
            if (!forceRefresh && latest != null && latest.savedAtMillis > (cached?.savedAtMillis ?: 0L) &&
                policy.isFresh(latest.savedAtMillis, now())
            ) {
                emit(
                    CacheUpdate(
                        data = latest.data,
                        source = CacheSource.CACHE,
                        savedAtMillis = latest.savedAtMillis,
                    ),
                )
                return@withLock
            }

            try {
                val fresh = fetch()
                val savedAt = now()
                writes.withLock {
                    if (requestGeneration == generation.get()) {
                        store.write(
                            scope,
                            key,
                            SerializedCacheEntry(json.encodeToString(serializer, fresh), savedAt),
                            policy.disk,
                        )
                    }
                }
                emit(CacheUpdate(fresh, CacheSource.NETWORK, savedAtMillis = savedAt))
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                cached = latest ?: cached
                val fallback = cached ?: throw throwable
                emit(
                    CacheUpdate(
                        data = fallback.data,
                        source = CacheSource.CACHE,
                        savedAtMillis = fallback.savedAtMillis,
                        error = throwable,
                    ),
                )
            }
        }
    }

    suspend fun remove(scope: String, key: String) = store.remove(scope, key)

    suspend fun removePrefix(scope: String, keyPrefix: String) = store.removePrefix(scope, keyPrefix)

    suspend fun clearPrivate() = writes.withLock {
        generation.incrementAndGet()
        store.clearPrivate()
    }

    suspend fun clearAll() = writes.withLock {
        generation.incrementAndGet()
        store.clearAll()
    }

    private suspend fun <T> read(
        scope: String,
        key: String,
        policy: CachePolicy,
        serializer: KSerializer<T>,
    ): DecodedCacheEntry<T>? {
        val entry = store.read(scope, key, policy.disk) ?: return null
        return runCatching {
            DecodedCacheEntry(
                data = json.decodeFromString(serializer, entry.payload),
                savedAtMillis = entry.savedAtMillis,
            )
        }.getOrElse {
            store.remove(scope, key)
            null
        }
    }
}

private data class DecodedCacheEntry<T>(
    val data: T,
    val savedAtMillis: Long,
)
