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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.OutlinedButton
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

private enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    EPUB("epub", "application/epub+zip", "EPUB"),
    TXT("txt", "text/plain", "TXT"),
}

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
    var directoryTarget by remember { mutableStateOf<NovelKey?>(null) }
    var exportChooserTarget by remember { mutableStateOf<OfflineBookRecord?>(null) }
    var exportTarget by remember { mutableStateOf<OfflineBookRecord?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    fun runUriExport(uri: android.net.Uri, record: OfflineBookRecord, format: ExportFormat) {
        exportJob = exportScope.launch {
            try {
                exportProgress = 0 to record.completedChapters
                val exportedChapters = context.contentResolver.openOutputStream(uri)?.use { output ->
                    when (format) {
                        ExportFormat.EPUB -> viewModel.exportEpub(record, output) { progress ->
                            exportProgress = progress.completed to progress.total
                        }?.exportedChapters ?: 0
                        ExportFormat.TXT -> viewModel.exportTxt(record, output) { progress ->
                            exportProgress = progress.completed to progress.total
                        }?.exportedChapters ?: 0
                    }
                } ?: error("无法打开导出位置")
                exportMessage = "已导出 $exportedChapters 章 ${format.label}"
            } catch (_: CancellationException) {
                exportMessage = "已取消 ${format.label} 导出"
            } catch (error: Throwable) {
                exportMessage = error.message.orEmpty().ifBlank { "${format.label} 导出失败" }
            } finally {
                exportProgress = null
                exportJob = null
            }
        }
    }
    val exportEpubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.EPUB.mimeType),
    ) { uri ->
        val record = exportTarget
        exportTarget = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        runUriExport(uri, record, ExportFormat.EPUB)
    }
    val exportTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.TXT.mimeType),
    ) { uri ->
        val record = exportTarget
        exportTarget = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        runUriExport(uri, record, ExportFormat.TXT)
    }

    fun startExport(record: OfflineBookRecord, format: ExportFormat) {
        if (exportJob != null) return
        exportJob = exportScope.launch {
            try {
                exportProgress = 0 to record.completedChapters
                val result = when (format) {
                    ExportFormat.EPUB -> viewModel.exportEpubToDownloadDirectory(record) { progress ->
                        exportProgress = progress.completed to progress.total
                    }
                    ExportFormat.TXT -> viewModel.exportTxtToDownloadDirectory(record) { progress ->
                        exportProgress = progress.completed to progress.total
                    }
                }
                if (result == null) {
                    exportTarget = record
                    when (format) {
                        ExportFormat.EPUB -> exportEpubLauncher.launch(epubFileName(record))
                        ExportFormat.TXT -> exportTxtLauncher.launch(txtFileName(record))
                    }
                } else {
                    exportMessage = "已保存 ${result.fileName}（${result.exportedChapters} 章）"
                }
            } catch (_: CancellationException) {
                exportMessage = "已取消 ${format.label} 导出"
            } catch (error: Throwable) {
                exportMessage = error.message.orEmpty().ifBlank { "${format.label} 导出失败" }
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
            if (title == "下载与导出") {
                val directory = viewModel.downloadDirectory.collectAsStateWithLifecycle().value
                Text(
                    "保存位置：${downloadDirectoryLabel(directory)}",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    showManagementActions = title == "下载与导出",
                    onRetry = { viewModel.retryDownload(record) },
                    onDelete = { deleteTarget = record },
                    onExport = { exportChooserTarget = record },
                    onDirectory = { directoryTarget = record.novel.key },
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
                    metadataLoading = visibleSources.any { result.novel.key in it.pendingMetadata },
                )
            }
        }
    }

    state.offlineBooks.firstOrNull { it.novel.key == directoryTarget }?.let { record ->
        AlertDialog(
            onDismissRequest = { directoryTarget = null },
            title = { Text("下载章节 · ${record.novel.title}") },
            text = {
                val groups = record.chapters.groupBy { it.volumeKey }
                val volumeKeys = (record.volumes.map { it.key } + groups.keys).distinct()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (volumeKeys.isEmpty()) item { Text("等待加载目录，尚无已下载章节") }
                    volumeKeys.forEach { key ->
                        val chapters = groups[key].orEmpty()
                        item(key = "volume:${key.sourceId}:${key.remoteId}") {
                            val title = record.volumes.firstOrNull { it.key == key }?.title ?: key.remoteId
                            val completed = chapters.count { it.key.remoteId in record.downloadedChapterIds }
                            Text("$title · 已下载 $completed/${chapters.count { !it.locked || it.key.remoteId in record.downloadedChapterIds || it.key.remoteId in record.unlockedChapterIds }} 章", style = MaterialTheme.typography.titleSmall)
                        }
                        items(chapters, key = { "chapter:${it.key.sourceId}:${it.key.remoteId}" }) { chapter ->
                            Column {
                                Text("${chapter.title} · ${record.chapterStatus(chapter).label}")
                                record.failedChapters[chapter.key.remoteId]?.takeIf { !chapter.locked }?.let { error ->
                                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { directoryTarget = null }) { Text("关闭") } },
        )
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

    exportChooserTarget?.let { record ->
        ExportFormatDialog(
            record = record,
            onDismiss = { exportChooserTarget = null },
            onExport = { format ->
                exportChooserTarget = null
                startExport(record, format)
            },
        )
    }
}

@Composable
private fun OfflineDownloadItem(
    record: OfflineBookRecord,
    sourceName: String,
    onOpen: () -> Unit,
    showManagementActions: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDirectory: () -> Unit,
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
            if (showManagementActions && (record.status == OfflineDownloadStatus.FAILED ||
                    (record.status == OfflineDownloadStatus.COMPLETE && !record.isFullyDownloaded()))) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = if (record.status == OfflineDownloadStatus.FAILED) "重试下载" else "继续下载")
                }
            }
            if (showManagementActions && record.status == OfflineDownloadStatus.COMPLETE) {
                OutlinedButton(onClick = onExport) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("导出", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (showManagementActions) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除离线书籍")
                }
            }
        }
        TextButton(onClick = onDirectory, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("查看下载章节")
        }
    }
}

