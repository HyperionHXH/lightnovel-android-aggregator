package io.github.jiangyuyi.lightnovel.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import io.github.jiangyuyi.lightnovel.core.model.DiscoverChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoverState(
    val channel: DiscoverChannel = DiscoverChannel.HOT,
    val books: List<BookSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class DiscoverViewModel(private val repository: LightNovelRepository) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        select(DiscoverChannel.HOT)
    }

    fun select(channel: DiscoverChannel) {
        if (_state.value.channel == channel && _state.value.books.isNotEmpty()) return
        loadJob?.cancel()
        if (channel == DiscoverChannel.COLLECTION) {
            _state.value = DiscoverState(channel = channel, loading = false)
            return
        }
        _state.value = DiscoverState(channel = channel)
        loadJob = viewModelScope.launch {
            runCatching { repository.discover(channel) }
                .onSuccess { page -> _state.value = DiscoverState(channel, page.items, loading = false) }
                .onFailure { error ->
                    _state.value = DiscoverState(channel, loading = false, error = error.message ?: "加载失败")
                }
        }
    }

    fun retry() = select(_state.value.channel.also { _state.value = _state.value.copy(books = emptyList()) })
}
