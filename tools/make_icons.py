#!/usr/bin/env python3
"""
Generates every MorseCode launcher icon + in-app brand mark from the supplied artwork.

Source of truth: `morseliink/logo.PNG` - the identity sheet the product owner approved.
Nothing here is hand-drawn: the tile is located in that image, its gradient is fitted, and the
signal mark (two bars, a 2x3 dot cluster whose right column is faded, and the transmit bar) is
traced from the pixels. The trace is then *verified* by rasterising it back and diffing it
against the artwork, so the vector assets cannot silently drift from the design.

Outputs
  res/mipmap-*/ic_launcher.png             legacy launcher (exact artwork, all 5 densities)
  res/mipmap-*/ic_launcher_round.png       legacy round (artwork, circle-masked)
  res/drawable/ic_launcher_foreground.xml  adaptive foreground (traced mark, 108dp viewport)
  res/drawable/ic_launcher_background.xml  adaptive background (fitted gradient)
  res/drawable/ic_mc_mark_{16,24,32,48}.xml      in-app mark (dark ink)
  res/drawable/ic_mc_mark_{16,24,32,48}_light.xml
  res/drawable/ic_stat_mc.xml              white notification silhouette

Run from the repository root:  python3 tools/make_icons.py [--check]
"""

import math
import os
import sys
from collections import deque

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
LOGO = os.path.join(ROOT, "morseliink", "logo.PNG")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = 108.0        # adaptive icons are always a 108dp viewport
SAFE_ZONE = 72.0        # ...of which a launcher mask may show the central 72dp
TILE_IN_ADAPTIVE = 1.0   # the artwork tile fills the adaptive viewport edge to edge, so any
                         # launcher mask crops it cleanly instead of exposing the corners
MARK_RATIO = 0.42        # the mark's width as a fraction of the tile (63 of 150 in the artwork)
DARK_RATIO = 0.62       # a pixel darker than this fraction of its background is "mark"


def lum(p):
    return 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]


def is_tile(p):
    """Bright brand-orange, i.e. the tile - not the dark backdrop around it."""
    r, g, b = p
    return r > 190 and 90 < g < 235 and b < 120


# ------------------------------------------------------------------ measurement
def find_tile(img):
    """Largest connected blob of brand-orange = the logo tile. Returns (x0, y0, x1, y1)."""
    w, h = img.size
    px = img.load()
    seen = bytearray(w * h)
    best = None
    for y0 in range(h):
        for x0 in range(w):
            if seen[y0 * w + x0] or not is_tile(px[x0, y0]):
                continue
            stack = deque([(x0, y0)])
            seen[y0 * w + x0] = 1
            minx = maxx = x0
            miny = maxy = y0
            n = 0
            while stack:
                x, y = stack.pop()
                n += 1
                if x < minx:
                    minx = x
                if x > maxx:
                    maxx = x
                if y < miny:
                    miny = y
                if y > maxy:
                    maxy = y
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and not seen[ny * w + nx] and is_tile(px[nx, ny]):
                        seen[ny * w + nx] = 1
                        stack.append((nx, ny))
            if best is None or n > best[0]:
                best = (n, minx, miny, maxx, maxy)
    if best is None:
        raise SystemExit("no brand-orange tile found in %s" % LOGO)
    return best[1], best[2], best[3], best[4]


def solve3(a, b):
    """Tiny Gauss-Jordan for the 3x3 background-plane fit (keeps numpy out of the toolchain)."""
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for col in range(3):
        piv = max(range(col, 3), key=lambda r: abs(m[r][col]))
        m[col], m[piv] = m[piv], m[col]
        d = m[col][col]
        if abs(d) < 1e-12:
            raise SystemExit("singular background fit")
        for j in range(col, 4):
            m[col][j] /= d
        for r in range(3):
            if r == col:
                continue
            f = m[r][col]
            for j in range(col, 4):
                m[r][j] -= f * m[col][j]
    return m[0][3], m[1][3], m[2][3]


def fit_planes(tile):
    """Fit colour = a*x + b*y + c per channel over the bright (non-mark) tile pixels."""
    w, h = tile.size
    px = tile.load()
    n = sx = sy = sxx = syy = sxy = 0.0
    acc = [[0.0] * 3 for _ in range(3)]
    rhs = [[0.0] * 3 for _ in range(3)]
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if p[0] <= 120:          # dark: mark, backdrop or the shadow under the tile
                continue
            n += 1
            sx += x
            sy += y
            sxx += x * x
            syy += y * y
            sxy += x * y
            for c in range(3):
                rhs[c][0] += x * p[c]
                rhs[c][1] += y * p[c]
                rhs[c][2] += p[c]
    mat = [[sxx, sxy, sx], [sxy, syy, sy], [sx, sy, n]]
    return [solve3(mat, rhs[c]) for c in range(3)]


