package io.github.jiangyuyi.lightnovel.feature.local

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.local.LocalBookDocument
import io.github.jiangyuyi.lightnovel.core.local.LocalBookRecord
import io.github.jiangyuyi.lightnovel.core.local.LocalChapterContent
import io.github.jiangyuyi.lightnovel.core.local.LocalContentBlock
import io.github.jiangyuyi.lightnovel.core.local.LocalLibraryStore
import io.github.jiangyuyi.lightnovel.core.local.chapterContent
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.feature.reader.ImmersiveReaderEffect
import io.github.jiangyuyi.lightnovel.feature.reader.ReaderSettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalReaderScreen(
    store: LocalLibraryStore,
    record: LocalBookRecord,
    preferencesStore: ReaderPreferencesAccess,
    onBack: () -> Unit,
) {
    val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(initialValue = ReaderPreferences())
    var document by remember(record.id) { mutableStateOf<LocalBookDocument?>(null) }
    var chapterIndex by remember(record.id) { mutableIntStateOf(0) }
    var content by remember(record.id) { mutableStateOf<LocalChapterContent?>(null) }
    var error by remember(record.id) { mutableStateOf<String?>(null) }
    var controlsVisible by remember(record.id) { mutableStateOf(false) }
    var settingsVisible by remember(record.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(record.id) {
        chapterIndex = store.readProgress(record.id)
        val loaded = withContext(Dispatchers.IO) { store.read(record) }
        document = loaded
        loaded?.let { chapterIndex = chapterIndex.coerceIn(0, it.chapters.lastIndex) }
        error = if (loaded == null) "本地文件无法读取，可能已被移动或删除" else null
    }
    LaunchedEffect(document, chapterIndex) {
        val loaded = document ?: return@LaunchedEffect
        store.saveProgress(record.id, chapterIndex)
        val chapter = loaded.chapters.getOrNull(chapterIndex) ?: return@LaunchedEffect
        content = null
        content = withContext(Dispatchers.IO) { runCatching { loaded.chapterContent(chapter) }.getOrNull() }
    }

    val colors = localReaderColors(preferences.theme)
    ImmersiveReaderEffect(
        darkBackground = preferences.theme == ReaderTheme.DARK,
        controlsVisible = controlsVisible || settingsVisible,
    )
    Box(Modifier.fillMaxSize().background(colors.background)) {
        when {
            document == null && error == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            else -> {
                val loaded = requireNotNull(document)
                content?.let { chapter ->
                    if (preferences.mode == ReaderMode.PAGED) {
                        LocalPagedReader(
                            chapter = chapter,
                            preferences = preferences,
                            colors = colors,
                            onPreviousChapter = { if (chapterIndex > 0) chapterIndex-- },
                            hasNextChapter = chapterIndex < loaded.chapters.lastIndex,
                            onNextChapter = { chapterIndex++ },
                            onToggleControls = { controlsVisible = !controlsVisible },
                            controlsVisible = controlsVisible,
                        )
                    } else LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(top = if (controlsVisible) 64.dp else 0.dp)
                            .pointerInput(chapterIndex, loaded.chapters.size) {
                                detectTapGestures { position ->
                                    val horizontal = position.x / size.width.toFloat().coerceAtLeast(1f)
                                    val vertical = position.y / size.height.toFloat().coerceAtLeast(1f)
                                    if (horizontal in 0.30f..0.70f && vertical in 0.25f..0.75f) {
                                        controlsVisible = !controlsVisible
                                    }
                                }
                            },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = preferences.horizontalPadding.dp,
                            end = preferences.horizontalPadding.dp,
                            top = 12.dp,
                            bottom = 40.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy((preferences.lineHeight * 3).dp),
                    ) {
                        item { Text(chapter.chapter.title, style = localHeadingStyle(preferences, colors.text)) }
                        items(chapter.blocks) { block ->
                            when (block) {
                                is LocalContentBlock.Paragraph -> Text(
                                    block.text,
                                    style = localBodyStyle(preferences, colors.text),
                                )
                                is LocalContentBlock.Image -> LocalImageBlock(block.bytes, Modifier.fillMaxWidth())
                            }
                        }
                        if (chapterIndex < loaded.chapters.lastIndex) {
                            item { LocalChapterEnd { chapterIndex++ } }
                        }
                    }
                } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        if (controlsVisible && document != null && error == null) {
            TopAppBar(
                title = { Text(record.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.text)
                    }
                },
                actions = {
                    IconButton(onClick = { settingsVisible = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "阅读设置", tint = colors.text)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.text,
                ),
            )
        }
    }
    if (settingsVisible) {
        ReaderSettingsDialog(
            preferences = preferences,
            onChange = { updated -> coroutineScope.launch { preferencesStore.update(updated) } },
            onDismiss = { settingsVisible = false },
        )
    }
}

@Composable
private fun LocalPagedReader(
    chapter: LocalChapterContent,
    preferences: ReaderPreferences,
    colors: LocalReaderColors,
    onPreviousChapter: () -> Unit,
    hasNextChapter: Boolean,
    onNextChapter: () -> Unit,
    onToggleControls: () -> Unit,
    controlsVisible: Boolean,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val estimatedChars = remember(maxWidth, maxHeight, preferences.fontSize, preferences.lineHeight) {
            val charsPerLine = (maxWidth.value - preferences.horizontalPadding * 2) /
                (preferences.fontSize * 0.95f)
            val linesPerPage = (maxHeight.value - 54f) /
                (preferences.fontSize * preferences.lineHeight * 1.15f)
            (charsPerLine * linesPerPage * 0.82f).toInt().coerceIn(240, 900)
        }
        val pages = remember(chapter.chapter.id, chapter.blocks, estimatedChars) {
            localPages(chapter.blocks, estimatedChars)
        }
        val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }
        val pagerScope = rememberCoroutineScope()
        LaunchedEffect(chapter.chapter.id, pages.size) {
            pagerState.scrollToPage(0)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
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
                modifier = Modifier.fillMaxSize().padding(
                    start = preferences.horizontalPadding.dp,
                    end = preferences.horizontalPadding.dp,
                    top = 12.dp + if (controlsVisible) 64.dp else 0.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = if (pages[pageIndex].size == 1 &&
                    pages[pageIndex].firstOrNull() is LocalContentBlock.Image
                ) Arrangement.Center else Arrangement.spacedBy((preferences.lineHeight * 3).dp),
            ) {
                if (pageIndex == 0) Text(chapter.chapter.title, style = localHeadingStyle(preferences, colors.text))
                pages[pageIndex].forEach { block -> LocalBlockView(block, preferences, colors) }
            }
        }
    }
}

