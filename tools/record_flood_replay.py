#!/usr/bin/env python3
"""Record a pinned storm of the real sim over one town as a `--replay` file (protocol v2.4.1: it carries `water`, `flood` and `flow`).

    python tools/record_flood_replay.py out.jsonl --seed 12 --type supercell --drains small --from 100 --to 215
    python tools/record_flood_replay.py out.jsonl --seed 12 --terrain hilly --town 2 --type cumulonimbus --every 3

One cloud's rain span is pinned over the chosen town (default: the biggest; tools/measure_rain_hits.py does the pinning), the
climate's own spawner and merging are off, the surface is warmed so it rains rather than snows, and the clock is held at midday.
The file is the v2 `hello` line followed by every `--every`-th frame (default 3, about 10 a second) between `--from` and `--to`
seconds of simulated time, which is what the viewer's `--replay FILE --frames N --cam X,Y,ZOOM` opens. The viewer agent opens
frames of the sheet rising, running off the street and down the road, the hollow filling, and the flood draining.
Only the output file is written (and a one-line summary on stdout).
"""
from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import measure_rain_hits as M                   # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out")
    ap.add_argument("--sim-dir", default=os.path.join(HERE, "..", "sim"))
    ap.add_argument("--seed", type=int, default=12)
    ap.add_argument("--terrain", default="rolling", choices=("flat", "rolling", "hilly", "mountainous"))
    ap.add_argument("--preset", default=None)
    ap.add_argument("--type", default="supercell", choices=M.TYPES)
    ap.add_argument("--drains", default="small", choices=("small", "normal", "large", "random"))
    ap.add_argument("--town", type=int, default=0, help="settlement id to pin the storm over (default: the biggest)")
    ap.add_argument("--from", dest="t0", type=float, default=100.0, help="first simulated second to record")
    ap.add_argument("--to", dest="t1", type=float, default=215.0, help="last simulated second to record")
    ap.add_argument("--every", type=int, default=3, help="write every N-th frame")
    ap.add_argument("--hour", type=float, default=13.0, help="hold the clock at this hour (midday: the water is easy to see)")
    a = ap.parse_args(argv)
    simulation = M._load(a.sim_dir)
    sim = M.build(simulation, a.seed, a.terrain, a.preset, drains=a.drains)
    if not sim.settlements:
        ap.error("this world has no settlements")
    tw = next((s for s in sim.settlements if s["id"] == a.town), None) if a.town else M.biggest_town(sim)
    if tw is None:
        ap.error(f"no settlement {a.town}: have {[s['id'] for s in sim.settlements]}")
    sim.handle_command({"cmd": "hour", "value": a.hour}, [])
    n = [0]
    written = [0]
    last = {}
    LISTS = ("water", "flood", "flow")
    with open(a.out, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(sim.hello_message(), separators=(",", ":")) + "\n")
        orig = sim.step

        def step(dt, extra_events=None):
            if n[0] % 15 == 0:
                sim.handle_command({"cmd": "hour", "value": a.hour}, [])
            msg = orig(dt, extra_events)
            n[0] += 1
            for key in LISTS:                                   # these go out on change only: remember the last of each
                if key in msg:
                    last[key] = msg[key]
            if a.t0 <= sim.world.t <= a.t1 and n[0] % a.every == 0:
                out = msg
                if not written[0]:                              # the first kept frame carries the lists as they stand (the viewer
                    out = dict(msg)                             # keeps the last one it was sent), or a --from after the flood began has none
                    for key in LISTS:
                        if key not in out and key in last:
                            out[key] = last[key]
                fh.write(json.dumps(out, separators=(",", ":")) + "\n")
                written[0] += 1
            return msg
        sim.step = step
        M.storm(sim, a.type, 0.5 * (tw["x0"] + tw["x1"]), a.t1 + 1, 1.0, True, 1)
    print(f"{a.out}: seed {a.seed} {a.terrain}, {a.type} over {tw['kind']} {tw['id']} ({tw['x1'] - tw['x0']:.0f} px, drains "
          f"{sim.water.sizes[tw['id']] if sim.water else '-'} px), {written[0]} frames from t = {a.t0:g} to {a.t1:g} s, ticks run {n[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
