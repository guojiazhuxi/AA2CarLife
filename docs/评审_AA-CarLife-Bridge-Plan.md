# 评审意见：AA-CarLife-Bridge-Plan v1.0

> 被评审文档：[AA-CarLife-Bridge-Plan.md](../AA-CarLife-Bridge-Plan.md)（v1.0，2026-11-23）
> 评审日期：2026-11-23 ｜ 关联：[补充分析_AA转CarLife桥接.md](./补充分析_AA转CarLife桥接.md)
> 结论级别：**方向正确，可以进入 P0；但有 3 处关键判断必须先修正，否则 P0 会白测、P1 会返工。**

---

## 0. 总评

这份计划整体质量很高：目标/约束清晰，角色对位（AA 手机端 + CarLife 车机端）正确，直通不重编码的取舍正确，P0 双门禁（多指 / 证书）抓得准，坑清单也基本来自真实项目经验。

以下是我基于公开资料与开源实现（AARE、HSD、CarPToCarL、headswap、aasdk、CarLife 逆向笔记）逐条核对后的意见。**问题不在方案骨架，而在三个"概率/前提"判断，以及 P0 少测了几项。**

---

## 1. 值得肯定的点

1. **双伪装架构是对的**：对车机当 AA 手机端，对本机 CarLife+ 当车机端；这是这个需求唯一不装盒子的可行形态（CarPToCarL 已证明 CarLife 侧可行，AARE/HSD 已证明 AA 手机端可行）。
2. **视频直通 + 分辨率对齐**：正确。重编码会同时吃掉延迟、发热和画质，直通是唯一能把延迟压下来的做法。
3. **P0 先验两个致命未知**：这个方法完全正确，尤其是"多指是车机给的，不是协议给的"这一条，比大多数方案清醒。
4. **附录 D / 审慎声明**：把未决问题、甚至"本文档协议细节不要照抄"写出来，是负责任的做法。
5. **不做通话、不做无线 AA、先保画面与触控**：范围控制得很好。

---

## 2. 关键修正（按重要性排序）

### 修正 A：Q2（车机校验证书）不是"中概率"，是"高概率"；且它有一个硬到期日

计划 §4.2 / §11 把 Q2 概率标为"中"，依据是"部分车机会校验"。但你的车是**奥迪**，有直接实测数据：

