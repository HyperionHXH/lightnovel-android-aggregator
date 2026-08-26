package io.github.jiangyuyi.lightnovel.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.jiangyuyi.lightnovel.core.model.Session
import io.github.jiangyuyi.lightnovel.core.source.BuiltInSourceIds
import io.github.jiangyuyi.lightnovel.core.source.SourceProfile
import io.github.jiangyuyi.lightnovel.core.source.SourceSession
import io.github.jiangyuyi.lightnovel.core.ui.ErrorPane
import io.github.jiangyuyi.lightnovel.core.ui.LoadingPane
import io.github.jiangyuyi.lightnovel.core.ui.LocalAppIconScale
import io.github.jiangyuyi.lightnovel.core.ui.RefreshStatus
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    session: Session,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onFollowing: () -> Unit,
    onFollowers: () -> Unit,
    onHistory: () -> Unit,
    onPublishing: () -> Unit,
    onMessages: () -> Unit,
    onSourceAccount: (String) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session.loggedIn) { viewModel.refresh(session.loggedIn) }
    val kingdomSession = state.sourceSessions[BuiltInSourceIds.LIGHT_NOVEL_KINGDOM]
    val shelfSession = state.sourceSessions[BuiltInSourceIds.LIGHT_NOVEL_SHELF]
    val anySourceLoggedIn = state.sourceSessions.values.any { it.loggedIn }

    RefreshableLazyColumn(
        isRefreshing = state.refreshing,
        onRefresh = { viewModel.refresh(session.loggedIn, true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TopAppBar(
                title = { Text("我的") },
            )
        }
        item { RefreshStatus(state.refreshing, state.refreshError) }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("账号与来源", style = MaterialTheme.typography.titleMedium)
                SourceAccountCard(
                    title = "轻之国度账号",
                    session = kingdomSession,
                    profile = state.sourceProfiles[BuiltInSourceIds.LIGHT_NOVEL_KINGDOM],
                    loading = BuiltInSourceIds.LIGHT_NOVEL_KINGDOM in state.sourceProfileLoading,
                    error = state.sourceProfileErrors[BuiltInSourceIds.LIGHT_NOVEL_KINGDOM],
                    onManage = { onSourceAccount(BuiltInSourceIds.LIGHT_NOVEL_KINGDOM) },
                    onLogout = onLogout.takeIf { kingdomSession?.loggedIn == true },
                ) {
                    val profile = state.profile
                    if (profile != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ProfileStat("关注", profile.followingCount, onFollowing)
                            ProfileStat("粉丝", profile.fansCount, onFollowers)
                            ProfileStat("发布", profile.postCount, onPublishing)
                            ProfileStat("轻币", profile.coin, null)
                        }
                        ProfileEntry(
                            "消息中心",
                            "回复、@、点赞、粉丝、系统与私信",
                            onMessages,
                            badge = state.messageSummary.unreadCount.takeIf { it > 0 },
                        )
                        ProfileEntry("关注与粉丝", "查看用户关系", onFollowing)
                        ProfileEntry("发布管理", "作品状态与审核进度", onPublishing)
                    }
                }
                SourceAccountCard(
                    title = "轻书架账号",
                    session = shelfSession,
                    profile = state.sourceProfiles[BuiltInSourceIds.LIGHT_NOVEL_SHELF],
                    loading = BuiltInSourceIds.LIGHT_NOVEL_SHELF in state.sourceProfileLoading,
                    error = state.sourceProfileErrors[BuiltInSourceIds.LIGHT_NOVEL_SHELF],
                    onManage = { onSourceAccount(BuiltInSourceIds.LIGHT_NOVEL_SHELF) },
                )

                if (state.loading && state.profile == null && session.loggedIn) LoadingPane()
                if (state.error != null && state.profile == null && session.loggedIn) {
                    ErrorPane(state.error!!, onRetry = { viewModel.refresh(session.loggedIn, true) })
                }

                Text("聚合功能", style = MaterialTheme.typography.titleMedium)
                ProfileEntry("阅读记录", "两站历史和本地阅读进度", onHistory)
                ProfileEntry("下载与导出", "查看离线书籍、重试下载和导出 EPUB", onDownloads)

                if (!anySourceLoggedIn) {
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录 / 注册") }
                }
                Text("应用设置", style = MaterialTheme.typography.titleMedium)
                ProfileEntry("设置", "阅读、外观、下载与通知", onSettings)

                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun SourceAccountCard(
    title: String,
    session: SourceSession?,
    profile: SourceProfile?,
    loading: Boolean,
    error: String?,
    onManage: () -> Unit,
    onLogout: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (profile?.avatarUrl != null) {
                    AsyncImage(
                        model = profile.avatarUrl,
                        contentDescription = "${title}头像",
                        modifier = Modifier.size(52.dp).clip(CircleShape),
                    )
                } else {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(52.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (session?.loggedIn == true) profile?.displayName ?: session.displayName ?: session.accountId ?: "已登录"
                        else "未登录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    profile?.accountId?.let { Text("账号 $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            if (session?.loggedIn == true && profile != null) {
                val details = buildList {
                    profile.balance?.let { add("轻币 $it") }
                    profile.levelLabel?.let { add(it) }
                    profile.extra["连续签到"]?.let { add("连续签到 $it") }
                    profile.extra["今日签到"]?.let { add("今日签到 $it") }
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "))
                content()
                if (profile.extra["今日签到"] == "未完成") {
                    TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("去签到") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onManage, modifier = Modifier.weight(1f)) { Text("账号管理") }
                    onLogout?.let { logout ->
                        TextButton(onClick = logout, modifier = Modifier.weight(1f)) { Text("退出登录") }
                    }
                }
            } else if (!loading) {
                Text("登录后可查看资料、轻币和该来源的专属功能", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("登录此来源") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ProfileStat(label: String, value: Int, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier.clickable(enabled = onClick != null) { onClick?.invoke() }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileEntry(title: String, subtitle: String, onClick: () -> Unit, badge: Int? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            badge?.let {
                Text(if (it > 99) "99+" else it.toString(), modifier = Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size((24 * LocalAppIconScale.current).dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
