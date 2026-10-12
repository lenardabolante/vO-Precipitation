#!/usr/bin/env python3
"""Measure how much rain the sim delivers to the ground (Phase L, LS0): the numbers the water model is tuned on.

    python tools/measure_rain_hits.py                      # nimbostratus / cumulonimbus / supercell, seeds 11 12 13, speed 1
    python tools/measure_rain_hits.py --speeds 1 8         # also at speed x8 (the sim's per-tick particle cap bites there)
    python tools/measure_rain_hits.py --preset oklahoma    # a region preset instead of --terrain
    python tools/measure_rain_hits.py --merged             # two clouds of one type over the same town
    python tools/measure_rain_hits.py --sim-dir DIR        # measure another copy of the sim (for before / after)

A "hit" is one rain particle that landed in a 32 px ground bucket (`rain_hits` of `ParticleSystem.update`). The
script spawns one cloud of each type over the largest settlement of a world, forces the surface warm so the rain
lands as rain, switches merging and the climate's own spawner off, then counts per simulated second:

  * hits per bucket under the cloud's own rain span (what PLAN.md Phase L first measured), and
  * hits landing inside each settlement's span x0 .. x1, which is what the drains see (the number the plan lacked).

`storm()` and `build()` are importable: tools/measure_flood_table.py reuses them. Only stdout is written.
"""
from __future__ import annotations

import argparse
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
G = 32                              # ground bucket width in px (objects.GROUND_STEP)
TYPES = ("nimbostratus", "cumulonimbus", "supercell")


def _load(sim_dir):
    sys.path.insert(0, os.path.abspath(sim_dir))
    import simulation                # noqa: WPS433 (imported here so --sim-dir can pick the copy)
    return simulation


def build(simulation, seed=11, terrain="rolling", preset=None, settlements="normal", w=3840, h=1080,
          warm_c=16.0, **kw):
    """A quiet world: no clouds, no spawner, no merging, surface `warm_c` degrees warmer (rain, not snow)."""
    sim = simulation.Simulation(seed, w, h, 30, terrain, settlements, preset, **kw)
    sim.merging = False
    sim.clouds = []
    sim._maybe_spawn_cloud = lambda *a, **k: None
    sim.handle_command({"cmd": "temp", "delta": warm_c}, [])
    return sim


def biggest_town(sim):
    return max(sim.settlements, key=lambda s: s["x1"] - s["x0"]) if sim.settlements else None


def spawn_over(sim, ctype, centre_x, n=1, y=200.0):
    """Spawn `n` clouds of `ctype` whose *rain span* is centred on `centre_x` (a supercell rains from its forward
    flank, offset from the anchor, so the anchor is moved to compensate). Returns the clouds."""
    out = []
    for i in range(n):
        sim.handle_command({"cmd": "spawn", "x": centre_x + 40.0 * i, "y": y, "type": ctype}, [])
        c = sim.clouds[-1]
        x0, x1 = sim.rain_span(c)
        off = 0.5 * (x0 + x1) - c.ax
        if abs(off) > 1.0:
            c.ax -= off
            c.x -= off
        out.append(c)
    return out


def storm(sim, ctype, centre_x=None, secs=240, speed=1.0, pin=True, n_clouds=1, per_tick=None):
    """Run one storm; returns dict(windows=[...], first=<cloud>, t_precip=<s of rain>). `windows` has one row per
    simulated second: t, precipitating (bool), rate (precip_rate), under (hits under the first cloud's span),
    buckets (buckets in that span), busiest (largest bucket), towns {id: hits in span}. `per_tick(sim, dt, hits)` is
    called after every step with that tick's rain_hits dict (the flood table hangs its observers there). `pin`
    holds the clouds' anchors still."""
    if centre_x is None:
        t = biggest_town(sim)
        centre_x = 0.5 * (t["x0"] + t["x1"]) if t else sim.world.w * 0.5
    if speed != 1.0:
        sim.handle_command({"cmd": "speed", "value": speed}, [])
    clouds = spawn_over(sim, ctype, centre_x, n_clouds)
    anchors = [c.ax for c in clouds]
    last = {}
    orig = sim.particles.update

    def upd(*a, **k):
        r = orig(*a, **k)
        last.clear()
        last.update(r[0])
        return r
    sim.particles.update = upd
    spans = [(s["id"], s["x0"], s["x1"]) for s in sim.settlements]
    win = {}
    win_t = 0.0
    rows = []
    dt = speed / 30.0
    first = clouds[0]
    t_end = sim.world.t + secs
    while sim.world.t < t_end:
        sim.step(1.0 / 30.0)
        if pin:
            for c, ax in zip(clouds, anchors):
                if c in sim.clouds:
                    d = ax - c.ax
                    c.ax += d
                    c.x += d
        for k, n in last.items():
            win[k] = win.get(k, 0) + n
        win_t += dt
        if per_tick is not None:
            per_tick(sim, dt, last)
        if win_t >= 1.0 - 1e-9:
            raining = first in sim.clouds and first.stage == "precipitating" and first.precip_type != "none"
            x0, x1 = sim.rain_span(first) if first in sim.clouds else (0.0, 0.0)
            ks = range(int(x0 / G + 0.5), int(x1 / G + 0.5) + 1)
            under = sum(win.get(k, 0) for k in ks)
            rows.append({"t": round(sim.world.t, 2), "raining": raining, "rate": first.precip_rate if raining else 0.0,
                         "under": under / win_t, "buckets": len(ks), "busiest": max(win.values(), default=0) / win_t,
                         "towns": {sid: sum(n for k, n in win.items() if a <= k * G <= b) / win_t
                                   for sid, a, b in spans}})
            win = {}
            win_t = 0.0
        if first not in sim.clouds and not sim.particles.protocol_list():
            break
    sim.particles.update = orig
    rain = [r for r in rows if r["raining"] and r["under"] > 0]
    return {"windows": rows, "first": first, "t_precip": float(len(rain))}


