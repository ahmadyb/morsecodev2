#!/usr/bin/env python3
"""
Generates every MorseCode launcher icon + the signal-mark vector assets.

Design (from the supplied identity sheet):
  * rounded square, brand gradient Sun (#FACC15) -> Ember (#F97316)
  * dark "signal mark": two vertical bars (dash + short), a 3x3 dot cluster,
    one vertical bar, and a horizontal transmit bar underneath

Outputs
  res/mipmap-*/ic_launcher.png             legacy launcher
  res/mipmap-*/ic_launcher_round.png       legacy round
  res/mipmap-anydpi-v26/ic_launcher*.xml   adaptive icon (API 26+)
  res/drawable/ic_launcher_foreground.xml  adaptive foreground (vector)
  res/drawable/ic_launcher_background.xml  adaptive background (gradient vector)
  res/drawable/ic_mc_mark_{16,24,32,48}.xml  toolbar / header variants
  res/drawable/ic_stat_mc.xml              white notification silhouette
"""

import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

SUN = (250, 204, 21)
EMBER = (249, 115, 22)
GLYPH = (26, 16, 2)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


# ---------------------------------------------------------------- raster icons
def gradient(size, radius_ratio=0.225, inset_ratio=0.0):
    """Rounded square filled with the brand gradient (supersampled for smooth edges)."""
    ss = 4
    w = size * ss
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    grad = Image.new("RGB", (w, w))
    px = grad.load()
    for y in range(w):
        for x in range(w):
            t = (x + y) / (2.0 * (w - 1))
            px[x, y] = tuple(int(SUN[i] + (EMBER[i] - SUN[i]) * t) for i in range(3))
    mask = Image.new("L", (w, w), 0)
    d = ImageDraw.Draw(mask)
    inset = int(w * inset_ratio)
    r = int(w * radius_ratio)
    d.rounded_rectangle([inset, inset, w - 1 - inset, w - 1 - inset], radius=r, fill=255)
    img.paste(grad, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)


def draw_mark(draw, size, scale=0.62, color=GLYPH, cx=0.5, cy=0.5):
    """The signal mark, drawn in a square of `scale * size` centred on (cx, cy)."""
    s = size * scale
    ox = size * cx - s / 2
    oy = size * cy - s / 2

    def X(v):
        return ox + v * s

    def Y(v):
        return oy + v * s

    bar_w = 0.105 * s
    radius = bar_w / 2
    # left tall dash, short bar
    draw.rounded_rectangle([X(0.16), Y(0.06), X(0.16) + bar_w, Y(0.60)], radius=radius, fill=color)
    draw.rounded_rectangle([X(0.30), Y(0.28), X(0.30) + bar_w, Y(0.60)], radius=radius, fill=color)
    # 3x3 dot cluster
    dot_r = 0.050 * s
    for row in range(3):
        for col in range(3):
            dcx = X(0.575) + (col - 1) * 0.155 * s
            dcy = Y(0.235) + row * 0.155 * s
            draw.ellipse([dcx - dot_r, dcy - dot_r, dcx + dot_r, dcy + dot_r], fill=color)
    # right dash
    draw.rounded_rectangle([X(0.845), Y(0.06), X(0.845) + bar_w, Y(0.60)], radius=radius, fill=color)
    # transmit bar underneath
    draw.rounded_rectangle([X(0.16), Y(0.78), X(0.945), Y(0.78) + bar_w * 0.9], radius=bar_w * 0.45, fill=color)


def write_png(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)


def make_legacy(size):
    """Legacy icons: glyph inside a rounded square with a small padding."""
    ss = 4
    img = gradient(size * ss, radius_ratio=0.235, inset_ratio=0.045)
    d = ImageDraw.Draw(img)
    draw_mark(d, size * ss, scale=0.60, color=GLYPH)
    return img.resize((size, size), Image.LANCZOS)


def make_round(size):
    """Round legacy icon: circle crop of the brand square."""
    ss = 4
    base = gradient(size * ss, radius_ratio=0.5, inset_ratio=0.02)
    d = ImageDraw.Draw(base)
    draw_mark(d, size * ss, scale=0.56, color=GLYPH)
    return base.resize((size, size), Image.LANCZOS)


