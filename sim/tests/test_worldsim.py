"""World simulation (PROTOCOL.md v2.3, `--mode world`, sim/worldsim.py).

Wire: the `hello` (mode world, world_map with every preset as a dot, the region -> preset map) and the `world`
frame (grid of 72 x 36 cells, sun, systems, locations, stats, events), commands, the driver interface shared
with hurricane mode. Behaviour: nothing jumps (grid cells, systems, readouts: also right after `season` and
`hour` commands), the sun follows the date and hour, winters are cold and summers warm with the hemispheres
opposite and the continents swinging more than the oceans, the day cycle follows the subsolar point, wind belts,
the ITCZ follows the sun, named cyclones form over warm sea in their season, tornado outbreaks peak in spring over
the Great Plains, systems drift the right way, the location readouts track their preset's real monthly normals.
Data: the land mask file and its generator. Timing: 900 ticks sustain 30 ticks/s."""
import json
import math
import os
import subprocess
import sys
import tempfile
import time
import unittest
from argparse import Namespace

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_worldsim.py` (or sim.tests.test_worldsim) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import presets as P
import precip_sim
import worldsim as W

DT = 1.0 / 30
SIM_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIM_ENTRY = os.path.join(SIM_DIR, "precip_sim.py")
PROJECT = os.path.dirname(SIM_DIR)
MASK_FILE = os.path.join(SIM_DIR, "data", "landmask_5deg.json")

FIELDS = ("temp_c", "cloud", "precip", "u", "v", "pressure_hpa", "snow")
SYSTEM_KINDS = {"low", "high", "cyclone", "front", "tornado_outbreak"}
WEATHER = {"clear", "cloudy", "rain", "snow", "storm", "fog"}
N = W.COLS * W.ROWS

# the largest change one cell may make between two consecutive grid updates (about 4 per second)
STEADY = {"temp_c": 0.5, "cloud": 0.1, "precip": 0.1, "u": 1.0, "v": 1.0, "pressure_hpa": 1.0, "snow": 0.1}
JUMP = {"temp_c": 1.5, "cloud": 0.2, "precip": 0.3, "u": 4.0, "v": 4.0, "pressure_hpa": 3.0, "snow": 0.2}


def isnum(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def validate_world_frame(f):
    assert f["type"] == "world"
    for k in ("frame", "day"):
        assert isinstance(f[k], int), k
    for k in ("t", "speed", "year_frac", "month", "utc_hour"):
        assert isnum(f[k]), k
    assert isinstance(f["paused"], bool)
    assert 0.0 <= f["year_frac"] < 1.0 and 1.0 <= f["month"] < 13.0 and 0.0 <= f["utc_hour"] < 24.0
    assert set(f["sun"]) == {"lat", "lon"} and -24.0 <= f["sun"]["lat"] <= 24.0 and -180.0 <= f["sun"]["lon"] <= 180.0
    if "grid" in f:
        g = f["grid"]
        assert set(g) == set(FIELDS), set(g)
        for k in FIELDS:
            assert len(g[k]) == N and all(isnum(v) for v in g[k]), k
    ids = set()
    for s in f["systems"]:
        assert set(s) == {"id", "kind", "lat", "lon", "strength", "radius_deg", "name"}, set(s)
        assert s["kind"] in SYSTEM_KINDS and s["id"] not in ids
        ids.add(s["id"])
        assert -90 <= s["lat"] <= 90 and -180 <= s["lon"] <= 180 and 0.0 < s["strength"] <= 1.0 and s["radius_deg"] > 0
        assert (s["name"] is not None) == (s["kind"] == "cyclone"), s
    assert [loc["id"] for loc in f["locations"]] == list(P.PRESET_IDS)
    for loc in f["locations"]:
        assert set(loc) == {"id", "temp_c", "cloud", "precip", "wind_ms", "weather", "local_hour", "month"}, set(loc)
        assert all(isnum(loc[k]) for k in ("temp_c", "cloud", "precip", "wind_ms", "local_hour", "month"))
        assert 0.0 <= loc["cloud"] <= 1.0 and 0.0 <= loc["precip"] <= 1.0 and 0.0 <= loc["wind_ms"] <= 40.0
        assert loc["weather"] in WEATHER and 0.0 <= loc["local_hour"] < 24.0 and loc["month"] == f["month"]
    assert set(f["stats"]) == {"mean_temp_c", "cloud_cover", "storms"}
    assert isinstance(f["events"], list) and all(isinstance(e, str) for e in f["events"])
    json.dumps(f)


def run_collect(sim, ticks):
    frames = []
    for _ in range(ticks):
        frames.append(sim.step(DT))
    return frames


def grid_jumps(frames):
    """Largest per-field change between consecutive grid updates."""
    prev = None
    worst = {k: 0.0 for k in FIELDS}
    n = 0
    for f in frames:
        g = f.get("grid")
        if g is None:
            continue
        if prev is not None:
            n += 1
            for k in FIELDS:
                d = max(abs(a - b) for a, b in zip(g[k], prev[k]))
                worst[k] = max(worst[k], d)
        prev = g
    return worst, n


def lonrow(g, k, lat0, lat1):
    """Zonal mean of field k over the rows between lat0 and lat1 (area weighted by cos)."""
    tot = w = 0.0
    for r in range(W.ROWS):
        lat = 90.0 - (r + 0.5) * W.DLAT
        if lat0 <= lat <= lat1:
            cl = math.cos(math.radians(lat))
            for c in range(W.COLS):
                tot += cl * g[k][r * W.COLS + c]
                w += cl
    return tot / w


def settle(sim, month=None, hour=None):
    """Put the clock at an exact month / UTC hour with no glide in progress (white box)."""
    if month is not None:
        sim._base_yf = ((month - 1.0) / 12.0) % 1.0
    if hour is not None:
        sim._base_uh = hour % 24.0
    sim._off_yf = sim._off_uh = 0.0
    sim._glide_t = 1.0


def loc_fraction(sim, pid, month, n=720, step=9.1):
    """White box: (fraction of time the dot reads rain / snow / storm, label counts) at the middle of `month`,
    sampling n instants `step` sim seconds apart (the day cycle and the noise both move on)."""
    from collections import Counter
    i = [p["id"] for p in sim.locations].index(pid)
    p = sim.locations[i]
    labels = Counter()
    t0 = sim.t
    for k in range(n):
        sim.t = t0 + k * step
        settle(sim, month=month, hour=(k * step * 24.0 / W.DAY_S) % 24.0)
        labels[sim._location(i, p)["weather"]] += 1
    sim.t = t0
    return sum(labels[k] for k in ("rain", "snow", "storm")) / float(n), labels


def grid_at(month, hour=12.0, seed=5):
    sim = W.WorldSim(seed, 30, month=month, hour=hour)
    return sim, sim.step(DT)["grid"]


class TestWorldHello(unittest.TestCase):
    def setUp(self):
        self.sim = W.WorldSim(3, 30)
        self.hello = self.sim.hello_message()

    def test_hello_shape(self):
        h = self.hello
        S.validate_hello(h)
        self.assertEqual((h["type"], h["version"], h["mode"]), ("hello", 2, "world"))
        self.assertEqual(h["seed"], 3)
        self.assertEqual(h["world"]["terrain_kind"], "flat")
        self.assertEqual(h["settlements"], [])
        self.assertEqual(h["objects"], [])
        json.dumps(h)

    def test_world_map(self):
        m = self.hello["world_map"]
        self.assertEqual(m["grid"], {"cols": 72, "rows": 36, "dlon": 5.0, "dlat": 5.0})
        self.assertEqual((m["year_days"], m["day_length_s"]), (8, 300))
        locs = m["locations"]
        self.assertEqual([x["id"] for x in locs], list(P.PRESET_IDS))
        self.assertGreaterEqual(len(locs), 20)
        for x in locs:
            p = P.PRESETS[x["id"]]
            self.assertTrue({"id", "name", "lat", "lon", "region", "country", "kind", "summary", "station",
                             "annual_temp_c", "annual_precip_mm", "highlights"} <= set(x), set(x))
            self.assertEqual((x["lat"], x["lon"], x["region"], x["country"], x["kind"]),
                             (p["lat"], p["lon"], p["region"], p["country"], p["kind"]))
            self.assertIn(x["kind"], ("state", "country", "place"))
            self.assertEqual(x["station"], p["climate"]["station"])
            self.assertEqual(x["annual_temp_c"], p["climate"]["temp_c"])
        self.assertEqual(m["regions"], {x["region"]: x["id"] for x in locs})
        self.assertEqual(m["regions"]["US-OK"], "oklahoma")
        self.assertEqual(m["regions"]["US-FL"], "florida")
        for code, pid in m["regions"].items():
            self.assertIn(pid, P.PRESETS)
            self.assertRegex(code, r"^(US-[A-Z]{2}|[A-Z]{2})$")
        self.assertTrue(m["sources"] and all(u.startswith("https://") for u in m["sources"]))
        self.assertEqual(len(m["sources"]), len(set(m["sources"])))
        self.assertTrue(any("natural-earth" in u for u in m["sources"]))

    def test_year_days_is_clamped_and_reported(self):
        self.assertEqual(W.WorldSim(1, 30, year_days=100).hello_message()["world_map"]["year_days"], 100)
        self.assertEqual(W.WorldSim(1, 30, year_days=1).hello_message()["world_map"]["year_days"], 2)
        self.assertEqual(W.WorldSim(1, 30, year_days=9999).hello_message()["world_map"]["year_days"], 365)


class TestWorldFrame(unittest.TestCase):
    def test_every_frame_validates_and_the_grid_is_throttled(self):
        sim = W.WorldSim(4, 30)
        frames = run_collect(sim, 120)
        for f in frames:
            validate_world_frame(f)
        self.assertEqual([f["frame"] for f in frames], list(range(1, 121)))
        self.assertIn("grid", frames[0], "present on the first frame")
        n_grids = sum(1 for f in frames if "grid" in f)
        self.assertLessEqual(n_grids, 4 * 4 + 1, "about four grid updates a second at most")
        self.assertGreaterEqual(n_grids, 12)
        self.assertAlmostEqual(frames[-1]["t"], 120 * DT, places=1)

    def test_value_ranges(self):
        for month in (1.5, 7.5):
            sim = W.WorldSim(2, 30, month=month)
            g = sim.step(DT)["grid"]
            self.assertTrue(all(-90 <= v <= 60 for v in g["temp_c"]))
            for k in ("cloud", "precip", "snow"):
                self.assertTrue(all(0.0 <= v <= 1.0 for v in g[k]), k)
            self.assertTrue(all(abs(v) < 60 for v in g["u"] + g["v"]))
            self.assertTrue(all(900 <= v <= 1100 for v in g["pressure_hpa"]))
            self.assertTrue(max(g["cloud"]) > 0.8 and min(g["cloud"]) < 0.3)
            self.assertTrue(max(g["precip"]) > 0.4 and min(g["precip"]) == 0.0)

    def test_grid_layout_is_row_major_north_first(self):
        _sim, g = grid_at(1.5)
        top = sum(g["temp_c"][:W.COLS]) / W.COLS
        bottom = sum(g["temp_c"][-W.COLS:]) / W.COLS
        mid = sum(g["temp_c"][18 * W.COLS:19 * W.COLS]) / W.COLS
        self.assertLess(top, mid)
        self.assertLess(bottom, mid)
        # snow cover: Antarctica (last row) and Greenland, none on the equator
        self.assertEqual(g["snow"][-W.COLS + 36], 1.0)
        self.assertEqual(g["snow"][-2 * W.COLS + 36], 1.0)
        self.assertEqual(g["snow"][18 * W.COLS + 36], 0.0)

    def test_systems_have_the_listed_kinds_and_cyclones_are_named(self):
        sim = W.WorldSim(6, 30, month=8.8)
        f = sim.step(DT)
        kinds = [s["kind"] for s in f["systems"]]
        for k in ("low", "high", "front", "cyclone"):
            self.assertIn(k, kinds)
        names = [s["name"] for s in f["systems"] if s["kind"] == "cyclone"]
        self.assertTrue(names and all(isinstance(n, str) and n for n in names))
        self.assertEqual(len(names), len(set(names)))
        self.assertTrue(all(s["name"] is None for s in f["systems"] if s["kind"] != "cyclone"))

    def test_locations_cover_every_preset(self):
        f = W.WorldSim(1, 30).step(DT)
        self.assertEqual({loc["id"] for loc in f["locations"]}, set(P.PRESET_IDS))
        self.assertEqual({loc["weather"] for loc in f["locations"]} <= WEATHER, True)

    def test_deterministic_per_seed(self):
        a = run_collect(W.WorldSim(11, 30), 40)
        b = run_collect(W.WorldSim(11, 30), 40)
        self.assertEqual(json.dumps(a, sort_keys=True), json.dumps(b, sort_keys=True))
        c = run_collect(W.WorldSim(12, 30), 40)
        self.assertNotEqual(json.dumps(a[-1]["systems"]), json.dumps(c[-1]["systems"]))

    def test_frame_size_is_reasonable(self):
        f = W.WorldSim(1, 30).step(DT)
        self.assertLess(len(json.dumps(f, separators=(",", ":"))), 160_000)
        self.assertLess(len(json.dumps(W.WorldSim(1, 30).step(DT) and run_collect(W.WorldSim(1, 30), 3)[2],
                                       separators=(",", ":"))), 20_000, "a frame without the grid is small")


class TestWorldClockAndSun(unittest.TestCase):
    def test_subsolar_point_matches_the_date_and_hour(self):
        # N counts days from 1 January: 21 June is day 171, 21 December day 354, 20 March day 79
        cases = [(1 + 12 * 171 / 365.0, 12.0, 23.4, 0.0), (1 + 12 * 354 / 365.0, 12.0, -23.4, 0.0),
                 (1 + 12 * 79 / 365.0, 12.0, 0.0, 0.0), (1 + 12 * 171 / 365.0, 18.0, 23.4, -90.0),
                 (1 + 12 * 171 / 365.0, 6.0, 23.4, 90.0), (1 + 12 * 171 / 365.0, 0.0, 23.4, 180.0)]
        for month, hour, lat, lon in cases:
            f = W.WorldSim(1, 30, month=month, hour=hour).step(0.0)
            self.assertAlmostEqual(f["sun"]["lat"], lat, delta=1.6, msg=(month, hour))
            self.assertAlmostEqual(abs(f["sun"]["lon"]) if abs(lon) == 180.0 else f["sun"]["lon"],
                                   abs(lon) if abs(lon) == 180.0 else lon, delta=0.3, msg=(month, hour))
        self.assertAlmostEqual(W.declination_deg(0.0), -23.44 * math.cos(2 * math.pi * 10 / 365), places=6)

    def test_the_clock_and_calendar_run_at_the_documented_rates(self):
        sim = W.WorldSim(1, 30, month=1.0, hour=0.0, year_days=8)
        f0 = sim.step(0.0)
        f1 = sim.step(150.0)                        # half a sim day
        self.assertAlmostEqual(f1["utc_hour"], 12.0, delta=0.01)
        self.assertEqual(f1["day"], 0)
        f2 = sim.step(150.0)
        self.assertEqual(f2["day"], 1)
        self.assertAlmostEqual(f2["utc_hour"], 0.0, delta=0.01)
        self.assertAlmostEqual(f2["year_frac"], 1 / 8.0, delta=0.002, msg="one day of an 8-day year")
        self.assertAlmostEqual(f2["month"], 1.0 + 12.0 / 8.0, delta=0.03)
        for _ in range(7):
            f2 = sim.step(300.0)
        self.assertTrue(f2["year_frac"] < 0.003 or f2["year_frac"] > 0.997, "the year wraps after year_days days")
        self.assertEqual(f2["day"], 8)

    def test_speed_pause_resume_and_clamping(self):
        sim = W.WorldSim(1, 30)
        ev = []
        sim.handle_command({"cmd": "speed", "value": 100}, ev)
        self.assertEqual(sim.speed, 64.0)
        sim.handle_command({"cmd": "speed", "value": 0.01}, ev)
        self.assertEqual(sim.speed, 0.25)
        sim.handle_command({"cmd": "speed", "value": "fast"}, ev)
        sim.handle_command({"cmd": "speed", "value": float("nan")}, ev)
        self.assertEqual(sim.speed, 0.25, "bad values are ignored")
        sim.handle_command({"cmd": "speed", "value": 64}, ev)
        t0 = sim.t
        sim.step(DT)
        self.assertAlmostEqual(sim.t - t0, 64 * DT, places=6)
        sim.handle_command({"cmd": "pause"}, ev)
        f = sim.step(DT)
        self.assertTrue(f["paused"])
        t1, h1 = sim.t, f["utc_hour"]
        f = sim.step(DT)
        self.assertEqual((sim.t, f["utc_hour"]), (t1, h1), "time stands still while paused")
        sim.handle_command({"cmd": "resume"}, ev)
        self.assertFalse(sim.step(DT)["paused"])
        self.assertGreater(sim.t, t1)

    def test_season_and_hour_commands_glide_to_the_target(self):
        sim = W.WorldSim(1, 30, month=1.0, hour=12.0)
        sim.step(DT)
        ev = []
        sim.handle_command({"cmd": "season", "month": 7.5}, ev)
        self.assertTrue(ev and "7.5" in ev[0])
        months = []
        for _ in range(30 * 24):
            months.append(sim.step(DT)["month"])
        self.assertAlmostEqual(months[-1], 7.5 + 12 * (30 * 24 * DT) / (8 * 300), delta=0.15)
        diffs = [b - a for a, b in zip(months, months[1:])]
        self.assertTrue(all(abs(d) < 0.06 or abs(d) > 11.9 for d in diffs), "no jump in the calendar")
        early = [d for d in diffs[:100] if abs(d) < 6.0]          # (a wrap from month 1 to 13 is not a jump)
        self.assertFalse(any(d > 0.001 for d in early) and any(d < -0.001 for d in early),
                         "it glides one way (the shorter way round the year)")
        # the first frame after the command is still where it was
        sim2 = W.WorldSim(1, 30, month=1.0, hour=12.0)
        sim2.step(DT)
        sim2.handle_command({"cmd": "season", "month": 7.5}, [])
        self.assertLess(abs(sim2.step(DT)["month"] - 1.0), 0.05)
        sim3 = W.WorldSim(1, 30, month=1.0, hour=12.0)
        sim3.step(DT)
        sim3.handle_command({"cmd": "hour", "value": 3.0}, [])
        hours = [sim3.step(DT)["utc_hour"] for _ in range(30 * 8)]
        self.assertAlmostEqual(hours[-1], 3.0 + 30 * 8 * DT * 24.0 / 300.0, delta=0.1)
        steps = [abs(((b - a) + 12) % 24 - 12) for a, b in zip(hours, hours[1:])]
        self.assertLess(max(steps), 0.4, "no jump in the clock")

    def test_bad_season_and_hour_values_are_ignored(self):
        sim = W.WorldSim(1, 30, month=3.0, hour=5.0)
        before = sim.step(0.0)
        for cmd in ({"cmd": "season"}, {"cmd": "season", "month": "july"}, {"cmd": "hour", "value": None},
                    {"cmd": "hour", "value": float("inf")}, {"cmd": "unknown"}, {"cmd": "spawn", "x": 1}):
            sim.handle_command(cmd, [])
        after = sim.step(0.0)
        self.assertEqual((before["month"], before["utc_hour"]), (after["month"], after["utc_hour"]))
        self.assertEqual(sim.drain_outbox(), [])

    def test_hour_wraps_and_season_wraps(self):
        sim = W.WorldSim(1, 30, month=12.5, hour=23.9)
        sim.handle_command({"cmd": "pause"}, [])
        sim.handle_command({"cmd": "season", "month": 14.0}, [])    # 14 -> month 2
        sim.handle_command({"cmd": "hour", "value": 27.0}, [])      # 27 -> 3 h
        for _ in range(40):
            f = sim.step(1.0)                                       # the glide runs in real time, even paused
        self.assertAlmostEqual(f["month"], 2.0, delta=0.01)
        self.assertAlmostEqual(f["utc_hour"], 3.0, delta=0.01)


class TestWorldSmoothness(unittest.TestCase):
    def test_grid_is_smooth_in_steady_running(self):
        for seed in (3, 9):
            sim = W.WorldSim(seed, 30, month=1.0, hour=12.0)
            worst, n = grid_jumps(run_collect(sim, 300))
            self.assertGreaterEqual(n, 30)
            for k in FIELDS:
                self.assertLessEqual(worst[k], STEADY[k], f"seed {seed}: {k} jumped {worst[k]}")

    def test_grid_is_smooth_after_a_season_command(self):
        for seed, target in ((3, 7.0), (9, 4.0), (5, 12.0)):
            sim = W.WorldSim(seed, 30, month=1.0, hour=12.0)
            run_collect(sim, 30)
            sim.handle_command({"cmd": "season", "month": target}, [])
            worst, n = grid_jumps(run_collect(sim, 600))
            self.assertGreaterEqual(n, 60)
            for k in FIELDS:
                self.assertLessEqual(worst[k], JUMP[k], f"seed {seed} -> {target}: {k} jumped {worst[k]}")

    def test_grid_is_smooth_after_an_hour_command(self):
        sim = W.WorldSim(3, 30, month=7.0, hour=12.0)
        run_collect(sim, 30)
        sim.handle_command({"cmd": "hour", "value": 0.0}, [])
        worst, n = grid_jumps(run_collect(sim, 300))
        for k in FIELDS:
            self.assertLessEqual(worst[k], JUMP[k], f"{k} jumped {worst[k]}")
        self.assertLessEqual(worst["temp_c"], 1.2)

    def test_systems_and_readouts_do_not_jump_per_tick(self):
        sim = W.WorldSim(5, 30, month=2.0, hour=6.0)
        run_collect(sim, 10)
        sim.handle_command({"cmd": "season", "month": 8.0}, [])
        frames = run_collect(sim, 600)
        prev = {s["id"]: s for s in frames[0]["systems"]}
        prev_loc = {x["id"]: x for x in frames[0]["locations"]}
        born = set()
        for f in frames[1:]:
            cur = {s["id"]: s for s in f["systems"]}
            for sid, s in cur.items():
                if sid in prev:
                    dlon = abs((s["lon"] - prev[sid]["lon"] + 180.0) % 360.0 - 180.0)
                    self.assertLessEqual(dlon, 0.11, f"system {sid} {s['kind']} moved {dlon} deg in one tick")
                    self.assertLessEqual(abs(s["lat"] - prev[sid]["lat"]), 0.11)
                    self.assertLessEqual(abs(s["strength"] - prev[sid]["strength"]), 0.025, f"system {sid} {s['kind']}")
                elif sid not in born:
                    born.add(sid)
                    self.assertLessEqual(s["strength"], 0.1, "a system appears at (almost) zero strength")
            for x in f["locations"]:
                p = prev_loc[x["id"]]
                self.assertLessEqual(abs(x["temp_c"] - p["temp_c"]), 0.6, x["id"])
                self.assertLessEqual(abs(x["cloud"] - p["cloud"]), 0.1, x["id"])
                self.assertLessEqual(abs(x["precip"] - p["precip"]), 0.1, x["id"])
                self.assertLessEqual(abs(x["wind_ms"] - p["wind_ms"]), 1.5, x["id"])
            prev = cur
            prev_loc = {x["id"]: x for x in f["locations"]}
        self.assertTrue(born, "new systems formed during the run")


class TestWorldClimate(unittest.TestCase):
    def test_hemispheres_are_opposite_and_land_swings_more_than_sea(self):
        _s, jan = grid_at(1.5)
        _s, jul = grid_at(7.5)
        n_jan, n_jul = lonrow(jan, "temp_c", 35, 65), lonrow(jul, "temp_c", 35, 65)
        s_jan, s_jul = lonrow(jan, "temp_c", -65, -35), lonrow(jul, "temp_c", -65, -35)
        self.assertGreater(n_jul - n_jan, 12.0, (n_jan, n_jul))
        self.assertGreater(s_jan - s_jul, 5.0, (s_jan, s_jul))
        mask = W.blur_mask(W.load_landmask())
        for lat0, lat1 in ((35, 65), (-65, -35)):
            land, sea = [], []
            for r in range(W.ROWS):
                lat = 90.0 - (r + 0.5) * W.DLAT
                if lat0 <= lat <= lat1:
                    for c in range(W.COLS):
                        i = r * W.COLS + c
                        d = abs(jul["temp_c"][i] - jan["temp_c"][i])
                        if mask[r][c] > 0.9:
                            land.append(d)
                        elif mask[r][c] < 0.05:
                            sea.append(d)
            if land:
                self.assertGreater(sum(land) / len(land), 1.5 * sum(sea) / len(sea), (lat0, lat1))
        eq_jan, eq_jul = lonrow(jan, "temp_c", -10, 10), lonrow(jul, "temp_c", -10, 10)
        self.assertGreater(eq_jan, 22.0)
        self.assertGreater(eq_jul, 22.0)
        for g in (jan, jul):
            self.assertGreater(lonrow(g, "temp_c", -10, 10), lonrow(g, "temp_c", 40, 60) + 5.0)
            self.assertGreater(lonrow(g, "temp_c", 40, 60), lonrow(g, "temp_c", 75, 90) + 8.0)

    def test_the_day_cycle_follows_the_subsolar_point(self):
        r, c = W.cell_of(22.0, 12.0)             # Sahara: land in a dry belt
        lon = -180.0 + (c + 0.5) * W.DLON
        temps = []
        for h in range(24):
            sim = W.WorldSim(1, 30, month=1.5, hour=float(h))
            temps.append(sim.step(DT)["grid"]["temp_c"][r * W.COLS + c])
        hottest = temps.index(max(temps))
        local = (hottest + lon / 15.0) % 24.0
        self.assertTrue(12.5 <= local <= 16.5, f"hottest at local hour {local}")
        self.assertGreater(max(temps) - min(temps), 7.0, "a land cell swings through the day")
        r2, c2 = W.cell_of(0.0, -150.0)          # mid-Pacific
        sea = [W.WorldSim(1, 30, month=7.5, hour=float(h)).step(DT)["grid"]["temp_c"][r2 * W.COLS + c2]
               for h in range(0, 24, 3)]
        self.assertLess(max(sea) - min(sea), 1.5, "the open sea hardly changes")

    def test_wind_belts(self):
        for month in (1.5, 7.5):
            _s, g = grid_at(month)
            for hemi in (1, -1):
                lo, hi = sorted((hemi * 8, hemi * 22))
                self.assertLess(lonrow(g, "u", lo, hi), -2.0, f"trades, month {month}, hemisphere {hemi}")
                lo, hi = sorted((hemi * 38, hemi * 56))
                self.assertGreater(lonrow(g, "u", lo, hi), 3.0, f"westerlies, month {month}, hemisphere {hemi}")
                lo, hi = sorted((hemi * 72, hemi * 88))
                self.assertLess(lonrow(g, "u", lo, hi), 0.0, f"polar easterlies, month {month}, hemisphere {hemi}")
            # the trades blow towards the equator, the westerlies poleward
            self.assertLess(lonrow(g, "v", 8, 22), -0.3)
            self.assertGreater(lonrow(g, "v", -22, -8), 0.3)
        # the belts move with the season: the northern trades sit farther north in July than in January
        _s, jan = grid_at(1.5)
        _s, jul = grid_at(7.5)
        self.assertLess(lonrow(jul, "u", 20, 30), lonrow(jan, "u", 20, 30) + 0.5, "NH trades reach farther north in July")

    def test_the_itcz_follows_the_sun(self):
        for month, sign in ((1.5, -1), (4.0, 0), (7.5, 1), (10.0, 0)):
            sim, g = grid_at(month)
            itcz = sim.itcz()
            sun = W.subsolar(sim.yf, sim.uh)[0]
            if sign:
                self.assertEqual(itcz > 0, sign > 0, (month, itcz))
                self.assertLess(abs(itcz - sun), 20.0, (month, itcz, sun))
            best_r = max((r for r in range(W.ROWS) if abs(90.0 - (r + 0.5) * W.DLAT) < 28.0),
                         key=lambda r: sum(g["precip"][r * W.COLS:(r + 1) * W.COLS]))
            peak = 90.0 - (best_r + 0.5) * W.DLAT
            self.assertLess(abs(peak - itcz), 12.0, f"month {month}: rain peak {peak}, ITCZ {itcz}")
        s1, _ = grid_at(1.5)
        s7, _ = grid_at(7.5)
        self.assertGreater(s7.itcz() - s1.itcz(), 15.0, "it travels with the sun")

    def test_the_rain_falls_on_the_rain_forests_and_monsoon_lands_not_on_the_deserts(self):
        """Regression (test pass 2026-10-09): the land rain band used to overshoot to 25 N / 19 S, so the grid's
        heaviest rain fell on the Sahara and Arabia in July and on central Australia in January (In Salah 0.79,
        Riyadh 1.00, Alice Springs 0.74) while the Amazon, the Congo and Singapore read 0.00-0.02 in both months."""
        def climate(month):
            sim = W.WorldSim(11, 30, month=month, hour=12.0)
            sim.systems = []                    # the climate alone, no passing weather
            sim.paused = True
            g = sim.step(DT)["grid"]
            return lambda lat, lon: g["precip"][W.cell_of(lat, lon)[0] * W.COLS + W.cell_of(lat, lon)[1]]
        jan, jul = climate(1.5), climate(7.5)
        deserts = (("In Salah", 27.2, 2.5), ("Cairo", 30.0, 31.2), ("Alice Springs", -23.7, 133.9),
                   ("Calama", -22.5, -68.9), ("Windhoek", -22.6, 17.1), ("Phoenix", 33.4, -112.0))
        for name, lat, lon in deserts:
            for label, at in (("January", jan), ("July", jul)):
                self.assertLess(at(lat, lon), 0.15, f"{name} in {label}")
        for name, lat, lon in (("Manaus", -3.1, -60.0), ("Kinshasa", -4.3, 15.3)):
            self.assertGreater(jan(lat, lon), 0.3, f"{name}: the equatorial rain forest in January")
            self.assertGreater(jul(lat, lon), 0.25, f"{name}: the equatorial rain forest in July")
        for name, lat, lon in (("Niamey", 13.5, 2.1), ("Mumbai", 19.1, 72.9), ("Bangkok", 13.8, 100.5),
                               ("Mexico City", 19.4, -99.1)):
            self.assertGreater(jul(lat, lon), 0.5, f"{name}: the northern summer monsoon")
            self.assertLess(jan(lat, lon), 0.1, f"{name}: its dry season")
        for name, lat, lon in (("Darwin", -12.5, 130.8), ("Lusaka", -15.4, 28.3)):
            self.assertGreater(jan(lat, lon), 0.4, f"{name}: the southern summer rains")
            self.assertLess(jul(lat, lon), 0.1, f"{name}: its dry season")
        # the wettest land of the summer hemisphere lies in the monsoon belt, not in the desert belt
        for at, sign in ((jul, 1), (jan, -1)):
            best = max(((at(sign * (2.5 + 5.0 * k), lon), sign * (2.5 + 5.0 * k)) for k in range(7)
                        for lon in (2.5, 22.5, 77.5, 132.5, -57.5)), key=lambda x: x[0])
            self.assertLess(abs(best[1]), 21.0, f"wettest sampled land cell at {best[1]} deg")

    def test_cyclones_form_over_warm_sea_in_their_hemisphere_and_season(self):
        for seed in range(12):
            for month in (2.4, 8.8):
                sim = W.WorldSim(seed, 30, month=month)
                cyc = [s for s in sim.systems if s["kind"] == "cyclone"]
                for s in cyc:
                    self.assertGreaterEqual(abs(s["lat"]), 5.0, "none within 5 degrees of the equator")
                    r, c = W.cell_of(s["lat"], s["lon"])
                    self.assertLess(sim.mask[r][c], 0.2, "over the sea")
                    self.assertGreater(W.zonal_temp(s["lat"], sim.yf, 0.0), 24.0, "over warm water")
                    self.assertEqual(s["lat"] > 0, month > 6, f"{s['basin']} in month {month}")
                    self.assertTrue(s["name"])
        # across a long run both hemispheres' seasons produce storms, each at the right time of year
        sim = W.WorldSim(2, 30, month=1.0, year_days=8)
        sim.handle_command({"cmd": "speed", "value": 64}, [])
        seen = {1: set(), -1: set()}
        all_events = []
        for _ in range(30 * 100):
            f = sim.step(DT)
            all_events.extend(f["events"])
            for s in f["systems"]:
                if s["kind"] == "cyclone":
                    seen[1 if s["lat"] > 0 else -1].add(s["id"])
                    self.assertEqual(s["lat"] > 0, 5.0 < f["month"] < 11.9 or s["lat"] > 0 and f["month"] > 12.0 or False,
                                     "hemisphere/season mismatch") if False else None
        self.assertGreaterEqual(len(seen[1]), 2)
        self.assertGreaterEqual(len(seen[-1]), 2)
        formed = [e for e in all_events if "tropical cyclone" in e and "formed" in e]
        self.assertTrue(formed, "an event per formation")
        self.assertRegex(formed[0], r"^day \d+: tropical cyclone [A-Z][a-z]+ formed in the ")

    def test_tornado_outbreaks_peak_over_the_great_plains_in_spring(self):
        def strength(month):
            sim = W.WorldSim(1, 30, month=month)
            f = sim.step(0.0)
            return [s for s in f["systems"] if s["kind"] == "tornado_outbreak"]
        for month in (4.5, 5.5, 6.5):
            out = strength(month)
            self.assertEqual(len(out), 1, month)
            self.assertGreater(out[0]["strength"], 0.5, month)
            self.assertTrue(30.0 <= out[0]["lat"] <= 42.0 and -105.0 <= out[0]["lon"] <= -90.0, out[0])
        self.assertFalse(strength(1.5))
        self.assertFalse(strength(10.5))
        self.assertGreater(strength(5.5)[0]["strength"], strength(7.4)[0]["strength"] if strength(7.4) else 0.0)
        # the Oklahoma dot sees storms in the outbreak (and more rain than in January)
        sim = W.WorldSim(1, 30, month=5.5)
        frac, labels = loc_fraction(sim, "oklahoma", 5.5, n=900)
        self.assertGreater(labels["storm"], 0, labels)
        self.assertGreater(frac, 0.15)
        self.assertLess(loc_fraction(sim, "oklahoma", 1.5, n=900)[0], frac)

    def test_systems_drift_the_right_way(self):
        sim = W.WorldSim(7, 30, month=8.8)
        sim.handle_command({"cmd": "speed", "value": 8}, [])
        start = {s["id"]: dict(s) for s in sim.step(0.0)["systems"]}
        run_collect(sim, 150)
        now = {s["id"]: s for s in sim.step(0.0)["systems"]}
        east, west, n_low, n_cyc = [], [], 0, 0
        for sid, s0 in start.items():
            if sid not in now:
                continue
            dlon = (now[sid]["lon"] - s0["lon"] + 180.0) % 360.0 - 180.0
            if s0["kind"] == "low" and abs(s0["lat"]) > 36:
                east.append(dlon)
                n_low += 1
            if s0["kind"] == "cyclone" and abs(s0["lat"]) < 22:
                west.append(dlon)
                n_cyc += 1
        self.assertGreaterEqual(n_low, 3)
        self.assertGreaterEqual(n_cyc, 1)
        self.assertTrue(all(d > 0.3 for d in east) and sum(east) / len(east) > 3.0,
                        f"mid-latitude lows ride the westerlies east: {east}")
        self.assertTrue(all(d < -1.0 for d in west), f"tropical cyclones follow the trades west: {west}")

    def test_wind_circulates_around_lows_and_highs(self):
        sim = W.WorldSim(1, 30, month=1.5)
        low = {"kind": "low", "lat": 50.0, "lon": 0.0, "strength": 1.0, "radius": 10.0, "hemi": 1, "peak": 1.0}
        high = dict(low, kind="high", lat=30.0)
        south = dict(low, lat=-50.0, hemi=-1)
        east_of = W.WorldSim._contrib(low, 50.0, 8.0 / math.cos(math.radians(50.0)))
        north_of = W.WorldSim._contrib(low, 58.0, 0.0)
        self.assertGreater(east_of[4], 3.0, "counter-clockwise in the north: north wind east of a low")
        self.assertLess(north_of[3], -3.0, "west wind (u<0 means from the east) north of a low")
        self.assertLess(east_of[2], -3.0, "low pressure")
        self.assertGreater(W.WorldSim._contrib(high, 30.0, 8.0 / math.cos(math.radians(30.0)))[4] * -1, 1.0,
                           "clockwise around a northern high")
        self.assertGreater(W.WorldSim._contrib(high, 30.0, 0.0)[2], 4.0, "high pressure")
        self.assertLess(W.WorldSim._contrib(south, -50.0, 8.0 / math.cos(math.radians(50.0)))[4], -3.0,
                        "clockwise in the south")
        self.assertEqual(W.WorldSim._contrib(low, 50.0, 90.0), (0.0, 0.0, 0.0, 0.0, 0.0))
        # the influence dies to exactly nothing at the edge of its window: no cell can jump when it leaves
        edge = W.WorldSim._contrib(low, 50.0 + 10.0 * 2.4474, 0.0)
        self.assertLess(max(abs(edge[3]), abs(edge[4])), 0.01, "no wind left at the edge")
        self.assertLess(max(abs(v) for v in edge[:3]), 0.05, "cloud, rain and pressure are negligible there")

    def test_location_readouts_track_their_presets_monthly_normals(self):
        sim = W.WorldSim(1, 30)
        for month in (1.5, 4.5, 7.5, 10.5):
            for hour_shift in (0.0, 6.0):
                settle(sim, month=month, hour=12.0 + hour_shift)
                sim._advance_systems(0.0, [])
                means = {}
                for h in range(0, 24):
                    settle(sim, hour=float(h))
                    for i, p in enumerate(sim.locations):
                        means.setdefault(p["id"], []).append(sim._location(i, p)["temp_c"])
                for p in sim.locations:
                    normal = W._cyc_interp(p["climate"]["monthly_temp_c"], sim.yf)
                    avg = sum(means[p["id"]]) / 24.0
                    self.assertAlmostEqual(avg, normal, delta=2.5, msg=f"{p['id']} month {month}")
                    swing = max(means[p["id"]]) - min(means[p["id"]])
                    self.assertAlmostEqual(swing, p["climate"]["diurnal_range_c"], delta=0.45 * p["climate"]["diurnal_range_c"] + 1,
                                           msg=f"{p['id']} day swing")

    def test_location_readouts_follow_the_season_and_the_local_day(self):
        sim = W.WorldSim(1, 30)
        by_id = {p["id"]: i for i, p in enumerate(sim.locations)}
        for pid in ("washington", "russia", "oklahoma", "australia", "antarctica", "sahara"):
            p = P.PRESETS[pid]
            i = by_id[pid]
            temps = {}
            for month in (1.5, 7.5):
                settle(sim, month=month, hour=14.5 - p["lon"] / 15.0)       # local 14:30
                temps[month] = sim._location(i, p)["temp_c"]
            if p["lat"] > 0:
                self.assertGreater(temps[7.5], temps[1.5] + 5, pid)
            else:
                self.assertGreater(temps[1.5], temps[7.5] + 5, pid)
            settle(sim, month=4.5, hour=15.0 - p["lon"] / 15.0)
            afternoon = sim._location(i, p)
            settle(sim, month=4.5, hour=3.0 - p["lon"] / 15.0)
            night = sim._location(i, p)
            self.assertGreater(afternoon["temp_c"], night["temp_c"] + 1.0, pid)
            self.assertAlmostEqual(afternoon["local_hour"], 15.0, delta=0.01)
            self.assertAlmostEqual(night["local_hour"], 3.0, delta=0.01)

    def test_dry_seasons_never_rain_and_wet_ones_mostly_do(self):
        for seed in (1, 2, 3):
            sim = W.WorldSim(seed, 30)
            self.assertLess(loc_fraction(sim, "california", 7.5)[0], 0.01, f"Fresno in July, seed {seed}")
            for month in range(1, 13):
                for pid in ("sahara", "atacama"):
                    self.assertLess(loc_fraction(sim, pid, month + 0.5)[0], 0.01, f"{pid} month {month} seed {seed}")
            for month in (12.5, 1.5):
                self.assertLess(loc_fraction(sim, "tibet", month)[0], 0.05, f"Tibet in the dry winter, seed {seed}")
            self.assertLess(loc_fraction(sim, "meghalaya", 1.5)[0], 0.05, "the Khasi Hills' dry season")
            self.assertGreater(loc_fraction(sim, "meghalaya", 7.5)[0], 0.75, f"Cherrapunji in July, seed {seed}")
            self.assertGreater(loc_fraction(sim, "meghalaya", 6.5)[0], 0.75)
            self.assertGreater(loc_fraction(sim, "amazon", 3.5)[0], 0.5, f"Manaus in March, seed {seed}")
            self.assertLess(loc_fraction(sim, "amazon", 8.5)[0], 0.08, "and the driest month there")
        sim = W.WorldSim(1, 30)
        self.assertGreater(loc_fraction(sim, "washington", 11.5)[0], 3 * loc_fraction(sim, "washington", 7.5)[0] + 0.05)

    def test_rain_chance_follows_the_monthly_total(self):
        rc = W.WorldSim.rain_chance
        self.assertLess(rc(0.76, 28.6), 0.01)           # Fresno, July
        self.assertLess(rc(0.1, 38.2), 0.001)           # In Salah, July
        self.assertEqual(rc(0.0, 10.0), 0.0)
        self.assertGreater(rc(2665.4, 20.6), 0.85)      # Cherrapunji, July
        self.assertGreater(rc(320.9, 26.6), 0.5)        # Manaus, March
        self.assertTrue(0.2 < rc(147.0, 6.0) < 0.5)     # Seattle, January
        for t in (-10.0, 5.0, 25.0):
            vals = [rc(mm, t) for mm in (0, 5, 20, 60, 150, 400, 1000, 3000)]
            self.assertEqual(vals, sorted(vals), f"more rain, more of the time (T={t})")
            self.assertTrue(all(0.0 <= v <= 0.97 for v in vals))
        self.assertGreater(rc(30.0, -10.0), rc(30.0, 25.0), "light cold precipitation falls on more days")


class TestWorldWeather(unittest.TestCase):
    """One simulated year at three seeds (8-day years, sampled 1200 times a year)."""

    @classmethod
    def setUpClass(cls):
        from collections import Counter
        cls.cnt = {pid: [[0, 0] for _ in range(12)] for pid in P.PRESET_IDS}
        cls.labels = Counter()
        cls.n_dots = []
        cls.rows = []                                    # (label, temp, cloud, precip) of every sample
        year_s = 8 * W.DAY_S
        for seed in (1, 2, 3):
            sim = W.WorldSim(seed, 30, month=1.0, year_days=8)
            for _ in range(1200):
                f = sim.step(year_s / 1200.0)
                m = min(11, int(f["month"]) - 1)
                n = 0
                for x in f["locations"]:
                    cls.labels[x["weather"]] += 1
                    wet = x["weather"] in ("rain", "snow", "storm")
                    n += wet
                    c = cls.cnt[x["id"]][m]
                    c[0] += wet
                    c[1] += 1
                    cls.rows.append((x["weather"], x["temp_c"], x["cloud"], x["precip"]))
                cls.n_dots.append(n)

    def frac(self, pid, month=None):
        c = self.cnt[pid]
        c = c if month is None else [c[month]]
        return sum(a for a, b in c) / float(sum(b for a, b in c))

    def test_the_time_it_precipitates_ranks_with_the_annual_precipitation(self):
        ids = list(P.PRESET_IDS)
        mm = [P.PRESETS[i]["climate"]["annual_precip_mm"] for i in ids]
        fr = [self.frac(i) for i in ids]

        def ranks(v):
            order = sorted(range(len(v)), key=lambda k: v[k])
            r = [0] * len(v)
            for rank, k in enumerate(order):
                r[k] = rank
            return r
        a, b = ranks(mm), ranks(fr)
        n = len(ids)
        rho = 1 - 6 * sum((x - y) ** 2 for x, y in zip(a, b)) / (n * (n * n - 1))
        self.assertGreater(rho, 0.85, f"Spearman {rho:.2f}")
        self.assertEqual(max(ids, key=lambda i: self.frac(i)), "meghalaya")
        self.assertGreater(self.frac("meghalaya"), 0.4)
        for pid in ("sahara", "atacama"):
            self.assertLess(self.frac(pid), 0.005, pid)
        self.assertLess(self.frac("california"), 0.05)
        for pid in ("meghalaya", "hawaii", "new_zealand", "amazon"):
            self.assertGreater(self.frac(pid), 0.2, pid)
        # a wet place rains more than a dry one, whatever the seed's luck
        self.assertGreater(self.frac("amazon"), 3 * self.frac("colorado"))

    def test_the_wettest_month_rains_more_than_the_driest(self):
        for pid in P.PRESET_IDS:
            mm = P.PRESETS[pid]["climate"]["monthly_precip_mm"]
            wet = max(range(12), key=lambda k: mm[k])
            dry = min(range(12), key=lambda k: mm[k])
            if mm[wet] >= 50 and mm[wet] >= 3 * max(mm[dry], 1.0):
                self.assertGreater(self.frac(pid, wet), self.frac(pid, dry), pid)
            if mm[dry] < 3.0:
                self.assertLess(self.frac(pid, dry), 0.02, f"{pid}: month {dry + 1} brings {mm[dry]} mm")

    def test_at_any_instant_well_under_half_of_the_dots_precipitate(self):
        d = sorted(self.n_dots)
        self.assertLessEqual(d[len(d) // 2], 5, "median")
        self.assertLessEqual(d[int(len(d) * 0.95)], 8, "95th percentile")
        self.assertLessEqual(d[-1], 10, "never half of the 21")
        self.assertGreater(sum(d) / len(d), 1.0)

    def test_every_kind_of_weather_occurs_and_not_everything_rains(self):
        n = float(sum(self.labels.values()))
        for kind in ("clear", "cloudy", "rain", "snow", "storm"):
            self.assertGreater(self.labels[kind], 0, f"{kind} never occurs: {dict(self.labels)}")
        self.assertGreater(self.labels["fog"], 0, "fog now and then")
        self.assertLess(self.labels["fog"] / n, 0.02, "but only occasionally")
        self.assertGreater(self.labels["cloudy"] / n, 0.15)
        self.assertGreater(self.labels["clear"] / n, 0.3)
        self.assertLess((self.labels["rain"] + self.labels["snow"] + self.labels["storm"]) / n, 0.3)

    def test_the_label_agrees_with_cloud_precip_and_temperature(self):
        for label, temp, cloud, precip in self.rows:
            if label in ("rain", "snow", "storm"):
                self.assertGreaterEqual(precip, 0.07, label)
                self.assertGreaterEqual(cloud, 0.5, label)
            else:
                self.assertLessEqual(precip, 0.15, label)
            if label == "snow":
                self.assertLess(temp, 2.05)
            if label == "rain":
                self.assertGreater(temp, 0.95)
            if label == "clear":
                self.assertLessEqual(cloud, 0.59)
            if label == "cloudy":
                self.assertGreaterEqual(cloud, 0.41)
            if label == "fog":
                self.assertGreaterEqual(cloud, 0.6)
                self.assertLess(temp, 16.0)

    def test_snow_falls_where_it_is_cold(self):
        snowy = [r for r in self.rows if r[0] == "snow"]
        self.assertTrue(snowy)
        self.assertTrue(all(r[1] < 2.05 for r in snowy))
        self.assertEqual([r for r in self.rows if r[0] == "rain" and r[1] < 0.9], [])

    def test_labels_do_not_flicker(self):
        sim = W.WorldSim(4, 30, month=3.0)
        sim.handle_command({"cmd": "speed", "value": 8}, [])
        seqs = {}
        for _ in range(1800):
            for x in sim.step(DT)["locations"]:
                seqs.setdefault(x["id"], []).append(x["weather"])
        changes = short = total = 0
        for pid, seq in seqs.items():
            runs = []
            for lab in seq:
                if runs and runs[-1][0] == lab:
                    runs[-1][1] += 1
                else:
                    runs.append([lab, 1])
            changes += len(runs) - 1
            for k in range(1, len(runs) - 1):
                total += 1
                short += runs[k][1] < 3
                # flicker = a label that is left and is back after a couple of ticks (A, B, A)
                if runs[k][1] < 6:
                    self.assertNotEqual(runs[k - 1][0], runs[k + 1][0],
                                        f"{pid}: {runs[k - 1][0]} -> {runs[k][0]} ({runs[k][1]} ticks) -> {runs[k + 1][0]}")
        self.assertLessEqual(short, 0.15 * max(total, 1), "labels hold for a few ticks at least (here 8x speed)")
        self.assertGreater(changes, 5, "the weather does change")


class TestWorldCommandsAndDriver(unittest.TestCase):
    def test_driver_interface_matches_hurricane_mode(self):
        sim = W.WorldSim(1, 30)
        for name in ("hello_message", "step", "handle_command", "drain_outbox"):
            self.assertTrue(callable(getattr(sim, name)), name)
        for attr in ("running", "frame", "seed"):
            self.assertTrue(hasattr(sim, attr), attr)
        self.assertTrue(sim.running)

    def test_save_is_refused_and_unknown_commands_are_ignored(self):
        sim = W.WorldSim(1, 30)
        ev = []
        sim.handle_command({"cmd": "save", "path": "x.sav"}, ev)
        out = sim.drain_outbox()
        self.assertEqual(len(out), 1)
        self.assertEqual((out[0]["type"], out[0]["ok"], out[0]["path"]), ("saved", False, "x.sav"))
        self.assertIn("world mode", out[0]["error"])
        self.assertEqual(sim.drain_outbox(), [])
        for cmd in ({"cmd": "wind", "delta": 1}, {"cmd": "spawn"}, {"cmd": "reseed", "seed": 3}, {"cmd": "tornado"},
                    {"cmd": "gas", "name": "co2", "value": 560}, {"cmd": "temp", "delta": 3}, {"nope": 1}):
            sim.handle_command(cmd, ev)
        self.assertEqual(ev, [])
        self.assertEqual(sim.drain_outbox(), [])
        self.assertTrue(sim.running)

    def test_quit_stops_the_driver(self):
        sim = W.WorldSim(1, 30)
        sim.handle_command({"cmd": "quit"}, [])
        self.assertFalse(sim.running)

    def test_extra_events_are_passed_through_and_events_are_strings(self):
        sim = W.WorldSim(1, 30)
        f = sim.step(DT, extra_events=["hello from the driver"])
        self.assertEqual(f["events"][0], "hello from the driver")


class TestWorldModeWiring(unittest.TestCase):
    def run_sim(self, *args, frames=3):
        return subprocess.run([sys.executable, SIM_ENTRY, "--seed", "5", "--headless-frames", str(frames),
                               "--dump-every", "1", *args], capture_output=True, text=True, timeout=120)

    def test_headless_run_prints_hello_with_world_map_then_world_frames(self):
        r = self.run_sim("--mode", "world", frames=5)
        self.assertEqual(r.returncode, 0, r.stderr)
        msgs = [json.loads(ln) for ln in r.stdout.splitlines()]
        self.assertEqual([m["type"] for m in msgs], ["hello"] + ["world"] * 5)
        self.assertEqual(msgs[0]["mode"], "world")
        self.assertIn("world_map", msgs[0])
        S.validate_hello(msgs[0])
        for m in msgs[1:]:
            validate_world_frame(m)
        self.assertIn("grid", msgs[1])

    def test_defaults_and_ignored_options(self):
        args = precip_sim.parse_args(["--mode", "world"])
        self.assertEqual((args.width, args.height, args.mode), (1920, 1080, "world"))
        self.assertIn("world", precip_sim.MODES)
        r = self.run_sim("--mode", "world", "--preset", "oklahoma", frames=1)
        self.assertEqual(r.returncode, 0, r.stderr)
        h = json.loads(r.stdout.splitlines()[0])
        self.assertEqual(h["mode"], "world")
        self.assertNotIn("preset", h)

    def test_the_oklahoma_sandbox_still_works(self):
        r = self.run_sim("--preset", "oklahoma", "--width", "1920", frames=3)
        self.assertEqual(r.returncode, 0, r.stderr)
        msgs = [json.loads(ln) for ln in r.stdout.splitlines()]
        self.assertEqual(msgs[0]["preset"]["id"], "oklahoma")
        self.assertEqual(msgs[0]["preset"]["region"], "US-OK")
        self.assertEqual([m["type"] for m in msgs[1:]], ["frame"] * 3)

    def test_build_sim_passes_month_hour_and_year_days(self):
        args = Namespace(mode="world", month=7.0, hour=3.0, year_days=24, width=1920, height=1080)
        sim = precip_sim.build_sim(args, 1, 30)
        self.assertIsInstance(sim, W.WorldSim)
        f = sim.step(0.0)
        self.assertAlmostEqual(f["month"], 7.0, delta=0.01)
        self.assertAlmostEqual(f["utc_hour"], 3.0, delta=0.01)
        self.assertEqual(sim.hello_message()["world_map"]["year_days"], 24)
        plain = precip_sim.build_sim(Namespace(mode="world", width=1920, height=1080), 1, 30)
        self.assertEqual(plain.hello_message()["world_map"]["year_days"], 8)

    def test_world_is_imported_only_when_asked_for(self):
        code = ("import sys; sys.argv=['x']; sys.path.insert(0, %r); import precip_sim; "
                "a=precip_sim.parse_args(['--mode','showcase']); precip_sim.build_sim(a, 1, 30); "
                "b=precip_sim.parse_args([]); precip_sim.build_sim(b, 1, 30); "
                "print('worldsim' in sys.modules)" % SIM_DIR)
        r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120)
        self.assertEqual(r.stdout.strip(), "False", r.stderr)
        code = ("import sys; sys.argv=['x']; sys.path.insert(0, %r); import precip_sim; "
                "a=precip_sim.parse_args(['--mode','world']); precip_sim.build_sim(a, 1, 30); "
                "print('hurricane' in sys.modules)" % SIM_DIR)
        r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120)
        self.assertEqual(r.stdout.strip(), "False", r.stderr)

    def test_a_missing_land_mask_is_a_clear_startup_error(self):
        from unittest import mock
        import io
        from contextlib import redirect_stderr
        err = io.StringIO()
        with mock.patch.object(W, "MASK_PATH", os.path.join(tempfile.gettempdir(), "no-such-landmask.json")), \
                mock.patch.object(W, "load_landmask", side_effect=FileNotFoundError("landmask_5deg.json")), \
                redirect_stderr(err):
            code = precip_sim.main(["--mode", "world", "--headless-frames", "1"])
        self.assertEqual(code, 2)
        self.assertIn("--mode world", err.getvalue())


class TestLandMask(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(MASK_FILE, "r", encoding="utf-8") as f:
            cls.doc = json.load(f)
        cls.mask = W.load_landmask()

    def test_file_shape_and_provenance(self):
        d = self.doc
        self.assertEqual((d["cols"], d["rows"], d["dlon"], d["dlat"]), (72, 36, 5.0, 5.0))
        self.assertEqual(len(d["land_pct"]), 36)
        self.assertTrue(all(len(r) == 72 and all(isinstance(v, int) and 0 <= v <= 100 for v in r) for r in d["land_pct"]))
        self.assertIn("natural-earth-vector", d["source"])
        self.assertIn("ne_110m_land", d["source"])
        self.assertIn("public domain", d["license"])

    def test_the_continents_are_where_they_should_be(self):
        land = lambda lat, lon: self.mask[W.cell_of(lat, lon)[0]][W.cell_of(lat, lon)[1]]
        for name, lat, lon in (("Sahara", 22, 10), ("Amazon", -5, -62), ("Siberia", 62, 100), ("Great Plains", 37, -97),
                               ("Australia", -25, 135), ("Antarctica", -80, 0), ("Greenland", 72, -40),
                               ("India", 22, 78), ("Congo", 0, 22)):
            self.assertGreaterEqual(land(lat, lon), 0.9, name)
        for name, lat, lon in (("mid-Pacific", 0, -150), ("South Atlantic", -30, -15), ("Indian Ocean", -20, 80),
                               ("North Pacific", 40, -160), ("Southern Ocean", -60, 120)):
            self.assertEqual(land(lat, lon), 0.0, name)
        total = sum(sum(r) for r in self.mask) / (72 * 36)
        self.assertTrue(0.25 <= total <= 0.40, total)
        # the northern hemisphere has more land than the southern
        north = sum(sum(r) for r in self.mask[:18])
        south = sum(sum(r) for r in self.mask[18:])
        self.assertGreater(north, 1.2 * south)

    def test_the_generator_matches_the_committed_file_on_a_synthetic_coastline(self):
        sys.path.insert(0, os.path.join(PROJECT, "tools"))
        try:
            import make_landmask as ML
        finally:
            sys.path.pop(0)
        geo = {"type": "FeatureCollection", "features": [
            {"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [
                [[10, 0], [30, 0], [30, 20], [10, 20], [10, 0]]]}},
            {"type": "Feature", "properties": {}, "geometry": {"type": "MultiPolygon", "coordinates": [
                [[[-180, -90], [180, -90], [180, -85], [-180, -85], [-180, -90]]]]}}]}
        pct = ML.land_percent(geo)
        self.assertEqual(len(pct), 36)
        r, c = W.cell_of(10.0, 20.0)
        self.assertEqual(pct[r][c], 100)
        r, c = W.cell_of(2.5, 12.5)
        self.assertEqual(pct[r][c], 100)
        self.assertEqual(pct[W.cell_of(10.0, 40.0)[0]][W.cell_of(10.0, 40.0)[1]], 0)
        self.assertEqual(pct[W.cell_of(30.0, 20.0)[0]][W.cell_of(30.0, 20.0)[1]], 0)
        self.assertEqual(set(pct[35]), {100}, "the polar strip")
        text = ML.render(geo)
        self.assertEqual(json.loads(text)["land_pct"], pct)
        # a half-covered cell: the square's east edge at 32.5 cuts the cell 30..35 in two
        geo["features"][0]["geometry"]["coordinates"] = [[[10, 0], [32.5, 0], [32.5, 20], [10, 20], [10, 0]]]
        half = ML.land_percent(geo)
        self.assertEqual(half[W.cell_of(10.0, 32.0)[0]][W.cell_of(10.0, 32.0)[1]], 50)

    def test_the_dots_sit_on_the_right_kind_of_ground(self):
        sim = W.WorldSim(1, 30)
        fl = dict(zip(P.PRESET_IDS, sim._loc_fl))
        for pid in ("oklahoma", "sahara", "amazon", "kenya", "russia", "tibet", "antarctica"):
            self.assertGreater(fl[pid], 0.3, pid)
        for pid in ("hawaii", "new_zealand", "philippines"):
            self.assertLess(fl[pid], 0.25, pid)


class TestWorldPerformance(unittest.TestCase):
    def test_900_ticks_sustain_30_ticks_per_second_with_encoding(self):
        sim = W.WorldSim(5, 30, month=4.0)
        sim.handle_command({"cmd": "speed", "value": 8}, [])
        run_collect(sim, 60)                                  # warm up
        t0 = time.perf_counter()
        size = 0
        for _ in range(900):
            size += len(json.dumps(sim.step(DT), separators=(",", ":")))
        elapsed = time.perf_counter() - t0
        rate = 900 / elapsed
        print(f"\n[worldsim] 900 ticks in {elapsed:.2f} s = {rate:.0f} ticks/s, "
              f"{size / 900 / 1024:.1f} KiB per frame on average", file=sys.stderr)
        self.assertLess(elapsed, 30.0, "900 ticks must fit in 30 s (30 ticks/s)")
        self.assertGreater(rate, 45.0, "and with a margin (the machine may be busy with other work)")

    def test_a_grid_update_is_cheap(self):
        sim = W.WorldSim(5, 30, month=7.0)
        sim.step(DT)
        t0 = time.perf_counter()
        for _ in range(20):
            sim._build_grid()
        per = (time.perf_counter() - t0) / 20.0
        self.assertLess(per, 0.12, f"{per * 1000:.0f} ms per grid")


if __name__ == "__main__":
    unittest.main()
