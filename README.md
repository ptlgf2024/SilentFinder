# SilentFinder 静音寻车

一加手机三段式静音键触发台铃（TAILG）电动车寻车模式的 Android 工具。

**当前版本 1.0**（versionCode 2）

## 原理

一加手机侧面的三段式按键拨到**静音档**时，系统会发出铃声模式变更广播。本应用以后台服务常驻监听该广播，检测到切换为静音模式后，向台铃服务器发送寻车指令请求（与官方 App `okhttp/4.2.2` 抓包请求一致），服务器即下发寻车命令，电动车鸣响闪灯。

## 功能

- **无通知栏后台服务**常驻内存（30 秒看门狗闹钟自动拉起 + 开机自启 + 销毁自愈）
- 仅**静音档**触发请求；震动档、正常档只记录日志不触发
- 单按钮切换启动/停止；打开 App 自动启动监听
- **进程级日志存储**（`LogStore`）：App 退到后台期间产生的监听、请求、响应日志不丢失，回到界面全量恢复
- 界面实时显示运行日志：监听事件、HTTP 请求、响应（自动解压 gzip），保留最近 500 条
- **首次运行引导**：弹窗引导设置耗电管理（完全允许后台行为）+ 自启动，附 ColorOS 具体路径，可一键跳转
- 现代化自适应图标（蓝色渐变 + 白色铃铛 + 红色静音斜杠，全密度 + Android 8+ 自适应层）
- `authorization` 可在 App 内菜单随时更新（存于 SharedPreferences），无需重新编译

## 权限（仅 3 个）

```
INTERNET                              网络请求
RECEIVE_BOOT_COMPLETED                开机自启
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS  申请电池优化白名单
```

## 请求内容

```
POST /v1/api/app/device/cmd/search HTTP/2
host: www.tailgdd.com
authorization: <URL 编码的加密 token，菜单中配置>
forward-service-ip: localhost
language: zh_CN
content-type: application/json; charset=UTF-8
accept-encoding: gzip
user-agent: okhttp/4.2.2

{"imei":"<车辆控制器 IMEI，菜单中配置>"}
```

使用 OkHttp 4.12（支持 HTTP/2，与抓包协议一致）。

## 使用

1. 安装 `SilentFinder.apk`，打开应用
2. 首次运行按引导完成两项设置（之后也可从 ☰ 菜单进入，以下为 ColorOS 路径）：
   - 耗电管理：设置 → 应用 → 应用管理 → 静音寻车 → 耗电管理 → 选「**完全允许后台行为**」
   - 自启动：设置 → 应用 → 自启动 → 允许「静音寻车」
3. 在 ☰ 菜单填入抓包获得的 `authorization` 和车辆 `IMEI`
4. 拨三段式键到静音档 → 车辆响铃闪灯

## 目录结构

```
app/
├── README.md
├── okhttp-4.12.0.jar / okio-jvm-3.6.0.jar / kotlin-stdlib-1.8.21.jar  # 依赖
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/                            # 图标（全密度 + 自适应）
    └── java/com/example/silentmonitor/
        ├── MainActivity.java           # 主界面：状态卡片、启停按钮、日志区、菜单
        ├── SilentModeService.java      # 后台监听服务 + OkHttp 请求 + 看门狗
        ├── BootReceiver.java           # 开机自启
        └── LogStore.java               # 进程级日志存储
```

## 说明

- `authorization` token 与 IMEI 来自官方 App 抓包，均在 App 内菜单配置（存于 SharedPreferences），无需重新编译；token 有时效，过期后服务端返回 `401 认证失败`，重新抓包后在菜单中粘贴新值即可
- 编译方式（无 Gradle，手动工具链）：
  `javac`（JDK 17，-source 8）→ `aapt2 link` → `d8`（打入 okhttp/okio/kotlin-stdlib）→ `zipalign` → `apksigner` 签名
- 签名密钥：`build/release.keystore`（alias `silentmonitor`，密码 `android1234`），换密钥会导致无法覆盖安装

## 免责声明

仅供个人对自己名下设备的使用与研究学习，请勿用于非法用途。
