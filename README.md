# 双机赛事监看与记分控制台（MatchConsole）

两台 Android 手机同一 Wi-Fi 下工作：

- **发送端（第一台）**：MediaProjection 采集屏幕 → WebRTC 点对点推流 → 同时本地录制成 MP4。
- **接收端（第二台）**：WebRTC 拉流渲染 + 赛事控制台（主画面 / 比分 / 计时 / 状态四个区域），
  主画面上叠加半透明记分牌，操作即时联动。

不使用 Miracast / AirPlay / Cast 等系统投屏协议，不做系统分屏、悬浮窗、小窗。
媒体走 WebRTC P2P，局域网直连，**不配置 STUN/TURN，不依赖公网**。

---

## 1. 项目结构树与文件职责

```
/workspace
├── settings.gradle.kts                 仓库与模块声明
├── build.gradle.kts                    顶层插件（AGP / Kotlin 不在此 apply）
├── gradle.properties                   JVM/AndroidX/BuildConfig 开关
├── gradle/libs.versions.toml           版本目录（依赖与插件集中管理）
├── local.properties                    本机 SDK 路径（不入库）
└── app/
    ├── build.gradle.kts                模块构建脚本（compileSdk 35 / minSdk 29）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml         权限 + 单 Activity + 前台服务声明
        ├── res/                        图标、主题、字符串
        └── java/com/matchconsole/
            ├── MatchConsoleApp.kt      Application：初始化 WebRTC、通知渠道、SenderController
            ├── MainActivity.kt         单 Activity，Compose 路由（模式选择 / 发送端 / 接收端）
            │
            ├── app/
            │   └── ModeSelectScreen.kt 模式选择页（发送端 / 接收端入口）
            │
            ├── common/                 跨模块基础设施
            │   ├── WebRtcCore.kt       PeerConnectionFactory / 全局 EglBase / RTCConfiguration / 渲染器工厂
            │   ├── ScreenRecorder.kt   复用视频帧的 MP4 录制器（MediaCodec H.264 + MediaMuxer）
            │   ├── LinkStats.kt        链路指标数据类 + 文本格式化
            │   ├── NetUtils.kt         本机 IPv4、连接串解析/拼接
            │   ├── QrCodeUtils.kt      ZXing 二维码生成（接收端显示）
            │   ├── Notifications.kt    通知渠道 + 前台服务通知
            │   ├── PermissionUtils.kt  运行时权限清单与检查
            │   └── Logx.kt             统一日志
            │
            ├── signaling/              局域网信令（自实现 TCP）
            │   ├── SignalingMessage.kt 信令消息模型（hello/offer/answer/ice/bye）+ 换行 JSON 编解码
            │   ├── SignalingServer.kt  接收端：监听端口、接受连接、收发消息、连接状态流
            │   └── SignalingClient.kt  发送端：连接接收端、收发消息、重连与状态流
            │
            ├── sender/                 发送端
            │   ├── SenderController.kt     总协调器：状态机、信令与 Peer 编排、录制开关
            │   ├── ScreenCaptureService.kt mediaProjection 前台服务（Android 14 时序）
            │   ├── SenderPeer.kt           ScreenCapturerAndroid + 建轨 + Offer/Answer + ICE + SDP 改码率 + 统计
            │   ├── SenderScreen.kt         发送端 Compose 界面
            │   └── QrScanScreen.kt         CameraX + MLKit 扫码页
            │
            ├── receiver/               接收端
            │   ├── ReceiverSession.kt      总协调器：信令服务、建 PeerConnection、回 Answer、挂渲染器、统计、录制
            │   └── ReceiverScreen.kt       接收端外壳：权限、启停信令服务、顶栏 + 控制台
            │
            ├── scoreboard/             记分牌
            │   ├── ScoreboardModels.kt     比赛数据模型（队名、比分、节次、犯规、暂停、计时、覆盖层配置、主题）
            │   ├── ScoreboardRepository.kt DataStore 持久化（重启恢复上一场）
            │   ├── ScoreboardViewModel.kt  状态机：所有比分/计时/犯规操作 + 自动保存
            │   └── ScoreboardOverlay.kt    主画面上的半透明记分牌条 + 链路指标浮层
            │
            └── console/                控制台 UI
                ├── ConsoleScreen.kt        四区域装配（主画面 / 比分 / 计时 / 状态）
                ├── ConsoleTheme.kt         浅色&深色主题、队色
                ├── ConsoleWidgets.kt       大按钮、卡片、信息行、顶部栏、Toast 宿主
                ├── VideoPane.kt            区域 1：主画面 + 记分牌叠加 + 指标浮层
                ├── ScoreControlPane.kt     区域 2：队名编辑、±分、节次、犯规、暂停
                ├── TimerControlPane.kt     区域 3：比赛计时 + 进攻时限 + 暂停计时 + 补时
                └── StatusPane.kt           区域 4：连接状态、二维码、参数、覆盖层开关、主题、接收端录制
```

