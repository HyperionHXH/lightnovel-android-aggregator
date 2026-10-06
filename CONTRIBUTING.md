# 贡献指南

感谢你为 Mixn 提交问题、文档或代码。Mixn 是面向个人账号的轻小说聚合阅读器，当前稳定版本为 `1.21.3`。

## 提交前

- 先搜索现有的 Issue、[CONTEXT.md](CONTEXT.md) 和相关 ADR，确认问题尚未记录。
- 只提交与当前改动相关的文件。不要提交账号凭据、Token、`security_key`、签名密码、Keystore、本地小说目录或导出的书籍内容。
- 来源接口问题请脱敏，避免粘贴密码、付费正文或完整会话响应。

## 开发环境

- JDK 17
- Android SDK 35

仓库自带 Gradle Wrapper，不需要安装全局 Gradle。开始开发前请先阅读 [CONTEXT.md](CONTEXT.md) 和适用的 [架构决策](docs/adr/)。

## 验证

提交改动前运行 Android 单元测试、Lint 和 Debug 构建：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon "-Pkotlin.incremental=false"
```

真实账号冒烟测试需要显式设置环境变量，默认测试不会访问外网。桌面端开发目前无限期搁置，不在当前贡献和验证范围内。

## 提交 Issue 和 Pull Request

Issue 请使用模板，并提供 Android 系统版本、Mixn 版本、来源、复现步骤和脱敏日志。Pull Request 请说明用户可见变化、验证命令和是否涉及来源协议、数据迁移或 Release 行为。

保持提交小而清晰，提交消息使用简短的动词开头，例如 `fix: ...`、`docs: ...`。不要为普通文档改动创建 GitHub Release；Release 只由 `v*` 标签工作流生成。

