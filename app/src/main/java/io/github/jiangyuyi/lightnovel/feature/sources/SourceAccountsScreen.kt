package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.AccountIdentifierKind
import io.github.jiangyuyi.lightnovel.core.source.RewardCenter
import io.github.jiangyuyi.lightnovel.core.source.RewardTask
import android.content.Intent
import android.net.Uri
import coil.compose.AsyncImage
import io.github.jiangyuyi.lightnovel.core.ui.RefreshableLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceAccountsScreen(
    viewModel: SourceAccountsViewModel,
    onBack: () -> Unit,
    focusSourceId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleAccounts = state.accounts.filter { focusSourceId == null || it.descriptor.id == focusSourceId }

    RefreshableLazyColumn(
        isRefreshing = visibleAccounts.any { it.checking },
        onRefresh = { viewModel.refresh(focusSourceId) },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        focusSourceId?.let { id ->
                            state.accounts.firstOrNull { it.descriptor.id == id }?.descriptor?.displayName
                                ?.let { "${it}账号" }
                        } ?: "来源账号",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
        items(visibleAccounts, key = { it.descriptor.id }) { account ->
            SourceAccountCard(
                account = account,
                onLogin = { identifier, password ->
                    viewModel.login(account.descriptor.id, identifier, password)
                },
                onLogout = { viewModel.logout(account.descriptor.id) },
                onReward = { viewModel.claimDailyReward(account.descriptor.id) },
                onRewardTask = { task -> viewModel.claimRewardTask(account.descriptor.id, task) },
                onEarnCoin = { viewModel.claimEarnCoin(account.descriptor.id) },
                onRetry = { viewModel.refresh(account.descriptor.id) },
            )
        }
    }
}

