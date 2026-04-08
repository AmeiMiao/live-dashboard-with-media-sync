# Live Dashboard Android App — 代码指南

> 更新：2026-03-22

## 构建与部署

- **最低 SDK**：见 `app/build.gradle.kts` → `minSdk` (26)
- **构建**：`./gradlew assembleDebug`（在 `agents/android-app/` 下执行）
- **APK 输出**：`app/build/outputs/apk/debug/app-debug.apk`
- **安装**：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 设计决策

- **蓝牙心率优先**：通过标准 BLE 心率服务直接读取实时心率，避免依赖 Health Connect 的同步延迟。
- **前台应用检测**：使用 Usage Stats 近似获取当前前台应用；锁屏或灭屏时降级为 `idle`，避免误报。
- **媒体监听保留**：通过 NotificationListenerService + MediaSession 补充媒体标题、歌手与播放器信息。
- **仅 WorkManager**：HeartbeatWorker 与 HeartRateWorker 都使用自调度 OneTimeWorkRequest 绕过 15 分钟最小周期。底层 AlarmManager 即使被冻结也能唤醒。
- **心跳默认关闭**：不是所有用户都需要显示手机在线，作为可选功能。

## 关键流程

### 心跳流程（可选）
1. 用户在 SetupScreen 点击「开始监听」→ `HeartbeatWorker.schedule(context, interval)`
2. HeartbeatWorker 延迟触发 → 读取电量信息
3. `ReportClient.reportApp()` POST 到 `/api/report`，包含当前状态对应的 `appId`（锁屏/灭屏时为 `idle`）+ 电量
4. Worker 自调度下一个 OneTimeWorkRequest
5. 通过 AlarmManager 存活于小米进程冻结

### 连接状态流程
1. `MainActivity.DashboardTopBar()` 运行 `LaunchedEffect` 循环
2. 每 5 秒创建临时 `ReportClient`，调用 `testConnection()`（GET `/api/health`）
3. 更新状态 → TopAppBar 显示「已连接」(绿) 或「未连接」(灰)

### 蓝牙心率流程
1. 用户在 HealthScreen 扫描 BLE 心率设备并选择连接
2. `HeartRateService` 建立 GATT 连接，订阅标准心率测量特征通知
3. 收到心率广播后，按 `heart_rate_report_interval` 节流
4. 通过 `ReportClient.reportApp()` POST 到 `/api/report`，在 `extra.heart_rate` 中附带实时心率
5. `HeartRateWorker` 定时唤起服务并尝试重连上次保存的设备

### 媒体同步流程
1. 用户授予通知监听权限
2. `MediaNotificationListenerService` 监听媒体通知与会话变化
3. `MediaSyncCoordinator` 去重后调用 `ReportClient.reportApp()`
4. `/api/report` 中 `extra.music` 被服务端合并进当前设备状态

## 常见问题

| 症状 | 原因 | 解决 |
|------|------|------|
| 「未连接」但服务器正常 | URL 缺少 `https://` 或 Token 为空 | 检查 SetupScreen 配置，确认已保存 |
| 心率不上报 | 蓝牙权限未授权、设备未连接、或设备不支持标准心率服务 | 在状态页确认蓝牙权限，在健康页重新扫描并连接设备 |
| 自动重连失败 | 已保存设备地址失效、蓝牙关闭、或设备不在附近 | 打开蓝牙，重新连接一次设备以更新保存地址 |
| 耗电快 | 心跳间隔过低（如 10s） | 将间隔调整到 20-50s |
| Token 保存失败 | EncryptedSharedPreferences 不可用（旧设备） | SetupScreen 会显示警告，无解决方案 |
| 后台被杀 | OEM 电池优化 | StatusScreen → 忽略电池优化 + 厂商特殊设置 |

## API 接口

| 方法 | 路径 | 用途 | 调用者 |
|------|------|------|--------|
| POST | `/api/report` | 心跳、前台应用、媒体和心率上报 | HeartbeatWorker / ForegroundAppDetector / MediaSyncCoordinator / HeartRateService |
| GET | `/api/health` | 连接测试 | MainActivity |

## DataStore 配置键

| 键 | 类型 | 默认值 | 说明 |
|----|------|--------|------|
| `server_url` | String | `""` | 服务器地址（必须 HTTPS） |
| `report_interval` | Int | `30` | 心跳间隔，秒（10-50） |
| `monitoring_enabled` | Boolean | `false` | 心跳是否开启 |
| `heart_rate_report_interval` | Int | `30` | 心率上报最小间隔，秒（10-300） |
| `token`（加密） | String | `null` | 认证令牌（AES256-GCM） |
