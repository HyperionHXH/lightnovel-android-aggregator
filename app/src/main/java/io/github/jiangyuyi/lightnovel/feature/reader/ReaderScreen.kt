package io.github.jiangyuyi.lightnovel.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.toArgb
import coil.compose.SubcomposeAsyncImage
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderImageScale
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderOrientation
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapZone
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.reader.UserFontRepository
import io.github.jiangyuyi.lightnovel.core.reader.fontLabel
import io.github.jiangyuyi.lightnovel.core.reader.fontFamily
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.ReaderImagePreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onFontPicker: () -> Unit = {},
    userFonts: UserFontRepository? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = state.preferences.readerColors()
    val safeTopPadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val chapterId = state.chapter?.chapter?.id
    val installedUserFontIds by (userFonts?.installed ?: flowOf(emptySet()))
        .collectAsStateWithLifecycle(initialValue = emptySet())
    var customFontFamily by remember(state.preferences.customFontId) { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(state.preferences.customFontId, installedUserFontIds) {
        customFontFamily = if (state.preferences.customFontId in installedUserFontIds) {
            userFonts?.load(state.preferences.customFontId)
        } else {
            null
        }
    }
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
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    val menuProgress = remember(state.restoredParagraph, blocks.size) {
        val total = (blocks.size - 1).coerceAtLeast(1)
        "已阅读 ${((state.restoredParagraph.toFloat() / total) * 100).roundToInt().coerceIn(0, 100)}%"
    }
    val volumePagingEnabled = readerVolumePagingEnabled(
        preferenceEnabled = state.preferences.volumeKeys,
        menuVisible = menuVisible,
        settingsVisible = state.settingsVisible,
        textSettingsVisible = state.textSettingsVisible,
    )

    ImmersiveReaderEffect(
        darkBackground = state.preferences.theme == ReaderTheme.DARK,
        barColor = colors.background,
    )
    ReaderKeepScreenOnEffect(state.preferences.keepScreenOn)
    ReaderOrientationEffect(state.preferences.orientation)

    LaunchedEffect(state.chapter?.chapter?.id, state.restoredParagraph) {
        anchorBlock = state.restoredParagraph.coerceAtLeast(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when {
            state.loading && state.chapter == null -> ReaderLoadingPane(
                background = colors.background,
                contentColor = colors.text,
            )
            state.error != null && state.chapter == null -> ErrorPane(
                message = state.error!!,
                modifier = Modifier.align(Alignment.Center),
                onRetry = viewModel::retry,
            )
            state.preferences.mode == ReaderMode.PAGED -> PagedReader(
                blocks = blocks,
                chapterTitle = state.chapter?.chapter?.title ?: "当前章节",
                preferences = state.preferences,
                colors = colors,
                customFontFamily = customFontFamily,
                anchorBlock = anchorBlock,
                onAnchorChanged = { anchorBlock = it },
                onProgress = { index -> viewModel.saveProgress(index, blocks.size) },
                onToggleControls = viewModel::toggleControls,
                onPreviousChapter = viewModel::previous,
                onNextChapter = viewModel::next,
                hasPreviousChapter = state.chapter?.previousChapterId != null,
                hasNextChapter = state.chapter?.nextChapterId != null,
                safeTopPadding = safeTopPadding,
                controlsVisible = state.controlsVisible,
                showProgressBar = state.preferences.showProgressBar,
                volumePagingEnabled = volumePagingEnabled,
            )
            else -> ScrollingReader(
                blocks = blocks,
                chapterTitle = state.chapter?.chapter?.title ?: "当前章节",
                preferences = state.preferences,
                colors = colors,
                customFontFamily = customFontFamily,
                anchorBlock = anchorBlock,
                onAnchorChanged = { anchorBlock = it },
                onProgress = { index -> viewModel.saveProgress(index, blocks.size) },
                onToggleControls = viewModel::toggleControls,
                onPreviousChapter = viewModel::previous,
                onNextChapter = viewModel::next,
                hasPreviousChapter = state.chapter?.previousChapterId != null,
                hasNextChapter = state.chapter?.nextChapterId != null,
                safeTopPadding = safeTopPadding,
                controlsVisible = state.controlsVisible,
                showProgressBar = state.preferences.showProgressBar,
                volumePagingEnabled = volumePagingEnabled,
            )
        }

        if (state.refreshing) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = safeTopPadding),
            )
        }

        if (state.loading && state.chapter != null) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = safeTopPadding),
            )
        }

        state.refreshError?.let { message ->
            ReaderStatusBanner(message, colors)
        }

        if (state.controlsVisible && !state.loading && (state.error == null || state.chapter != null)) {
            ReaderControls(
                title = state.chapter?.chapter?.title ?: "阅读",
                colors = colors,
                onBack = onBack,
                onMenu = { menuVisible = true },
            )
        }
    }

    if (menuVisible) {
        ReaderMenuSheet(
            bookTitle = state.chapter?.bookTitle ?: "阅读",
            chapterTitle = state.chapter?.chapter?.title ?: "当前章节",
            mode = state.preferences.mode,
            progressText = menuProgress,
            showProgressBar = state.preferences.showProgressBar,
            background = colors.background,
            contentColor = colors.text,
            onDismiss = { menuVisible = false },
            onCatalog = onCatalog,
            onSettings = { viewModel.showSettings(true) },
            onTextSettings = { viewModel.showTextSettings(true) },
            onRetry = state.refreshError?.let { viewModel::retry },
            onToggleProgressBar = {
                viewModel.updatePreferences { it.copy(showProgressBar = !it.showProgressBar) }
            },
        )
    }

    if (state.settingsVisible) {
        ReaderSettingsDialog(
            preferences = state.preferences,
            onChange = { value -> viewModel.updatePreferences { value } },
            onDismiss = { viewModel.showSettings(false) },
        )
    }

    if (state.textSettingsVisible) {
        ReaderTextSettingsDialog(
            preferences = state.preferences,
            currentFontLabel = state.preferences.fontLabel(),
            onChange = { value -> viewModel.updatePreferences { value } },
            onDismiss = { viewModel.showTextSettings(false) },
            onChooseFont = {
                viewModel.showTextSettings(false)
                onFontPicker()
            },
        )
    }

}