@Composable
private fun ExportFormatDialog(
    record: OfflineBookRecord,
    onDismiss: () -> Unit,
    onExport: (ExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出《${record.novel.title}》") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择保存格式", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { onExport(ExportFormat.EPUB) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_file_download), contentDescription = null)
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.Start,
                    ) {
                        Text("EPUB", style = MaterialTheme.typography.titleSmall)
                        Text("保留目录和插图", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = { onExport(ExportFormat.TXT) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "TXT",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.Start,
                    ) {
                        Text("TXT", style = MaterialTheme.typography.titleSmall)
                        Text("适合纯文本阅读器", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun epubFileName(record: OfflineBookRecord): String =
    record.novel.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "novel" } + ".epub"

private fun txtFileName(record: OfflineBookRecord): String =
    record.novel.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "novel" } + ".txt"

private fun downloadDirectoryLabel(value: String?): String = value?.let { uri ->
    val name = android.net.Uri.parse(uri).lastPathSegment
        ?.substringAfterLast(':')
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
    "自定义文件夹${name?.let { " · $it" }.orEmpty()}"
} ?: "应用专用目录 · 应用数据/offline_library"

private fun offlineStatusLabel(record: OfflineBookRecord): String = when (record.status) {
    OfflineDownloadStatus.QUEUED -> "等待下载"
    OfflineDownloadStatus.DOWNLOADING -> "正在下载 · ${record.downloadSummary()}"
    OfflineDownloadStatus.COMPLETE -> "${if (record.isFullyDownloaded()) "全书已下载" else "部分已下载"} · ${record.downloadSummary()}"
    OfflineDownloadStatus.FAILED -> record.error ?: "下载失败"
}

private fun OfflineBookRecord.downloadSummary(): String {
    val volumes = if (totalVolumes > 0) "$completedVolumes/$totalVolumes 卷 · " else ""
    return "$volumes$completedChapters/$totalChapters 章"
}
