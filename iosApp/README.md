# Mixn iOS

这是 Mixn 的原生 SwiftUI iOS 目标，最低支持 iOS 17。Android 端继续使用现有 Kotlin/Compose 工程；iOS 端通过相同的来源协议访问轻之国度与轻书架，不复制 Android 私有实现。

当前可用能力：

- 轻之国度登录、Keychain 会话保存、发现、搜索、详情、正文阅读；
- 轻书架邮箱登录、Keychain Token 保存、登录后 SignalR JSON 发现、搜索和正文；
- 双源独立入口、来源标识、系统分享图标、基础阅读界面。

书架同步、阅读设置/分页、下载导出、签到和完整章节目录仍需按 iOS 的生命周期、文件和后台任务模型逐项补齐。iOS CI 会先验证工程可编译，不能把当前开发预览版当成 App Store 完整版。

## 本机运行

需要 macOS、Xcode 16 或更高版本。打开 `Mixn.xcodeproj`，选择 `Mixn` Scheme 和 iOS 17 模拟器即可运行。首次真机运行时，在 Xcode 的 Signing & Capabilities 中选择自己的 Apple Developer Team；Bundle ID 为 `io.github.jiangyuyi.mixn`，如该 ID 已被占用可改成个人团队下的唯一 ID。

## 签名与发布

Apple 不允许第三方替用户生成可发布的签名证书。必须使用用户自己的 Apple Developer 账号：

1. 在 Apple Developer 中创建 App ID `io.github.jiangyuyi.mixn`，并创建 Apple Distribution 证书和 App Store provisioning profile；
2. 在 GitHub Actions 中配置 `IOS_TEAM_ID`、`IOS_BUNDLE_ID`、`IOS_PROVISIONING_PROFILE_BASE64`、`IOS_PROVISIONING_PROFILE_NAME`、`IOS_CERTIFICATE_P12_BASE64`、`IOS_CERTIFICATE_PASSWORD`；
3. 推送 `v*` 标签，`.github/workflows/ios-release.yml` 会在 macOS runner 上导入证书、归档、导出 App Store IPA 并上传构建产物；也可以手动运行 workflow，选择 `ad-hoc` 导出已登记设备可安装的 IPA；
4. 第一次发布前在 App Store Connect 创建对应应用记录，并确认 Bundle ID 与 profile 完全一致。

没有 Apple Developer 账号时仍可构建和运行模拟器版本，但不能生成可安装到其他设备或提交 TestFlight/App Store 的签名 IPA。证书、私钥和 profile 永远不应提交到 Git。

发布 workflow 会在导入 profile 前校验 UUID、Team ID、Bundle ID 和 profile 名称，归档和导出命令启用失败即终止；任务结束时会清理临时证书、profile 和 keychain。iOS 与 Android 可以使用同一个 `v*` 标签，两个 workflow 会幂等复用同一个 GitHub Release 并分别上传 IPA/APK。
