package io.github.jiangyuyi.lightnovel.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(viewModel: AuthViewModel, onBack: () -> Unit, onCompleted: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var account by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(state.completed) { if (state.completed) onCompleted() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账号") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("登录") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("注册") })
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) {
                OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("用户名或邮箱") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(onClick = { viewModel.login(account, password) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("登录")
                }
            } else {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("邮箱") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it }, Modifier.weight(1f), label = { Text("邮箱验证码") }, singleLine = true)
                    Button(onClick = { viewModel.sendCode(email) }, enabled = !state.loading, modifier = Modifier.padding(top = 8.dp)) {
                        Text("发送")
                    }
                }
                OutlinedTextField(nickname, { nickname = it }, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("密码（至少 6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = { viewModel.register(email, code, nickname, password) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("注册并登录") }
            }
            state.message?.let { Text(it) }
            state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            Text("密码和验证码只会发送到轻之国度，不会保存在设备上。")
        }
    }
}

