package io.github.jiangyuyi.lightnovel.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.SourceNovelCard
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AggregateDiscoverScreen(
    viewModel: AggregateDiscoverViewModel,
    onBook: (NovelKey) -> Unit,
    onAccounts: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeSource = state.sources.singleOrNull()
    val results = activeSource?.items.orEmpty()
    val allLoaded = state.sources.isNotEmpty() && state.sources.all { it.loaded }

    RefreshableLazyColumn(
        isRefreshing = state.sources.any { it.loading },
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TopAppBar(
                title = { Text("发现") },
            )
            ScrollableTabRow(
                selectedTabIndex = state.sourceOptions.indexOfFirst {
                    it.descriptor.id == state.selectedSourceId
                }.coerceAtLeast(0),
                edgePadding = 10.dp,
            ) {
                state.sourceOptions.forEach { source ->
                    Tab(
                        selected = state.selectedSourceId == source.descriptor.id,
                        onClick = { viewModel.selectSource(source.descriptor.id) },
                        text = { Text(source.descriptor.displayName) },
                    )
                }
            }
            if (state.feeds.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = state.feeds.indexOf(state.selectedFeed).coerceAtLeast(0),
                    edgePadding = 10.dp,
                ) {
                    state.feeds.forEach { feed ->
                        Tab(
                            selected = state.selectedFeed == feed,
                            onClick = { viewModel.selectFeed(feed) },
                            text = { Text(feed.label) },
                        )
                    }
                }
            }
        }

        state.sources.forEach { source ->
            item(key = "header-${source.descriptor.id}") {
                DiscoverSourceHeader(source)
            }
            if (source.errorMessage != null) {
                item(key = "error-${source.descriptor.id}") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            }
        }

        when {
            state.sourceOptions.isEmpty() -> item { EmptyPane("没有可用的在线来源") }
            state.selectedFeed == null -> item { EmptyPane("当前来源没有可用榜单") }
            allLoaded && results.isEmpty() && state.sources.none { it.errorMessage != null } -> {
                item { EmptyPane("此榜单暂时没有可显示的作品") }
            }
            else -> items(
                items = results,
                key = { novel -> "${novel.key.sourceId}:${novel.key.remoteId}" },
            ) { novel ->
                SourceNovelCard(
                    novel = novel,
                    sourceName = activeSource?.descriptor?.displayName.orEmpty(),
                    onClick = { onBook(novel.key) },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoverSourceHeader(source: DiscoverSourceUiState) {
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
        when {
            source.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            source.items.isNotEmpty() -> Text(
                "${source.items.size} 本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
