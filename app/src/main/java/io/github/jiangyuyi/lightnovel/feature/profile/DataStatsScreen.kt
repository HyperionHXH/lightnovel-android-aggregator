package io.github.jiangyuyi.lightnovel.feature.profile

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.offline.OfflineBookRecord
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun DataStatsScreen(
    offlineLibrary: OfflineLibraryAccess,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by offlineLibrary.books.collectAsStateWithLifecycle()
    val downloadDirectory by offlineLibrary.downloadDirectory.collectAsStateWithLifecycle()
    val downloader = remember(context) { AppUpdateDownloader(context) }
    var updateBytes by remember { mutableStateOf(downloader.storedBytes()) }
    var cacheBytes by remember { mutableStateOf(context.cacheDir.directorySize()) }
    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val stats = remember(books) { books.toOfflineStats() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("数据与统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("本地数据概览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("只统计 Mixn 已保存的数据，不会上传阅读记录。", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            StatsCard(
                title = "阅读与离线",
                icon = Icons.Filled.MenuBook,
                rows = listOf(
                    "离线书籍" to stats.books.toString(),
                    "已下载卷数" to stats.completedVolumes.toString() + " / " + stats.totalVolumes,
                    "已下载章节" to stats.completedChapters.toString() + " / " + stats.totalChapters,
                    "失败任务" to stats.failedBooks.toString(),
                ),
            )
        }
        item {
            StorageCard(
                downloadDirectory = downloadDirectory,
                cacheBytes = cacheBytes,
                updateBytes = updateBytes,
                onClearCache = { pendingClear = ClearTarget.CACHE },
                onClearUpdates = { pendingClear = ClearTarget.UPDATES },
            )
        }
        message?.let { text ->
            item {
                Text(text, modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    pendingClear?.let { target ->
        val title = if (target == ClearTarget.CACHE) "清理临时缓存？" else "清理更新包？"
        val detail = if (target == ClearTarget.CACHE) {
            "只会删除图片和网络临时文件，不会删除书架、阅读进度或离线书籍。"
        } else {
            "只会删除已下载的 APK 和断点文件，不会影响已安装的应用。"
        }
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(title) },
            text = { Text(detail) },
            confirmButton = {
                Button(onClick = {
                    val selected = target
                    pendingClear = null
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            if (selected == ClearTarget.CACHE) {
                                val before = context.cacheDir.directorySize()
                                context.cacheDir.deleteRecursively()
                                context.cacheDir.mkdirs()
                                before
                            } else {
                                downloader.clearStoredPackages()
                            }
                        }
                        cacheBytes = context.cacheDir.directorySize()
                        updateBytes = downloader.storedBytes()
                        message = "已清理 " + formatBytes(removed)
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { pendingClear = null }) { Text("取消") } },
        )
    }
}

private enum class ClearTarget { CACHE, UPDATES }

private data class OfflineStats(
    val books: Int,
    val completedVolumes: Int,
    val totalVolumes: Int,
    val completedChapters: Int,
    val totalChapters: Int,
    val failedBooks: Int,
)

private fun List<OfflineBookRecord>.toOfflineStats() = OfflineStats(
    books = size,
    completedVolumes = sumOf(OfflineBookRecord::completedVolumes),
    totalVolumes = sumOf(OfflineBookRecord::totalVolumes),
    completedChapters = sumOf(OfflineBookRecord::completedChapters),
    totalChapters = sumOf(OfflineBookRecord::totalChapters),
    failedBooks = count { it.status == OfflineDownloadStatus.FAILED },
)

@Composable
private fun StatsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    rows: List<Pair<String, String>>,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StorageCard(
    downloadDirectory: String?,
    cacheBytes: Long,
    updateBytes: Long,
    onClearCache: () -> Unit,
    onClearUpdates: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("数据与存储", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("下载目录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(downloadDirectoryLabel(downloadDirectory), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StorageActionRow("临时缓存", formatBytes(cacheBytes), Icons.Filled.Cached, "清理", onClearCache)
            StorageActionRow("更新包", formatBytes(updateBytes), Icons.Filled.Download, "清理", onClearUpdates)
        }
    }
}

@Composable
private fun StorageActionRow(
    label: String,
    size: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: String,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label)
            Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

private fun downloadDirectoryLabel(value: String?): String = value?.let {
    Uri.parse(it).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "自定义文件夹"
} ?: "应用专用目录（应用数据/offline_library）"

private fun File.directorySize(): Long = walkTopDown().filter(File::isFile).sumOf(File::length)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (value >= 100 || index == 0) "%.0f %s".format(Locale.US, value, units[index])
    else "%.1f %s".format(Locale.US, value, units[index])
}
