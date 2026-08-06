package io.github.jiangyuyi.lightnovel.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jiangyuyi.lightnovel.core.model.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(session: Session, onLogin: () -> Unit, onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("我的") })
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (session.loggedIn) session.user?.nickname ?: "已登录用户" else "游客",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(if (session.loggedIn) "书架与阅读进度会同步到轻之国度" else "登录后可同步书架与阅读记录")
                }
            }
            if (session.loggedIn) {
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
            } else {
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录 / 注册") }
            }
            Text("说明", style = MaterialTheme.typography.titleMedium)
            Text("这是使用轻之国度当前公开 Web API 的非官方客户端。接口可能随网站升级而变化。")
            Text("独立“合集”频道当前在网站标记为维护中；本客户端完整支持书籍、分卷、章节以及同书其他版本。")
            Text("评论首版为只读，避免误发内容和触发审核流程。")
        }
    }
}

