package io.github.jiangyuyi.lightnovel.feature.profile

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val REPOSITORY_URL = "https://github.com/HyperionHXH/lightnovel-android-aggregator"
private const val RELEASES_URL = "$REPOSITORY_URL/releases"
private const val ISSUES_URL = "$REPOSITORY_URL/issues"
private const val LICENSE_URL = "$REPOSITORY_URL/blob/main/LICENSE"
private const val LATEST_API_URL = "https://api.github.com/repos/HyperionHXH/lightnovel-android-aggregator/releases/latest"

private data class LatestRelease(val tag: String, val apkUrl: String?)
private data class AboutLink(val title: String, val icon: ImageVector, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未检查更新") }
    var release by remember { mutableStateOf<LatestRelease?>(null) }

    fun checkForUpdate() {
        if (checking) return
        checking = true
        status = "正在检查 GitHub Release…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
            checking = false
            result.onSuccess { latest ->
                release = latest
                status = if (compareVersions(latest.tag, BuildConfig.VERSION_NAME) > 0) {
                    "发现新版本 ${latest.tag}"
                } else {
                    "当前已是最新版本"
                }
            }.onFailure { status = "检查失败：${it.message ?: "网络不可用"}" }
        }
    }

    val hasUpdate = release?.let { compareVersions(it.tag, BuildConfig.VERSION_NAME) > 0 } == true
    LaunchedEffect(Unit) { checkForUpdate() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Mixn",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
                Text(
                    "聚合轻之国度与轻书架的在线轻小说阅读器",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val url = release?.apkUrl
                            if (hasUpdate && url != null) {
                                enqueueApkDownload(context, url, release!!.tag)
                                status = "已加入下载队列，请在系统通知中查看"
                            } else {
                                checkForUpdate()
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (hasUpdate) Icons.Filled.Download else Icons.Filled.Refresh,
                                contentDescription = null,
                            )
                        }
                        Text(if (hasUpdate && release?.apkUrl != null) "下载新版本" else "检查更新")
                    }
                    TextButton(onClick = { context.openWeb(RELEASES_URL) }) { Text("更新日志") }
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("检查失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
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
private fun AboutLinkGroup(
    title: String,
    links: List<AboutLink>,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            links.forEachIndexed { index, link ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(link.url) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            link.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(link.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "在浏览器打开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index != links.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 62.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

private fun Context.openWeb(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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
        val response = connection
        if (response.responseCode !in 200..299) error("GitHub 返回 ${response.responseCode}")
        val root = JSONObject(response.inputStream.bufferedReader().use { it.readText() })
        val tag = root.optString("tag_name").ifBlank { error("Release 没有版本号") }
        val apkUrl = root.optJSONArray("assets")?.let { assets ->
            (0 until assets.length()).asSequence()
                .map { assets.optJSONObject(it) }
                .filterNotNull()
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?.takeIf(String::isNotBlank)
        }
        LatestRelease(tag, apkUrl)
    } finally {
        connection.disconnect()
    }
}

private fun enqueueApkDownload(context: Context, url: String, tag: String) {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("Mixn $tag")
        .setDescription("正在下载 Mixn 更新包")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Mixn-$tag.apk")
    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
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