def make_notification(size=96):
    """Notification small icons must be a flat white silhouette."""
    ss = 4
    img = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mark(d, size * ss, scale=0.84, color=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


# ------------------------------------------------------------- vector helpers
def rr(x, y, w, h, r):
    """Rounded-rectangle path data."""
    return (
        "M%.3f,%.3f h%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f v%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f "
        "h%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f v%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f Z"
        % (
            x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, -r, r, -(w - 2 * r),
            r, r, -r, r, -(h - 2 * r), r, r, -r, -r,
        )
    )


def circle(cx, cy, r):
    return "M%.3f,%.3f a%.3f,%.3f 0 1 0 %.3f,0 a%.3f,%.3f 0 1 0 %.3f,0 Z" % (
        cx - r, cy, r, r, 2 * r, r, r, -2 * r,
    )


def mark_paths(size, scale, color, cx=None, cy=None, ox=None, oy=None):
    """Path elements for the signal mark inside a `size` viewport."""
    s = size * scale
    if ox is None:
        ox = (size - s) / 2 if cx is None else cx * size - s / 2
    if oy is None:
        oy = (size - s) / 2 if cy is None else cy * size - s / 2
    bar_w = 0.105 * s
    out = []
    out.append('<path android:fillColor="%s" android:pathData="%s"/>'
               % (color, rr(ox + 0.16 * s, oy + 0.06 * s, bar_w, 0.54 * s, bar_w / 2)))
    out.append('<path android:fillColor="%s" android:pathData="%s"/>'
               % (color, rr(ox + 0.30 * s, oy + 0.28 * s, bar_w, 0.32 * s, bar_w / 2)))
    dot_r = 0.050 * s
    for row in range(3):
        for col in range(3):
            dcx = ox + 0.575 * s + (col - 1) * 0.155 * s
            dcy = oy + 0.235 * s + row * 0.155 * s
            out.append('<path android:fillColor="%s" android:pathData="%s"/>'
                       % (color, circle(dcx, dcy, dot_r)))
    out.append('<path android:fillColor="%s" android:pathData="%s"/>'
               % (color, rr(ox + 0.845 * s, oy + 0.06 * s, bar_w, 0.54 * s, bar_w / 2)))
    out.append('<path android:fillColor="%s" android:pathData="%s"/>'
               % (color, rr(ox + 0.16 * s, oy + 0.78 * s, 0.785 * s, bar_w * 0.9, bar_w * 0.45)))
    return "\n    ".join(out)


def vector(path, viewport, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="%ddp"\n    android:height="%ddp"\n'
            '    android:viewportWidth="%d"\n    android:viewportHeight="%d">\n'
            '    %s\n</vector>\n' % (viewport[2], viewport[3], viewport[0], viewport[1], body)
        )


def main():
    for name, size in DENSITIES.items():
        write_png(os.path.join(RES, "mipmap-%s" % name, "ic_launcher.png"), make_legacy(size))
        write_png(os.path.join(RES, "mipmap-%s" % name, "ic_launcher_round.png"), make_round(size))

    # notification silhouette (white, 24dp bucket)
    write_png(os.path.join(RES, "drawable-xhdpi", "ic_stat_mc.png"), make_notification(48))

    # adaptive icon (API 26+): 108dp viewport, mark inside the 72dp safe zone
    vector(
        os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
        (108, 108, 108, 108),
        mark_paths(108, 0.50, "#1A1002"),
    )
    with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            '    <path android:pathData="M0,0h108v108h-108z">\n'
            '        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">\n'
            '            <gradient android:startX="0" android:startY="0" android:endX="108" android:endY="108"\n'
            '                android:type="linear">\n'
            '                <item android:offset="0" android:color="#FFFACC15"/>\n'
            '                <item android:offset="1" android:color="#FFF97316"/>\n'
            '            </gradient>\n'
            '        </aapt:attr>\n'
            '    </path>\n</vector>\n'
        )
    for name, bg in (("ic_launcher", "@drawable/ic_launcher_background"),
                     ("ic_launcher_round", "@drawable/ic_launcher_background")):
        with open(os.path.join(RES, "mipmap-anydpi-v26", "%s.xml" % name), "w") as fh:
            fh.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="%s"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                '</adaptive-icon>\n' % bg
            )

    # progressively smaller in-app variants (toolbars, headers, list rows, notifications)
    for dp in (16, 24, 32, 48):
        vector(
            os.path.join(RES, "drawable", "ic_mc_mark_%d.xml" % dp),
            (dp, dp, 24, 24),
            mark_paths(24, 0.98, "#1A1002"),
        )
        vector(
            os.path.join(RES, "drawable", "ic_mc_mark_%d_light.xml" % dp),
            (dp, dp, 24, 24),
            mark_paths(24, 0.98, "#F5F5F5"),
        )
    vector(
        os.path.join(RES, "drawable", "ic_stat_mc.xml"),
        (24, 24, 24, 24),
        mark_paths(24, 0.94, "#FFFFFFFF"),
    )

    # brand gradient tile used behind avatars / headers
    print("icons written to", RES)


if __name__ == "__main__":
    main()
