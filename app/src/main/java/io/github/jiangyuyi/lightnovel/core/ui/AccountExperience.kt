package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.source.SourceProfile

@Composable
fun AccountExperience(profile: SourceProfile, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val level = listOfNotNull(
            profile.level?.let { "Lv.$it" },
            profile.levelLabel?.takeIf { it.isNotBlank() && it != "Lv.${profile.level}" },
        ).joinToString(" · ")
        if (level.isNotEmpty()) {
            Text(level, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        val experience = profile.experience
        profile.maximumLevel?.takeIf { it > 1 }?.let { maximum ->
            profile.level?.takeIf { it in 1..maximum }?.let { current ->
                val progress = (current - 1).toFloat() / (maximum - 1)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "等级进度 $current / $maximum（非经验进度）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (experience == null) {
            Text(
                "来源暂未提供经验数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("累计经验 ${experience.total}", style = MaterialTheme.typography.bodyMedium)
            experience.fraction?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            val progressLabel = when {
                experience.maxLevel -> "已达最高等级"
                experience.earnedInLevel != null && experience.requiredInLevel != null ->
                    "本级经验 ${experience.earnedInLevel} / ${experience.requiredInLevel} · 距升级 ${experience.remaining}"
                else -> "来源暂未提供升级门槛"
            }
            Text(
                progressLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        profile.extra["访问等级"]?.let {
            Text("访问等级 $it", style = MaterialTheme.typography.bodySmall)
        }
        profile.extra["经验获取"]?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
