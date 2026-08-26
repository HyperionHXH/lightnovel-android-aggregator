package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.offline.EmptyOfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.reader.EmptyChapterFontAccess
import io.github.jiangyuyi.lightnovel.core.source.ChapterContent
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.ReadingProgress
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.feature.sources.toSourceUiMessage
import io.github.jiangyuyi.lightnovel.feature.sources.runSourceCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily

data class SourceReaderState(
    val chapter: ChapterContent? = null,
    val chapterFontFamily: FontFamily? = null,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val restoredBlock: Int = 0,
    val loading: Boolean = true,
    val settingsVisible: Boolean = false,
    val error: String? = null,
)

class SourceReaderViewModel(
    private val novelKey: NovelKey,
    initialChapterKey: ChapterKey,
    private val registry: SourceRegistry,
    private val preferenceStore: ReaderPreferencesAccess,
    private val offlineLibrary: OfflineLibraryAccess = EmptyOfflineLibraryAccess,
    private val chapterFonts: ChapterFontAccess = EmptyChapterFontAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(SourceReaderState())
    val state: StateFlow<SourceReaderState> = _state.asStateFlow()
    private var currentChapterKey = initialChapterKey
    private var chapterJob: Job? = null
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            preferenceStore.preferences.collect { preferences ->
                _state.value = _state.value.copy(preferences = preferences)
            }
        }
        loadChapter(initialChapterKey)
    }

    fun loadChapter(chapterKey: ChapterKey) {
        if (chapterKey.sourceId != novelKey.sourceId) return
        val provider = registry.readerProvider(novelKey.sourceId)
        chapterJob?.cancel()
        progressJob?.cancel()
        currentChapterKey = chapterKey
        _state.value = _state.value.copy(
            chapter = null,
            chapterFontFamily = null,
            restoredBlock = 0,
            loading = true,
            settingsVisible = false,
            error = null,
        )
        chapterJob = viewModelScope.launch {
            val progress = preferenceStore.sourceProgress(novelKey).first()
            runSourceCatching {
                val chapter = offlineLibrary.readChapter(novelKey, chapterKey)
                    ?: provider?.getChapter(novelKey, chapterKey)
                    ?: error("该来源不支持阅读")
                chapter to chapterFonts.load(chapter.fontUrl)
            }
                .onSuccess { (chapter, chapterFontFamily) ->
                    if (currentChapterKey != chapterKey) return@onSuccess
                    _state.value = _state.value.copy(
                        chapter = chapter,
                        chapterFontFamily = chapterFontFamily,
                        restoredBlock = progress
                            ?.takeIf { it.chapterKey == chapterKey }
                            ?.paragraphIndex
                            ?: 0,
                        loading = false,
                        error = null,
                    )
                }
                .onFailure { error ->
                    if (currentChapterKey != chapterKey) return@onFailure
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.toSourceUiMessage("章节加载失败"),
                    )
                }
        }
    }

    fun retry() = loadChapter(currentChapterKey)

    fun previous() = _state.value.chapter?.previousChapterKey?.let(::loadChapter)

    fun next() = _state.value.chapter?.nextChapterKey?.let(::loadChapter)

    fun showSettings(show: Boolean) {
        _state.value = _state.value.copy(settingsVisible = show)
    }

    fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        val updated = transform(_state.value.preferences)
        viewModelScope.launch { preferenceStore.update(updated) }
    }

    fun saveProgress(blockIndex: Int, totalBlocks: Int) {
        val chapter = _state.value.chapter ?: return
        val percent = if (totalBlocks <= 1) 100 else {
            ((blockIndex.toFloat() / (totalBlocks - 1)) * 100).toInt().coerceIn(0, 100)
        }
        val progress = ReadingProgress(
            novelKey = novelKey,
            volumeKey = chapter.chapter.volumeKey,
            chapterKey = chapter.chapter.key,
            paragraphIndex = blockIndex.coerceAtLeast(0),
            percent = percent,
        )
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(800)
            preferenceStore.saveSourceProgress(progress)
            registry.readingProgressSyncProvider(novelKey.sourceId)?.let { sync ->
                runCatching { sync.saveReadingProgress(progress) }
            }
        }
    }
}
