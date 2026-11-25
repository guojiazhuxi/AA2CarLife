# NOTICE

本目录（`aa2carlife/`）是个人自用研究项目，包含对以下开源项目的移植/参考：

## CarPToCarL

- 项目：https://github.com/jjuugk/CarPToCarL
- 许可：**MIT License**，Copyright (c) 2026 lizhihua
- 使用范围：`app/src/main/java/com/hobby/aa2carlife/carlife/CarLifeProtocol.kt` 的常量、
  帧编解码、protobuf 手写编码、HU_INFO/featureConfig/videoEncoderInfo/触控/模块状态解析等，
  移植并裁剪自该项目的 `app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeProtocol.kt`；
  握手时序与坑位结论来自其 `docs/carlife-hijack-findings.md` 与 `CarLifeHuClient.kt`。
- MIT 许可允许复制、修改、再分发，但**不授予**其实现的第三方协议（百度 CarLife）相关权利。

MIT 许可全文见：refs/CarPToCarL/LICENSE（仓库内 `refs/` 目录）。

## 后续可能引入

- AARE（https://github.com/prototux/AARE，**GPL-3.0-or-later**）：M2 起 vendor 其 `aap/` 协议模块。
  一旦引入，本仓库整体按 GPL-3.0 发布，并保留其版权声明与 `aap/src/main/proto/NOTICE.md`。

## 声明

- 本项目与 Google、百度、荣耀、奥迪均无关联，未获授权或背书；
- 「Android Auto」「CarLife」「荣耀车联」等为各自权利人商标，本文仅作描述性引用；
- 本项目不含任何证书私钥、不含 Google/Baidu SDK 产物，仅用于个人设备上的互操作研究。
