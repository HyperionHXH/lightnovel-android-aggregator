package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary

@Composable
fun SourceNovelCard(
    novel: NovelSummary,
    sourceName: String,
    showUnreadChapterCount: Boolean = false,
    updateLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metadataLoading: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 156.dp)
            .then(if (metadataLoading) Modifier.semantics { contentDescription = "正在加载书籍信息" }
                else Modifier.clickable(onClick = onClick)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        if (metadataLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MetadataPlaceholder(Modifier.size(width = 76.dp, height = 108.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetadataPlaceholder(Modifier.fillMaxWidth(0.85f).height(22.dp))
                    MetadataPlaceholder(Modifier.fillMaxWidth(0.55f).height(14.dp))
                    MetadataPlaceholder(Modifier.fillMaxWidth().height(14.dp))
                    MetadataPlaceholder(Modifier.fillMaxWidth(0.85f).height(14.dp))
                }
            }
            return@Card
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NovelCover(
                url = novel.coverUrl,
                title = novel.title,
                modifier = Modifier.size(width = 76.dp, height = 108.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    novel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(sourceName, novel.authors.joinToString(" / "))
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showUnreadChapterCount) novel.unreadChapterCount?.takeIf { it > 0 }?.let { unreadCount ->
                    Text(
                        "未读 $unreadCount 章",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                updateLabel?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // Some source list endpoints omit introductions and expose them
                // only on the detail endpoint; avoid a misleading empty row.
                novel.synopsis.takeIf(String::isNotBlank)?.let { synopsis ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        synopsis,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataPlaceholder(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)))
}
