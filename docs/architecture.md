# 架构说明

> MatchConsole 的模块划分、连接建立流程、媒体管线与性能设计。

---

## 1. 总体结构

```
        第一台（发送端）                          第二台（接收端）
┌────────────────────────────┐          ┌────────────────────────────┐
│ ModeSelectScreen           │          │ ModeSelectScreen           │
│        │                   │          │        │                   │
│ SenderScreen               │          │ ReceiverScreen             │
│        │                   │          │        │                   │
│ SenderController  ◀────────┼──信令──▶ │ ReceiverSession            │
│    │        │              │  TCP     │    │        │              │
│ SenderPeer  ScreenCapture  │  8770    │ SignalingServer  PeerConn   │
│    │                       │          │    │                       │
│ MediaProjection 采集        │          │ SurfaceViewRenderer 渲染    │
│    │                       │          │    │                       │
│ WebRTC 编码 ──媒体(P2P)─────┼─────────▶│ 视频轨 → 记分牌/控制台       │
│    │                       │          │                            │
│ ScreenRecorder → MP4       │          │ ScoreboardViewModel(DataStore)│
└────────────────────────────┘          └────────────────────────────┘
```

- **信令**：局域网自实现 TCP，接收端监听 `8770`，发送端主动连接。
- **媒体**：WebRTC 点对点，只交换 host candidate（`RTCConfiguration(emptyList())`，无 STUN/TURN）。

---

## 2. 模块职责

| 包 | 关键类 | 职责 |
| --- | --- | --- |
| `common` | `WebRtcCore` | `PeerConnectionFactory`、全局 `EglBase`、`RTCConfiguration`、渲染器工厂 |
| | `ScreenRecorder` | 复用视频帧的 MP4 录制器（MediaCodec H.264 + MediaMuxer） |
| | `NetUtils` / `QrCodeUtils` / `PermissionUtils` / `Notifications` / `Logx` / `LinkStats` | 局域网地址、二维码、权限、通知、日志、链路指标 |
| `signaling` | `SignalingMessage` | `hello / offer / answer / ice / bye` 模型 + 换行 JSON 编解码 |
| | `SignalingServer` | 接收端：监听端口、接受连接、收发消息、连接状态流 |
| | `SignalingClient` | 发送端：连接接收端、收发消息、重连与状态流 |
| `sender` | `SenderController` | 总协调器：状态机、信令与 Peer 编排、录制开关 |
| | `SenderPeer` | `ScreenCapturerAndroid` + 建轨 + Offer/Answer + ICE + SDP 改码率 + 统计 |
| | `ScreenCaptureService` | `mediaProjection` 前台服务（Android 14 时序） |
| | `SenderScreen` / `QrScanScreen` | 发送端界面 / CameraX + MLKit 扫码 |
| `receiver` | `ReceiverSession` | 总协调器：信令服务、建 `PeerConnection`、回 Answer、挂渲染器、统计、录制 |
| | `ReceiverScreen` | 接收端外壳：权限、启停信令服务、顶栏 + 控制台 |
| `scoreboard` | `ScoreboardModels` | 比赛数据模型（队名、比分、节次、犯规、暂停、计时、覆盖层配置、主题） |
| | `ScoreboardRepository` | DataStore 持久化 |
| | `ScoreboardViewModel` | 状态机 + 状态投影（见 §5） |
| | `ScoreboardOverlay` | 记分牌横条 + 沉浸叠加层 + 底部快捷条 |
| `console` | `ConsoleScreen` | 四区域装配 |
| | `VideoPane` / `ScoreControlPane` / `TimerControlPane` / `StatusPane` | 四个区域 |
| | `ConsoleTheme` / `ConsoleWidgets` | 主题队色 / 通用组件 |

---

## 3. 连接建立流程

```
发送端                                    接收端
  │  TCP connect(ip:8770)                   │
  │────────────────────────────────────────▶│  接受连接
  │  hello                                  │
  │────────────────────────────────────────▶│
  │  offer (SDP)                            │
  │────────────────────────────────────────▶│  setRemoteDescription
  │                                         │  createAnswer
  │  answer (SDP)                           │
  │◀────────────────────────────────────────│
  │  setRemoteDescription                   │
  │  ice (candidate)  ⇄  ice (candidate)    │  双向交换 host candidate
  │                                         │
  │  ════════ WebRTC 媒体流（P2P，UDP）═══════▶
```

