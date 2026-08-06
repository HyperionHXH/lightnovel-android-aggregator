package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit, onCatalog: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = state.preferences.readerColors()
    val blocks = remember(state.chapter) {
        val chapter = state.chapter
        if (chapter == null) emptyList() else buildList {
            add(ReaderBlock.Heading(chapter.chapter.title))
            addAll(ReaderContentParser.parse(chapter.bodyHtml, chapter.bodyText))
        }
    }
    var anchorBlock by rememberSaveable(state.chapter?.chapter?.id) {
        mutableIntStateOf(state.restoredParagraph.coerceAtLeast(0))
    }

    LaunchedEffect(state.chapter?.chapter?.id, state.restoredParagraph) {
        anchorBlock = state.restoredParagraph.coerceAtLeast(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when {
            state.loading -> LoadingPane(Modifier.align(Alignment.Center))
            state.error != null -> ErrorPane(
                message = state.error!!,
                modifier = Modifier.align(Alignment.Center),
                onRetry = viewModel::retry,
            )
            state.preferences.mode == ReaderMode.PAGED -> PagedReader(
                blocks = blocks,
                preferences = state.preferences,
                colors = colors,
                anchorBlock = anchorBlock,
                onAnchorChanged = { anchorBlock = it },
                onProgress = { index -> viewModel.saveProgress(index, blocks.size) },
                onToggleControls = viewModel::toggleControls,
                onPreviousChapter = viewModel::previous,
                onNextChapter = viewModel::next,
                hasPreviousChapter = state.chapter?.previousChapterId != null,
                hasNextChapter = state.chapter?.nextChapterId != null,
            )
            else -> ScrollingReader(
                blocks = blocks,
                preferences = state.preferences,
                colors = colors,
                anchorBlock = anchorBlock,
                onAnchorChanged = { anchorBlock = it },
                onProgress = { index -> viewModel.saveProgress(index, blocks.size) },
                onToggleControls = viewModel::toggleControls,
            )
        }

        if (state.controlsVisible && !state.loading && state.error == null) {
            ReaderControls(
                bookTitle = state.chapter?.bookTitle ?: "阅读",
                colors = colors,
                onBack = onBack,
                onCatalog = onCatalog,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onSettings = { viewModel.showSettings(true) },
                previousEnabled = state.chapter?.previousChapterId != null,
                nextEnabled = state.chapter?.nextChapterId != null,
            )
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
private fun PagedReader(
    blocks: List<ReaderBlock>,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    anchorBlock: Int,
    onAnchorChanged: (Int) -> Unit,
    onProgress: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val paragraphStyle = preferences.paragraphStyle(colors.text)
        val headingStyle = preferences.headingStyle(colors.text)
        val horizontalPadding = preferences.horizontalPadding.dp
        val pageTopPadding = 72.dp
        val pageBottomPadding = 88.dp
        val pageWidthPx = with(density) { (maxWidth - horizontalPadding * 2).roundToPx().coerceAtLeast(1) }
        val pageHeightPx = with(density) {
            (maxHeight - pageTopPadding - pageBottomPadding).roundToPx().coerceAtLeast(1)
        }
        val spacingPx = with(density) { 14.dp.roundToPx() }
        val pages = remember(blocks, preferences.font, preferences.fontSize, preferences.lineHeight, preferences.horizontalPadding, pageWidthPx, pageHeightPx) {
            paginateReaderBlocks(
                blocks = blocks,
                textMeasurer = textMeasurer,
                paragraphStyle = paragraphStyle,
                headingStyle = headingStyle,
                density = density,
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                spacingPx = spacingPx,
            )
        }
        val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(pages) {
            val containingPage = pages.indexOfFirst { anchorBlock in it.firstBlockIndex..it.lastBlockIndex }
            val target = (containingPage.takeIf { it >= 0 } ?: pages.indexOfLast { it.firstBlockIndex <= anchorBlock })
                .coerceAtLeast(0)
                .coerceAtMost(pages.lastIndex.coerceAtLeast(0))
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { pageIndex ->
                    pages.getOrNull(pageIndex)?.firstBlockIndex?.let {
                        onAnchorChanged(it)
                        onProgress(it)
                    }
                }
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pageTopPadding, bottom = pageBottomPadding)
                .pointerInput(pages.size, hasPreviousChapter, hasNextChapter) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val fraction = up.position.x / size.width.toFloat().coerceAtLeast(1f)
                        when {
                            fraction < 0.30f && pagerState.currentPage > 0 -> scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                            fraction < 0.30f && hasPreviousChapter -> onPreviousChapter()
                            fraction > 0.70f && pagerState.currentPage < pages.lastIndex -> scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                            fraction > 0.70f && hasNextChapter -> onNextChapter()
                            else -> onToggleControls()
                        }
                    }
                },
        ) { pageIndex ->
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                pages.getOrNull(pageIndex)?.elements.orEmpty().forEach { element ->
                    when (element) {
                        is ReaderPageElement.Text -> ReaderTextElement(element, preferences, colors)
                        is ReaderPageElement.Illustration -> ReaderIllustration(
                            block = element.block,
                            modifier = Modifier.fillMaxWidth().height(with(density) { element.heightPx.toDp() }),
                            colors = colors,
                        )
                    }
                }
            }
        }

        Text(
            text = "${pagerState.currentPage + 1} / ${pages.size.coerceAtLeast(1)}",
            color = colors.text.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = horizontalPadding, bottom = 72.dp),
        )
    }
}

