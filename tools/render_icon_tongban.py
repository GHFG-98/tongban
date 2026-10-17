#!/usr/bin/env python3
"""瞳伴图标：镜头/眼睛（白圈+瞳孔，瞳孔中公路延伸向远方）+ 底部同行足迹。"""
import math, os
from PIL import Image, ImageDraw, ImageFilter

S = 2; SZ = 1024; C = SZ * S

def lerp(a, b, t): return tuple(int(a[i] + (b[i]-a[i])*t) for i in range(3))

def diag(size, c1, c2):
    small = Image.new("RGB", (64, 64))
    for y in range(64):
        for x in range(64):
            small.putpixel((x, y), lerp(c1, c2, (x+y)/126.0))
    return small.resize(size, Image.BICUBIC)

def rmask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0]-1, size[1]-1], radius=r, fill=255)
    return m

img = diag((C, C), (13, 58, 102), (24, 158, 196)).convert("RGBA")
cx, cy = C // 2, int(C * 0.44)

# ---- 外圈：白色镜头环 ----
R_OUT = int(C * 0.315)
d = ImageDraw.Draw(img, "RGBA")
d.ellipse([cx-R_OUT, cy-R_OUT, cx+R_OUT, cy+R_OUT], fill=(235, 246, 252, 255))

# ---- 瞳孔：深色 ----
R_PUP = int(C * 0.245)
d.ellipse([cx-R_PUP, cy-R_PUP, cx+R_PUP, cy+R_PUP], fill=(8, 26, 44, 255))

# ---- 瞳孔中的公路（透视梯形+虚线+远方光点），裁成圆形贴入 ----
road = Image.new("RGBA", (R_PUP*2, R_PUP*2), (8, 26, 44, 255))  # 瞳孔深色底：paste 的 L 蒙版会把透明当黑色
rd = ImageDraw.Draw(road)
w, h = road.size
rcx, rcy = w/2, h*0.46
rd.polygon([(rcx-w*0.012, rcy-h*0.10), (rcx+w*0.012, rcy-h*0.10),
            (rcx+w*0.115, rcy+h*0.30), (rcx-w*0.115, rcy+h*0.30)], fill=(206, 224, 238, 245))
for t in [0.06, 0.24, 0.46, 0.72]:
    y0 = rcy - h*0.09 + t*h*0.38
    lw = w*(0.008 + t*0.022)
    lh = h*(0.016 + t*0.038)
    rd.rounded_rectangle([rcx-lw/2, y0, rcx+lw/2, y0+lh], radius=lw/2, fill=(13, 58, 102, 255))
rd.ellipse([rcx-w*0.012, rcy-h*0.125, rcx+w*0.012, rcy-h*0.095], fill=(255, 238, 182, 255))
m = Image.new("L", road.size, 0)
ImageDraw.Draw(m).ellipse([0, 0, w-1, h-1], fill=255)
img.paste(road, (cx-R_PUP, cy-R_PUP), m)

# ---- 瞳孔右上的细长反光（小，预混合亮色）----
d = ImageDraw.Draw(img, "RGBA")
d.ellipse([cx+R_PUP*0.30, cy-R_PUP*0.88, cx+R_PUP*0.52, cy-R_PUP*0.62], fill=(96, 116, 136, 255))
# 镜头环上的小亮点（像录制指示灯）
d.ellipse([cx+R_OUT*0.62, cy-R_OUT*0.62, cx+R_OUT*0.80, cy-R_OUT*0.44], fill=(255, 99, 71, 255))

# ---- 底部：两串同行足迹（从中间向两侧下方斜出）----
def print_foot(base, x, y, ang, scale, color):
    f = Image.new("RGBA", (C, C), (0,0,0,0))
    fd = ImageDraw.Draw(f)
    sw, sh = C*0.021*scale, C*0.040*scale
    fd.ellipse([x-sw/2, y-sh/2, x+sw/2, y+sh/2], fill=color)          # 前掌
    fd.ellipse([x-sw*0.38, y+sh*0.28, x+sw*0.38, y+sh*0.78], fill=color)  # 脚跟
    return Image.alpha_composite(base, f.rotate(ang, center=(x, y)))

pale = (235, 246, 252, 215)
for i in range(5):
    t = i / 4.0
    # 左串（向左下）
    lx = cx - C*0.10 - t*C*0.20
    ly = int(C*0.72) + int(t*C*0.16) + (i % 2)*int(C*0.045)
    img = print_foot(img, lx, ly, 160 - t*14, 1.0 - t*0.1, pale)
    # 右串（向右下）
    rx = cx + C*0.10 + t*C*0.20
    ry = int(C*0.72) + int(t*C*0.16) + ((i+1) % 2)*int(C*0.045)
    img = print_foot(img, rx, ry, -160 + t*14, 1.0 - t*0.1, pale)

out = Image.new("RGBA", (C, C), (0,0,0,0))
out.paste(img, (0, 0), rmask((C, C), int(C*0.225)))
out.resize((SZ, SZ), Image.LANCZOS).save(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "icon_src", "app_icon.png"))
print("tongban icon written")
