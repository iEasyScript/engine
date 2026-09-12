#!/usr/bin/env python3
"""Render client/launcher/assets/projectx.ico from the launcher's #i-crest brand mark.

Geometry is the `#i-crest` symbol in client/launcher/ui/index.html; colours are the
`.brand-crest` rule in client/launcher/ui/style.css. Re-run after either changes.
"""

import math
import struct
import sys
from io import BytesIO

import numpy as np
from PIL import Image, ImageDraw

BRASS = (246, 228, 187, 255)
DISC_INNER = (58, 67, 81)
DISC_OUTER = (23, 29, 37)

SIZES = [16, 24, 32, 48, 64, 128, 256]
SS = 8
VIEWBOX = 32.0


def cubic(p0, c1, c2, p1, steps=48):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1.0 - t
        x = u * u * u * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * p1[0]
        y = u * u * u * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * p1[1]
        out.append((x, y))
    return out


# M16 2 4 6v10c0 7 5 12 12 14 7-2 12-7 12-14V6L16 2Z
SHIELD = [(16, 2), (4, 6), (4, 16)]
SHIELD += cubic((4, 16), (4, 23), (9, 28), (16, 30))
SHIELD += cubic((16, 30), (23, 28), (28, 23), (28, 16))
SHIELD += [(28, 6)]

# M16 6v19M12.8 10.2h6.4M9.7 14.6h12.6M11.5 20.4l9-2
INNER = [
    [(16, 6), (16, 25)],
    [(12.8, 10.2), (19.2, 10.2)],
    [(9.7, 14.6), (22.3, 14.6)],
    [(11.5, 20.4), (20.5, 18.4)],
]


def medallion(px):
    yy, xx = np.mgrid[0:px, 0:px].astype(np.float64)
    cx, cy = 0.32 * px, 0.26 * px
    extent = max(math.hypot(cx - x, cy - y) for x in (0, px) for y in (0, px))
    t = np.clip(np.hypot(xx - cx, yy - cy) / (0.70 * extent), 0.0, 1.0)[..., None]

    rgb = np.array(DISC_INNER, np.float64) * (1 - t) + np.array(DISC_OUTER, np.float64) * t
    img = np.concatenate([rgb, np.full((px, px, 1), 255.0)], axis=2)

    r = px / 2.0 - max(1.0, px * 0.012)
    outside = np.hypot(xx - px / 2.0, yy - px / 2.0) > r
    img[outside] = 0
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def stroke(draw, pts, width, closed=False):
    seq = list(pts) + ([pts[0]] if closed else [])
    w = max(1, int(round(width)))
    draw.line(seq, fill=BRASS, width=w, joint="curve")
    r = w / 2.0
    for x, y in seq:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=BRASS)


def render(size):
    px = size * SS
    img = medallion(px)
    draw = ImageDraw.Draw(img)

    # Rim: the 1px black border in .brand-crest.
    inset = max(1.0, px * 0.012)
    draw.ellipse([inset, inset, px - inset, px - inset],
                 outline=(0, 0, 0, 255), width=max(1, int(px * 0.016)))

    detailed = size >= 32
    span = px * (0.55 if detailed else 0.64)
    scale = span / VIEWBOX
    ox = oy = (px - span) / 2.0

    def place(seg):
        return [(ox + x * scale, oy + y * scale) for x, y in seg]

    # Below ~32px the inner charges collapse into noise, so the shield carries the
    # mark alone and needs a floor on stroke weight to survive the downsample.
    floor = SS * (1.6 if detailed else 2.1)
    stroke(draw, place(SHIELD), max(1.6 * scale, floor), closed=True)
    if detailed:
        for seg in INNER:
            stroke(draw, place(seg), max(1.5 * scale, SS * 1.5))

    return img.resize((size, size), Image.LANCZOS)


def bmp_entry(img):
    w, h = img.size
    px = img.load()
    xor = bytearray()
    for y in range(h - 1, -1, -1):
        for x in range(w):
            r, g, b, a = px[x, y]
            xor += bytes((b, g, r, a))
    row = ((w + 31) // 32) * 4
    and_mask = bytes(row * h)
    header = struct.pack("<IiiHHIIiiII", 40, w, h * 2, 1, 32, 0, len(xor) + len(and_mask), 0, 0, 0, 0)
    return header + bytes(xor) + and_mask


def write_ico(path, images):
    entries, blobs = [], []
    for img in images:
        w, h = img.size
        if w >= 128:
            buf = BytesIO()
            img.save(buf, format="PNG")
            blobs.append(buf.getvalue())
        else:
            blobs.append(bmp_entry(img))
        entries.append((w, h))

    offset = 6 + 16 * len(images)
    out = bytearray(struct.pack("<HHH", 0, 1, len(images)))
    for (w, h), blob in zip(entries, blobs):
        out += struct.pack("<BBBBHHII", w % 256, h % 256, 0, 0, 1, 32, len(blob), offset)
        offset += len(blob)
    for blob in blobs:
        out += blob
    with open(path, "wb") as fh:
        fh.write(bytes(out))


def main():
    dest = sys.argv[1] if len(sys.argv) > 1 else "projectx.ico"
    write_ico(dest, [render(s) for s in SIZES])
    print(f"wrote {dest} ({', '.join(str(s) for s in SIZES)})")


if __name__ == "__main__":
    main()
