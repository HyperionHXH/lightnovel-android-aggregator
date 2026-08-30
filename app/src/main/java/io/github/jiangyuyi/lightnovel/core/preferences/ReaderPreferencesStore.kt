package io.github.jiangyuyi.lightnovel.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jiangyuyi.lightnovel.core.model.LocalReadingProgress
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.readerDataStore by preferencesDataStore(name = "reader_preferences")

interface ReaderPreferencesAccess {
    val preferences: Flow<ReaderPreferences>
    suspend fun update(value: ReaderPreferences)
    fun progress(bookId: Long): Flow<LocalReadingProgress?>
    suspend fun saveProgress(bookId: Long, progress: LocalReadingProgress)
    fun sourceProgress(novelKey: NovelKey): Flow<ReadingProgress?>
    fun allSourceProgress(): Flow<List<ReadingProgress>> = flowOf(emptyList())
    suspend fun saveSourceProgress(progress: ReadingProgress)
}

object EmptyReaderPreferencesAccess : ReaderPreferencesAccess {
    override val preferences: Flow<ReaderPreferences> = flowOf(ReaderPreferences())
    override suspend fun update(value: ReaderPreferences) = Unit
    override fun progress(bookId: Long): Flow<LocalReadingProgress?> = flowOf(null)
    override suspend fun saveProgress(bookId: Long, progress: LocalReadingProgress) = Unit
    override fun sourceProgress(novelKey: NovelKey): Flow<ReadingProgress?> = flowOf(null)
    override suspend fun saveSourceProgress(progress: ReadingProgress) = Unit
}

class ReaderPreferencesStore(private val context: Context) : ReaderPreferencesAccess {
    override val preferences: Flow<ReaderPreferences> = context.readerDataStore.data.map { values ->
        ReaderPreferences(
            font = enumValueOrDefault(values[FONT], ReaderFont.SERIF),
            fontSize = (values[FONT_SIZE] ?: 19f).coerceIn(14f, 32f),
            lineHeight = (values[LINE_HEIGHT] ?: 1.7f).coerceIn(1.2f, 2.2f),
            horizontalPadding = (values[PADDING] ?: 22).coerceIn(12, 40),
            theme = enumValueOrDefault(values[THEME], ReaderTheme.SEPIA),
            mode = enumValueOrDefault(values[MODE], ReaderMode.PAGED),
            showProgressBar = values[SHOW_PROGRESS_BAR] ?: true,
        )
    }

    override suspend fun update(value: ReaderPreferences) {
        context.readerDataStore.edit { values ->
            values[FONT] = value.font.name
            values[FONT_SIZE] = value.fontSize.coerceIn(14f, 32f)
            values[LINE_HEIGHT] = value.lineHeight.coerceIn(1.2f, 2.2f)
            values[PADDING] = value.horizontalPadding.coerceIn(12, 40)
            values[THEME] = value.theme.name
            values[MODE] = value.mode.name
            values[SHOW_PROGRESS_BAR] = value.showProgressBar
        }
    }

    override fun progress(bookId: Long): Flow<LocalReadingProgress?> = context.readerDataStore.data.map { values ->
        values[stringPreferencesKey("progress_$bookId")]?.split(':')?.let { parts ->
            if (parts.size != 3) return@let null
            val chapterId = parts[0].toLongOrNull() ?: return@let null
            val paragraph = parts[1].toIntOrNull() ?: return@let null
            val percent = parts[2].toIntOrNull() ?: return@let null
            LocalReadingProgress(chapterId, paragraph, percent.coerceIn(0, 100))
        }
    }

    override suspend fun saveProgress(bookId: Long, progress: LocalReadingProgress) {
        context.readerDataStore.edit { values ->
            values[stringPreferencesKey("progress_$bookId")] =
                "${progress.chapterId}:${progress.paragraphIndex}:${progress.percent.coerceIn(0, 100)}"
        }
    }

    override fun sourceProgress(novelKey: NovelKey): Flow<ReadingProgress?> = context.readerDataStore.data.map { values ->
        values[sourceProgressKey(novelKey)]?.let { serialized ->
            runCatching { json.decodeFromString<ReadingProgress>(serialized) }.getOrNull()
        }
    }

    override fun allSourceProgress(): Flow<List<ReadingProgress>> = context.readerDataStore.data.map { values ->
        values.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(SOURCE_PROGRESS_PREFIX)) return@mapNotNull null
            (value as? String)?.let { serialized ->
                runCatching { json.decodeFromString<ReadingProgress>(serialized) }.getOrNull()
            }
        }.distinctBy(ReadingProgress::novelKey)
    }

    override suspend fun saveSourceProgress(progress: ReadingProgress) {
        context.readerDataStore.edit { values ->
            values[sourceProgressKey(progress.novelKey)] = json.encodeToString(progress)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: default

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val FONT = stringPreferencesKey("font")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val PADDING = intPreferencesKey("horizontal_padding")
        val THEME = stringPreferencesKey("theme")
        val MODE = stringPreferencesKey("reader_mode")
        val SHOW_PROGRESS_BAR = booleanPreferencesKey("show_progress_bar")

        const val SOURCE_PROGRESS_PREFIX = "source_progress_"

        fun sourceProgressKey(key: NovelKey) = stringPreferencesKey(
            "$SOURCE_PROGRESS_PREFIX${key.sourceId.length}_${key.sourceId}${key.remoteId}",
        )
    }
}
