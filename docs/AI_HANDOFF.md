# Mixn 项目交接记录

更新时间：2026-10-22
仓库：`https://github.com/HyperionHXH/lightnovel-android-aggregator.git`
当前分支：`feature/multi-source-foundation`
当前分支最新提交以 `git log -1` 为准；本文件记录的最近认证与阅读交互增强均已随分支提交推送。
`1.22.1 / 37` 已通过用户的 Android 模拟器人工验收；用户已授权提交、推送当前分支并创建 `v1.22.1` 发布标签。签名 APK 与 SHA-256 由 Android Release 工作流生成，构建和发布状态以对应 Actions 运行结果为准。
用户现已确认模拟器可联网、轻书架作者能显示，授权优化作者与书名同时呈现后推送编译。本轮保持现有候选 `1.22.2 / 38`，不再次增加版本；发布时必须显式指定仓库 `HyperionHXH/lightnovel-android-aggregator`（本机 gh 默认仓库可能指向另一个项目）。

## 项目定位

Mixn 是面向个人账号的轻小说聚合阅读器，内置轻之国度（LK）和轻书架（LNS）两个来源。应用统一提供发现、搜索、详情、章节、在线阅读、书架、阅读历史、离线下载/阅读、EPUB/TXT 导出和来源账号入口；两个来源的认证、协议、ID 和错误必须保持隔离。

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
- 在线章节离线保存、离线书架、EPUB 3/TXT 导出；默认写入应用专用 `offline_library/exports`，SAF 目录写入 `Mixn/exports`，不再支持本地 EPUB 导入/本地阅读器。已下载书架只展示书籍和进度，导出与删除集中在“下载与导出”。
- 发现/搜索触底分页、缓存 stale-while-revalidate、磁盘 LRU、章节正文缓存和来源更新快照。
- 阅读控制栏为 Compose 覆盖层；正文使用固定安全区，避免刘海/摄像头遮挡。展开/收起不再调用系统栏 API，也不触发整章重新解析或重新分页。
- 阅读页加载状态统一使用安全区域内的居中进度指示器；阅读菜单使用居中限宽可滚动面板，避免底部弹层和顶部偏移。
- 音量键翻页通过 `MainActivity.dispatchKeyEvent` 统一拦截；阅读页控制栏显示时仍可翻页，菜单或设置弹窗打开时恢复系统音量行为。菜单外部 scrim 可点击关闭，面板内部事件不会误关闭。
- 文字样式提供系统默认、系统黑体、系统宋体、等宽字体、手写体、紧凑黑体和圆体；细黑、中黑、特黑已移除，旧版本值会回退到衬线字体。独立字体预览页会为未安装字体展示中文示例，安装后用统一正文样例比较真实字形；可按需下载思源宋体、思源黑体、霞鹜文楷、源流明体、未来荧黑和汇文明朝体，下载文件固定版本并做 SHA-256 校验，已安装字体不会重复下载。
- 设置页按外观、阅读、下载和其他分组，外观/阅读/下载可分别恢复本组默认值，组内项目用分隔线保持视觉层次；聚合功能不再提供无来源归属的通用登录入口。
- `v1.10.0` 字体目录包含思源宋体、思源黑体、霞鹜文楷、源流明体、未来荧黑和汇文明朝体；后三款分别固定到 ButTaiwan、Wêlai 和 bosswnx 的可再发行版本，按需下载并做 SHA-256 校验。APK 仅内置统一样例的轻量真实预览子集，完整字体不打包。霞鹜文楷等宽已移除，旧设置 ID 会在读取时回退。
- 新手引导包含下载目录步骤；用户可以首次启动时选择 SAF 文件夹，也可以继续在设置页修改或恢复应用专用目录。未选择时明确显示应用专用目录，不阻断引导完成。
- Windows 客户端具备发现、搜索、书架、设置、登录、详情、章节、在线阅读、历史和 EPUB 导出等基础流程；Android 的 TXT/SAF 导出逻辑暂不复用到桌面端。
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

