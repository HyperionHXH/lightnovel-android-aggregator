package io.github.jiangyuyi.lightnovel.feature.profile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.model.ReaderImageScale
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderOrientation
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapZone
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferences
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.preferences.AppScale
import io.github.jiangyuyi.lightnovel.core.preferences.AppThemeMode
import io.github.jiangyuyi.lightnovel.R
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.reader.fontLabel
import io.github.jiangyuyi.lightnovel.core.updates.UpdateNotificationSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    offlineLibrary: OfflineLibraryAccess,
    updateNotifications: UpdateNotificationSettings,
    readerPreferences: ReaderPreferencesAccess,
    appPreferences: AppPreferencesAccess,
    onBack: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onFontSelection: () -> Unit = {},
) {
    val wifiOnly by offlineLibrary.wifiOnly.collectAsStateWithLifecycle()
    val downloadDirectory by offlineLibrary.downloadDirectory.collectAsStateWithLifecycle()
    val backgroundUpdatesEnabled by updateNotifications.enabled.collectAsStateWithLifecycle(initialValue = false)
    val reader by readerPreferences.preferences.collectAsStateWithLifecycle(initialValue = ReaderPreferences())
    val app by appPreferences.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        updateNotifications.setEnabled(granted)
    }
    val downloadDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        offlineLibrary.setDownloadDirectory(uri.toString())
    }
    val onBackgroundUpdatesChange: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            updateNotifications.setEnabled(false)
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            updateNotifications.setEnabled(true)
        }
    }

    SettingsScreenContent(
        wifiOnly = wifiOnly,
        downloadDirectoryLabel = downloadDirectory?.let { downloadDirectoryName(context, it) }
            ?: "应用专用目录",
        backgroundUpdatesEnabled = backgroundUpdatesEnabled,
        readerPreferences = reader,
        appPreferences = app,
        onWifiOnlyChange = offlineLibrary::setWifiOnly,
        onChooseDownloadDirectory = { downloadDirectoryLauncher.launch(null) },
        onResetDownloadDirectory = { offlineLibrary.setDownloadDirectory(null) },
        onBackgroundUpdatesChange = onBackgroundUpdatesChange,
        onResetDownloadSettings = {
            offlineLibrary.setWifiOnly(true)
            offlineLibrary.setDownloadDirectory(null)
            updateNotifications.setEnabled(false)
        },
        onReaderPreferencesChange = { value -> scope.launch { readerPreferences.update(value) } },
        onAppPreferencesChange = { value -> scope.launch { appPreferences.update(value) } },
        onBack = onBack,
        onRestartOnboarding = onRestartOnboarding,
        onFontSelection = onFontSelection,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    wifiOnly: Boolean,
    downloadDirectoryLabel: String = "应用专用目录",
    backgroundUpdatesEnabled: Boolean,
    readerPreferences: ReaderPreferences = ReaderPreferences(),
    appPreferences: AppPreferences = AppPreferences(),
    onWifiOnlyChange: (Boolean) -> Unit,
    onChooseDownloadDirectory: () -> Unit = {},
    onResetDownloadDirectory: () -> Unit = {},
    onBackgroundUpdatesChange: (Boolean) -> Unit,
    onResetDownloadSettings: () -> Unit = {},
    onReaderPreferencesChange: (ReaderPreferences) -> Unit = {},
    onAppPreferencesChange: (AppPreferences) -> Unit = {},
    onBack: () -> Unit,
    onRestartOnboarding: () -> Unit = {},
    onFontSelection: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
        item {
            AppearanceSettingsSection(
                preferences = appPreferences,
                onChange = onAppPreferencesChange,
                onReset = { onAppPreferencesChange(AppPreferences()) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            ReaderSettingsSection(
                preferences = readerPreferences,
                onChange = onReaderPreferencesChange,
                onFontSelection = onFontSelection,
                onReset = { onReaderPreferencesChange(ReaderPreferences()) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            DownloadSettingsSection(
                wifiOnly = wifiOnly,
                downloadDirectoryLabel = downloadDirectoryLabel,
                onWifiOnlyChange = onWifiOnlyChange,
                onChooseDownloadDirectory = onChooseDownloadDirectory,
                onResetDownloadDirectory = onResetDownloadDirectory,
                backgroundUpdatesEnabled = backgroundUpdatesEnabled,
                onBackgroundUpdatesChange = onBackgroundUpdatesChange,
                onReset = onResetDownloadSettings,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            OtherSettingsSection(
                onRestartOnboarding = onRestartOnboarding,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsSectionHeading(
    title: String,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) {
            Icon(Icons.Default.Refresh, contentDescription = "恢复默认")
            Text("恢复默认")
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    )
}

@Composable
private fun OtherSettingsSection(
    onRestartOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("其他", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("新手引导", fontWeight = FontWeight.SemiBold)
                    Text(
                        "重新查看外观、阅读器和使用入口设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRestartOnboarding) { Text("打开") }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsSection(
    preferences: AppPreferences,
    onChange: (AppPreferences) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading("外观", onReset)
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("主题", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = AppThemeMode.entries,
                    selected = preferences.themeMode,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(themeMode = it)) },
                )
                SettingsDivider()
                Text("界面字号", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = AppScale.entries,
                    selected = preferences.uiScale,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(uiScale = it)) },
                )
                SettingsDivider()
                Text("图标大小", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = AppScale.entries,
                    selected = preferences.iconScale,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(iconScale = it)) },
                )
            }
        }
    }
}