@Composable
private fun ScrollingReader(
    blocks: List<ReaderBlock>,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    anchorBlock: Int,
    onAnchorChanged: (Int) -> Unit,
    onProgress: (Int) -> Unit,
    onToggleControls: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(blocks, anchorBlock) {
        if (blocks.isNotEmpty() && listState.firstVisibleItemIndex == 0) {
            listState.scrollToItem(anchorBlock.coerceIn(0, blocks.lastIndex))
        }
    }
    LaunchedEffect(listState, blocks.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                onAnchorChanged(it)
                onProgress(it)
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().clickable(onClick = onToggleControls),
        contentPadding = PaddingValues(
            start = preferences.horizontalPadding.dp,
            end = preferences.horizontalPadding.dp,
            top = 88.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(blocks) { _, block ->
            when (block) {
                is ReaderBlock.Heading -> Text(block.text, style = preferences.headingStyle(colors.text))
                is ReaderBlock.Paragraph -> Text(
                    block.text,
                    style = preferences.paragraphStyle(colors.text).copy(
                        textIndent = if (block.firstLineIndent) TextIndent(firstLine = preferences.fontSize.sp * 2) else TextIndent.None,
                    ),
                )
                is ReaderBlock.Illustration -> ReaderIllustration(block, Modifier.fillMaxWidth(), colors)
            }
        }
    }
}

@Composable
private fun ReaderTextElement(element: ReaderPageElement.Text, preferences: ReaderPreferences, colors: ReaderColors) {
    val style = if (element.heading) preferences.headingStyle(colors.text) else preferences.paragraphStyle(colors.text)
    Text(
        text = element.text,
        style = style.copy(
            textIndent = if (element.firstLineIndent) TextIndent(firstLine = preferences.fontSize.sp * 2) else TextIndent.None,
        ),
    )
}

@Composable
private fun ReaderIllustration(block: ReaderBlock.Illustration, modifier: Modifier, colors: ReaderColors) {
    SubcomposeAsyncImage(
        model = block.url,
        contentDescription = "正文插图",
        contentScale = ContentScale.Fit,
        modifier = modifier,
        loading = {
            Box(Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.04f)), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(Modifier.fillMaxWidth(0.45f))
            }
        },
        error = {
            Box(Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.04f)), contentAlignment = Alignment.Center) {
                Text("插图加载失败", color = colors.text.copy(alpha = 0.7f))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderControls(
    bookTitle: String,
    colors: ReaderColors,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSettings: () -> Unit,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
) {
    TopAppBar(
        title = { Text(bookTitle) },
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        actions = { TextButton(onClick = onCatalog) { Text("目录") } },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.text,
            navigationIconContentColor = colors.text,
            actionIconContentColor = colors.text,
        ),
    )
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        color = colors.background,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious, enabled = previousEnabled) { Text("上一章") }
            Button(onClick = onSettings) { Text("阅读设置") }
            TextButton(onClick = onNext, enabled = nextEnabled) { Text("下一章") }
        }
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
                Text("翻页方式")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.mode == mode,
                            onClick = { onChange(preferences.copy(mode = mode)) },
                            label = { Text(mode.label) },
                        )
                    }
                }
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

private fun ReaderPreferences.paragraphStyle(color: Color) = TextStyle(
    color = color,
    fontFamily = font.family(),
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineHeight).sp,
)

private fun ReaderPreferences.headingStyle(color: Color) = TextStyle(
    color = color,
    fontFamily = font.family(),
    fontSize = (fontSize + 5).sp,
    lineHeight = ((fontSize + 5) * lineHeight).sp,
)

private fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
}
