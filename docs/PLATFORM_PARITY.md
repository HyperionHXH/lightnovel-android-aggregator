# 双端功能与维护约束

## 开发线

- Android 在 `app/`，原生 iOS 在 `ios/`，来源样例在 `contracts/`。保留已有 MIT 授权；Triomi 的 GPL 实现仅作为方法参考，不整段搬入。
- `feat/ios-parity` 是初次移植的短期开发分支；验收后两端应随同一条主开发线维护，不建立永久分离的 Android/iOS 业务分支。
- 一个用户功能或 bug 一次变更：检查两端实现、同一协议回归、数据迁移、文档与发布。确为系统特有功能时写出另一端的可用替代及限制，不填空方法、不隐藏功能冒称对等。
- Kotlin 语言不等于 Android 专属；当前 Compose Android、Cronet、Keystore、DataStore、WorkManager、SAF 与 JVM 文件 API 才是直接阻止 iOS 运行的依赖。当前选择不破坏 Android 的独立 SwiftUI 客户端，而不是在此次任务中重写整个 Android 工程。

## 用户功能清单

下表是验收范围，不代表只凭文件存在就通过。实际运行记录与已知限制在 `AI_HANDOFF.md` 和对应 run 产物中；真实来源账号写入、付费权限和手机硬件不能用 UI 夹具冒充验收。

| 功能/不变量 | Android 主要实现 | iOS 主要实现 | 验收重点 |
| --- | --- | --- | --- |
| 首次引导/来源独立账号/目录选择 | `feature/onboarding`, `feature/sources/SourceAccounts*` | `Views/SettingsViews`, `Core/AppStore`, `Core/HTTPClient` | 保留已有数据；密码不存偏好；另一来源不受影响 |
| LK API / 会话 /注册 | `core/data/LightNovelRepository`, `core/network/JsonExt` | `Sources/KingdomSource` | HTTP业务错误、别名、取消、钥匙串、同账号重登旧响应 |
| LNS SignalR/会话刷新/备用来源 | `source/lightnovelshelf` | `Sources/ShelfSource`, `Sources/ShelfSignalR` | 帧边界、关联ID、gzip、请求取消不重放、限流、refresh单次 |
| 双源发现/独立榜单/分页 | `feature/discover` | `Views/LibraryViews` | 独立错误、正确下一页与固定榜单末尾，不混两站榜单 |
| 聚合搜索/作者匹配 | `feature/search` | `Views/LibraryViews`, 两来源 | quoted LNS搜索、书名/真实作者同步出现、上传者绝非作者 |
| 详情/同书版本/标签/目录 | `feature/sources/SourceBook*` | `Views/BookViews` | 详情不等待慢书架，分卷/章分页，锁章收费明确确认 |
| 作者补全/缓存 | `ListMetadataHydrator`, `ShelfDetailCache` | `NovelListModel`, `ShelfSource` | 有界四并发，缓存命中即显，失败结束占位，顺序稳定 |
| 阅读三个模式/跟手分页/取消回弹 | `feature/reader` | `Reader/ReaderDocument`, `Reader/ReaderWebView`, `Reader/ReaderView` | 停稳才保存、快速点按/拖回、章边界、进度恢复/seek |
| 七系统字体/自选下载/专用字体 | `core/reader`, `feature/profile/FontSelection*` | `Storage/FontStore`, `Reader`, `Views/SettingsViews` | hash校验，WOFF2真实渲染，专用字体失败不显示混淆正文 |
| 字号/行距/边距/主题/点击区/反转/屏幕方向/图片缩放 | `ReaderPreferences`, `ReaderTapZones`, `SettingsScreen` | `Core/Preferences`, `ReaderDocument`, `SettingsViews` | 持久化与分组重置、横竖屏、safe area、长文可读 |
| 阅读菜单/目录/插图缩放/相册/分享 | `ReaderMenu`, `ReaderImagePreview` | `ReaderView`, `ReaderImagePreview` | 菜单触摸层级、权限按需、原生截图包括WebView |
| 音量键/常亮/亮度 | `ReaderHardwareControls` | `ReaderView` 生命周期 | 常亮/亮度恢复；音量键拦截无公开等价，明确不支持 |
| 书架/下载筛选/更新标记/全部已读 | `feature/bookshelf`, `core/updates` | `LibraryViews`, `Storage/LibraryStore` | 来源键隔离；只有可信基线增长才更新；标记不改远端历史 |
| 远端与本地历史/继续阅读/删除 | `feature/history` | `LibraryViews`, `LibraryStore` | 本地进度不谎称通用云同步；来源能力允许才删除远端 |
| 全书/分卷下载/失败重试/删除/重启恢复 | `core/offline` | `Storage/LibraryStore` | 逐章检查点，锁章不当已下载，删除旧任务不复活 |
| TXT / EPUB3 /封面/图片/字体 | `core/txt`, `core/epub` | `Storage/EPUBWriter`, `LibraryStore` | 合法ZIP mimetype首项、OPF/nav、离线字体与图片、系统分享/目录 |
| 缓存统计/安全清理/容量上限 | `core/cache`, `DataStatsScreen` | `Storage/ContentCache`, `FontStore`, `DataStatsView` | 8MiB内存/96MiB磁盘/64MiB临时字体，不删下载/进度/凭据 |
| 来源等级/经验/轻币/签到 | `Profile*`, `SourceAccounts*`, source协议 | `SettingsViews`, 两来源 | 成长与访问等级分离，经验门槛不猜，签到后读取真实资料 |
| LK评论/回复/表情/@/图片/点赞/独立评分 | `CommentUi`, `SourceBookViewModel` | `CommentViews`, `KingdomSource` | 真实端点、图片评论不丢、评分不顺带发文字、nested回复 |
| LK关注/粉丝/关注操作 | `AccountScreens/ViewModels` | `CommunityViews` | 分页、确认取关、同账号重登迟到响应隔离 |
| LK发布作品状态 | `PublishingScreen` | `CommunityViews` | 状态只读、详情链接；不冒充完整作者工作台 |
| LK消息六类/未读/私信只读/目标跳转 | `MessagesScreens/ViewModels` | `CommunityViews` | 私有消息不落公开缓存、不增加未有的私信发送承诺 |
| 外观/界面与图标大小/分组默认 | `AppPreferencesStore`, `SettingsScreen` | `Preferences`, `SettingsViews`, `MixnApp` | 与阅读设置隔离，恢复默认不删数据 |
| Wi-Fi下载/后台更新通知 | `OfflineDownloadWorker`, `BookshelfUpdateWorker` | `LibraryStore`, `BGUpdateScheduler` | iOS由系统调度，非持续后台，默认关闭通知 |
| 关于/版本/更新/安全 | `AboutScreen`, `AppUpdateDownloader` | `AboutView`, `scripts/ios/package_ipa.py` | iOS只打开IPA发布页，不执行APK安装或宣传永久签名 |

