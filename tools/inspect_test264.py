"""解析 assets/aap/test.264 的 SPS（修正版 Annex-B 切分）。"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\tang\Desktop\AATOCARLIFE\aa2carlife\app\src\main\assets\aap\test.264"
data = open(path, "rb").read()

starts = [m.end() for m in re.finditer(b"\x00\x00\x01|\x00\x00\x00\x01", data)]
nets = []
for idx, s in enumerate(starts):
    e = starts[idx + 1] if idx + 1 < len(starts) else len(data)
    # 去掉下一个起始码前的尾随 0
    payload = data[s:e].rstrip(b"\x00")
    if payload:
        nets.append(payload)

print("NAL count:", len(nets), "types:", [n[0] & 0x1F for n in nets], "sizes:", [len(n) for n in nets])


class Bits:
    def __init__(self, b):
        self.b = b
        self.pos = 0

    def u(self, n):
        v = 0
        for _ in range(n):
            byte = self.b[self.pos >> 3]
            v = (v << 1) | ((byte >> (7 - (self.pos & 7))) & 1)
            self.pos += 1
        return v

    def ue(self):
        z = 0
        while self.u(1) == 0:
            z += 1
        return (1 << z) - 1 + (self.u(z) if z else 0)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)


for nal in nets:
    if (nal[0] & 0x1F) != 7:
        continue
    rbsp = bytearray()
    body = nal[1:]  # 跳过 NAL header 字节，之后才是 profile_idc
    i = 0
    while i < len(body):
        if i + 2 < len(body) and body[i] == 0 and body[i + 1] == 0 and body[i + 2] == 3:
            rbsp += body[i:i + 2]
            i += 3
        else:
            rbsp.append(body[i])
            i += 1
    b = Bits(rbsp)
    profile = b.u(8)
    b.u(8)
    level = b.u(8)
    sps_id = b.ue()
    chroma = 1
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = b.ue()
        if chroma == 3:
            b.u(1)
        b.ue()
        b.ue()
        b.u(1)
        se = b.se()
        if se == 0:
            b.ue(); b.ue()
        elif se == 1:
            b.u(1); b.se(); b.se()
            n = b.ue()
            for _ in range(n):
                b.se()
    log2fn = b.ue() + 4
    poct = b.ue()
    if poct == 0:
        b.ue()
    elif poct == 1:
        b.u(1); b.se(); b.se()
        n = b.ue()
        for _ in range(n):
            b.se()
    b.ue()
    b.u(1)
    w_mbs = b.ue() + 1
    h_map = b.ue() + 1
    frame_mbs_only = b.u(1)
    if not frame_mbs_only:
        b.u(1)
    b.u(1)
    crop = b.u(1)
    cl = ct = cr = cb = 0
    if crop:
        cl = b.ue(); cr = b.ue(); ct = b.ue(); cb = b.ue()
    coded_w = w_mbs * 16
    coded_h = h_map * 16 * (2 - frame_mbs_only)
    width, height = coded_w, coded_h
    if chroma == 1:
        width -= (cl + cr) * 2
        height -= (ct + cb) * 2
    print(f"SPS: profile={profile} level={level} chroma={chroma} sps_id={sps_id} "
          f"coded={coded_w}x{coded_h} crop=l{cl} r{cr} t{ct} b{cb} -> {width}x{height}")