- AARE 实测 **2019 奥迪 e-tron（MIB2+ 原厂 MMI）**：AARE 自签证书被拒（`status -3`），**导入 Google 签发的 CarService 证书后才成功**（[AARE #151](https://github.com/prototux/AARE/issues/151)，[证书文档](https://github.com/prototux/AARE/blob/main/docs/certificates.md)）。
- AARE 实测 **2019 奥迪 Q3（ALPINE MIB2+）**：表现更差——AARE 界面出现约 3 秒后弹回车机，日志是 "no version request from head unit"（[#86](https://github.com/prototux/AARE/issues/86)）。
- 你的 MIB3 是更新一代、基于 Android 的车机，使用 Google AA 接收实现的概率更高；**Q2 用自签证书通过的概率我判断 <20%**。

**因此 Q2 必须改成两阶段测试，而不是一个 yes/no：**

| 阶段 | 操作 | 判定与意义 |
|---|---|---|
| Q2a | 装 AARE，**先点掉手机上的「New head unit / 信任这台车机」提示**，再连接 | 失败时看清楚是哪一种：`status -3`（车机拒绝我们的证书）／`-24`（证书过期）／`no version request`（车机根本没进入 AA 协议）／TLS 中断（车机直接挂断） |
| Q2b | 若 Q2a 被拒：在 AARE 里导入一份 **Google 签发的 CarService 证书**（AARE 支持导入/下载，其文档说明了来源与风险），再连一次 | **这一步才是真正决定项目生死的一步**。能连上 → 项目可行（但依赖证书）；仍失败 → 终止 |

**必须现在就知道的两件事**（计划里一个字没提）：

1. 这张证书有**硬到期日**：公开可得的当前版本叶证书 **2026-12-31 到期**，较新的 AA 版本约 **2027-02-17**，且 Google 会轮换/可远程换证。也就是说：**项目还没写完，第一张证书可能就过期了**。设计上必须把证书做成"可导入、可替换、不写死"，并接受"每隔几个月要换一次证书"的维护成本。
2. 这不是"难度陡增"的技术问题，而是**合规红线**：证书/私钥来自 Google 的 AA APK，提取和分发都违反 Google 条款（个人自用与公开分发是两种性质）。参考项目 AARE/HSD 都是在"用户自行提供证书"的框架下运作的——你的方案也应如此设计（App 不内置、不自动分发），否则风险性质会变。

> 结论：Q2 应该在 P0 阶段就跑到"导入证书后能连"为止，而不是把证书当 P1 才处理的备选。**如果 Q2b 也过不了，这项目直接结束，不用写 P1。**

### 修正 B：多指前提要重新取证——CarPlay 单点是"iOS 26 之前"的普遍事实，不是 MIB3 特有

计划 §1.2 把"CarPlay 只支持单点触控"当成这台车的特殊缺陷。实际情况是：**CarPlay 在 iOS 26 之前整体就不支持多点触控**（2025 年 WWDC 才为导航类应用加入多指手势，且要求车机硬件支持；[报道](https://www.computerbase.de/news/mobilitaet/apple-im-auto-navigation-in-carplay-erhaelt-multi-touch-unterstuetzung.93067/)）。这有两个含义：

1. 你的判断"CarPlay 单点"**大概率成立**，但它不是 MIB3 的问题，而是 CarPlay 生态的普遍状态——所以"AA 有双指"的价值判断要更谨慎：**AA 的双指同样取决于车机是否上报多指**（见 Mazda/零跑等社区案例：有的车机 AA 默认只给单指，需要车机侧改动）。Q1 依然是必测项，而且建议同时测两种：
   - 官方 AA + Google 地图，双指捏合；
   - 官方 AA + 高德/支持捏合的地图应用（若车机 AA 支持）；
   并记录 `InputReport` 里 **pointer count** 到底是不是 2（Q1 通过 AARE 的日志/自研探针看更直接）。
2. **收益上限要算清楚**：CarLife 本身**最多两指**（计划 §2.1 已写明，来自 headswap 逆向笔记）。所以就算 AA 转发 5 指，你的桥也只能给 CarLife 两指 → 最终收益 = **双指缩放**。请确认这个收益值不值得后面 1–3 个月的工作量；如果只是"两个手指捏地图"，也可以考虑兜底方案（见 §4 路线 C）。

### 修正 C：不要从零写 AA 手机端——先用"现成件组合"做端到端可行性验证

计划 P1 是"从零实现 AA 手机端最小闭环，1–2 周"。对熟悉协议的人有参考代码时勉强可能，但更稳的做法是先验证"**CarLife 走 AA 上屏、双指可用**"这个端到端命题，再决定是否自研：

**推荐组合（1–3 天，不用写 AA 协议栈）：**

```
┌──────────── 手机 ────────────┐
│ 荣耀车联 CarLife+ 引擎        │
│   │ 7240 劫持（自研 Viewer App，照 CarPToCarL 思路）
│   ▼                          │
│ Viewer App：解码显示 + 转发触控 │  ← 就是一个普通全屏 App
│   ▲ 触摸事件                  │
│   │                          │
│ HSD（现成开源）：AA 手机端发送端 │  ← 已在多协议上验证，支持 AA + 证书配置
│   │ USB AOA                  │
└───┼──────────────────────────┘
    ▼
奥迪 MIB3（AA 车机端）
```

- **HSD**（[SadykovIV/HSD](https://github.com/SadykovIV/HSD)）：开源（GPL 系，以仓库为准），支持 AA 手机端（USB/无线）、CarLife、CarPlay、HiCar，**支持按车机导入证书配置**（`Settings → Certificates → Install from ZIP`），能把手机上的 App（DriveDeck/Android Desktop）投到车机并回传触摸。需要一次性的激活（Shizuku/ADB，非 root）；证书仍要你自己提供（见修正 A）。
- **Viewer App**：自研，只有两件事——(1) 照 CarPToCarL 的方式连本机 CarLife+ 的 7240，劫持 H.264/PCM 并解码显示；(2) 把 HSD 送到 App 的 MotionEvent 转成 CarLife `TOUCH_ACTION` 发回 9340。GPL 传染注意：若复用 CarPToCarL 代码，Viewer 也要开源。
- 验证目标：**车机屏上出现荣耀车联界面 + 能双指缩放 + 音频能出**。过了这一步，再决定：
  - 合并成单 App（自研 AA 协议栈，直通低延迟）——工作量大；
  - 或保持"HSD + Viewer"两 App 组合——最快、最省事、但依赖第三方 App。

> 特别提醒：HSD 的验证价值在于**把"AA 手机端 + 证书"这个最大不确定性用现成代码先跑掉**。如果 HSD 在你的 MIB3 上连 AARE 的证书/导入证书后能出画面，那 Q2b 的答案就有了，P1 的自研才有的放矢。

### 修正 D：协议细节校正清单

计划里这些点需要改或补（否则 P1 一定踩）：

| # | 计划位置 | 问题 | 建议 |
|---|---|---|---|
| 1 | §5.3 帧格式 | `channel/flags/length/[msgId 2B][protobuf]` 只是二手推断 | 以 [opencardev/aasdk](https://github.com/opencardev/aasdk)（维护中，f1xpl 是旧版）或 AARE 的 `aap/` 实现为准；**注意 ~16 KB 分片与加密标志位**，不要假设一帧一消息 |
| 2 | §5.4/§5.6 | 缺少**视频 ack 流控**、Ping 响应、未知消息容错、`VideoConfig` 的 config index / session id 语义 | 参考 mretallack/AndroidAuto 的 "max_unacked 追踪 + backpressure" 与 AARE 的 `aap` 测试；VideoConfig 不合规会导致黑屏/超时（mretallack 的 known bug 里写过） |
| 3 | §5.5 | "先声明后实现是被允许的" | 首版只声明 **video + input**（sensor 可选），不要声明 audio/mic/media browser 等；车机可能等你开通道，声明未实现反而增加失败面 |
| 4 | §5.8 | 音频表写死 48k/2ch、16k/1ch | 必须从车机 `ServiceDiscovery` 的 audioConfig 协商（CarPToCarL 实测奥迪 `100/media` 同时声明 44.1k 与 48k，写死 48k 会被重采样）；首版不做音频的话直接不声明 |
| 5 | §6.2/§6.3 | CarLife 帧格式 `[channel u32][length u32]`、通道名、鉴权分支"含分号 randomValue 走 md5" | 全部以 CarPToCarL / headswap 源码为准（尤其那个 md5 分支，写错就是"连上 30 秒必断"）；注意 CarPToCarL 的端口事实是 **7240 主通道 + 9240 媒体音频 + 9241 TTS + 9340 触控**，不是"六个通道"这么笼统 |
| 6 | §7.2 IDR 策略 | "过期时本机临时编码一个 IDR（解码最近帧→I16x16 压缩→替换原帧）" | v1 不要做这个（成本高、易崩）。CarPToCarL 已验证的更简做法：**空闲重发最后一个 IDR 保活；源重启等新 IDR，4 秒零帧就请求引擎重建编码器**。实在不行再考虑自编码 |
| 7 | §7.3 触控 | 只写了"1:1 映射" | 必须补一条 CarPToCarL 的血泪教训：**CarLife 引擎内部会自己乘 `touchXOffset` 做换算，不要替它算**；坐标按协议域（视频像素）原样透传、只做边界钳位，否则拖拽位移会同比缩小 |
| 8 | §4.4 accessory 探针 | "写 10 行探针 App" | 先确认 `<usb-accessory>` 过滤器的匹配规则（通配/精确值），否则探针收不到 `USB_ACCESSORY_ATTACHED`；更省事的取法：**AARE 的连接日志 / `adb shell dumpsys usb`** 里就有六个字符串 |

### 修正 E：P0 需要补测的 6 件事

| # | 要测的 | 为什么 |
|---|---|---|
| 1 | Q2 两阶段（自签 → 导入 Google 证书），并区分 `-3` / `-24` / no version request / TLS 中断 | 见修正 A |
| 2 | 荣耀手机能否稳定进入 **USB accessory 模式**，以及是否与「荣耀车联 / HiCar」App 抢 accessory 广播 | 计划 §5.1 提了但没列为门禁；若被系统 App 抢走，整个方案不可行 |
| 3 | MIB3 在 `ServiceDiscovery` 里给的分辨率/DPI/driving status、AudioConfig 列表 | 决定 VideoConfig/音频协商参数，别拿 1280×720 硬套（从 AARE 日志里就能读到） |
| 4 | 荣耀车联 CarLife+ 版本、`7240` 是否监听、必须手动进「无线连接」才有真画面 | CarPToCarL 已证明：自动拉起引擎只会出「欢迎页」，必须用户手动点「无线连接」；这一条直接影响产品 UX 与验收标准 |
| 5 | MIB3 的 AA 是否 **允许第三方 App 一直占着 AA 会话**（进原车菜单/倒车影像后能否恢复） | 决定 P1 的焦点状态机设计，以及"到底能不能当日常导航用" |
| 6 | CarLife 的 `INFO` 阶段能否接受**任意分辨率**（含宽屏比例） | 决定"直通"能否成立；不能的话就要回到转码路线（延迟+发热都上一个台阶） |

---

## 3. 对原计划的逐条批注（摘要）

| 原计划位置 | 结论 | 批注 |
|---|---|---|
| §1.1 车机不能装 APK、CarPlay/AA 都有线 | ✅ 采纳 | 与我的信息一致；注意"有线 CarPlay"意味着 CarPToCarL 不能直接用（它只走无线） |
| §2.1 "AAWireless（芯片上连 Android 都没有）" | ⚠️ 证据误用 | AAWireless 是**透明桥接**（转发真手机的 AA 会话），不是"AA 手机端实现"。正确证据：AARE、mretallack/AndroidAuto、HSD、DiLink-Auto（另一个开源 AA 手机端项目） |
| §2.1 "官方 CarLife+ 可被未 root 劫持" | ✅ 采纳 | CarPToCarL 已验证（荣耀 `com.baidu.carlife.honor` 8.8.5） |
| §2.1 "CarLife 最多两指" | ✅ 采纳（来源 headswap 笔记） | 这就是收益上限，见修正 B |
| §2.1 "CarLife 车机端只需公钥验签" | ✅ 方向对 | 但公钥/鉴权分支的细节以 CarPToCarL 源码为准 |
| §4 P0 双门禁 | ✅ 但需扩展 | Q2 改两阶段；补测修正 E 的 6 项 |
| §5.2 TLS 自签 + 不验车机证书 | ✅ 自用可接受 | 建议把车机证书 subject/issuer 打进日志（计划已提），并记录 AUTH 状态码 |
| §5.4 握手时序 | ✅ 大致对 | `AUTH_COMPLETE` 是车机→手机；方向别写反 |
| §6.1 "连 127.0.0.1:7240" | ✅ 对 | 端口集合按 CarPToCarL：7240/9240/9241/9340 |
| §7.1 分辨率对齐 | ✅ 关键 | 但先测 CarLife 是否接受任意分辨率（修正 E-6） |
| §9 工期（P1 1–2 周） | ⚠️ 偏乐观 | 从零写 AA 手机端不现实；用 AARE/HSD 做基线可把 P1 压到 1–2 周 |
| §12 固件路线"建议放弃" | ✅ 同意 | 8W0906961CA 签名校验，变砖风险不值得 |
| §13 参考项目 | ⚠️ 需更新 | 加 HSD / DiLink-Auto / opencardev/aasdk；headswap 是反方向（AA 手机→CarLife 车机），只能当 CarLife 逆向参考，不能当 AA 手机端参考 |

---

## 4. 建议的修订版路线

```
P0（1 天）：
  Q1 多指实测 + Q2 两阶段（自签→导入证书） + 6 项补测
  ├─ Q1 ❌ → 终止（或只做单点，价值不足）
  ├─ Q2b ✅ → 继续
  └─ Q2b ❌ → 终止，买 CarPlay→HiCar/ICCOA 盒子兜底
P0.5（1–3 天，强烈建议）：HSD + 自研 Viewer 组合，验证「CarLife 经 AA 上屏 + 双指 + 音频」
  ├─ 成功 → 有两条路可选：A) 保持组合（快、省事，依赖第三方 App）
  │                          B) 自研单 App 直通（慢、低延迟，参考 AARE/HSD 源码）
  └─ 失败 → 用日志定位：证书？分辨率？触控？再决定是否继续
P1（1–2 周）：AA 手机端最小闭环（以 AARE/HSD 为基线，fork 或参考实现）
P2（1–2 周）：接 CarLife 源 + 双指 + 音频
P3：稳定性（焦点/重连/发热/证书轮换）
```

**兜底路线（建议现在就先买，保证折腾失败也有得用）**：CarPlay→HiCar / CarPlay→ICCOA 盒子（¥200–400，CarlinKit U2H/X1HI 等，支持荣耀/华为手机连接），单点触控但开箱即用。

---

## 5. 参考项目勘误与补充

| 项目 | 原计划描述 | 修正/补充 |
|---|---|---|
| [jjuugk/CarPToCarL](https://github.com/jjuugk/CarPToCarL) | CarLife 劫持、奥迪实车经验 | ✅ 准确；注意其**双指触控是 1.0.7 起实验性支持**、奥迪 MIB3 是无线 CarPlay 验证 |
| [YegorkaPomidorka/headswap](https://github.com/YegorkaPomidorka/headswap) | AA 车机端（反方向） | ✅ 反方向没错；其 CarLife 逆向笔记可参考，但**不能当 AA 手机端参考** |
| [SadykovIV/HSD](https://github.com/SadykovIV/HSD) | （未提） | **新增**：Headswap 的延续项目，手机端多协议发送端（AA/CarLife/CarPlay/HiCar），支持证书配置，Android 10+，开源——最适合做 P0.5 验证/参考 |
| [andersonlucasg3/DiLink-Auto](https://andersonlucasg3.github.io/dilink-auto-android/) | （未提） | **新增**：另一个开源 AA 手机端替代（任意 Android 手机、支持多点触控与双指缩放），可参考其 AA 实现 |
| [f1xpl/aasdk](https://github.com/f1xpl/aasdk) | AA 协议栈与 .proto | ⚠️ 旧仓库；维护版是 [opencardev/aasdk](https://github.com/opencardev/aasdk)（250+ proto，GPL-3.0） |
| [nisargjhaveri/WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle) | AA 手机端（Linux gadget） | ⚠️ 它本质也是"车机侧透明桥"思路，对"手机 App 扮演 AA 手机"参考价值有限（可参考 USB/AOA 处理） |

---

## 6. 风险与合规（个人自用边界）

- **证书**：这是唯一无法绕过的外部依赖。个人自用（自己提取、自己导入、不内置、不分发）与"做成软件给别人用"性质不同；**不要内置、不要自动下载、不要公开分发证书或密钥**。
- **到期日**：2026-12-31 / 2027-02-17（随 AA 版本），必须在 App 里做证书导入/替换与到期提醒。
- **协议方**：CarLife+ 是百度/荣耀的封闭实现，劫持属于非官方行为，随版本失效；CarPlay/AA 是各自商标，文档与 App 不要使用其认证标识。
- **驾驶安全**：行驶中只做导航类展示；配置、调试一律停车。
- **开源许可**：AARE/HSD/CarPToCarL 都是 GPL 系，自用无碍；任何分发/二次发布必须开源并保留声明。

---

## 7. 需要你补充的信息（影响判定）

1. **车型年款**与 MIB3 具体版本（如 2021 A4L / 2023 A6L）——决定是否用 Google AA 接收实现、以及 AA 版本。
2. 有没有 **iPhone + iOS 26** 可以测 CarPlay 双指？（用来对照"CarPlay 单点"的结论）
3. 有没有**带 GMS 的安卓手机**可以测 Q1（官方 AA + 双指）？
4. 荣耀机型 + MagicOS 版本；是否接受 HSD 那种**一次性的 Shizuku/ADB 激活**（非 root）？
5. 你能接受"**每几个月换一次证书**"的维护成本吗？（这直接决定项目值不值得做）

---

## 附：一句话结论

> 方案骨架成立，但请把 P0 从"两个 yes/no"升级为"**Q2 两阶段跑到导入证书能连**"，并先花 1–3 天用 **HSD + 自研 Viewer** 验证端到端；如果这两步都过，再投入自研 AA 直通，否则及时止损。
