#!/usr/bin/env python3
"""Time the simulation: one step() plus JSON encoding per frame, as the stream does it.

    python tools/bench_sim.py                       # 3840x1080 and 6400x1200, seed 7
    python tools/bench_sim.py --size 3840x1080 --frames 600 --warmup 1500 --seed 3
    python tools/bench_sim.py --size 3840x1080 --preset all        # every region preset (v2.2)
    python tools/bench_sim.py --size 3840x1080 --tornado           # a supercell's tornado on the ground (v2.3)

A warm-up run (default 1500 frames = 50 sim seconds) lets the clouds mature so the measured
frames have a busy sky: several clouds precipitating, thousands of particles. Prints mean/p95/max
milliseconds for step, encode and the total, the mean frame size in bytes and the particle
count. Nothing is written to stdout except the report table.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "sim"))

from simulation import Simulation  # noqa: E402


def pct(vals, p):
    s = sorted(vals)
    return s[min(len(s) - 1, int(len(s) * p))]


def start_tornado(sim, dt):
    """Spawn a supercell mid-world, run until it is mature and send the `tornado` command, then run until the
    funnel is on the ground (the roads' cars, the debris and the vortex wind are then all active)."""
    sim.handle_command({"cmd": "spawn", "x": sim.world.w * 0.5, "type": "supercell"}, [])
    cloud = next(c for c in sim.clouds if c.type == "supercell")
    cloud.rot_peak = cloud.wall_peak = 0.95
    for _ in range(30 * 120):
        sim.step(dt)
        if cloud.stage in ("mature", "precipitating") and cloud.form >= 0.85:
            break
    sim.handle_command({"cmd": "tornado", "cloud": cloud.id}, [])
    for _ in range(30 * 60):
        sim.step(dt)
        if sim.tornadoes and sim.tornadoes[0].reach >= 1.0 and sim.tornadoes[0].stage == "mature":
            break


def bench(w, h, seed, warmup, frames, terrain, settlements, fps=30, preset=None, tornado=False):
    t0 = time.perf_counter()
    sim = Simulation(seed, w, h, fps, terrain, settlements, preset)
    init_ms = (time.perf_counter() - t0) * 1000.0
    dt = 1.0 / fps
    for _ in range(warmup):
        sim.step(dt)
    if tornado:
        start_tornado(sim, dt)
    step_ms, enc_ms, sizes, parts, precip, tors, cars = [], [], [], [], [], [], []
    for _ in range(frames):
        a = time.perf_counter()
        msg = sim.step(dt)
        b = time.perf_counter()
        line = json.dumps(msg, separators=(",", ":")) + "\n"
        c = time.perf_counter()
        step_ms.append((b - a) * 1000.0)
        enc_ms.append((c - b) * 1000.0)
        sizes.append(len(line.encode("utf-8")))
        parts.append(len(sim.particles))
        precip.append(sum(1 for cl in sim.clouds if cl.stage == "precipitating"))
        tors.append(len(sim.tornadoes))
        cars.append(len(sim.cars.cars))
    tot = [a + b for a, b in zip(step_ms, enc_ms)]
    return {
        "size": f"{w}x{h}" + (f" {preset}" if preset else ""), "init_ms": init_ms,
        "step_mean": statistics.fmean(step_ms), "enc_mean": statistics.fmean(enc_ms),
        "tot_mean": statistics.fmean(tot), "tot_p95": pct(tot, 0.95), "tot_max": max(tot),
        "bytes": statistics.fmean(sizes), "particles": statistics.fmean(parts),
        "particles_max": max(parts), "precipitating": statistics.fmean(precip),
        "clouds": len(sim.clouds), "tornadoes": statistics.fmean(tors), "cars": statistics.fmean(cars),
        "debris": len(sim.particles.debris),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", action="append", help="WxH (repeatable); default 3840x1080 and 6400x1200")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--warmup", type=int, default=1500)
    ap.add_argument("--frames", type=int, default=600)
    ap.add_argument("--terrain", default="rolling")
    ap.add_argument("--settlements", default="normal")
    ap.add_argument("--preset", action="append",
                    help="region preset to bench (repeatable, or 'all'); default: no preset")
    ap.add_argument("--tornado", action="store_true",
                    help="measure with a supercell's tornado on the ground (spawns the supercell after the warm-up)")
    ap.add_argument("--repeat", type=int, default=1,
                    help="run each size N times and report the run with the lowest mean (best-of-N is the "
                         "usual way to benchmark on a machine that is also doing other work)")
    a = ap.parse_args()
    sizes = a.size or ["3840x1080", "6400x1200"]
    from presets import PRESET_IDS
    presets = [None] if not a.preset else (list(PRESET_IDS) if "all" in a.preset else a.preset)
    print(f"{'size':>26} {'step':>7} {'encode':>7} {'mean':>7} {'p95':>7} {'max':>7} {'bytes':>9} "
          f"{'particles':>9} {'raining':>7} {'init ms':>8}")
    for s, pid in ((s, pid) for pid in presets for s in sizes):
        w, h = (int(v) for v in s.lower().split("x"))
        r = min((bench(w, h, a.seed, a.warmup, a.frames, a.terrain, a.settlements, preset=pid, tornado=a.tornado)
                 for _ in range(max(1, a.repeat))), key=lambda x: x["tot_mean"])
        print(f"{r['size']:>26} {r['step_mean']:7.2f} {r['enc_mean']:7.2f} {r['tot_mean']:7.2f} "
              f"{r['tot_p95']:7.2f} {r['tot_max']:7.2f} {r['bytes']:9.0f} {r['particles']:9.0f} "
              f"{r['precipitating']:7.1f} {r['init_ms']:8.0f}")
        print(f"{'':>26} cars {r['cars']:.0f}, tornadoes {r['tornadoes']:.1f}, debris at the end {r['debris']}, "
              f"ticks/s {1000.0 / r['tot_mean']:.0f} (mean) / {1000.0 / r['tot_p95']:.0f} (p95)")


if __name__ == "__main__":
    main()
