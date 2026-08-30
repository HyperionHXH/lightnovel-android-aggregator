package io.github.jiangyuyi.lightnovel.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
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
    val blocks = chapter?.let {
        buildList {
            add(ReaderBlock.Heading(it.chapter.title))
            addAll(ReaderContentParser.parse(it.bodyHtml, it.bodyText))
        }
    }.orEmpty()
    val listState = rememberLazyListState()
    val scrollBoundaryOffset = if (chapter?.previousChapterKey != null) 1 else 0
    val progressBlockCount = (blocks.size - 1).coerceAtLeast(1)
    val listScope = rememberCoroutineScope()
    var menuVisible by remember { mutableStateOf(false) }
    var scrollScrubValue by remember(chapter?.chapter?.key) { mutableFloatStateOf(0f) }
    var scrollScrubbing by remember { mutableStateOf(false) }
    val menuProgress = remember(state.restoredBlock, progressBlockCount) {
        "已阅读 ${((state.restoredBlock.toFloat() / progressBlockCount) * 100).roundToInt().coerceIn(0, 100)}%"
    }

    ImmersiveReaderEffect(
        darkBackground = state.preferences.theme == ReaderTheme.DARK,
        controlsVisible = state.controlsVisible || state.settingsVisible,
    )

    LaunchedEffect(chapter?.chapter?.key, state.restoredBlock, blocks.size) {
        if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.SCROLL && blocks.isNotEmpty()) {
            listState.scrollToItem((state.restoredBlock + scrollBoundaryOffset + 1).coerceIn(0, blocks.lastIndex + scrollBoundaryOffset))
        }
    }
    LaunchedEffect(chapter?.chapter?.key, blocks.size) {
        if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.SCROLL && chapter != null && blocks.isNotEmpty()) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index ->
                    viewModel.saveProgress(
                        (index - scrollBoundaryOffset - 1).coerceAtLeast(0),
                        progressBlockCount,
                    )
                    if (!scrollScrubbing) {
                        val blockIndex = (index - scrollBoundaryOffset - 1).coerceIn(0, progressBlockCount)
                        scrollScrubValue = blockIndex.toFloat() / progressBlockCount.toFloat()
                    }
                }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) {
        when {
            state.error != null && chapter == null -> ErrorPane(state.error!!, onRetry = viewModel::retry)
            chapter == null && state.loading -> LoadingPane()
            chapter == null -> EmptyPane("章节不存在或暂不可见")
            else -> if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.PAGED) {
                SourcePagedReader(
                    blocks = blocks,
                    chapterTitle = chapter.chapter.title,
                    preferences = state.preferences,
                    colors = colors,
                    chapterFontFamily = state.chapterFontFamily,
                    anchorBlock = state.restoredBlock + 1,
                    onPreviousChapter = viewModel::previous,
                    hasPreviousChapter = chapter.previousChapterKey != null,
                    hasNextChapter = chapter.nextChapterKey != null,
                    onNextChapter = viewModel::next,
                    onProgress = { index -> viewModel.saveProgress((index - 1).coerceAtLeast(0), progressBlockCount) },
                    onToggleControls = viewModel::toggleControls,
                    controlsVisible = state.controlsVisible,
                    showProgressBar = state.preferences.showProgressBar,
                )
            } else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (state.controlsVisible) 64.dp else 0.dp)
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
                    start = state.preferences.horizontalPadding.dp,
                    end = state.preferences.horizontalPadding.dp,
                    top = 18.dp,
                    bottom = if (state.controlsVisible && state.preferences.showProgressBar) 86.dp else 18.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (chapter.previousChapterKey != null) {
                    item { SourceChapterBoundary("上一章", viewModel::previous, previous = true) }
                }
                itemsIndexed(blocks, key = { index, _ -> index }) { _, block ->
                    SourceReaderBlock(block, state.preferences, colors, state.chapterFontFamily)
                }
                if (chapter.nextChapterKey != null) {
                    item { SourceChapterBoundary("下一章", viewModel::next, previous = false) }
                }
            }
        }

        if (state.loading && chapter != null) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = colors.text,
                trackColor = colors.text.copy(alpha = 0.12f),
            )
        }

        if (state.error != null && chapter != null) {
            Text(
                text = state.error!!,
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(colors.background.copy(alpha = 0.96f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        if (state.controlsVisible && !state.loading && (state.error == null || chapter != null)) {
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
                    IconButton(onClick = { menuVisible = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "阅读菜单", tint = colors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.text,
                    navigationIconContentColor = colors.text,
                    actionIconContentColor = colors.text,
                ),
            )
        }

        if (state.preferences.mode == io.github.jiangyuyi.lightnovel.core.model.ReaderMode.SCROLL &&
            state.controlsVisible && state.preferences.showProgressBar && chapter != null
        ) {
            ReaderProgressBar(
                label = "${chapter.chapter.title} · 已读 ${(scrollScrubValue * 100).roundToInt().coerceIn(0, 100)}%",
                value = scrollScrubValue,
                background = colors.background,
                contentColor = colors.text,
                onValueChange = {
                    scrollScrubbing = true
                    scrollScrubValue = it
                },
                onValueChangeFinished = {
                    val targetBlock = (scrollScrubValue * progressBlockCount).roundToInt().coerceIn(0, progressBlockCount)
                    scrollScrubbing = false
                    listScope.launch {
                        val maxIndex = (blocks.lastIndex + scrollBoundaryOffset).coerceAtLeast(0)
                        listState.scrollToItem((targetBlock + scrollBoundaryOffset + 1).coerceIn(0, maxIndex))
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (menuVisible) {
        ReaderMenuSheet(
            bookTitle = chapter?.novelTitle ?: "阅读",
            chapterTitle = chapter?.chapter?.title ?: "当前章节",
            mode = state.preferences.mode,
            progressText = menuProgress,
            showProgressBar = state.preferences.showProgressBar,
            background = colors.background,
            contentColor = colors.text,
            onDismiss = { menuVisible = false },
            onCatalog = onCatalog,
            onSettings = { viewModel.showSettings(true) },
            onRetry = state.error?.let { viewModel::retry },
            onToggleProgressBar = {
                viewModel.updatePreferences { it.copy(showProgressBar = !it.showProgressBar) }
            },
        )
    }

    if (state.settingsVisible) {
        ReaderSettingsDialog(
            preferences = state.preferences,
            onChange = { updated -> viewModel.updatePreferences { updated } },
            onDismiss = { viewModel.showSettings(false) },
        )
    }
}

@Composable
private fun SourcePagedReader(
    blocks: List<ReaderBlock>,
    chapterTitle: String,
    preferences: ReaderPreferences,
    colors: SourceReaderColors,
    chapterFontFamily: FontFamily?,
    anchorBlock: Int,
    onPreviousChapter: () -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onNextChapter: () -> Unit,
    onProgress: (Int) -> Unit,
    onToggleControls: () -> Unit,
    controlsVisible: Boolean,
    showProgressBar: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val horizontalPadding = preferences.horizontalPadding.dp
        val pageWidth = with(density) { (maxWidth - horizontalPadding * 2).roundToPx().coerceAtLeast(1) }
        val pageHeight = with(density) {
            (
                maxHeight -
                    (if (controlsVisible) 64.dp else 0.dp) -
                    (if (controlsVisible && showProgressBar) 86.dp else 0.dp) -
                    28.dp
            ).roundToPx().coerceAtLeast(1)
        }
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
        var currentAnchor by remember(blocks) { mutableIntStateOf(anchorBlock) }
        var scrubValue by remember { mutableFloatStateOf(0f) }
        var scrubbing by remember { mutableStateOf(false) }
        LaunchedEffect(pages) {
            val target = pages.indexOfFirst { currentAnchor in it.firstBlockIndex..it.lastBlockIndex }
                .takeIf { it >= 0 }
                ?: pages.indexOfLast { it.firstBlockIndex <= currentAnchor }.coerceAtLeast(0)
            pagerState.scrollToPage(target.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
        }
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { index ->
                if (!scrubbing) {
                    scrubValue = if (pages.size <= 1) 0f else index.coerceIn(0, pages.lastIndex).toFloat() / pages.lastIndex.toFloat()
                }
                pages.getOrNull(index)?.let {
                    currentAnchor = it.firstBlockIndex
                    onProgress(it.firstBlockIndex)
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 8.dp + if (controlsVisible) 64.dp else 0.dp,
                    bottom = if (controlsVisible && showProgressBar) 86.dp else 12.dp,
                )
                .pointerInput(pagerState.currentPage, pages.size, hasNextChapter) {
                    detectTapGestures { position ->
                        when {
                            position.x < size.width * 0.25f -> {
                                if (pagerState.currentPage > 0) {
                                    pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                } else if (hasPreviousChapter) {
                                    onPreviousChapter()
                                }
                            }
                            position.x > size.width * 0.75f -> {
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
                verticalArrangement = if (pages[pageIndex].elements.size == 1 &&
                    pages[pageIndex].elements.firstOrNull() is ReaderPageElement.Illustration
                ) Arrangement.Center else Arrangement.spacedBy(14.dp),
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
        if (controlsVisible && showProgressBar) {
            val visiblePage = pagerState.currentPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
            ReaderProgressBar(
                label = "$chapterTitle · 第 ${visiblePage + 1} / ${pages.size.coerceAtLeast(1)} 页",
                value = scrubValue,
                background = colors.background,
                contentColor = colors.text,
                onValueChange = {
                    scrubbing = true
                    scrubValue = it
                },
                onValueChangeFinished = {
                    val target = if (pages.size <= 1) 0 else (scrubValue * pages.lastIndex).roundToInt()
                    scrubbing = false
                    pagerScope.launch { pagerState.scrollToPage(target.coerceIn(0, pages.lastIndex.coerceAtLeast(0))) }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
private fun SourceChapterBoundary(label: String, onClick: () -> Unit, previous: Boolean) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
    ) {
        Text(if (previous) "‹  $label" else "$label  ›")
    }
}

@Composable
private fun ReaderRemoteImage(url: String, modifier: Modifier) {
    var zoomed by remember(url) { mutableStateOf(false) }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = "插图",
        modifier = modifier.clickable { zoomed = true },
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
    if (zoomed) {
        Dialog(
            onDismissRequest = { zoomed = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { zoomed = false },
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = "放大插图",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
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
