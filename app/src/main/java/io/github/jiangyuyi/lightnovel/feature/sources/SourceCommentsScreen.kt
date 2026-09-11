package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.CommentSort
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceCommentsScreen(
    viewModel: SourceBookViewModel,
    onBack: () -> Unit,
    onAccounts: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    RefreshableLazyColumn(
        isRefreshing = state.commentsLoading,
        onRefresh = { viewModel.loadCommentsForScreen() },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("作品评论") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommentSort.entries.forEach { sort ->
                    FilterChip(selected = state.commentSort == sort, onClick = { viewModel.selectCommentSort(sort) }, label = { Text(sort.label) })
                }
            }
        }
        item {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1_000) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 96.dp),
                label = { Text("说说你对这部作品的看法") },
                supportingText = { Text("${draft.length} / 1,000") },
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.publishComment(draft, onLoginRequired = onAccounts, onPublished = { draft = "" }) },
                        enabled = draft.isNotBlank() && !state.publishingComment,
                    ) {
                        if (state.publishingComment) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发表评论")
                    }
                },
            )
        }
        state.commentError?.let { message ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    if (state.commentLoginRequired) TextButton(onClick = onAccounts) { Text("登录") }
                }
            }
        }
        when {
            state.commentsLoading && state.comments.isEmpty() -> item { LoadingPane() }
            state.comments.isEmpty() && state.commentError == null -> item { EmptyPane("暂无评论，来留下第一条评论吧") }
            else -> {
                items(state.comments, key = { "comment-${it.id}" }) { comment ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(comment.authorName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(comment.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(comment.content)
                            val meta = listOfNotNull(
                                comment.likeCount.takeIf { it > 0 }?.let { "赞 $it" },
                                comment.replyCount.takeIf { it > 0 }?.let { "回复 $it" },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.commentsHasMore || state.commentsLoadingMore) item {
                    TextButton(onClick = viewModel::loadMoreComments, enabled = !state.commentsLoadingMore, modifier = Modifier.fillMaxWidth()) {
                        if (state.commentsLoadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("加载更多评论")
                    }
                }
            }
        }
    }
}