Android 当前版本：`versionName 1.22.2`、`versionCode 38`，沿用同一轮候选编号。Release 使用源码版本码，tag 仅注入版本名称。桌面端仍不在本轮支持与发布范围；签名产物与发布状态以当前标签的 Actions 运行结果为准。

## 2026-10-22 模拟器联网与后续修正

- 原 `noval_api35` 的全局私人 DNS 被设为严格 `hostname / one.one.one.one`，轻书架、百度等均报 `unknown host`。只在该模拟器将 `private_dns_mode` 改为 `opportunistic`，删除 `private_dns_specifier`，不更改宿主机 DNS、不修改 App TLS 校验。
- 首轮 DNS 修正只得到阶段性 TCP 探测和“请先登录”提示，不足以证明完整登录链路稳定。用户再次报告断网后，已在同一主模拟器复现：Chrome `DNS_PROBE_FINISHED_BAD_CONFIG`、三站 TCP 443 超时，Android 仅 `PARTIAL_CONNECTIVITY`。不得继续引用首轮结果声称联网已修好。
- 存储审计基线（原模拟器）：`files` 48 KiB、`cache` 52 KiB、`databases` 8 KiB；没有测得空间暴涨，发现的是代码中的无上限内存/章节字体和清理遗漏。旧清理仅删 `cacheDir`，漏掉正文数据库、章节字体，还绕过存储所有者。
- 参考 Android 官方[应用专用文件与缓存](https://developer.android.google.cn/training/data-storage/app-specific)、[LRU 图片缓存](https://developer.android.google.cn/topic/performance/graphics/cache-bitmap)及[模拟器 DNS](https://developer.android.google.cn/studio/run/emulator-networking-dns)：临时缓存需由应用主动限制和清理，不能寄望系统自动回收；用户下载和阅读进度属于保留数据。
- 作者补全通过 `ListMetadataProvider` 明确 opt-in。三个 ViewModel 发布带 `pendingMetadata` 的列表，缺作者的卡片用稳定、不可点击的骨架占位，作者完成与清除占位在同一次状态更新，书名/作者/简介一起出现；缓存和已知作者不占位，失败/缺作者结束占位且不伪造，原顺序与逐来源错误保留。`ShelfDetailCache` 合并同书详情请求，五分钟 TTL、64 项/512 Ki 字符上限。
- `TemporaryReadingCache` 统一协调清理。正文内存 8 MiB、实际 SQLite 文件 96 MiB；图片仅 32 MiB 内存；临时字体 64 MiB。字体迁移扫描当前下载目录和旧内部书库，已下载章节必需字体保留至独立目录，不因临时淘汰丢失；旧 SAF 副本/进行中下载可能仍引用的离线字体不自动推断为孤儿删除。
- 最终清理临时测试源码后的本地验证：206 项单元测试（205 通过、1 项既有跳过），Lint 0 Error（15 Warning/6 Information），Debug 和 instrumentation APK 构建通过。独立 API 35 模拟器上 39 项永久设备回归全通过，覆盖三处作者卡片、真实 SQLite 文件限额/LRU/高水位回收、字体迁移及阅读器。
- 另一个临时真实 UI 烟测已点击“数据与统计”的清理按钮，确认正文数据库减小、缓存项消失，同时已下载正文可读取、离线字体可加载、导出和手动字体文件保留、阅读进度 63% 不变；临时源码随后删除。烟测仅用独立测试模拟器，未清理主模拟器的用户数据。
- 主模拟器已覆盖安装 `1.22.2 / 38`，不清除用户数据。后续联网修复、用户登录后，真实轻书架发现页返回「年度最垃圾伪圣女 · わからないマン」「愿你手拥幸福 · ショーン田中」「圣剑学院的魔剑使 4 · 志瑞祐」，新书页返回十利ハレ、丸戸史明等真实作者。冷频道切换的实际截图确认书卡显示时书名、作者已同时出现，不将固定响应回归冒充线上验收。
- 二次诊断发现宿主机浏览器启用 `127.0.0.1:7890` 代理，原模拟器启动命令未包含代理，默认 DNS 也不稳定。参照 Android 官方[两种代理的区别](https://developer.android.google.cn/studio/run/emulator-networking-proxy)，保留用户数据重启主模拟器，显式使用模拟器层代理与 DNS：`C:/Android/Sdk/emulator/emulator.exe -avd noval_api35 -port 5554 -gpu host -no-boot-anim -no-snapshot-load -http-proxy http://127.0.0.1:7890 -dns-server 223.5.5.5,119.29.29.29`。仅调整本机模拟器环境，不安装证书、不关闭 TLS 校验、不改变 App 正式联网逻辑。
- 冷启动后实测：Android 网络成为 `VALIDATED`，原有 HTTP/HTTPS 连通性检测均返回 204（未替换检测地址）；模拟器 Chrome 加载百度与轻书架首页，App 实际拉取轻之国度榜单。通过模拟器 Chrome 的临时调试连接，从官方轻书架页面分别向主/备用 `/api/user/login` 发送空 JSON 验证请求（没有账号密码、`credentials: omit`），两者均收到 HTTP 200 JSON 响应；这仅证明登录 API 可达，不代表登录成功或真实作者验收完成。临时调试端口已移除。当前联网依赖宿主机 7890 代理持续运行，重启模拟器须保留上述启动参数。
- 同步呈现回归先在旧版真实 Compose 屏幕失败（详情未返回时仍出现书名），修正后 41 项定向设备回归全通过，涵盖三处卡片、作者缺失/网络失败退出占位、SQLite 实际限额与回收、离线字体保护、翻页/进度/菜单/图片。207 项单元测试（206 通过、1 项既有跳过），Lint 0 Error（15 Warning/6 Information），Debug 与 instrumentation APK 构建通过；单本慢详情不会阻挡另一卡片，超时后占位能结束。
- 用户登录后的真实作者搜索已通过：临时 opt-in Compose 烟测使用正式 `MainActivity` 与保存的轻书架会话，从 UI 输入「志瑞祐」，实际服务器返回「轻书架 · 志瑞祐」及「圣剑学院」作品，未出现上传者「小孤独」。没有读出或注入账号凭据；临时测试验证后删除，不进入提交。

## 2026-10-22 左右翻页与轻书架作者搜索

- 两个分页阅读入口共用 `HorizontalPageTurn`：原生 `HorizontalPager` 开启手指拖动，预组合相邻页，点击/音量键动画互斥；图片点击和长按仍由子节点处理。
- 分页进度与跨章回调只观察停稳页。左右跨章使用边界过渡页；页码、恢复锚点和进度条排除前置过渡页，卡片模式保留自己的图层和手势。
- 轻书架官网 [`Search.vue`](https://github.com/LightNovelShelf/Web/blob/06b0f7ec745142f702101bb225b1440850dd0a93/src/pages/Search.vue) 的 `exact` 模式通过给 `GetBookList.KeyWords` 加双引号同时匹配书名/作者；普通关键词只做标题 bigm 模糊。当前聚合搜索采用单请求的组合包含匹配，不拼接两条分页流。
- 官网 [`BookInList` / `GetBookInfoRes`](https://github.com/LightNovelShelf/Web/blob/06b0f7ec745142f702101bb225b1440850dd0a93/src/services/book/types.ts) 区分上传者 `UserName` 与作品 `Author`、`Extra.classification.author`。列表不再回退到上传者；缺失作者时留空，不增加逐卡详情请求。
- 交互参考 [Readest 手势文档](https://readest.com/docs/advanced)：边缘点击翻页、中心切换工具栏、移动端左右滑动。此次采用连续平移，不引入仿真卷页或替换卡片模式。
- 原 AVD 安装的 `1.22.0 / 36` 已复现：指针 DOWN/MOVE 期间正文像素不变，松手才触发翻页。`1.22.1 / 37` 同场景已观察到拖动中两页并排移动。当前两站均未登录；轻书架线上搜索仍需用户登录后验收。
- API 35 `noval_api35`（`emulator-5554`、host GPU）30 项定向设备检查全部通过：双阅读入口的停稳进度/跨章/恢复/进度条、原生拖动/回弹/快速点击/音量键、卡片与图片点击/长按，以及临时真实适配器→搜索 UI 的固定响应烟测。临时来源 UI 烟测与诊断日志在验证后移除，永久保留有行为约束的回归。
- 模拟器匿名访问轻书架一度显示网络连接失败；宿主侧匿名真实 SignalR 烟测已通过，实际返回认证错误，不能据此宣称登录后作者结果通过。用户需自行登录轻书架，核对作者搜索的实际作品集合。没有读取或更改本地账号凭据。
- 清理临时源码后的最终本地验证：196 项单元测试（195 通过、1 项既有线上跳过）、Lint（0 Error；15 Warning/8 Information）、Debug APK 与 instrumentation APK 构建通过；32 项永久定向设备回归全部通过，另外一次临时来源搜索 UI 烟测通过。用户随后确认模拟器验收通过，授权推送编译；Release 由标签工作流执行。

## 2026-10-15 双源等级经验接入

- 轻书架 `GetMyInfo.Growth` 接入 `Exp / GrowthLevel / Level / CurrentLevelExp / NextLevelExp`；`NextLevelExp: null` 才标记满级，字段缺失不能当成满级。成长等级和访问等级分别展示，不写死经验阈值表。
- 官方依据：[Growth 协议](https://github.com/LightNovelShelf/Web/blob/master/src/services/points/type.ts)、[Header.vue 经验计算](https://github.com/LightNovelShelf/Web/blob/master/src/components/app/Header.vue)，线上 `https://www.lightnovel.life/assets/index-C3w8JnIX.js` 也包含这些字段。
- 轻书架 `SignIn.Reward` 未被官方 Web 界面作为金币使用；删除本地 `before.coin + reward`，成功后读取 `GetMyInfo`，以实际前后余额/经验差值显示奖励。读取失败不把成功签到误报成失败，后续资料读取有可见错误和重试。
- LK 当前官网个人页的九档等级为平民/骑士/勋爵/子爵/伯爵/侯爵/公爵/王爵/皇帝，进度是 `(level - 1) / 8`，不是经验。证据为官网 Nuxt build `mv7vsr4h`：`DxoZno7O.js`（个人页）、`JlVi9Ybn.js`（等级映射）、`DyhAAvsD.js`（`my-home-v1` 资料解析）。只接入真实 `exp`；没有可核实的经验门槛或签到加经验规则，不解释 `next_exp` 为绝对阈值，不默认经验为 0。
- “我的”和来源账号页复用经验组件。两站签到后刷新 profile+签到状态，取消先前资料任务以免旧值覆盖；退出清理资料任务和数据，从账号管理回到“我的”用 `LifecycleResumeEffect` 重新读取。未新增自动签到或模拟阅读赚经验。
- API 35 独立 AVD 已用固定来源响应操作真实账号页，验证签到后等级 2→3、累计经验 195→201；1.3 倍系统字体下经验条、访问等级和签到说明没有文字重叠；LK 等级条与经验缺失状态独立展示。原 AVD 只读检查两站均 `signedIn=false`，未取得真实账号经验，也没有调用线上签到。
- 最终本地单元测试 194 项（0 失败、1 项既有跳过），Lint、Debug/Release 与 instrumentation APK 构建通过；3 项临时真实 Compose 页面烟测覆盖签到升级、LK 缺失经验、“我的”双源及满级（固定响应，不宣称真实账号签到验收）。签名 Release 为 `1.22.0 / 36`，v2 签名校验通过。

## 2026-10-15 LK/LNS 加载延迟排查

- 原 AVD LK 的系统 DNS 线路多次等待 12 秒后备用线路才成功；旧版只读 smoke 列表 14.908 秒、详情 12.458 秒、目录 23.461 秒，章节在 60 秒总护栏内未完成。成功线路不会复用是客户端放大因素。
- 修复后同一原 AVD：首次列表仍需 14.425 秒，但后续详情 0.902 秒、目录 0.802 秒、正文 1.205 秒。网络层按域名在进程内记住成功线路，失败清除偏好；没有持久保存 CDN 地址，也没有降低 TLS 安全。
- 独立 API 35 AVD 当前接口：列表 2.193 秒、详情 0.636 秒、目录 1.798 秒、正文 2.253 秒；官网正常访问的 API 返回 200、约 0.55–2.12 秒。不能凭这些结果证明服务器高负荷；原模拟器与独立设备链路差异明确。
- 详情/目录/书架已独立提交状态，慢书架不再阻挡首屏和首卷章节；本地延迟 fixture 的真实 Compose 页面可显示并操作目录。刷新继续保留内容，旧请求取消后不可污染新状态。
- Cronet 与 LNS SignalR 使用各自拥有的超时边界；外部取消直接传播。LNS 的本地 WebSocket fixture 已证明旧版会在外部取消后进入备用调用，修复后不再重放。
- LNS 官网正常加载；独立 AVD 匿名 GetBookList 返回 unauthorized，原 AVD 连接失败。两设备均无有效登录态，未验证 LNS 登录后章节/字体，不把匿名认证错误当服务端崩溃。
- 本地真实 Cronet fixture 已覆盖外层取消、EOF、真实 12 秒线路超时与回退。Debug 清单仅为回环 HTTP 测试增加 `127.0.0.1` 允许项，Release 明文禁用保持不变。
- 另以真实 Cronet 本地 fixture 复现页面 10 秒截止比单线路 12 秒更短，首条坏线路会阻止任何回退；JSON 单线路改为 4 秒，图片/上传保留 12 秒，取消边界不变。
- 最终 `1.21.4` JSON 4 秒线路配置，独立 AVD 冷进程实测：列表 8.042 秒、详情 1.089 秒、目录 0.816 秒、正文 1.233 秒（已取得非空正文）。这是单轮样本，不代表稳定延迟保证；LNS 同轮约 1.83 秒返回匿名未授权。
- 最终本地验证：179 项单元测试（0 失败、1 项既有跳过）、Lint、Debug/Release 与 instrumentation APK 构建通过；API 35 上 3 项真实 Cronet 回归全部通过，包括页面 10 秒截止内完成 JSON 备用线路请求。签名 APK 为 `1.21.4` / `35`，v2 签名校验通过。
- 原 AVD 最终冷进程复测：列表 5.806 秒、详情 0.770 秒、目录 1.196 秒、正文 1.792 秒，LNS 约 0.85 秒返回匿名未授权。临时线上计时与 Compose 延迟 fixture 源码在验收后删除，仅保留隔离的回归测试。

## 2026-09-23 图片点击回归修复

- 修复 `ReaderScreen` 和 `SourceReaderScreen` 在分页/滚动阅读中由父级全屏手势层抢占 `ReaderImagePreview` 点击的问题。
- 父级阅读手势现在只处理未被子节点消费的点击；图片点击优先打开预览，图片长按保存操作栏、关闭预览和相册下载逻辑未改动。
- 新增 `ReaderImagePreviewTest.parentTapHandlerDoesNotStealImageClick`，并在 API 35 AVD 上通过图片预览、长按保存、卡片翻页、音量键、阅读菜单和加载状态共 12 项定向测试。
- `v1.21.3` 已完成 Debug APK、Android 单元测试、Lint 和模拟器定向回归后再发布。

## 2026-09-11 本轮变更

- 1.14.0：详情页改为简介/评论/章节三 Tab；评论支持分页、最热/最新、星级展示和可选评分发布；评论业务码 20 显示为评论链接错误；标签和轻币任务卡片完成视觉分层，广告任务不接入。
- 1.14.1：评论读取改为与官方网页一致的可匿名请求；根据官方错误码表将业务码 20 显示为评论服务数据失败，并提供评论区域独立重试。实测官方评论代理当前仍返回 20，需等待站点服务端修复。
- 发现页榜单已按官网行为核对：LK 日榜、新书榜、周榜是固定快照，分页接口会重复内容，因此请求 30 个名额并在 UI 明确显示“官网固定榜单已全部加载”；原创、同人、EPUB、最近更新仍使用服务端 `page_info.has_next` 继续分页。
- LK 福利中心仅保留官方七日签到：`welfare-sign-detail-v1`、`claim-welfare-sign-v1` 和余额刷新。浏览赚轻币依赖官方客户端计时/心跳接口，当前没有可确认的公开合法接口，Mixn 不伪造阅读时长，因此不再展示或调用该任务；普通轻币任务也已移除。
- LK 作品评论支持最热/最新、分页加载、星级展示和发布评分，接口为 `get-book-comments` 与 `publish-book-comment`。评论能力通过 `CommentProvider` 暴露，轻书架不实现该接口；评论代理返回业务码 20 时显示为“评论链接错误”，不伪装成暂无评论。
- 本轮验证：148 项 Android 单元测试通过（1 项既有跳过）、Android Lint 通过、Debug APK 构建通过、桌面端测试通过；AVD `emulator-5554` 动态检查确认 LK 日榜显示官网实际 28 本并给出固定榜单提示。

## 2026-09-12 本轮变更

- 1.15.0：简介移除评分，作品 Tag 点击后进入带初始关键词的聚合搜索；评论评分改为图标式五星控件。
- 轻币 UI 只显示七日签到、余额和连续签到天数；不接入广告、浏览计时或其他需要模拟官方客户端行为的任务。
- 本轮新增 EPUB/TXT 导出：离线书籍卡片分别提供两个导出按钮，优先写入配置的下载目录；EPUB 使用现有封面/插图资源，TXT 使用 UTF-8 纯文本并以 `[插图]` 占位。滚动阅读插图增加稳定高度约束，放大预览改为真实触摸长按显示操作栏，并使用右上角关闭或系统返回退出。
- 轻书架登录的 HTTP 2xx 业务失败在登录上下文中归类为认证错误，并保留 `Msg/message/Error` 文案，避免账号输错被误报为来源服务不可用。
- 关于页参考常见开源应用改为品牌头部、检查更新主按钮和项目/开源分组列表，原有 Release 检查与 APK 系统下载保持不变。
- AVD `noval_api35` 动态确认简介、Tag 自动换行、Tag 搜索导航、评分选中态和关于页布局在 360dp 宽度下没有文字重叠。

## 2026-09-12 图片与离线导出修复

- 放大插图的操作栏改为挂在独立全尺寸容器上的 `combinedClickable`，真实手指长按和无障碍长按均可触发；为 Dialog 预留稳定的导航栏安全距离，避免按钮创建后落到系统导航栏下方。
- 移除全屏空白点击退出判定，避免图片判定区域误关闭；预览统一使用右上角关闭按钮或系统返回退出。
- 图片解析接受 `src`、`data-src`、`data-original`、`data-url`、协议相对地址和 HTTP 升级 HTTPS；图片请求允许来源返回的 HTTPS CDN。JPEG 写入相册使用标准 `image/jpeg` MIME。
- 离线进度显示“已完成卷数/总卷数 · 已下载章节数/总章节数”，锁定章节不计入统计；游客搜索没有登录门槛。
- 验证：`testDebugUnitTest`、`lintDebug`、`assembleDebug`、`desktopApp:test` 通过；AVD `noval_api35` 上 `ReaderImagePreviewTest` 的语义长按与真实触摸长按均通过。

## 发布流程

1. 修改后运行 `git diff --check` 和相关测试。
2. 用说明性提交信息 `git commit`，然后推送当前分支：`git push origin feature/multi-source-foundation`。

## 2026-09-13 LK 评论收尾

- LK 评论现已接入官方富评论协议：图片、官方表情、Unicode 表情、@ UID、回复目标、发布时间、点赞状态和回复预览均有独立模型与解析测试。
- 评论编辑器支持真实图片上传、@ 关注用户、表情插入、回复和点赞；评分通过官方评分字段单独提交，不重复发布评分评论。
- 评论读取保留图片-only 评论，分页仍按官方 `page_info` 判断；未登录允许读取，发布/点赞/上传按来源认证错误引导登录。
- `jsonBody` 已支持任意 `JsonElement`，避免 `mention_uids` 被编码成字符串；评论 API 的评分请求与官方 `view` 字段保持一致。
- 本轮已通过 `:app:testDebugUnitTest`、`:app:lintDebug` 和 `:app:assembleDebug`；轻书架适配已完成本地验证，等待用户确认后再提交发布。
- Review 后修复表情菜单只展示前 24 项的问题，改为可滚动展示完整官方分组；轻书架协议已兼容新旧详情/章节/正文字段，并完成真实账号模拟器回归。
- 1.20.0 修复 LK 评分十分制换算；评分通过独立确认按钮提交，评论发布不再携带评分字段。回复输入栏移动到目标评论下方，发送成功后自动清空并收起。

## 2026-09-14 轻书架适配回归

- 默认版本升级为 `1.21.1` / `versionCode 32`，本地 Debug APK 已重新安装到 AVD `noval_api35`（`emulator-5554`）。
- 轻书架详情兼容 `Book`/`BookInfo`/`Data` 容器及大小写变体；章节兼容 `Chapters`/`Chapter`/`ChapterList`，空的 `Chapters` 会回退到有效旧字段。
- 正文兼容多层 `Chapter`/`Data`/`Result` 响应、字符串/对象 `Content` 和官网 `Convert` 参数；简介 HTML 与字面量换行已清理。
- 轻书架列表接口按官方协议不返回作品简介，发现、搜索和书架卡片不再显示误导性的“暂无简介”；进入详情后通过 `GetBookInfo` 显示真实简介，避免一页作品额外发起大量详情请求导致卡顿和限流。
- 轻书架阅读进度通过 `SaveReadPosition({ Bid, Cid, XPath })` 同步；专用章节字体仍在正文显示前加载，避免混淆文本。
- 已实测轻书架发现、详情、章节目录和正文；正文截图显示为正常中文，无新的崩溃或 ANR。`uiautomator` 导出的混淆字符串不代表屏幕实际字形。
- 轻书架评论接口未在官网前端发现，当前不伪造评论能力；轻币/经验进度上报结果由官方服务器决定。
- 发现列表对缺少简介的条目使用后台详情补全，最多 4 个并发、单项 8 秒超时；列表首屏不再等待详情请求。简介通过原子状态更新逐条显示。
- “我的”页来源账号卡片不再重复提供退出登录，统一从“账号管理”进入。
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
- `v1.10.0` 本轮已在本机 `noval_api35` AVD 验证字体选择页面和新增预览资源，完整 Debug 构建、单元测试、Lint、桌面测试及定向 Android 测试通过。在线冒烟测试需要预置真实双源登录态，未登录时不能作为本轮 UI 回归依据。
- 桌面端仍为 Swing/JVM，功能已覆盖基础流程，但视觉细节和与 Android 的完全一致性仍可继续优化。
- Java Android API 的 deprecated warning（如 `statusBarColor`）目前不阻断构建。
- 最近阅读体验增强提交：`105a36c`（文字样式分组）、`9a5589b`（控制栏分层）、`9960815`（点击区域）、`5fa1c96`（音量键/常亮）、`5ff43c4`（认证错误解释）、`2001c43`（分页缓存键收窄）。

## 后续建议

- 在真实账号下回归 LK 付费章节购买持久化、LNS 专用字体和签到。
- 为来源适配器补充请求 fixture/契约测试，接口变更时先更新 fixture 再改解析器。
- 对阅读器增加 Macrobenchmark 或 Android Studio Frame Timeline，重点观察图片密集章节和控制栏动画。
- 继续统一 Android/桌面端的加载、错误、分页和键鼠/触控交互，但不要重新引入已移除的本地 EPUB 阅读功能。
