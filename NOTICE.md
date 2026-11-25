# NOTICE / 第三方许可

本仓库整体以 **GPL-3.0** 发布，详见根目录 [LICENSE](LICENSE)。
本仓库包含或参考了以下开源项目：

## AARE

- 项目：https://github.com/prototux/AARE
- 许可：**GPL-3.0-or-later**
- 使用范围：`aap/` 目录（AAP 协议栈、生成的 protobuf 类）。
- 许可全文：`aap/LICENSE-GPL-3.0.txt`；根目录 `LICENSE` 为 GPL-3.0。

## CarPToCarL

- 项目：https://github.com/jjuugk/CarPToCarL
- 许可：**MIT License**，Copyright (c) 2026 lizhihua
- 使用范围：`app/src/main/java/com/hobby/aa2carlife/carlife/CarLifeProtocol.kt` 的常量、
  帧编解码、protobuf 手写编码、HU_INFO/featureConfig/videoEncoderInfo/触控/模块状态解析等，
  移植并裁剪自该项目的 `CarLifeProtocol.kt`；握手时序与坑位结论参考其文档与 `CarLifeHuClient.kt`。

MIT 许可全文：

```text
MIT License

Copyright (c) 2026 lizhihua

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

MIT 许可允许复制、修改、再分发，但**不授予**其实现的第三方协议（百度 CarLife）相关权利。

## AA 身份材料

- `app/src/main/assets/aap/phone_chain.pem`、`phone_key.pem` 来自 Android Auto 客户端，
  属于可从 APK 中公开提取的身份材料；不是本项目作者的个人证书。
- 它们仍受 Google 服务条款约束，仅供个人研究；如不想使用，可替换成自己合法获得的证书。

## 声明

- 本项目与 Google、百度、荣耀、奥迪均无关联，未获授权或背书；
- 「Android Auto」「CarLife」「荣耀车联」等为各自权利人商标，本文仅作描述性引用；
- 本项目不含任何证书私钥、不含 Google/Baidu SDK 产物，仅用于个人设备上的互操作研究。