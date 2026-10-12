#!/usr/bin/env python3
"""Generate sim/data/landmask_5deg.json (the land mask of the world simulation, `--mode world`).

    python tools/make_landmask.py                       # download Natural Earth 110m land, write the mask
    python tools/make_landmask.py --src ne_110m_land.geojson   # use a file you already have (no network)
    python tools/make_landmask.py --check               # verify that the committed file matches this tool

Source: Natural Earth 110m physical vectors, `ne_110m_land` (public domain),
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson

The mask is 72 columns x 36 rows of 5-degree cells (row 0 = 90N..85N, column 0 = 180W..175W, the same
layout as the `world` frame grid); every cell holds the percentage (0..100) of land, found by testing an
8 x 8 lattice of sample points inside the cell against the land polygons (even-odd rule, scan lines).
The generated file is committed so the simulation needs no network; only this tool does.
Nothing is written outside --out; the download goes to a temporary file that is deleted afterwards.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_PATH = os.path.join(ROOT, "sim", "data", "landmask_5deg.json")

URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson"
COLS, ROWS, DLON, DLAT = 72, 36, 5.0, 5.0
SUB = 8                      # sample points per cell side


def _polygons(geo: dict):
    """Yield every polygon as a list of rings (exterior first), each ring a list of (lon, lat)."""
    for feat in geo["features"]:
        g = feat["geometry"]
        if g["type"] == "Polygon":
            yield [[(p[0], p[1]) for p in ring] for ring in g["coordinates"]]
        elif g["type"] == "MultiPolygon":
            for poly in g["coordinates"]:
                yield [[(p[0], p[1]) for p in ring] for ring in poly]


def land_percent(geo: dict) -> list:
    """ROWS lists of COLS integers (0..100)."""
    ns = COLS * SUB
    nr = ROWS * SUB
    dx = 360.0 / ns
    dy = 180.0 / nr
    hits = [[0] * ns for _ in range(nr)]
    polys = list(_polygons(geo))
    boxes = []
    for rings in polys:
        ys = [p[1] for p in rings[0]]
        boxes.append((min(ys), max(ys)))
    for ry in range(nr):
        lat = 90.0 - (ry + 0.5) * dy
        row = hits[ry]
        for rings, (ylo, yhi) in zip(polys, boxes):
            if lat < ylo or lat > yhi:
                continue
            xs = []
            for ring in rings:
                a = ring[-1]
                for b in ring:
                    # the edge a-b crosses the scan line (half-open rule so a vertex counts once)
                    if (a[1] > lat) != (b[1] > lat):
                        xs.append(a[0] + (lat - a[1]) * (b[0] - a[0]) / (b[1] - a[1]))
                    a = b
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                # sample column c has its centre at -180 + (c + 0.5) * dx
                c0 = max(0, math.ceil((xs[i] + 180.0) / dx - 0.5))
                c1 = min(ns - 1, math.floor((xs[i + 1] + 180.0) / dx - 0.5))
                for c in range(c0, c1 + 1):
                    row[c] = 1
    out = []
    for r in range(ROWS):
        line = []
        for c in range(COLS):
            n = 0
            for j in range(SUB):
                seg = hits[r * SUB + j]
                n += sum(seg[c * SUB:(c + 1) * SUB])
            line.append(int(round(100.0 * n / (SUB * SUB))))
        out.append(line)
    return out


def render(geo: dict) -> str:
    pct = land_percent(geo)
    head = {
        "cols": COLS, "rows": ROWS, "dlon": DLON, "dlat": DLAT,
        "layout": "land_pct[row][col]; row 0 = 90N..85N, col 0 = 180W..175W; percent of the 5-degree cell that is land",
        "source": URL, "dataset": "Natural Earth 110m physical vectors, ne_110m_land",
        "license": "public domain (Natural Earth)", "samples_per_cell": SUB * SUB,
        "generator": "tools/make_landmask.py",
    }
    lines = [json.dumps(pct_row, separators=(",", ":")) for pct_row in pct]
    h = json.dumps(head, separators=(",", ":"), ensure_ascii=False)
    return h[:-1] + ',"land_pct":[\n' + ",\n".join(lines) + "\n]}\n"


def _load(src: str | None) -> dict:
    if src:
        with open(src, "r", encoding="utf-8") as f:
            return json.load(f)
    with tempfile.TemporaryDirectory() as td:
        path = os.path.join(td, "ne_110m_land.geojson")
        print(f"downloading {URL}", file=sys.stderr)
        urllib.request.urlretrieve(URL, path)
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--src", default=None, help="a local ne_110m_land.geojson (default: download)")
    ap.add_argument("--out", default=OUT_PATH)
    ap.add_argument("--check", action="store_true", help="compare with the committed file, write nothing")
    args = ap.parse_args(argv)
    text = render(_load(args.src))
    if args.check:
        try:
            with open(args.out, "r", encoding="utf-8", newline="") as f:
                same = f.read() == text
        except OSError:
            same = False
        print(f"{args.out}: {'in sync' if same else 'OUT OF SYNC'}")
        return 0 if same else 1
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    total = sum(1 for _ in text.splitlines())
    print(f"{args.out}: wrote {COLS}x{ROWS} land mask ({total} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
