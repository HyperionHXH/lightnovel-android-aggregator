# LightNovel Android

轻之国度（`lightnovel.fun`）的非官方 Android 客户端。项目基于 2026-08-06 实测的站点 Web BFF/API 实现，使用 Kotlin、Jetpack Compose 和 Material 3。

> 本项目仅用于学习与个人使用，不隶属于轻之国度。请遵守站点规则和内容版权要求，不要批量抓取、分发或商业使用站点内容。

## 已实现

- 用户名/邮箱密码登录、邮箱验证码注册、会话恢复与退出。
- 热门、排行、新书、原创、同人、EPUB、最近更新分区。
- 搜索分类、标签筛选和书籍跳转。
- 书籍详情、同书其他版本、分卷和章节目录。
- 登录后加入/移出书架、我的书架。
- 正文阅读、上一章/下一章、目录返回、阅读进度保存。
- 无衬线/衬线/等宽字体，14–32sp 字号、行高、页边距和白色/米黄/护眼绿/深色背景。
- 书籍评论匿名只读展示；评论故障不会影响书籍详情和阅读。
- Android Keystore 加密保存 `security_key`；不保存密码和验证码。

网站的独立“合集”分区目前标记为维护中。本客户端按实际可用的数据实现“书籍 → 分卷 → 章节”三级目录，并展示 `alternate_versions`；合集页会显示维护说明，不调用猜测接口。

完整的 API 调研、合集/评论评估、架构与验收计划见 [实施计划](docs/IMPLEMENTATION_PLAN.md)。

## 构建

要求：

- JDK 17
- Android SDK 35
- 无需安装全局 Gradle

Windows：

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS/Linux：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

仓库优先使用阿里云的 Google Maven、Maven Central 和 Gradle Plugin Portal 镜像，并保留官方源回退。Gradle Wrapper 分发使用腾讯云镜像。若你的网络不能访问该镜像，可把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 改回：

```text
https://services.gradle.org/distributions/gradle-8.9-bin.zip
```

Debug APK 生成于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 当前验证结果

- `testDebugUnitTest`：4 个测试通过，0 失败。
- `lintDebug`：通过；依赖中的旧版 Navigation 自定义 Lint 检查会产生兼容性警告，不影响构建。
- `assembleDebug`：通过。
- 本机未执行真实账号自动化测试，避免保存或传输用户密码、验证码与会话令牌。登录、注册验证码和书架写操作请在 App 内由用户主动触发。

## API 与隐私

应用只使用 HTTPS：

- Web BFF：`https://www.lightnovel.fun/api/pc-proxy/`
- 评论读取：`https://api.lightnovel.fun/pc-comment-proxy/`

站点没有为本项目提供稳定 SDK，因此 API 可能变化。网络层集中处理响应信封、历史字段兼容和错误映射。Debug/Release 均不会记录密码、验证码或 `security_key`。

正文只用于当前阅读页面，不随 Git 提交，也不提供整本离线导出。游客阅读设置和位置保存在 DataStore；登录用户的书架、阅读进度和阅读设置会按站点 API 同步。

## 工程结构

```text
app/src/main/java/io/github/jiangyuyi/lightnovel/
├─ core/
│  ├─ data/          Repository
│  ├─ model/         书籍、分卷、章节、评论与阅读设置
│  ├─ network/       OkHttp、API 解包与兼容解析
│  ├─ preferences/   阅读偏好和本地进度
│  ├─ session/       Keystore 加密会话
│  └─ ui/            主题与通用组件
└─ feature/
   ├─ auth/
   ├─ book/
   ├─ bookshelf/
   ├─ discover/
   ├─ profile/
   ├─ reader/
   └─ search/
```

## 已知限制

- 站点接口可能临时返回 5xx，页面提供错误提示和重试。
- 独立合集频道维护中；当前实现以分卷和同书版本覆盖实际阅读结构。
- 评论为只读，发布、回复、点赞、图片上传和举报未实现。
- EPUB 频道可以浏览；为避免批量下载与版权风险，首版不实现整本导出。
- 未接入动态、私信、发帖和作者工作台，这些不属于阅读客户端的核心范围。

