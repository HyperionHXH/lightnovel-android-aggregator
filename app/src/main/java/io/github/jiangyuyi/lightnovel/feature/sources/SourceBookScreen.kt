package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.offline.OfflineDownloadStatus
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import io.github.jiangyuyi.lightnovel.core.ui.NovelCover
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBookScreen(
    viewModel: SourceBookViewModel,
    onBack: () -> Unit,
    onBook: (NovelKey) -> Unit,
    onRead: (ChapterKey) -> Unit,
    onAccounts: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    RefreshableLazyColumn(
        isRefreshing = state.refreshing,
        onRefresh = { viewModel.load(true) },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        state.detail?.novel?.title ?: "书籍详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
        if (state.refreshing) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        when {
            state.loading -> item { LoadingPane() }
            state.error != null && state.detail == null -> item {
                ErrorPane(state.error!!, onRetry = { viewModel.load(true) })
            }
            state.detail == null -> item { EmptyPane("书籍不存在或暂不可见") }
            else -> {
                val detail = checkNotNull(state.detail)
                val novel = detail.novel
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        NovelCover(
                            url = novel.coverUrl,
                            title = novel.title,
                            modifier = Modifier.size(width = 112.dp, height = 158.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                novel.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                novel.authors.joinToString(" / ").ifBlank { "作者未知" },
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                listOfNotNull(
                                    state.source?.displayName,
                                    novel.volumeCount.takeIf { it > 0 }?.let { "$it 卷" },
                                    novel.chapterCount.takeIf { it > 0 }?.let { "$it 章" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { viewModel.startReading(onRead) },
                                enabled = (state.readingProgress != null || state.volumes.isNotEmpty()) &&
                                    !state.startingReader,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.startingReader) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(if (state.readingProgress == null) "开始阅读" else "继续阅读")
                                }
                            }
                            if (state.shelfSupported) {
                                OutlinedButton(
                                    onClick = { viewModel.toggleShelf(onAccounts) },
                                    enabled = !state.shelfLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (state.shelfLoading) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(if (state.inRemoteShelf == true) "移出书架" else "加入书架")
                                    }
                                }
                            }
                            state.shelfError?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            val offline = state.offlineRecord
                            val downloadActive = offline?.status == OfflineDownloadStatus.QUEUED ||
                                offline?.status == OfflineDownloadStatus.DOWNLOADING
                            OutlinedButton(
                                onClick = viewModel::downloadBook,
                                enabled = !downloadActive,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when (offline?.status) {
                                        OfflineDownloadStatus.QUEUED -> "等待下载"
                                        OfflineDownloadStatus.DOWNLOADING ->
                                            "下载中 ${offline.completedChapters}/${offline.totalChapters}"
                                        OfflineDownloadStatus.COMPLETE -> "补全离线章节"
                                        OfflineDownloadStatus.FAILED -> "重试下载"
                                        null -> "下载全书"
                                    },
                                )
                            }
                            if (downloadActive && offline.totalChapters > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        offline.completedChapters.toFloat() / offline.totalChapters
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            offline?.error?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (novel.tags.isNotEmpty()) {
                    item {
                        Text(
                            novel.tags.joinToString(" · "),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                item {
                    SourceSection("简介") {
                        Text(novel.synopsis.ifBlank { "暂无简介" })
                    }
                }
                if (detail.alternateVersions.isNotEmpty()) {
                    item {
                        SourceSection("同书其他版本") {
                            detail.alternateVersions.forEach { version ->
                                Text(
                                    version.title,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().clickable { onBook(version.key) }.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "分卷与章节",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                state.directoryError?.let { message ->
                    item { ErrorPane(message, onRetry = { viewModel.load(true) }) }
                }
                if (state.volumes.isEmpty() && state.directoryError == null) {
                    item { EmptyPane("暂无目录") }
                } else {
                    items(state.volumes, key = { "${it.key.sourceId}:${it.key.remoteId}" }) { volume ->
                        SourceVolumeCard(
                            title = volume.title,
                            chapterCount = volume.chapterCount,
                            expanded = state.expandedVolumeKey == volume.key,
                            chapters = state.chapters[volume.key],
                            onToggle = { viewModel.toggleVolume(volume.key) },
                            onDownload = { viewModel.downloadVolume(volume.key) },
                            downloadEnabled = state.offlineRecord?.status != OfflineDownloadStatus.QUEUED &&
                                state.offlineRecord?.status != OfflineDownloadStatus.DOWNLOADING,
                            onLoadMore = { viewModel.loadMoreChapters(volume.key) },
                            onRead = onRead,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceVolumeCard(
    title: String,
    chapterCount: Int,
    expanded: Boolean,
    chapters: VolumeChaptersState?,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
    onLoadMore: () -> Unit,
    onRead: (ChapterKey) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text("$chapterCount 章", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDownload, enabled = downloadEnabled) { Text("下载本卷") }
                Text(if (expanded) "收起" else "展开")
            }
            if (expanded) {
                chapters?.items.orEmpty().forEach { chapter ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !chapter.locked) { onRead(chapter.key) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                    ) {
                        Text(chapter.title, Modifier.weight(1f))
                        if (chapter.locked) Text("锁定", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (chapters?.loading == true) {
                    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                chapters?.error?.let { message ->
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        IconButton(onClick = onLoadMore) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重试")
                        }
                    }
                }
                if (chapters?.hasMore == true && !chapters.loading) {
                    TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                }
                if (chapters != null && chapters.items.isEmpty() && !chapters.loading && chapters.error == null) {
                    EmptyPane("本卷暂无章节")
                }
            }
        }
    }
}

@Composable
private fun SourceSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