## 共享协议回归

- `contracts/lns-author-cases.json` 被 Android 真实 LNS gateway 和 iOS 真实 parser 的测试同时读取，约束作者优先级/上传者排除。
- `contracts/lk-book.json` 被两端 LK 解析测试读取，约束章节统计、作者和标签。新增协议 bug 应先在同一目录加入来源的脱敏响应，再让两端真实 Adapter 测试消费，不复制被测逻辑。
- `scripts/ios/check_contract.py` 防止两端版本静默不一致。它是清单/配置检查，**不是功能对等证明**。
- UI 改动分别运行 Android instrumentation 和 iOS XCTest/XCUITest；不能用浏览器手机尺寸或 Swift 语法检查代替 iOS 原生执行。

## 发布规则

普通开发分支只跑构建/测试，不创建 Release。未来新 `v*` 标签由双端工作流执行，iOS验收与Android签名构建全部成功后才上传两端产物。
手动发布也必须先解析已经存在的版本标签，两端检出同一不可变提交；不能用主分支的新源码重新编译旧标签。IPA 和原生巡检清单保存实际源码提交与 Actions run。当前初次追加 iOS 的例外则在交付记录中明确其提交不同于早先 Android 标签。

这轮用户明确要求 Android 不变：不增加版本、不移动现有 `v1.22.2`、不重新构建发布旧 APK。iOS 全部可执行门槛成功、产物下载核验与截图目视完成后，仅追加新的 unsigned IPA/摘要到既有版本。已有同名不同摘要文件一律拒绝覆盖。
