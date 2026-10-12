#!/usr/bin/env python3
"""Render the world simulation's fields to PNG files (stdlib only: zlib + struct) so they can be looked at.

    python tools/render_world_fields.py [--out tools/debug_plots/v23] [--seed 5]

Writes (720 x 360 each, equirectangular, north up, 10 px per 5-degree cell, bilinear):
    temp_jan.png  temp_jul.png      2 m temperature, blue (-40 C) .. red (+40 C), at 12 UTC in January / July
    cloud_jul.png cloud_jan.png     cloud cover, dark (clear) .. white (overcast)
    precip_jul.png                  rain intensity
    world_fields.png                the five above stacked, for one-look checking
Overlays: coast (the land mask edge), the ITCZ latitude (dashed white), the subsolar point (yellow cross),
the systems (low = blue ring, high = red ring, cyclone = magenta disc, front = cyan, tornado outbreak = yellow
disc) and the location dots (white).
"""
from __future__ import annotations

import argparse
import math
import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "sim"))

import worldsim as W  # noqa: E402
import presets as P  # noqa: E402

PX = 10
WIDTH, HEIGHT = W.COLS * PX, W.ROWS * PX


def write_png(path: str, rows: list) -> None:
    h, w = len(rows), len(rows[0]) // 3
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def lerp_color(stops, x):
    for (x0, c0), (x1, c1) in zip(stops, stops[1:]):
        if x <= x1:
            t = 0.0 if x1 == x0 else (x - x0) / (x1 - x0)
            t = max(0.0, min(1.0, t))
            return tuple(c0[i] + (c1[i] - c0[i]) * t for i in range(3))
    return stops[-1][1]


TEMP_STOPS = [(-40, (40, 20, 120)), (-20, (50, 90, 200)), (0, (120, 200, 240)), (10, (170, 225, 150)),
              (20, (250, 235, 110)), (30, (240, 140, 50)), (40, (190, 40, 30))]
CLOUD_STOPS = [(0.0, (20, 30, 60)), (0.5, (120, 130, 150)), (1.0, (250, 250, 255))]
PRECIP_STOPS = [(0.0, (15, 20, 30)), (0.2, (30, 80, 160)), (0.5, (60, 200, 220)), (1.0, (250, 250, 120))]


def sample(field, x, y):
    """Bilinear sample at pixel centre (x, y) of the 720x360 image (longitude wraps)."""
    fx = x / PX - 0.5
    fy = y / PX - 0.5
    c0 = math.floor(fx)
    r0 = math.floor(fy)
    tx, ty = fx - c0, fy - r0
    def at(r, c):
        r = min(W.ROWS - 1, max(0, r))
        return field[r * W.COLS + (c % W.COLS)]
    a = at(r0, c0) * (1 - tx) + at(r0, c0 + 1) * tx
    b = at(r0 + 1, c0) * (1 - tx) + at(r0 + 1, c0 + 1) * tx
    return a * (1 - ty) + b * ty


def to_xy(lat, lon):
    return (lon + 180.0) / 360.0 * WIDTH, (90.0 - lat) / 180.0 * HEIGHT


def disc(img, cx, cy, r, color, ring=False):
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        if not 0 <= y < HEIGHT:
            continue
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            xx = x % WIDTH
            d = math.hypot(x - cx, y - cy)
            if (ring and r - 1.6 <= d <= r) or (not ring and d <= r):
                img[y][3 * xx:3 * xx + 3] = bytes(color)


def render(sim, field_name, stops, lo, hi) -> list:
    g = sim._grid[field_name]
    img = []
    for y in range(HEIGHT):
        row = bytearray(3 * WIDTH)
        for x in range(WIDTH):
            v = sample(g, x + 0.5, y + 0.5)
            col = lerp_color(stops, v)
            row[3 * x:3 * x + 3] = bytes(int(max(0, min(255, c))) for c in col)
        img.append(row)
    # coast: pixels on the border between a land cell and a sea cell of the unblurred mask
    raw = W.load_landmask()
    land = [[v >= 0.5 for v in row] for row in raw]
    for y in range(HEIGHT):
        r = y // PX
        for x in range(WIDTH):
            c = x // PX
            edge = False
            if y % PX == 0 and r > 0 and land[r - 1][c] != land[r][c]:
                edge = True
            if x % PX == 0 and land[r][(c - 1) % W.COLS] != land[r][c]:
                edge = True
            if edge:
                img[y][3 * x:3 * x + 3] = bytes((25, 25, 25))
    # ITCZ latitude, dashed
    iy = int(to_xy(sim.itcz(), 0.0)[1])
    for x in range(0, WIDTH, 12):
        for k in range(6):
            if 0 <= iy < HEIGHT:
                img[iy][3 * (x + k):3 * (x + k) + 3] = bytes((255, 255, 255))
    # subsolar point
    sx, sy = to_xy(*sim.sun())
    for k in range(-7, 8):
        for (xx, yy) in ((sx + k, sy), (sx, sy + k)):
            if 0 <= int(yy) < HEIGHT:
                img[int(yy)][3 * (int(xx) % WIDTH):3 * (int(xx) % WIDTH) + 3] = bytes((255, 255, 0))
    # systems
    for s in sim.systems:
        if s["strength"] < 0.05:
            continue
        x, y = to_xy(s["lat"], s["lon"])
        k = s["kind"]
        if k == "low":
            disc(img, x, y, 7, (40, 90, 255), ring=True)
        elif k == "high":
            disc(img, x, y, 7, (255, 60, 40), ring=True)
        elif k == "cyclone":
            disc(img, x, y, 5, (255, 40, 220))
        elif k == "front":
            disc(img, x, y, 3, (0, 230, 230))
        elif k == "tornado_outbreak":
            disc(img, x, y, 5, (255, 255, 0))
    for p in sim.locations:
        x, y = to_xy(p["lat"], p["lon"])
        disc(img, x, y, 2.5, (255, 255, 255))
    return img


def build(month, seed):
    sim = W.WorldSim(seed, 30, month=month, hour=12.0)
    sim.step(1 / 30)
    return sim


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=os.path.join(ROOT, "viewer", "snapshots", "v23", "sim"))
    ap.add_argument("--seed", type=int, default=5)
    args = ap.parse_args(argv)
    jan = build(1.5, args.seed)
    jul = build(7.5, args.seed)
    panels = {
        "temp_jan": render(jan, "temp_c", TEMP_STOPS, -40, 40),
        "temp_jul": render(jul, "temp_c", TEMP_STOPS, -40, 40),
        "cloud_jul": render(jul, "cloud", CLOUD_STOPS, 0, 1),
        "cloud_jan": render(jan, "cloud", CLOUD_STOPS, 0, 1),
        "precip_jul": render(jul, "precip", PRECIP_STOPS, 0, 1),
    }
    for name, img in panels.items():
        write_png(os.path.join(args.out, name + ".png"), img)
        print("wrote", os.path.join(args.out, name + ".png"))
    stack = []
    for name in ("temp_jan", "temp_jul", "cloud_jan", "cloud_jul", "precip_jul"):
        stack.extend(panels[name])
        stack.append(bytearray(b"\x00" * (3 * WIDTH)))
    write_png(os.path.join(args.out, "world_fields.png"), stack)
    print("wrote", os.path.join(args.out, "world_fields.png"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