---

## 2. 依赖与权限

### 2.1 版本（`gradle/libs.versions.toml`）

| 项 | 版本 |
| --- | --- |
| AGP | 8.7.3 |
| Kotlin | 2.0.21（含 compose 编译器插件） |
| Compose BOM | 2024.12.01（Material3） |
| WebRTC | `io.github.webrtc-sdk:android:137.7151.05` |
| DataStore | 1.1.1 |
| CameraX / MLKit / ZXing | 1.4.1 / 17.3.0 / 3.5.3 |

### 2.2 AndroidManifest 权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 局域网信令 + WebRTC 媒体 |
| `CHANGE_WIFI_MULTICAST_STATE` | 局域网发现（保留） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14 屏幕采集前台服务 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 前台服务第二类型（接收端/长任务） |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| `CAMERA` | 发送端扫码连接 |
| `WAKE_LOCK` | 30 分钟长时间运行 |

Activity 锁定 `sensorLandscape`；`ScreenCaptureService` 声明
`foregroundServiceType="mediaProjection|dataSync"`。

---

## 3. 编译

### 3.1 环境要求

- JDK **17**（AGP 8.7.3 不支持 JDK 21+ 之外的更新版本，请勿用 JDK 25）
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`、`platform-tools`
- Gradle 8.9+（仓库 wrapper 指向 8.11.1）

### 3.2 Android Studio（推荐）

1. `File > Open` 选择 `/workspace`（含 `settings.gradle.kts` 的目录）。
2. Gradle Sync 完成后，`Build > Build Bundle(s) / APK(s) > Build APK(s)`。
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 3.3 命令行（已实测通过）

```bash
export JAVA_HOME=<你的 JDK17 路径>
export ANDROID_HOME=<你的 Android SDK 路径>
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug        # 或使用本机 gradle
```

若在公司代理后编译，给 Gradle 传代理参数：

```bash
./gradlew :app:assembleDebug \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8080 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8080
```

> 本仓库已验证：`assembleDebug` 成功产出 `app-debug.apk`（debug 包含 WebRTC 原生库与 MLKit，体积约 120MB+，属正常）。

### 3.4 发布包签名

`assembleRelease` 读取仓库根目录的 `keystore.properties`，存在时才产出**已签名**的 APK：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` 格式（**该文件与 `.jks` 均已被 `.gitignore` 忽略，不进仓库**）：

```properties
storeFile=matchconsole-release.jks
storePassword=<你的密码>
keyAlias=matchconsole
keyPassword=<你的密码>
```

`storeFile` 相对仓库根目录解析。文件不存在时构建不会中断，但产物为**未签名** APK，无法直接安装。

> keystore 与密码请单独备份（密码管理器 / 离线介质）。**一旦丢失，就无法再对同一 `applicationId` 发布更新**，只能更换包名重发。

