package io.github.jiangyuyi.lightnovel.feature.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.jiangyuyi.lightnovel.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REPOSITORY_URL = "https://github.com/HyperionHXH/lightnovel-android-aggregator"
private const val RELEASES_URL = REPOSITORY_URL + "/releases"
private const val ISSUES_URL = REPOSITORY_URL + "/issues"
private const val LICENSE_URL = REPOSITORY_URL + "/blob/main/LICENSE"
private const val LATEST_API_URL = "https://api.github.com/repos/HyperionHXH/lightnovel-android-aggregator/releases/latest"

internal data class LatestRelease(
    val tag: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val publishedAt: String,
    val notes: String,
    val releaseUrl: String,
)

private enum class UpdateDownloadStatus { IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED }
private data class AboutLink(val title: String, val icon: ImageVector, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember(context) { AppUpdateDownloader(context) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未检查更新") }
    var release by remember { mutableStateOf<LatestRelease?>(null) }
    var downloadStatus by remember { mutableStateOf(UpdateDownloadStatus.IDLE) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var bytesPerSecond by remember { mutableStateOf(0L) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    fun checkForUpdate() {
        if (checking) return
        checking = true
        status = "正在检查 GitHub Release…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
            checking = false
            result.onSuccess { latest ->
                release = latest
                val currentFile = latest.apkUrl?.let { downloader.apkFile(latest.tag) }
                val partial = latest.apkUrl?.let { downloader.partialFile(latest.tag) }
                downloadedFile = currentFile?.takeIf(File::exists)
                if (downloadedFile != null) {
                    downloadStatus = UpdateDownloadStatus.COMPLETED
                    downloadedBytes = downloadedFile!!.length()
                    totalBytes = latest.apkSizeBytes
                } else if (partial?.isFile == true && partial.length() > 0L) {
                    downloadStatus = UpdateDownloadStatus.PAUSED
                    downloadedBytes = partial.length()
                    totalBytes = latest.apkSizeBytes
                } else {
                    downloadStatus = UpdateDownloadStatus.IDLE
                    downloadedBytes = 0L
                    totalBytes = 0L
                }
                status = if (compareVersions(latest.tag, BuildConfig.VERSION_NAME) > 0) {
                    "发现新版本 " + latest.tag
                } else {
                    "当前已是最新版本"
                }
            }.onFailure { status = "检查失败：" + (it.message ?: "网络不可用") }
        }
    }

    fun startDownload() {
        val latest = release ?: return
        if (latest.apkUrl == null || downloadJob?.isActive == true) return
        downloadError = null
        downloadStatus = UpdateDownloadStatus.DOWNLOADING
        downloadJob = scope.launch {
            val result = downloader.download(latest) { progress ->
                scope.launch(Dispatchers.Main.immediate) {
                    downloadedBytes = progress.downloadedBytes
                    totalBytes = progress.totalBytes
                    bytesPerSecond = progress.bytesPerSecond
                }
            }
            if (!isActive) return@launch
            downloadJob = null
            result.onSuccess { file ->
                downloadedFile = file
                downloadedBytes = file.length()
                totalBytes = latest.apkSizeBytes
                downloadStatus = UpdateDownloadStatus.COMPLETED
                status = "更新包已下载，可以安装"
            }.onFailure {
                downloadStatus = UpdateDownloadStatus.FAILED
                downloadError = it.message ?: "更新包下载失败"
            }
        }
    }

    fun pauseDownload() {
        downloadStatus = UpdateDownloadStatus.PAUSED
        downloadJob?.cancel()
        downloadJob = null
    }

    fun cancelDownload() {
        release?.let { downloader.deletePartial(it.tag) }
        downloadJob?.cancel()
        downloadJob = null
        downloadStatus = UpdateDownloadStatus.IDLE
        downloadedBytes = 0L
        totalBytes = 0L
        bytesPerSecond = 0L
        downloadError = null
    }

    val currentDownloadJob by rememberUpdatedState(downloadJob)
    DisposableEffect(Unit) { onDispose { currentDownloadJob?.cancel() } }
    LaunchedEffect(Unit) { checkForUpdate() }

    val latest = release
    val hasUpdate = latest?.let { compareVersions(it.tag, BuildConfig.VERSION_NAME) > 0 } == true
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("关于与更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Mixn", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("当前版本 " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
                Text("聚合轻之国度与轻书架的在线轻小说阅读器", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            UpdateCard(
                latest = latest,
                checking = checking,
                status = status,
                hasUpdate = hasUpdate,
                downloadStatus = downloadStatus,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
                downloadError = downloadError,
                onCheck = ::checkForUpdate,
                onDownload = ::startDownload,
                onPause = ::pauseDownload,
                onResume = ::startDownload,
                onCancel = ::cancelDownload,
                onInstall = { downloadedFile?.let { context.installApk(it) } },
                onReleaseNotes = { latest?.releaseUrl?.let(context::openWeb) },
            )
        }
        item {
            AboutLinkGroup(
                title = "项目",
                links = listOf(
                    AboutLink("源代码", Icons.Filled.Code, REPOSITORY_URL),
                    AboutLink("版本发布", Icons.Filled.History, RELEASES_URL),
                    AboutLink("问题反馈", Icons.Filled.BugReport, ISSUES_URL),
                ),
                onOpen = context::openWeb,
            )
        }
        item {
            AboutLinkGroup(
                title = "开源",
                links = listOf(AboutLink("MIT 开源许可证", Icons.Filled.Balance, LICENSE_URL)),
                onOpen = context::openWeb,
            )
        }
    }
}

@Composable
private fun UpdateCard(
    latest: LatestRelease?,
    checking: Boolean,
    status: String,
    hasUpdate: Boolean,
    downloadStatus: UpdateDownloadStatus,
    downloadedBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
    downloadError: String?,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onReleaseNotes: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (hasUpdate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onCheck) { Icon(Icons.Filled.Refresh, contentDescription = "检查更新") }
            }
            latest?.let { item ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("最新版本 " + item.tag, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(item.publishedAt.toDisplayDate(), item.apkSizeBytes.takeIf { it > 0L }?.let(::formatBytes))
                                .joinToString(" · ").ifBlank { "GitHub Release" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(if (hasUpdate) "可更新" else "已是最新", color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
                if (item.notes.isNotBlank()) {
                    Text(
                        item.notes.trim(),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    downloadStatus == UpdateDownloadStatus.DOWNLOADING -> {
                        val progress = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (totalBytes > 0L) formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes) else "已下载 " + formatBytes(downloadedBytes), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(formatBytes(bytesPerSecond) + "/秒", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Text("暂停")
                            }
                            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Text("取消")
                            }
                        }
                    }
                    downloadStatus == UpdateDownloadStatus.PAUSED -> {
                        Text("已暂停 · " + formatBytes(downloadedBytes) + if (totalBytes > 0L) " / " + formatBytes(totalBytes) else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("继续下载")
                            }
                            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                                Text("删除")
                            }
                        }
                    }
                    downloadStatus == UpdateDownloadStatus.COMPLETED -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("更新包已准备好 · " + formatBytes(downloadedBytes), modifier = Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onInstall) { Text("安装") }
                        }
                    }
                    downloadStatus == UpdateDownloadStatus.FAILED -> {
                        Text(downloadError ?: "更新包下载失败", color = MaterialTheme.colorScheme.error)
                        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text("重试下载")
                        }
                    }
                    hasUpdate && item.apkUrl != null -> {
                        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Text("下载并安装 " + item.tag)
                        }
                    }
                }
                TextButton(onClick = onReleaseNotes, modifier = Modifier.fillMaxWidth()) { Text("查看完整更新日志") }
            }
        }
    }
}