@Composable
private fun LocalBlockView(block: LocalContentBlock, preferences: ReaderPreferences, colors: LocalReaderColors) {
    when (block) {
        is LocalContentBlock.Paragraph -> Text(block.text, style = localBodyStyle(preferences, colors.text))
        is LocalContentBlock.Image -> {
            LocalImageBlock(block.bytes, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LocalChapterEnd(onNextChapter: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onNextChapter,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
    ) { Text("下一章") }
}

private data class LocalImageLoad(val image: ImageBitmap? = null, val finished: Boolean = false)

@Composable
private fun LocalImageBlock(bytes: ByteArray, modifier: Modifier) {
    var result by remember(bytes) { mutableStateOf(LocalImageLoad()) }
    var zoomed by remember(bytes) { mutableStateOf(false) }
    LaunchedEffect(bytes) {
        result = withContext(Dispatchers.Default) {
            LocalImageLoad(
                image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap(),
                finished = true,
            )
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        result.image?.let { image ->
            Image(image, "正文插图", modifier.clickable { zoomed = true }, contentScale = ContentScale.Fit)
        } ?: if (!result.finished) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        } else {
            Text("图片无法读取", color = MaterialTheme.colorScheme.error)
        }
    }
    if (zoomed && result.image != null) {
        Dialog(
            onDismissRequest = { zoomed = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { zoomed = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(result.image!!, "放大插图", Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
            }
        }
    }
}

private fun localPages(blocks: List<LocalContentBlock>, maxChars: Int = 900): List<List<LocalContentBlock>> {
    val pages = mutableListOf<MutableList<LocalContentBlock>>()
    var current = mutableListOf<LocalContentBlock>()
    var chars = 0
    fun finish() {
        if (current.isNotEmpty()) pages += current
        current = mutableListOf()
        chars = 0
    }
    blocks.forEach { block ->
        if (block is LocalContentBlock.Image) {
            if (current.isNotEmpty()) finish()
            pages += mutableListOf(block)
        } else {
            val text = (block as LocalContentBlock.Paragraph).text
            text.chunked(maxChars).forEach { part ->
                if (chars + part.length > maxChars && current.isNotEmpty()) finish()
                current += LocalContentBlock.Paragraph(part)
                chars += part.length
            }
        }
    }
    finish()
    return pages.ifEmpty { listOf(emptyList()) }
}

private data class LocalReaderColors(val background: Color, val text: Color)

private fun localReaderColors(theme: ReaderTheme): LocalReaderColors = when (theme) {
    ReaderTheme.WHITE -> LocalReaderColors(Color(0xFFFFFBFE), Color(0xFF211A1D))
    ReaderTheme.SEPIA -> LocalReaderColors(Color(0xFFF7F0E2), Color(0xFF43372B))
    ReaderTheme.GREEN -> LocalReaderColors(Color(0xFFEAF3E8), Color(0xFF263829))
    ReaderTheme.DARK -> LocalReaderColors(Color(0xFF161414), Color(0xFFECE0E2))
}

private fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
}

private fun localBodyStyle(preferences: ReaderPreferences, color: Color) = TextStyle(
    color = color,
    fontFamily = preferences.font.family(),
    fontSize = preferences.fontSize.sp,
    lineHeight = (preferences.fontSize * preferences.lineHeight).sp,
)

private fun localHeadingStyle(preferences: ReaderPreferences, color: Color) = TextStyle(
    color = color,
    fontFamily = preferences.font.family(),
    fontSize = (preferences.fontSize + 5).sp,
    lineHeight = ((preferences.fontSize + 5) * preferences.lineHeight).sp,
)
