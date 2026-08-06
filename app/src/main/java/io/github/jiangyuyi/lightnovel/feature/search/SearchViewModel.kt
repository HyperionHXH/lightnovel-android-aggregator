package io.github.jiangyuyi.lightnovel.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.model.BookSummary
import io.github.jiangyuyi.lightnovel.core.model.SearchTaxonomy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val workType: String = "",
    val primaryTag: String = "",
    val taxonomy: SearchTaxonomy = SearchTaxonomy(emptyList(), emptyList()),
    val results: List<BookSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(private val repository: LightNovelRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { repository.taxonomy() }.onSuccess { taxonomy ->
                _state.value = _state.value.copy(taxonomy = taxonomy)
            }
        }
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun setWorkType(value: String) {
        _state.value = _state.value.copy(
            query = "",
            primaryTag = "",
            workType = if (_state.value.workType == value) "" else value,
        )
        search()
    }

    fun setTag(value: String) {
        _state.value = _state.value.copy(
            query = "",
            workType = "",
            primaryTag = if (_state.value.primaryTag == value) "" else value,
        )
        search()
    }

    fun search() {
        searchJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        searchJob = viewModelScope.launch {
            runCatching {
                repository.search(_state.value.query, _state.value.workType, _state.value.primaryTag)
            }.onSuccess { page ->
                _state.value = _state.value.copy(results = page.items, loading = false)
            }.onFailure { error ->
                _state.value = _state.value.copy(loading = false, error = error.message ?: "搜索失败")
            }
        }
    }
}
