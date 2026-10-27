<p align="center">
  <img src="docs/mixn-icon.png" width="112" alt="Mixn 图标">
</p>

# Mixn

Mixn 是面向个人账号的轻小说聚合阅读器，内置轻之国度（LK）与轻书架（LNS）两个来源。它把发现、搜索、书架、阅读进度和离线导出放在一个应用中，同时保持两站的登录态、接口协议和错误相互隔离。

当前 Android 版本：**1.22.2 / 38**（真实作者同步呈现与阅读缓存管理）。

- Android Release：[v1.22.2](https://github.com/HyperionHXH/lightnovel-android-aggregator/releases/tag/v1.22.2)
- Android APK：[Mixn-1.22.2.apk](https://github.com/HyperionHXH/lightnovel-android-aggregator/releases/download/v1.22.2/Mixn-1.22.2.apk)
- iOS 构建目标：**1.22.2 / 38**，最低 iOS/iPadOS 15.0；初次移植正在验收，是否可下载以该 Release 的实际附件为准。**只提供未签名 IPA，普通 iPhone 不能直接点开安装。** [安装、更新与平台限制](docs/IOS.md)。

> 本项目是非官方客户端，不隶属于轻之国度或轻书架。请遵守两个站点的服务条款和内容版权要求，不要批量抓取、分发或商业使用站点内容。

## 当前功能

### 发现与搜索

- 轻之国度和轻书架分别展示各自的热门、排行、新书和更新频道，不把两个站点强行混成一个榜单。
- 聚合搜索会并行查询两个来源，逐来源显示结果或错误；没有统一登录门槛，但来源接口要求登录时会单独提示，不能绕过站点认证。
- 发现和搜索支持触底分页。官网固定榜单在站点没有更多数据时会明确提示，不会重复追加相同书籍。
- 轻书架关键词使用官网的书名/作者包含匹配，支持直接输入作者姓名；不把上传者 `UserName` 当作作品作者。作者从真实详情补齐，缺少作者的卡片先显示稳定占位，作品与作者一起呈现，不等待整页；发现、搜索和书架复用短时请求缓存，真实作者缺失时不伪造，详情请求失败保留书籍与来源错误提示。

### 书籍与阅读

- 书籍详情包含简介、标签、同书版本、分卷和章节目录。
- 统一书架按 `sourceId + remoteId` 区分来源，支持全部书籍、已下载书籍和阅读历史。
- 在线章节支持分页阅读和上下滚动，可调整字体、字号、行距、边距、背景、点击区域、图片缩放、屏幕方向、屏幕常亮和进度条；Android 另支持音量键翻页，iOS 不使用私有 API 拦截系统音量键。
- 正文插图支持懒加载、协议相对地址和常见 CDN 地址；放大后长按可保存到相册，退出使用右上角关闭或系统返回。
- 付费章节按来源接口和账号权限处理。客户端不会绕过锁定、伪造余额或伪造阅读记录。

### 账号与站点功能

- 两个来源独立登录、退出和会话恢复，凭据不会跨站共享。
- 轻之国度：个人资料、轻币余额、七日签到、关注/粉丝、发布管理、消息中心和作品评论。
- 轻之国度评论支持最热/最新、分页、星级展示和发表评论；轻书架没有对应评论接口，因此不显示评论入口。
- 轻书架：个人资料、收藏同步和官方签到。
- 站点接口或账号权限不可用时，页面会保留来源、认证、超时、权限或内容不存在等具体错误。
- “我的”和来源账号页显示等级、称号、累计经验与进度。轻书架区分成长等级与访问等级，经验条使用服务器下发的本级/下级经验门槛；签到后重新读取资料、余额与经验，不在本地猜测奖励。
- LK 官网当前只有九档等级进度，应用明确标为“非经验进度”；接口确实返回 `exp` 时才显示累计经验。LK 未公布经验门槛，不能据等级推算经验百分比或承诺签到升级。

### 下载与导出

- 可下载整本或指定分卷的可读章节，锁定章节不会被当作已下载内容。
- 下载页显示已完成卷数和章节数，并提供失败重试与删除记录。
- 已下载章节可导出为 UTF-8 TXT 或 EPUB 3；EPUB 会尽量包含封面和正文插图，TXT 使用 `[插图]` 占位。
- Android 导出优先写入授权下载目录；未选择目录时使用应用专用目录 `offline_library/exports`。iOS 使用系统文件夹授权或分享面板，离线正文保存在自身沙盒，不是 Android SAF 整库迁移；详见 [iOS 数据说明](docs/IOS.md#升级与数据)。应用不会扫描导入本地 EPUB，也不会删除用户原始文件。
- “仅使用 Wi-Fi 下载”和后台更新提醒属于全局设置，后台提醒默认关闭。

### 字体与外观

- 提供系统字体和可下载字体的独立预览页，下载前后均可对比中文、标点和英文示例。
- 阅读设置与界面外观设置分开保存；各分组可单独恢复默认值。
- 主题、界面字号和图标大小可调整。轻书架章节若要求服务端专用字体，会优先使用该字体以避免正文乱码。
- 左右翻页支持相邻两页连续跟手移动、松手吸附和取消回弹；点击翻页共用动画，阅读进度及跨章导航只在翻页停稳后更新。卡片翻页和上下滚动仍是独立模式；Android 音量键翻页复用同一动画。

## 平台状态

### Android

Android 使用 Kotlin、Jetpack Compose、Material 3，最低支持 Android 8.0（API 26），目标 SDK 35，Release APK 使用项目 Android 密钥签名。本轮 iOS 移植不增加 Android 版本、不重新发布已有 APK。

### iOS / iPadOS

iOS 在同一仓库使用 SwiftUI、UIKit、WebKit 独立实现，最低系统 15.0。Windows 无法运行 Xcode，原生编译、单元测试、模拟器操作、截图和录屏在 macOS CI 执行；不是网页套壳预览或模拟器产物改名为 IPA。

**未签名 IPA 仍需用户自行使用兼容工具签名/安装。** 免费签名可能到期，需要按工具规则刷新；没有永久签名、App Store 或 TestFlight 保证。先阅读 [完整安装步骤、兼容范围、升级备份与免责声明](docs/IOS.md)，再选择 AltStore / SideStore 或已经兼容的 TrollStore 环境。

两端用同一功能清单、脱敏协议样例和联合发布门槛维护。后台下载/通知、音量键、方向和文件权限受 iOS 公开 API 限制，不承诺超越系统能力；[功能对照与例外](docs/PLATFORM_PARITY.md)。

桌面端开发已无限期搁置，不属于当前支持或发布范围；仓库中的桌面端代码不代表 Mixn 提供可用的桌面版本。

## 截图

<p align="center">
  <img src="docs/screenshots/discover.png" width="300" alt="Mixn Android 1.21.3 发现页">
</p>

## 构建与运行

### 环境要求

- JDK 17
- Android SDK 35（构建 Android 端）
- 不需要全局 Gradle，仓库自带 Gradle Wrapper

### Android

在仓库根目录执行：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon "-Pkotlin.incremental=false"
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release 签名

Android Release APK 使用 Android Keystore 签名。首次配置可运行：

```powershell
./scripts/setup-release-signing.ps1
```

签名密钥、密码、`signing.properties` 和本地账号信息均不应提交到 Git。丢失同一签名密钥后，无法覆盖安装后续版本。

推送 `v*` 标签会触发 [双端 Release 工作流](.github/workflows/release.yml)：iOS 无签名设备构建、XCTest 与原生页面巡检通过后，运行 Android 测试、Lint、签名构建和 `apksigner` 校验，两端成功才发布 APK、unsigned IPA 与 SHA-256。当前 Android 标签为 `v1.22.2`；版本号使用源码，不依赖运行次数。**本次初始 iOS 验收后只向已有版本追加 IPA，不移动标签或重发 APK。** 普通文档修改不创建 Release。

### iOS 构建与验收

使用固定 Xcode 16.4 + XcodeGen，在 Mac 生成工程并分别构建真机 Release 和模拟器测试。完整命令及产物目录见 [iOS 构建文档](docs/IOS.md#在-mac-上构建)；[云端 iOS Acceptance](.github/workflows/ios.yml)不自动发布。Release 不含 DEBUG 的测试账号/书籍夹具，且不携带 Apple 签名材料。

## 测试

默认测试使用固定响应，不访问真实账号或外网。完整本地回归命令：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon "-Pkotlin.incremental=false"
```

轻书架匿名 SignalR 冒烟测试需要显式开启，网络不可用时保持跳过：

```powershell
$env:RUN_LNS_SMOKE = "true"
./gradlew.bat :app:testDebugUnitTest --tests "io.github.jiangyuyi.lightnovel.source.lightnovelshelf.LightNovelShelfLiveSmokeTest"
```

最近的 Android 真机/AVD 验证覆盖发现、双源搜索、登录态恢复、书架、详情/目录、分页与滚动阅读、正文图片、字体设置、缓存恢复、下载和 EPUB/TXT 导出。真实账号相关功能仍应由使用者自行确认，因为站点接口、登录态和付费权限会变化。

`1.21.4` 修复详情被目录/书架请求阻塞：详情、目录与书架状态分别更新，刷新保留已有内容。LK 网络层按域名在当前进程记住成功线路，避免持续重复等待同一坏线路；采用顺序失败回退，不会并发多路竞速，也不保证选择绝对最快的线路。JSON 单线路 4 秒截止允许在页面 10 秒总截止内尝试备用线路，图片/上传保留 12 秒。页面取消不会误触发线路回退。LNS SignalR 也保留外部取消，不把已取消的请求重放到备用端点。本地网络回归只访问回环服务器，Debug 构建仅对 `127.0.0.1` 放行 HTTP，Release 仍只允许 HTTPS。

排查发现原 AVD 的系统 DNS 线路多次等待 12 秒后才能通过备用线路成功，而独立 AVD 和官网正常请求可在数秒内完成；这不构成服务端高负荷的证据。HTTP 418、认证失败和站点真实错误不会被线路偏好逻辑掩盖。LNS 登录后正文/专用字体本轮未重新验收，需有效账号登录态。

等级与经验协议依据：[轻书架官方 Growth 字段](https://github.com/LightNovelShelf/Web/blob/master/src/services/points/type.ts)、[官方经验条计算](https://github.com/LightNovelShelf/Web/blob/master/src/components/app/Header.vue)；LK 依据当前[官网个人页](https://www.lightnovel.fun/me)的九档等级展示。缺少经验或门槛时显示未提供，不显示虚构的零值或经验条。签到成功后资料刷新失败会保留旧数据并提示重试；从账号管理返回“我的”会重新读取资料。

## 隐私、数据和已知限制

- 应用只请求完成对应功能所需的网络、通知和相册写入权限，不申请全盘存储权限。
- 密码、验证码和 `security_key` 不写入正文缓存、日志或崩溃报告；会话分别使用 Android Keystore / iOS Keychain 保护。退出登录清理对应账号的私有缓存，不跨站退出。
- 正文、封面和插图缓存只保留在当前设备，不会随 Git 或项目发布；导出文件由用户选择的目录保存。
- “我的 → 数据与统计”显示在线阅读缓存与自动限额，并可安全清理。正文/列表为 96 MiB 实际磁盘、8 MiB 内存上限；临时章节字体上限 64 MiB；Android 图片缓存仅使用 32 MiB 内存，iOS 原生图片/WebKit 内存另由系统管理。下载正文与专用字体、导出、自选字体、账号、阅读进度不属于临时缓存，不随此入口删除。
- LK 的“浏览赚轻币”依赖官方客户端计时/心跳接口，当前没有可确认的公开合法接口，因此 Mixn 不伪造阅读时长，也不接入广告任务。
- 轻书架正文可能依赖站点下发的专用字体；字体或接口变化时会显示来源错误，而不是展示混淆文本。
- 网站接口、SignalR 协议、CDN 和榜单数据可能随时变化。来源适配器和错误处理集中在对应模块，遇到接口变更请优先提交可复现信息。
- 项目不包含本地 EPUB/TXT 导入或本地书库阅读功能；离线能力仅针对 Mixn 下载的在线章节及其导出。

## 工程结构

```text
app/src/main/java/io/github/jiangyuyi/lightnovel/
├─ core/       统一模型、来源契约、网络、缓存、会话、离线和阅读设置
├─ feature/    发现、搜索、书架、详情、阅读、账号、消息、字体和设置页面
└─ source/     轻之国度与轻书架的内置来源适配器
docs/          聚合方案、ADR、测试/交接记录和截图
```

iOS 对应目录：`ios/Mixn/Core`（协议/账号/设置）、`Sources`（双源）、`Storage`（缓存/下载/导出/字体）、`Reader`、`Views`；共享来源样例位于 `contracts/`，原生验收和打包工具位于 `scripts/ios/`。

## 贡献与反馈

请在 [GitHub Issues](https://github.com/HyperionHXH/lightnovel-android-aggregator/issues) 提交问题，附上：

- Android/iOS 系统版本、机型、Mixn 版本；iOS 另附安装工具及是否签名到期（不要提供账号或证书）；
- 来源（轻之国度/轻书架）、页面和复现步骤；
- 是否登录、是否使用缓存或离线模式；
- 脱敏后的错误文案、截图或测试响应。

不要在 Issue、日志或提交中粘贴密码、Token、`security_key`、签名密码或付费内容。

项目状态、架构决策和后续交接记录：

- [`CONTEXT.md`](CONTEXT.md)
- [`双源聚合开发大纲`](docs/AGGREGATOR_PLAN.md)
- [`来源适配器 ADR`](docs/adr/0001-built-in-source-adapters.md)
- [`原生 iOS 与联合开发 ADR`](docs/adr/0002-native-ios-and-joint-release.md)
- [`iOS 安装、限制与免责声明`](docs/IOS.md)
- [`双端功能与维护约束`](docs/PLATFORM_PARITY.md)
- [`AI 交接记录`](docs/AI_HANDOFF.md)
- [`CHANGELOG.md`](CHANGELOG.md)

## 许可证

客户端源代码使用 [MIT License](LICENSE)。站点内容、书籍正文、插图和轻之国度/轻书架相关商标不因本许可证改变其原有权利归属。

## 免责声明

Mixn 为独立非官方客户端，与来源网站、Apple 或安装工具没有隶属、合作或担保关系。不托管小说，不授予小说/插图/封面/字体版权，不提供破解付费章节或规避认证。请仅访问与导出有权使用的内容，遵守适用规则与来源服务条款，不分发或商业利用站点内容。

接口、网络、签名过期/吊销、第三方工具、系统限制及用户操作可能导致无法启动、功能中断或数据丢失；升级前将重要导出保存在沙盒外，不要先卸载。项目按 MIT 的“现状”提供，不保证永久签名、所有设备兼容或持续可用；不排除适用规则中不可排除的权利。iOS 的具体边界与使用方法见 [完整说明](docs/IOS.md)。
