# Live Dashboard — Android App 源码

> `android-source` 分支 — Android 客户端源码
>
> 服务端部署、前端功能、API 参考等通用文档请参阅 [`main` 分支 README](https://github.com/Monika-Dream/live-dashboard/tree/main#readme)。

## 下载

预编译 APK 可从 [GitHub Releases](https://github.com/Monika-Dream/live-dashboard/releases) 直接下载安装。

## 这个分支包含什么

Android 客户端是一个 Kotlin + Jetpack Compose 应用，通过蓝牙心率设备实时读取心率，并上报到 Live Dashboard；同时支持可选的手机在线/电量心跳、媒体状态同步和前台应用检测。无需 root。

### 功能

| 功能 | 说明 |
|------|------|
| **蓝牙实时心率** | 连接标准 BLE 心率设备，实时接收心率广播并按设定间隔上报 |
| **自动重连保活** | 记住上次连接设备，后台定时拉起服务并尝试自动重连 |
| **心跳上报** | 可选功能，10–50 秒间隔（默认 30 秒），上报在线状态和电池信息 |
| **电量上报** | 自动上报电池电量和充电状态 |
| **前台应用检测** | 通过 Usage Stats 获取当前前台应用，用于面板展示当前正在使用的应用 |
| **媒体同步** | 通过通知监听同步当前媒体标题、艺术家和播放器名称 |
| **连接状态检测** | 每 5 秒测试服务器连接，顶栏实时显示连接状态 |
| **诊断日志** | APP 内 DebugLog 页面查看同步日志，方便排查问题 |

### 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Android BLE / GATT（标准心率服务 `0x180D`）
- WorkManager — 心跳与心率服务保活
- NotificationListenerService + MediaSession
- WorkManager — 后台定时同步，支持网络约束和指数退避
- DataStore — 持久化配置和同步状态
- EncryptedSharedPreferences — Token 加密存储

### 系统要求

- Android 8.0+ (API 26)
- 支持标准 BLE 心率服务的蓝牙设备

### 文件结构

```
agents/android-app/
├── app/
│   ├── build.gradle.kts              # 构建配置（SDK 版本、依赖）
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/monika/dashboard/
│           ├── MainActivity.kt        # 入口 + 导航
│           ├── DashboardApp.kt        # Application 类
│           ├── data/
│           │   ├── SettingsStore.kt    # DataStore 配置管理
│           │   └── DebugLog.kt        # 内存日志（UI 查看）
│           ├── heart/
│           │   ├── BluetoothHeartRateManager.kt # BLE 扫描、连接、心率通知
│           │   ├── HeartRateService.kt         # 前台服务 + 心率上报
│           │   └── HeartRateWorker.kt          # 自动重连保活任务
│           ├── device/
│           │   ├── ForegroundAppDetector.kt # 前台应用检测
│           │   └── ScreenStateReceiver.kt   # 锁屏/息屏切 idle
│           ├── media/
│           │   ├── MediaNotificationListenerService.kt # 媒体通知监听
│           │   └── MediaSyncCoordinator.kt           # 媒体状态上报
│           ├── network/
│           │   └── ReportClient.kt    # HTTP 上报客户端
│           └── ui/screens/
│               ├── SetupScreen.kt     # 服务器配置
│               ├── StatusScreen.kt    # 状态总览 + 权限诊断
│               └── HealthScreen.kt    # 蓝牙心率管理
├── BUILD.md                           # 构建指南
├── GUIDE.md                           # 代码指南（架构、流程、API）
├── build.gradle.kts                   # 项目级构建
├── gradle/                            # Gradle wrapper
└── settings.gradle.kts
```

## 构建

```bash
cd agents/android-app
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

详见 [`BUILD.md`](agents/android-app/BUILD.md)。

## 架构与代码指南

详见 [`GUIDE.md`](agents/android-app/GUIDE.md)，包含：
- 心率上报流程、心跳流程、媒体同步流程
- 设计决策（为什么用 BLE、为什么用 WorkManager 保活等）
- API 接口和 DataStore 配置键
- 常见问题排查
