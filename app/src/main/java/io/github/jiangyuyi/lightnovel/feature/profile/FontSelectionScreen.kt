package io.github.jiangyuyi.lightnovel.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.reader.READER_FONT_PREVIEW
import io.github.jiangyuyi.lightnovel.core.reader.UserFontDefinition
import io.github.jiangyuyi.lightnovel.core.reader.UserFontRepository
import io.github.jiangyuyi.lightnovel.core.reader.fontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSelectionScreen(
    readerPreferences: ReaderPreferencesAccess,
    userFonts: UserFontRepository,
    onBack: () -> Unit,
) {
    val preferences by readerPreferences.preferences.collectAsStateWithLifecycle(initialValue = ReaderPreferences())
    val installedIds by userFonts.installed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var installedFamilies by remember { mutableStateOf<Map<String, FontFamily>>(emptyMap()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(installedIds) {
        installedFamilies = installedIds.mapNotNull { id -> userFonts.load(id)?.let { id to it } }.toMap()
    }

    fun select(font: ReaderFont) {
        scope.launch { readerPreferences.update(preferences.copy(font = font, customFontId = null)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("字体预览与选择") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("把系统字体和已下载字体放在一起比较", style = MaterialTheme.typography.titleMedium)
                Text(
                    "中文、英文字母、数字和标点会一起显示。下载字体后才会使用它的真实字形。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Text(
                "系统字体",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        items(ReaderFont.entries, key = { it.name }) { font ->
            SystemFontCard(
                font = font,
                selected = preferences.customFontId == null && preferences.font == font,
                onSelect = { select(font) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            Text(
                "可下载字体",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        items(UserFontRepository.catalog, key = UserFontDefinition::id) { definition ->
            UserFontCard(
                definition = definition,
                installed = definition.id in installedIds,
                family = installedFamilies[definition.id],
                selected = preferences.customFontId == definition.id,
                downloading = downloadingId == definition.id,
                onDownload = {
                    downloadingId = definition.id
                    error = null
                    scope.launch {
                        val result = userFonts.download(definition)
                        if (result.isSuccess) {
                            readerPreferences.update(preferences.copy(customFontId = definition.id, font = ReaderFont.DEFAULT))
                        } else {
                            error = result.exceptionOrNull()?.message ?: "字体下载失败"
                        }
                        downloadingId = null
                    }
                },
                onUse = {
                    scope.launch { readerPreferences.update(preferences.copy(customFontId = definition.id, font = ReaderFont.DEFAULT)) }
                },
                onDelete = {
                    scope.launch {
                        userFonts.delete(definition)
                        if (preferences.customFontId == definition.id) {
                            readerPreferences.update(preferences.copy(customFontId = null, font = ReaderFont.SERIF))
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        error?.let { message ->
            item {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SystemFontCard(
    font: ReaderFont,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FontPreviewCard(
        modifier = modifier,
        title = font.label,
        preview = READER_FONT_PREVIEW,
        family = font.fontFamily(),
        selected = selected,
        supporting = "Android 系统字体",
        action = {
            TextButton(onClick = onSelect) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null)
                Text(if (selected) "使用中" else "使用")
            }
        },
    )
}

@Composable
private fun UserFontCard(
    definition: UserFontDefinition,
    installed: Boolean,
    family: FontFamily?,
    selected: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FontPreviewCard(
        modifier = modifier,
        title = definition.name,
        preview = if (installed) READER_FONT_PREVIEW else definition.preview,
        family = family,
        selected = selected,
        supporting = "${definition.sizeLabel} · ${definition.license}",
        action = {
            if (downloading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (!installed) {
                TextButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("下载并预览")
                }
            } else {
                TextButton(onClick = onUse) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null)
                    Text(if (selected) "使用中" else "使用")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除字体")
                }
            }
        },
        note = if (!installed) "下载后将用真实字形显示这段中文示例" else null,
    )
}

@Composable
private fun FontPreviewCard(
    title: String,
    preview: String,
    family: FontFamily?,
    selected: Boolean,
    supporting: String,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                action()
            }
            Text(
                preview,
                style = TextStyle(
                    fontFamily = family ?: FontFamily.Default,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
