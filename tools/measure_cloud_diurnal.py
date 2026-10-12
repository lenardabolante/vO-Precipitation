"""Measure how the sky's cloud population follows the time of day.

For each configuration (the default sandbox and some region presets) and several seeds, the sandbox
simulation is run headless through whole sim days (300 sim seconds each) and, per hour of the day
(0..23, from `world.time_of_day`), the tool tabulates

  alive      mean number of clouds alive, by type (time-weighted, so it is a mean over the hour)
  spawn      clouds born per simulated day, by type (automatic spawner only)
  morph      cumulus -> cumulonimbus towerings per simulated day
  tornado    tornadoes started per simulated day

and prints the ratio the diurnal rule is judged by: Cb alive during 22-05 h against 14-19 h, and the
share of Cb spawns + morphs that happen between 23 h and 06 h.

World mode (`--world`) has no individual clouds; it reports, per local hour, how often the dots of the
location list show `storm`, `clear` and `cloudy`, and their mean cloud cover.

Run it from the project root:

    python tools/measure_cloud_diurnal.py                      # default sandbox + 4 presets, 3 seeds, 2 days
    python tools/measure_cloud_diurnal.py --configs sandbox oklahoma --seeds 1 2 --days 2
    python tools/measure_cloud_diurnal.py --world --seeds 1 2 --days 2
    python tools/measure_cloud_diurnal.py --json out.json      # also write the raw numbers
    python tools/measure_cloud_diurnal.py --json after.json --before-json before.json --plot cb.svg   # before/after figure

The season is frozen (year_days=365: one sim day moves the calendar by 1/365 of a year) so the table
shows the day cycle and not the drift of the months. A run opens at 0 h; the first `--warmup` days are
simulated but not counted (the opening sky is fast-forwarded and is not a steady state).
It only reads: nothing is written unless `--json` / `--plot` is given. The sandbox runs take a few minutes per
configuration (3840 px worlds are not needed: the default width is 1920).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SIM_DIR = os.path.join(os.path.dirname(HERE), "sim")
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)

from clouds import TYPE_NAMES                       # noqa: E402
from simulation import Simulation                   # noqa: E402

DAY_S = 300.0
CONFIGS = {                       # name -> (preset id or None, month or None = the preset's own start month)
    "sandbox": (None, 4.0),       # the default sandbox: generated climate, 1 April
    "central_europe": ("central_europe", None),     # temperate
    "oklahoma": ("oklahoma", None),                 # storm climate (May)
    "florida": ("florida", None),                   # tropical / maritime
    "sahara": ("sahara", None),                     # desert
}
DEFAULT_CONFIGS = ("sandbox", "central_europe", "oklahoma", "florida", "sahara")
GROUPS = (("cumulus", ("cumulus",)),
          ("Cb", ("cumulonimbus", "supercell")),
          ("stratiform", ("nimbostratus", "stratocumulus", "altostratus", "stratus")),
          ("high", ("altocumulus", "cirrus")))
NIGHT = tuple(h % 24 for h in range(22, 29))        # 22..05
AFTERNOON = tuple(range(14, 19))                    # 14..18  (14-19 h)
NIGHT_BORN = (23, 0, 1, 2, 3, 4, 5)                 # 23..05  (23-06 h)


def blank():
    return {"time": [0.0] * 24, "alive": {t: [0.0] * 24 for t in TYPE_NAMES},
            "spawn": {t: [0] * 24 for t in TYPE_NAMES}, "morph": [0] * 24, "tornado": [0] * 24,
            "from_morph_alive": [0.0] * 24}


def run_sandbox(preset, month, seed, days, warmup, w, h, fps, year_days, start_hour=0.0, quiet=False):
    kw = dict(seed=seed, w=w, h=h, fps=fps, preset=preset, hour=start_hour, year_days=year_days)
    if month is not None:
        kw["month"] = month
    sim = Simulation(**kw)
    dt = 1.0 / fps
    n_total = int(round((days + warmup) * DAY_S * fps))
    n_skip = int(round(warmup * DAY_S * fps))
    acc = blank()
    seen = {c.id for c in sim.clouds}
    last_tornadoes = sim.tornadoes_total
    for i in range(n_total):
        sim.step(dt)
        counted = i >= n_skip
        hr = int(sim.world.time_of_day * 24.0) % 24
        # births: ids not seen before (the opening clouds are in `seen` from the start)
        for c in sim.clouds:
            if c.id not in seen:
                seen.add(c.id)
                if counted:
                    acc["spawn"][c.type][hr] += 1
        if counted:
            acc["time"][hr] += dt
            for c in sim.clouds:
                if c.merging_into is not None:
                    continue
                acc["alive"][c.type][hr] += dt
                if c.from_type is not None and c.type == "cumulonimbus":
                    acc["from_morph_alive"][hr] += dt
            if sim.tornadoes_total != last_tornadoes:
                acc["tornado"][hr] += sim.tornadoes_total - last_tornadoes
        last_tornadoes = sim.tornadoes_total
        # towerings are announced in the tick's events (_hook_events remembers them)
        for e in getattr(sim, "_last_events", ()):
            if "began towering" in e and counted:
                acc["morph"][hr] += 1
    return acc


def _hook_events(sim_cls):
    """Wrap Simulation._build_frame to remember the tick's event strings in `_last_events`."""
    orig = sim_cls._build_frame

    def wrapped(self, events, deaths, ice_alive):
        self._last_events = events
        return orig(self, events, deaths, ice_alive)

    sim_cls._build_frame = wrapped


