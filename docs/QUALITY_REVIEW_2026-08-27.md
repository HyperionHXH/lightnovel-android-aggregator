# Mixn 质量审查（2026-08-27）

## 审查范围

本次按常规 Android 发布流程检查产品边界、构建与发布、安全与隐私、性能、可靠性、测试、无障碍和可维护性。审查基线是 `feature/multi-source-foundation` 分支的 `f634dfa`。

## 本轮已修复

1. **P1 - 认证数据备份边界**
   - 问题：Android 备份规则未排除 `secure_session.xml` 和 `credentials_lightnovelshelf.xml`。两者虽使用 Android Keystore 加密，但密文恢复到其他设备后无法使用原密钥解密，也不应进入云备份。
   - 处理：云备份和设备迁移都明确排除两个凭据文件。

2. **P1 - Release 包体积和无用代码**
   - 问题：Release 关闭 R8，且引入完整 `material-icons-extended`。旧 Debug APK 为 57,638,771 字节，其中 Material 扩展图标的 DEX 定义约 14 MiB。
   - 处理：Release 开启 R8 和资源收缩；移除扩展图标包，只保留核心图标，项目内补充文件、文件夹和下载矢量资源。

5. **P2 - 品牌一致性**
   - 应用名、README、发布 APK 名和 GitHub Release 标题统一为 `Mixn`。
   - 新授权的下载目录使用 `Mixn` 子目录；已存在的“诺阅”子目录优先继续使用，避免升级后丢失离线书可见性。
   - 保留原 `applicationId` 和 Kotlin 包名，这是覆盖安装、保留登录态与用户数据的兼容要求，不是界面品牌残留。
   - 图标使用用户提供的素材，已裁掉底部文字并缩放为 `432x432` 正方形；原图不进入仓库。

## 下一阶段高优先级

1. **P1 - 建立可量化的性能基线**
   - 新建 Macrobenchmark/Baseline Profile 模块，测量冷启动、首帧、发现页滚动、在线章节打开与二次打开。
   - 性能回归应使用 Release-like 构建和固定 AVD，不再用 Debug 模拟器的主观卡顿作为唯一依据。

2. **P1 - 离线目录迁移的原子性**
   - 当前迁移是逐书复制，失败时没有显式进度、错误状态和重试点。
   - 应增加迁移任务状态、完整性校验与可恢复失败，成功前不切换活动存储。

3. **P1 - 来源协议变化检测**
   - 两站都不是为本客户端提供的稳定 SDK。应对登录、发现、搜索、详情、目录、正文、书架和签到各保留一份脱敏合约样本，在 CI 内验证解析兼容性。

## 后续工程化

- **P2 - 仪器化测试**：补 SAF 目录授权、旧下载目录兼容、通知权限、首次引导和进程重建回归。
- **P2 - 无障碍与大字体**：用 TalkBack、200% 系统字体、深色模式和横屏完成主流程程验收；当前图标按钮大部分已有语义标签。
- **P2 - 可观测性**：增加隐私友好的本地诊断页，记录来源、错误类型、协议和耗时，不记录账号、Token、阅读正文或完整 URL 查询参数。
- **P2 - 依赖治理**：引入版本目录和 Dependabot/Renovate，每次升级都跑双源合约测试与 Release 收缩构建。
- **P3 - 命名清理**：内部 `LightNovel*` 类名和包名可以在不改 `applicationId` 的前提下逐步迁移，但这不影响用户体验，不应优先于协议稳定和阅读可靠性。

## 发布门槛

每次发布至少执行：

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin
./gradlew.bat assembleRelease
```

并验证：APK 签名、覆盖安装保留数据、两站分别登录和阅读、断网阅读、在线缓存二次打开、下载/导出、通知权限拒绝、深色模式和大字体。

## 本次验证结果

- `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`compileDebugAndroidTestKotlin`：通过。
- 开启 R8 与资源收缩的 `assembleRelease`：通过，一次性测试签名的 APK v2 签名校验通过。
- Debug APK：从 57,638,771 字节降至 43,457,205 字节，减少 24.6%。
- 收缩后 Release APK：32,124,933 字节。
- Android 35 干净 AVD：安装成功，冷启动成功，系统解析的应用名为 `Mixn`。
- 已知构建警告：Cronet 实验 API 被上游标记弃用；Release Lint 会输出 Kotlin Analysis API 工具链警告，两者均未导致构建或 Lint 失败。
