package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceReaderScreen(
    viewModel: SourceReaderViewModel,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = state.preferences.sourceReaderColors()
    val chapter = state.chapter
    val blocks = chapter?.let { ReaderContentParser.parse(it.bodyHtml, it.bodyText) }.orEmpty()
    val listState = rememberLazyListState()

    LaunchedEffect(chapter?.chapter?.key, state.restoredBlock, blocks.size) {
        if (blocks.isNotEmpty()) {
            listState.scrollToItem(state.restoredBlock.coerceIn(0, blocks.lastIndex))
        }
    }
    LaunchedEffect(chapter?.chapter?.key, blocks.size) {
        if (chapter != null && blocks.isNotEmpty()) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index -> viewModel.saveProgress(index, blocks.size) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        chapter?.chapter?.title ?: "阅读",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.text)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showSettings(true) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "阅读设置", tint = colors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.text,
                ),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = viewModel::previous,
                    enabled = chapter?.previousChapterKey != null,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一章", tint = colors.text)
                }
                IconButton(onClick = onCatalog) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "目录", tint = colors.text)
                }
                IconButton(
                    onClick = viewModel::next,
                    enabled = chapter?.nextChapterKey != null,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一章", tint = colors.text)
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().background(colors.background).padding(padding),
        ) {
            when {
                state.loading -> LoadingPane()
                state.error != null -> ErrorPane(state.error!!, onRetry = viewModel::retry)
                chapter == null -> EmptyPane("章节不存在或暂不可见")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = state.preferences.horizontalPadding.dp,
                        vertical = 18.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(blocks, key = { index, _ -> index }) { _, block ->
                        SourceReaderBlock(block, state.preferences, colors, state.chapterFontFamily)
                    }
                }
            }
        }
    }

    if (state.settingsVisible) {
        SourceReaderSettings(
            preferences = state.preferences,
            onChange = { updated -> viewModel.updatePreferences { updated } },
            onDismiss = { viewModel.showSettings(false) },
        )
    }
}

@Composable
private fun SourceReaderBlock(
    block: ReaderBlock,
    preferences: ReaderPreferences,
    colors: SourceReaderColors,
    chapterFontFamily: FontFamily?,
) {
    when (block) {
        is ReaderBlock.Heading -> Text(
            block.text,
            color = colors.text,
            style = preferences.sourceTextStyle(colors.text, chapterFontFamily).copy(
                fontSize = (preferences.fontSize + 4).sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )

        is ReaderBlock.Paragraph -> Text(
            block.text,
            style = preferences.sourceTextStyle(colors.text, chapterFontFamily).copy(
                textIndent = TextIndent(firstLine = if (block.firstLineIndent) 2.em else 0.em),
            ),
        )

        is ReaderBlock.Illustration -> {
            val ratio = block.width?.toFloat()?.div(block.height?.takeIf { it > 0 } ?: 1)
            var modifier = Modifier.fillMaxWidth()
            modifier = if (ratio != null && ratio.isFinite() && ratio > 0f) {
                modifier.aspectRatio(ratio)
            } else {
                modifier.heightIn(min = 180.dp, max = 520.dp)
            }
            AsyncImage(
                model = block.url,
                contentDescription = "插图",
                modifier = modifier,
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceReaderSettings(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            Text("字体")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { font ->
                    FilterChip(
                        selected = preferences.font == font,
                        onClick = { onChange(preferences.copy(font = font)) },
                        label = { Text(font.label) },
                    )
                }
            }
            Text("背景")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = preferences.theme == theme,
                        onClick = { onChange(preferences.copy(theme = theme)) },
                        label = { Text(theme.label) },
                    )
                }
            }
        }
    }
}

private data class SourceReaderColors(val background: Color, val text: Color)

private fun ReaderPreferences.sourceReaderColors(): SourceReaderColors = when (theme) {
    ReaderTheme.WHITE -> SourceReaderColors(Color(0xFFFFFBFF), Color(0xFF211A1C))
    ReaderTheme.SEPIA -> SourceReaderColors(Color(0xFFF7EED9), Color(0xFF3A3025))
    ReaderTheme.GREEN -> SourceReaderColors(Color(0xFFDDEBDD), Color(0xFF233128))
    ReaderTheme.DARK -> SourceReaderColors(Color(0xFF171416), Color(0xFFE8E0E2))
}

private fun ReaderPreferences.sourceTextStyle(
    color: Color,
    chapterFontFamily: FontFamily?,
) = TextStyle(
    color = color,
    fontFamily = chapterFontFamily ?: when (font) {
        ReaderFont.SANS -> FontFamily.SansSerif
        ReaderFont.SERIF -> FontFamily.Serif
        ReaderFont.MONO -> FontFamily.Monospace
    },
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineHeight).sp,
)
