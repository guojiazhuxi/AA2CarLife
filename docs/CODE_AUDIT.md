# AA2CarLife 代码审计报告

> 快照：`main @ 6cd6ccc`（2026-11-26）。审计阶段未改业务代码；P1 修复记录见第 8 节，清理阶段见第 9 节。

## 1. 审计范围与方法

- 范围：`app/` 的 18 个 Kotlin 文件、资源、Manifest、Gradle 配置；`aap/` 只看集成与安全边界，生成 protobuf 不计入死代码。
- 方法：全仓引用扫描、声明引用计数、Android Lint、并发/生命周期人工审查。
- Lint：`./gradlew :app:lintDebug`，结果 **1 error + 78 warnings**。
  唯一 error 在本机未跟踪的 `local.properties`（Windows 路径转义），不是仓库源码问题。

## 2. 结论摘要

- **死代码/不可达分支**：约 250–300 行集中在 MainActivity 的旧触摸实验模式。
- **完全未使用**：Compose 主题 3 个文件、Compose 插件/依赖、2 个模板测试、3 个协议常量、若干方法。
- **未使用资源**：Lint 报告 24 个（15 个 string、7 个 color、2 个 xml）。
- **高风险问题**：日志线程安全、服务启停竞态、触摸线程池关闭竞态、音频暂停未生效、转码管线 release 不 join、AA 音频通道可能被视频分支跳过。
- **构建/发布**：Compose 依赖未使用，APK 体积和构建时间有优化空间；Lint 未通过；无真实测试。

## 3. 死代码与不可达分支

| 位置 | 内容 | 证据 | 建议 |
|---|---|---|---|
| `MainActivity.kt:91` | `tapSendSingleClick = false` | 只在 `1469-1474` 的不可达分支读取 | 删除字段和分支 |
| `MainActivity.kt:102` | `allComposite = false` | 分支 `1337/1384/1459/1516` 永远不会进入 | 删除字段和所有分支 |
| `MainActivity.kt:105` | `moveWithIndex = false` | 只在 `1379` 不可达分支读取 | 删除 |
| `MainActivity.kt:108-123` | `pinchTranslate` 及 `translating/translate*`、`translateSecondDownRunnable` | `pinchTranslate` 永远 false；`1233-1321` 整段不可达；`pinchDistance`（约 1535）只被这段使用 | 删除约 100 行及 `TRANSLATE_*` 常量 |
| `MainActivity.kt:129-132` | `newTouchMode` 及 `newTouchLastX/Y/At` | 永远 false；`1184-1229` 整段不可达 | 删除约 50 行 |
| `MainActivity.kt:135` | `officialGestureMode = true` | 没有 UI 再修改；单指始终走官方手势 | 去掉分支判断，或保留常量并注释 |
| `MainActivity.kt:126` | `cptclPipeline = true` | 没有 UI 再修改；非 CPTCL 的 else 分支不可达 | 去掉字段和 else 分支 |
| `MainActivity.kt:202` | `resolutionChoiceIndex` | 仅声明，无读写 | 删除 |
| `MainActivity.kt:950-953` | `applyResolutionChoice()` | 只在 `238` 调用，永远传 `null,null` | 连同 `238` 调用一起删除 |
| `MainActivity.kt:162/285` | `statusText` | 只赋值，无读取；状态已走 `statusDetail` | 删除字段和 findViewById |
| `CarLifeService.kt:253-274` | 手动分辨率字段/方法 | `setResolutionOverride` 只被 `applyResolutionChoice` 以 null 调用 | 删除手动分辨率路径；保留自动/记忆路径 |
| `CarLifeService.kt:538-540` | `resolutionOverride != null` 日志分支 | 该字段永远 null | 随手动分辨率一起删 |
| `CarLifeAudio.kt:105` | `onMediaPause()` | 无调用点 | 接入暂停/恢复，或删除 |
| `CarLifeProtocol.kt:106` | `packStream()` | 无调用点 | 删除，或标注为协议保留 |
| `CarLifeProtocol.kt:55-56,85` | `TOUCH_LONG_PRESS`、`TOUCH_HARD_KEY`、`TTS_DEFAULT_RATE` | 全仓引用次数为 1（声明本身） | 删除或注释保留 |
| `RingLog.kt:75` | `clear()` | 无调用点（清空按钮已删） | 删除 |
| `app/src/main/java/com/hobby/aa2carlife/ui/theme/*` | Color/Theme/Type 三个 Compose 主题文件 | 无任何 `@Composable` 使用；App 用 XML/View | 删除整个 `ui/theme` |
| `app/src/test/.../ExampleUnitTest.kt` | `2 + 2` 模板测试 | 无业务断言 | 删除或替换为真实测试 |
| `app/src/androidTest/.../ExampleInstrumentedTest.kt` | 包名模板测试 | 无业务断言 | 删除或替换 |

