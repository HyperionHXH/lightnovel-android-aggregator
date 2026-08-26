package io.github.jiangyuyi.lightnovel.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiangyuyi.lightnovel.core.source.AccountIdentifierKind
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
    onRetry: () -> Unit,
) {
    var identifier by remember(account.descriptor.id) { mutableStateOf("") }
    var password by remember(account.descriptor.id) { mutableStateOf("") }
    val focus = LocalFocusManager.current
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
                }
                if (account.checking) {
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
                if (account.rewardLoading && account.rewardStatus == null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在读取签到状态", style = MaterialTheme.typography.bodySmall)
                    }
                }
                account.rewardStatus?.let { reward ->
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
                    visualTransformation = PasswordVisualTransformation(),
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
            }
        }
    }
}
