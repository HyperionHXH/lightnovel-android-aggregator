# iOS 构建、安装和使用

## 产物是什么

Mixn iOS 使用原生 SwiftUI / UIKit / WebKit，最低 iOS/iPadOS 15.0；应用 ID 为 `io.github.hyperionhxh.mixn`。版本与 Android 的 `1.22.2 / 38` 对齐；这轮不改 Android 版本、不重新发布 APK。

交付文件名为 `Mixn-iOS-1.22.2-Unsigned.ipa`，另附 SHA-256。**Unsigned 是没有 Apple 开发者签名/描述文件，不代表普通 iPhone 可以不签名直接安装。** 它不在 App Store / TestFlight 分发，不带 Apple ID、证书、账号密码或站点会话，不需要 JIT。

- GitHub Actions 的 artifact ZIP 是运输容器；先解开外层 ZIP，取得里面的 `.ipa`，不要把外层 ZIP 直接导入安装工具。
- iPhoneOS arm64 Release `.app` 才能打包 IPA；iOS Simulator `.app` 仅能在 Mac 模拟器运行，不能当手机安装包。
- 模拟器、自动断言、截图和录屏只能证明对应测试场景；不是所有型号/系统/网站账号的保证。每次验收以精确提交、Actions run 和产物摘要为准。

## 安装路径

以下官方文档于 2026-10-27 查证。系统及工具政策会变化，请安装前再次核对官方兼容表；不要向本项目、Issue 或陌生签名服务提供 Apple 账号密码。

### AltStore Classic

