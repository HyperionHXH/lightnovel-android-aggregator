package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Stable first-frame state for the reader. It stays centered while chapter data arrives. */
@Composable
internal fun ReaderLoadingPane(
    background: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = contentColor,
                strokeWidth = 3.dp,
            )
            Text(
                text = "正在加载章节…",
                color = contentColor.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
