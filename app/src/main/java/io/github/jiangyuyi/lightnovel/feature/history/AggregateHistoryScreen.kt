package io.github.jiangyuyi.lightnovel.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.SourceNovelCard
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AggregateHistoryScreen(
    viewModel: AggregateHistoryViewModel,
    onBack: () -> Unit,
    onOpen: (NovelKey, ChapterKey?) -> Unit,
    onAccounts: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results = viewModel.results(state)
    val initialLoading = results.isEmpty() && state.sources.any { it.loading }
    val allLoaded = state.sources.isNotEmpty() && state.sources.all { it.loaded }
    var deleteTarget by remember { mutableStateOf<AggregateHistoryResult?>(null) }

    RefreshableLazyColumn(
        isRefreshing = state.sources.any { it.loading || it.refreshing },
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TopAppBar(
                title = { Text("阅读历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }

        state.actionError?.let { message ->
            item {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.sources.filter { it.errorMessage != null }.forEach { source ->
            item(key = "error-${source.descriptor.id}") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${source.descriptor.displayName}：${source.errorMessage}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重试")
                        }
                        if (source.errorKind == SourceErrorKind.AUTHENTICATION) {
                            TextButton(onClick = onAccounts) { Text("前往登录") }
                        }
                    }
                }
            }
        }

        when {
            state.sources.isEmpty() -> item { EmptyPane("没有支持阅读历史的在线来源") }
            initialLoading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            allLoaded && results.isEmpty() && state.sources.none { it.errorMessage != null } -> item {
                EmptyPane("还没有阅读记录")
            }
            else -> items(
                items = results,
                key = { result ->
                    "history:${result.entry.novel.key.sourceId}:${result.entry.novel.key.remoteId}"
                },
            ) { result ->
                val entry = result.entry
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceNovelCard(
                        novel = entry.novel,
                        sourceName = result.source.displayName,
                        onClick = { onOpen(entry.novel.key, entry.lastChapterKey) },
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.lastChapterTitle.ifBlank {
                                    if (entry.lastChapterKey != null) "上次阅读位置" else "打开详情"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (entry.readAt.isNotBlank()) {
                                Text(
                                    entry.readAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = { onOpen(entry.novel.key, entry.lastChapterKey) }) {
                            Text(if (entry.lastChapterKey == null) "查看" else "继续阅读")
                        }
                        if (result.canDelete) {
                            IconButton(
                                onClick = { deleteTarget = result },
                                enabled = entry.novel.key !in state.deleting,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = if (entry.novel.key in state.deleting) "删除中" else "删除",
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.sources.any { it.hasMore || it.loadingMore }) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        onClick = viewModel::loadMore,
                        enabled = state.sources.none { it.loadingMore },
                    ) {
                        Text(if (state.sources.any { it.loadingMore }) "加载中" else "加载更多")
                    }
                }
            }
        }
    }

    deleteTarget?.let { result ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除阅读记录") },
            text = {
                Text(
                    "将从${result.source.displayName}账户删除《${result.entry.novel.title}》的阅读记录。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(result)
                        deleteTarget = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
