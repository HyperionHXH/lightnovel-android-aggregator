package io.github.jiangyuyi.lightnovel.feature.reader

import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit, onCatalog: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = state.preferences.readerColors()
    val body = remember(state.chapter) {
        val raw = state.chapter?.bodyText?.ifBlank {
            state.chapter?.bodyHtml?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }.orEmpty()
        }.orEmpty()
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("本章暂无正文") }
    }
    val listState = rememberLazyListState()
    val paragraphCount by remember(body) { derivedStateOf { body.size } }

    LaunchedEffect(state.chapter?.chapter?.id, state.restoredParagraph, body.size) {
        if (body.isNotEmpty()) listState.scrollToItem(state.restoredParagraph.coerceIn(0, body.lastIndex))
    }
    LaunchedEffect(listState, state.chapter?.chapter?.id, paragraphCount) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.saveProgress(it, paragraphCount) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when {
            state.loading -> LoadingPane(Modifier.align(Alignment.Center))
            state.error != null -> ErrorPane(
                message = state.error!!,
                modifier = Modifier.align(Alignment.Center),
                onRetry = viewModel::retry,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clickable { viewModel.toggleControls() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = state.preferences.horizontalPadding.dp,
                    end = state.preferences.horizontalPadding.dp,
                    top = 88.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        state.chapter?.chapter?.title.orEmpty(),
                        style = TextStyle(
                            color = colors.text,
                            fontFamily = state.preferences.font.family(),
                            fontSize = (state.preferences.fontSize + 5).sp,
                            lineHeight = ((state.preferences.fontSize + 5) * state.preferences.lineHeight).sp,
                        ),
                    )
                }
                items(body.size) { index ->
                    Text(
                        body[index],
                        style = TextStyle(
                            color = colors.text,
                            fontFamily = state.preferences.font.family(),
                            fontSize = state.preferences.fontSize.sp,
                            lineHeight = (state.preferences.fontSize * state.preferences.lineHeight).sp,
                        ),
                    )
                }
            }
        }

        if (state.controlsVisible) {
            TopAppBar(
                title = { Text(state.chapter?.bookTitle ?: "阅读") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onCatalog) { Text("目录") } },
            )
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::previous, enabled = state.chapter?.previousChapterId != null) { Text("上一章") }
                    Button(onClick = { viewModel.showSettings(true) }) { Text("阅读设置") }
                    TextButton(onClick = viewModel::next, enabled = state.chapter?.nextChapterId != null) { Text("下一章") }
                }
            }
        }
    }

    if (state.settingsVisible) {
        ReaderSettingsDialog(
            preferences = state.preferences,
            onChange = { value -> viewModel.updatePreferences { value } },
            onDismiss = { viewModel.showSettings(false) },
        )
    }
}

@Composable
private fun ReaderSettingsDialog(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("阅读设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字体")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderFont.entries.forEach { font ->
                        FilterChip(
                            selected = preferences.font == font,
                            onClick = { onChange(preferences.copy(font = font)) },
                            label = { Text(font.label) },
                        )
                    }
                }
                Text("字号 ${preferences.fontSize.toInt()}")
                Slider(
                    value = preferences.fontSize,
                    onValueChange = { onChange(preferences.copy(fontSize = it)) },
                    valueRange = 14f..32f,
                    steps = 17,
                )
                Text("行高 ${"%.1f".format(preferences.lineHeight)}")
                Slider(
                    value = preferences.lineHeight,
                    onValueChange = { onChange(preferences.copy(lineHeight = it)) },
                    valueRange = 1.2f..2.2f,
                    steps = 9,
                )
                Text("页边距 ${preferences.horizontalPadding}")
                Slider(
                    value = preferences.horizontalPadding.toFloat(),
                    onValueChange = { onChange(preferences.copy(horizontalPadding = it.toInt())) },
                    valueRange = 12f..40f,
                    steps = 13,
                )
                Text("背景")
                ReaderTheme.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { theme ->
                            FilterChip(
                                selected = preferences.theme == theme,
                                onClick = { onChange(preferences.copy(theme = theme)) },
                                label = { Text(theme.label) },
                            )
                        }
                    }
                }
            }
        },
    )
}

private data class ReaderColors(val background: Color, val text: Color)

@Composable
private fun ReaderPreferences.readerColors(): ReaderColors = when (theme) {
    ReaderTheme.WHITE -> ReaderColors(Color(0xFFFFFBFF), Color(0xFF211A1C))
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF7EED9), Color(0xFF3A3025))
    ReaderTheme.GREEN -> ReaderColors(Color(0xFFDDEBDD), Color(0xFF233128))
    ReaderTheme.DARK -> ReaderColors(Color(0xFF171416), Color(0xFFE8E0E2))
}

private fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
}