@Composable
private fun SourceAccountCard(
    account: SourceAccountItemState,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onReward: () -> Unit,
    onRewardTask: (RewardTask) -> Unit,
    onEarnCoin: () -> Unit,
    onRetry: () -> Unit,
) {
    var identifier by remember(account.descriptor.id) { mutableStateOf("") }
    var password by remember(account.descriptor.id) { mutableStateOf("") }
    var passwordVisible by remember(account.descriptor.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val identifierIsEmail = account.descriptor.accountIdentifierKind == AccountIdentifierKind.EMAIL

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                account.profile?.avatarUrl?.let { avatarUrl ->
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = "账号头像",
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        account.descriptor.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (account.session.loggedIn) {
                            account.session.displayName ?: account.session.accountId ?: "已登录"
                        } else {
                            "未登录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    account.profile?.accountId?.let { id ->
                        Text("UID $id", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (account.checking || account.profileLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            account.error?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重试")
                    }
                }
            }
            account.notice?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }

            if (account.session.loggedIn) {
                account.profile?.let { profile ->
                    val identity = listOfNotNull(
                        profile.levelLabel,
                        profile.balance?.let { "轻币 $it" },
                    ).joinToString(" · ")
                    if (identity.isNotBlank()) {
                        Text(identity, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (account.rewardLoading && account.rewardStatus == null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在读取签到状态", style = MaterialTheme.typography.bodySmall)
                    }
                }
                account.rewardCenter?.let { center ->
                    RewardCenterContent(
                        center = center,
                        actionKey = account.rewardActionKey,
                        loading = account.rewardLoading,
                        onSign = onReward,
                        onRewardTask = onRewardTask,
                        onEarnCoin = onEarnCoin,
                    )
                } ?: account.rewardStatus?.let { reward ->
                    val details = listOfNotNull(
                        reward.balance?.let { "余额 $it" },
                        reward.streakDays?.let { "连续 $it 天" },
                    ).joinToString(" · ")
                    if (details.isNotBlank()) {
                        Text(details, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = onReward,
                        enabled = !reward.claimedToday && !account.rewardLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (account.rewardLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (reward.claimedToday) "今日已签到" else "领取今日签到")
                        }
                    }
                }
                OutlinedButton(
                    onClick = onLogout,
                    enabled = !account.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (account.signingOut) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("退出该来源")
                    }
                }
            } else if (!account.checking) {
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (identifierIsEmail) "邮箱" else "用户名 / 邮箱") },
                    isError = account.error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (identifierIsEmail) KeyboardType.Email else KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("密码") },
                    isError = account.error != null,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "隐藏" else "显示")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focus.clearFocus()
                            val transientPassword = password
                            password = ""
                            onLogin(identifier, transientPassword)
                        },
                    ),
                )
                Button(
                    onClick = {
                        focus.clearFocus()
                        val transientPassword = password
                        password = ""
                        onLogin(identifier, transientPassword)
                    },
                    enabled = !account.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (account.signingIn) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("登录")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    account.descriptor.registrationUrl?.let { url ->
                        TextButton(onClick = { context.openSourcePage(url) }) {
                            Text("注册账号")
                        }
                    }
                    account.descriptor.passwordResetUrl?.let { url ->
                        TextButton(onClick = { context.openSourcePage(url) }) {
                            Text("找回 / 改密")
                        }
                    }
                    account.descriptor.websiteUrl?.let { url ->
                        TextButton(onClick = { context.openSourcePage(url) }) {
                            Text(
                                if (account.descriptor.registrationUrl == null && account.descriptor.passwordResetUrl == null) {
                                    "官网注册 / 改密"
                                } else {
                                    "网页登录"
                                },
                            )
                        }
                    }
                }
                Text(
                    "注册、找回密码和验证码由官方网页完成；完成后返回这里输入账号密码，浏览器登录不会自动导入 Mixn。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RewardCenterContent(
    center: RewardCenter,
    actionKey: String?,
    loading: Boolean,
    onSign: () -> Unit,
    onRewardTask: (RewardTask) -> Unit,
    onEarnCoin: () -> Unit,
) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(center.signTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (center.signSubtitle.isNotBlank()) {
            Text(center.signSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (center.days.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(center.days, key = { it.day }) { day ->
                    Column(
                        modifier = Modifier.width(68.dp).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("第 ${day.day} 天", style = MaterialTheme.typography.labelSmall)
                        Text("${day.rewardAmount}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            when {
                                day.claimed -> "已领"
                                day.claimable -> "今日"
                                else -> "轻币"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day.claimable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Button(
            onClick = onSign,
            enabled = center.claimable && actionKey == null && !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (actionKey == "sign") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (center.claimed) "今日已签到" else if (center.claimable) "领取今日签到" else "暂不可签到")
        }

        center.earning?.let { earning ->
            HorizontalDivider()
            Text(earning.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(earning.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (earning.totalProgress > 0) {
                LinearProgressIndicator(
                    progress = { earning.progress.toFloat() / earning.totalProgress.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    earning.progressText.ifBlank { "${earning.progress} / ${earning.totalProgress}" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onEarnCoin, enabled = earning.claimable && actionKey == null) {
                    if (actionKey == "earning") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (earning.claimed) "已领取" else "领取 ${earning.rewardAmount} 轻币")
                }
            }
        }

        val supportedTasks = center.tasks.filter { task ->
            task.available && listOf("广告", "看视频", "观看视频", "激励").none { keyword ->
                task.title.contains(keyword) || task.subtitle.contains(keyword)
            } && !task.key.contains("ad", ignoreCase = true)
        }
        if (supportedTasks.isNotEmpty()) {
            HorizontalDivider()
            Text("轻币任务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            supportedTasks.forEach { task ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(task.title, fontWeight = FontWeight.Medium)
                            if (task.subtitle.isNotBlank()) {
                                Text(task.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (task.totalProgress > 0) {
                                Text("进度 ${task.progress}/${task.totalProgress} · 奖励 ${task.rewardAmount} 轻币", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(
                            onClick = { onRewardTask(task) },
                            enabled = task.claimable && actionKey == null,
                        ) {
                            if (actionKey == "task:${task.key}") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (task.claimed) "已领取" else if (task.claimable) task.buttonText.ifBlank { "领取" } else "未完成")
                        }
                    }
                }
            }
        }
    }
}

private fun android.content.Context.openSourcePage(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { startActivity(intent) }
}