def plane(planes, x, y):
    return tuple(min(255.0, max(0.0, planes[c][0] * x + planes[c][1] * y + planes[c][2]))
                 for c in range(3))


def corner_radius(tile):
    """Fit the rounded-square corner radius of the tile from its own edge."""
    w, h = tile.size
    px = tile.load()
    edge = []
    for y in range(0, int(h * 0.35)):
        for x in range(w):
            if is_tile(px[x, y]):
                edge.append((x, y))
                break
    best, best_err = None, None
    for r10 in range(120, 420, 5):
        r = r10 / 10.0
        err = 0.0
        for (x, y) in edge:
            # distance from the corner-circle centre, only meaningful inside the arc region
            dx = max(0.0, r - x)
            dy = max(0.0, r - y)
            d = math.hypot(r - x, r - y) if (dx > 0 or dy > 0) else 0.0
            err += (d - r) ** 2
        if best_err is None or err < best_err:
            best, best_err = r, err
    return best


def local_background(tile, radius=25):
    """Median-filtered tile: a robust local background for masking (a linear plane drifts
    over the artwork's gloss, which used to split single dots into two fragments)."""
    return tile.filter(ImageFilter.MedianFilter(size=radius)).load()


def dilate(points, rounds=1):
    """Grow a pixel set so a mark that anti-aliases into two lobes labels as one shape."""
    cur = set(points)
    for _ in range(rounds):
        nxt = set(cur)
        for (x, y) in cur:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    nxt.add((x + dx, y + dy))
        cur = nxt
    return cur


def components(mask):
    """8-connected components of a {(x, y): ratio} dict."""
    seen = set()
    out = []
    for start in list(mask):
        if start in seen:
            continue
        stack = [start]
        seen.add(start)
        comp = []
        while stack:
            x, y = stack.pop()
            comp.append((x, y))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    q = (x + dx, y + dy)
                    if q in mask and q not in seen:
                        seen.add(q)
                        stack.append(q)
        out.append(comp)
    return out


class Shape:
    """One traced element of the mark, in tile-normalised units (0..1 of the tile side)."""

    def __init__(self, kind, x, y, w, h, alpha):
        self.kind = kind        # "bar" | "hbar" | "dot"
        self.x, self.y, self.w, self.h = x, y, w, h
        self.alpha = alpha
        self.r = (min(w, h) / 2.0) if kind != "dot" else min(w, h) / 2.0

    def __repr__(self):
        return "%-4s x%.3f y%.3f w%.3f h%.3f a%.2f" % (
            self.kind, self.x, self.y, self.w, self.h, self.alpha)

    def contains(self, px, py):
        cx, cy = self.x + self.w / 2.0, self.y + self.h / 2.0
        if self.kind == "dot":
            return math.hypot(px - cx, py - cy) <= self.r
        rx, ry = self.w / 2.0, self.h / 2.0
        rr = min(self.r, rx, ry)
        dx, dy = abs(px - cx), abs(py - cy)
        if dx > rx or dy > ry:
            return False
        if dx <= rx - rr or dy <= ry - rr:
            return True
        return math.hypot(dx - (rx - rr), dy - (ry - rr)) <= rr


def trace(tile, planes):
    """Trace the mark out of the tile into tile-normalised shapes."""
    size = tile.size[0]
    px = tile.load()
    bg = local_background(tile)
    mask = mask_of(tile)
    ink = min((px[x, y] for (x, y) in mask), key=lum)
    ink_lum = lum(ink)
    shapes = []
    for comp in sorted(components(dilate(mask)), key=len, reverse=True):
        comp = [p for p in comp if p in mask]      # measure the real pixels, not the dilation
        if len(comp) < 25:
            continue
        xs = [p[0] for p in comp]
        ys = [p[1] for p in comp]
        x, y = min(xs) - 0.5, min(ys) - 0.5
        w, h = max(xs) - min(xs) + 1.0, max(ys) - min(ys) + 1.0
        core = min(comp, key=lambda p: mask[p])
        b_lum = lum(bg[core[0], core[1]])
        obs = lum(px[core[0], core[1]])
        # The faded dot column is the ink drawn at partial alpha, so solve for it against the
        # fitted background instead of storing a second colour.
        alpha = (b_lum - obs) / (b_lum - ink_lum) if b_lum > ink_lum + 1 else 1.0
        alpha = max(0.0, min(1.0, alpha))
        if w > h * 1.8:
            kind = "hbar"
        elif h > w * 1.4:
            kind = "bar"
        else:
            kind = "dot"
        shapes.append(Shape(kind, x / size, y / size, w / size, h / size, alpha))
    shapes.sort(key=lambda s: (s.y, s.x))
    return shapes, ink, ink_lum


