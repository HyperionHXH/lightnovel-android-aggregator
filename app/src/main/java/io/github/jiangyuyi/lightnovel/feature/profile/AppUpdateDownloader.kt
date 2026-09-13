package io.github.jiangyuyi.lightnovel.feature.profile

import android.content.Context
import io.github.jiangyuyi.lightnovel.BuildConfig
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.max

internal data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
)

internal class AppUpdateDownloader(private val context: Context) {
    fun apkFile(tag: String): File = File(updateDirectory(), "Mixn-${safeTag(tag)}.apk")

    fun partialFile(tag: String): File = File(updateDirectory(), "Mixn-${safeTag(tag)}.apk.part")

    suspend fun download(
        release: LatestRelease,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): Result<File> = runCatching {
        withContext(Dispatchers.IO) {
            val target = apkFile(release.tag)
            val partial = partialFile(release.tag)
            updateDirectory().mkdirs()
            downloadToPartial(release.apkUrl ?: error("Release 没有 APK 下载地址"), partial, onProgress)
            if (target.exists()) target.delete()
            check(partial.renameTo(target) || copyFile(partial, target)) { "更新包保存失败" }
            target
        }
    }

    fun deletePartial(tag: String) {
        partialFile(tag).delete()
    }

    /** Returns the size of completed and resumable update packages kept locally. */
    fun storedBytes(): Long = updateDirectory().listFiles().orEmpty()
        .filter { it.isFile && (it.extension.equals("apk", true) || it.name.endsWith(".apk.part", true)) }
        .sumOf(File::length)

    /** Removes update packages only; offline books and reader cache are untouched. */
    fun clearStoredPackages(): Long {
        var removed = 0L
        updateDirectory().listFiles().orEmpty()
            .filter { it.isFile && (it.extension.equals("apk", true) || it.name.endsWith(".apk.part", true)) }
            .forEach { file ->
                val size = file.length()
                if (file.delete()) removed += size
            }
        return removed
    }

    private suspend fun downloadToPartial(
        url: String,
        partial: File,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ) {
        var append = partial.length() > 0L
        var connection = openConnection(url, partial.length(), append)
        try {
            if (append && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                partial.delete()
                append = false
                connection = openConnection(url, 0L, false)
            }
            check(connection.responseCode in 200..299) {
                "更新包下载失败（HTTP ${connection.responseCode}）"
            }

            val startingBytes = if (append) partial.length() else 0L
            val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val totalBytes = if (responseLength >= 0L) startingBytes + responseLength else 0L
            var downloaded = startingBytes
            var lastReportAt = 0L
            val startedAt = System.nanoTime()
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastReportAt >= REPORT_INTERVAL_NANOS) {
                            val elapsed = max(1L, now - startedAt)
                            val speed = ((downloaded - startingBytes) * 1_000_000_000L) / elapsed
                            onProgress(UpdateDownloadProgress(downloaded, totalBytes, speed))
                            lastReportAt = now
                        }
                    }
                }
            }
            val elapsed = max(1L, System.nanoTime() - startedAt)
            val speed = ((downloaded - startingBytes) * 1_000_000_000L) / elapsed
            onProgress(UpdateDownloadProgress(downloaded, totalBytes, speed))
            if (totalBytes > 0L) check(downloaded >= totalBytes) { "更新包下载不完整" }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, offset: Long, append: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "Mixn-Android/${BuildConfig.VERSION_NAME}")
            if (append && offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

    private fun updateDirectory(): File = File(context.filesDir, "updates")

    private fun copyFile(source: File, target: File): Boolean = runCatching {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        source.delete()
    }.getOrDefault(false)

    private fun safeTag(tag: String): String = tag.removePrefix("v").replace(Regex("[^0-9A-Za-z._-]"), "_")

    private companion object {
        const val REPORT_INTERVAL_NANOS = 150_000_000L
    }
}