def summarise(res):
    rain = [r for r in res["windows"] if r["raining"] and r["under"] > 0]
    if not rain:
        return None
    per_bucket = [r["under"] / r["buckets"] for r in rain]
    out = {"secs": len(rain), "mean": statistics.fmean(per_bucket), "busiest": max(r["busiest"] for r in rain),
           "rate_hi": max(r["rate"] for r in rain), "rate_lo": min(r["rate"] for r in rain),
           "first_t": rain[0]["t"], "towns": {}}
    for sid in rain[0]["towns"]:
        v = [r["towns"][sid] for r in rain]
        peak10 = max((sum(v[i:i + 10]) / len(v[i:i + 10]) for i in range(max(1, len(v) - 9))), default=0.0)
        out["towns"][sid] = {"mean": statistics.fmean(v), "peak10": peak10}
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sim-dir", default=os.path.join(HERE, "..", "sim"))
    ap.add_argument("--seeds", type=int, nargs="+", default=[11, 12, 13])
    ap.add_argument("--types", nargs="+", default=list(TYPES))
    ap.add_argument("--speeds", type=float, nargs="+", default=[1.0])
    ap.add_argument("--terrain", default="rolling")
    ap.add_argument("--preset", default=None)
    ap.add_argument("--merged", action="store_true", help="two clouds of each type over the same town")
    ap.add_argument("--secs", type=int, default=240)
    ap.add_argument("--drift", action="store_true", help="let the clouds drift (default: anchors pinned over the town)")
    a = ap.parse_args(argv)
    simulation = _load(a.sim_dir)
    print(f"{'type':14s}{'seed':>5s}{'spd':>4s}{'n':>2s} {'rain s':>6s} {'rate':>11s} {'hits/s/bucket':>13s} {'busiest':>8s}"
          f"  town hits/s in span (mean | peak 10 s | per px)")
    agg = {}
    for ctype in a.types:
        for speed in a.speeds:
            for seed in a.seeds:
                sim = build(simulation, seed, a.terrain, a.preset)
                town = biggest_town(sim)
                res = storm(sim, ctype, None, a.secs, speed, pin=not a.drift, n_clouds=2 if a.merged else 1)
                s = summarise(res)
                if s is None:
                    print(f"{ctype:14s}{seed:5d}{speed:4g}{2 if a.merged else 1:2d}   never rained in {a.secs} s")
                    continue
                tw = s["towns"].get(town["id"], {"mean": 0, "peak10": 0}) if town else {"mean": 0, "peak10": 0}
                per_px = tw["mean"] / (town["x1"] - town["x0"]) if town else 0.0
                print(f"{ctype:14s}{seed:5d}{speed:4g}{2 if a.merged else 1:2d} {s['secs']:6d} "
                      f"{s['rate_hi']:5.2f}-{s['rate_lo']:4.2f} {s['mean']:13.2f} {s['busiest']:8.1f}"
                      f"  {tw['mean']:7.1f} | {tw['peak10']:7.1f} | {per_px:5.3f}")
                agg.setdefault((ctype, speed), []).append((s["mean"], s["secs"], per_px))
    print("-- mean over seeds: type speed -> hits/s/bucket, rain seconds, town hits/s per px")
    for (ctype, speed), v in agg.items():
        print(f"{ctype:14s} x{speed:g}: {statistics.fmean(x[0] for x in v):5.2f}  {statistics.fmean(x[1] for x in v):5.0f} s  "
              f"{statistics.fmean(x[2] for x in v):5.3f}")


if __name__ == "__main__":
    main()
