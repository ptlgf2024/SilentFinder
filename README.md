# SilentFinder 静音寻车

一加手机三段式静音键触发台铃（TAILG）电动车寻车模式的 Android 工具。

**当前版本 2.22**（versionCode 34）

## 原理

一加手机侧面的三段式按键（静音/震动/正常）拨动时，系统会发出铃声模式变更广播。本应用以后台服务常驻监听该广播，按拨动手势向台铃服务器发送不同指令：拨到静音档发寻车、快速双拨震动档发启动、震动档保持不动发停止。

## 功能

- **无通知栏后台服务**常驻内存（30 秒看门狗闹钟自动拉起 + 开机自启 + 销毁自愈）
- 单次拨到**静音档** → 发送寻车指令，车辆鸣响闪灯
- **2 秒内连续两次**拨到震动档 → 发送车辆启动指令
- 拨到震动档并**保持不动 3 秒** → 发送车辆停止指令（快速双拨在计时到点前已触发启动，不会误停；拨离震动档即取消停止）
- 正常档不触发任何请求
- 单按钮切换启动/停止；打开 App 自动启动监听
- **进程级日志存储**：App 退到后台期间产生的日志不丢失，回到界面全量恢复（保留最近 500 条）
- **首次运行引导**：弹窗引导设置耗电管理 + 自启动
- 现代化自适应图标（蓝色渐变 + 白色铃铛 + 红色静音斜杠）

## 权限（仅 3 个）

```
INTERNET                              网络请求
RECEIVE_BOOT_COMPLETED                开机自启
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS  申请电池优化白名单
```

## 使用

1. 安装 APK（从 [Releases](https://github.com/ptlgf2024/SilentFinder/releases/latest) 下载最新版），打开应用
2. 首次运行按引导完成两项设置（ColorOS 路径）：
   - 耗电管理：设置 → 应用 → 应用管理 → 静音寻车 → 耗电管理 → 选「**完全允许后台行为**」
   - 自启动：设置 → 应用 → 自启动 → 允许「静音寻车」
3. 在 ☰ 菜单填入抓包获得的 `authorization` 和车辆 `IMEI`
4. 拨三段式键到静音档 → 车辆响铃闪灯；2 秒内双拨震动档 → 车辆启动；拨到震动档保持不动 3 秒 → 车辆停止

## 系统要求

Android 7.0（API 24）及以上。默认针对 ColorOS（一加/OPPO）调优，其他品牌手机三段式静音键行为可能不同。

## 数据来源

`authorization` 与车辆 `IMEI` 均来自台铃官方 App 的抓包：

- 官方 App 发起寻车时，请求头中的 `authorization` 即 token（有时效，过期后需重新抓包）
- 请求体 `{"imei":"..."}` 中的值为车辆控制器 IMEI

两者均在 App 内 ☰ 菜单中配置，存于 SharedPreferences，无需重新编译。

## 编译

无 Gradle，手动工具链，环境要求：

- JDK 17
- Android SDK：build-tools 34.0.0、platform android-34

```
javac -source 8 → aapt2 link（--min-sdk-version 24 --target-sdk-version 34）→ d8（打入 okhttp/okio/kotlin-stdlib）→ zipalign → apksigner 签名
```

签名密钥不在仓库中（`.gitignore` 已排除 `*.keystore`），自行生成：`keytool -genkeypair -v -keystore release.keystore -alias silentmonitor -keyalg RSA -keysize 2048 -validity 10000`。注意：已有安装包覆盖更新必须使用同一密钥。

## 免责声明

仅供个人对自己名下设备的使用与研究学习，请勿用于非法用途。
