package io.github.jiangyuyi.lightnovel.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import coil.compose.SubcomposeAsyncImage
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit, onCatalog: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = state.preferences.readerColors()
    val safeTopPadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val chapterId = state.chapter?.chapter?.id
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

    ImmersiveReaderEffect(
        darkBackground = state.preferences.theme == ReaderTheme.DARK,
        controlsVisible = state.controlsVisible || state.settingsVisible,
    )

    LaunchedEffect(state.chapter?.chapter?.id, state.restoredParagraph) {
        anchorBlock = state.restoredParagraph.coerceAtLeast(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when {
            state.loading && state.chapter == null -> LoadingPane(Modifier.align(Alignment.Center))
            state.error != null && state.chapter == null -> ErrorPane(
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
                safeTopPadding = safeTopPadding,
                controlsVisible = state.controlsVisible,
            )
            else -> ScrollingReader(
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
                safeTopPadding = safeTopPadding,
                controlsVisible = state.controlsVisible,
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
                bookTitle = state.chapter?.bookTitle ?: "阅读",
                colors = colors,
                onBack = onBack,
                onCatalog = onCatalog,
                onSettings = { viewModel.showSettings(true) },
                onRetry = viewModel::retry,
                showRetry = state.refreshError != null,
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
    safeTopPadding: Dp,
    controlsVisible: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val paragraphStyle = preferences.paragraphStyle(colors.text)
        val headingStyle = preferences.headingStyle(colors.text)
        val horizontalPadding = preferences.horizontalPadding.dp
        val pageTopPadding = safeTopPadding + 8.dp + if (controlsVisible) 64.dp else 0.dp
        val pageBottomPadding = 12.dp
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
        val pagerState = rememberPagerState {
            pages.size.coerceAtLeast(1) + if (hasNextChapter) 1 else 0
        }
        var turnRequest by remember { mutableStateOf<ReaderTurnRequest?>(null) }
        var turnRequestToken by remember { mutableIntStateOf(0) }

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
                            is ReaderPageElement.Text -> ReaderTextElement(element, preferences, colors)
                            is ReaderPageElement.Illustration -> ReaderIllustration(
                                block = element.block,
                                modifier = Modifier.fillMaxWidth().height(with(density) { element.heightPx.toDp() }),
                                colors = colors,
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
                .pointerInput(pages.size, hasPreviousChapter, hasNextChapter) {
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
                                when (endX / size.width.toFloat().coerceAtLeast(1f)) {
                                    in 0f..0.30f -> requestTurn(ReaderTurnDirection.PREVIOUS)
                                    in 0.70f..1f -> requestTurn(ReaderTurnDirection.NEXT)
                                    else -> onToggleControls()
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
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    safeTopPadding: Dp,
    controlsVisible: Boolean,
) {
    val listState = rememberLazyListState()
    val boundaryOffset = if (hasPreviousChapter) 1 else 0
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
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = safeTopPadding + if (controlsVisible) 64.dp else 0.dp)
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
        if (blocks.isNotEmpty() && hasNextChapter) {
            item {
                ReaderChapterEnd("下一章", onNextChapter, previous = false)
            }
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
    var zoomed by remember(block.url) { mutableStateOf(false) }
    SubcomposeAsyncImage(
        model = block.url,
        contentDescription = "正文插图",
        contentScale = ContentScale.Fit,
        modifier = modifier.clickable { zoomed = true },
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
                    model = block.url,
                    contentDescription = "放大插图",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderControls(
    bookTitle: String,
    colors: ReaderColors,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    showRetry: Boolean,
) {
    TopAppBar(
        title = {
            Text(
                bookTitle,
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
            IconButton(onClick = onCatalog) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "阅读设置")
            }
            if (showRetry) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重试")
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text("字体")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderFont.entries.forEach { font ->
                        ReaderOptionChip(
                            selected = preferences.font == font,
                            onClick = { onChange(preferences.copy(font = font)) },
                            label = font.label,
                        )
                    }
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
private fun readerSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

@Composable
internal fun ImmersiveReaderEffect(darkBackground: Boolean, controlsVisible: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, darkBackground, controlsVisible) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible) {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        controller?.isAppearanceLightStatusBars = !darkBackground
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            previousLightStatusBars?.let { controller?.isAppearanceLightStatusBars = it }
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
