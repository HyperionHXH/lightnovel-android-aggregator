package io.github.jiangyuyi.lightnovel.feature.sources

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.jiangyuyi.lightnovel.core.source.SourceComment
import io.github.jiangyuyi.lightnovel.core.source.SourceCommentEmoji
import io.github.jiangyuyi.lightnovel.core.source.SourceCommentMedia
import io.github.jiangyuyi.lightnovel.core.source.SourceMentionCandidate
import kotlin.math.roundToInt

@Composable
internal fun CommentComposer(
    viewModel: SourceBookViewModel,
    state: SourceBookState,
    draft: String,
    onDraftChange: (String) -> Unit,
    ratingStars: Int,
    onRatingChange: (Int) -> Unit,
    mentionUids: List<Long>,
    onMentionUidsChange: (List<Long>) -> Unit,
    replyTo: SourceComment?,
    onCancelReply: () -> Unit,
    onLoginRequired: () -> Unit,
    onPublished: () -> Unit,
) {
    var emojiMenu by remember { mutableStateOf(false) }
    var mentionMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val imageBytes = bytes?.takeUnless { it.isEmpty() } ?: return@rememberLauncherForActivityResult
        viewModel.uploadCommentImage(
            bytes = imageBytes,
            fileName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "comment-image.jpg" } ?: "comment-image.jpg",
            mimeType = context.contentResolver.getType(uri) ?: "image/jpeg",
            onLoginRequired = onLoginRequired,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        replyTo?.let { target ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回复 ${target.authorName}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onCancelReply) { Text("取消") }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { onDraftChange(it.take(1_000)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("说说本作品...") },
            supportingText = { Text("${draft.length} / 1,000") },
            trailingIcon = {
                IconButton(
                    onClick = {
                        viewModel.publishComment(
                            content = draft,
                            ratingStars = ratingStars,
                            rootCommentId = replyTo?.rootCommentId ?: replyTo?.id,
                            replyCommentId = replyTo?.id,
                            mentionUids = mentionUids,
                            media = state.commentMedia,
                            onLoginRequired = onLoginRequired,
                            onPublished = onPublished,
                        )
                    },
                    enabled = (draft.isNotBlank() || state.commentMedia.isNotEmpty() || ratingStars > 0) &&
                        !state.publishingComment && !state.uploadingCommentImage,
                ) {
                    if (state.publishingComment) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发表评论")
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { emojiMenu = !emojiMenu; viewModel.loadCommentEmojis() }) {
                Text("☺", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            BoxedEmojiMenu(
                expanded = emojiMenu,
                emojis = state.commentEmojis,
                onDismiss = { emojiMenu = false },
                onSelect = { emoji ->
                    onDraftChange((draft + emoji.code).take(1_000))
                    emojiMenu = false
                },
            )
            IconButton(onClick = { mentionMenu = !mentionMenu; viewModel.loadMentionCandidates() }) {
                Icon(Icons.Filled.AlternateEmail, contentDescription = "提及用户")
            }
            DropdownMenu(expanded = mentionMenu, onDismissRequest = { mentionMenu = false }) {
                if (state.mentionCandidates.isEmpty()) {
                    DropdownMenuItem(text = { Text("暂无可提及的关注用户") }, onClick = { mentionMenu = false })
                } else {
                    state.mentionCandidates.take(8).forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text("@${candidate.name}") },
                            onClick = {
                                onDraftChange((draft + "@${candidate.name} ").take(1_000))
                                onMentionUidsChange((mentionUids + candidate.uid).distinct())
                                mentionMenu = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { imagePicker.launch("image/*") }) {
                if (state.uploadingCommentImage) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "添加图片")
            }
            Spacer(Modifier.weight(1f))
            Text("评分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.commentMedia.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.commentMedia.size) { index ->
                    val media = state.commentMedia[index]
                    Column(horizontalAlignment = Alignment.End) {
                        AsyncImage(
                            model = media.url,
                            contentDescription = "已添加的评论图片",
                            modifier = Modifier.size(72.dp),
                        )
                        TextButton(onClick = { viewModel.removeCommentImage(media) }) { Text("移除") }
                    }
                }
            }
        }
        CommentRatingSelector(value = ratingStars, onValueChange = onRatingChange)
    }
}

@Composable
private fun BoxedEmojiMenu(
    expanded: Boolean,
    emojis: List<SourceCommentEmoji>,
    onDismiss: () -> Unit,
    onSelect: (SourceCommentEmoji) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (emojis.isEmpty()) {
            DropdownMenuItem(text = { Text("表情加载中…") }, onClick = onDismiss)
        } else {
            emojis.take(24).forEach { emoji ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            emoji.imageUrl
                                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }
                                ?.let { AsyncImage(model = it, contentDescription = emoji.label, modifier = Modifier.size(24.dp)) }
                            Text(emoji.label ?: emoji.code, Modifier.padding(start = 8.dp))
                        }
                    },
                    onClick = { onSelect(emoji) },
                )
            }
        }
    }
}