常见误报：`onBind/onStartCommand/onServiceConnected` 等 override 被引用计数当成 1，实际上是框架回调，不是死代码。

## 4. 冗余代码与构建冗余

- **Compose 完全未使用**：`app/build.gradle.kts` 仍启用 `kotlin.compose`、`buildFeatures.compose`，并引入 Compose BOM、material3、ui、tooling、activity-compose；App 实际是 View/XML。建议删除插件、Compose 依赖、`ui/theme`，可减小 APK 和构建时间。
- **生命周期/Activity Compose 依赖**：`lifecycle-runtime-ktx`、`activity-compose` 无直接使用，可随 Compose 一起删。
- **未使用资源**：Lint 共 24 个：`btn_start/stop/clear/copy_log`、`chk_new_touch/all_composite/move_index/pinch_translate/tap_single_click`、`btn_aa_bench/selftest`、`hint_log_copy/advanced/advanced_hide/usb_auto`、7 个颜色、`backup_rules.xml`、`data_extraction_rules.xml`。
- **重复状态字段**：`statusText` 与 `statusDetail` 同时存在，前者已无用。
- **重复触控实现**：官方手势、CPTCL、新模式、模式B、捏合翻译五套逻辑共存在一个 `handleTouch`；实际只走官方手势 + CPTCL，其余是历史实验。
- **废弃 API 检查**：minSdk=31，但代码仍保留多个 `SDK_INT >= 23/26/29/30` 分支（Lint 标了 7 处），可直接删除。
- **未使用权限/配置**：`backup_rules.xml`、`data_extraction_rules.xml` 未被 Manifest 引用；`allowBackup=false` 下应删除或显式接 `dataExtractionRules`。

## 5. 主要问题（按严重度）

### P1 — 影响稳定性/功能

1. **RingLog 的 SimpleDateFormat 非线程安全**
   - `RingLog.kt:27` 的 `SimpleDateFormat` 被视频、音频、触摸、主线程同时调用；
   - 可能产生错误时间、`NumberFormatException`，甚至影响调用线程；
   - 建议改为 `synchronized(fmt)`、`DateTimeFormatter`，或让单写线程负责格式化。

2. **CarLifeService 启停竞态**
   - `startSession()` 在后台线程探测/建流（`497-513`），`stopSession()`（`617-631`）可在建流过程中执行；
   - `stopSession` 没有取消 start 线程，也没有把 `startInProgress` 复位为 false；
   - 结果可能是：用户点断开后，start 线程又把 `client/transcode/audio` 建起来，留下幽灵会话和唤醒锁；
   - 建议用单一 `sessionLock`/`Job` 管理，stop 时取消 start future 并等待收尾。

3. **触摸线程池关闭竞态**
   - `CarLifeHuClient.stop()` 执行 `touchExecutor.shutdown()`（`128-134`）；
   - `enqueueTouch()`（`514-547`）先检查 `running` 再 `execute`，两者之间 stop 会导致 `RejectedExecutionException`，而它在 UI/触摸调用链上，可能闪退；
   - 建议 catch `RejectedExecutionException`，或在 shutdown 前置 `running=false` 并丢弃。

4. **媒体暂停/恢复/停止没有作用到本地 AudioTrack**
   - `CarLifeHuClient` 对 `MUSIC_PKG_PAUSE/RESUME/STOP` 只打日志（`431-433`）；
   - `CarLifeAudio.onMediaPause()` 无调用点；
   - 本地播放会继续出声/继续缓存，和 CarLife 状态不一致；
   - 建议把这三个包接到 `CarLifeAudio` 的 pause/play/stop，并处理 flush。

5. **TranscodePipeline.release() 不等待线程结束**
   - `TranscodePipeline.kt:101-105` 只置 running=false、interrupt，没有 `join`；
   - `CarLifeService.recreateTranscode()` 会立刻创建新管线，旧 GL 线程可能仍在释放/使用 EGL、MediaCodec、Surface；
   - 建议 release 后 join（带超时）再建新管线，或复用单例管线。

