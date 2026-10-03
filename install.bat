@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

rem ============================================================
rem  MatchConsole 一键安装脚本（Windows）
rem    - 自动检测 adb
rem    - 从最新 Release 下载 APK（或使用本地已构建产物）
rem    - 安装到当前通过 USB 连接的所有设备
rem
rem  用法：
rem    install.bat                              下载最新 Release 并安装
rem    install.bat --apk path\to\app-debug.apk  安装指定 APK
rem    install.bat --tag v1.0                   指定 Release 版本
rem    install.bat --uninstall                  卸载已安装的 App
rem ============================================================

if "%MATCHCONSOLE_REPO%"=="" (set "REPO=XIAWAN-PROMAX/Event-console") else (set "REPO=%MATCHCONSOLE_REPO%")
set "PKG=com.matchconsole"
set "APK="
set "TAG="
set "UNINSTALL=0"

:parse
if "%~1"=="" goto after_parse
if /i "%~1"=="--apk"       ( set "APK=%~2" & shift & shift & goto parse )
if /i "%~1"=="--tag"       ( set "TAG=%~2" & shift & shift & goto parse )
if /i "%~1"=="--uninstall" ( set "UNINSTALL=1" & shift & goto parse )
if /i "%~1"=="-h"          goto usage
if /i "%~1"=="--help"      goto usage
echo [MatchConsole] 未知参数：%~1（用 --help 查看用法）
exit /b 1

:after_parse

rem ---------- 1. 检测 adb ----------
where adb >nul 2>nul
if errorlevel 1 (
  echo [MatchConsole] 未检测到 adb，请先安装 Android Platform Tools：
  echo   https://developer.android.com/tools/releases/platform-tools
  echo [MatchConsole] 安装后把 platform-tools 加入 PATH，再重新运行本脚本。
  exit /b 1
)
for /f "delims=" %%p in ('where adb') do ( echo [MatchConsole] adb：%%p & goto adb_ok )
:adb_ok

rem ---------- 2. 检测设备 ----------
set "DEVICES="
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
  if "%%b"=="device" set "DEVICES=!DEVICES! %%a"
)
if "!DEVICES!"=="" (
  echo [MatchConsole] 没有检测到已授权的设备。请连接手机、开启「USB 调试」并在手机上允许调试授权。
  exit /b 1
)
echo [MatchConsole] 检测到设备：!DEVICES!

rem ---------- 3. 卸载模式 ----------
if "!UNINSTALL!"=="1" (
  for %%d in (!DEVICES!) do (
    echo [MatchConsole] 卸载 %PKG%（设备 %%d）...
    adb -s %%d uninstall %PKG%
  )
  echo [MatchConsole] 完成。
  exit /b 0
)

rem ---------- 4. 准备 APK ----------
if not "!APK!"=="" (
  if not exist "!APK!" (
    echo [MatchConsole] 找不到 APK 文件：!APK!
    exit /b 1
  )
  echo [MatchConsole] 使用本地 APK：!APK!
  goto install_apk
)

set "TMP=%TEMP%\matchconsole_install"
if exist "!TMP!" rd /s /q "!TMP!"
mkdir "!TMP!" >nul
set "URLFILE=!TMP!\url.txt"

echo [MatchConsole] 解析 Release（!TAG!）的 APK 下载地址 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $h=@{'User-Agent'='Mozilla/5.0'}; $repo='!REPO!'; $tag='!TAG!'; if(-not $tag){ $r=Invoke-WebRequest -Uri ('https://github.com/'+$repo+'/releases/latest') -Headers $h -UseBasicParsing; $tag=$r.BaseResponse.RequestMessage.RequestUri.AbsolutePath.TrimEnd('/').Split('/')[-1] }; $a=Invoke-WebRequest -Uri ('https://github.com/'+$repo+'/releases/expanded_assets/'+$tag) -Headers $h -UseBasicParsing; $m=[regex]::Match($a.Content,'/[A-Za-z0-9._/-]+\.apk'); if($m.Success){ Write-Output ('https://github.com'+$m.Value) } else { exit 2 }" > "!URLFILE!" 2>nul

set "URL="
for /f "usebackq delims=" %%u in ("!URLFILE!") do set "URL=%%u"
if "!URL!"=="" (
  echo [MatchConsole] 未能获取 Release APK 下载地址。请检查网络，或用 --apk 指定本地 APK。
  exit /b 1
)

echo [MatchConsole] 下载：!URL!
set "APK=!TMP!\MatchConsole.apk"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '!URL!' -OutFile '!APK!' -Headers @{'User-Agent'='Mozilla/5.0'}"
if not exist "!APK!" (
  echo [MatchConsole] APK 下载失败。可改用 --apk 指定本地 APK。
  exit /b 1
)

:install_apk
rem ---------- 5. 安装 ----------
for %%d in (!DEVICES!) do (
  echo [MatchConsole] 安装到设备 %%d ...
  adb -s %%d install -r "!APK!"
)

echo [MatchConsole] 全部完成！两台手机分别插上运行一次本脚本即可。
echo [MatchConsole] 提示：同一 Wi-Fi 下打开 App 选择 接收端 / 发送端 开始使用。
exit /b 0

:usage
echo 用法：
echo   install.bat                              下载最新 Release 并安装
echo   install.bat --apk path\to\app-debug.apk  安装指定 APK
echo   install.bat --tag v1.0                   指定 Release 版本
echo   install.bat --uninstall                  卸载已安装的 App
exit /b 0