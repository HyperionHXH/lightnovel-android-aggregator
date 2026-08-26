package io.github.jiangyuyi.lightnovel.feature.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibraryAccess
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferences
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferencesAccess
import io.github.jiangyuyi.lightnovel.core.preferences.AppScale
import io.github.jiangyuyi.lightnovel.core.preferences.AppThemeMode
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesAccess
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
) {
    val wifiOnly by offlineLibrary.wifiOnly.collectAsStateWithLifecycle()
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
        backgroundUpdatesEnabled = backgroundUpdatesEnabled,
        readerPreferences = reader,
        appPreferences = app,
        onWifiOnlyChange = offlineLibrary::setWifiOnly,
        onBackgroundUpdatesChange = onBackgroundUpdatesChange,
        onReaderPreferencesChange = { value -> scope.launch { readerPreferences.update(value) } },
        onAppPreferencesChange = { value -> scope.launch { appPreferences.update(value) } },
        onBack = onBack,
        onRestartOnboarding = onRestartOnboarding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    wifiOnly: Boolean,
    backgroundUpdatesEnabled: Boolean,
    readerPreferences: ReaderPreferences = ReaderPreferences(),
    appPreferences: AppPreferences = AppPreferences(),
    onWifiOnlyChange: (Boolean) -> Unit,
    onBackgroundUpdatesChange: (Boolean) -> Unit,
    onReaderPreferencesChange: (ReaderPreferences) -> Unit = {},
    onAppPreferencesChange: (AppPreferences) -> Unit = {},
    onBack: () -> Unit,
    onRestartOnboarding: () -> Unit = {},
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            ReaderSettingsSection(
                preferences = readerPreferences,
                onChange = onReaderPreferencesChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            DownloadSettingsSection(
                wifiOnly = wifiOnly,
                onWifiOnlyChange = onWifiOnlyChange,
                backgroundUpdatesEnabled = backgroundUpdatesEnabled,
                onBackgroundUpdatesChange = onBackgroundUpdatesChange,
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
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("外观", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
                Text("界面字号", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = AppScale.entries,
                    selected = preferences.uiScale,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(uiScale = it)) },
                )
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
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("阅读", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("字体", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderFont.entries,
                    selected = preferences.font,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(font = it)) },
                )
                Text("字号 ${preferences.fontSize.toInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = preferences.fontSize,
                    onValueChange = { onChange(preferences.copy(fontSize = it)) },
                    valueRange = 14f..32f,
                    steps = 17,
                )
                Text("行高 ${"%.1f".format(preferences.lineHeight)}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = preferences.lineHeight,
                    onValueChange = { onChange(preferences.copy(lineHeight = it)) },
                    valueRange = 1.2f..2.2f,
                    steps = 9,
                )
                Text("页边距 ${preferences.horizontalPadding} dp", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = preferences.horizontalPadding.toFloat(),
                    onValueChange = { onChange(preferences.copy(horizontalPadding = it.toInt())) },
                    valueRange = 12f..40f,
                    steps = 13,
                )
                Text("阅读方式", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderMode.entries,
                    selected = preferences.mode,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(mode = it)) },
                )
                Text("阅读背景", fontWeight = FontWeight.SemiBold)
                ChipRow(
                    values = ReaderTheme.entries,
                    selected = preferences.theme,
                    label = { it.label },
                    onSelected = { onChange(preferences.copy(theme = it)) },
                )
            }
        }
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
    onWifiOnlyChange: (Boolean) -> Unit,
    backgroundUpdatesEnabled: Boolean,
    onBackgroundUpdatesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("下载设置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
