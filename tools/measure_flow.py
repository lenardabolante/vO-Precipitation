#!/usr/bin/env python3
"""Overland flow and flooding without a curb (Phase N, protocol v2.4.1): the measurements the film is tuned on (NS0, NS6).

    python tools/measure_flow.py census                      # which settlements are hollows, sit in a hollow, or shed (seeds 11-18 x 4 terrains)
    python tools/measure_flow.py streams                     # the film vector: steady thickness for q x slope, and the travel time of a 112 px run
    python tools/measure_flow.py sheet                       # a flat 814 px street as a sheet: rain class x drain class (water model only)
    python tools/measure_flow.py record OUT.json --seed 12 --type cumulonimbus   # run real storms, one per town, record the rain they drop
    python tools/measure_flow.py table OUT.json [more.json]  # replay recorded rain through the water model: the NS6 table
    python tools/measure_flow.py storm --seed 12 --drains small                  # the screenshot case on a real world, headless
    python tools/measure_flow.py bench [--sim-dir DIR]       # ms a tick and bytes a frame: dry, rain, overflow (old against new: --sim-dir)

A storm is one cloud with its rain span pinned over one town (tools/measure_rain_hits.py). The recorded rain is *every* landed
particle per tick (the film is fed by the slopes around a town, not only by its span), so a replay through `water.WaterField`
(any constants: `--set NAME=VALUE`) gives the result of running the storm again, at a fraction of the cost. Only stdout and the
record file are written.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import measure_rain_hits as M                   # noqa: E402

DT = 1.0 / 30
G = 32
ORDER = ["dry", "wet", "ponding", "flooded", "overflowing"]
TERRAINS = ("rolling", "hilly", "mountainous", "flat")
RAIN_CLASS = {"nimbostratus": "steady rain", "cumulonimbus": "downpour", "supercell": "supercell"}


def load(sim_dir):
    simulation = M._load(sim_dir)
    import water                                # noqa: WPS433
    return simulation, water


class FlatTer:
    """A hand-made terrain: elevation knots (x, e) joined by straight lines, y = 1000 - e, `step` px samples."""

    def __init__(self, knots, w=3000, step=8):
        self.w, self.step = w, step
        n = int(math.ceil(w / step)) + 1
        ys = []
        for i in range(n):
            x = i * step
            for (xa, ea), (xb, eb) in zip(knots, knots[1:]):
                if xa <= x <= xb:
                    ys.append(1000.0 - (ea + (eb - ea) * (x - xa) / (xb - xa)))
                    break
            else:
                ys.append(1000.0 - knots[-1][1])
        self.y = ys


def town(sid, x0, x1, kind="city"):
    return {"id": sid, "kind": kind, "name": f"T{sid}", "x0": x0, "x1": x1, "population": 1000}


# ---------------------------------------------------------------------------------------------------- census
def census(a):
    simulation, water = load(a.sim_dir)
    tot, per, holds = {}, {}, []
    for terr in TERRAINS:
        for seed in range(a.seeds[0], a.seeds[1] + 1):
            sim = M.build(simulation, seed, terr)
            w = sim.water
            tm = w.tmap
            for s in sim.settlements:
                sid = s["id"]
                ks = w.film.span_cells[sid]
                kinds = {tm.kind[w.film.el_of[k]] for k in ks}
                if sid in tm.street_el:
                    j = tm.street_el[sid]
                    cat = "hollow"
                    holds.append(min(tm.e[tm.dv[j - 1]], tm.e[tm.dv[j]]) - tm.elems[j].bed_e)
                elif "basin" in kinds:
                    cat = "in a hollow"            # a pool that rises to the pad covers it
                else:
                    cat = "sheds"
                per.setdefault(terr, {}).setdefault(cat, 0)
                per[terr][cat] += 1
                tot[cat] = tot.get(cat, 0) + 1
    print(f"settlements {sum(tot.values())} (seeds {a.seeds[0]}-{a.seeds[1]} x {len(TERRAINS)} terrains), the street counted at BASIN_MIN_DEPTH "
          f"{water.BASIN_MIN_DEPTH} px:")
    for t, d in per.items():
        print(f"  {t:12s} {d}")
    print(f"  total        {tot}")
    if holds:
        print(f"  a street that is a hollow holds {statistics.median(holds):.2f} px (median), {max(holds):.1f} px (deepest)")


# ---------------------------------------------------------------------------------------------------- streams
def hill(slope, run=160.0, bowl=120.0, top=300.0, w=3000, x_fall=1000.0):
    e1 = top - slope * run
    return FlatTer([(0, top), (x_fall, top), (x_fall + run, e1), (x_fall + run + 40, e1 - bowl), (x_fall + run + 140, e1 - bowl),
                    (x_fall + run + 180, e1), (w, e1)], w)


def streams(a):
    simulation, W = load(a.sim_dir)
    print(f"film vector: h = (q / (KU sqrt(slope)))^0.6, KU {W.KU}; measured at cells 34-38 of a 320 px slope after 120 s")
    print(f"{'slope':>6s} " + " ".join(f"{'q ' + str(q):>16s}" for q in (5, 34, 127)))
    for slope in (0.05, 0.10, 0.22, 0.60, 1.0):
        row = []
        for q in (5.0, 34.0, 127.0):
            f = W.WaterField(hill(slope, 320.0), [], [], 1)
            for _ in range(int(120 / DT)):
                f.update(DT, {32: q * DT / W.HIT_AREA})
            hs = [f.film.S[k] / G for k in range(34, 39)]
            want = (q / (W.KU * math.sqrt(slope))) ** 0.6
            row.append(f"{want:6.2f} {min(hs):5.2f}-{max(hs):4.2f}")
        print(f"{slope:6.2f} " + " ".join(f"{r:>16s}" for r in row) + "   (want  got)")
    print("travel time of the first water down a 112 px run of slope 0.29 into a hollow (first 0.5 px^2 in the hollow):")
    for q in (2.0, 5.0, 10.0, 34.0, 127.0):
        f = W.WaterField(hill(0.29, 112.0), [], [], 1)
        t = None
        for i in range(int(90 / DT)):
            f.update(DT, {32: q * DT / W.HIT_AREA})
            if sum(b.V for b in f.pools.values()) >= 0.5:
                t = (i + 1) * DT
                break
        h = (q / (W.KU * math.sqrt(0.29))) ** 0.6
        print(f"  q {q:5.1f} px^2/s: film {h:4.2f} px thick, first water in the hollow after {t if t is None else round(t, 1)} s")


# ---------------------------------------------------------------------------------------------------- the sheet on a pad
def sheet_case(W, rain_px, size, length=814.0, seconds=240.0, tail=300.0, hit_area=None):
    """A flat street `length` px long at the top of a road that falls to the world edge; rain `rain_px` px^2/s per px of street
    for `seconds`, then dry. Returns (peak depth on the span, s to 3 px, s to dry after the rain, stored at the peak)."""
    ter = FlatTer([(0, 300.0), (999, 300.0), (1000, 200.0), (1000 + length, 200.0), (1000 + length + 30, 170.0), (3000, 0.0)])
    f = W.WaterField(ter, [town(1, 1000.0, 1000.0 + length)], [], 1)
    f.sizes[1] = size
    ks = f.film.span_cells[1]
    per = rain_px * G / (hit_area or W.HIT_AREA)
    peak, t3, dry = 0.0, None, None
    n = int((seconds + tail) / DT)
    for i in range(n):
        t = (i + 1) * DT
        f.update(DT, {k: per * DT for k in ks} if t <= seconds else {})
        if i % 3 == 0:
            d = f.span_depth(1)
            if d > peak:
                peak = d
            if t3 is None and d >= W.FLOOD_DEPTH:
                t3 = t
            if t > seconds and dry is None and d < W.WET_DEPTH:
                dry = t - seconds
    return peak, t3, dry


def sheet(a):
    simulation, W = load(a.sim_dir)
    rain = {"steady": 0.157, "downpour": 0.208, "supercell": 0.294}       # px^2/s per px of street at HIT_AREA 1.15 (measured, PLAN.md Phase L)
    print(f"a flat 814 px street, rain of 240 s then dry; KU {W.KU}, KU_STREET {W.KU_STREET}, SLOPE_MIN {W.SLOPE_MIN}, FLOOD_DEPTH {W.FLOOD_DEPTH}, "
          f"OVERFLOW_DEPTH {W.OVERFLOW_DEPTH}")
    print(f"{'rain':10s}{'drains':8s}{'cap/px':>8s}{'peak px':>9s}{'s to 3 px':>10s}{'s to dry':>9s}  state")
    for rn, rp in rain.items():
        for cn, size in W.CLASSES.items():
            peak, t3, dry = sheet_case(W, rp, size)
            capx = W.capacity(W.inlets_for(1000.0, 1814.0), size) / 814.0
            st = "overflowing" if peak >= W.OVERFLOW_DEPTH else "flooded" if peak >= W.FLOOD_DEPTH else "ponding" if peak >= W.WET_DEPTH else "wet"
            print(f"{rn:10s}{cn:8s}{capx:8.3f}{peak:9.2f}{str(t3 if t3 is None else round(t3)):>10}{str(dry if dry is None else round(dry)):>9}  {st}")


# ---------------------------------------------------------------------------------------------------- record and replay
def record_storm(simulation, seed, terrain, ctype, town_id, speed=1.0, preset=None, n_clouds=1, secs=330):
    sim = M.build(simulation, seed, terrain, preset)
    t = next((s for s in sim.settlements if s["id"] == town_id), None)
    if t is None:
        return None
    ticks = []

    def tick(sim_, dt, hits):
        ticks.append([round(dt, 6), [v for k, n in sorted(hits.items()) for v in (k, n)]])
    res = M.storm(sim, ctype, 0.5 * (t["x0"] + t["x1"]), secs, speed, True, n_clouds, per_tick=tick)
    rain = M.summarise(res)
    return {"seed": seed, "terrain": terrain, "preset": preset, "town": town_id, "type": ctype, "speed": speed, "clouds": n_clouds,
            "rain_secs": rain and rain["secs"], "ticks": ticks}


def record(a):
    simulation, _water = load(a.sim_dir)
    out = []
    for terrain in a.terrains:
        for seed in range(a.seeds[0], a.seeds[1] + 1):
            sim = M.build(simulation, seed, terrain)
            ids = [s["id"] for s in sim.settlements]
            if a.towns == "biggest" and sim.settlements:
                ids = [M.biggest_town(sim)["id"]]
            for tid in ids:
                for ctype in a.types:
                    t0 = time.time()
                    r = record_storm(simulation, seed, terrain, ctype, tid, a.speed, secs=a.secs)
                    if r:
                        out.append(r)
                        print(f"recorded {terrain}/{seed} town {tid} {ctype}: {r['rain_secs']} s of rain, {len(r['ticks'])} ticks, "
                              f"{time.time() - t0:.0f} s", flush=True)
                    with open(a.out, "w") as fh:
                        json.dump(out, fh, separators=(",", ":"))


def make_fields(water, sim):
    out = {}
    for name, size in water.CLASSES.items():
        f = water.WaterField(sim.terrain, sim.settlements, sim.objects.objs, sim.seed, "normal")
        for sid in f.sizes:
            f.sizes[sid] = size
        out[name] = f
    out["by-kind"] = water.WaterField(sim.terrain, sim.settlements, sim.objects.objs, sim.seed, "normal")
    return out


TAIL_S = 300


def replay(simulation, water, rec):
    """Feed one recorded storm to the drain classes; returns {class: analysis} and the town."""
    sim = M.build(simulation, rec["seed"], rec["terrain"], rec["preset"])
    tid = rec["town"]
    tw = next(s for s in sim.settlements if s["id"] == tid)
    fields = make_fields(water, sim)
    f0 = fields["small"]
    span = f0.film.span_cells[tid]
    lower = f0.film.dirn[span[-1]] or 1
    beyond = [span[-1] + lower * i for i in range(1, 4)] if lower > 0 else [span[0] + lower * i for i in range(1, 4)]
    beyond = [k for k in beyond if 0 <= k < f0.film.nb]
    rows = {n: [] for n in fields}
    clock = t = 0.0
    ticks = rec["ticks"] + [[rec["ticks"][-1][0], []]] * int(TAIL_S / max(DT, rec["ticks"][-1][0]))
    for dt, flat in ticks:
        hits = {flat[i]: flat[i + 1] for i in range(0, len(flat), 2)}
        t += dt
        for f in fields.values():
            f.update(dt, hits)
        clock += dt
        if clock >= 1.0 - 1e-9:
            clock = 0.0
            for n, f in fields.items():
                st, depth, _backed = f.flood_entry(tid)
                h = max((f.film.S[k] / G for k in beyond), default=0.0)
                rows[n].append((t, depth, st, f.inflow(tid), h))
    tm = f0.tmap
    kinds = {tm.kind[f0.film.el_of[k]] for k in span}
    cat = "hollow" if tid in tm.street_el else ("in a hollow" if "basin" in kinds else "sheds")
    out = {n: analyse(r, fields[n].sizes[tid], fields[n].cap_of(tid), tw) for n, r in rows.items()}
    for v in out.values():
        if v:
            v["cat"] = cat
    return tw, out


def analyse(r, size, cap, tw):
    rain = [i for i, x in enumerate(r) if x[3] >= 0.5]
    if not rain:
        return None
    i0, i1 = rain[0], rain[-1]
    peak_i = max(range(len(r)), key=lambda i: r[i][1])
    peak_state = max((x[2] for x in r), key=ORDER.index)
    to_flood = next((r[i][0] - r[i0][0] for i in range(len(r)) if r[i][2] in ("flooded", "overflowing")), None)
    to3 = next((r[i][0] - r[i0][0] for i in range(len(r)) if r[i][1] >= 3.0), None)
    clear = None
    if r[peak_i][1] >= 0.05:
        j = next((i for i in range(peak_i, len(r)) if r[i][1] < 0.05), None)
        if j is not None:
            clear = max(0.0, r[j][0] - r[i1][0])
    return {"peak_state": peak_state, "peak_depth": r[peak_i][1], "to_flood": to_flood, "to3": to3, "clear": clear,
            "secs_flooded": sum(1 for x in r if x[2] in ("flooded", "overflowing")), "size": size, "cap": cap,
            "stream_peak": max(x[4] for x in r), "rain_secs": i1 - i0 + 1}


def med(v):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None


def fmt(x, nd=1):
    return "-" if x is None else f"{x:.{nd}f}"


def table(a):
    simulation, water = load(a.sim_dir)
    for kv in a.set:
        k, v = kv.split("=")
        setattr(water, k, float(v))
        print(f"-- {k} = {v}")
    recs = []
    for path in a.files:
        with open(path) as fh:
            recs += json.load(fh)
    by = a.by
    done = [(r, replay(simulation, water, r)) for r in recs]
    groups = {}
    for rec, (tw, an) in done:
        cat = next((v["cat"] for v in an.values() if v), "-")
        key = (rec["type"], rec["terrain"] if "terrain" in by else "all", cat if "cat" in by else "all")
        groups.setdefault(key, []).append(an)
    print(f"{'rain class':12s}{'terrain':12s}{'towns':12s}{'runs':>5s} | {'drains':8s}{'dry':>4s}{'wet':>4s}{'pnd':>4s}{'FLD':>4s}{'OVF':>4s} "
          f"{'peak px med/max':>16s} {'s to 3px':>9s} {'s to dry':>9s} {'stream px':>10s}")
    for (ctype, terrain, cat), ans in sorted(groups.items(), key=lambda kv: (list(RAIN_CLASS).index(kv[0][0]), kv[0][1], kv[0][2])):
        first = True
        for k in list(water.CLASSES) + ["by-kind"]:
            an = [x[k] for x in ans if x.get(k)]
            if not an:
                continue
            cnt = {s: sum(1 for x in an if x["peak_state"] == s) for s in ORDER}
            pk = [x["peak_depth"] for x in an]
            print(f"{(RAIN_CLASS[ctype] if first else ''):12s}{(terrain if first else ''):12s}{(cat if first else ''):12s}{(str(len(an)) if first else ''):>5s} | "
                  f"{k:8s}{cnt['dry']:4d}{cnt['wet']:4d}{cnt['ponding']:4d}{cnt['flooded']:4d}{cnt['overflowing']:4d} "
                  f"{fmt(med(pk), 2):>8s}/{max(pk):<7.2f} {fmt(med([x['to3'] for x in an])):>9s} {fmt(med([x['clear'] for x in an])):>9s} "
                  f"{fmt(med([x['stream_peak'] for x in an]), 2):>10s}")
            first = False


# ---------------------------------------------------------------------------------------------------- fitting the constants (NS6)
ROWS = [  # (rain class, drain class, what the contract C2'-C4' asks of the peak depth d)
    ("nimbostratus", "large", "d < F", lambda d, F, O: d < F),
    ("nimbostratus", "normal", "d < F", lambda d, F, O: d < F),
    ("cumulonimbus", "small", "d >= F", lambda d, F, O: d >= F),
    ("cumulonimbus", "normal", "d < F", lambda d, F, O: d < F),
    ("cumulonimbus", "large", "d < F", lambda d, F, O: d < F),
    ("supercell", "small", "d >= O", lambda d, F, O: d >= O),
    ("supercell", "normal", "F <= d < O", lambda d, F, O: F <= d < O),
    ("supercell", "large", "d < F", lambda d, F, O: d < F),
]


def peaks(simulation, water, recs):
    """{(record index, class): peak depth on the town's span} for every recorded storm, at the current constants."""
    out = {}
    for ri, rec in enumerate(recs):
        sim = M.build(simulation, rec["seed"], rec["terrain"], rec["preset"])
        fields = make_fields(water, sim)
        fields.pop("by-kind")
        tid = rec["town"]
        top = {n: 0.0 for n in fields}
        ticks = rec["ticks"]
        for n_, (dt, flat) in enumerate(ticks):
            hits = {flat[i]: flat[i + 1] for i in range(0, len(flat), 2)}
            for n, f in fields.items():
                f.update(dt, hits)
            if n_ % 15 == 0:
                for n, f in fields.items():
                    d = f.span_depth(tid)
                    if d > top[n]:
                        top[n] = d
        for n in fields:
            out[(ri, n)] = top[n]
    return out


def fit(a):
    simulation, water = load(a.sim_dir)
    recs = []
    for path in a.files:
        with open(path) as fh:
            recs += json.load(fh)
    results = []
    cache = {}
    if a.cache and os.path.exists(a.cache):
        with open(a.cache) as fh:
            cache = json.load(fh)
    for ku in a.ku:
        for sm in a.slope_min:
            water.KU_STREET, water.SLOPE_MIN = ku, sm
            key = f"{ku},{sm},{len(recs)}"
            if key in cache:
                pk = {(r, c): v for r, c, v in cache[key]}
            else:
                pk = peaks(simulation, water, recs)
                cache[key] = [[r, c, v] for (r, c), v in pk.items()]
                if a.cache:
                    with open(a.cache, "w") as fh:
                        json.dump(cache, fh)
            for F in a.flood:
                for O in a.overflow:
                    if O <= F:
                        continue
                    rows = []
                    for ctype, cls, _txt, test in ROWS:
                        idx = [i for i, r in enumerate(recs) if r["type"] == ctype]
                        ok = sum(1 for i in idx if test(pk[(i, cls)], F, O))
                        rows.append((ok, len(idx)))
                    score = sum(o / n for o, n in rows if n) / len(rows)
                    results.append((score, ku, sm, F, O, rows))
            print(f"KU_STREET {ku} SLOPE_MIN {sm}: replayed {len(recs)} storms", flush=True)
    results.sort(key=lambda r: -r[0])
    print(f"\n{'score':>6s} {'KU_STREET':>9s} {'SLOPE_MIN':>9s} {'FLOOD':>6s} {'OVER':>5s}  " + " ".join(f"{c[0][:4]}/{c[1][:3]}" for c in ROWS))
    for score, ku, sm, F, O, rows in results[:a.top]:
        print(f"{score:6.3f} {ku:9.0f} {sm:9.3f} {F:6.1f} {O:5.1f}  " + " ".join(f"{o:>4d}/{n:<3d}" for o, n in rows))


# ---------------------------------------------------------------------------------------------------- the screenshot case, headless
def storm(a):
    """A town in a storm that exceeds its drains: depth on the pad, the film down the slope beyond it, the pool at the bottom."""
    simulation, W = load(a.sim_dir)
    sim = M.build(simulation, a.seed, a.terrain, None, drains=a.drains)
    w = sim.water
    towns = sim.settlements if a.town == 0 else [s for s in sim.settlements if s["id"] == a.town]
    tw = max(towns, key=lambda s: s["x1"] - s["x0"])
    sid = tw["id"]
    f = w.film
    span = f.span_cells[sid]
    out_dir = f.dirn[span[-1]] or 1
    lowest = span[-1] if out_dir > 0 else span[0]
    beyond = [lowest + out_dir * i for i in range(1, 12) if 0 <= lowest + out_dir * i < f.nb]
    print(f"seed {a.seed} {a.terrain}: {tw['kind']} {tw['id']}, {tw['x1'] - tw['x0']:.0f} px, drains {w.sizes[sid]} px (cap {w.cap_of(sid):.1f} px^2/s), "
          f"water runs {'right' if out_dir > 0 else 'left'}, street is {'a hollow' if sid in w.tmap.street_el else 'ground'}")
    rows = []
    clock = [0.0]
    first = {"film": None, "body": None, "spill": None}

    def hook(sim_, dt, hits):
        t = sim_.world.t
        if first["film"] is None and any(f.S[k] > 0 for k in beyond):
            first["film"] = t
        if first["body"] is None and w.pools and any(b.V > 0.5 for b in w.pools.values()):
            first["body"] = t
        if first["spill"] is None and any(b.spilling for b in w._bodies()):
            first["spill"] = t
        clock[0] += dt
        if clock[0] >= a.every - 1e-9:
            clock[0] = 0.0
            wl = w.water_list()
            body = max(wl, key=lambda b: b["vol"], default=None)
            pad = max((f.S[k] / G for k in span), default=0.0)
            rows.append((t, pad, [round(f.S[k] / G, 2) for k in beyond[:5]], w.flood_entry(sid)[0], body))
    sim.handle_command({"cmd": "hour", "value": 13}, [])
    M.storm(sim, a.type, 0.5 * (tw["x0"] + tw["x1"]), a.secs, 1.0, True, 1, per_tick=hook)
    print(f"{'t':>5s} {'pad px':>7s} {'film beyond the street (px, first 5 cells)':>46s}  {'state':>11s}  body: level y / x0..x1 / depth")
    for t, pad, hb, st, body in rows:
        b = f"{body['y']:.1f} / {body['x0']:.0f}..{body['x1']:.0f} / {body['depth']:.1f}" if body else "-"
        print(f"{t:5.0f} {pad:7.2f} {str(hb):>46s}  {st:>11s}  {b}")
    print("first film beyond the street at t =", first["film"], " first body at t =", first["body"], " first spill at t =", first["spill"])
    led = {k: round(v, 1) for k, v in w.ledger.items()}
    print("ledger", led, "stored", round(w.stored(), 1), "balance", round(led["rain"] - w.stored() - sum(v for k, v in led.items() if k != "rain"), 3))


# ---------------------------------------------------------------------------------------------------- a diagnostic plot
def plot(a):
    """A diagnostic plot (NOT a picture of the game): one pinned storm over one town, the water model run twice (small drains, large
    drains) on the same rain. Drawn with Pillow (already used by the other tools): the depth of the sheet on the street, the film's
    thickness at the first and at the last cell of the stream beyond the street, and the depth of the body that receives it."""
    from PIL import Image, ImageDraw
    simulation, W = load(a.sim_dir)
    sim = M.build(simulation, a.seed, a.terrain)
    tw = M.biggest_town(sim) if not a.town else next(s for s in sim.settlements if s["id"] == a.town)
    tid = tw["id"]
    fields = make_fields(W, sim)
    fields.pop("by-kind")
    fields.pop("normal")
    f0 = fields["small"]
    span = f0.film.span_cells[tid]
    d = f0.film.dirn[span[-1]] or 1
    first_k = span[-1] + d if d > 0 else span[0] + d
    path = [first_k]
    while 0 <= path[-1] + f0.film.dirn[path[-1]] < f0.film.nb and f0.film.dirn[path[-1] + f0.film.dirn[path[-1]]] != 0 and len(path) < 60:
        path.append(path[-1] + f0.film.dirn[path[-1]])
    last_k = path[-1]
    series = {n: [] for n in fields}
    clock = [0.0]
    t_box = [0.0]

    def tick(sim_, dt, hits):
        t_box[0] += dt
        for f in fields.values():
            f.update(dt, hits)
        clock[0] += dt
        if clock[0] >= 0.5:
            clock[0] = 0.0
            for n, f in fields.items():
                el = f.film.el_of[f.film.term[last_k]]
                b = f.elem_body[el]
                series[n].append((t_box[0], f.span_depth(tid), f.film.S[first_k] / G, f.film.S[last_k] / G, b.depth if b and b.V > 0 else 0.0))
    M.storm(sim, a.type, 0.5 * (tw["x0"] + tw["x1"]), a.secs, 1.0, True, 1, per_tick=tick)
    names = [("sheet on the street", (30, 90, 200)), ("film, first cell beyond", (230, 140, 30)),
             ("film, last cell of the stream", (40, 150, 70)), ("receiving body, depth", (150, 60, 170))]
    Wd, Hd, L, R, TOP, BOT = 980, 700, 70, 20, 50, 50
    img = Image.new("RGB", (Wd, Hd), (250, 250, 247))
    dr = ImageDraw.Draw(img)
    t_hi = max(x[0] for x in series["small"])
    y_hi = max(12.0, 1.1 * max(v for s_ in series.values() for row in s_ for v in row[1:]))
    ph = (Hd - TOP - BOT - 40) // 2
    for pi, n in enumerate(("small", "large")):
        top = TOP + pi * (ph + 40)

        def px(tt, v, top=top):
            return (L + tt / t_hi * (Wd - L - R), top + ph - v / y_hi * ph)
        dr.line([(L, top), (L, top + ph), (Wd - R, top + ph)], fill=(40, 40, 40), width=2)
        for v, label in ((W.FLOOD_DEPTH, f"flooded ({W.FLOOD_DEPTH:g} px)"), (W.OVERFLOW_DEPTH, f"overflowing ({W.OVERFLOW_DEPTH:g} px)")):
            x0, y = px(0, v)
            x1, _ = px(t_hi, v)
            dr.line([(x0, y), (x1, y)], fill=(170, 170, 170))
            dr.text((L + 6, y - 12), label, fill=(120, 120, 120))
        for v in range(0, int(y_hi) + 1, 2):
            x, y = px(0, v)
            dr.line([(x - 5, y), (x, y)], fill=(40, 40, 40))
            dr.text((x - 34, y - 6), f"{v} px", fill=(40, 40, 40))
        for tt in range(0, int(t_hi), 50):
            x, y = px(tt, 0)
            dr.line([(x, y), (x, y + 5)], fill=(40, 40, 40))
            dr.text((x - 10, y + 8), f"{tt}s", fill=(40, 40, 40))
        for i, (_label, col) in enumerate(names):
            dr.line([px(row[0], row[1 + i]) for row in series[n]], fill=col, width=3)
        dr.text((L + 6, top - 16), f"{n} drains {fields[n].sizes[tid]:g} px (cap {fields[n].cap_of(tid):.0f} px2/s)", fill=(30, 30, 30))
    for i, (label, col) in enumerate(names):
        dr.rectangle([L + 10 + i * 230, 8, L + 24 + i * 230, 22], fill=col)
        dr.text((L + 30 + i * 230, 9), label, fill=(30, 30, 30))
    dr.text((L, Hd - 24), f"diagnostic plot, not the game: {a.type} over {tw['kind']} {tw['name']} ({tw['x1'] - tw['x0']:.0f} px), seed {a.seed} "
                          f"{a.terrain}; stream cells {first_k}..{last_k}", fill=(90, 90, 90))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    img.save(a.out)
    print(f"wrote {a.out}: " + ", ".join(f"{n}: street peak {max(r[1] for r in s_):.2f} px, stream peak {max(r[2] for r in s_):.2f} / "
                                         f"{max(r[3] for r in s_):.2f} px, receiving body {max(r[4] for r in s_):.1f} px" for n, s_ in series.items()))


# ---------------------------------------------------------------------------------------------------- cost
def bench(a):
    """Milliseconds a tick (step + JSON encoding, as the stream does it) and bytes a frame: dry, in rain, and in an overflow."""
    simulation, W = load(a.sim_dir)
    rows = []
    for name, ctype in (("dry", None), ("rain", "cumulonimbus"), ("overflow", "supercell")):
        sim = M.build(simulation, a.seed, a.terrain, None, drains="small", w=a.w, h=a.h)
        tw = M.biggest_town(sim)
        sim.handle_command({"cmd": "hour", "value": 13}, [])
        if ctype:
            cx = 0.5 * (tw["x0"] + tw["x1"])
            clouds = M.spawn_over(sim, ctype, cx, 1)
            anchors = [c.ax for c in clouds]
            for _ in range(int(a.warm * 30)):
                sim.step(DT)
                for c, ax in zip(clouds, anchors):
                    if c in sim.clouds:
                        c.x += ax - c.ax
                        c.ax = ax
        else:
            for _ in range(int(a.warm * 30)):
                sim.step(DT)
        ms, sizes, wms = [], [], []
        w = getattr(sim, "water", None)
        orig = w._process if w is not None else None
        if w is not None:
            def timed(dt, _o=orig):
                t0 = time.perf_counter()
                _o(dt)
                wms.append((time.perf_counter() - t0) * 1000.0)
            w._process = timed
        for _ in range(a.frames):
            t0 = time.perf_counter()
            msg = sim.step(DT)
            line = json.dumps(msg, separators=(",", ":")) + "\n"
            ms.append((time.perf_counter() - t0) * 1000.0)
            sizes.append(len(line.encode("utf-8")))
        extra = ""
        if w is not None:
            ex = sum(len(json.dumps({k: msg[k]}, separators=(",", ":"))) for k in ("water", "flood", "flow") if k in msg)
            extra = f"  water step {statistics.fmean(wms) if wms else 0.0:.3f} ms per call (every {W.PROCESS_EVERY} ticks)"
            film = len(w.film.act) if hasattr(w, "film") else 0
            extra += f", film cells {film}"
        rows.append((name, statistics.fmean(ms), sorted(ms)[int(len(ms) * 0.95)], statistics.fmean(sizes), extra))
    print(f"{'scene':10s}{'tick ms mean':>13s}{'p95':>8s}{'bytes/frame':>13s}   ({a.w}x{a.h}, seed {a.seed} {a.terrain}, small drains, {a.warm} s warm-up, {a.frames} frames)")
    for name, m, p, b, extra in rows:
        print(f"{name:10s}{m:13.2f}{p:8.2f}{b:13.0f}{extra}")
        print(f"{'':10s}  = {1000.0 / m:.0f} ticks/s mean, {1000.0 / p:.0f} ticks/s at p95")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sim-dir", default=os.path.join(HERE, "..", "sim"))
    sub = ap.add_subparsers(dest="cmd", required=True)

    def seeds(p):
        p.add_argument("--seeds", type=int, nargs=2, default=[11, 18], metavar=("FIRST", "LAST"))
    p = sub.add_parser("census")
    seeds(p)
    sub.add_parser("streams")
    sub.add_parser("sheet")
    p = sub.add_parser("record")
    p.add_argument("out")
    seeds(p)
    p.add_argument("--terrains", nargs="+", default=list(TERRAINS))
    p.add_argument("--types", nargs="+", default=list(RAIN_CLASS))
    p.add_argument("--towns", choices=("all", "biggest"), default="all")
    p.add_argument("--speed", type=float, default=1.0)
    p.add_argument("--secs", type=int, default=330)
    p = sub.add_parser("table")
    p.add_argument("files", nargs="+")
    p.add_argument("--set", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--by", nargs="*", default=["cat"], choices=("terrain", "cat"), help="what to split the rows by (default: the town's type)")
    p = sub.add_parser("fit")
    p.add_argument("files", nargs="+")
    p.add_argument("--ku", type=float, nargs="+", default=[23.0, 35.0, 50.0, 70.0, 100.0])
    p.add_argument("--slope-min", type=float, nargs="+", default=[0.02, 0.04])
    p.add_argument("--flood", type=float, nargs="+", default=[2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0])
    p.add_argument("--overflow", type=float, nargs="+", default=[5.0, 6.0, 7.0, 8.0, 9.0, 10.0])
    p.add_argument("--top", type=int, default=15)
    p.add_argument("--cache", default=None, help="a JSON file of the peak depths per (KU_STREET, SLOPE_MIN): a re-fit of the thresholds is then instant")
    p = sub.add_parser("storm")
    p.add_argument("--seed", type=int, default=12)
    p.add_argument("--terrain", default="rolling")
    p.add_argument("--type", default="supercell")
    p.add_argument("--drains", default="small")
    p.add_argument("--town", type=int, default=0)
    p.add_argument("--secs", type=int, default=240)
    p.add_argument("--every", type=float, default=10.0)
    p = sub.add_parser("plot")
    p.add_argument("--seed", type=int, default=12)
    p.add_argument("--terrain", default="rolling")
    p.add_argument("--type", default="supercell")
    p.add_argument("--town", type=int, default=0)
    p.add_argument("--secs", type=int, default=300)
    p.add_argument("--out", default=os.path.join(HERE, "debug_plots", "v241", "flow_supercell.png"))
    p = sub.add_parser("bench")
    p.add_argument("--seed", type=int, default=12)
    p.add_argument("--terrain", default="rolling")
    p.add_argument("--w", type=int, default=3840)
    p.add_argument("--h", type=int, default=1080)
    p.add_argument("--warm", type=float, default=60.0)
    p.add_argument("--frames", type=int, default=900)
    a = ap.parse_args(argv)
    {"census": census, "streams": streams, "sheet": sheet, "record": record, "table": table, "fit": fit, "storm": storm, "plot": plot, "bench": bench}[a.cmd](a)


if __name__ == "__main__":
    main()
