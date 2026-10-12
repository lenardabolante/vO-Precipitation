#!/usr/bin/env python3
"""The LS6 table of Phase L: which storm floods which town, measured on the real sim's rain (not asserted).

    # 1. record the rain (slow: one real storm per seed x town x cloud type; the hits on each town's span, per tick)
    python tools/measure_flood_table.py --record hits_cb.json --types cumulonimbus
    # 2. replay it against the water model (fast; any constants): the table
    python tools/measure_flood_table.py --replay hits_cb.json hits_nimbo.json hits_super.json
    python tools/measure_flood_table.py --replay hits_*.json --set HIT_AREA=1.5 --set INLET_K=0.3
    python tools/measure_flood_table.py --timeline cumulonimbus --seed 12     # per-second text timeline of one storm
    python tools/measure_flood_table.py                                       # record + table in one go (slow)

A storm is one cloud with its rain span pinned over one town (tools/measure_rain_hits.py). A town's street body sees
only the rain that lands on its own span, so the record is, per tick, the number of hits inside that span; replaying
those hits through the real `water.WaterField` (the real terrain, the real street profile, any constants) gives the same
result as running the storm again. The hits are fed to one `WaterField` per drain class (every settlement gets the
class size 3.0 / 4.7 / 8.2) and to one in `normal` mode (the town's size by its kind). Reported per rain class x drain
class: how many runs reach each state, the median peak depth, the median seconds from the first rain on the town to
`flooded`, and the median seconds from the end of the rain to a dry street. Only stdout is written (and the record file).
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import measure_rain_hits as M                   # noqa: E402

ORDER = ["dry", "wet", "ponding", "flooded", "overflowing"]
TAIL_S = 400                                    # seconds of clear sky appended to a recorded storm when it is replayed


def class_sizes(water):
    return list(water.CLASSES.items())


def make_fields(water, sim):
    """One extra WaterField per drain class (uniform sizes) and one in `normal` mode (sizes by kind)."""
    out = {}
    for name, size in class_sizes(water):
        f = water.WaterField(sim.terrain, sim.settlements, sim.objects.objs, sim.seed, "normal")
        for sid in f.sizes:
            f.sizes[sid] = size
        out[name] = f
    out["by-kind"] = water.WaterField(sim.terrain, sim.settlements, sim.objects.objs, sim.seed, "normal")
    return out


def record(simulation, seed, town_id, ctype, speed=1.0, terrain="rolling", preset=None, n_clouds=1, secs=330):
    """Run one storm; return {meta, hits: [[dt, hits in the town's span], ...]} (one entry per tick)."""
    sim = M.build(simulation, seed, terrain, preset)
    town = next((s for s in sim.settlements if s["id"] == town_id), None)
    if town is None:
        return None
    cx = 0.5 * (town["x0"] + town["x1"])
    series = []
    x0, x1 = town["x0"], town["x1"]

    def tick(sim_, dt, hits):
        series.append([round(dt, 6), sum(n for k, n in hits.items() if x0 <= k * 32 <= x1)])
    res = M.storm(sim, ctype, cx, secs, speed, True, n_clouds, per_tick=tick)
    rain = M.summarise(res)
    return {"seed": seed, "town": town_id, "type": ctype, "speed": speed, "terrain": terrain, "preset": preset,
            "clouds": n_clouds, "rain": rain and {"secs": rain["secs"], "mean": rain["mean"], "rate_hi": rain["rate_hi"],
                                                    "town_mean": rain["towns"][town_id]["mean"]}, "hits": series}


def replay(simulation, water, rec):
    """Feed a recorded storm to the drain classes; returns {class: analysis}."""
    sim = M.build(simulation, rec["seed"], rec["terrain"], rec["preset"])
    tid = rec["town"]
    town = next(s for s in sim.settlements if s["id"] == tid)
    fields = make_fields(water, sim)
    k = next(k for k in range(len(fields["small"].tmap.buckets)) if town["x0"] <= k * 32 <= town["x1"])
    rows = {n: [] for n in fields}
    clock = 0.0
    t = 0.0
    tail = [[rec["hits"][-1][0], 0]] * int(TAIL_S * 30 / max(1.0, rec["speed"]))   # then the sky is clear: how long to dry
    for dt, total in rec["hits"] + tail:
        t += dt
        for f in fields.values():
            f.update(dt, {k: total} if total else {})
        clock += dt
        if clock >= 1.0 - 1e-9:
            clock = 0.0
            for n, f in fields.items():
                b = f.sbody[tid]
                rows[n].append((t, round(b.depth, 2) if b.V > 0.0 else 0.0, f.state_of(tid), f.inflow(tid)))
    out = {n: analyse(r, fields[n].sizes[tid], fields[n].cap_of(tid)) for n, r in rows.items()}
    return town, out


def analyse(r, size, cap):
    """Peak state / depth, seconds from the first rain on the town to flooded, seconds from the end of that rain to dry."""
    rain = [i for i, (_t, _d, _s, q) in enumerate(r) if q >= 0.5]
    if not rain:
        return None
    i0, i1 = rain[0], rain[-1]
    peak_i = max(range(len(r)), key=lambda i: r[i][1])
    peak_state = max((r[i][2] for i in range(len(r))), key=ORDER.index)
    to_flood = next((r[i][0] - r[i0][0] for i in range(len(r)) if r[i][2] in ("flooded", "overflowing")), None)
    to_pond = next((r[i][0] - r[i0][0] for i in range(len(r)) if r[i][1] >= 0.05), None)
    clear = None
    if r[peak_i][1] >= 0.05:
        j = next((i for i in range(peak_i, len(r)) if r[i][1] < 0.05), None)
        if j is not None:
            clear = max(0.0, r[j][0] - r[i1][0])
    n_flood = sum(1 for x in r if x[2] in ("flooded", "overflowing"))
    return {"peak_state": peak_state, "peak_depth": r[peak_i][1], "to_flood": to_flood, "to_pond": to_pond,
            "clear": clear, "secs_flooded": n_flood, "size": size, "cap": cap, "rain_secs": i1 - i0 + 1}


def med(v):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None


def fmt(x, nd=1):
    return "-" if x is None else f"{x:.{nd}f}"


def print_table(results, water, header):
    print(header)
    print(f"{'rain class':14s}{'runs':>5s} {'town hits/s/px':>15s} | {'drains':8s}{'size':>5s}{'cap/px':>7s} "
          f"{'dry':>4s}{'wet':>4s}{'pnd':>4s}{'FLD':>4s}{'OVF':>4s} {'peak px med/max':>16s} {'to flood s':>11s} {'to dry s':>9s}")
    for ctype, rs in results.items():
        rate = statistics.fmean(r["rec"]["rain"]["town_mean"] / (r["town"]["x1"] - r["town"]["x0"]) for r in rs)
        first = True
        for k in [n for n, _ in class_sizes(water)] + ["by-kind"]:
            an = [r["fields"][k] for r in rs if r["fields"][k]]
            cnt = {s: sum(1 for x in an if x["peak_state"] == s) for s in ORDER}
            pk = [x["peak_depth"] for x in an]
            size = statistics.fmean(x["size"] for x in an)
            capx = statistics.fmean(r["fields"][k]["cap"] / (r["town"]["x1"] - r["town"]["x0"]) for r in rs if r["fields"][k])
            print(f"{ctype if first else '':14s}{(str(len(rs)) if first else ''):>5s} {(f'{rate:.3f}' if first else ''):>15s} | "
                  f"{k:8s}{size:5.1f}{capx:7.3f} {cnt['dry']:4d}{cnt['wet']:4d}{cnt['ponding']:4d}{cnt['flooded']:4d}"
                  f"{cnt['overflowing']:4d} {fmt(med(pk), 2):>8s}/{max(pk):<7.2f} {fmt(med([x['to_flood'] for x in an])):>11s} "
                  f"{fmt(med([x['clear'] for x in an])):>9s}")
            first = False


def timeline(simulation, water, seed, ctype, speed, town_id):
    sim = M.build(simulation, seed)
    town = next((s for s in sim.settlements if s["id"] == town_id), None) or M.biggest_town(sim)
    rec = record(simulation, seed, town["id"], ctype, speed)
    sim = M.build(simulation, seed)
    fields = make_fields(water, sim)
    tid = town["id"]
    k = next(k for k in range(len(fields["small"].tmap.buckets)) if town["x0"] <= k * 32 <= town["x1"])
    clock = 0.0
    t = 0.0
    rows = []
    for dt, total in rec["hits"]:
        t += dt
        for f in fields.values():
            f.update(dt, {k: total} if total else {})
        clock += dt
        if clock >= 1.0 - 1e-9:
            clock = 0.0
            rows.append((t, {n: (f.inflow(tid), f.sbody[tid].depth if f.sbody[tid].V > 0 else 0.0, f.state_of(tid))
                             for n, f in fields.items() if n != "by-kind"}))
    print(f"{ctype} over town {tid} ({town['kind']}, {town['x1'] - town['x0']:.0f} px, seed {seed}, speed x{speed:g}); "
          f"cap px2/s: " + ", ".join(f"{n} {fields[n].cap_of(tid):.1f}" for n, _ in class_sizes(water)))
    names = [n for n, _ in class_sizes(water)]
    print(f"{'t':>6s} {'inflow':>7s} | " + " | ".join(f"{n:^22s}" for n in names))
    last = None
    for t, d in rows:
        key = tuple((round(v[1], 1), v[2]) for v in d.values())
        if int(t) % 5 != 0 and key == last:
            continue
        if d[names[0]][0] < 0.5 and all(v[1] < 0.05 for v in d.values()):
            continue
        print(f"{t:6.0f} {d[names[0]][0]:7.1f} | " + " | ".join(f"{d[n][1]:6.2f} px {d[n][2]:>11s}" for n in names))
        last = key


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sim-dir", default=os.path.join(HERE, "..", "sim"))
    ap.add_argument("--seeds", type=int, nargs="+", default=list(range(11, 19)))
    ap.add_argument("--types", nargs="+", default=list(M.TYPES))
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--terrain", default="rolling")
    ap.add_argument("--preset", default=None)
    ap.add_argument("--merged", action="store_true", help="two clouds of the type over the town")
    ap.add_argument("--towns", choices=("biggest", "all"), default="all")
    ap.add_argument("--record", metavar="FILE", help="run the storms and write the recorded hits to FILE (no table)")
    ap.add_argument("--replay", metavar="FILE", nargs="+", help="read recorded hits and print the table for them")
    ap.add_argument("--span", type=float, nargs=2, metavar=("MIN", "MAX"), help="with --replay: only towns whose street is this long (px)")
    ap.add_argument("--set", action="append", default=[], metavar="NAME=VALUE", help="override a constant of sim/water.py")
    ap.add_argument("--timeline", metavar="CLOUD_TYPE", help="print a per-second timeline of one storm instead of the table")
    ap.add_argument("--seed", type=int, default=12, help="with --timeline: the world seed")
    ap.add_argument("--town", type=int, default=0, help="with --timeline: settlement id (default: the biggest)")
    a = ap.parse_args(argv)
    simulation = M._load(a.sim_dir)
    import water
    for kv in a.set:
        name, _, val = kv.partition("=")
        setattr(water, name, float(val))
    if a.timeline:
        timeline(simulation, water, a.seed, a.timeline, a.speed, a.town)
        return
    recs = []
    if a.replay:
        for fn in a.replay:
            with open(fn, encoding="utf-8") as fh:
                recs += json.load(fh)
    else:
        for ctype in a.types:
            for seed in a.seeds:
                probe = M.build(simulation, seed, a.terrain, a.preset)
                ids = ([M.biggest_town(probe)["id"]] if a.towns == "biggest" else [s["id"] for s in probe.settlements])
                for tid in ids:
                    r = record(simulation, seed, tid, ctype, a.speed, a.terrain, a.preset, 2 if a.merged else 1)
                    if r is not None and r["rain"] is not None:
                        recs.append(r)
        if a.record:
            with open(a.record, "w", encoding="utf-8") as fh:
                json.dump(recs, fh, separators=(",", ":"))
            print(f"recorded {len(recs)} storms to {a.record}")
            return
    results = {}
    for rec in recs:
        if a.span:
            probe = M.build(simulation, rec["seed"], rec["terrain"], rec["preset"])
            tw = next(x for x in probe.settlements if x["id"] == rec["town"])
            if not a.span[0] <= tw["x1"] - tw["x0"] <= a.span[1]:
                continue
        town, fields = replay(simulation, water, rec)
        results.setdefault(rec["type"], []).append({"rec": rec, "town": town, "fields": fields})
    consts = {k: getattr(water, k) for k in ("HIT_AREA", "INLET_K", "SOAK", "CURB", "FLOOD_DEPTH")}
    sp = sorted({r["speed"] for r in recs})
    print_table(results, water, f"constants {consts}  speed x{'/'.join(f'{s:g}' for s in sp)}  classes {water.CLASSES}  "
                                f"terrain {a.preset or a.terrain}  clouds {sorted({r['clouds'] for r in recs})}")


if __name__ == "__main__":
    main()