6. **AA 音频通道可能永远不打开**
   - `AaHeadUnitServer.onServiceDiscovery` 的 `when`（`803-816`）先判断 videoConfigs；
   - 若车机的 media sink 同时带 videoConfigs 和 audioType，只创建 VideoChannel，AudioChannel 分支被跳过，`mediaAudio` 保持 null；
   - 这与日志中的“收到 CarLife 音频但媒体通道未就绪”一致，也是车机音量只显示导航的候选原因；
   - 建议分别处理视频/音频能力，必要时同一 service 开两个通道；先用服务发现日志实测确认。

7. **audio 引用/采样率字段跨线程可见性不足**
   - `CarLifeService.audio`（`73`）不是 volatile，`lastMediaRate/lastTtsRate`（`79-82`）也非 volatile；
   - 主线程切换本地声音与 CarLife 回调线程读写存在竞态；
   - 建议 audio 标 `@Volatile`，或把音频状态收进同步对象/单线程。

### P2 — 功能/安全/维护风险

- `CarLifeAudio.write()`（`152-163`）对 `AudioTrack.write` 部分写入只计数，不重试剩余字节，可能丢 PCM；建议循环写或记录并重建。
- `CarLifeHuClient.videoReader()`（`354-380`）和 `cmdReader()`（`156-175`）直接按报文长度 `ByteArray(length)`，没有上限；损坏/恶意 localhost 流可导致 OOM 或负长度异常。
- `AaHeadUnitServer.start()`（`341-374`）先 `running=true` 再在子线程创建 ServerSocket；绑定失败时 running 仍为 true，后续 start 被挡住；建议成功绑定后再置 true。
- `AaHeadUnitServer.sendVideoFrame()`/`sendAudioPcm()` 与 `stop()` 无统一锁；停止时仍可能向已释放通道发送，建议统一生命周期锁。
- `wakeLock.acquire()` 无 timeout（Lint WakelockTimeout）；若会话异常结束未走 stopSession，CPU 唤醒锁可能长期持有；建议带超时/在 onState 结束路径释放。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 会触发 Play 政策警告；个人项目可保留，但公开上架需移除/说明。
- `AcceptAll` X509TrustManager 位于 `aap/.../Cryptor.kt:200`；未配置 PinStore 时接受任意车机证书（AARE 的兼容策略）。USB 本地场景风险较低，但公开项目应说明或启用 pinning。
- 证书私钥 `phone_key.pem` 已进 Git（按你的要求）；GitHub secret scanning/Google 条款风险仍在。
- 无真实测试：两个测试都是模板；建议至少覆盖触摸坐标映射、协议帧编解码、显示档案 key、音频重采样。
- Lint 未通过：`local.properties` 路径转义错误（本机环境，不在仓库），导致 `lintDebug` 失败；修正后可把 Lint 接入 CI。

### P3 — 代码质量/资源

- 布局硬编码文本 11 处（Lint HardcodedText），建议移到 `strings.xml`。
- `9sp/10sp` 小字号 3 处（Lint SmallSp），日志可接受，提示文字建议 ≥11sp。
- 外层 ScrollView 内嵌日志 ScrollView（Lint NestedScrolling）；运行时已用 `requestDisallowIntercept` 处理，但结构上仍建议改成固定日志区或 NestedScrollView 方案。
- `logScroll`/`PreviewLayout` 的 OnTouchListener 未调用 `performClick`（Lint ClickableViewAccessibility），可补无障碍处理。
- `statusText` 重复、`btn_advanced` 等旧字符串残留、`colors.xml` 模板色残留。
- Manifest `android:label` 冗余、固定 `screenOrientation=portrait`、`allowBackup` 与新备份规则不一致。

## 6. 建议清理顺序

1. 先修 P1 竞态/功能问题（RingLog、Service 启停、触摸池、音频暂停、转码 join、AA 音频通道）。
2. 删除 MainActivity 的新触摸/捏合翻译/模式B/手动分辨率死分支，拆出 `TouchController`。
3. 删除 Compose 主题/依赖/测试模板和 24 个未使用资源。
4. 修 Lint error，清 HardcodedText/SmallSp/无障碍警告。
5. 增加最小单测和 CI（build + lint + unit test）。

## 8. P1 修复记录

修复提交：`4bfa7b6`。

