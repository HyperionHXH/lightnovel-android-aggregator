package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.ui.EmptyPane
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    ImmersiveReaderEffect(
        darkBackground = state.preferences.theme == ReaderTheme.DARK,
        controlsVisible = state.controlsVisible || state.settingsVisible,
    )

    LaunchedEffect(chapter?.chapter?.key, state.restoredBlock, blocks.size) {
        if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.SCROLL && blocks.isNotEmpty()) {
            listState.scrollToItem(state.restoredBlock.coerceIn(0, blocks.lastIndex))
        }
    }
    LaunchedEffect(chapter?.chapter?.key, blocks.size) {
        if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.SCROLL && chapter != null && blocks.isNotEmpty()) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index -> viewModel.saveProgress(index, blocks.size) }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) {
        when {
            state.loading -> LoadingPane()
            state.error != null -> ErrorPane(state.error!!, onRetry = viewModel::retry)
            chapter == null -> EmptyPane("章节不存在或暂不可见")
            else -> if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.PAGED) {
                SourcePagedReader(
                    blocks = blocks,
                    preferences = state.preferences,
                    colors = colors,
                    chapterFontFamily = state.chapterFontFamily,
                    onPreviousChapter = viewModel::previous,
                    hasNextChapter = chapter.nextChapterKey != null,
                    onNextChapter = viewModel::next,
                    onProgress = { index -> viewModel.saveProgress(index, blocks.size) },
                    onToggleControls = viewModel::toggleControls,
                )
            } else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(chapter.chapter.key) {
                        detectTapGestures { position ->
                            val horizontal = position.x / size.width.toFloat().coerceAtLeast(1f)
                            val vertical = position.y / size.height.toFloat().coerceAtLeast(1f)
                            if (horizontal in 0.30f..0.70f && vertical in 0.25f..0.75f) {
                                viewModel.toggleControls()
                            }
                        }
                    },
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

        if (state.controlsVisible && !state.loading && state.error == null) {
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
                    containerColor = colors.background.copy(alpha = 0.97f),
                    titleContentColor = colors.text,
                    navigationIconContentColor = colors.text,
                    actionIconContentColor = colors.text,
                ),
            )
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
private fun SourcePagedReader(
    blocks: List<ReaderBlock>,
    preferences: ReaderPreferences,
    colors: SourceReaderColors,
    chapterFontFamily: FontFamily?,
    onPreviousChapter: () -> Unit,
    hasNextChapter: Boolean,
    onNextChapter: () -> Unit,
    onProgress: (Int) -> Unit,
    onToggleControls: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val horizontalPadding = preferences.horizontalPadding.dp
        val pageWidth = with(density) { (maxWidth - horizontalPadding * 2).roundToPx().coerceAtLeast(1) }
        val pageHeight = with(density) { (maxHeight - 28.dp).roundToPx().coerceAtLeast(1) }
        val pages = remember(blocks, preferences, pageWidth, pageHeight) {
            paginateReaderBlocks(
                blocks = blocks,
                textMeasurer = textMeasurer,
                paragraphStyle = preferences.sourceTextStyle(colors.text, chapterFontFamily),
                headingStyle = preferences.sourceTextStyle(colors.text, chapterFontFamily).copy(fontSize = (preferences.fontSize + 4).sp),
                density = density,
                pageWidthPx = pageWidth,
                pageHeightPx = pageHeight,
                spacingPx = with(density) { 14.dp.roundToPx() },
            )
        }
        val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }
        val pagerScope = rememberCoroutineScope()
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { index ->
                pages.getOrNull(index)?.let { onProgress(it.firstBlockIndex) }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp, bottom = 12.dp)
                .pointerInput(pagerState.currentPage, pages.size, hasNextChapter) {
                    detectTapGestures { position ->
                        when {
                            position.x < size.width * 0.35f -> {
                                if (pagerState.currentPage > 0) {
                                    pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                } else {
                                    onPreviousChapter()
                                }
                            }
                            position.x > size.width * 0.65f -> {
                                if (pagerState.currentPage < pages.lastIndex) {
                                    pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                } else if (hasNextChapter) {
                                    onNextChapter()
                                }
                            }
                            else -> onToggleControls()
                        }
                    }
                },
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
        ) { pageIndex ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                pages[pageIndex].elements.forEach { element ->
                    when (element) {
                        is ReaderPageElement.Text -> Text(
                            element.text,
                            style = preferences.sourceTextStyle(colors.text, chapterFontFamily).copy(
                                fontSize = if (element.heading) (preferences.fontSize + 4).sp else preferences.fontSize.sp,
                                fontWeight = if (element.heading) FontWeight.SemiBold else FontWeight.Normal,
                                textIndent = TextIndent(firstLine = if (element.firstLineIndent) 2.em else 0.em),
                            ),
                        )
                        is ReaderPageElement.Illustration -> ReaderRemoteImage(
                            url = element.block.url,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).height(with(density) { element.heightPx.toDp() }),
                        )
                    }
                }
            }
        }
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
            ReaderRemoteImage(block.url, modifier)
        }
    }
}

@Composable
private fun ReaderRemoteImage(url: String, modifier: Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = "插图",
        modifier = modifier,
        contentScale = ContentScale.Fit,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp)
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("图片加载失败", color = MaterialTheme.colorScheme.error)
            }
        },
    )
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
            Text("翻页方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preferences.mode == mode,
                        onClick = { onChange(preferences.copy(mode = mode)) },
                        label = { Text(mode.label) },
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
