package io.github.jiangyuyi.lightnovel.core.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Screen-local, bounded metadata only; detail/network coalescing remains the adapter's responsibility. */
internal class ListMetadataHydrator(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Metadata(val authors: List<String>, val synopsis: String, val expiresAt: Long) {
        val weight: Int get() = synopsis.length + authors.sumOf(String::length)
    }

    private val cache = LinkedHashMap<NovelKey, Metadata>(16, 0.75f, true)
    private var cacheWeight = 0

    fun withCachedMetadata(novel: NovelSummary): NovelSummary {
        val metadata = synchronized(cache) {
            cache[novel.key]?.also {
                if (it.expiresAt <= nowMillis()) {
                    cache.remove(novel.key)
                    cacheWeight -= it.weight
                }
            }?.takeIf { it.expiresAt > nowMillis() }
        } ?: return novel
        return novel.withMissingMetadata(metadata.authors, metadata.synopsis)
    }

    fun pendingAuthors(items: List<NovelSummary>): Set<NovelKey> = synchronized(cache) {
        val now = nowMillis()
        buildSet {
            items.forEach { novel ->
                if (novel.authors.isNotEmpty()) return@forEach
                val metadata = cache[novel.key]
                if (metadata != null && metadata.expiresAt > now) return@forEach
                if (metadata != null) {
                    cache.remove(novel.key)
                    cacheWeight -= metadata.weight
                }
                add(novel.key)
            }
        }
    }

    suspend fun hydrate(
        provider: DetailProvider,
        items: List<NovelSummary>,
        includeAuthors: Boolean,
        onMetadata: (NovelKey, NovelSummary) -> Unit,
        onFailure: (Throwable) -> Unit,
        onSettled: (NovelKey) -> Unit = {},
    ) = coroutineScope {
        val permits = Semaphore(4)
        items.distinctBy { it.key }.forEach { original ->
            val novel = withCachedMetadata(original)
            val cached = synchronized(cache) { cache.containsKey(novel.key) }
            if (cached) {
                onMetadata(novel.key, novel)
            } else if (novel.synopsis.isBlank() || (includeAuthors && novel.authors.isEmpty())) {
                launch {
                    var delivered = false
                    try {
                        val detail = permits.withPermit {
                            withTimeout(8_000) { provider.getNovelDetail(novel.key).novel }
                        }
                        currentCoroutineContext().ensureActive()
                        require(detail.key == novel.key) { "detail belongs to a different source resource" }
                        val metadata = Metadata(detail.authors, detail.synopsis, nowMillis() + TTL_MILLIS)
                        synchronized(cache) {
                            cache.remove(novel.key)?.let { cacheWeight -= it.weight }
                            // A pathological synopsis must not turn a small metadata cache into an unbounded one.
                            if (metadata.weight <= MAX_CHARACTERS) {
                                cache[novel.key] = metadata
                                cacheWeight += metadata.weight
                                while (cache.size > MAX_ENTRIES || cacheWeight > MAX_CHARACTERS) {
                                    val eldest = cache.entries.iterator()
                                    cacheWeight -= eldest.next().value.weight
                                    eldest.remove()
                                }
                            }
                        }
                        onMetadata(novel.key, novel.withMissingMetadata(detail.authors, detail.synopsis))
                        delivered = true
                    } catch (error: TimeoutCancellationException) {
                        onFailure(SourceException(SourceErrorKind.TIMEOUT, "metadata request timed out", error))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        onFailure(error)
                    } finally {
                        if (!delivered) onSettled(novel.key)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 128
        const val MAX_CHARACTERS = 256 * 1024
        const val TTL_MILLIS = 5 * 60 * 1_000L
    }
}

/** Only fill missing card metadata, never replace list identity, cover, counts or shelf/update state. */
internal fun NovelSummary.withMissingMetadata(authors: List<String>, synopsis: String): NovelSummary = copy(
    authors = this.authors.ifEmpty { authors },
    synopsis = this.synopsis.ifBlank { synopsis },
)
