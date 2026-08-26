package io.github.jiangyuyi.lightnovel.core.updates

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sourceUpdateNotificationDataStore by preferencesDataStore(
    name = "source_update_notifications",
)

@Serializable
data class SourceUpdateNotificationSnapshot(
    val novelKey: NovelKey,
    val chapterCount: Int = 0,
    val unreadChapterCount: Int? = null,
)

interface SourceUpdateNotificationSnapshotAccess {
    val snapshots: Flow<List<SourceUpdateNotificationSnapshot>>
    suspend fun saveAll(values: List<SourceUpdateNotificationSnapshot>)
}

object EmptySourceUpdateNotificationSnapshotAccess : SourceUpdateNotificationSnapshotAccess {
    override val snapshots: Flow<List<SourceUpdateNotificationSnapshot>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun saveAll(values: List<SourceUpdateNotificationSnapshot>) = Unit
}

class SourceUpdateNotificationSnapshotStore(private val context: Context) : SourceUpdateNotificationSnapshotAccess {
    override val snapshots: Flow<List<SourceUpdateNotificationSnapshot>> =
        context.sourceUpdateNotificationDataStore.data.map { values ->
            values.asMap().mapNotNull { (key, value) ->
                if (!key.name.startsWith(SNAPSHOT_PREFIX)) return@mapNotNull null
                (value as? String)?.let { serialized ->
                    runCatching { json.decodeFromString<SourceUpdateNotificationSnapshot>(serialized) }.getOrNull()
                }
            }.distinctBy(SourceUpdateNotificationSnapshot::novelKey)
        }

    override suspend fun saveAll(values: List<SourceUpdateNotificationSnapshot>) {
        if (values.isEmpty()) return
        context.sourceUpdateNotificationDataStore.edit { preferences ->
            values.forEach { snapshot ->
                preferences[snapshotKey(snapshot.novelKey)] = json.encodeToString(snapshot)
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val SNAPSHOT_PREFIX = "notified_"

        fun snapshotKey(key: NovelKey) = stringPreferencesKey(
            "$SNAPSHOT_PREFIX${key.sourceId.length}_${key.sourceId}${key.remoteId}",
        )
    }
}

class UpdateNotificationSettings(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(applicationContext)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: Flow<Boolean> = _enabled.asStateFlow()

    init {
        if (_enabled.value) schedule()
    }

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        if (enabled) schedule() else workManager.cancelUniqueWork(WORK_NAME)
    }

    fun isEnabled(): Boolean = _enabled.value

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<BookshelfUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val PREFERENCES_NAME = "update_notification_preferences"
        const val KEY_ENABLED = "enabled"
        const val WORK_NAME = "bookshelf-update-check"
    }
}
