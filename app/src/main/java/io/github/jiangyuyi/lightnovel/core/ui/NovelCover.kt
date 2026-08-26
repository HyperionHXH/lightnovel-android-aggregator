package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun NovelCover(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val coverUrl = url?.takeIf(String::isNotBlank)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        NovelCoverPlaceholder(title)
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "$title 封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

@Composable
private fun NovelCoverPlaceholder(title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title.trim().take(1).ifBlank { "书" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "暂无封面",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
