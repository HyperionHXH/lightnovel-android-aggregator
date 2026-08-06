package io.github.jiangyuyi.lightnovel.feature.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.model.BookDetail
import io.github.jiangyuyi.lightnovel.core.model.ChapterSummary
import io.github.jiangyuyi.lightnovel.core.model.Comment
import io.github.jiangyuyi.lightnovel.core.model.Volume
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookState(
    val detail: BookDetail? = null,
    val volumes: List<Volume> = emptyList(),
    val chapters: Map<Long, List<ChapterSummary>> = emptyMap(),
    val expandedVolumeId: Long? = null,
    val loadingVolumeId: Long? = null,
    val comments: List<Comment> = emptyList(),
    val commentsAvailable: Boolean = true,
    val inBookshelf: Boolean = false,
    val loading: Boolean = true,
    val shelfLoading: Boolean = false,
    val error: String? = null,
)

class BookViewModel(
    private val bookId: Long,
    private val repository: LightNovelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BookState())
    val state: StateFlow<BookState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = BookState(loading = true)
        viewModelScope.launch {
            val detailRequest = async { repository.bookDetail(bookId) }
            val volumeRequest = async { repository.volumes(bookId).items }
            val shelfRequest = async { runCatching { repository.isInBookshelf(bookId) }.getOrDefault(false) }
            val commentsRequest = async { runCatching { repository.comments(bookId).items } }
            runCatching {
                val detail = detailRequest.await()
                val volumes = volumeRequest.await()
                val comments = commentsRequest.await()
                BookState(
                    detail = detail,
                    volumes = volumes,
                    comments = comments.getOrDefault(emptyList()),
                    commentsAvailable = comments.isSuccess,
                    inBookshelf = shelfRequest.await(),
                    loading = false,
                )
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = BookState(loading = false, error = it.message ?: "书籍加载失败") }
        }
    }

    fun toggleVolume(volumeId: Long) {
        if (_state.value.expandedVolumeId == volumeId) {
            _state.value = _state.value.copy(expandedVolumeId = null)
            return
        }
        _state.value = _state.value.copy(expandedVolumeId = volumeId)
        if (_state.value.chapters.containsKey(volumeId)) return
        _state.value = _state.value.copy(loadingVolumeId = volumeId)
        viewModelScope.launch {
            runCatching { repository.chapters(bookId, volumeId).items }
                .onSuccess { chapters ->
                    _state.value = _state.value.copy(
                        chapters = _state.value.chapters + (volumeId to chapters),
                        loadingVolumeId = null,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingVolumeId = null, error = it.message) }
        }
    }

    fun toggleBookshelf(onLoginRequired: () -> Unit) {
        if (!repository.session.value.loggedIn) {
            onLoginRequired()
            return
        }
        if (_state.value.shelfLoading) return
        val target = !_state.value.inBookshelf
        _state.value = _state.value.copy(shelfLoading = true)
        viewModelScope.launch {
            runCatching { repository.setBookshelf(bookId, target) }
                .onSuccess { _state.value = _state.value.copy(inBookshelf = it, shelfLoading = false) }
                .onFailure { _state.value = _state.value.copy(shelfLoading = false, error = it.message) }
        }
    }

    suspend fun readingTarget(): Long = repository.readerBootstrap(bookId).chapterId
}

