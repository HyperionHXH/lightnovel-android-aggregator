# 贡献指南

Mixn 是面向个人账号的 Android / iOS 轻小说聚合阅读器，当前版本基线为 `1.22.2 / 38`。iOS 初次移植只增补同版本产物，不重发 Android APK。

## 提交前

- 先搜索现有的 Issue、[CONTEXT.md](CONTEXT.md) 和相关 ADR，确认问题尚未记录。
- 只提交与当前改动相关的文件。不要提交账号凭据、Token、`security_key`、签名密码、Keystore、本地小说目录或导出的书籍内容。
- 来源接口问题请脱敏，避免粘贴密码、付费正文或完整会话响应。

## 开发环境

- JDK 17
- Android SDK 35
- iOS：macOS、固定 Xcode 16.4 和 XcodeGen；详见 [`docs/IOS.md`](docs/IOS.md)。Windows 本地不能代替原生 iOS 构建验收。

仓库自带 Gradle Wrapper，不需要安装全局 Gradle。开始开发前请先阅读 [CONTEXT.md](CONTEXT.md) 和适用的 [架构决策](docs/adr/)。

## 验证

提交改动前运行 Android 单元测试、Lint 和 Debug 构建：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon "-Pkotlin.incremental=false"
```

还须按 [`双端维护约束`](docs/PLATFORM_PARITY.md)逐项检查另一端。协议 bug 使用 `contracts/` 脱敏样例同时覆盖 Kotlin / Swift 真实解析路径；iOS 跑单元测试和原生 UI tour，记录精确提交、run、截图及录屏摘要，下载后目视每张图。禁止删除仍有用户意义的验收来使构建通过，也不能拿 DEBUG 夹具当真实账号验收。真实账号冒烟显式开启，不把个人账号放入 CI；桌面端继续不在验证范围。

## 提交 Issue 和 Pull Request

Issue 请使用模板，并提供系统/机型、Mixn 版本、来源、复现步骤和脱敏日志；iOS 补充安装工具与签名状态。PR 说明两端实现与测试；系统特有能力写出另一端替代、限制，注明来源协议/数据迁移/Release 影响。

保持提交小而清晰，使用简短动词消息，如 `fix: ...`、`docs: ...`。不为普通文档修改建 Release；未来新标签通过双端门槛后共同发布 APK 与 unsigned IPA。初次 iOS 在验收后仅追加既有 `v1.22.2` 的新文件，不移动标签、不覆盖旧 APK。

