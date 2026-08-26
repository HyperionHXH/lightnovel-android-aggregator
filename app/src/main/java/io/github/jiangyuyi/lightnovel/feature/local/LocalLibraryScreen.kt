package io.github.jiangyuyi.lightnovel.feature.local

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.local.LocalBookRecord
import io.github.jiangyuyi.lightnovel.core.local.LocalLibraryStore
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

private val LOCAL_FILE_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "text/plain",
    "text/html",
    "application/xhtml+xml",
    "application/x-fictionbook+xml",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLibraryScreen(
    store: LocalLibraryStore,
    onBook: (LocalBookRecord) -> Unit,
) {
    val books by store.books.collectAsStateWithLifecycle()
    val importedFiles by store.importedFiles.collectAsStateWithLifecycle()
    val indexing by store.isIndexing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        if (uris.isNotEmpty()) store.addFiles(uris)
    }

    RefreshableLazyColumn(
        isRefreshing = indexing,
        onRefresh = store::reindex,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("本地书库") },
                actions = {
                    IconButton(onClick = { fileLauncher.launch(LOCAL_FILE_MIME_TYPES) }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "导入书籍")
                    }
                },
            )
        }
        if (importedFiles.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有导入本地书籍", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "选择现成的 EPUB、TXT、HTML 或 FB2 文件后即可阅读。文件只在本机解析，不会上传。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { fileLauncher.launch(LOCAL_FILE_MIME_TYPES) }) {
                            Icon(Icons.Filled.FileOpen, contentDescription = "导入书籍")
                        }
                    }
                }
            }
        }
        if (books.isEmpty() && importedFiles.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) {
                    Text("正在读取已导入文件，或文件格式暂不支持", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(books, key = LocalBookRecord::id) { book ->
            LocalBookCard(book, onClick = { onBook(book) })
        }
        if (importedFiles.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已导入文件", style = MaterialTheme.typography.titleSmall)
                    importedFiles.forEach { uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                uri.substringAfterLast('/').ifBlank { uri },
                                Modifier.weight(1f),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(uri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                                store.removeFile(uri)
                            }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "移除导入文件")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalBookCard(book: LocalBookRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = book.available,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        book.author.takeIf(String::isNotBlank),
                        book.format.label,
                        book.chapterCount.takeIf { it > 0 }?.let { "$it 章" },
                        if (book.available) null else "文件不可用",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (book.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (book.available) {
                Icon(Icons.Filled.FileOpen, contentDescription = "打开书籍", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
