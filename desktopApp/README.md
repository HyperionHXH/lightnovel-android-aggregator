# Mixn Windows

这是 Mixn 的 Windows 桌面端预览版，使用 Kotlin/JVM 与 Swing，提供接近 Kazumi 的独立窗口、侧边导航和滚动列表工作流。

当前已具备：

- 发现、聚合搜索、统一书架、设置四个桌面入口；
- 轻之国度与轻书架独立发现、排行和搜索；
- 两站独立登录、会话恢复、退出、资料和轻书架签到；
- 书籍详情、分卷、章节目录、在线正文、章节解锁提示和阅读进度；
- 统一书架，按 `sourceId:remoteId` 去重并保留来源；
- 双源阅读历史聚合，保留来源标识；
- 已读章节离线保存和 EPUB 导出（锁定章节不会导出）；
- 发现与搜索列表的触底分页状态机，追加时按 `sourceId:remoteId` 去重；
- 独立的 `DesktopPageLoader` 来源接口，UI 不依赖 Android `Context`、DataStore 或 WorkManager；
- `installDist` 分发目录和 `jpackage` Windows app-image 打包脚本。

桌面端现在通过独立的纯 Kotlin 来源适配器调用两站官方接口。轻书架使用 SignalR JSON/gzip 协议，轻之国度使用 BFF/API；桌面会话 Token 使用本地 AES-GCM 加密偏好存储，密码只存在登录调用期间。桌面端不会显示伪造的书籍数据，来源错误会保留在各自操作中。

构建与运行：

```powershell
./gradlew.bat :desktopApp:run
./gradlew.bat :desktopApp:installDist
./scripts/package-windows.ps1
```