- ✅ RingLog 改用 `DateTimeFormatter`，移除线程不安全的 `SimpleDateFormat`。
- ✅ CarLifeService 增加 `sessionLock + sessionGeneration + startThread`：stop 会取消启动、等待建流结束，避免幽灵会话。
- ✅ CarLifeHuClient 触摸入队捕获 `RejectedExecutionException`，停止中的迟到触摸不再崩 UI。
- ✅ 媒体 PAUSE/RESUME/STOP 与 TTS STOP 已接到 `CarLifeAudio`；本地 AudioTrack 会暂停/恢复/停止并 flush。
- ✅ `CarLifeAudio` 增加 `released` 标志，释放后的旧回调不会重新创建 AudioTrack。
- ✅ TranscodePipeline.release() 会 join GL 线程（1.5s 超时），避免旧/新管线重叠。
- ✅ `audio`、`client`、`lastMedia*` 已加 `@Volatile`，改善跨线程可见性。
- ⚠️ AA 音频通道：已加服务发现诊断（同时带视频/音频、未找到 MEDIA 通道时明确告警）；没有在未实测车机服务列表前强行双开通道，避免破坏 AAP 的 service/id 约束。

修复后验证：`:app:assembleDebug` 成功；重新跑 Lint 结果 **0 errors / 78 warnings**（本机 `local.properties` 路径已修正，该文件不进 Git）。

剩余 P2/P3 未动：死代码清理、Compose 依赖/主题删除、24 个未使用资源、模板测试、硬编码文案、无障碍/小字号等。

## 7. 备注

- 本报告是快照；行号以当前 `main` 为准。
- `aap/` 是 vendored AARE，生成 protobuf 和框架代码不按死代码处理；只记录 TrustAll 等集成风险。
- 本次未改业务代码，避免审计和修复混在一起。
## 9. 清理阶段记录

清理提交：`11257dd`。

- 删除 MainActivity 的新触摸、捏合翻译、模式B、MOVE 索引、单击补发、手动分辨率、无用 statusText 等死代码/死分支。
- 删除 Compose 插件、Compose BOM/material3/ui/tooling/activity-compose/lifecycle 依赖，以及 `ui/theme` 三个模板文件；App 只保留 View/XML。
- 删除 24 个未使用资源（15 string、7 color、backup_rules、旧 data_extraction_rules）；重新加入被 Manifest 引用的 dataExtractionRules。
- 布局硬编码文案移入 strings.xml；日志/提示字号提升到 11sp；补充无障碍点击和 NestedScrolling 抑制说明。
- 增加 `CarLifeProtocolTest`（4 个测试）和 GitHub Actions `Android CI`（assemble + unit test + lint）。

验证结果：

- `:app:assembleDebug` 成功；
- `:app:testDebugUnitTest`：4/4 通过；
- `:app:lintDebug`：**0 errors / 9 warnings**；
- APK 体积从约 **34.1MB → 8.0MB**（Compose 依赖移除后）；
- 已安装并在手机上启动（2026-11-26 01:21:39），UI 正常。

剩余 9 条 Lint warning 属于有意保留或上游 AARE：

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的 Play 政策提醒（个人项目需要后台保持会话）；
- 固定竖屏（车机投屏界面按竖屏设计）；
- AARE 的 `AcceptAll` X509TrustManager（vendored，未配置 pinning）；
- Wakelock 无 timeout；
- AGP/Kotlin 有更新版本。

仍未处理的功能/安全项：AudioTrack 部分写入重试、报文长度上限、AA 音频通道选择（需服务发现日志）、TLS pinning、Wakelock 超时。
## 10. P2 前五项修复记录

修复提交：`4bf0371`。

- ✅ CarLifeAudio 部分写入重试：按 offset 续写，250ms 超时；超时才丢弃剩余字节并计数，避免静默丢 PCM。
- ✅ 报文长度上限：视频本来已有 8MB 检查；补充控制通道 16KB 上限（U16 长度本身最大 64KB）。
- ✅ AaHeadUnitServer.start：先成功绑定 ServerSocket 再置 running=true；start/stop 加 `@Synchronized`，绑定失败不再卡死后续启动。
- ✅ 视频发送与 stop 竞态：codec config/frame 发送加 runCatching；失败只记一次日志，不再让异常冒到 CarLife 线程。
- ✅ Wakelock：改为 10 分钟 timeout，8 分钟自动续期；release 时移除续期回调。

修复后验证：

- assembleDebug 成功；
- 4 个单测通过；
- Lint：0 errors / 8 warnings；
- 已安装并启动（2026-11-26 01:34:41）。

剩余 8 条 warning：电池优化权限政策提醒、固定竖屏 2 条、AARE TrustAll 3 条、AGP/Kotlin 版本更新 2 条。