package io.github.jiangyuyi.lightnovel.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookshelfState(
    val books: List<BookSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class BookshelfViewModel(private val repository: LightNovelRepository) : ViewModel() {
    private val _state = MutableStateFlow(BookshelfState())
    val state: StateFlow<BookshelfState> = _state.asStateFlow()

    fun refresh() {
        if (!repository.session.value.loggedIn) {
            _state.value = BookshelfState()
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { repository.bookshelf() }
                .onSuccess { _state.value = BookshelfState(books = it) }
                .onFailure { _state.value = BookshelfState(error = it.message ?: "书架加载失败") }
        }
    }
}