> debug 包与 release 包使用**不同签名**，无法互相覆盖安装。若设备上已装过 debug 包，需先卸载。

---

## 4. 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

两台手机都装同一个 APK。建议两台都：

```bash
adb shell settings put global stay_on_while_plugged_in 3   # 调试时常亮
```

---

## 5. 连接与使用

### 5.0 首页

模式选择页右上角有 **「关于我们」**，点击弹窗展示：作者、开源说明与 GNU GPLv3 许可协议。

### 5.1 接收端（第二台）

1. 打开 App → 选 **接收端**。
2. 首次会申请「通知」权限（Android 13+）。
3. App 自动启动局域网信令服务，**状态与设置区**显示本机 `IP:端口`（默认 `8770`）与二维码。
4. 界面即控制台：左/中央主画面，右侧比分 + 计时，右下状态与设置。

### 5.2 发送端（第一台）

1. 打开 App → 选 **发送端**。
2. 首次申请「相机」（扫码用）、「通知」权限。
3. 选采集参数：`720p / 1080p`、`30 / 60 fps`、码率上限 `4 / 6 / 8 Mbps`（下次开始采集生效）。
4. 连接接收端：点 **扫码连接** 扫第二台二维码，或手输 `192.168.x.x:8770`。
5. 点 **开始屏幕采集** → 系统弹投屏授权 → 允许（此后进入 `mediaProjection` 前台服务）。
6. 点 **开始推流**。
7. 需要留档时点 **开始录制到 MP4**（复用 WebRTC 编码前的视频帧，与推流画面一致，**不需要二次授权**）。

### 5.3 接收端操作

| 区域 | 操作 |
| --- | --- |
| 主画面区 | 16:9 等比适配黑边填充；画面上叠加记分牌条（可开关、可切顶部/底部）；角落显示链路指标（可整体关闭）；右上角有「沉浸预览」「指标」开关 |
| 沉浸预览 | 点画面右上角「沉浸预览」→ 主画面铺满整屏、顶部细条隐藏，记分牌固定置顶，底部出现半透明快捷记分条（两队 +1/+2/+3/-1 与开始/暂停/重置）。点「退出沉浸」回到四区域控制台 |
| 纯净画面 | 点画面右上角「纯净画面」或状态区同名开关 → 一键屏蔽**所有**盖在推流画面上的东西（记分牌条、链路指标、连接角标、沉浸快捷条），画面零遮挡，只留画面上角一个半透明的「显示浮层」按钮用于恢复。状态会持久化 |
| 画面缩放 | 状态区「画面：适配 / 画面：裁切」——适配保证画面完整可见（可能有黑边），裁切铺满但会切掉左右或上下 |
| 比分控制区 | 点击队名改名；每队 `+1 / +2 / +3 / -1`；节次切换；犯规数 `+/-`；暂停数 `+/-` |
| 计时控制区 | 大字号比赛时间；开始 / 暂停 / 重置；进攻时限倒数与开关；暂停计时；补时 |
| 状态与设置区 | 连接状态、接收端 IP:端口与二维码、分辨率/帧率/码率、记分牌开关与位置、浅色/深色主题、接收端本地录制、一键重置比赛 |

比分、计时等所有改动会即时同步到主画面上的记分牌叠加层，并通过 DataStore 持久化，App 重启后恢复上一场。

---

## 6. 测试与验收对照

