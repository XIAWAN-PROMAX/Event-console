# 常见问题（FAQ）

> 连接、画面、录制、崩溃等问题的排查顺序。

---

## 连接类

### 一直显示「等待连接」

按顺序检查：

1. 两台手机是否连**同一个 Wi-Fi、同一网段**（例如都是 `192.168.1.x`）。
2. 接收端 App 是否在前台运行（信令服务随界面生命周期启动）。
3. 发送端填写的 `IP:端口` 是否正确（默认端口 `8770`）。
4. 电脑 / 路由器防火墙是否拦截了 `8770` 端口。
5. 路由器是否开启了「AP 隔离 / 客户端隔离」——开启后两台设备互相不可见。

### 一直显示「建立通道中」

- 关闭两台手机上的 **VPN**（VPN 会改变默认路由，导致局域网地址不可达）。
- 确认路由器未开启客户端隔离。
- 重新在发送端点一次「开始推流」。

### 二维码扫不出来

- 接收端二维码只在**未连接**时显示；已连接后需要先断开。
- 光线不足或屏幕反光时，可改用**手动输入** `IP:端口`。

---

## 画面类

### 已显示「已连接」但画面黑屏

- 发送端是否真的点了 **「开始推流」**？
- 查看**发送端状态区**的 ICE / Peer 状态是否为 `connected` / `stable`。
- 确认接收端主线是否被其它浮层遮挡（可点「纯净画面」排查）。

### 画面卡顿 / 延迟高

- 降到 `720p` + `30fps`，并把码率上限调低到 `4 Mbps`（**下次开始采集生效**）。
- 查看接收端状态区的 **RTT / 丢包**：丢包高说明 Wi-Fi 信号差或信道拥挤。
- 让两台设备靠近路由器，避开 2.4G 拥堵信道。

### 画面有黑边

属正常现象：默认「适配」模式按 16:9 等比缩放，保证画面完整可见。
若想铺满，可在状态区切换为「**画面：裁切**」（边缘会被切掉）。

---

## 录制类

### 录制文件只有几 KB

- 采集未真正出帧。先确认**推流正常、接收端有画面**，再开始录制。
- 检查发送端「运行状态」中的**已编码帧**是否在增长。

### 录制文件在哪？

```
Android/data/com.matchconsole/files/Movies/
```

可通过 `adb pull` 导出：

```bash
adb shell ls /sdcard/Android/data/com.matchconsole/files/Movies/
adb pull /sdcard/Android/data/com.matchconsole/files/Movies/<文件名>.mp4 ./
```

### 录制和推流画面不一致？

正常设计下两者一致（录制复用编码前的同一路视频帧）。
若不一致，请检查是否使用了非官方版本或修改过 `ScreenRecorder`。

---

## 崩溃 / 稳定性

### Android 14 上点「开始屏幕采集」即崩

- 确认 `ScreenCaptureService` 的 `foregroundServiceType` 未被改动
  （必须包含 `mediaProjection`，并且**先启动前台服务再获取 MediaProjection**）。

### 一开录就闪退

- 这是典型的 WebRTC 视频帧被重复释放问题。请确认 `ScreenRecorder.onFrame` 中
  **没有**调用 `frame.release()`（`VideoFrame` 由调用方持有并自动释放）。

### 切后台 / 锁屏后中断

- 采集由 `mediaProjection` 前台服务承载，正常应持续。
- 检查系统「电池优化 / 后台限制」是否杀掉了 App，将其设为「不受限制」。

---

## 性能 / 日志

统一日志 tag 为 `MatchConsole`：

```bash
adb logcat -s MatchConsole:*
```

查看机身温度状态：

```bash
adb shell dumpsys thermalservice | grep -i status
```

调试时常亮屏幕：

```bash
adb shell settings put global stay_on_while_plugged_in 3
```

---

## 还是没有解决？

请到 [Issues](https://github.com/XIAWAN-PROMAX/Event-console/issues) 提交，附上：

- 两台手机型号、Android 版本；
- 复现步骤；
- `adb logcat -s MatchConsole:*` 的相关日志。