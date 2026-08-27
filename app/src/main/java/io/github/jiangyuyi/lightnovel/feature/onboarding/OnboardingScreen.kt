package io.github.jiangyuyi.lightnovel.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderMode
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences
import io.github.jiangyuyi.lightnovel.core.model.ReaderTheme
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferences
import io.github.jiangyuyi.lightnovel.core.preferences.AppScale
import io.github.jiangyuyi.lightnovel.core.preferences.AppThemeMode

private const val STEP_COUNT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    appPreferences: AppPreferences,
    readerPreferences: ReaderPreferences,
    onAppPreferencesChange: (AppPreferences) -> Unit,
    onReaderPreferencesChange: (ReaderPreferences) -> Unit,
    onComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("开始设置") })
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onComplete) { Text("跳过") }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (step > 0) {
                        TextButton(onClick = { step-- }) { Text("上一步") }
                    }
                    Button(
                        onClick = {
                            if (step == STEP_COUNT - 1) onComplete() else step++
                        },
                    ) {
                        Text(if (step == STEP_COUNT - 1) "开始使用" else "下一步")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { (step + 1) / STEP_COUNT.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "第 ${step + 1} 步，共 $STEP_COUNT 步",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (step) {
                        0 -> AppearanceStep(
                            preferences = appPreferences,
                            onChange = onAppPreferencesChange,
                        )
                        1 -> ReaderStep(
                            preferences = readerPreferences,
                            onChange = onReaderPreferencesChange,
                        )
                        else -> ReadyStep()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceStep(
    preferences: AppPreferences,
    onChange: (AppPreferences) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StepHeading(
            title = "先调整外观",
            subtitle = "这些选项之后都可以在设置中修改。",
        )
        ChoiceSection(
            title = "主题",
            values = AppThemeMode.entries,
            selected = preferences.themeMode,
            label = { it.label },
            onSelected = { onChange(preferences.copy(themeMode = it)) },
        )
        ChoiceSection(
            title = "界面字号",
            values = AppScale.entries,
            selected = preferences.uiScale,
            label = { it.label },
            onSelected = { onChange(preferences.copy(uiScale = it)) },
        )
        ChoiceSection(
            title = "图标大小",
            values = AppScale.entries,
            selected = preferences.iconScale,
            label = { it.label },
            onSelected = { onChange(preferences.copy(iconScale = it)) },
        )
    }
}

@Composable
private fun ReaderStep(
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StepHeading(
            title = "设置阅读器",
            subtitle = "选择你更习惯的字体、翻页方式和背景。",
        )
        ChoiceSection(
            title = "字体",
            values = ReaderFont.entries,
            selected = preferences.font,
            label = { it.label },
            onSelected = { onChange(preferences.copy(font = it)) },
        )
        ChoiceSection(
            title = "阅读方式",
            values = ReaderMode.entries,
            selected = preferences.mode,
            label = { it.label },
            onSelected = { onChange(preferences.copy(mode = it)) },
        )
        ChoiceSection(
            title = "阅读背景",
            values = ReaderTheme.entries,
            selected = preferences.theme,
            label = { it.label },
            onSelected = { onChange(preferences.copy(theme = it)) },
        )
    }
}

@Composable
private fun ReadyStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeading(
            title = "准备好了",
            subtitle = "轻小说聚合器会把两个在线来源和本地书库放在一个阅读体验里。",
        )
        ReadyItem("发现", "在两个来源中分别浏览榜单和新书。")
        ReadyItem("账号", "在“我的”中分别登录轻之国度和轻书架。")
        ReadyItem("本地", "在“本地”中导入 EPUB、TXT、HTML 或 FB2 文件。")
        Text(
            "首次加载在线内容需要网络；进入书架或本地阅读后，已保存的内容可以离线使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChoiceSection(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
}

@Composable
private fun ReadyItem(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