@Composable
internal fun SourceCommentCard(
    comment: SourceComment,
    emojis: List<SourceCommentEmoji> = emptyList(),
    onLike: () -> Unit,
    onReply: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                comment.authorAvatarUrl?.let {
                    AsyncImage(model = it, contentDescription = comment.authorName, modifier = Modifier.size(30.dp))
                }
                Text(
                    comment.authorName,
                    Modifier.weight(1f).padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    comment.createdAt.ifBlank { "时间未知" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            comment.ratingStars?.let { CommentRatingDisplay(it) }
            CommentRichText(comment.content.ifBlank { "（图片评论）" }, emojis)
            if (comment.media.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(comment.media.size) { index ->
                        AsyncImage(
                            model = comment.media[index].url,
                            contentDescription = "评论图片",
                            modifier = Modifier.size(104.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike) {
                    Icon(
                        imageVector = if (comment.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (comment.liked) "取消点赞" else "点赞",
                        tint = if (comment.liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${comment.likeCount}", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onReply) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("回复${comment.replyCount.takeIf { it > 0 }?.let { " $it" } ?: ""}")
                }
            }
            comment.replies.take(3).forEach { reply ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${reply.authorName}：${reply.content}", style = MaterialTheme.typography.bodySmall)
                        Text(reply.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentRichText(content: String, emojis: List<SourceCommentEmoji>) {
    val emojiByCode = remember(emojis) { emojis.associateBy { it.code } }
    val tokenRegex = remember(emojis) {
        val codes = emojis.map { it.code.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending(String::length)
            .map(Regex.Companion::escape)
        val knownCodes = codes.takeIf { it.isNotEmpty() }?.joinToString("|")
        val patterns = listOfNotNull(
            "\\[s:[^]]+\\]",
            "\\{:[^}:]+:\\}",
            knownCodes,
        )
        Regex("(?:${patterns.joinToString("|")})")
    }
    val matches = remember(content, tokenRegex) { tokenRegex.findAll(content).toList() }
    if (matches.isEmpty()) {
        Text(content)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var cursor = 0
        matches.forEach { match ->
            if (match.range.first > cursor) Text(content.substring(cursor, match.range.first))
            val emoji = emojiByCode[match.value]
            val imageUrl = emoji?.imageUrl?.takeIf {
                it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/")
            }
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = emoji.label, modifier = Modifier.size(24.dp))
            } else {
                Text(emoji?.text ?: match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < content.length) Text(content.substring(cursor))
    }
}

@Composable
internal fun WorkScore(score: Double?) {
    val normalized = score?.coerceIn(0.0, 5.0)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("作品评分", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (normalized == null || normalized <= 0.0) {
            Text("暂无评分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CommentRatingDisplay(normalized.roundToInt())
            Text(String.format("%.1f / 5", normalized), style = MaterialTheme.typography.bodySmall)
        }
    }
}
