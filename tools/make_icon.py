#!/usr/bin/env python3
"""由源图生成 Android 自适应图标（launcher icon）资源。

用法：
    python3 tools/make_icon.py [源图路径]
    源图默认 icon_src/app_icon.png（正方形，圆角已烘焙进去的 AI 出图可直接用）

生成：
    app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml       自适应图标（API 26+）
    app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
    app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png  前景层（108dp=432px）
    app/src/main/res/drawable/ic_launcher_background.xml        背景层（渐变）
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png 旧式图标兜底（API<26）

然后在 AndroidManifest.xml 的 <application> 上加：
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
"""
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def gen_legacy(src, out_dir):
    for den, px in LEGACY.items():
        d = os.path.join(RES, f"mipmap-{den}")
        os.makedirs(d, exist_ok=True)
        src.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))


def gen_foreground(src):
    """自适应前景层：108dp 画布（xxxhdpi=432px），内容铺满整层。
    关键内容居中的设计可被launcher mask安全裁剪；如需更保险可把缩放系数调小。"""
    px = 432
    d = os.path.join(RES, "mipmap-xxxhdpi")
    os.makedirs(d, exist_ok=True)
    src.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"))


def gen_xml():
    d = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(d, exist_ok=True)
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for name in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(d, f"{name}.xml"), "w", encoding="utf-8") as f:
            f.write(adaptive)

    bg_dir = os.path.join(RES, "drawable")
    os.makedirs(bg_dir, exist_ok=True)
    bg = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient
                android:type="linear"
                android:angle="315"
                android:startColor="#103E69"
                android:endColor="#1FA8C9" />
        </aapt:attr>
    </path>
</vector>
"""
    with open(os.path.join(bg_dir, "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write(bg)


if __name__ == "__main__":
    src_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "icon_src", "app_icon.png")
    if not os.path.exists(src_path):
        sys.exit(f"源图不存在: {src_path}\n先放一张正方形 PNG 到该路径，或用 tools/render_placeholder_icon.py 生成占位图")
    src = Image.open(src_path).convert("RGBA")
    if src.width != src.height:
        sys.exit(f"源图需为正方形，当前 {src.width}x{src.height}")
    gen_legacy(src, RES)
    gen_foreground(src)
    gen_xml()
    print(f"OK: {src_path} ({src.width}x{src.height})")
    print("生成完毕，确认 AndroidManifest.xml 的 <application> 含：")
    print('    android:icon="@mipmap/ic_launcher"')
    print('    android:roundIcon="@mipmap/ic_launcher_round"')
