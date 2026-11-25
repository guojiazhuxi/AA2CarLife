# AA2CarLife · M1 源码（CarLife 单侧直通）

> 目标：**不依赖车机/AA**，在手机上连本机荣耀车联/CarLife+ 引擎（`127.0.0.1:7240…`），走 CarLife 协议 3.0，收 H.264 并在 SurfaceView 里解码上屏，同时把手机触摸经 9340 回传给引擎。
> 这是 [开发手册](../../docs/开发手册.md) 的 M1 里程碑实现。

## 一、融合进 Android Studio（推荐：先建空工程再放源码）

### 1. 新建工程

Android Studio → **New Project** → **Empty Views Activity**（不要选 Compose）→

| 项 | 值 |
|---|---|
| Name | `AA2CarLife` |
| Package name | `com.hobby.aa2carlife` |
| Language | **Kotlin** |
| Minimum SDK | **API 31 (Android 12)** |
| Build configuration language | Kotlin DSL |

创建后等它完成**首次 Gradle Sync**（会下载 Gradle/AGP/Kotlin，几分钟到几十分钟）。

### 2. 覆盖/新增文件

把本目录的源码拷进你的工程（同名覆盖）：

```
app/src/main/AndroidManifest.xml                     ← 覆盖（或按注释合并）
app/src/main/res/layout/activity_main.xml            ← 新增/覆盖
app/src/main/res/values/strings.xml                  ← 覆盖
app/src/main/res/xml/accessory_filter.xml            ← 新增（M2 用，先放着）
app/src/main/java/com/hobby/aa2carlife/MainActivity.kt        ← 覆盖
app/src/main/java/com/hobby/aa2carlife/CarLifeService.kt      ← 新增
app/src/main/java/com/hobby/aa2carlife/carlife/*.kt           ← 新增
app/src/main/java/com/hobby/aa2carlife/diag/*.kt              ← 新增
```

- 模板自带的 `MainActivity.kt` 直接覆盖；如果模板用的是 `fragment`/`nav` 相关文件，可以删除，不影响；
- 模板的 `res/layout/activity_main.xml` 覆盖为我们的布局；
- **不需要**新增任何第三方依赖（只用 Android 框架自带的 API）；如果你建工程时带了 Compose 依赖，可以删掉，不影响编译；
- 若 IDE 提示 `Activity` 类或主题报错，把 `themes.xml` 里的父主题改成 `@android:style/Theme.Material.Light.NoActionBar`（我们的 Manifest 已直接指定框架主题，可忽略模板主题文件）。

### 3. 权限确认

Manifest 已声明：`INTERNET`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`android.hardware.usb.accessory`（feature，`required="false"`）。首次运行请在系统弹窗里允许通知权限（前台服务需要）。

### 4. 编译运行

```
Run ▶ app
```

第一次运行后，按开发手册 §4.3 的 M1 流程操作（见下）。

## 二、M1 使用步骤（车上，只连蓝牙、不插 USB）

1. 手机蓝牙与奥迪 MIB3 连接（荣耀车联的 WLAN 引导页需要）；
2. 打开 **荣耀车联 → CarLife+ 增强版 → 无线 WLAN 连接**，停在「正在连接」界面（不要退出）；
3. 切回本 App（AA2CarLife），点 **启动**；
4. 状态栏出现：
   `CARLIFE: 版本匹配成功（协议 3.0）` → `收到手机设备信息` → `手机确认编码参数` → `首帧`；
5. SurfaceView 出现荣耀车联的车机界面；在画面上点/拖/双指捏合，应能在手机上看到 CarLife 界面响应；
6. 日志区可查看/复制；断线会自动重连。

## 三、M1 已知限制（刻意留到后续里程碑）

- 不做 AA 桥接（M2/M3）：目前触摸来自手机屏幕，不是车机；
- 不做音频（M5）；
- 不做焦点/倒车/仪表盘；
- 解码器遇到分辨率变化需要重启会话（M5 再处理）；
- 荣耀车联/CarLife+ 版本升级可能改协议：见开发手册 §5.3 排错。

## 四、排错（先看 App 内日志）

| 现象 | 处理 |
|---|---|
| `端口 7240 连接失败` | 荣耀车联没停在「正在连接」；先打开它并点无线连接 |
| `15s 内未收到版本匹配` | 引擎被 4.0 协议污染：到设置里强制停止荣耀车联/CarLife+ 后重试（adb：`am force-stop com.baidu.carlife.honor`） |
| 收到 `65642`（加密握手） | 引擎进了加密路径：确认发送的是 3.0、`CONTENT_ENCRYPTION=0`；重启引擎 |
| 有 `65544` 但黑屏 | 没有首帧/IDR；看日志是否 `首帧`；重启引擎重试 |
| 双指无效 | 单指走单点消息、双指必须走 `0x68001` 复合动作（已实现）；若仍无效，记录日志发我 |

## 五、许可与来源

- `carlife/CarLifeProtocol.kt` 与协议流程参考并移植自 [CarPToCarL](https://github.com/jjuugk/CarPToCarL)（**MIT**，Copyright (c) 2026 lizhihua），见 [NOTICE.md](NOTICE.md)；
- 其余代码为本项目自研，随主仓库以 GPL-3.0 发布（个人项目）。
