# Mixn Windows 桌面端

这是 Mixn 的 Kotlin/JVM + Swing 桌面端预览版。它复用 Android 端的来源模型和接口边界，在 Windows 上提供宽屏窗口、侧边导航、封面列表和正文阅读流程。

当前版本：**1.17.0**

## 当前能力

- 发现页：轻之国度与轻书架分别浏览各自的榜单和更新频道；
- 聚合搜索：并行查询两个来源，支持触底分页和来源级错误提示；
- 统一书架与阅读历史：按 sourceId:remoteId 区分来源；
- 双源账号：登录、会话恢复、退出、资料和轻书架签到；
- 书籍详情：封面、简介、标签、同书版本、分卷和章节目录；
- 正文阅读：章节切换、阅读进度、字体字号、点击区域、反转点击和图片缩放；
- 离线能力：保存已读章节、失败状态和 EPUB 导出；
- 设置与缓存：本地加密会话、阅读偏好、封面缓存和离线数据。

桌面端仍是预览版。评论发布/评分、Android 通知和 WorkManager、相册保存、音量键翻页、Android 专用字体下载及部分移动端阅读交互不承诺与 Android 完全一致。轻书架或轻之国度的接口、登录态和付费权限变化也可能影响桌面端可用性。

## 运行

要求 JDK 17。直接运行：

~~~powershell
./gradlew.bat :desktopApp:run
~~~

生成可复制的运行目录：

~~~powershell
./gradlew.bat :desktopApp:installDist
desktopApp/build/install/Mixn/Mixn.bat
~~~

在 Windows 上生成不依赖系统 Java 的 app-image：

~~~powershell
./scripts/package-windows.ps1
~~~

成功后目录为：

~~~text
desktopApp/build/windows/Mixn/Mixn/Mixn.exe
~~~

脚本需要 JDK 17+ 自带的 jpackage。如果系统找不到 jpackage，脚本仍会保留 installDist 运行目录。未签名的 app-image 可能触发 Windows SmartScreen，这是打包签名问题，不代表应用无法启动；可先使用 Mixn.exe 同目录的启动器或 installDist 目录排查。

版本可通过环境变量覆盖，Release 工作流会从 Git tag 注入：

~~~powershell
$env:APP_VERSION_NAME = "1.17.0"
./gradlew.bat :desktopApp:installDist
~~~

## 测试

桌面端测试不访问真实账号或外网，使用固定响应验证来源适配器、分页、阅读交互、离线存储和运行时状态：

~~~powershell
./gradlew.bat :desktopApp:test --no-daemon "-Pkotlin.incremental=false"
~~~

Android 与桌面端一起回归：

~~~powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :desktopApp:test --no-daemon "-Pkotlin.incremental=false"
~~~

## 数据与隐私

- 桌面会话 Token 使用本地 AES-GCM 偏好存储；密码只在登录请求期间存在，不写入日志或缓存。
- 书架、阅读进度、封面和离线章节保存在当前用户的本地 Mixn 数据目录，不会上传到本项目。
- 桌面端不会扫描或导入本地 EPUB；EPUB 导出只处理 Mixn 已保存且可读取的在线章节。
- 不要把账号、Token、security_key、签名密码或付费正文提交到 GitHub。

## 来源适配器

桌面端通过独立的 DesktopSource 契约接入两站：轻之国度使用 BFF/API，轻书架使用 REST/SignalR。UI 只依赖统一的书籍、卷、章节和会话模型；来源错误会保留在对应操作中，不会用伪造数据填充另一来源。

实现入口：

- src/main/kotlin/io/github/jiangyuyi/lightnovel/desktop/DesktopKingdomSource.kt
- src/main/kotlin/io/github/jiangyuyi/lightnovel/desktop/DesktopShelfSource.kt
- src/main/kotlin/io/github/jiangyuyi/lightnovel/desktop/Main.kt

更完整的项目边界、测试记录和发布说明见根目录 [README.md](../README.md)、[CONTEXT.md](../CONTEXT.md)、[docs/AGGREGATOR_PLAN.md](../docs/AGGREGATOR_PLAN.md) 和 [CHANGELOG.md](../CHANGELOG.md)。

## 反馈

请在 [GitHub Issues](https://github.com/HyperionHXH/lightnovel-android-aggregator/issues) 中注明 Windows 版本、JDK 版本、Mixn 版本、来源和复现步骤，并附脱敏后的错误信息。桌面端问题请同时说明使用的是 installDist 还是 jpackage app-image。
