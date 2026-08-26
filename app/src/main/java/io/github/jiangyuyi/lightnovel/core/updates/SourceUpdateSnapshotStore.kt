package io.github.jiangyuyi.lightnovel.core.updates

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sourceUpdateDataStore by preferencesDataStore(name = "source_update_snapshots")

@Serializable
data class SourceUpdateSnapshot(
    val novelKey: NovelKey,
    val chapterCount: Int = 0,
    val unreadChapterCount: Int? = null,
    val acknowledgedChapterCount: Int? = null,
    val acknowledgedUnreadChapterCount: Int? = null,
    val observedAtEpochMillis: Long = 0,
)

interface SourceUpdateSnapshotAccess {
    val snapshots: Flow<List<SourceUpdateSnapshot>>
    suspend fun saveAll(values: List<SourceUpdateSnapshot>)
}

object EmptySourceUpdateSnapshotAccess : SourceUpdateSnapshotAccess {
    override val snapshots: Flow<List<SourceUpdateSnapshot>> = flowOf(emptyList())
    override suspend fun saveAll(values: List<SourceUpdateSnapshot>) = Unit
}

class SourceUpdateSnapshotStore(private val context: Context) : SourceUpdateSnapshotAccess {
    override val snapshots: Flow<List<SourceUpdateSnapshot>> = context.sourceUpdateDataStore.data.map { values ->
        values.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(SOURCE_UPDATE_PREFIX)) return@mapNotNull null
            (value as? String)?.let { serialized ->
                runCatching { json.decodeFromString<SourceUpdateSnapshot>(serialized) }.getOrNull()
            }
        }.distinctBy(SourceUpdateSnapshot::novelKey)
    }

    override suspend fun saveAll(values: List<SourceUpdateSnapshot>) {
        if (values.isEmpty()) return
        context.sourceUpdateDataStore.edit { preferences ->
            values.forEach { snapshot ->
                preferences[sourceUpdateKey(snapshot.novelKey)] = json.encodeToString(snapshot)
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val SOURCE_UPDATE_PREFIX = "source_update_"

        fun sourceUpdateKey(key: NovelKey) = stringPreferencesKey(
            "$SOURCE_UPDATE_PREFIX${key.sourceId.length}_${key.sourceId}${key.remoteId}",
        )
    }
}
