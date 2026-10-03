# 快速开始

> 面向第一次使用 MatchConsole 的人：10 分钟把两台手机连起来，收到画面并开始记分。

---

## 1. 你需要准备什么

- **两台 Android 手机**：系统 Android 10（API 29）及以上。
- **同一个 Wi-Fi**：两台必须连**同一网段**（例如都是 `192.168.1.x`）。
  - 不要开启路由器的「AP 隔离 / 客户端隔离」，否则两台设备互相不可见。
  - 建议关闭 VPN（VPN 会改变默认路由，可能导致局域网地址不可达）。
- 第二台（接收端）用于监看与记分，第一台（发送端）接在游戏主机上采集画面。

---

## 2. 安装

### 方式 A：一键安装脚本（推荐）

**Linux / macOS**

```bash
curl -fsSL https://raw.githubusercontent.com/XIAWAN-PROMAX/Event-console/main/install.sh | bash
```

**Windows（PowerShell）**

```powershell
irm https://raw.githubusercontent.com/XIAWAN-PROMAX/Event-console/main/install.bat -OutFile install.bat ; .\install.bat
```

脚本会自动检测 `adb`、下载最新 Release 的 APK 并安装到已连接设备。两台手机分别插上各跑一次即可。

前提：已安装 [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)，并在手机上开启「USB 调试」。

### 方式 B：手动安装

1. 打开 [Releases](https://github.com/XIAWAN-PROMAX/Event-console/releases)，下载最新 APK。
2. 命令行安装：

   ```bash
   adb install -r MatchConsole-1.0-release.apk
   ```

3. 或在手机上直接点开 APK 安装（需允许「安装未知来源应用」）。

> 两台手机安装**同一个 APK** 即可；进入 App 后再选择「发送端 / 接收端」。

---

## 3. 首次连接

### 第二台：接收端

1. 打开 App → 选 **接收端**。
2. 首次会申请「通知」权限（Android 13+），允许即可。
3. App 自动启动局域网信令服务。**状态与设置区**会显示本机 `IP:端口`（默认 `8770`）与一个二维码。
4. 此时界面就是赛事控制台，等待第一台接入。

### 第一台：发送端

1. 打开 App → 选 **发送端**。
2. 首次申请「相机」（扫码用）与「通知」权限。
3. 选择采集参数：`720p / 1080p`、`30 / 60 fps`、码率上限 `4 / 6 / 8 Mbps`。
   > 参数在**下次开始采集**时生效。
4. 连接接收端：
   - 点 **扫码连接**，对准第二台的二维码；或
   - 手动输入第二台的 `192.168.x.x:8770`，点 **手动连接**。
5. 点 **开始屏幕采集** → 系统弹出投屏授权 → 允许。
6. 点 **开始推流**。此时第二台主画面应出现第一台的画面。

### 需要留档时

在发送端点 **开始录制到 MP4**。录制复用推流前的视频帧，**不需要第二次投屏授权**，
录制内容与推流画面完全一致。停止后文件保存在：

```
Android/data/com.matchconsole/files/Movies/
```

---

## 4. 常用建议

```bash
# 调试时常亮屏幕（两台都可设置）
adb shell settings put global stay_on_while_plugged_in 3
```

查看统一日志（问题排查用）：

```bash
adb logcat -s MatchConsole:*
```

---

## 5. 接下来

- 完整操作说明 → [使用手册](user-guide.md)
- 连不上、黑屏、录制为空 → [常见问题](faq.md)
- 想自己改代码、打新包 → [构建与发布](build-and-release.md)