def merge(accs):
    out = blank()
    for a in accs:
        for k in ("time", "morph", "tornado", "from_morph_alive"):
            for i in range(24):
                out[k][i] += a[k][i]
        for t in TYPE_NAMES:
            for i in range(24):
                out["alive"][t][i] += a["alive"][t][i]
                out["spawn"][t][i] += a["spawn"][t][i]
    return out


def per_hour(acc):
    """Normalise: mean alive per hour, and spawns / morphs / tornadoes per simulated day."""
    days = [max(acc["time"][i], 1e-9) / (DAY_S / 24.0) for i in range(24)]   # sim days observed in this bin
    res = {"alive": {}, "spawn": {}}
    for name, types in GROUPS:
        res["alive"][name] = [sum(acc["alive"][t][i] for t in types) / max(acc["time"][i], 1e-9) for i in range(24)]
        res["spawn"][name] = [sum(acc["spawn"][t][i] for t in types) / days[i] for i in range(24)]
    res["alive"]["total"] = [sum(res["alive"][n][i] for n, _ in GROUPS) for i in range(24)]
    res["spawn"]["total"] = [sum(res["spawn"][n][i] for n, _ in GROUPS) for i in range(24)]
    res["alive"]["supercell"] = [acc["alive"]["supercell"][i] / max(acc["time"][i], 1e-9) for i in range(24)]   # (a subset of Cb)
    res["spawn"]["supercell"] = [acc["spawn"]["supercell"][i] / days[i] for i in range(24)]
    res["alive"]["Cb_from_tower"] = [acc["from_morph_alive"][i] / max(acc["time"][i], 1e-9) for i in range(24)]
    res["morph"] = [acc["morph"][i] / days[i] for i in range(24)]
    res["tornado"] = [acc["tornado"][i] / days[i] for i in range(24)]
    res["days"] = days
    return res


def mean(xs):
    xs = list(xs)
    return sum(xs) / len(xs) if xs else 0.0


