package io.github.jiangyuyi.lightnovel.feature.local

import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.local.LocalBookRecord
import io.github.jiangyuyi.lightnovel.core.local.LocalLibraryStore
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn
import io.github.jiangyuyi.lightnovel.R

private val LOCAL_FILE_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "text/plain",
    "text/html",
    "application/xhtml+xml",
    "application/x-fictionbook+xml",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalLibraryScreen(
    store: LocalLibraryStore,
    onBook: (LocalBookRecord) -> Unit,
) {
    val books by store.books.collectAsStateWithLifecycle()
    val importedFiles by store.importedFiles.collectAsStateWithLifecycle()
    val roots by store.roots.collectAsStateWithLifecycle()
    val indexing by store.isIndexing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val visibleBooks = filterLocalBooks(books, query)
    val hasLibrarySources = roots.isNotEmpty() || importedFiles.isNotEmpty()
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        store.addFolder(uri)
    }
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
                    if (selectedIds.isNotEmpty()) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除选中记录")
                        }
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "取消选择")
                        }
                    }
                    IconButton(
                        onClick = {
                            if (searchVisible) {
                                searchVisible = false
                                query = ""
                                focus.clearFocus()
                            } else {
                                searchVisible = true
                            }
                        },
                    ) {
                        Icon(
                            if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchVisible) "关闭搜索" else "搜索本地书库",
                        )
                    }
                    IconButton(onClick = { folderLauncher.launch(null) }) {
                        Icon(painterResource(R.drawable.ic_folder_open), contentDescription = "选择书库文件夹")
                    }
                    IconButton(onClick = { fileLauncher.launch(LOCAL_FILE_MIME_TYPES) }) {
                        Icon(painterResource(R.drawable.ic_file_open), contentDescription = "单独导入文件")
                    }
                },
            )
        }
        if (searchVisible) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    placeholder = { Text("搜索本地书籍") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
            }
        }
        if (!hasLibrarySources) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有选择本地书库", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "选择文件夹后自动读取其中的小说",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { folderLauncher.launch(null) }) {
                                Icon(painterResource(R.drawable.ic_folder_open), contentDescription = "选择书库文件夹")
                            }
                        }
                    }
                }
            }
        }
        if (books.isEmpty() && hasLibrarySources) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) {
                    Text("正在读取已导入文件，或文件格式暂不支持", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (books.isNotEmpty() && visibleBooks.isEmpty()) {
            item {
                Text(
                    "没有找到匹配的本地书籍",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visibleBooks, key = LocalBookRecord::id) { book ->
            LocalBookCard(
                store = store,
                book = book,
                selected = book.id in selectedIds,
                selectionMode = selectedIds.isNotEmpty(),
                onClick = {
                    if (selectedIds.isNotEmpty()) {
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(book.id)) remove(book.id)
                        }
                    } else onBook(book)
                },
                onLongClick = { selectedIds = selectedIds.toMutableSet().apply { add(book.id) } },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移除本地记录") },
            text = { Text("只从 Mixn 书库中移除选中记录，不会删除手机上的原文件。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    store.removeFiles(books.filter { it.id in selectedIds }.map(LocalBookRecord::uri))
                    selectedIds = emptySet()
                    confirmDelete = false
                }) { Text("移除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

internal fun filterLocalBooks(books: List<LocalBookRecord>, query: String): List<LocalBookRecord> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return books
    return books.filter { book ->
        book.title.contains(normalizedQuery, ignoreCase = true) ||
            book.author.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LocalBookCard(
    store: LocalLibraryStore,
    book: LocalBookRecord,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var coverBytes by remember(book.id, book.coverPath) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(book.id, book.coverPath) {
        coverBytes = store.readCover(book)
    }
    val coverBitmap = remember(coverBytes) {
        coverBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .combinedClickable(enabled = book.available, onClick = onClick, onLongClick = onLongClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 104.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = "${book.title} 封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        book.title.trim().take(1).ifBlank { "书" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
            if (selectionMode) {
                androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = null)
            } else if (book.available) {
                Icon(painterResource(R.drawable.ic_file_open), contentDescription = "打开书籍", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
