package io.github.jiangyuyi.lightnovel.feature.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.local.LocalBookDocument
import io.github.jiangyuyi.lightnovel.core.local.LocalBookRecord
import io.github.jiangyuyi.lightnovel.core.local.LocalChapterContent
import io.github.jiangyuyi.lightnovel.core.local.LocalLibraryStore
import io.github.jiangyuyi.lightnovel.core.local.chapterContent
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import kotlinx.coroutines.Dispatchers
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
        content = withContext(Dispatchers.IO) { runCatching { loaded.chapterContent(chapter) }.getOrNull() }
    }

    val colors = localReaderColors(preferences.theme)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopAppBar(
            title = { Text(record.title, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        when {
            document == null && error == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            else -> {
                val loaded = requireNotNull(document)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(loaded.chapters.getOrNull(chapterIndex)?.title ?: "正文", Modifier.weight(1f))
                    IconButton(
                        enabled = chapterIndex > 0,
                        onClick = { chapterIndex-- },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一章")
                    }
                    IconButton(
                        enabled = chapterIndex < loaded.chapters.lastIndex,
                        onClick = { chapterIndex++ },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一章")
                    }
                }
                content?.let { chapter ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = preferences.horizontalPadding.dp,
                            end = preferences.horizontalPadding.dp,
                            top = 12.dp,
                            bottom = 40.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy((preferences.lineHeight * 3).dp),
                    ) {
                        item { Text(chapter.chapter.title, style = localHeadingStyle(preferences, colors.text)) }
                        items(chapter.text.split(Regex("\\n{2,}"))) { paragraph ->
                            if (paragraph.isNotBlank()) Text(paragraph.trim(), style = localBodyStyle(preferences, colors.text))
                        }
                    }
                }
            }
        }
    }
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
