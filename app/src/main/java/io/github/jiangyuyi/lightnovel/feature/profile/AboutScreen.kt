package io.github.jiangyuyi.lightnovel.feature.profile

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

private const val RELEASES_URL = "https://github.com/HyperionHXH/lightnovel-android-aggregator/releases"
private const val LATEST_API_URL = "https://api.github.com/repos/HyperionHXH/lightnovel-android-aggregator/releases/latest"

private data class LatestRelease(val tag: String, val apkUrl: String?)

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
    LaunchedEffect(Unit) { checkForUpdate() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("关于 Mixn") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mixn", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("双来源轻小说阅读器", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("更新与下载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("新版会从 GitHub Release 发布。Mixn 会读取最新版本并使用系统下载服务保存 APK，安装仍由 Android 系统确认。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = {
                        val url = release?.apkUrl
                        if (url != null && compareVersions(release?.tag.orEmpty(), BuildConfig.VERSION_NAME) > 0) {
                            enqueueApkDownload(context, url, release!!.tag)
                            status = "已加入下载队列，请在系统通知中查看"
                        } else {
                            checkForUpdate()
                        }
                    },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
                    Text(if (release?.apkUrl != null && compareVersions(release?.tag.orEmpty(), BuildConfig.VERSION_NAME) > 0) "下载新版本" else "检查更新")
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("开源信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("项目地址：github.com/HyperionHXH/lightnovel-android-aggregator", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Mixn 仅整合公开来源接口，不绕过来源登录、付费或访问限制。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