@Composable
private fun AboutLinkGroup(title: String, links: List<AboutLink>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            links.forEachIndexed { index, link ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(link.url) }.padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(link.icon, contentDescription = null, modifier = Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(link.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "在浏览器打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (index != links.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun Context.openWeb(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun Context.installApk(file: File) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
            return
        }
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

private suspend fun fetchLatestRelease(): Result<LatestRelease> = runCatching {
    val connection = (URL(LATEST_API_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Mixn-Android")
    }
    try {
        if (connection.responseCode !in 200..299) error("GitHub 返回 " + connection.responseCode)
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = root.optString("tag_name").ifBlank { error("Release 没有版本号") }
        var apkUrl: String? = null
        var apkSize = 0L
        root.optJSONArray("assets")?.let { assets ->
            (0 until assets.length()).asSequence()
                .map { assets.optJSONObject(it) }
                .filterNotNull()
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.let {
                    apkUrl = it.optString("browser_download_url").takeIf(String::isNotBlank)
                    apkSize = it.optLong("size", 0L)
                }
        }
        LatestRelease(
            tag = tag,
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            publishedAt = root.optString("published_at"),
            notes = root.optString("body"),
            releaseUrl = root.optString("html_url").ifBlank { RELEASES_URL },
        )
    } finally {
        connection.disconnect()
    }
}

private fun compareVersions(left: String, right: String): Int {
    fun parts(value: String) = value.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(left)
    val b = parts(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val result = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
        if (result != 0) return result
    }
    return 0
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (value >= 100.0 || index == 0) "%.0f %s".format(Locale.US, value, units[index])
    else "%.1f %s".format(Locale.US, value, units[index])
}

private fun String.toDisplayDate(): String? = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(this)?.let {
        SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date(it.time))
    }
}.getOrNull()
