#!/usr/bin/env python3
"""Record a heavy real-sim segment as protocol v2 JSONL for the viewer's --replay / --bench.

Drives sim.Simulation directly (no real-time sleep): seeds extra clouds with the same
`spawn` / `temp` commands the viewer sends, fast-forwards, then writes a v2 hello line (terrain,
settlements and the full object list) plus the next N frames to the output file. Replaying it
gives a deterministic, dense scene for performance benchmarks and before/after snapshots.
v1 replay files do not load in v2 and must be re-recorded with this tool.

    python tools/record_replay.py --seed 8 --spawn 14 --cold 4 --skip 900 --frames 300 --min-particles 2500 out.jsonl
    python tools/record_replay.py --width 6400 --height 1200 --terrain mountainous --frames 200 big.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "sim"))

from simulation import Simulation  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--seed", type=int, default=8)
    ap.add_argument("--width", type=int, default=3840)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--terrain", default="rolling", choices=("flat", "rolling", "hilly", "mountainous"))
    ap.add_argument("--settlements", default="normal", choices=("none", "sparse", "normal", "dense"))
    ap.add_argument("--spawn", type=int, default=14, help="extra clouds seeded across the sky")
    ap.add_argument("--respawn-every", type=int, default=240, help="re-seed clouds every K frames while skipping")
    ap.add_argument("--cold", type=float, default=0.0, help="lower surface temp by this many deg C")
    ap.add_argument("--skip", type=int, default=900, help="frames to fast-forward before recording")
    ap.add_argument("--frames", type=int, default=300)
    ap.add_argument("--min-particles", type=int, default=0, help="keep skipping (max 3x) until this many particles")
    a = ap.parse_args()
    if not (1280 <= a.width <= 9600 and 720 <= a.height <= 1440):
        ap.error("size must be 1280-9600 wide and 720-1440 tall")

    sim = Simulation(a.seed, a.width, a.height, 30, a.terrain, a.settlements)
    dt = 1.0 / 30
    ev: list = []
    if a.cold:
        sim.handle_command({"cmd": "temp", "delta": -abs(a.cold)}, ev)

    def seed_clouds():
        for k in range(a.spawn):
            sim.handle_command({"cmd": "spawn", "x": 80 + (k + 0.5) * (a.width - 160) / a.spawn,
                                "y": (150 + 45 * ((k * 7) % 3)) * a.height / 720.0}, ev)

    seed_clouds()
    frame = None
    limit = a.skip * 3
    for i in range(limit):
        if a.respawn_every and i and i % a.respawn_every == 0:
            seed_clouds()
        frame = sim.step(dt, extra_events=ev)
        ev = []
        if i >= a.skip and frame["stats"]["particles"] >= a.min_particles:
            break
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(sim.hello_message(), separators=(",", ":")) + "\n")
        counts = []
        for _ in range(a.frames):
            frame = sim.step(dt)
            counts.append(frame["stats"]["particles"])
            f.write(json.dumps(frame, separators=(",", ":")) + "\n")
    print(f"wrote {a.out}: {a.frames} frames, particles min={min(counts)} avg={sum(counts)//len(counts)} max={max(counts)}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
