# M2：AA 传输层台架验证记录（2026-11-24）

## 结论：AA 手机端（TCP 开发通道）打通 ✅

在手机 App 中实现了 **Android Auto 手机端**（AARE `aap` 模块，GPL-3.0），通过
**TCP 5277**（与 Google AA 的 "developer head unit server" 同款通道）与桌面车机
（AARE `HeadUnitClient`）完成：

```
版本协商 → TLS → 服务发现 → 视频通道 → 输入通道 → 正常结束
```

## 台架组成

| 组件 | 位置 | 说明 |
|---|---|---|
| 手机端 AA 实现 | `aa2carlife/aap`（AARE aap 模块）+ `app/.../aa/AaHeadUnitServer.kt` | TLS 服务端、服务发现、视频/输入通道 |
| 证书 | `app/src/main/assets/aap/phone_chain.pem` + `phone_key.pem` | 从 Android Auto 18.0 APK 提取，有效期到 **2027-02-17**（仅本地个人研究用） |
| 测试视频 | `app/src/main/assets/aap/test.264` | M2 阶段先推测试图；M3 换成 CarLife 实时帧 |
| 桌面车机 | AARE `HeadUnitClient`（`bench/aare-aap` 构建） | `java -cp <testcp> net.prototux.aare.aap.sim.HeadUnitClientKt out.h264 12 1280x720 tap:640,360@4` |

## 操作步骤（已验证）

1. 手机上 App → 点「AA台架」按钮 → 日志：`AA: 台架服务已启动：127.0.0.1:5277`
2. PC：`adb forward tcp:5277 tcp:5277`
3. PC：运行 HeadUnitClient

## 实测结果（2026-11-24 01:15）

手机端日志：

```
AA: 台架服务已启动：127.0.0.1:5277
AA: 车机连接来自 /127.0.0.1:33341
AA: 会话就绪，车机版本=(1, 6)
AA: 视频配置 0: VIDEO_1280x720 VIDEO_FPS_30fps dpi=160
AA: 视频开始 index=0 VIDEO_1280x720
AA: 视频焦点 PROJECTED（开始推流）
AA: 触摸 action=0 x=640 y=360 id=0
AA: 触摸 action=1 x=640 y=360 id=0
AA: 已发送 150 帧，丢弃 0
AA: 已发送 300 帧，丢弃 0
AA: 会话结束（RUNNING）- head unit ended the session (USER_SELECTION)
```

桌面车机侧：

```
frames=353 bytes=656095 codecConfigs=2 audio=0 pings=11
channels=[1, 2, 3, 4, 6, 7] error=null
phone: AA2CarLife / Android
[4.1s] tap:640,360
```

## M2 验收清单

| 编号 | 项目 | 状态 |
|---|---|---|
| M2.1 | USB/AOA 识别 | ⏳ TCP 开发通道已验证；USB AOA 留到实车接入 |
| M2.2 | TLS 握手 | ✅（证书来自 AA 18.0；DHU 对同证书返回 `Verify returned: ok`） |
| M2.3 | 版本协商 | ✅（协商到 1.6/1.7） |
| M2.4 | 服务发现 | ✅（视频/输入通道被车机接受） |
| M2.5 | 视频通道 | ✅ 测试图 353 帧 / 656KB，`codecConfigs=2`，无错误 |
| M2.6 | 输入通道 | ✅ tap 下发 → 手机端收到 action=0/1 @ (640,360) |
| M2.7 | 音频通道 | ⏳ 待接 CarLife PCM（下一步） |
| M2.8 | 稳定性 | ✅ 12 秒会话，pings=11，正常结束无 error |

## 补充：DHU / 证书实验

- Google DHU 连接手机原生 AA：TCP 连上但卡在 "Waiting for phone..."（AA 18 与 2022 版 DHU 兼容性问题，不影响本台架）。
- 用提取的 Google 证书 + AARE `PhoneSim` 连 Google DHU：
  - 旧证书（16.7，2026-08-19 过期）→ `Verify returned: certificate has expired` → `-24`
  - 新证书（18.0，2027-02-17）→ **`Verify returned: ok`**（DHU 接受）
- 因此实车（校验 Google 证书链的车机）M4 证书风险已基本解除。

## 下一步（M3 起）

1. 把 **CarLife 实时 H.264 帧**接入 AA 视频通道（替代测试图）；
2. 把 **CarLife 媒体 PCM** 接入 AA 音频通道（44100→48000 可能需要重采样）；
3. AA 触摸 → CarLife 多指映射（已接好骨架，需实会话联调）；
4. 实车阶段：USB AOA 承载（AAP 帧不变，只换 `Transport` 实现）。