| 验收项 | 操作 | 预期 |
| --- | --- | --- |
| 同网连通 | 两台连同一 Wi-Fi（同一网段，勿开 AP 隔离） | 接收端能显示二维码与 IP:端口 |
| 推流建立 | 发送端扫码/手输 → 开始采集 → 开始推流 | 接收端出现「已连接」徽标，主画面出现第一台画面 |
| 控制台布局 | 观察接收端 | 主画面 + 比分区 + 计时区 + 状态区同屏显示 |
| 记分牌联动 | 点比分 `+1/+2/+3/-1` | 主画面叠加层比分实时变化，无卡顿 |
| 计时联动 | 开始 / 暂停 / 重置 | 主画面叠加层计时同步 |
| 本地录屏 | 发送端点开始录制，操作 1 分钟后停止 | MP4 正常播放，画面与推流一致 |
| 持久化 | 杀进程重开接收端 | 比分、队名、节次恢复 |
| 延迟 | 发送端滑屏，看接收端 | 局域网目测延迟 < 500ms（状态区可看 RTT） |
| 稳定性 | 连续运行 30 分钟 | 不崩溃；画质不劣化；机身可接受的温升 |
| 生命周期 | 切后台 / 锁屏 / 来电后回到前台 | 采集与推流保持，或能自动恢复；无 ANR |

延迟与稳定性快速观测：

```bash
adb logcat -s MatchConsole:*   # 统一日志 tag
adb shell dumpsys thermalservice | grep -i status
```

---

## 7. 设计要点与已知限制

1. **Android 14 的 MediaProjection 时序**：必须先启动 `mediaProjection` 前台服务，再调用
   `MediaProjectionManager.getMediaProjection()`。本项目由 `ScreenCaptureService` 保证该顺序。
2. **只申请一次屏幕投影**：Android 14 起同一个 `MediaProjection` 只允许 `createVirtualDisplay()` 一次。
   因此本地录制没有用第二个 `MediaRecorder`，而是把 `ScreenRecorder` 作为 `VideoSink` 挂到
   WebRTC 视频轨上，直接吃编码前的 I420 帧 —— 零额外投影、无二次弹窗、录制内容与推流完全一致。
3. **码率控制**：通过改写 SDP 的 `b=AS` / `b=TIAS` 行实现，不依赖具体 WebRTC 版本内部 API。
4. **无 STUN/TURN**：`RTCConfiguration(emptyList())`，只交换 host candidate，仅在局域网可用。
5. **横屏锁定**：`android:screenOrientation="sensorLandscape"`。
6. 接收端与发送端媒体协商为 `UNIFIED_PLAN`，接收端以 `RECV_ONLY` transceiver 接入。
7. **重组范围收敛（性能关键）**：记分牌计时器 100ms 更新一次状态。如果 UI 直接订阅整个
   `ScoreboardState`，包含 `SurfaceViewRenderer` 的 `AndroidView` 会跟着 10Hz 重组，
   主画面必然卡顿、按钮点击也会迟滞。
   因此 `ScoreboardViewModel` 按「变化频率」把状态投影成四条独立的流：

   | 流 | 频率 | 订阅者 |
   | --- | --- | --- |
   | `clock` | 100ms | 记分牌叠加层、计时读数（叶子节点） |
   | `clockFlags` | 点击级 | 计时按钮文案 |
   | `scores` | 点击级 | 比分控制区、比分叠加层 |
   | `layout` | 点击级 | 控制台布局、主题、覆盖层开关 |

   每条都经过 `distinctUntilChanged`，控制台顶层与主画面容器**不订阅** `clock`，
   所以计时数字跳动时只重组那几个 `Text`，视频画面与按钮完全不受影响。

---

## 8. 常见问题

| 现象 | 排查 |
| --- | --- |
| 一直「等待连接」 | 两台是否同网段；接收端是否有 App 在跑信令服务；防火墙是否拦截 8770；IP 是否填错 |
| 一直「建立通道中」 | 检查是否开了 AP 隔离/客户端隔离；关闭 VPN（VPN 会改默认路由） |
| 画面黑屏但有「已连接」 | 发送端是否真的点了「开始推流」；查看发送端状态区 Peer/ICE 状态 |
| 录制文件只有几 KB | 采集未真正出帧；先确认推流正常再录制 |
| Android 14 上采集启动即崩 | 确认 `ScreenCaptureService` 的 `foregroundServiceType` 未被改动 |