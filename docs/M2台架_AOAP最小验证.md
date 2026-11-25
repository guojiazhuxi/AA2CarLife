# M2 台架前置：AOAP 最小验证（WSL2 + usbipd）

目标：先回答一个决定性问题——**荣耀手机的 USB 栈支不支持 AOA accessory 模式**。
只要这一步失败，AA 手机端方案就不成立；成功才有必要继续做 TLS/AAP。

## 0. 环境

- Windows 10/11 + WSL2（Ubuntu）
- 手机：荣耀（已开开发者选项 + USB 调试，USB 连接模式选「传输文件/MTP」）
- 手机已装我们的 App（`com.hobby.aa2carlife`，manifest 里有 accessory_filter）

## 1. Windows 侧：安装 usbipd-win 并把手机转给 WSL

管理员 PowerShell：

```powershell
winget install usbipd            # 或从 GitHub 下载 MSI
adb kill-server                  # 避免 adb 抢占手机
usbipd list                      # 找到手机（Honor/Huawei，VID 12d1）的 BUSID
usbipd bind --busid <BUSID>      # 只需一次
usbipd attach --wsl --busid <BUSID>
```

> 注意：`usbipd bind` 会把手机从 Windows 的 ADB 驱动切走；之后 adb 不可用，恢复用
> `usbipd detach` + 设备管理器卸载/重扫，或 `usbipd unbind`。

## 2. WSL 侧：准备 Python 依赖

```bash
sudo apt update && sudo apt install -y python3-pip libusb-1.0-0
pip3 install pyusb
sudo modprobe vhci-hcd            # 没报错即可；WSL2 内核一般自带
lsusb                             # 应能看到手机
```

## 3. 跑最小验证

```bash
cd /mnt/c/Users/tang/Desktop/AATOCARLIFE/tools   # 或把脚本拷进 WSL
python3 aoap_test.py --list      # 先确认能看到手机
python3 aoap_test.py             # 发 AOA 51/52/53 握手，等待 18D1:2D00/2D01
```

## 4. 结果判定

| 现象 | 结论 |
|---|---|
| 打印 AOAP 协议版本（一般 2），字符串都发送成功，随后发现 `18D1:2D00`（或 2D01） | ✅ 手机支持 AOA，M2 可继续 |
| 51 请求就失败 | ❌ USB 栈不支持 AOA / 设备没真正转到 WSL |
| 53 之后 `lsusb` 里手机消失，重新 `usbipd list` 出现 `18D1:2D00` 但没附着 | 重新 `usbipd attach` 后再跑 `--check`；这是 usbipd 重新枚举的常见情况 |
| 始终看不到 `18D1` | 手机厂商可能禁了 AOA，需换思路（无线 AA / 换手机） |

## 5. 成功后下一步（M2.1 之后）

1. 用同样的宿主环境实现/联调 AAP：
   - TLS Server（自签，台架侧关校验）
   - 版本协商 → 服务发现 → 视频/输入/音频通道
2. 台架 HU 二选一：
   - AARE `desktop-head-unit`（开源，可关证书校验；Linux/macOS）
   - 自写最小 HU 脚本（libusb + TLS，Windows/WSL 都能跑）
3. 若 WSL2 重新枚举问题太烦：改用 **VirtualBox/VMware 的 Ubuntu 虚拟机 + USB 直通**（AOAP 重新枚举在 VM 的 USB 控制器里正常）。

## 6. 已知风险

- usbipd 在设备重新枚举时可能丢失附着（AOAP 的 53 请求必然引发重新枚举），需要 `--auto-attach` 或手动重新 attach；
- `usbipd bind` 期间 Windows 的 adb 不可用，调试请依赖 App 内日志 `files/aa2cl.log`；
- WSL2 的 USB/IP 对等时性（control transfer 时序）不如原生 Linux，若 51/52/53 时序被打断会失败——那就换 VM。