- 媒体协商为 `UNIFIED_PLAN`，接收端以 `RECV_ONLY` transceiver 接入。
- 断开时发送 `bye`。

---

## 4. 媒体与录制管线

### 4.1 发送端采集与编码

```
MediaProjection/VirtualDisplay
        │
ScreenCapturerAndroid ──▶ VideoSource ──▶ VideoTrack ──▶ PeerConnection（发送）
                                              │
                                              ▼
                                    ScreenRecorder（作为 VideoSink）
                                              │
                                     MediaCodec(H.264) ─▶ MediaMuxer ─▶ MP4
```

**关键点：只申请一次屏幕投影。** Android 14 起同一个 `MediaProjection` 只允许
`createVirtualDisplay()` 一次。因此本地录制没有用第二个 `MediaRecorder`，而是把
`ScreenRecorder` 作为 `VideoSink` 挂到 WebRTC 视频轨，直接吃**编码前**的 I420 帧：

- 零额外投影、无二次授权弹窗；
- 录制内容与推流画面完全一致；
- `onFrame` 中**不能**调用 `frame.release()`——`VideoFrame` 由调用方持有并自动释放，
  自行 release 会造成引用计数下溢（native 双重释放），表现为一开录就闪退。
- 所有从编码器 `dequeueInputBuffer` 取出的缓冲都必须通过 `queueInputBuffer` 归还，
  否则编码器输入缓冲会耗尽。

### 4.2 接收端渲染

```
PeerConnection ──▶ VideoTrack ──▶ SurfaceViewRenderer（EglBase 上下文）
                        │
                        └──▶（可选）接收端本地录制
```

---

## 5. 性能设计：重组范围收敛（关键）

记分牌计时器每 **100ms** 更新一次状态。如果 UI 直接订阅整个 `ScoreboardState`，
包含 `SurfaceViewRenderer` 的 `AndroidView` 会跟着 **10Hz** 重组，主画面必然卡顿、
按钮点击也会迟滞。

因此 `ScoreboardViewModel` 按「变化频率」把状态投影成四条独立的流：

| 流 | 频率 | 订阅者 |
| --- | --- | --- |
| `clock` | 100ms | 记分牌计时读数（叶子节点） |
| `clockFlags` | 点击级 | 计时按钮文案 |
| `scores` | 点击级 | 比分控制区、比分叠加层 |
| `layout` | 点击级 | 控制台布局、主题、开关 |

每条流都经过 `distinctUntilChanged`。控制台顶层与主画面容器**不订阅** `clock`，
所以计时数字跳动时只重组那几个 `Text`，视频画面与按钮完全不受影响。

---

## 6. 布局设计

- 记分牌从「画面上的叠加层」改为**画面下方的独立横条**（`ScoreboardBand`），
  保证推流画面不被遮挡。
- 沉浸预览时画面铺满整屏，此时记分牌才以叠加层形式出现在画面顶部，
  底部快捷记分条可通过开关隐藏，实现**零遮挡**。
- 横屏锁定：`android:screenOrientation="sensorLandscape"`。

---

## 7. 权限与前台服务

| 权限 | 用途 |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 局域网信令 + WebRTC 媒体 |
| `CHANGE_WIFI_MULTICAST_STATE` | 局域网发现（保留） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14 屏幕采集前台服务 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 前台服务第二类型 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| `CAMERA` | 发送端扫码连接 |
| `WAKE_LOCK` | 长时间运行 |

`ScreenCaptureService` 声明 `foregroundServiceType="mediaProjection|dataSync"`，
并保证「先启动前台服务，再调用 `getMediaProjection()`」的 Android 14 时序。

---

## 8. 依赖版本

| 项 | 版本 |
| --- | --- |
| AGP | 8.7.3 |
| Kotlin | 2.0.21（含 Compose 编译器插件） |
| Compose BOM | 2024.12.01（Material3） |
| WebRTC | `io.github.webrtc-sdk:android:137.7151.05` |
| DataStore | 1.1.1 |
| CameraX / MLKit / ZXing | 1.4.1 / 17.3.0 / 3.5.3 |

集中管理于 `gradle/libs.versions.toml`。