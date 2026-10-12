#!/usr/bin/env python3
"""A diagnostic plot (NOT a picture of the game): water depth on one town's street against time, for the three drain classes,
under one real storm of the sim.

    python tools/plot_flood.py                                  # cumulonimbus over the biggest town of seed 12
    python tools/plot_flood.py --type supercell --seed 14 --out tools/debug_plots/v24/flood_supercell.png

Drawn with Pillow (already used by the other tools). The sim runs the storm (tools/measure_rain_hits.py), the recorded
hits on the town's span are replayed through `water.WaterField` for each class size, and the depths are drawn with the
`flooded` line (3 px) and the curb (10 px).
"""
from __future__ import annotations

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import measure_rain_hits as M                   # noqa: E402
import measure_flood_table as T                 # noqa: E402

COLORS = {"small": (200, 60, 50), "normal": (220, 150, 30), "large": (40, 120, 200)}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sim-dir", default=os.path.join(HERE, "..", "sim"))
    ap.add_argument("--seed", type=int, default=12)
    ap.add_argument("--type", default="cumulonimbus")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--out", default=os.path.join(HERE, "debug_plots", "v24", "flood_cumulonimbus.png"))
    a = ap.parse_args(argv)
    simulation = M._load(a.sim_dir)
    import water
    from PIL import Image, ImageDraw
    sim = M.build(simulation, a.seed)
    town = M.biggest_town(sim)
    rec = T.record(simulation, a.seed, town["id"], a.type, a.speed)
    sim = M.build(simulation, a.seed)
    fields = T.make_fields(water, sim)
    tid = town["id"]
    k = next(k for k in range(len(fields["small"].tmap.buckets)) if town["x0"] <= k * 32 <= town["x1"])
    series = {n: [] for n, _ in T.class_sizes(water)}
    rain = []
    t = clock = 0.0
    for dt, total in rec["hits"]:
        t += dt
        for f in fields.values():
            f.update(dt, {k: total} if total else {})
        clock += dt
        if clock >= 0.5:
            clock = 0.0
            for n in series:
                b = fields[n].sbody[tid]
                series[n].append((t, b.depth if b.V > 0 else 0.0))
            rain.append((t, fields["small"].inflow(tid)))
    W_, H_, L, R, TOP, BOT = 960, 460, 70, 20, 40, 60
    img = Image.new("RGB", (W_, H_), (250, 250, 247))
    d = ImageDraw.Draw(img)
    t_hi = max(x[0] for x in rain)
    first = next((x[0] for x in rain if x[1] >= 0.5), 0.0)
    t0 = max(0.0, first - 15.0)
    y_hi = max(12.0, max(v for s in series.values() for _t, v in s) * 1.1)

    def px(tt, v):
        return (L + (tt - t0) / (t_hi - t0) * (W_ - L - R), H_ - BOT - v / y_hi * (H_ - TOP - BOT))
    for v, label in ((water.FLOOD_DEPTH, "flooded (3 px)"), (water.CURB, "curb (10 px)")):
        x0, y = px(t0, v)
        x1, _ = px(t_hi, v)
        d.line([(x0, y), (x1, y)], fill=(160, 160, 160), width=1)
        d.text((L + 6, y - 12), label, fill=(110, 110, 110))
    d.line([(L, TOP), (L, H_ - BOT), (W_ - R, H_ - BOT)], fill=(40, 40, 40), width=2)
    for tt in range(int(t0 // 50 * 50) + 50, int(t_hi), 50):
        x, y = px(tt, 0)
        d.line([(x, y), (x, y + 5)], fill=(40, 40, 40))
        d.text((x - 10, y + 8), f"{tt}s", fill=(40, 40, 40))
    for v in range(0, int(y_hi) + 1, 2):
        x, y = px(t0, v)
        d.line([(x - 5, y), (x, y)], fill=(40, 40, 40))
        d.text((x - 32, y - 6), f"{v} px", fill=(40, 40, 40))
    for n, pts in series.items():
        d.line([px(tt, v) for tt, v in pts if tt >= t0], fill=COLORS[n], width=3)
    for i, (n, size) in enumerate(T.class_sizes(water)):
        cap = fields[n].cap_of(tid)
        d.rectangle([L + 14 + i * 230, 10, L + 28 + i * 230, 24], fill=COLORS[n])
        d.text((L + 34 + i * 230, 11), f"{n} drains {size:g} px (cap {cap:.0f} px2/s)", fill=(30, 30, 30))
    d.text((L, H_ - 24), f"diagnostic plot, not the game: {a.type} over {town['kind']} {town['name']} "
                          f"({town['x1'] - town['x0']:.0f} px), seed {a.seed}, speed x{a.speed:g}; depth on the street against time",
           fill=(90, 90, 90))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    img.save(a.out)
    print(f"wrote {a.out}  peaks: " + ", ".join(f"{n} {max(v for _t, v in s):.2f} px" for n, s in series.items()))


if __name__ == "__main__":
    main()
