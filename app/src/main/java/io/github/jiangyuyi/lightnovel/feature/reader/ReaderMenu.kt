package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode

/** Shared actions for both online reader implementations. */
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
    onTextSettings: () -> Unit,
    onRetry: (() -> Unit)?,
    onToggleProgressBar: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .heightIn(max = 640.dp)
                    .testTag("reader-menu-panel"),
                shape = RoundedCornerShape(28.dp),
                color = readerControlPanelColor(background),
                contentColor = contentColor,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
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
                        headline = "文字样式",
                        supporting = "字体、字号、行距和页边距",
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = { onDismiss(); onTextSettings() },
                    )
                    ReaderMenuItem(
                        headline = "阅读设置",
                        supporting = "翻页方式和阅读背景",
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
            .background(readerControlPanelColor(background).copy(alpha = 0.98f))
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

/** A subtle but stable contrast layer for reader controls over the page. */
internal fun readerControlPanelColor(background: Color): Color {
    val luminance = background.red * 0.2126f + background.green * 0.7152f + background.blue * 0.0722f
    val factor = if (luminance > 0.5f) 0.92f else 1.12f
    return Color(
        red = (background.red * factor).coerceIn(0f, 1f),
        green = (background.green * factor).coerceIn(0f, 1f),
        blue = (background.blue * factor).coerceIn(0f, 1f),
        alpha = background.alpha,
    )
}
