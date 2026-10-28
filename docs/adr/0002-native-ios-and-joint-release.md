# ADR 0002：原生 iOS 与双端联合维护发布

状态：采纳（初次交付须通过原生验收）  
日期：2026-10-27

## 背景

用户要求按 Android 当前全部用户功能移植 iOS，此后功能和 bug 两端一起维护、同一 Release 含两端安装包；本次 Android 无新功能，不加版本、不重发 APK。开发机 Windows 不能执行 Xcode；参考项目 Triomi iOS 采用 Flutter 和云端 macOS 验收，但 Mixn 已是原生 Kotlin / Compose Android 工程，且两者许可分别为 MIT 与 GPLv3，不能直接拷贝源码合并。

Android Compose、Cronet、Keystore、DataStore、WorkManager 和 SAF 不能直接在 iOS 执行；单纯增加一个 iOS target 不会使现有 Android App 跨平台。

## 决策

1. 保留 Android native，增加 SwiftUI / UIKit / WebKit 原生 iOS 15+ 客户端，不在此次任务中强制整个 Android 重写或增加业务共享框架。
2. 两端位于同一仓库、同一主开发线；初次移植分支为 `feat/ios-parity`，不是长期隔离平台业务分支。新增功能/bug 同一 PR 评估并修复两端，平台例外明确说明。
3. 协议语义由 `contracts/` 的脱敏响应约束，两端真实 Adapter 各自执行；功能语义逐项维护在 `docs/PLATFORM_PARITY.md`。源资源组合键、来源认证隔离、付费权限、专用字体、缓存所有权继续遵守 ADR0001。
4. iOS 会话进 Keychain，离线下载逐章原子检查点、临时内容按限额淘汰。系统文件夹授权用于导出，私有文件所有者负责删除；不照搬 Android SAF 内部存储布局。
5. 不用私有 API 模拟音量键接管、强制后台常驻、签名绕过或静默安装。后台刷新是系统调度的最早时间，不能冒充 Android 六小时周期保证；详见 `docs/IOS.md`。
6. XcodeGen 与固定 Xcode 在 macOS CI 生成工程，分别产生 arm64 iPhoneOS 无签名 Release IPA、模拟器 XCTest/XCUITest、原生截图和录屏。自动完整性核验后仍须下载目视截图；不能用语法检查或图片哈希当界面验收。
7. 未来新版本标签由联合门槛控制，双端都通过后发布 APK + unsigned IPA。本次先通过 iOS 全部门槛，再仅给既有 `v1.22.2` 追加新 IPA/摘要，不移动标签、不替换已发布 Android 文件。未通过则不发。
8. 已发布版本的平台独有修复经该平台原生验收、用户明确授权发布后，可只更新既有 Release 的对应平台安装包、摘要与来源清单；不提升无改动平台版本、不替换其附件、不移动既有标签。必须同时更新默认分支 README/安装说明和 Release 说明，并从公开下载入口重新下载核验。只提供 Actions 临时附件不算完成发布；IPA 同版本修订以精确源提交和 SHA-256 区分。

## 理由

- 避免为移植破坏已验收的 Android 阅读器、来源协议和本地数据；UIKit/WebKit 直接提供 iOS 支持的字体/分享/权限路径。
- 在没有实体 iOS 设备的 Windows 环境，云端真实模拟器与 device 构建比不能运行的工程声明更可验证。
- 不复制另一个许可证下的实现；借鉴可复用的是验收方法与平台机制。
- 共享语言不是同步维护的充分条件。用同一功能清单、真实协议样例和联合门槛约束两份原生实现，避免单端悄悄漂移。

## 后果

- 两份 UI/Adapter 有维护成本；每次修改须搜索并评估两端，来源修复原则上补共同样例，不宣称只改一个文件即可自动生效。
- 后续可依据真实重复与维护证据引入可共享核心，而非提前增加 Rust/KMP/Flutter 桥接与迁移风险。
- 未签名 IPA 不是 App Store 安装或永久签名服务；README 必须说明工具、系统、到期、数据备份及免责声明。
- 自动测试和模拟器无法代替真实账号权限、硬件音量、相册、低电量调度、设备签名到期验证。这些限制不能写成“全部真机验收通过”。