@Composable
private fun ReaderSettingsSection(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onFontSelection: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading("阅读", onReset)
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("字体", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(preferences.fontLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onFontSelection) { Text("预览与选择") }
                }
                SettingsDivider()
                Text("字号 ${preferences.fontSize.toInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = preferences.fontSize,
                    onValueChange = { onChange(preferences.copy(fontSize = it)) },
                    valueRange = 14f..32f,
                    steps = 17,
                )
                SettingsDivider()
                Text("行高 ${"%.1f".format(preferences.lineHeight)}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = preferences.lineHeight,
                    onValueChange = { onChange(preferences.copy(lineHeight = it)) },
                    valueRange = 1.2f..2.2f,
                    steps = 9,
                )
                SettingsDivider()
                Text("页边距 ${preferences.horizontalPadding} dp", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = preferences.horizontalPadding.toFloat(),
                    onValueChange = { onChange(preferences.copy(horizontalPadding = it.toInt())) },
                    valueRange = 12f..40f,
                    steps = 13,
                )
                SettingsDivider()
                Text("阅读方式", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderMode.entries,
                    selected = preferences.mode,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(mode = it)) },
                )
                SettingsDivider()
                Text("阅读背景", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderTheme.entries,
                    selected = preferences.theme,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(theme = it)) },
                )
                SettingsDivider()
                Text("点击区域", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderTapZone.entries,
                    selected = preferences.tapZone,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(tapZone = it)) },
                )
                SettingsDivider()
                Text("反转点击区域", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderTapInversion.entries,
                    selected = preferences.tapInversion,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(tapInversion = it)) },
                )
                SettingsDivider()
                Text("屏幕方向", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderOrientation.entries,
                    selected = preferences.orientation,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(orientation = it)) },
                )
                SettingsDivider()
                Text("图片缩放", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderImageScale.entries,
                    selected = preferences.imageScale,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(imageScale = it)) },
                )
                SettingsDivider()
                ReaderSwitchRow(
                    label = "音量键翻页",
                    checked = preferences.volumeKeys,
                    onCheckedChange = { onChange(preferences.copy(volumeKeys = it)) },
                )
                SettingsDivider()
                ReaderSwitchRow(
                    label = "保持屏幕常亮",
                    checked = preferences.keepScreenOn,
                    onCheckedChange = { onChange(preferences.copy(keepScreenOn = it)) },
                )
                SettingsDivider()
                ReaderSwitchRow(
                    label = "显示阅读进度条",
                    checked = preferences.showProgressBar,
                    onCheckedChange = { onChange(preferences.copy(showProgressBar = it)) },
                )
            }
        }
    }
}

@Composable
private fun ReaderSwitchRow(
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChipRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
internal fun DownloadSettingsSection(
    wifiOnly: Boolean,
    downloadDirectoryLabel: String = "应用专用目录",
    onWifiOnlyChange: (Boolean) -> Unit,
    onChooseDownloadDirectory: () -> Unit = {},
    onResetDownloadDirectory: () -> Unit = {},
    backgroundUpdatesEnabled: Boolean,
    onBackgroundUpdatesChange: (Boolean) -> Unit,
    onReset: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSectionHeading("下载", onReset)
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("下载目录", fontWeight = FontWeight.SemiBold)
                    Text(
                        downloadDirectoryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onChooseDownloadDirectory) {
                    Icon(painterResource(R.drawable.ic_folder_open), contentDescription = "选择下载目录")
                }
                if (downloadDirectoryLabel != "应用专用目录") {
                    IconButton(onClick = onResetDownloadDirectory) {
                        Icon(Icons.Default.Refresh, contentDescription = "恢复应用专用目录")
                    }
                }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("仅使用 Wi-Fi 下载", fontWeight = FontWeight.SemiBold)
                    Text(
                        "关闭后允许使用移动网络开始新的下载任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChange)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("后台更新提醒", fontWeight = FontWeight.SemiBold)
                    Text(
                        "每 6 小时检查一次在线书架，仅提醒新增章节",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = backgroundUpdatesEnabled,
                    onCheckedChange = onBackgroundUpdatesChange,
                )
            }
        }
    }
}

private fun downloadDirectoryName(context: android.content.Context, value: String): String =
    runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(value))?.name
    }.getOrNull()?.takeIf(String::isNotBlank) ?: "已选择的文件夹"
