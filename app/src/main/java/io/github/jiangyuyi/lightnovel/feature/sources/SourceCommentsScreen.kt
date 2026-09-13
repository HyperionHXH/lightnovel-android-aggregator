package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.CommentSort
import io.github.jiangyuyi.lightnovel.core.source.SourceComment
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
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
    var ratingStars by remember { mutableStateOf(0) }
    var mentionUids by remember { mutableStateOf(emptyList<Long>()) }
    var replyTo by remember { mutableStateOf<SourceComment?>(null) }
    LaunchedEffect(Unit) { viewModel.loadCommentEmojis() }
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (replyTo == null) {
                    CommentComposer(
                        viewModel = viewModel,
                        state = state,
                        draft = draft,
                        onDraftChange = { draft = it },
                        mentionUids = mentionUids,
                        onMentionUidsChange = { mentionUids = it },
                        replyTo = null,
                        onCancelReply = { replyTo = null },
                        onLoginRequired = onAccounts,
                        onPublished = {
                            draft = ""
                            mentionUids = emptyList()
                        },
                    )
                }
                RatingCommitRow(
                    value = ratingStars,
                    onValueChange = { ratingStars = it },
                    enabled = !state.publishingComment,
                    onConfirm = {
                        viewModel.rateNovel(
                            ratingStars,
                            onLoginRequired = onAccounts,
                            onRated = { ratingStars = 0 },
                        )
                    },
                )
            }
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
                    SourceCommentCard(
                        comment = comment,
                        emojis = state.commentEmojis,
                        onLike = { viewModel.toggleCommentLike(comment, onAccounts) },
                        onReply = { replyTo = comment },
                    )
                    if (replyTo?.id == comment.id) {
                        CommentComposer(
                            viewModel = viewModel,
                            state = state,
                            draft = draft,
                            onDraftChange = { draft = it },
                            mentionUids = mentionUids,
                            onMentionUidsChange = { mentionUids = it },
                            replyTo = replyTo,
                            onCancelReply = { replyTo = null },
                            onLoginRequired = onAccounts,
                            onPublished = {
                                draft = ""
                                mentionUids = emptyList()
                                replyTo = null
                            },
                        )
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
