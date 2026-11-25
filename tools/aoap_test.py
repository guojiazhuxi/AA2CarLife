#!/usr/bin/env python3
"""
AOAP（Android Open Accessory Protocol）最小验证脚本
==================================================

用途：在 PC（Windows / Linux / WSL2）上模拟 AA 车机端，对手机发起 AOA 握手：
  1) 请求 51 读 AOAP 协议版本
  2) 请求 52 依次发送 accessory 描述字符串（索引 0..5）
  3) 请求 53 让手机切换到 accessory 模式
之后手机应重新枚举为 VID:PID = 18D1:2D00（或 18D1:2D01 = accessory + adb）。

这一步只验证“荣耀手机支不支持 AOA accessory 模式”，不涉及 AA 的 TLS/AAP。

依赖：
  pip install pyusb
  Linux/WSL2 还需要 libusb：sudo apt install libusb-1.0-0

用法：
  python3 aoap_test.py --list        # 列出当前 USB 设备
  python3 aoap_test.py               # 在手机上执行 AOAP 握手
  python3 aoap_test.py --check       # 只检查是否已出现 18D1:2D00/2D01
"""

import argparse
import sys
import time

try:
    import usb.core
    import usb.util
except ImportError:
    sys.exit("缺少 pyusb：请先 pip install pyusb")

AOA_GET_PROTOCOL = 51
AOA_SEND_STRING = 52
AOA_START = 53

ACC_VID = 0x18D1
ACC_PIDS = (0x2D00, 0x2D01)

# Accessory 描述字符串（索引 0..5，必须带结尾 \0）
# 车机（DHU/真车）通常用 manufacturer="Google", model="Android Auto Mobile" 等；
# 这里先随便填，只用于验证 AOA 切换。以后要和 manifest 的 accessory_filter 对齐。
ACCESSORY_STRINGS = [
    "hobby-aa2carlife",       # 0 manufacturing
    "aa2carlife",             # 1 model
    "aa2carlife test bench",  # 2 description
    "1.0",                    # 3 version
    "https://example.invalid",  # 4 URI
    "0000000001",             # 5 serial
]


def list_devices():
    print("=== 当前 USB 设备 ===")
    for d in usb.core.find(find_all=True):
        try:
            mfg = usb.util.get_string(d, d.iManufacturer) or ""
        except Exception:
            mfg = ""
        try:
            prod = usb.util.get_string(d, d.iProduct) or ""
        except Exception:
            prod = ""
        print(f"  {d.idVendor:04x}:{d.idProduct:04x}  {mfg} / {prod}  bus={d.bus} addr={d.address}")


def find_phone():
    """找一台可能的手机：优先 Honor/Huawei（12d1），否则列出全部让用户选。"""
    devs = list(usb.core.find(find_all=True, idVendor=0x12D1))  # 华为/荣耀
    if devs:
        return devs[0]
    devs = list(usb.core.find(find_all=True))
    if not devs:
        sys.exit("没有发现任何 USB 设备：先确认 usbipd 已 attach、手机已插好并解锁")
    print("未发现 12d1（荣耀/华为）设备，当前设备列表：")
    list_devices()
    sys.exit("请用 --list 查看后手动选择（脚本可再加 VID/PID 参数）")


def send_aoa(dev):
    try:
        dev.set_configuration()
    except Exception as e:
        print(f"[warn] set_configuration 失败（可能已配置）：{e}")

    try:
        ver = dev.ctrl_transfer(0xC0, AOA_GET_PROTOCOL, 0, 0, 2)
        version = int(ver[0]) | (int(ver[1]) << 8) if len(ver) >= 2 else -1
        print(f"[1] AOAP 协议版本 = {version}")
    except Exception as e:
        sys.exit(f"[x] 读 AOAP 协议版本失败：{e}\n    → 手机/USB 栈可能不支持 AOA，或 usbipd 没把设备交给 WSL")

    for i, s in enumerate(ACCESSORY_STRINGS):
        try:
            dev.ctrl_transfer(0x40, AOA_SEND_STRING, 0, i, s.encode("utf-8") + b"\x00")
            print(f"[2] accessory 字符串[{i}] 发送成功：{s}")
        except Exception as e:
            sys.exit(f"[x] 发送 accessory 字符串[{i}] 失败：{e}")

    try:
        dev.ctrl_transfer(0x40, AOA_START, 0, 0, None)
        print("[3] AOA START 已发送，手机应重新枚举为 18D1:2D00/2D01")
    except Exception as e:
        sys.exit(f"[x] 发送 AOA START 失败：{e}")

    usb.util.dispose_resources(dev)


def wait_accessory(timeout=15):
    print(f"=== 等待 accessory 设备（{timeout}s，可重新执行 usbipd attach）===")
    deadline = time.time() + timeout
    while time.time() < deadline:
        for pid in ACC_PIDS:
            d = usb.core.find(idVendor=ACC_VID, idProduct=pid)
            if d is not None:
                print(f"✅ 发现 accessory 设备 {ACC_VID:04x}:{pid:04x}")
                print(f"   bus={d.bus} addr={d.address} speed={d.speed}")
                for cfg in d:
                    print(f"   配置 {cfg.bConfigurationValue}:")
                    for intf in cfg:
                        print(
                            f"     接口 {intf.bInterfaceNumber}: class=0x{intf.bInterfaceClass:02x} "
                            f"subclass=0x{intf.bInterfaceSubClass:02x}",
                        )
                        for ep in intf:
                            print(
                                f"       端点 0x{ep.bEndpointAddress:02x} "
                                f"attr={ep.bmAttributes} maxpacket={ep.wMaxPacketSize}",
                            )
                return 0
        time.sleep(0.5)
    print("❌ 未发现 18D1:2D00/2D01。")
    print("   如果 lsusb/--list 里也看不到，说明重新枚举后 usbipd 附着断了：")
    print("   在 Windows 管理员 PowerShell 里重新执行：")
    print("     usbipd list                              # 找新的 busid（可能变为 2D00 设备）")
    print("     usbipd attach --wsl --busid <新busid>")
    print("   然后重新跑： python3 aoap_test.py --check")
    return 1


def main():
    ap = argparse.ArgumentParser(description="AOAP 最小验证")
    ap.add_argument("--list", action="store_true", help="列出 USB 设备")
    ap.add_argument("--check", action="store_true", help="只检查是否已有 accessory 设备")
    args = ap.parse_args()

    if args.list:
        list_devices()
        return 0
    if args.check:
        return wait_accessory(5)

    dev = find_phone()
    print(f"[0] 目标手机：{dev.idVendor:04x}:{dev.idProduct:04x} bus={dev.bus} addr={dev.address}")
    send_aoa(dev)
    return wait_accessory(15)


if __name__ == "__main__":
    sys.exit(main())
