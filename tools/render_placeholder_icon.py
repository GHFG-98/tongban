#!/usr/bin/env python3
"""占位图标渲染：按「豆包AI生成」那张设计稿的样子，用 PIL 绘制 1024x1024 图标。
用途：app_icon.png 原图未提供时先顶上空；原图放到 icon_src/app_icon.png 后，
运行 tools/make_icon.py 即可用原图重新生成（见该脚本说明）。
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

S = 2                      # 超采样倍数
SZ = 1024                  # 输出尺寸
C = SZ * S                 # 画布尺寸


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", (1, h))
    for y in range(h):
        img.putpixel((0, y), lerp(top, bottom, y / max(h - 1, 1)))
    return img.resize((w, h))


def diagonal_gradient(size, c1, c2):
    """左上 -> 右下 渐变"""
    w, h = size
    small = Image.new("RGB", (64, 64))
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126.0
            small.putpixel((x, y), lerp(c1, c2, t))
    return small.resize((w, h), Image.BICUBIC)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def draw_scene():
    img = diagonal_gradient((C, C), (16, 62, 105), (31, 168, 201)).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")

    horizon = int(C * 0.585)
    cx = C // 2

    # --- 天空亮带（地平线附近提亮） ---
    glow = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([cx - C * 0.45, horizon - C * 0.22, cx + C * 0.45, horizon + C * 0.10],
               fill=(120, 200, 230, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(C * 0.05))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img, "RGBA")

    # --- 路面（透视梯形） ---
    road_top_w = C * 0.13
    road_bot_w = C * 1.15
    road = [(cx - road_top_w / 2, horizon), (cx + road_top_w / 2, horizon),
            (cx + road_bot_w / 2, C), (cx - road_bot_w / 2, C)]
    d.polygon(road, fill=(10, 27, 46, 255))

    # 路面左右边线
    for sign in (-1, 1):
        x_top = cx + sign * road_top_w / 2
        x_bot = cx + sign * road_bot_w / 2
        d.line([(x_top, horizon), (x_bot, C)], fill=(150, 195, 220, 210),
               width=int(C * 0.008))

    # 中央虚线（近大远小）
    n = 7
    for i in range(n):
        t0 = i / n
        t1 = t0 + 0.55 / n
        y0 = horizon + (C - horizon) * (t0 ** 1.7)
        y1 = horizon + (C - horizon) * (t1 ** 1.7)
        w0 = C * 0.006 + C * 0.020 * t0
        w1 = C * 0.006 + C * 0.020 * t1
        d.polygon([(cx - w0 / 2, y0), (cx + w0 / 2, y0),
                   (cx + w1 / 2, y1), (cx - w1 / 2, y1)],
                  fill=(235, 245, 250, 230))

    # --- 护栏（两侧，带立柱） ---
    rail_col = (140, 180, 210, 200)
    post_col = (110, 155, 190, 210)
    for sign in (-1, 1):
        x_top = cx + sign * C * 0.20
        y_top = horizon + C * 0.005
        x_bot = cx + sign * C * 0.62
        y_bot = C * 0.86
        # 主栏杆（双线）
        for off, wdt in ((0, 7), (C * 0.020, 4)):
            d.line([(x_top, y_top + off), (x_bot, y_bot + off)],
                   fill=rail_col, width=int(C * 0.0035 * (1 + wdt / 10)))
        # 立柱
        for i in range(7):
            t = i / 6
            px = x_top + (x_bot - x_top) * t
            py = y_top + (y_bot - y_top) * t
            ph = C * (0.045 + 0.075 * t)
            d.rectangle([px - C * 0.006, py, px + C * 0.006, py + ph],
                        fill=post_col)

    # --- 方向盘（底部剪影） ---
    wheel = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wheel)
    wcx, wcy, r = cx, int(C * 1.02), int(C * 0.34)
    wd.ellipse([wcx - r, wcy - r, wcx + r, wcy + r], fill=(6, 20, 36, 255))
    wd.ellipse([wcx - r * 0.62, wcy - r * 0.62, wcx + r * 0.62, wcy + r * 0.62],
               fill=(0, 0, 0, 0))
    # 用背景色挖空中心（近似）：叠一层路面色
    wd.ellipse([wcx - r * 0.60, wcy - r * 0.60, wcx + r * 0.60, wcy + r * 0.60],
               fill=(10, 27, 46, 255))
    wd.ellipse([wcx - r * 0.30, wcy - r * 0.30, wcx + r * 0.30, wcy + r * 0.30],
               fill=(6, 20, 36, 255))
    img = Image.alpha_composite(img, wheel)
    d = ImageDraw.Draw(img, "RGBA")

    # --- 时间轴（顶部横线 + 三个点） ---
    tl_y = int(C * 0.255)
    tl_x0, tl_x1 = int(C * 0.16), int(C * 0.88)
    card_cx = [int(C * 0.245), int(C * 0.5), int(C * 0.755)]
    d.line([(tl_x0, tl_y), (tl_x1, tl_y)], fill=(255, 255, 255, 235),
           width=int(C * 0.006))
    for i, x in enumerate(card_cx):
        r = int(C * 0.016)
        if i == 0:
            d.ellipse([x - r, tl_y - r, x + r, tl_y + r],
                      fill=(255, 255, 255, 255))
        else:
            d.ellipse([x - r, tl_y - r, x + r, tl_y + r],
                      outline=(255, 255, 255, 230), width=int(C * 0.006))

    # --- 三张照片卡片 ---
    cw, ch = int(C * 0.195), int(C * 0.135)
    cy = int(C * 0.42)
    for i, x in enumerate(card_cx):
        x0, y0, x1, y1 = x - cw // 2, cy, x + cw // 2, cy + ch
        # 卡片底（半透明）
        d.rounded_rectangle([x0, y0, x1, y1], radius=int(C * 0.022),
                            fill=(215, 238, 248, 60))
        # 边框
        border = (255, 255, 255, 255) if i == 1 else (216, 238, 247, 220)
        d.rounded_rectangle([x0, y0, x1, y1], radius=int(C * 0.022),
                            outline=border, width=int(C * 0.0075))
        # 卡片内小图 glyph（山 + 太阳）
        gx0, gy0 = x0 + cw * 0.16, y0 + ch * 0.24
        gx1, gy1 = x1 - cw * 0.16, y1 - ch * 0.20
        d.ellipse([gx1 - cw * 0.13, gy0, gx1, gy0 + cw * 0.13],
                  fill=(127, 196, 224, 255))
        d.polygon([(gx0, gy1), (gx0 + (gx1 - gx0) * 0.38, gy0 + ch * 0.16),
                   (gx0 + (gx1 - gx0) * 0.62, gy1)], fill=(127, 196, 224, 255))
        d.polygon([(gx0 + (gx1 - gx0) * 0.40, gy1),
                   (gx0 + (gx1 - gx0) * 0.68, gy0 + ch * 0.30),
                   (gx1, gy1)], fill=(95, 175, 208, 255))

    # --- 右下星芒 ---
    sx, sy, sr = int(C * 0.865), int(C * 0.875), int(C * 0.075)
    star = [(sx, sy - sr), (sx + sr * 0.22, sy - sr * 0.22),
            (sx + sr, sy), (sx + sr * 0.22, sy + sr * 0.22),
            (sx, sy + sr), (sx - sr * 0.22, sy + sr * 0.22),
            (sx - sr, sy), (sx - sr * 0.22, sy - sr * 0.22)]
    d.polygon(star, fill=(201, 237, 247, 255))

    # --- 圆角遮罩 ---
    mask = rounded_mask((C, C), int(C * 0.225))
    out = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.resize((SZ, SZ), Image.LANCZOS)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    dst_dir = os.path.join(root, "icon_src")
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, "app_icon.png")
    draw_scene().save(dst)
    print("written:", dst)
