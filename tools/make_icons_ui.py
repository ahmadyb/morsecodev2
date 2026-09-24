#!/usr/bin/env python3
"""
Generates the in-app icon set as vector drawables.

The app ships without an icon library (no Material Components dependency), so every glyph the
UI needs is written here as a plain filled <vector>. Icons are always tinted at runtime with
the current accent / text colour, so they are generated as white shapes on a 24dp viewport.
"""

import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")


# ---------------------------------------------------------------- path helpers
def rect(x, y, w, h, r=0.0):
    if r <= 0:
        return "M%.2f,%.2f h%.2f v%.2f h%.2f Z" % (x, y, w, h, -w)
    return (
        "M%.2f,%.2f h%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f v%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f "
        "h%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f v%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f Z"
        % (x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, -r, r, -(w - 2 * r),
           r, r, -r, r, -(h - 2 * r), r, r, -r, -r)
    )


def circle(cx, cy, r):
    return "M%.2f,%.2f a%.2f,%.2f 0 1 0 %.2f,0 a%.2f,%.2f 0 1 0 %.2f,0 Z" % (
        cx - r, cy, r, r, 2 * r, r, r, -2 * r)


def ring(cx, cy, r, t):
    ri = r - t
    return (circle(cx, cy, r) + " " + circle(cx, cy, ri))


def poly(points):
    out = "M%.2f,%.2f " % points[0]
    for p in points[1:]:
        out += "L%.2f,%.2f " % p
    return out + "Z"


def stroke(points, width):
    """Crude polyline drawn as a quad strip - keeps every icon a single filled path."""
    import math
    parts = []
    for i in range(len(points) - 1):
        (x1, y1), (x2, y2) = points[i], points[i + 1]
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / length * width / 2.0, dx / length * width / 2.0
        parts.append(poly([(x1 + nx, y1 + ny), (x2 + nx, y2 + ny),
                           (x2 - nx, y2 - ny), (x1 - nx, y1 - ny)]))
    return " ".join(parts)


