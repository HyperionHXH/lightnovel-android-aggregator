# Mixn 项目交接记录

更新时间：2026-09-10
仓库：`https://github.com/HyperionHXH/lightnovel-android-aggregator.git`
当前分支：`feature/multi-source-foundation`
当前分支最新提交以 `git log -1` 为准；本文件记录的最近认证与阅读交互增强均已随分支提交推送。

## 项目定位

Mixn 是面向个人账号的轻小说聚合阅读器，内置轻之国度（LK）和轻书架（LNS）两个来源。应用统一提供发现、搜索、详情、章节、在线阅读、书架、阅读历史、离线下载/阅读、EPUB 导出和来源账号入口；两个来源的认证、协议、ID 和错误必须保持隔离。

领域不变量和术语以根目录 [`CONTEXT.md`](../CONTEXT.md) 为准；架构决策见 [`docs/adr/0001-built-in-source-adapters.md`](adr/0001-built-in-source-adapters.md)。

## 代码结构

- `app/`：Android 客户端，Jetpack Compose UI、来源适配器、缓存、书架、下载和阅读器。
- `app/src/main/java/.../source/`：来源能力接口与 LK/LNS 实现。不要把站点协议泄漏到通用 UI。
- `app/src/main/java/.../feature/reader/`：统一阅读器和双源阅读页；正文解析见 `ReaderContentParser.kt`。
- `desktopApp/`：Kotlin/JVM Swing Windows 客户端。入口和构建说明见 [`desktopApp/README.md`](../desktopApp/README.md)。
- `scripts/package-windows.ps1`：Windows `installDist` + `jpackage` 打包。
- `.github/workflows/`：CI 与 tag Release 工作流。
- `docs/`：计划、质量/UI review、性能与 AI 交接资料。

## 已完成能力

- 双源独立发现，统一聚合搜索；发现页按来源展示榜单，书架为“全部/已下载”并显示来源。
- 两站独立登录、会话恢复、退出、账号资料、轻书架签到；凭据不跨来源共享。
- 详情、分卷、章节目录、在线正文、在线插图、阅读进度和章节边界导航。
- LK 章节解锁状态按账号和资源保存；价格解析兼容字符串、整数和嵌套金额。
- 在线章节离线保存、离线书架、EPUB 3 文本导出和系统文件保存入口；不再支持本地 EPUB 导入/本地阅读器。
- 发现/搜索触底分页、缓存 stale-while-revalidate、磁盘 LRU、章节正文缓存和来源更新快照。
- 阅读控制栏为 Compose 覆盖层；正文使用固定安全区，避免刘海/摄像头遮挡。展开/收起不再调用系统栏 API，也不触发整章重新解析或重新分页。
- 阅读页加载状态统一使用安全区域内的居中进度指示器；阅读菜单使用居中限宽可滚动面板，避免底部弹层和顶部偏移。
- 音量键翻页通过 `MainActivity.dispatchKeyEvent` 统一拦截；阅读页控制栏显示时仍可翻页，菜单或设置弹窗打开时恢复系统音量行为。菜单外部 scrim 可点击关闭，面板内部事件不会误关闭。
- 文字样式提供系统默认、系统黑体、系统宋体、等宽字体、手写体、紧凑黑体和圆体；细黑、中黑、特黑已移除，旧版本值会回退到衬线字体。独立字体预览页会为未安装字体展示中文示例，安装后用统一正文样例比较真实字形；可按需下载思源宋体、思源黑体、霞鹜文楷及等宽版本，下载文件固定提交并做 SHA-256 校验，已安装字体不会重复下载。
- 设置页按外观、阅读、下载和其他分组，外观/阅读/下载可分别恢复本组默认值，组内项目用分隔线保持视觉层次；聚合功能不再提供无来源归属的通用登录入口。
- 新手引导包含下载目录步骤；用户可以首次启动时选择 SAF 文件夹，也可以继续在设置页修改或恢复应用专用目录。未选择时明确显示应用专用目录，不阻断引导完成。
- Windows 客户端具备发现、搜索、书架、设置、登录、详情、章节、在线阅读、历史和 EPUB 导出等基础流程。
- 应用品牌为 `Mixn`，图标素材为 `docs/mixn-icon.png`；iOS 目标已移除。

## 最近性能修复与验证

