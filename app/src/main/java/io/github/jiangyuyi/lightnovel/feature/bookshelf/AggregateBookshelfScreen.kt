package io.github.jiangyuyi.lightnovel.feature.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.SourceNovelCard
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn
import io.github.jiangyuyi.lightnovel.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AggregateBookshelfScreen(
    viewModel: AggregateBookshelfViewModel,
    onBook: (NovelKey) -> Unit,
    onAccounts: () -> Unit,
    title: String = "我的书架",
    onBack: (() -> Unit)? = null,
    showTabs: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleSources = visibleBookshelfSources(state)
    val results = interleaveBookshelfResults(visibleSources, state.updatedBooks)
    val allLoaded = visibleSources.isNotEmpty() && visibleSources.all { it.loaded }
    val selectedTabIndex = if (state.downloadedOnly) 1 else 0
    val updateSummary = bookshelfUpdateSummary(state)
    var deleteTarget by remember { mutableStateOf<OfflineBookRecord?>(null) }
    var exportTarget by remember { mutableStateOf<OfflineBookRecord?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        val record = exportTarget
        exportTarget = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        exportJob = exportScope.launch {
            try {
                exportProgress = 0 to record.chapters.count { !it.locked && it.key.remoteId in record.downloadedChapterIds }
                val result =
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    viewModel.exportEpub(record, output) { progress ->
                        exportProgress = progress.completed to progress.total
                    }
                } ?: error("无法打开导出位置")
                exportMessage = "已导出 ${result?.exportedChapters ?: 0} 章"
            } catch (_: CancellationException) {
                exportMessage = "已取消 EPUB 导出"
            } catch (error: Throwable) {
                exportMessage = error.message.orEmpty().ifBlank { "EPUB 导出失败" }
            } finally {
                exportProgress = null
                exportJob = null
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    RefreshableLazyColumn(
        isRefreshing = visibleSources.any { it.loading || it.refreshing },
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!state.downloadedOnly && state.updatedBooks.isNotEmpty()) {
                        TextButton(onClick = viewModel::markAllUpdatesSeen) { Text("全部标为已读") }
                    }
                },
            )
            if (showTabs) {
                ScrollableTabRow(selectedTabIndex = selectedTabIndex, edgePadding = 10.dp) {
                    Tab(
                        selected = !state.downloadedOnly,
                        onClick = viewModel::selectAll,
                        text = { Text("全部") },
                    )
                    Tab(
                        selected = state.downloadedOnly,
                        onClick = viewModel::selectDownloaded,
                        text = { Text("已下载") },
                    )
                }
            }
        }
        exportMessage?.let { message ->
            item(key = "epub-export-message") {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        exportProgress?.let { (completed, total) ->
            item(key = "epub-export-progress") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("正在导出章节 $completed/$total", modifier = Modifier.weight(1f))
                    IconButton(onClick = { exportJob?.cancel() }) {
                        Icon(Icons.Filled.Close, contentDescription = "取消导出")
                    }
                }
            }
        }

        if (!state.downloadedOnly) {
            updateSummary?.let { summary ->
                item(key = "bookshelf-update-summary") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("发现 ${summary.totalBooks} 本书有更新")
                            Text(
                                summary.bySource.joinToString("、") { group ->
                                    "${group.sourceName} ${group.count} 本"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        visibleSources.forEach { source ->
            if (source.errorMessage != null) {
                item(key = "error-${source.descriptor.id}") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
        }

        if (!state.downloadedOnly && results.isEmpty() && visibleSources.any { it.loading || it.refreshing }) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        }

        when {
            state.downloadedOnly && state.offlineBooks.isEmpty() -> item {
                EmptyPane("还没有已下载的书籍")
            }
            state.downloadedOnly -> items(
                items = state.offlineBooks,
                key = { record -> "offline:${record.novel.key.sourceId}:${record.novel.key.remoteId}" },
            ) { record ->
                val sourceName = state.sourceOptions.firstOrNull { it.id == record.novel.key.sourceId }
                    ?.displayName
                    ?: record.novel.key.sourceId
                OfflineDownloadItem(
                    record = record,
                    sourceName = sourceName,
                    onOpen = { onBook(record.novel.key) },
                    onRetry = { viewModel.retryDownload(record) },
                    onDelete = { deleteTarget = record },
                    onExport = {
                        exportTarget = record
                        exportLauncher.launch(epubFileName(record))
                    },
                )
            }
            state.sourceOptions.isEmpty() -> item { EmptyPane("没有支持书架的在线来源") }
            allLoaded && results.isEmpty() && visibleSources.none { it.errorMessage != null } -> {
                item { EmptyPane("书架还是空的，去发现喜欢的作品吧") }
            }
            else -> items(
                items = results,
                key = { result -> "${result.novel.key.sourceId}:${result.novel.key.remoteId}" },
            ) { result ->
                SourceNovelCard(
                    novel = result.novel,
                    sourceName = result.source.displayName,
                    showUnreadChapterCount = true,
                    updateLabel = "发现新章节".takeIf {
                        result.hasUpdates && result.novel.unreadChapterCount == null
                    },
                    onClick = { onBook(result.novel.key) },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }

    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除离线书籍") },
            text = { Text("将删除《${record.novel.title}》的离线章节，不会移出线上书架。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDownload(record)
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

@Composable
private fun OfflineDownloadItem(
    record: OfflineBookRecord,
    sourceName: String,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SourceNovelCard(
            novel = record.novel,
            sourceName = sourceName,
            showUnreadChapterCount = true,
            onClick = onOpen,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        if (record.status == OfflineDownloadStatus.QUEUED ||
            record.status == OfflineDownloadStatus.DOWNLOADING
        ) {
            val progress = if (record.totalChapters == 0) 0f else {
                record.completedChapters.toFloat() / record.totalChapters
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                offlineStatusLabel(record),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (record.status == OfflineDownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (record.status == OfflineDownloadStatus.FAILED) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重试下载")
                }
            }
            if (record.status == OfflineDownloadStatus.COMPLETE) {
                IconButton(onClick = onExport) {
                    Icon(painterResource(R.drawable.ic_file_download), contentDescription = "导出 EPUB")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除离线书籍")
            }
        }
    }
}

private fun epubFileName(record: OfflineBookRecord): String =
    record.novel.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "novel" } + ".epub"

private fun offlineStatusLabel(record: OfflineBookRecord): String = when (record.status) {
    OfflineDownloadStatus.QUEUED -> "等待下载"
    OfflineDownloadStatus.DOWNLOADING -> "已下载 ${record.completedChapters}/${record.totalChapters} 章"
    OfflineDownloadStatus.COMPLETE -> "已下载 ${record.completedChapters} 章"
    OfflineDownloadStatus.FAILED -> record.error ?: "下载失败"
}