# ---------------------------------------------------------------- the icon set
def icons():
    ic = {}

    # navigation
    ic["ic_nav_connect"] = (
        ring(12, 12, 8.4, 1.7) + " " + ring(12, 12, 3.2, 1.7) + " " + circle(12, 12, 1.1)
    )
    ic["ic_nav_files"] = ("M3.6,6.4 a2,2 0 0 1 2,-2 h3.6 l2,2.2 h7.2 a2,2 0 0 1 2,2 v9.2 a2,2 0 0 1 -2,2 "
                          "H5.6 a2,2 0 0 1 -2,-2 Z")
    ic["ic_nav_history"] = ring(12, 12, 8.6, 1.7) + " " + stroke([(12, 6.6), (12, 12.4), (16.2, 14.6)], 1.9)
    ic["ic_nav_settings"] = (
        stroke([(4, 7.2), (20, 7.2)], 1.7) + " " + circle(9.2, 7.2, 2.1) +
        " " + stroke([(4, 12), (20, 12)], 1.7) + " " + circle(15.2, 12, 2.1) +
        " " + stroke([(4, 16.8), (20, 16.8)], 1.7) + " " + circle(10.8, 16.8, 2.1)
    )

    # actions
    ic["ic_plus"] = stroke([(12, 5), (12, 19)], 2.2) + " " + stroke([(5, 12), (19, 12)], 2.2)
    ic["ic_list"] = (rect(4, 6, 16, 1.9, 0.9) + " " + rect(4, 11, 16, 1.9, 0.9) +
                     " " + rect(4, 16, 16, 1.9, 0.9))
    ic["ic_pause"] = rect(7, 5.5, 3.2, 13, 0.9) + " " + rect(13.8, 5.5, 3.2, 13, 0.9)
    ic["ic_minimize"] = stroke([(5.5, 12), (18.5, 12)], 2.2)
    ic["ic_end"] = stroke([(6.5, 6.5), (17.5, 17.5)], 2.2) + " " + stroke([(17.5, 6.5), (6.5, 17.5)], 2.2)
    ic["ic_back"] = (stroke([(14.5, 5.5), (8, 12), (14.5, 18.5)], 2.1))
    ic["ic_more"] = circle(12, 5.4, 1.9) + " " + circle(12, 12, 1.9) + " " + circle(12, 18.6, 1.9)
    ic["ic_search"] = ring(10.6, 10.6, 5.6, 1.8) + " " + stroke([(14.8, 14.8), (19.4, 19.4)], 2.0)
    ic["ic_trash"] = (rect(9, 4.4, 6, 1.8, 0.6) + " " + rect(4.6, 7.4, 14.8, 1.8, 0.6) +
                      " " + poly([(6.6, 10.4), (17.4, 10.4), (16.2, 20), (7.8, 20)]) +
                      " " + rect(10.6, 12.4, 1.4, 5.6, 0.5) + " " + rect(12, 12.4, 1.4, 5.6, 0.5))
    ic["ic_share"] = (circle(17.6, 6.2, 2.1) + " " + circle(6.4, 12, 2.1) + " " + circle(17.6, 17.8, 2.1) +
                      " " + stroke([(8.3, 11), (15.7, 7.2)], 1.6) + " " + stroke([(8.3, 13), (15.7, 16.8)], 1.6))
    ic["ic_send_up"] = stroke([(12, 19), (12, 6.6)], 2.1) + " " + poly([(12, 4.2), (18.2, 11), (5.8, 11)])
    ic["ic_receive_down"] = stroke([(12, 5), (12, 17.4)], 2.1) + " " + poly([(12, 19.8), (5.8, 13), (18.2, 13)])
    ic["ic_check"] = stroke([(5.2, 12.6), (10, 17.2), (18.8, 7.2)], 2.4)
    ic["ic_close"] = stroke([(6.5, 6.5), (17.5, 17.5)], 2.0) + " " + stroke([(17.5, 6.5), (6.5, 17.5)], 2.0)
    ic["ic_chevron_down"] = stroke([(6.5, 9.5), (12, 15), (17.5, 9.5)], 2.1)
    ic["ic_chevron_right"] = stroke([(9.5, 6), (15.5, 12), (9.5, 18)], 2.1)
    ic["ic_refresh"] = (ring(12, 12, 7.6, 1.8) + " " + poly([(12, 2.6), (17.4, 6.6), (12, 10.6)]))
    ic["ic_copy"] = (rect(8.4, 8.4, 11, 12, 1.6) + " " + rect(4.6, 3.6, 11, 12, 1.6) +
                     " " + rect(6.4, 5.4, 7.4, 8.4, 0.9))
    ic["ic_download"] = (stroke([(12, 4.4), (12, 14)], 2.1) + " " + poly([(12, 17.6), (6.4, 11.4), (17.6, 11.4)]) +
                         " " + stroke([(5.6, 20), (18.4, 20)], 2.1))
    ic["ic_upload"] = (stroke([(12, 16), (12, 6.4)], 2.1) + " " + poly([(12, 4), (17.6, 10.2), (6.4, 10.2)]) +
                       " " + stroke([(5.6, 20), (18.4, 20)], 2.1))
    ic["ic_qr"] = (rect(4, 4, 6.4, 6.4, 1.2) + " " + rect(13.6, 4, 6.4, 6.4, 1.2) +
                   " " + rect(4, 13.6, 6.4, 6.4, 1.2) + " " + rect(13.6, 13.6, 3, 3, 0.6) +
                   " " + rect(17.6, 17.6, 2.4, 2.4, 0.5) + " " + rect(13.6, 17.6, 2.4, 2.4, 0.5) +
                   " " + rect(17.6, 13.6, 2.4, 2.4, 0.5))

    # status / media
    ic["ic_play"] = poly([(7.5, 4.6), (19.4, 12), (7.5, 19.4)])
    ic["ic_rotate"] = (ring(12, 12.6, 7.2, 1.8) + " " + poly([(12, 2), (17.6, 6.2), (12, 10.4)]))
    ic["ic_info"] = ring(12, 12, 8.6, 1.7) + " " + circle(12, 7.2, 1.3) + " " + rect(10.9, 10, 2.2, 7, 1)
    ic["ic_edit"] = (poly([(4.4, 19.6), (5.6, 15.2), (15.4, 5.4), (18.6, 8.6), (8.8, 18.4)]) +
                     " " + poly([(16.3, 4.5), (17.8, 3), (21, 6.2), (19.5, 7.7)]))
    ic["ic_heart"] = poly([(12, 20.2), (3.6, 12.4), (3.6, 8.6), (6.6, 5.6), (10.4, 5.6), (12, 7.6),
                           (13.6, 5.6), (17.4, 5.6), (20.4, 8.6), (20.4, 12.4)])
    ic["ic_shuffle"] = (stroke([(4, 8), (9, 8), (15, 16), (20, 16)], 1.9) +
                        " " + poly([(20.4, 16), (17.2, 13.4), (17.2, 18.6)]) +
                        " " + stroke([(4, 16), (9, 16), (15, 8), (20, 8)], 1.9) +
                        " " + poly([(20.4, 8), (17.2, 5.4), (17.2, 10.6)]))
    ic["ic_repeat"] = (stroke([(6, 7.6), (18, 7.6)], 1.9) + " " + poly([(19.6, 7.6), (16.4, 4.8), (16.4, 10.4)]) +
                       " " + stroke([(18, 16.4), (6, 16.4)], 1.9) + " " + poly([(4.4, 16.4), (7.6, 13.6), (7.6, 19.2)]))
    ic["ic_prev"] = (rect(5, 6, 2.4, 12, 1) + " " + poly([(19, 6), (19, 18), (9, 12)]))
    ic["ic_next"] = (rect(16.6, 6, 2.4, 12, 1) + " " + poly([(5, 6), (5, 18), (15, 12)]))
    ic["ic_volume"] = (poly([(4, 9.4), (8, 9.4), (12, 5.4), (12, 18.6), (8, 14.6), (4, 14.6)]) +
                       " " + stroke([(14.6, 9.4), (16.4, 12), (14.6, 14.6)], 1.7) +
                       " " + stroke([(17.4, 7.2), (20, 12), (17.4, 16.8)], 1.7))
    ic["ic_cc"] = (ring(12, 12, 8.6, 1.8) + " " + poly([(10.6, 9.4), (8.4, 9.4), (7.6, 10.4), (7.6, 13.6),
                                                        (8.4, 14.6), (10.6, 14.6), (10.6, 13)]) +
                   " " + poly([(16.4, 9.4), (14.2, 9.4), (13.4, 10.4), (13.4, 13.6), (14.2, 14.6),
                               (16.4, 14.6), (16.4, 13)]))
    ic["ic_fullscreen"] = (stroke([(4, 9), (4, 4), (9, 4)], 1.9) + " " + stroke([(15, 4), (20, 4), (20, 9)], 1.9) +
                           " " + stroke([(20, 15), (20, 20), (15, 20)], 1.9) + " " + stroke([(9, 20), (4, 20), (4, 15)], 1.9))
    ic["ic_lock"] = (rect(5.2, 10.4, 13.6, 9.4, 2.2) + " " + ring(12, 9, 4.4, 1.9) +
                     " " + circle(12, 15, 1.6) + " " + rect(11.3, 15, 1.4, 3.2, 0.6))
    ic["ic_globe"] = (ring(12, 12, 8.6, 1.7) + " " + stroke([(12, 3.4), (12, 20.6)], 1.6) +
                      " " + stroke([(3.4, 12), (20.6, 12)], 1.6) +
                      " " + stroke([(5.4, 7.2), (18.6, 7.2)], 1.4) +
                      " " + stroke([(5.4, 16.8), (18.6, 16.8)], 1.4))
    ic["ic_plane"] = (poly([(3, 11.4), (21, 4), (13.6, 21), (11.4, 13.6)]) + " " + poly([(11.4, 13.6), (21, 4), (9.6, 12.2)]))
    ic["ic_wifi"] = (stroke([(3.6, 9.4), (12, 4.4), (20.4, 9.4)], 1.9) +
                     " " + stroke([(6.6, 13), (12, 9.8), (17.4, 13)], 1.9) + " " + circle(12, 17.2, 1.9))
    ic["ic_bluetooth"] = (stroke([(12, 3), (12, 21), (17.6, 16.2), (6.8, 7.8)], 1.9) +
                          " " + stroke([(12, 3), (17.6, 7.8), (6.8, 16.2), (12, 21)], 1.9))
    ic["ic_battery"] = (rect(3.4, 7.4, 15.4, 9.2, 2) + " " + rect(19.6, 10, 1.6, 4, 0.6))
    ic["ic_warn"] = (poly([(12, 3.2), (22, 20.4), (2, 20.4)]) + " " + rect(11.1, 9, 1.9, 6, 0.8) +
                     " " + circle(12, 17.4, 1.2))
    ic["ic_link"] = (stroke([(9.4, 14.6), (14.6, 9.4)], 1.9) +
                     " " + ring(8, 16, 3.6, 1.8) + " " + ring(16, 8, 3.6, 1.8))
    ic["ic_file"] = (poly([(6, 3), (14, 3), (19, 8), (19, 21), (6, 21)]) +
                     " " + poly([(14, 3), (14, 8), (19, 8)]))
    ic["ic_image"] = (rect(3.6, 5, 16.8, 14, 2.2) + " " + circle(8.6, 9.8, 1.8) +
                      " " + poly([(5.6, 17.6), (11, 11.6), (14.4, 15.2), (16.6, 13.2), (18.8, 17.6)]))
    ic["ic_video"] = (rect(3, 6, 12.6, 12, 2.2) + " " + poly([(17.4, 10.4), (21, 7.4), (21, 16.6), (17.4, 13.6)]))
    ic["ic_music"] = (circle(7.6, 17.4, 3.1) + " " + stroke([(10.7, 17.4), (10.7, 4.6)], 1.9) +
                      " " + poly([(10.7, 4.6), (19.4, 6.6), (19.4, 10.4), (10.7, 8.4)]))
    ic["ic_apk"] = (ring(12, 12, 8.6, 1.7) + " " + stroke([(8.6, 8.6), (15.4, 15.4)], 1.7) +
                    " " + stroke([(15.4, 8.6), (8.6, 15.4)], 1.7))
    ic["ic_folder"] = ("M3.8,7 a2,2 0 0 1 2,-2 h3.4 l1.8,2.2 h8.2 a2,2 0 0 1 2,2 v8.2 a2,2 0 0 1 -2,2 "
                       "H5.8 a2,2 0 0 1 -2,-2 Z")
    ic["ic_stop"] = rect(7, 7, 10, 10, 1.6)
    ic["ic_phone"] = (rect(6.6, 2.6, 10.8, 18.8, 2.4) + " " + rect(9.4, 4.6, 5.2, 13.4, 1) +
                      " " + circle(12, 19.6, 1))
    ic["ic_pc"] = (rect(2.6, 4.4, 18.8, 12.4, 1.8) + " " + rect(10.4, 16.8, 3.2, 2.6, 0.4) +
                   " " + rect(7, 19.4, 10, 1.8, 0.6))
    ic["ic_shield"] = (poly([(12, 2.6), (20, 5.6), (20, 12), (12, 21.4), (4, 12), (4, 5.6)]) +
                       " " + stroke([(8.4, 12), (11.2, 15), (15.8, 9.2)], 2.0))
    ic["ic_grid"] = (rect(4, 4, 7, 7, 1.2) + " " + rect(13, 4, 7, 7, 1.2) +
                     " " + rect(4, 13, 7, 7, 1.2) + " " + rect(13, 13, 7, 7, 1.2))

    return ic


def write(name, paths):
    os.makedirs(OUT, exist_ok=True)
    body = "\n    ".join(
        '<path android:fillColor="#FFFFFFFF" android:pathData="%s"/>' % p for p in paths
    )
    with open(os.path.join(OUT, name + ".xml"), "w") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by tools/make_icons_ui.py - tinted at runtime. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
            '    %s\n</vector>\n' % body
        )


def main():
    ic = icons()
    for name, data in ic.items():
        write(name, [data] if isinstance(data, str) else data)
    print("wrote %d icons to %s" % (len(ic), OUT))


if __name__ == "__main__":
    main()
