package io.github.jiangyuyi.lightnovel.core.local

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Runs metadata work concurrently and reports each result as soon as it is ready. */
internal suspend fun <T, R> progressivelyLoad(
    items: List<T>,
    maxConcurrency: Int,
    load: suspend (T) -> R,
    onLoaded: (R) -> Unit,
) = coroutineScope {
    val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    items.map { item ->
        launch {
            val loaded = semaphore.withPermit { load(item) }
            onLoaded(loaded)
        }
    }.forEach { it.join() }
}

internal class LocalBookMetadataCache(
    private val readValue: (String) -> String?,
    private val writeValues: (Map<String, String>) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val pending = ConcurrentHashMap<String, String>()

    fun get(uri: String, sizeBytes: Long, lastModified: Long): LocalBookRecord? =
        runCatching {
            val key = LocalBookParser.stableId(uri)
            (pending[key] ?: readValue(key))
                ?.let { json.decodeFromString<LocalBookRecord>(it) }
                ?.takeIf { record ->
                    record.uri == uri &&
                        record.sizeBytes == sizeBytes &&
                        record.lastModified == lastModified &&
                        record.available
                }
        }.getOrNull()

    fun put(record: LocalBookRecord) {
        if (!record.available) return
        runCatching {
            pending[LocalBookParser.stableId(record.uri)] = json.encodeToString(record)
        }
    }

    fun flush() {
        if (pending.isEmpty()) return
        val values = pending.toMap()
        pending.clear()
        writeValues(values)
    }
}
