package io.github.jiangyuyi.lightnovel.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.SourceDescriptor
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.SourceNovelCard
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AggregateSearchScreen(
    viewModel: AggregateSearchViewModel,
    onBook: (NovelKey) -> Unit,
    onAccounts: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val interleavedResults = interleaveSourceResults(state.sources)

    RefreshableLazyColumn(
        isRefreshing = state.sources.any { it.loading },
        onRefresh = viewModel::searchNow,
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TopAppBar(title = { Text("聚合搜索") })
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("书名 / 作者 / 关键词") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focus.clearFocus()
                            viewModel.searchNow()
                        },
                    ),
                )
                Button(
                    onClick = {
                        focus.clearFocus()
                        viewModel.searchNow()
                    },
                ) { Text("搜索") }
            }
        }

        if (state.query.isBlank()) {
            item { EmptyPane("输入关键词，同时搜索轻之国度和轻书架") }
        } else {
            state.sources.forEach { source ->
                item(key = "header-${source.descriptor.id}") {
                    SourceResultHeader(source)
                }
                when {
                    source.loading && source.items.isEmpty() -> item(key = "loading-${source.descriptor.id}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在搜索…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    source.errorMessage != null -> item(key = "error-${source.descriptor.id}") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(source.errorMessage, color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = viewModel::retry) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "重试")
                                }
                                if (source.errorKind == SourceErrorKind.AUTHENTICATION) {
                                    TextButton(onClick = onAccounts) { Text("前往登录") }
                                }
                            }
                        }
                    }

                    source.searched && source.items.isEmpty() -> item(key = "empty-${source.descriptor.id}") {
                        Text(
                            "没有找到匹配书籍",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Unit
                }
            }
            items(
                items = interleavedResults,
                key = { result -> "${result.novel.key.sourceId}:${result.novel.key.remoteId}" },
            ) { result ->
                SourceNovelCard(
                    novel = result.novel,
                    sourceName = result.source.displayName,
                    onClick = { onBook(result.novel.key) },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

internal data class SourceNovelResult(
    val source: SourceDescriptor,
    val novel: NovelSummary,
)

internal fun interleaveSourceResults(sources: List<SourceSearchUiState>): List<SourceNovelResult> {
    val largestSource = sources.maxOfOrNull { it.items.size } ?: return emptyList()
    return buildList {
        repeat(largestSource) { index ->
            sources.forEach { source ->
                source.items.getOrNull(index)?.let { novel ->
                    add(SourceNovelResult(source.descriptor, novel))
                }
            }
        }
    }
}

@Composable
private fun SourceResultHeader(source: SourceSearchUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            source.descriptor.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (source.items.isNotEmpty()) {
            Text(
                "${source.items.size} 本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