def summary(res):
    cb = res["alive"]["Cb"]
    night = mean(cb[h] for h in NIGHT)
    aft = mean(cb[h] for h in AFTERNOON)
    born = [res["spawn"]["Cb"][h] + res["morph"][h] for h in range(24)]
    born_night = mean(born[h] for h in NIGHT_BORN)
    born_aft = mean(born[h] for h in AFTERNOON)
    cu = res["alive"]["cumulus"]
    return {"cb_night": night, "cb_aft": aft, "cb_ratio": night / aft if aft > 0 else float("nan"),
            "born_night": born_night, "born_aft": born_aft,
            "born_ratio": born_night / born_aft if born_aft > 0 else float("nan"),
            "cu_night": mean(cu[h] for h in NIGHT), "cu_day": mean(cu[h] for h in range(9, 19)),
            "tot_night": mean(res["alive"]["total"][h] for h in NIGHT),
            "tot_day": mean(res["alive"]["total"][h] for h in range(9, 19)),
            "strat_night": mean(res["alive"]["stratiform"][h] for h in NIGHT),
            "strat_day": mean(res["alive"]["stratiform"][h] for h in range(9, 19))}


def print_table(name, res, nseeds, days):
    print(f"\n=== {name}  ({nseeds} seeds x {days} days)  hour-of-day table ===")
    cols = ["cumulus", "Cb", "stratiform", "high", "total"]
    print("hour | alive: " + " ".join(f"{c:>10}" for c in cols) + " | spawn/day: cumul     Cb  strat   high | morph/day tornado/day Cb_tower_alive")
    for h in range(24):
        a = " ".join(f"{res['alive'][c][h]:10.2f}" for c in cols)
        s = " ".join(f"{res['spawn'][c][h]:6.2f}" for c in ("cumulus", "Cb", "stratiform", "high"))
        print(f"{h:4d} |        {a} |           {s} | {res['morph'][h]:9.2f} {res['tornado'][h]:11.3f} {res['alive']['Cb_from_tower'][h]:15.3f}")
    s = summary(res)
    sc = res["alive"]["supercell"]
    print(f"  supercells alive  22-05h {mean(sc[h] for h in NIGHT):.3f}   14-18h {mean(sc[h] for h in AFTERNOON):.3f};  born per day {sum(res['spawn']['supercell']):.2f};"
          f"  tornadoes per day {sum(res['tornado']):.3f}  (by hour: " + " ".join(f"{h}:{res['tornado'][h]:.2f}" for h in range(24) if res['tornado'][h] > 0) + ")")
    print(f"  Cb alive   22-05h {s['cb_night']:.3f}   14-18h {s['cb_aft']:.3f}   ratio {s['cb_ratio']:.3f}")
    print(f"  Cb born    23-05h {s['born_night']:.3f}/h  14-18h {s['born_aft']:.3f}/h  ratio {s['born_ratio']:.3f}   (spawns + towerings per sim day per hour-bin)")
    print(f"  cumulus alive  night {s['cu_night']:.2f}  day(9-18h) {s['cu_day']:.2f}   stratiform night {s['strat_night']:.2f} day {s['strat_day']:.2f}"
          f"   all clouds night {s['tot_night']:.2f} day {s['tot_day']:.2f}")


# -- World mode -----------------------------------------------------------------------------------
def run_world(seed, month, days, warmup, fps, year_days=365, step_s=1.0):
    from worldsim import WorldSim
    ws = WorldSim(seed, fps, month=month, hour=0.0, year_days=year_days)
    ws._build_grid = lambda: {}                  # the dots do not read the grid; skip building it
    n_total = int(round((days + warmup) * DAY_S / step_s))
    n_skip = int(round(warmup * DAY_S / step_s))
    labels = ("storm", "rain", "snow", "fog", "cloudy", "clear")
    cnt = {k: [0] * 24 for k in labels}
    cloud = [0.0] * 24
    n = [0] * 24
    for i in range(n_total):
        fr = ws.step(step_s)
        if i < n_skip:
            continue
        for loc in fr["locations"]:
            hr = int(loc["local_hour"]) % 24
            cnt[loc["weather"]][hr] += 1
            cloud[hr] += loc["cloud"]
            n[hr] += 1
    return cnt, cloud, n


