# 安全政策

## 支持版本

安全修复优先针对当前稳定版本 `1.21.3` 和默认分支。旧版本可能无法获得修复，请先升级并确认问题仍可复现。

## 报告漏洞

请不要在公开 Issue 中披露可利用的漏洞、凭据或完整请求。优先通过 GitHub Security Advisories 提交私密报告：

<https://github.com/HyperionHXH/lightnovel-android-aggregator/security/advisories/new>

如果无法使用私密通道，请先提交不包含细节的 Issue，请维护者提供安全联系渠道。报告应包含受影响版本、复现步骤、影响范围和可行的修复建议；请删除密码、Token、`security_key`、Keystore、签名密码、个人信息和付费内容。

我们会确认收到报告，评估影响，修复后在变更记录中说明。请给维护者合理时间完成修复和发布。

## 本地开发安全

- `signing.properties`、`.signing/`、Keystore 和签名密码只保存在本机或 CI Secrets 中。
- 不要提交本地小说目录、EPUB/TXT、账号数据、缓存或日志。
- 两个内容来源的登录凭据必须保持独立，不要在 Issue、日志或测试夹具中使用真实凭据。

