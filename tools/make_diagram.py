# -*- coding: utf-8 -*-
import os, math
from PIL import Image, ImageDraw, ImageFont

W, H = 1800, 1150
img = Image.new("RGB", (W, H), "#ffffff")
d = ImageDraw.Draw(img)

def font(size, bold=False):
    for p in ([r"C:\Windows\Fonts\msyhbd.ttc"] if bold else [r"C:\Windows\Fonts\msyh.ttc"]) + [r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try: return ImageFont.truetype(p, size)
            except Exception: pass
    return ImageFont.load_default()

F_TITLE=font(44,True); F_SUB=font(24); F_H=font(30,True); F_H2=font(26,True); F_B=font(23); F_S=font(20); F_XS=font(18)

def rbox(x1,y1,x2,y2,fill,outline,width=3,r=18):
    d.rounded_rectangle([x1,y1,x2,y2], radius=r, fill=fill, outline=outline, width=width)

def text_center(cx,y,s,f,fill="#111111"):
    d.text((cx-d.textlength(s,font=f)/2,y), s, font=f, fill=fill)

def arrow(x1,y1,x2,y2,color="#1f7a3d",width=4,head=16):
    d.line([x1,y1,x2,y2], fill=color, width=width)
    ang=math.atan2(y2-y1,x2-x1)
    for sign in (0.5,-0.5):
        d.line([x2,y2,x2-head*math.cos(ang-sign*0.9),y2-head*math.sin(ang-sign*0.9)], fill=color, width=width)

def label(x,y,s,f=F_S,fill="#333333"):
    d.text((x,y), s, font=f, fill=fill)

text_center(W/2,30,"AA2CarLife：两个“假车机”的关系（HeadUnitClient vs Google DHU）",F_TITLE)
text_center(W/2,92,"手机扮演 Android Auto 手机端；PC 用两种方式扮演“车机”验收；实车走 USB AOA",F_SUB,"#555555")

# ---- 手机 ----
rbox(80,160,1180,560,"#eef4ff","#3b6fb6",4)
label(102,172,"手机（荣耀 MagicOS）",F_H,"#1b3f73")

# 引擎
rbox(110,250,520,470,"#ffffff","#7a9cd0",3)
text_center(315,262,"荣耀车联 / CarLife 引擎",F_H)
text_center(315,306,"CarLife+（com.baidu.carlife.honor）",F_XS)
text_center(315,344,"产出 H.264 / PCM，接收触摸",F_S,"#555555")
text_center(315,380,"端口 7240/8240/9240/9340",F_S,"#888888")
text_center(315,420,"（我们扮演“CarLife 车机”端）",F_S,"#8a5200")

# App
rbox(680,250,1150,500,"#ffffff","#3b6fb6",3)
text_center(915,262,"我们的 App",F_H2)
text_center(915,296,"com.hobby.aa2carlife",F_XS,"#555555")
rbox(706,330,1126,388,"#e8f1ff","#7aa7dd",2,12)
text_center(916,340,"CarLifeHuClient（CarLife 车机端）",F_B)
text_center(916,374,"握手 / 视频 / 音频 / 触摸",F_XS,"#555555")
rbox(706,404,1126,472,"#e8f1ff","#7aa7dd",2,12)
text_center(916,414,"AaHeadUnitServer（AA 手机端）",F_B)
text_center(916,450,"AAP over TLS，监听 127.0.0.1:5277",F_XS,"#555555")

# 引擎 <-> App
arrow(520,360,680,360,"#b06a00",4)
label(534,314,"CarLife",F_S,"#8a5200")
label(534,330,"协议",F_S,"#8a5200")
label(534,382,"TCP 127.0.0.1",F_XS,"#8a5200")

# ---- PC ----
rbox(80,640,1180,1080,"#f6f7f9","#666666",4)
label(102,650,"PC（Windows）上的两种“假车机”",F_H,"#333333")
text_center(630,692,"两者都通过 adb forward tcp:5277 → AAP over TLS 连接手机 App（同一套协议）",F_XS,"#555555")

rbox(120,730,620,1020,"#eaf7ee","#2f8a4d",3)
text_center(370,742,"A. HeadUnitClient",F_H,"#1c5f34")
text_center(370,782,"bench/aare-aap（AARE 测试代码）",F_B)
for i,t in enumerate([
    "• 无界面、可脚本化：命令行跑",
    "• 统计断言：frames/bytes/audio 等",
    "• 不校验证书（自签也能连）",
    "• 支持 tap:/swipe: 指令发触摸",
    "• 补丁：收到的 PCM 可落盘",
    "  → 音频 -32768 bug 靠它定位",
]):
    label(146,824+i*32,t,F_S,"#222222")

rbox(660,730,1140,1020,"#fff4e6","#b06a00",3)
text_center(900,742,"B. Google DHU",F_H,"#8a5200")
text_center(900,782,"Desktop Head Unit（AA SDK 自带）",F_B)
for i,t in enumerate([
    "• 带 GUI：直接看到 CarLife 画面",
    "• PC 扬声器出声（听音质）",
    "• 校验证书链 → Verify returned: ok",
    "• 也能连自带的 Google AA（对照）",
    "• 人工验收：画面/声音/触摸",
]):
    label(686,824+i*32,t,F_S,"#222222")

# App -> 两个假车机
arrow(780,500,380,730,"#1f7a3d",4)
label(430,560,"adb forward 5277",F_XS,"#1c5f34")
arrow(1030,500,960,730,"#1f7a3d",4)
label(1000,560,"adb forward 5277",F_XS,"#1c5f34")

# ---- 真车 ----
rbox(1340,640,1760,980,"#ffe9e9","#b33a3a",4)
text_center(1550,658,"奥迪 MIB3（真车机）",F_H,"#7a1f1f")
for i,t in enumerate([
    "• 有线 Android Auto = USB AOA",
    "• 手机被切成 accessory 模式",
    "• App 接收 USB 通道，跑同一套 AAP",
    "",
    "→ 台架验收通过后，才上真车（M4）",
]):
    label(1364,720+i*40,t,F_S,"#7a1f1f")

arrow(1150,478,1340,740,"#b33a3a",5)
label(1235,505,"USB AOA",F_XS,"#7a1f1f")
label(1235,538,"（M4 真车）",F_XS,"#7a1f1f")

text_center(W/2,1098,"HeadUnitClient = 快速回归 + 数据抓取（可编程）；DHU = 官方参考 + 人工验收；两者都通过 TCP 5277 扮演车机",F_B,"#444444")

out=r"docs\images\headunit-simulators-relation.png"
img.save(out)
print("saved:",out,img.size)