def print_world(results):
    labels = ("storm", "rain", "snow", "fog", "cloudy", "clear")
    cnt = {k: [0] * 24 for k in labels}
    cloud = [0.0] * 24
    n = [0] * 24
    for c, cl, nn in results:
        for k in labels:
            for i in range(24):
                cnt[k][i] += c[k][i]
        for i in range(24):
            cloud[i] += cl[i]
            n[i] += nn[i]
    print("\n=== World mode: share of dots (all locations, local hour) ===")
    print("hour |  storm   rain  cloudy  clear | mean cloud")
    for h in range(24):
        d = max(n[h], 1)
        print(f"{h:4d} | " + " ".join(f"{cnt[k][h] / d:6.3f}" for k in ("storm", "rain", "cloudy", "clear")) + f" | {cloud[h] / d:8.3f}")
    d_n = sum(n[h] for h in NIGHT)
    d_a = sum(n[h] for h in AFTERNOON)
    sn = sum(cnt["storm"][h] for h in NIGHT) / max(d_n, 1)
    sa = sum(cnt["storm"][h] for h in AFTERNOON) / max(d_a, 1)
    rn = sum(cnt["rain"][h] for h in NIGHT) / max(d_n, 1)
    ra = sum(cnt["rain"][h] for h in AFTERNOON) / max(d_a, 1)
    print(f"  storm share  22-05h {sn:.4f}  14-18h {sa:.4f}  ratio {sn / sa if sa else float('nan'):.3f}")
    print(f"  rain share   22-05h {rn:.4f}  14-18h {ra:.4f}  ratio {rn / ra if ra else float('nan'):.3f}")


PALETTE = ("#2b6cb0", "#c05621", "#2f855a", "#805ad5", "#b7791f", "#718096")


