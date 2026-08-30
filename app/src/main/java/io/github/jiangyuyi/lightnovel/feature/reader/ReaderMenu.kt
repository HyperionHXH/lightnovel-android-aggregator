package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode

/** Shared actions for both online reader implementations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderMenuSheet(
    bookTitle: String,
    chapterTitle: String,
    mode: ReaderMode,
    progressText: String,
    showProgressBar: Boolean,
    background: Color,
    contentColor: Color,
    onDismiss: () -> Unit,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
    onRetry: (() -> Unit)?,
    onToggleProgressBar: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = background,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = bookTitle,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.72f),
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.62f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            ReaderMenuItem(
                headline = "打开章节目录",
                supporting = "选择其他章节",
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                onClick = { onDismiss(); onCatalog() },
            )
            ReaderMenuItem(
                headline = "阅读设置",
                supporting = "字体、背景和翻页方式",
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                onClick = { onDismiss(); onSettings() },
            )
            ReaderMenuItem(
                headline = if (showProgressBar) "隐藏进度条" else "显示进度条",
                supporting = "${mode.label} · $progressText",
                icon = {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                    )
                },
                onClick = onToggleProgressBar,
            )
            if (onRetry != null) {
                ReaderMenuItem(
                    headline = "重新加载",
                    supporting = "重新请求当前章节内容",
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = { onDismiss(); onRetry() },
                )
            }
        }
    }
}

@Composable
private fun ReaderMenuItem(
    headline: String,
    supporting: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = icon,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun ReaderProgressBar(
    label: String,
    value: Float,
    background: Color,
    contentColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(background.copy(alpha = 0.97f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = contentColor.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = contentColor,
                activeTrackColor = contentColor,
                inactiveTrackColor = contentColor.copy(alpha = 0.22f),
            ),
        )
    }
}