1. 按 [AltStore 官方安装文档](https://faq.altstore.io/altstore-classic)安装 AltServer 和设备端 AltStore，完成工具要求的电脑连接与账号设置。
2. 设备端 **My Apps → `+`** 选择本项目的 IPA，由工具使用你的签名身份处理并安装。
3. 按系统提示信任开发者、启用开发者模式（若系统要求）。
4. [官方 Getting Started](https://faq.altstore.io/altstore-classic/your-altstore)说明，普通免费账户侧载应用通常 7 天到期、同时活跃应用数量受限；在到期前按工具流程刷新。付费账号及其他渠道以各自政策为准，项目不能保证永久签名。

### SideStore

先阅读 [官方前提](https://docs.sidestore.io/docs/installation/prerequisites)。当前要求 iOS/iPadOS 15+、锁屏密码、Apple Account、首次安装用电脑、Wi-Fi，并在安装/更新/刷新时开启官方要求的设备内 VPN。按官方安装流程完成后，在 SideStore 导入本项目 IPA。不要把任意网络连接或项目模拟器的代理设置当成 SideStore 所需环境。

### 已有 TrollStore / 越狱环境

只有已经符合 [TrollStore 官方支持范围](https://github.com/opa334/TrollStore)的设备才使用它导入 IPA：当前支持 14.0 beta 2–16.6.1、16.7 RC（20H18）、17.0；Mixn 自身仍需 15.0+。不能由此推断 16.7 正式版、17.0.1+ 或任意新 iOS 可安装。越狱与其他安装工具各有设备/系统要求，项目不附带破解、签名绕过或永久安装能力，也不建议仅为 Mixn 修改设备安全环境。

## 升级与数据

- 覆盖升级尽量保持同一 Bundle ID、安装工具和签名团队。更换签名身份可能造成无法覆盖、钥匙串不可访问或重新登录。
- **先在 App 中导出需要保留的书籍到沙盒外，再升级。不要先卸载。** 卸载会删除沙盒；重装不保证找回账号、进度、下载、自选字体或导出。
- Android 与 iOS 没有自动传输整份本地数据库；两个来源的远端书架/历史仍按来源账号和网站能力同步，不能把本地阅读进度误称通用云同步。
- 站点会话存 iOS Keychain，不存密码；退出一个来源不会退出另一个来源。钥匙串可能跨卸载保留，具体由系统/签名身份决定。
- 离线正文和必需字体在 Application Support，临时内容另有所有者和限额；“数据与统计 → 清理临时缓存”不删除下载、导出、自选字体和阅读进度。
- 设置中选择的文件夹是系统授权的**导出目标**。离线正文仍在 App 私有目录；这不是 Android SAF 整个书库迁移的同一存储机制。目录授权失效时重新选择，不会偷偷删除旧文件。

## 平台限制

| 项目 | iOS 行为与边界 |
| --- | --- |
| 音量键翻页 | 不使用私有 API 接管硬件音量键，也不伪装已支持；使用点击区域、滑动和阅读菜单翻页。系统音量照常工作。 |
| 后台下载 | 前台完成、保存章节检查点；系统挂起可能中断。返回前台/重启后恢复排队和中断任务，手动取消/删除不自动复活。 |
| 更新提醒 | 使用 BGAppRefreshTask 和本地通知，需授权及系统实际调度；“最早六小时后”不是每六小时必定执行。强制退出、低电量和系统策略可能阻止。 |
| 应用更新 | 只能打开发布页取得新 IPA，再用原安装工具处理；不执行 Android APK 安装器，不保证静默升级。 |
| 屏幕方向 | iOS 16+ 请求系统几何方向；iOS 15 限制可用方向并由实际设备旋转生效。多窗口/分屏仍受系统约束。 |
| 正文字体 | 轻书架要求的专用字体必须成功取得才显示正文；不以系统字体展示混淆文字。自选字体仅作用于不强制专用字体的章节。 |
| 网络 | 使用系统 HTTPS 与来源协议，不关闭证书验证、不打包用户电脑代理。局域网、防火墙、VPN、站点限制仍可能影响真实连接。 |
| 照片与文件 | 只在用户操作时请求相册写入/文件夹授权。模拟器不代替真实照片权限、设备音量、亮度、锁屏或存储提供商验收。 |

## 在 Mac 上构建

固定工具链见 [`ios/toolchain.json`](../ios/toolchain.json)：Xcode 16.4，iOS 18.5 模拟器作为验收基线；目标最低系统仍为 15.0。XcodeGen 工程由 [`ios/project.yml`](../ios/project.yml)生成，生成工程不入 Git。

```bash
python3 scripts/ios/check_contract.py
xcodegen generate --spec ios/project.yml
xcodebuild -project ios/Mixn.xcodeproj -scheme Mixn -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/ios/device CODE_SIGNING_ALLOWED=NO build
python3 scripts/ios/package_ipa.py \
  --app build/ios/device/Build/Products/Release-iphoneos/Mixn.app \
  --output build/ios/ipa/Mixn-iOS-1.22.2-Unsigned.ipa
```

不要使用模拟器编译产物代替上述 device build。Windows 仅能运行 Android 和只读产物检查；原生 iOS 类型检查、构建、XCTest/XCUITest 要在 Mac 上实际执行。

## 云端验收与发布

[`iOS Acceptance`](../.github/workflows/ios.yml)在开发分支运行：版本契约检查 → 无签名 device Release → 真实模拟器单元测试 → 生产页面巡检 → 原生截图/完整封装录屏 → SHA/尺寸/帧核验。失败保留诊断，但不上传可交付 IPA。UI 夹具在 DEBUG 编译条件内，Release 不含测试账号或测试书籍。

下载成功 run 的 `mixn-ios-unsigned` 和 `mixn-ios-acceptance` 到独立的 `release_ios/<RUN_ID>/`，核对 SHA-256、IPA 元数据和每张图；不要用别的提交的截图作为证据。本轮 iOS 初次发布只向已有 `v1.22.2` 增补新的 IPA/校验文件，不移动标签、不替换旧 Android APK。

以后的新版本 `v*` 统一发布由双端门槛约束：iOS 验收成功后才构建、验证 Android 签名 APK；两者都通过才发布 APK 与 unsigned IPA。普通功能/bug 在同一分支和 PR 中维护，见 [`双端功能对照`](PLATFORM_PARITY.md)。

## 免责声明

Mixn 是独立、非官方客户端，与 Apple、轻之国度、轻书架、AltStore、SideStore 或 TrollStore 均无隶属、合作或担保关系。第三方名称和商标属于其权利人；MIT 许可证只覆盖本项目有权许可的客户端源代码，不授予小说、封面、插图、字体或站点数据的额外权利。

使用前应遵守适用规则、来源服务条款、账号权限和内容授权。软件不托管小说、不提供破解付费章节、规避认证或批量分发服务；下载/导出只用于有权访问的内容，请勿公开传播或用于侵权用途。账号权限、站点数据、接口变化、断网、系统调度、签名过期/吊销、第三方工具或用户操作均可能导致功能中断、无法启动或数据丢失；请自行保留重要导出并保护账号。

软件按 [MIT License](../LICENSE)所述“现状”提供，不承诺不中断、无错误、永久签名或适用于所有设备。以上说明不排除适用规则中不可排除的权利，也不是对第三方安装工具的安全背书。