def cluster(values, tol):
    """Group near-equal coordinates (anti-aliased edges) into one grid line."""
    groups = []
    for v in sorted(values):
        if not groups or v - groups[-1][-1] > tol:
            groups.append([v])
        else:
            groups[-1].append(v)
    return [sum(g) / len(g) for g in groups]


def snap(shapes):
    """Snap the traced numbers to the design's grid so the vector is clean and symmetric."""
    bars = [s for s in shapes if s.kind == "bar"]
    dots = [s for s in shapes if s.kind == "dot"]
    hbar = [s for s in shapes if s.kind == "hbar"]
    out = []
    if len(bars) == 2:
        w = sum(b.w for b in bars) / 2.0
        h = sum(b.h for b in bars) / 2.0
        top = sum(b.y for b in bars) / 2.0
        lefts = sorted(b.x for b in bars)
        out.append(Shape("bar", lefts[0], top, w, h, 1.0))
        out.append(Shape("bar", lefts[1], top, w, h, 1.0))
    else:
        out.extend(bars)
    if len(dots) == 6:
        r = sum(min(d.w, d.h) for d in dots) / 6.0
        # cluster the centres: anti-aliasing puts two dots of the same column half a pixel apart
        cols = cluster([d.x + d.w / 2.0 for d in dots], 0.05)
        rows = cluster([d.y + d.h / 2.0 for d in dots], 0.05)
        for i, cx in enumerate(cols):
            col = [d for d in dots if abs(d.x + d.w / 2.0 - cx) < 0.05]
            alpha = max((d.alpha for d in col), default=1.0)
            for cy in rows:
                out.append(Shape("dot", cx - r / 2.0, cy - r / 2.0, r, r, alpha))
    else:
        out.extend(dots)
    out.extend(hbar)
    return out


def rasterise(shapes, size, ss=3):
    """Render the traced mark to an alpha mask - used to verify the trace against the artwork."""
    scale = size * ss
    img = Image.new("L", (scale, scale), 0)
    d = ImageDraw.Draw(img)
    for s in shapes:
        a = int(round(s.alpha * 255))
        x0, y0 = s.x * scale, s.y * scale
        x1, y1 = (s.x + s.w) * scale, (s.y + s.h) * scale
        if s.kind == "dot":
            d.ellipse([x0, y0, x1 - 1, y1 - 1], fill=a)
        else:
            d.rounded_rectangle([x0, y0, x1 - 1, y1 - 1], radius=s.r * scale, fill=a)
    return img.resize((size, size), Image.LANCZOS)


def mask_of(tile):
    """The mark pixels of the artwork, as {pixel: darkness ratio}."""
    size = tile.size[0]
    px = tile.load()
    bg = local_background(tile)
    lo, hi = int(size * 0.20), int(size * 0.80)
    out = {}
    for y in range(lo, hi):
        for x in range(lo, hi):
            b = lum(bg[x, y])
            if b < 40:
                continue
            if lum(px[x, y]) / b < DARK_RATIO:
                out[(x, y)] = lum(px[x, y]) / b
    return out


def source_mark_mask(tile):
    """The artwork's own mark mask (binary), used to verify the trace geometrically."""
    size = tile.size[0]
    img = Image.new("L", (size, size), 0)
    out = img.load()
    for (x, y) in mask_of(tile):
        out[x, y] = 255
    return img


# -------------------------------------------------------------------- toolchain
def ensure_tile_image():
    """The artwork tile, alpha-masked to its rounded square."""
    src = Image.open(LOGO).convert("RGB")
    x0, y0, x1, y1 = find_tile(src)
    tile = src.crop((x0, y0, x1 + 1, y1 + 1))
    ss = 4
    size = tile.size[0]
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * ss - 1, size * ss - 1], radius=corner_radius(tile) * ss, fill=255)
    tile = tile.convert("RGBA")
    tile.putalpha(mask.resize((size, size), Image.LANCZOS))
    return tile