def plot(series, path, title="cumulonimbus + supercell alive against hour of day"):
    """One diagnostic figure: Cb alive against hour, one line per series ({label: per-hour result}; a label ending in
    ' (before)' is drawn dashed). Hand-written SVG, so it needs nothing beyond the standard library."""
    W_, H_, L, R, T, B = 760, 380, 52, 150, 34, 44
    ymax = max([max(r["alive"]["Cb"]) for r in series.values()] + [0.5])
    ymax = float(int(ymax * 1.15 * 2 + 1)) / 2.0
    def X(h): return L + (W_ - L - R) * (h / 23.0)
    def Y(v): return T + (H_ - T - B) * (1.0 - v / ymax)
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W_} {H_}" font-family="Segoe UI, Arial, sans-serif" font-size="11">',
           '<rect width="100%" height="100%" fill="#ffffff"/>',
           f'<text x="{L}" y="20" font-size="13" font-weight="600" fill="#1a202c">{title}</text>']
    for k in range(int(ymax * 2) + 1):
        v = k / 2.0
        out.append(f'<line x1="{L}" x2="{W_ - R}" y1="{Y(v):.1f}" y2="{Y(v):.1f}" stroke="#e2e8f0"/>')
        out.append(f'<text x="{L - 6}" y="{Y(v) + 4:.1f}" text-anchor="end" fill="#4a5568">{v:g}</text>')
    for h in range(0, 24, 3):
        out.append(f'<text x="{X(h):.1f}" y="{H_ - B + 16}" text-anchor="middle" fill="#4a5568">{h:02d}</text>')
    out.append(f'<rect x="{X(22):.1f}" y="{T}" width="{X(23) - X(22):.1f}" height="{H_ - T - B}" fill="#1a365d" opacity="0.08"/>')
    out.append(f'<rect x="{X(0):.1f}" y="{T}" width="{X(5) - X(0):.1f}" height="{H_ - T - B}" fill="#1a365d" opacity="0.08"/>')
    out.append(f'<rect x="{X(14):.1f}" y="{T}" width="{X(18) - X(14):.1f}" height="{H_ - T - B}" fill="#dd6b20" opacity="0.10"/>')
    out.append(f'<text x="{(L + W_ - R) / 2:.0f}" y="{H_ - 8}" text-anchor="middle" fill="#4a5568">hour of day (shaded: 22-05 h night, 14-18 h afternoon)</text>')
    out.append(f'<text transform="translate(14,{(T + H_ - B) / 2:.0f}) rotate(-90)" text-anchor="middle" fill="#4a5568">Cb alive (mean count)</text>')
    names = sorted({k.replace(" (before)", "") for k in series})
    for i, (label, res) in enumerate(series.items()):
        col = PALETTE[names.index(label.replace(" (before)", "")) % len(PALETTE)]
        dash = ' stroke-dasharray="5 4"' if label.endswith("(before)") else ""
        pts = " ".join(f"{X(h):.1f},{Y(v):.1f}" for h, v in enumerate(res["alive"]["Cb"]))
        out.append(f'<polyline points="{pts}" fill="none" stroke="{col}" stroke-width="2"{dash}/>')
        ly = T + 8 + 16 * i
        out.append(f'<line x1="{W_ - R + 12}" x2="{W_ - R + 36}" y1="{ly}" y2="{ly}" stroke="{col}" stroke-width="2"{dash}/>')
        out.append(f'<text x="{W_ - R + 42}" y="{ly + 4}" fill="#2d3748">{label}</text>')
    out.append("</svg>")
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(chr(10).join(out))
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--configs", nargs="+", default=list(DEFAULT_CONFIGS), choices=list(CONFIGS))
    ap.add_argument("--seeds", nargs="+", type=int, default=[1, 2, 3])
    ap.add_argument("--days", type=float, default=2.0, help="counted sim days per run (default 2)")
    ap.add_argument("--warmup", type=float, default=0.5, help="sim days simulated first and not counted (default 0.5)")
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=15, help="steps per sim second (default 15; the app uses 30)")
    ap.add_argument("--year-days", type=int, default=365)
    ap.add_argument("--world", action="store_true", help="measure World mode instead of the sandbox")
    ap.add_argument("--months", nargs="+", type=float, default=[4.0, 7.0], help="--world: months to run")
    ap.add_argument("--json", help="also write the raw numbers here")
    ap.add_argument("--plot", help="also write a Cb-alive-against-hour figure here (svg)")
    ap.add_argument("--before-json", help="a --json file of an earlier run: drawn dashed in the figure for comparison")
    args = ap.parse_args()

    t0 = time.time()
    out = {}
    if args.world:
        results = []
        for m in args.months:
            for s in args.seeds:
                results.append(run_world(s, m, args.days, args.warmup, args.fps, args.year_days))
        print_world(results)
        print(f"\n({time.time() - t0:.0f} s)")
        return 0
    _hook_events(Simulation)
    tables = {}
    for name in args.configs:
        preset, month = CONFIGS[name]
        accs = []
        for s in args.seeds:
            accs.append(run_sandbox(preset, month, s, args.days, args.warmup, args.width, args.height,
                                    args.fps, args.year_days))
            print(f"  [{name} seed {s} done, {time.time() - t0:.0f} s]", file=sys.stderr, flush=True)
        res = per_hour(merge(accs))
        tables[name] = res
        print_table(name, res, len(args.seeds), args.days)
    print("\n=== summary ===")
    print("config            Cb 22-05h  Cb 14-18h  ratio | born/h 23-05  14-18  ratio | cumulus night/day | strat night/day | all night/day")
    for name, res in tables.items():
        s = summary(res)
        print(f"{name:16s} {s['cb_night']:9.3f} {s['cb_aft']:10.3f} {s['cb_ratio']:6.2f} | {s['born_night']:12.3f} {s['born_aft']:6.3f} {s['born_ratio']:6.2f} |"
              f" {s['cu_night']:6.2f}/{s['cu_day']:<6.2f}   | {s['strat_night']:6.2f}/{s['strat_day']:<6.2f}  | {s['tot_night']:5.2f}/{s['tot_day']:<5.2f}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(tables, f)
    if args.plot:
        series = {}
        if args.before_json:
            with open(args.before_json, encoding="utf-8") as f:
                for k, v in json.load(f).items():
                    if k in tables:
                        series[k + " (before)"] = v
        series.update(tables)
        plot(series, args.plot)
        print("plot:", args.plot)
    print(f"\n({time.time() - t0:.0f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