最近提交：`694d0b5`、`e21c6d0`、`517e92e`。修复重点是阅读页控制栏卡顿：系统 Insets 只在进入阅读页或主题变化时配置，双源章节正文按章节 `remember`，解析器正则静态复用。

已在本机 AVD `noval_api35`（Android API 35）安装启动验证：可进入在线章节，正文/封面/插图显示，无 Mixn ANR 或崩溃；首次展开约 32ms，收起约 11ms，连续滚动非 legacy jank 约 4.5%。`swiftshader_indirect` 软件渲染下大图会掉帧，宿主 GPU 模式更适合性能测试。偶发 `UiAutomationService already registered` 属测试工具重复注册，不是应用崩溃。

本轮（2026-09-09）新增：阅读字体增加系统默认；阅读设置增加反转点击区域、屏幕方向和图片缩放，并持久化到 DataStore；方向切换通过 `configChanges` 保持当前阅读栈。来源账号登录支持密码显示切换、错误字段高亮和更明确的官网注册/改密文案。桌面端同步点击区域、反转和图片缩放，新增 `DesktopReaderInteractionTest`。

## 构建与测试

在仓库根目录执行：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :desktopApp:test --no-daemon "-Pkotlin.incremental=false"
./scripts/package-windows.ps1
```

Android 默认版本：`versionName 1.9.0`、`versionCode 15`；桌面和 jpackage 默认版本也是 `1.9.0`。tag Release 工作流会从 tag 注入版本。当前正式 Release 为 `v1.9.0`（本轮提交并推送后创建）。

## 发布流程

1. 修改后运行 `git diff --check` 和相关测试。
2. 用说明性提交信息 `git commit`，然后推送当前分支：`git push origin feature/multi-source-foundation`。
3. 大功能或用户可感知修复通过 tag 创建 Release（例如 `v1.4.3`），构建并上传 APK、SHA256、Windows 包。仅文档小改动通常不单独发 Release。
4. 不要提交账号、Token、密码、`签名密码.txt`、`signing.properties` 或本地小说目录。

## 迁移到英文目录

建议新目录：`C:\Users\MiunaH\Desktop\mixn`。不要复制旧目录中的本地小说和密码文件，直接克隆：

```powershell
git clone -b feature/multi-source-foundation https://github.com/HyperionHXH/lightnovel-android-aggregator.git C:\Users\MiunaH\Desktop\mixn
```

在新目录补充机器本地的 `local.properties`（Android SDK）和签名配置；密码只放在本机安全存储，不要加入 Git。若需要保留未发布改动，先在旧目录提交并推送，再克隆。

## 已知限制与风险

- 没有真实 LK/LNS 账号时无法验证登录、签到和付费章节；付费解锁依赖站点接口和账号余额，接口变更需在对应适配器修复。
- 两站接口、SignalR/BFF 字段和专用字体可能变化，需保留来源级错误和超时处理。
- Android 图片绘制性能受模拟器渲染后端影响；应在真实设备和 GPU 模式 AVD 上复测。
- `v1.9.0` 本轮已在本机 `noval_api35` AVD 验证中文字体预览、设置分组/独立恢复默认和移除通用登录入口；完整 Debug 构建、单元测试、Lint、桌面测试及定向 Android 测试通过。在线冒烟测试需要预置真实双源登录态，未登录时不能作为本轮 UI 回归依据。
- 桌面端仍为 Swing/JVM，功能已覆盖基础流程，但视觉细节和与 Android 的完全一致性仍可继续优化。
- Java Android API 的 deprecated warning（如 `statusBarColor`）目前不阻断构建。
- 最近阅读体验增强提交：`105a36c`（文字样式分组）、`9a5589b`（控制栏分层）、`9960815`（点击区域）、`5fa1c96`（音量键/常亮）、`5ff43c4`（认证错误解释）、`2001c43`（分页缓存键收窄）。

## 后续建议

- 在真实账号下回归 LK 付费章节购买持久化、LNS 专用字体和签到。
- 为来源适配器补充请求 fixture/契约测试，接口变更时先更新 fixture 再改解析器。
- 对阅读器增加 Macrobenchmark 或 Android Studio Frame Timeline，重点观察图片密集章节和控制栏动画。
- 继续统一 Android/桌面端的加载、错误、分页和键鼠/触控交互，但不要重新引入已移除的本地 EPUB 阅读功能。