def write_png(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    return os.path.getsize(path)


def rr(x, y, w, h, r):
    return ("M%.3f,%.3f h%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f v%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f "
            "h%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f v%.3f a%.3f,%.3f 0 0 1 %.3f,%.3f Z"
            % (x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, -r, r, -(w - 2 * r),
               r, r, -r, r, -(h - 2 * r), r, r, -r, -r))


def circle(cx, cy, r):
    return "M%.3f,%.3f a%.3f,%.3f 0 1 0 %.3f,0 a%.3f,%.3f 0 1 0 %.3f,0 Z" % (
        cx - r, cy, r, r, 2 * r, r, r, -2 * r)


def mark_paths(shapes, ink_hex, size, cx=None, cy=None, width=None):
    """Vector path elements for the traced mark, centred on (cx, cy) in a `size` viewport.

    `width` is how wide the mark is drawn, in viewport units. The artwork has it at
    MARK_RATIO of the icon's width, so the same ratio is applied to whatever surface the
    mark lands on - which keeps the logo looking like the logo at every size.
    """
    xs = [s.x for s in shapes] + [s.x + s.w for s in shapes]
    ys = [s.y for s in shapes] + [s.y + s.h for s in shapes]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    if width is None:
        width = size
    f = width / (maxx - minx)
    cx = size / 2.0 if cx is None else cx
    cy = size / 2.0 if cy is None else cy
    ox = cx - (minx + maxx) / 2.0 * f
    oy = cy - (miny + maxy) / 2.0 * f
    out = []
    for s in shapes:
        a = int(round(s.alpha * 255))
        color = "%02X%s" % (a, ink_hex[1:]) if a < 255 else ink_hex
        x, y, sw, sh = ox + s.x * f, oy + s.y * f, s.w * f, s.h * f
        path = circle(x + sw / 2.0, y + sh / 2.0, min(sw, sh) / 2.0) if s.kind == "dot" \
            else rr(x, y, sw, sh, s.r * f)
        out.append('<path android:fillColor="%s" android:pathData="%s"/>' % (color, path))
    return "\n    ".join(out)


def vector(path, width_dp, height_dp, vw, vh, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                 '    android:width="%gdp"\n    android:height="%gdp"\n'
                 '    android:viewportWidth="%g"\n    android:viewportHeight="%g">\n'
                 '    %s\n</vector>\n' % (width_dp, height_dp, vw, vh, body))


def gradient_vector(path, planes, tile_side):
    """Adaptive background: the fitted gradient, drawn over the scaled-up artwork tile."""
    s = ADAPTIVE * TILE_IN_ADAPTIVE / tile_side
    o = (ADAPTIVE - tile_side * s) / 2.0
    c0 = plane(planes, 0, 0)
    c1 = plane(planes, tile_side, tile_side)
    hexes = ["#FF%02X%02X%02X" % tuple(int(round(v)) for v in (c0, c1)[i]) for i in range(2)]
    with open(path, "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                 '    android:width="108dp"\n    android:height="108dp"\n'
                 '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
                 '    <path android:pathData="M0,0h108v108h-108z">\n'
                 '        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">\n'
                 '            <gradient android:type="linear"\n'
                 '                android:startX="%.3f" android:startY="%.3f"\n'
                 '                android:endX="%.3f" android:endY="%.3f">\n'
                 '                <item android:offset="0" android:color="%s"/>\n'
                 '                <item android:offset="1" android:color="%s"/>\n'
                 '            </gradient>\n'
                 '        </aapt:attr>\n'
                 '    </path>\n</vector>\n'
                 % (o, o, o + tile_side * s, o + tile_side * s, hexes[0], hexes[1]))
    return hexes


def adaptive_xml(path, background):
    with open(path, "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                 '    <background android:drawable="%s"/>\n'
                 '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                 '</adaptive-icon>\n' % background)


def main():
    check = "--check" in sys.argv
    src = Image.open(LOGO).convert("RGB")
    x0, y0, x1, y1 = find_tile(src)
    tile = src.crop((x0, y0, x1 + 1, y1 + 1))
    tile_side = tile.size[0]
    planes = fit_planes(tile)
    shapes, ink, ink_lum = trace(tile, planes)
    shapes = snap(shapes)
    ink_hex = "#%02X%02X%02X" % ink

    print("artwork      %s  tile %dx%d at (%d,%d)" % (os.path.basename(LOGO), tile_side,
                                                      tile_side, x0, y0))
    print("ink          %s  (luminance %.1f)" % (ink_hex, ink_lum))
    print("corner radius %.1fpx of %d  (%.1f%%)" % (corner_radius(tile), tile_side,
                                                    100.0 * corner_radius(tile) / tile_side))
    print("gradient     %s -> %s" % (plane(planes, 0, 0), plane(planes, tile_side, tile_side)))
    for s in shapes:
        print("  traced     %s" % s)

    # --- verification: trace the artwork, then rasterise the trace and diff the two masks
    ref = source_mark_mask(tile)
    got = rasterise(shapes, tile_side)

    # (a) geometry: every element of the model must sit within a pixel of an artwork element
    worst = 0.0
    for comp in components(mask_of(tile)):
        comp = [p for p in comp if p is not None]
        if len(comp) < 25:
            continue
        xs = [p[0] for p in comp]
        ys = [p[1] for p in comp]
        src = (min(xs), min(ys), max(xs), max(ys))
        cx, cy = (src[0] + src[2]) / 2.0 / tile_side, (src[1] + src[3]) / 2.0 / tile_side
        near = min(shapes, key=lambda s: abs(s.x + s.w / 2 - cx) + abs(s.y + s.h / 2 - cy))
        model = (near.x * tile_side, near.y * tile_side,
                 (near.x + near.w) * tile_side, (near.y + near.h) * tile_side)
        worst = max(worst, max(abs(a - b) for a, b in zip(src, model)))
    print("trace check  worst element edge deviation %.2fpx" % worst)
    if worst > 1.6:
        raise SystemExit("tracing error: an element is %.2fpx away from the artwork" % worst)

    # (b) coverage: boundary-tolerant IoU (1px of anti-aliasing is not a mismatch)
    ref_d = ref.filter(ImageFilter.MaxFilter(3))
    got_d = got.filter(ImageFilter.MaxFilter(3))
    rp, rdp, gp, gdp = ref.load(), ref_d.load(), got.load(), got_d.load()
    union = inter = 0
    for y in range(tile_side):
        for x in range(tile_side):
            r, g = rp[x, y] > 110, gp[x, y] > 110
            if r or g:
                union += 1
                if (r and gdp[x, y] > 110) or (g and rdp[x, y] > 110):
                    inter += 1
    iou = inter / float(max(1, union))
    print("trace check  boundary-tolerant IoU %.3f over %d mark pixels" % (iou, union))
    if iou < 0.97:
        raise SystemExit("trace does not match the artwork (IoU %.3f)" % iou)
    if check:
        print("--check: trace verified, nothing written")
        return

    # --- legacy launcher icons: the artwork itself, exactly
    art = ensure_tile_image()
    for name, size in DENSITIES.items():
        dirpath = os.path.join(RES, "mipmap-%s" % name)
        write_png(os.path.join(dirpath, "ic_launcher.png"),
                  art.resize((size, size), Image.LANCZOS))
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
        round_icon = art.resize((size * 4, size * 4), Image.LANCZOS)
        round_icon.putalpha(mask)
        write_png(os.path.join(dirpath, "ic_launcher_round.png"),
                  round_icon.resize((size, size), Image.LANCZOS))

    # --- adaptive icon (API 26+): traced mark over the fitted gradient
    vector(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
           108, 108, 108, 108,
           mark_paths(shapes, ink_hex, ADAPTIVE, cx=54.0, cy=54.0,
                      width=SAFE_ZONE * MARK_RATIO))
    gradient_vector(os.path.join(RES, "drawable", "ic_launcher_background.xml"), planes, tile_side)
    for name in ("ic_launcher", "ic_launcher_round"):
        adaptive_xml(os.path.join(RES, "mipmap-anydpi-v26", "%s.xml" % name),
                     "@drawable/ic_launcher_background")

    # --- in-app marks (toolbars, headers, empty states) + notification silhouette
    for dp in (16, 24, 32, 48):
        vector(os.path.join(RES, "drawable", "ic_mc_mark_%d.xml" % dp), dp, dp, 24, 24,
               mark_paths(shapes, ink_hex, 24, width=20.0))
        vector(os.path.join(RES, "drawable", "ic_mc_mark_%d_light.xml" % dp), dp, dp, 24, 24,
               mark_paths(shapes, "#F5F5F5", 24, width=20.0))
    vector(os.path.join(RES, "drawable", "ic_stat_mc.xml"), 24, 24, 24, 24,
           mark_paths(shapes, "#FFFFFFFF", 24, width=20.0))
    print("icons written to", RES)


if __name__ == "__main__":
    main()
