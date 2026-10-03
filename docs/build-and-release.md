# 构建与发布

> 从源码构建 APK、配置签名、发布新版本的完整流程。

---

## 1. 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | **17**（AGP 8.7.3 请勿使用过新的 JDK） |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0`、`platform-tools` |
| Gradle | 8.9+（仓库 wrapper 指向 8.11.1，无需单独安装） |

`gradle.properties` 中 Gradle 堆内存建议：

```properties
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
```

---

## 2. 配置 SDK 路径

在仓库根目录创建 `local.properties`（**不入库**）：

```properties
sdk.dir=/path/to/Android/Sdk
```

---

## 3. 命令行构建

```bash
export JAVA_HOME=<你的 JDK17 路径>
export ANDROID_HOME=<你的 Android SDK 路径>
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Debug 包
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 包
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> debug 包含 WebRTC 原生库与 MLKit，体积约 120MB+ 属正常。

若在公司代理后编译，给 Gradle 传代理参数：

```bash
./gradlew :app:assembleDebug \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8080 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8080
```

---

## 4. Android Studio（推荐）

1. `File > Open` 选择仓库根目录（含 `settings.gradle.kts`）。
2. Gradle Sync 完成后，`Build > Build Bundle(s) / APK(s) > Build APK(s)`。
3. 产物在 `app/build/outputs/apk/`。

---

## 5. 发布包签名

`assembleRelease` 会读取仓库根目录的 `keystore.properties`；存在时才产出**已签名**的 APK。

文件格式（**该文件与 `.jks` 均已被 `.gitignore` 忽略，不进仓库**）：

```properties
storeFile=matchconsole-release.jks
storePassword=<你的密码>
keyAlias=matchconsole
keyPassword=<你的密码>
```

`storeFile` 相对仓库根目录解析。文件不存在时构建不会中断，但产物为**未签名** APK，无法直接安装。

生成 keystore：

```bash
keytool -genkeypair -v -keystore matchconsole-release.jks \
  -alias matchconsole -keyalg RSA -keysize 2048 -validity 10000
```

> keystore 与密码请单独备份（密码管理器 / 离线介质）。
> **一旦丢失，就无法再对同一 `applicationId` 发布更新**，只能更换包名重发。

> debug 包与 release 包使用**不同签名**，无法互相覆盖安装。若设备上已装过 debug 包，需先卸载。

---

## 6. 安装到设备

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

或使用仓库的一键安装脚本：

```bash
./install.sh --apk app/build/outputs/apk/release/app-release.apk
```

---

## 7. 发布新版本（GitHub Release）

1. **提升版本号**：修改 `app/build.gradle.kts` 中的 `versionCode` / `versionName`。
2. **构建并签名**：`./gradlew :app:assembleRelease`。
3. **打 tag 并推送**：

   ```bash
   git tag v1.1
   git push origin v1.1
   ```

4. **创建 Release 并上传 APK**：

   ```bash
   gh release create v1.1 app/build/outputs/apk/release/app-release.apk \
     --title "v1.1" --notes "更新说明..."
   ```

5. 更新 README 顶部的版本徽章。

> `install.sh` / `install.bat` 默认拉取**最新 Release** 的 APK，发布后一键安装脚本会自动指向新版本。

---

## 8. 常见构建问题

| 现象 | 处理 |
| --- | --- |
| `SDK location not found` | 检查 `local.properties` 的 `sdk.dir` |
| `Unsupported class file major version` | JDK 版本不对，改用 JDK 17 |
| Gradle 编译 OOM | 调大 `org.gradle.jvmargs` 的 `-Xmx` |
| 下载依赖超时 | 配置代理或镜像仓库 |