@Composable
private fun PagedReader(
    blocks: List<ReaderBlock>,
    chapterTitle: String,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    customFontFamily: FontFamily?,
    anchorBlock: Int,
    onAnchorChanged: (Int) -> Unit,
    onProgress: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    safeTopPadding: Dp,
    controlsVisible: Boolean,
    showProgressBar: Boolean,
    volumePagingEnabled: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val paragraphStyle = preferences.paragraphStyle(colors.text, customFontFamily)
        val headingStyle = preferences.headingStyle(colors.text, customFontFamily)
        val horizontalPadding = preferences.horizontalPadding.dp
        // Keep pagination stable while the title bar/progress overlay toggles.
        // The status-bar inset is permanent; reader controls are intentionally
        // drawn over the page, matching Lithium's no-reflow behavior.
        val pageTopPadding = safeTopPadding + 8.dp
        val pageBottomPadding = 12.dp
        val pageWidthPx = with(density) { (maxWidth - horizontalPadding * 2).roundToPx().coerceAtLeast(1) }
        val pageHeightPx = with(density) {
            (maxHeight - pageTopPadding - pageBottomPadding).roundToPx().coerceAtLeast(1)
        }
        val spacingPx = with(density) { 14.dp.roundToPx() }
        val pages = remember(blocks, preferences.font, preferences.customFontId, customFontFamily, preferences.fontSize, preferences.lineHeight, preferences.horizontalPadding, pageWidthPx, pageHeightPx) {
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
        val pagerState = rememberPagerState {
            pages.size.coerceAtLeast(1) + if (hasNextChapter) 1 else 0
        }
        val pagerScope = rememberCoroutineScope()
        var scrubValue by remember { mutableFloatStateOf(0f) }
        var scrubbing by remember { mutableStateOf(false) }
        var turnRequest by remember { mutableStateOf<ReaderTurnRequest?>(null) }
        var turnRequestToken by remember { mutableIntStateOf(0) }

        ReaderVolumeKeyEffect(
            enabled = volumePagingEnabled,
            onPrevious = {
                turnRequestToken += 1
                turnRequest = ReaderTurnRequest(ReaderTurnDirection.PREVIOUS, turnRequestToken)
            },
            onNext = {
                turnRequestToken += 1
                turnRequest = ReaderTurnRequest(ReaderTurnDirection.NEXT, turnRequestToken)
            },
        )

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
                    if (!scrubbing) {
                        scrubValue = if (pages.size <= 1) 0f else {
                            pageIndex.coerceIn(0, pages.lastIndex).toFloat() / pages.lastIndex.toFloat()
                        }
                    }
                    pages.getOrNull(pageIndex)?.firstBlockIndex?.let {
                        onAnchorChanged(it)
                        onProgress(it)
                    }
                }
        }
        LaunchedEffect(pagerState.currentPage, pages.size, hasNextChapter) {
            if (hasNextChapter && pagerState.currentPage == pages.size) onNextChapter()
        }
        LaunchedEffect(turnRequest?.token) {
            val request = turnRequest ?: return@LaunchedEffect
            when (request.direction) {
                ReaderTurnDirection.PREVIOUS -> when {
                    pagerState.currentPage > 0 -> pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    hasPreviousChapter -> onPreviousChapter()
                }
                ReaderTurnDirection.NEXT -> when {
                    pagerState.currentPage < pages.lastIndex -> pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    hasNextChapter -> onNextChapter()
                }
            }
            if (turnRequest == request) turnRequest = null
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pageTopPadding, bottom = pageBottomPadding),
        ) { pageIndex ->
            if (pageIndex < pages.size) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    pages.getOrNull(pageIndex)?.elements.orEmpty().forEach { element ->
                        when (element) {
                            is ReaderPageElement.Text -> ReaderTextElement(element, preferences, colors, customFontFamily)
                            is ReaderPageElement.Illustration -> ReaderIllustration(
                                block = element.block,
                                modifier = Modifier.fillMaxWidth().height(with(density) { element.heightPx.toDp() }),
                                colors = colors,
                                imageScale = preferences.imageScale,
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在进入下一章…", color = colors.text.copy(alpha = 0.72f))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    pages.size,
                    hasPreviousChapter,
                    hasNextChapter,
                    preferences.tapZone,
                    preferences.tapInversion,
                ) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var releasedX: Float? = null
                        var releasedY: Float? = null
                        while (releasedX == null) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                releasedX = change.position.x
                                releasedY = change.position.y
                            }
                        }
                        val endX = releasedX ?: return@awaitEachGesture
                        val endY = releasedY ?: return@awaitEachGesture
                        val deltaX = endX - start.x
                        val deltaY = endY - start.y

                        fun requestTurn(direction: ReaderTurnDirection) {
                            turnRequestToken += 1
                            turnRequest = ReaderTurnRequest(direction, turnRequestToken)
                        }

                        when {
                            abs(deltaX) <= viewConfiguration.touchSlop && abs(deltaY) <= viewConfiguration.touchSlop -> {
                                when (readerTapAction(
                                    preferences.tapZone,
                                    endX / size.width.toFloat().coerceAtLeast(1f),
                                    endY / size.height.toFloat().coerceAtLeast(1f),
                                    preferences.tapInversion,
                                )) {
                                    ReaderTapAction.PREVIOUS -> requestTurn(ReaderTurnDirection.PREVIOUS)
                                    ReaderTapAction.NEXT -> requestTurn(ReaderTurnDirection.NEXT)
                                    ReaderTapAction.CONTROLS -> onToggleControls()
                                    ReaderTapAction.NONE -> Unit
                                }
                            }
                        }
                    }
                },
        )

        if (pagerState.currentPage < pages.size) {
            Text(
                text = "${pagerState.currentPage + 1} / ${pages.size.coerceAtLeast(1)}",
                color = colors.text.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
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
private fun ScrollingReader(
    blocks: List<ReaderBlock>,
    chapterTitle: String,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    customFontFamily: FontFamily?,
    anchorBlock: Int,
    onAnchorChanged: (Int) -> Unit,
    onProgress: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    safeTopPadding: Dp,
    controlsVisible: Boolean,
    showProgressBar: Boolean,
    volumePagingEnabled: Boolean,
) {
    val listState = rememberLazyListState()
    val listScope = rememberCoroutineScope()
    val boundaryOffset = if (hasPreviousChapter) 1 else 0
    val progressCount = (blocks.size - 1).coerceAtLeast(1)
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    ReaderVolumeKeyEffect(
        enabled = volumePagingEnabled,
        onPrevious = {
            listScope.launch {
                val amount = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset)
                    .coerceAtLeast(1)
                listState.animateScrollBy(-amount.toFloat())
            }
        },
        onNext = {
            listScope.launch {
                val amount = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset)
                    .coerceAtLeast(1)
                listState.animateScrollBy(amount.toFloat())
            }
        },
    )
    LaunchedEffect(blocks) {
        if (blocks.isNotEmpty() && listState.firstVisibleItemIndex == 0) {
            listState.scrollToItem((anchorBlock + boundaryOffset).coerceIn(0, blocks.lastIndex + boundaryOffset))
        }
    }
    LaunchedEffect(listState, blocks.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                onAnchorChanged((it - boundaryOffset).coerceAtLeast(0))
                onProgress((it - boundaryOffset).coerceAtLeast(0))
                if (!scrubbing) {
                    scrubValue = (it - boundaryOffset).coerceIn(0, progressCount).toFloat() / progressCount.toFloat()
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = safeTopPadding)
                .pointerInput(onToggleControls) {
                    detectTapGestures { position ->
                        val horizontalFraction = position.x / size.width.toFloat().coerceAtLeast(1f)
                        val verticalFraction = position.y / size.height.toFloat().coerceAtLeast(1f)
                        if (horizontalFraction in 0.30f..0.70f && verticalFraction in 0.25f..0.75f) {
                            onToggleControls()
                        }
                    }
                },
            contentPadding = PaddingValues(
                start = preferences.horizontalPadding.dp,
                end = preferences.horizontalPadding.dp,
                top = 8.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (blocks.isNotEmpty() && hasPreviousChapter) {
                item { ReaderChapterEnd("上一章", onPreviousChapter, previous = true) }
            }

            itemsIndexed(blocks) { _, block ->
                when (block) {
                    is ReaderBlock.Heading -> Text(block.text, style = preferences.headingStyle(colors.text, customFontFamily))
                    is ReaderBlock.Paragraph -> Text(
                        block.text,
                        style = preferences.paragraphStyle(colors.text, customFontFamily).copy(
                            textIndent = if (block.firstLineIndent) TextIndent(firstLine = preferences.fontSize.sp * 2) else TextIndent.None,
                        ),
                    )
                    is ReaderBlock.Illustration -> ReaderIllustration(
                        block,
                        Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp),
                        colors,
                        preferences.imageScale,
                    )
                }
            }
            if (blocks.isNotEmpty() && hasNextChapter) {
                item {
                    ReaderChapterEnd("下一章", onNextChapter, previous = false)
                }
            }
        }
        if (controlsVisible && showProgressBar) {
            ReaderProgressBar(
                label = "$chapterTitle · 已读 ${(scrubValue * 100).roundToInt().coerceIn(0, 100)}%",
                value = scrubValue,
                background = colors.background,
                contentColor = colors.text,
                onValueChange = {
                    scrubbing = true
                    scrubValue = it
                },
                onValueChangeFinished = {
                    val targetBlock = (scrubValue * progressCount).roundToInt().coerceIn(0, progressCount)
                    scrubbing = false
                    // The first list item is an optional chapter boundary; the heading is block zero.
                    listScope.launch {
                        val maxIndex = (blocks.lastIndex + boundaryOffset).coerceAtLeast(0)
                        listState.scrollToItem((targetBlock + boundaryOffset).coerceIn(0, maxIndex))
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ReaderChapterEnd(label: String, onClick: () -> Unit, previous: Boolean) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
    ) {
        Text(if (previous) "‹  $label" else "$label  ›")
    }
}

@Composable
private fun ReaderTextElement(
    element: ReaderPageElement.Text,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    customFontFamily: FontFamily?,
) {
    val style = if (element.heading) preferences.headingStyle(colors.text, customFontFamily) else preferences.paragraphStyle(colors.text, customFontFamily)
    Text(
        text = element.text,
        style = style.copy(
            textIndent = if (element.firstLineIndent) TextIndent(firstLine = preferences.fontSize.sp * 2) else TextIndent.None,
        ),
    )
}

@Composable
private fun ReaderIllustration(
    block: ReaderBlock.Illustration,
    modifier: Modifier,
    colors: ReaderColors,
    imageScale: ReaderImageScale,
) {
    ReaderImagePreview(block.url, modifier, imageScale, "正文插图", "插图加载失败", colors.text.copy(alpha = 0.7f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderControls(
    title: String,
    colors: ReaderColors,
    onBack: () -> Unit,
    onMenu: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "阅读菜单")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = readerControlPanelColor(colors.background),
            titleContentColor = colors.text,
            navigationIconContentColor = colors.text,
            actionIconContentColor = colors.text,
        ),
    )
}

@Composable
private fun ReaderStatusBanner(message: String, colors: ReaderColors) {
    Text(
        text = message,
        color = colors.text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(colors.background.copy(alpha = 0.96f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
internal fun ReaderSettingsDialog(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("阅读设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("翻页方式")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderMode.entries.forEach { mode ->
                        ReaderOptionChip(
                            selected = preferences.mode == mode,
                            onClick = { onChange(preferences.copy(mode = mode)) },
                            label = mode.label,
                        )
                    }
                }
                Text("点击区域")
                ReaderTapZone.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { zone ->
                            ReaderOptionChip(
                                selected = preferences.tapZone == zone,
                                onClick = { onChange(preferences.copy(tapZone = zone)) },
                                label = zone.label,
                            )
                        }
                    }
                }
                Text("反转点击区域")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderTapInversion.entries.forEach { inversion ->
                        ReaderOptionChip(
                            selected = preferences.tapInversion == inversion,
                            onClick = { onChange(preferences.copy(tapInversion = inversion)) },
                            label = inversion.label,
                        )
                    }
                }
                Text("屏幕方向")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderOrientation.entries.forEach { orientation ->
                        ReaderOptionChip(
                            selected = preferences.orientation == orientation,
                            onClick = { onChange(preferences.copy(orientation = orientation)) },
                            label = orientation.label,
                        )
                    }
                }
                Text("图片缩放")
                ReaderImageScale.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { scale ->
                            ReaderOptionChip(
                                selected = preferences.imageScale == scale,
                                onClick = { onChange(preferences.copy(imageScale = scale)) },
                                label = scale.label,
                            )
                        }
                    }
                }
                ReaderSettingSwitch(
                    label = "音量键翻页",
                    checked = preferences.volumeKeys,
                    onCheckedChange = { onChange(preferences.copy(volumeKeys = it)) },
                )
                ReaderSettingSwitch(
                    label = "保持屏幕常亮",
                    checked = preferences.keepScreenOn,
                    onCheckedChange = { onChange(preferences.copy(keepScreenOn = it)) },
                )
                Text("背景")
                ReaderTheme.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { theme ->
                            ReaderOptionChip(
                                selected = preferences.theme == theme,
                                onClick = { onChange(preferences.copy(theme = theme)) },
                                label = theme.label,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun ReaderTextSettingsDialog(
    preferences: ReaderPreferences,
    currentFontLabel: String? = null,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
    sourceFontRequired: Boolean = false,
    onChooseFont: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("文字样式") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("字体")
                Text(
                    "当前：${currentFontLabel ?: preferences.fontLabel()}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onChooseFont) { Text("打开字体预览与选择") }
                if (sourceFontRequired) {
                    Text(
                        "当前章节使用来源字体以保证文字编码正确；字体选择将在不需要专用字体的章节生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "系统字体可直接使用；下载并安装的字体会在这里单独显示。具体字形会随设备系统版本变化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("字号 ${preferences.fontSize.toInt()}")
                Slider(
                    value = preferences.fontSize,
                    onValueChange = { onChange(preferences.copy(fontSize = it)) },
                    valueRange = 14f..32f,
                    steps = 17,
                    colors = readerSliderColors(),
                )
                Text("行高 ${"%.1f".format(preferences.lineHeight)}")
                Slider(
                    value = preferences.lineHeight,
                    onValueChange = { onChange(preferences.copy(lineHeight = it)) },
                    valueRange = 1.2f..2.2f,
                    steps = 9,
                    colors = readerSliderColors(),
                )
                Text("页边距 ${preferences.horizontalPadding}")
                Slider(
                    value = preferences.horizontalPadding.toFloat(),
                    onValueChange = { onChange(preferences.copy(horizontalPadding = it.toInt())) },
                    valueRange = 12f..40f,
                    steps = 13,
                    colors = readerSliderColors(),
                )
            }
        },
    )
}

@Composable
private fun ReaderOptionChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        leadingIcon = if (selected) {
            { Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ReaderSettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun readerSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

@Composable
internal fun ImmersiveReaderEffect(darkBackground: Boolean, barColor: Color) {
    val view = LocalView.current
    DisposableEffect(view, darkBackground, barColor) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousStatusBarColor = window?.statusBarColor
        val previousNavigationBarColor = window?.navigationBarColor
        window?.statusBarColor = barColor.toArgb()
        window?.navigationBarColor = barColor.toArgb()
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Configure system bars once for the reader surface. Toggling the
        // in-app controls must not reconfigure Insets or trigger a window
        // relayout; the title/progress controls are Compose overlays.
        // Keeping the status bar visible protects display cutouts/camera areas.
        controller?.show(WindowInsetsCompat.Type.statusBars())
        controller?.hide(WindowInsetsCompat.Type.navigationBars())
        controller?.isAppearanceLightStatusBars = !darkBackground
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            previousLightStatusBars?.let { controller?.isAppearanceLightStatusBars = it }
            previousStatusBarColor?.let { window?.statusBarColor = it }
            previousNavigationBarColor?.let { window?.navigationBarColor = it }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class ReaderTurnDirection { PREVIOUS, NEXT }

private data class ReaderTurnRequest(val direction: ReaderTurnDirection, val token: Int)

private data class ReaderColors(val background: Color, val text: Color)

@Composable
private fun ReaderPreferences.readerColors(): ReaderColors = when (theme) {
    ReaderTheme.WHITE -> ReaderColors(Color(0xFFFFFBFF), Color(0xFF211A1C))
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF7EED9), Color(0xFF3A3025))
    ReaderTheme.GREEN -> ReaderColors(Color(0xFFDDEBDD), Color(0xFF233128))
    ReaderTheme.DARK -> ReaderColors(Color(0xFF171416), Color(0xFFE8E0E2))
}

private fun ReaderPreferences.paragraphStyle(color: Color, customFontFamily: FontFamily? = null) = TextStyle(
    color = color,
    fontFamily = customFontFamily ?: font.fontFamily(),
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineHeight).sp,
)

private fun ReaderPreferences.headingStyle(color: Color, customFontFamily: FontFamily? = null) = TextStyle(
    color = color,
    fontFamily = customFontFamily ?: font.fontFamily(),
    fontSize = (fontSize + 5).sp,
    lineHeight = ((fontSize + 5) * lineHeight).sp,
)

private fun ReaderImageScale.contentScale(): ContentScale = when (this) {
    ReaderImageScale.FIT -> ContentScale.Fit
    ReaderImageScale.FILL -> ContentScale.FillBounds
    ReaderImageScale.FIT_WIDTH -> ContentScale.FillWidth
    ReaderImageScale.FIT_HEIGHT -> ContentScale.FillHeight
    ReaderImageScale.ORIGINAL -> ContentScale.Inside
    ReaderImageScale.SMART -> ContentScale.Crop
}
