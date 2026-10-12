"""Hurricane mode (protocol v2.2): hello, frame schema, stage figures against the real scales,
monotone intensification / decay, smoothness (also across `stage` jumps), the shape of the
vertical cross-section, auto looping, commands, determinism and speed.

Run:  python -m unittest sim.tests.test_hurricane
  or  python -m unittest discover -s sim/tests -p "test_hurricane.py"
Set PRECIP_SKIP_PERF=1 to skip the timing test.  The per-stage table is printed by
TestStageTable (use -v or look at stdout)."""
import json
import math
import os
import re
import time
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim.tests.test_hurricane` from the project root
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import hurricane as H
from hurricane import HurricaneSim

DT = 1.0 / 30
FPS = 30
STAGES = ["disturbance", "depression", "storm", "cat1", "cat2", "cat3", "cat4", "cat5",
          "landfall", "dissipation"]
FOCI = {"sst", "coriolis", "convection", "inflow", "eye", "eyewall", "rainbands", "outflow",
        "warm_core", "surge", "decay", "none"}
VIEWS = {"outside", "inside", "both"}

# Saffir-Simpson Hurricane Wind Scale (NHC), 1-minute sustained knots
SSHWS_KT = {1: (64, 82), 2: (83, 95), 3: (96, 112), 4: (113, 136), 5: (137, None)}
# original SSHS central-pressure (hPa) and storm-surge (m, from 4-5, 6-8, 9-12, 13-18, >18 ft) ranges
ORIG_PRESSURE = {1: (980, 1100), 2: (965, 979), 3: (945, 964), 4: (920, 944), 5: (800, 920)}
ORIG_SURGE_M = {1: (1.2, 1.5), 2: (1.8, 2.4), 3: (2.7, 3.7), 4: (4.0, 5.5), 5: (5.5, 9.0)}


def sentences(text):
    return len(re.findall(r"[.!?](?:['\")]*)(?:\s|$)", text.strip()))


def settle(sim, stage, seconds=16.0):
    """Jump to `stage`, run `seconds`; return (last frame, last section the viewer would hold)."""
    sim.handle_command({"cmd": "stage", "id": stage}, [])
    last_section = None
    msg = None
    for _ in range(int(seconds * FPS)):
        msg = sim.step(DT)
        if "section" in msg:
            last_section = msg["section"]
    return msg, last_section


def grid(section, name):
    cols, rows = section["cols"], section["rows"]
    g = section[name]
    return [g[r * cols:(r + 1) * cols] for r in range(rows)]


def zc(section, row):
    return (row + 0.5) * section["dz_km"]


def rc(section, col):
    return (col + 0.5) * section["dr_km"]


class SettledCase(unittest.TestCase):
    """Settled frame + section for every stage, computed once."""

    @classmethod
    def setUpClass(cls):
        sim = HurricaneSim(11, FPS)
        cls.hello = sim.hello_message()
        cls.frames = {}
        cls.sections = {}
        for sid in STAGES:
            cls.frames[sid], cls.sections[sid] = settle(sim, sid)


# ---------------------------------------------------------------------------------------------
class TestHello(unittest.TestCase):
    def setUp(self):
        self.h = HurricaneSim(5, FPS).hello_message()

    def test_generic_keys_and_mode(self):
        h = self.h
        self.assertEqual(h["type"], "hello")
        self.assertEqual(h["version"], 2)
        self.assertEqual(h["mode"], "hurricane")
        self.assertEqual(h["seed"], 5)
        self.assertEqual(h["settlements"], [])
        self.assertEqual(h["objects"], [])
        w = h["world"]
        self.assertEqual((w["w"], w["h"], w["ground_y"], w["terrain_kind"]), (1920, 1080, 1000, "flat"))
        self.assertEqual(len(w["terrain"]["y"]), math.ceil(w["w"] / w["terrain"]["step"]) + 1)
        self.assertEqual(set(w["terrain"]["y"]), {1000})
        json.dumps(h, allow_nan=False)

    def test_domain_and_sources(self):
        hu = self.h["hurricane"]
        self.assertEqual(hu["domain"], {"radius_km": 600, "top_km": 18})
        self.assertGreaterEqual(len(hu["sources"]), 5)
        for url in hu["sources"]:
            self.assertTrue(url.startswith("https://"), url)
        joined = " ".join(hu["sources"])
        for host in ("nhc.noaa.gov", "aoml.noaa.gov", "noaa.gov/jetstream", "wikipedia.org"):
            self.assertIn(host, joined)

    def test_stages_in_order_with_real_figures(self):
        stages = self.h["hurricane"]["stages"]
        self.assertEqual([s["id"] for s in stages], STAGES)
        cats = [s["category"] for s in stages]
        self.assertEqual(cats[:8], [-2, -1, 0, 1, 2, 3, 4, 5])
        for s in stages:
            self.assertEqual(set(s), {"id", "name", "wind_kt", "pressure_hpa", "category", "summary"})
            lo, hi = s["wind_kt"]
            self.assertIsInstance(lo, int)
            self.assertTrue(hi is None or hi >= lo, s["id"])
            self.assertTrue(900 <= s["pressure_hpa"] <= 1015, s["id"])
            n = sentences(s["summary"])
            self.assertTrue(2 <= n <= 4, f'{s["id"]}: {n} sentences')
        by = {s["id"]: s for s in stages}
        # exactly the NHC scale for the hurricane categories
        for cat, (lo, hi) in SSHWS_KT.items():
            self.assertEqual(by[f"cat{cat}"]["wind_kt"], [lo, hi])
        self.assertEqual(by["storm"]["wind_kt"], [34, 63])       # NHC tropical storm
        self.assertEqual(by["depression"]["wind_kt"][1], 33)     # NHC tropical depression <= 33 kt
        self.assertEqual(by["disturbance"]["pressure_hpa"], 1010)
        # pressure falls from disturbance to cat5
        ps = [by[s]["pressure_hpa"] for s in STAGES[:8]]
        self.assertEqual(ps, sorted(ps, reverse=True))

    def test_lesson_covers_required_topics(self):
        lesson = self.h["hurricane"]["lesson"]
        ids = [c["id"] for c in lesson]
        self.assertEqual(len(ids), len(set(ids)))
        for c in lesson:
            self.assertEqual(set(c), {"id", "title", "text", "view", "focus", "stage"})
            self.assertIn(c["view"], VIEWS)
            self.assertIn(c["focus"], FOCI)
            self.assertIn(c["stage"], STAGES)
            n = sentences(c["text"])
            self.assertTrue(3 <= n <= 6, f'{c["id"]}: {n} sentences')
        foci = {c["focus"] for c in lesson}
        for need in ("sst", "coriolis", "convection", "inflow", "eye", "eyewall", "rainbands",
                     "outflow", "warm_core", "surge", "decay"):
            self.assertIn(need, foci, f"no chapter with focus {need}")
        self.assertEqual({c["view"] for c in lesson}, VIEWS)
        # outside chapters for the outside picture, inside chapters for the section
        for c in lesson:
            if c["id"].startswith("outside_"):
                self.assertEqual(c["view"], "outside")
            if c["id"].startswith("inside_"):
                self.assertEqual(c["view"], "inside")
        # the tour goes ingredients -> ... -> death, and its stages never go backwards past landfall
        stage_idx = [STAGES.index(c["stage"]) for c in lesson]
        self.assertEqual(ids[0], "ingredients")
        self.assertIn("coriolis", ids)
        self.assertIn("stages", ids)
        self.assertIn("dissipation", ids)
        self.assertTrue(stage_idx.index(STAGES.index("landfall")) > stage_idx.index(STAGES.index("cat3")))
        text = " ".join(c["text"] for c in lesson).lower()
        for phrase in ("heat engine", "coriolis", "eyewall", "rainband", "cirrus", "warm-core",
                       "storm surge", "26.5"):
            self.assertIn(phrase, text)

    def test_unit_conversions_in_the_text_agree(self):
        """Regression (2026-10-09): the first chapter said 5 degrees of latitude was "555 km or
        300 miles" (555 km is 345 miles; 300 is the figure in nautical miles). Every pair of the
        same quantity in two units, in every chapter and stage summary, must agree."""
        hu = self.h["hurricane"]
        texts = [(c["id"], c["text"]) for c in hu["lesson"]] + [(s["id"], s["summary"]) for s in hu["stages"]]
        num = r"(\d+(?:\.\d+)?)"
        found = 0

        def agree(where, a, b, factor, what, tol=0.04):
            nonlocal found
            found += 1
            a, b = float(a), float(b)
            self.assertLessEqual(abs(a * factor - b), max(tol * b, 0.6),
                                 f"{where}: {a:g} {what} is {a * factor:.1f}, the text says {b:g}")

        for where, t in texts:
            for m in re.finditer(num + r" km or " + num + r" miles", t):
                agree(where, m[1], m[2], 1 / 1.609344, "km in miles")
            for m in re.finditer(num + r" km/h \(" + num + r" mph", t):
                agree(where, m[1], m[2], 1 / 1.609344, "km/h in mph")
            for m in re.finditer(num + r" kt \(" + num + r" mph", t):
                agree(where, m[1], m[2], 1.15078, "kt in mph")
            for m in re.finditer(num + r" to " + num + r" kt \(" + num + r" to " + num + r" mph, "
                                 + num + r" to " + num + r" km/h", t):
                agree(where, m[1], m[3], 1.15078, "kt in mph")
                agree(where, m[2], m[4], 1.15078, "kt in mph")
                agree(where, m[1], m[5], 1.852, "kt in km/h")
                agree(where, m[2], m[6], 1.852, "kt in km/h")
            for m in re.finditer(num + r" to " + num + r" nautical miles \(about " + num + r" to " + num + r" km", t):
                agree(where, m[1], m[3], 1.852, "nautical miles in km")
                agree(where, m[2], m[4], 1.852, "nautical miles in km")
            for m in re.finditer(num + r" to " + num + r" ft \(" + num + r" to " + num + r" m\)", t):
                agree(where, m[1], m[3], 0.3048, "ft in m", 0.06)
                agree(where, m[2], m[4], 0.3048, "ft in m", 0.06)
        self.assertGreaterEqual(found, 30)          # the patterns really matched the text
        first = hu["lesson"][0]["text"]
        self.assertIn("555 km or 345 miles", first)

    def test_the_eye_chapter_does_not_say_sinking_air_presses_on_the_surface(self):
        """Regression (2026-10-09): the chapter explained the warm eye with "the sinking also
        presses a little on the surface". The eye has the LOWEST surface pressure of the storm;
        the warmth comes from compression of the sinking air."""
        text = {c["id"]: c["text"] for c in self.h["hurricane"]["lesson"]}["inside_eye"].lower()
        self.assertNotIn("presses", text)
        self.assertIn("compressed", text)
        self.assertIn("lowest surface pressure", text)

    def test_hello_does_not_mutate_state(self):
        sim = HurricaneSim(5, FPS)
        a = sim.hello_message()
        sim.step(DT)
        self.assertEqual(a["hurricane"], sim.hello_message()["hurricane"])


# ---------------------------------------------------------------------------------------------
class TestFrameSchema(unittest.TestCase):
    def check_frame(self, m):
        top = {"type", "frame", "t", "speed", "paused", "stage", "stage_index", "stage_frac", "auto",
               "state", "profile", "bands", "events"}
        self.assertTrue(top <= set(m), top - set(m))
        self.assertLessEqual(set(m) - top, {"section"})
        self.assertEqual(m["type"], "hurricane")
        self.assertIn(m["stage"], STAGES)
        self.assertEqual(STAGES[m["stage_index"]], m["stage"])
        self.assertTrue(0.0 <= m["stage_frac"] <= 1.0)
        self.assertIsInstance(m["paused"], bool)
        self.assertIsInstance(m["auto"], bool)
        st = m["state"]
        keys = {"vmax_ms", "vmax_kt", "pressure_hpa", "category", "rmw_km", "eye_km", "r34_km",
                "sst_c", "warm_core_c", "cloud_top_km", "rain_mmh", "surge_m", "organization",
                "eye_clarity", "over_land", "rotation", "hemisphere"}
        self.assertEqual(set(st), keys)
        for k in keys - {"over_land", "hemisphere"}:
            self.assertIsInstance(st[k], (int, float), k)
            self.assertTrue(math.isfinite(st[k]), k)
        self.assertIsInstance(st["vmax_kt"], int)
        self.assertIsInstance(st["category"], int)
        self.assertIsInstance(st["over_land"], bool)
        self.assertEqual(st["hemisphere"], "N")
        self.assertTrue(0.0 <= st["organization"] <= 1.0)
        self.assertTrue(0.0 <= st["eye_clarity"] <= 1.0)
        self.assertGreaterEqual(st["eye_km"], 0)
        self.assertGreaterEqual(st["rmw_km"], 5)
        self.assertGreaterEqual(st["rain_mmh"], 0)
        pr = m["profile"]
        self.assertEqual(set(pr), {"r_km", "wind_ms", "pressure_hpa", "rain_mmh", "cloud_top_km"})
        n = len(pr["r_km"])
        self.assertEqual(pr["r_km"][0], 0)
        self.assertEqual(pr["r_km"][-1], 600)
        self.assertEqual(pr["r_km"], sorted(set(pr["r_km"])))
        for k in ("wind_ms", "pressure_hpa", "rain_mmh", "cloud_top_km"):
            self.assertEqual(len(pr[k]), n, k)
            self.assertTrue(all(math.isfinite(v) for v in pr[k]), k)
        self.assertTrue(all(v >= 0 for v in pr["wind_ms"]))
        self.assertTrue(all(v >= 0 for v in pr["rain_mmh"]))
        self.assertTrue(all(0 <= v <= 18 for v in pr["cloud_top_km"]))
        self.assertEqual(len(m["bands"]), 6)
        for b in m["bands"]:
            self.assertEqual(set(b), {"theta", "r0_km", "r1_km", "pitch", "width_km", "intensity"})
            self.assertLess(b["r0_km"], b["r1_km"])
            self.assertLessEqual(b["r1_km"], 600)
            self.assertGreater(b["pitch"], 0)
            self.assertGreater(b["width_km"], 0)
            self.assertTrue(0.0 <= b["intensity"] <= 1.0)
        self.assertIsInstance(m["events"], list)
        if "section" in m:
            self.check_section(m["section"])

    def check_section(self, s):
        self.assertEqual(set(s), {"cols", "rows", "dr_km", "dz_km", "u", "w", "v", "cloud", "temp_anom"})
        self.assertEqual((s["cols"], s["rows"]), (48, 16))
        self.assertAlmostEqual(s["cols"] * s["dr_km"], 600)
        self.assertAlmostEqual(s["rows"] * s["dz_km"], 18)
        for k in ("u", "w", "v", "cloud", "temp_anom"):
            self.assertEqual(len(s[k]), s["cols"] * s["rows"], k)
            self.assertTrue(all(isinstance(x, float) and math.isfinite(x) for x in s[k]), k)
        self.assertTrue(all(0.0 <= x <= 1.0 for x in s["cloud"]))

    def test_frames_match_schema_over_a_life(self):
        sim = HurricaneSim(3, FPS)
        sim.handle_command({"cmd": "auto", "value": True}, [])
        first = True
        for i in range(2400):
            m = sim.step(DT * 3)           # 3x faster than real time through the whole life
            if first:
                self.assertIn("section", m, "the first frame must carry a section")
                first = False
            if i % 7 == 0 or "section" in m:
                self.check_frame(m)
            if i % 50 == 0:
                json.dumps(m, allow_nan=False)
        self.assertEqual(m["frame"], 2400)

    def test_extra_events_come_first_and_are_kept(self):
        sim = HurricaneSim(3, FPS)
        m = sim.step(DT, extra_events=["hello from the driver"])
        self.assertEqual(m["events"][0], "hello from the driver")

    def test_default_start_is_a_quiet_disturbance(self):
        m = HurricaneSim(3, FPS).step(DT)
        self.assertEqual(m["stage"], "disturbance")
        self.assertFalse(m["auto"])
        self.assertFalse(m["paused"])
        self.assertEqual(m["state"]["category"], -2)
        self.assertEqual(m["speed"], 1.0)


# ---------------------------------------------------------------------------------------------
class TestStageFigures(SettledCase):
    """The finished picture of every stage against the real scales."""

    def test_wind_category_and_pressure_follow_the_stage(self):
        stages = {s["id"]: s for s in self.hello["hurricane"]["stages"]}
        for sid in STAGES:
            st = self.frames[sid]["state"]
            lo, hi = stages[sid]["wind_kt"]
            self.assertGreaterEqual(st["vmax_kt"], lo, sid)
            if hi is not None:
                self.assertLessEqual(st["vmax_kt"], hi, sid)
            self.assertEqual(st["category"], H.category_for_kt(st["vmax_kt"]), sid)
            self.assertAlmostEqual(st["vmax_ms"], st["vmax_kt"] * 0.514444, delta=0.6, msg=sid)
            self.assertLess(abs(st["pressure_hpa"] - stages[sid]["pressure_hpa"]), 30, sid)
            self.assertEqual(self.frames[sid]["stage"], sid)
            self.assertEqual(self.frames[sid]["stage_frac"], 0.0)
            self.assertFalse(self.frames[sid]["auto"])
        for sid, cat in (("disturbance", -2), ("depression", -1), ("storm", 0), ("cat1", 1),
                         ("cat2", 2), ("cat3", 3), ("cat4", 4), ("cat5", 5), ("landfall", 4)):
            self.assertEqual(self.frames[sid]["state"]["category"], cat, sid)

    def test_hurricane_categories_match_saffir_simpson(self):
        for cat in range(1, 6):
            st = self.frames[f"cat{cat}"]["state"]
            lo, hi = SSHWS_KT[cat]
            self.assertGreaterEqual(st["vmax_kt"], lo)
            if hi:
                self.assertLessEqual(st["vmax_kt"], hi)
            plo, phi = ORIG_PRESSURE[cat]
            self.assertTrue(plo <= st["pressure_hpa"] <= phi, f"cat{cat} pressure {st['pressure_hpa']}")
            slo, shi = ORIG_SURGE_M[cat]
            self.assertTrue(slo <= st["surge_m"] <= shi, f"cat{cat} surge {st['surge_m']}")

    def test_intensification_trend_across_stages(self):
        def series(key):
            return [self.frames[s]["state"][key] for s in STAGES[:8]]
        self.assertEqual(series("vmax_kt"), sorted(series("vmax_kt")))
        self.assertEqual(series("pressure_hpa"), sorted(series("pressure_hpa"), reverse=True))
        self.assertEqual(series("surge_m"), sorted(series("surge_m")))
        self.assertEqual(series("warm_core_c"), sorted(series("warm_core_c")))
        self.assertEqual(series("cloud_top_km"), sorted(series("cloud_top_km")))
        self.assertEqual(series("organization"), sorted(series("organization")))
        self.assertEqual(series("eye_clarity"), sorted(series("eye_clarity")))
        self.assertEqual(series("rain_mmh"), sorted(series("rain_mmh")))
        # the eyewall contracts as the storm intensifies
        rm = series("rmw_km")
        self.assertEqual(rm, sorted(rm, reverse=True))

    def test_real_world_ranges(self):
        for sid in STAGES:
            st = self.frames[sid]["state"]
            self.assertTrue(940 <= st["pressure_hpa"] <= 1012 or st["pressure_hpa"] < 940, sid)
            self.assertTrue(10 <= st["cloud_top_km"] <= 18 or sid == "dissipation", sid)
            self.assertLessEqual(st["warm_core_c"], 14.0, sid)
            self.assertLessEqual(st["cloud_top_km"], 18.0, sid)
        for sid in STAGES[:8]:
            self.assertGreaterEqual(self.frames[sid]["state"]["sst_c"], 26.5, f"{sid} needs warm water")
            self.assertFalse(self.frames[sid]["state"]["over_land"], sid)
        self.assertGreaterEqual(self.frames["cat5"]["state"]["sst_c"], 29.5)   # near 30 C for cat5
        for sid in ("cat1", "cat2", "cat3", "cat4", "cat5"):
            st = self.frames[sid]["state"]
            self.assertTrue(100 <= st["r34_km"] <= 320, f"{sid} r34 {st['r34_km']}")
            self.assertTrue(8 <= st["eye_km"] <= 33, f"{sid} eye radius {st['eye_km']}")   # 30-65 km diameter
            self.assertTrue(10 <= st["rmw_km"] <= 50, f"{sid} rmw {st['rmw_km']}")
            self.assertLess(st["eye_km"], st["rmw_km"], sid)
        for sid in ("cat3", "cat4", "cat5"):
            self.assertGreaterEqual(self.frames[sid]["state"]["warm_core_c"], 7.0)     # 7-13 C aloft
            self.assertGreaterEqual(self.frames[sid]["state"]["cloud_top_km"], 15.0)   # 15-18 km eyewall tops
            self.assertGreater(self.frames[sid]["state"]["eye_clarity"], 0.7)
        self.assertEqual(self.frames["disturbance"]["state"]["eye_clarity"], 0.0)
        self.assertEqual(self.frames["disturbance"]["state"]["r34_km"], 0.0)       # no gales yet
        self.assertEqual(self.frames["depression"]["state"]["r34_km"], 0.0)
        self.assertGreater(self.frames["storm"]["state"]["r34_km"], 50.0)
        self.assertEqual(self.frames["disturbance"]["state"]["surge_m"] < 0.2, True)

    def test_landfall_and_dissipation(self):
        lf, ds, c5 = (self.frames[s]["state"] for s in ("landfall", "dissipation", "cat5"))
        self.assertTrue(lf["over_land"])
        self.assertTrue(ds["over_land"])
        self.assertLess(lf["vmax_kt"], c5["vmax_kt"])
        self.assertLess(ds["vmax_kt"], lf["vmax_kt"])
        self.assertGreater(lf["pressure_hpa"], c5["pressure_hpa"])
        self.assertGreater(ds["pressure_hpa"], lf["pressure_hpa"])        # the pressure fills
        self.assertLess(ds["eye_clarity"], 0.35)                          # eye clouds over
        self.assertLess(ds["warm_core_c"], c5["warm_core_c"])
        self.assertLess(ds["cloud_top_km"], c5["cloud_top_km"])
        self.assertLess(ds["rain_mmh"], c5["rain_mmh"])
        self.assertLess(ds["organization"], 0.6)
        self.assertLess(ds["surge_m"], lf["surge_m"])

    def test_holland_profiles_are_consistent(self):
        for sid in ("storm", "cat1", "cat3", "cat5"):
            m = self.frames[sid]
            st, pr = m["state"], m["profile"]
            r = pr["r_km"]
            wind, pres = pr["wind_ms"], pr["pressure_hpa"]
            ipk = max(range(len(r)), key=lambda i: wind[i])
            self.assertLessEqual(abs(r[ipk] - st["rmw_km"]), 10.01, f"{sid}: wind max at {r[ipk]} vs rmw {st['rmw_km']}")
            self.assertGreaterEqual(wind[ipk], 0.9 * st["vmax_ms"] - 1.0, sid)
            self.assertLessEqual(wind[ipk], st["vmax_ms"] + 1.6, sid)
            # pressure: central value, rising outward to the environment, monotone
            self.assertAlmostEqual(pres[0], st["pressure_hpa"], delta=0.11, msg=sid)
            self.assertEqual(pres, sorted(pres), sid)
            self.assertGreater(pres[-1], 1005.0, sid)
            self.assertLess(pres[-1], 1013.0, sid)
            # the wind falls off beyond the radius of maximum wind
            outer = [w for rr, w in zip(r, wind) if rr >= r[ipk]]
            self.assertEqual(outer, sorted(outer, reverse=True), sid)
            self.assertLess(wind[-1], 0.25 * st["vmax_ms"] + 2.0, sid)
            # gale radius agrees with the profile
            gale = [rr for rr, w in zip(r, wind) if w >= 17.5]
            self.assertAlmostEqual(max(gale), st["r34_km"], delta=10.5, msg=sid)
        # eye is calm at cat3+
        for sid in ("cat3", "cat4", "cat5"):
            m = self.frames[sid]
            wind = m["profile"]["wind_ms"]
            self.assertLess(wind[0], 3.0, sid)
            self.assertLess(wind[0], 0.1 * m["state"]["vmax_ms"], sid)
        # gradient-wind balance used by the model: B*dp/(rho*e) = vmax^2 stays in Holland's range
        sim = HurricaneSim(11, FPS)
        for sid in ("cat1", "cat2", "cat3", "cat4", "cat5"):
            settle(sim, sid, 12)
            self.assertTrue(1.0 <= sim._core()["B"] <= 2.2, f"{sid} B={sim._core()['B']}")

    def test_rain_peaks_in_the_eyewall_and_the_eye_is_dry(self):
        for sid in ("cat3", "cat4", "cat5"):
            m = self.frames[sid]
            r, rain = m["profile"]["r_km"], m["profile"]["rain_mmh"]
            ipk = max(range(len(r)), key=lambda i: rain[i])
            self.assertTrue(0.7 * m["state"]["rmw_km"] <= r[ipk] <= 1.8 * m["state"]["rmw_km"] + 10, sid)
            self.assertLess(rain[0], 0.15 * rain[ipk], f"{sid}: eye should be dry")
            self.assertEqual(rain[-1] < 0.2 * rain[ipk], True, sid)
            # rainbands: a secondary bump outside the eyewall above the far-field background
            ring = [v for rr, v in zip(r, rain) if 100 <= rr <= 250]
            self.assertGreater(max(ring), rain[-1] + 1.0, sid)
            self.assertLessEqual(abs(m["state"]["rain_mmh"] - max(rain)), 0.12 * m["state"]["rain_mmh"] + 0.5, sid)
            # cloud tops: highest in the eyewall, clear eye is low
            ct = m["profile"]["cloud_top_km"]
            self.assertLess(ct[0], 5.0, sid)
            self.assertGreater(max(ct), 15.0, sid)
            self.assertEqual(ct.index(max(ct)) * 10 <= 1.6 * m["state"]["rmw_km"] + 10, True, sid)

    def test_bands_organise_with_the_storm(self):
        def ints(sid):
            return [b["intensity"] for b in self.frames[sid]["bands"]]
        self.assertLess(sum(ints("disturbance")), 0.15)
        self.assertEqual(ints("cat3").count(0.0), 0)
        sums = [sum(ints(s)) for s in ("storm", "cat1", "cat2", "cat3", "cat4", "cat5")]
        self.assertEqual(sums, sorted(sums))
        # a band that is not there yet keeps its slot with intensity ~0, and the list is the same length
        self.assertEqual(len(self.frames["disturbance"]["bands"]), len(self.frames["cat5"]["bands"]))
        thetas = [round(b["theta"], 3) for b in self.frames["cat3"]["bands"]]
        self.assertEqual(thetas, [round(b["theta"], 3) for b in self.frames["cat5"]["bands"]])
        self.assertEqual(len(set(thetas)), 6)


# ---------------------------------------------------------------------------------------------
class TestSection(SettledCase):
    def test_surface_inflow_is_strongest_outside_the_eyewall(self):
        for sid in ("cat1", "cat3", "cat4", "cat5"):
            s, st = self.sections[sid], self.frames[sid]["state"]
            u0 = grid(s, "u")[0]
            cmin = min(range(s["cols"]), key=lambda c: u0[c])
            self.assertLess(u0[cmin], -0.15 * st["vmax_ms"], sid)
            self.assertGreater(rc(s, cmin), st["rmw_km"], f"{sid}: strongest inflow should be outside the RMW")
            self.assertLess(rc(s, cmin), 4.5 * st["rmw_km"], sid)
            # inflow everywhere outside the eye at the lowest level, weaker far away
            for c in range(s["cols"]):
                if rc(s, c) > 1.2 * st["rmw_km"]:
                    self.assertLess(u0[c], -0.5, f"{sid} col {c}")
            self.assertGreater(u0[-1], u0[cmin])
            # inflow is a low-level feature: near zero at mid levels far from the wall
            mid = grid(s, "u")[8]
            self.assertLess(abs(mid[-1]), 1.0, sid)

    def test_ascent_is_in_a_sloping_eyewall(self):
        for sid in ("cat1", "cat3", "cat4", "cat5"):
            s, st = self.sections[sid], self.frames[sid]["state"]
            w = grid(s, "w")
            best = max(((w[r][c], r, c) for r in range(s["rows"]) for c in range(s["cols"])))
            wmax, row, col = best
            self.assertGreater(wmax, 1.5, sid)
            rm = st["rmw_km"]
            self.assertTrue(0.7 * rm <= rc(s, col) <= 2.5 * rm + s["dr_km"], f"{sid}: max ascent at {rc(s, col)} km, rmw {rm}")
            far = max(w[r][c] for r in range(s["rows"]) for c in range(s["cols"]) if rc(s, c) > 250)
            self.assertLess(far, 0.5 * wmax, sid)
            # the position of the updraft moves outward with height (sloping eyewall)
            def peak_col(r):
                return max(range(s["cols"]), key=lambda c: w[r][c])
            self.assertLessEqual(peak_col(2), peak_col(9), sid)
            self.assertLess(rc(s, peak_col(2)), rc(s, peak_col(10)) + 1e-9, sid)
            # no ascent at the very top or at the surface, a deep updraft in between
            self.assertLess(max(w[0]), 0.4 * wmax, sid)
            self.assertLess(max(w[-1]), 0.1 * wmax, sid)

    def test_outflow_aloft_is_positive(self):
        for sid in ("cat1", "cat3", "cat4", "cat5"):
            s, st = self.sections[sid], self.frames[sid]["state"]
            u = grid(s, "u")
            top = st["cloud_top_km"]
            best = max(((u[r][c], r, c) for r in range(s["rows"]) for c in range(s["cols"])))
            umax, row, col = best
            self.assertGreater(umax, 4.0, sid)
            self.assertGreater(zc(s, row), 0.6 * top, f"{sid}: the strongest outflow is near the top")
            # positive across the outflow layer outside the eyewall, negative near the surface
            ro = [c for c in range(s["cols"]) if rc(s, c) > 2.5 * st["rmw_km"] + 30 and rc(s, c) < 450]
            layer = [r for r in range(s["rows"]) if 0.7 * top <= zc(s, r) <= 0.95 * top]
            self.assertTrue(layer)
            for r in layer:
                self.assertGreater(sum(u[r][c] for c in ro) / len(ro), 1.0, f"{sid} row {r}")
            self.assertGreater(umax, abs(min(min(row_) for row_ in u[3:])) * 0.8, sid)   # not a low-level thing
            self.assertLess(sum(u[0][c] for c in ro) / len(ro), -1.0, sid)

    def test_eye_is_warm_clear_and_sinking(self):
        for sid in ("cat3", "cat4", "cat5"):
            s, st = self.sections[sid], self.frames[sid]["state"]
            ta, cl, w = grid(s, "temp_anom"), grid(s, "cloud"), grid(s, "w")
            # warmest cell is in the eye, aloft
            best = max(((ta[r][c], r, c) for r in range(s["rows"]) for c in range(s["cols"])))
            tmax, row, col = best
            self.assertLessEqual(rc(s, col), max(st["eye_km"], 12.5) + 1e-9, sid)
            self.assertGreater(zc(s, row), 6.0, sid)
            self.assertLess(zc(s, row), 14.0, sid)
            self.assertAlmostEqual(tmax, st["warm_core_c"], delta=0.1 * st["warm_core_c"], msg=sid)
            self.assertGreater(tmax, 7.0, sid)
            # warm anomaly decays away from the core
            self.assertLess(ta[row][-1], 0.1 * tmax, sid)
            self.assertLess(ta[0][0], 0.2 * tmax, sid)
            # eye column: nearly cloud free above the low cloud, sinking
            eye_cells = [cl[r][0] for r in range(s["rows"]) if zc(s, r) >= 3.0]
            self.assertLess(sum(eye_cells) / len(eye_cells), 0.2, sid)
            self.assertLess(max(eye_cells), 0.3, sid)
            wall = max(cl[r][c] for r in range(s["rows"]) for c in range(s["cols"]))
            self.assertGreater(wall, 0.8, sid)
            sink = [w[r][0] for r in range(s["rows"]) if 4.0 <= zc(s, r) <= 12.0]
            self.assertLess(sum(sink) / len(sink), -0.1, sid)
            self.assertTrue(all(x < 0.5 for x in sink), sid)
            # rainbands: convective ascent and cloud away from the eyewall
            ring = [(c, w[6][c], cl[6][c]) for c in range(s["cols"]) if 3 * st["rmw_km"] < rc(s, c) < 480]
            self.assertGreater(max(x[1] for x in ring), 0.2, sid)
            self.assertGreater(max(x[2] for x in ring), 0.4, sid)

    def test_cloud_density_has_eyewall_bands_and_cirrus_shield(self):
        for sid in ("cat3", "cat5"):
            s, st = self.sections[sid], self.frames[sid]["state"]
            cl = grid(s, "cloud")
            top = st["cloud_top_km"]
            # above the cloud top: no cloud
            for r in range(s["rows"]):
                if zc(s, r) > top + 1.7:
                    self.assertLess(max(cl[r]), 0.12, f"{sid} row {r}")
            # cirrus outflow shield at the top, extending far out
            layer = max(range(s["rows"]), key=lambda r: sum(cl[r][8:]))
            self.assertGreater(zc(s, layer), 0.7 * top)
            self.assertGreater(cl[layer][40], 0.1, sid)
            # eyewall column is dense through the depth
            col = min(range(s["cols"]), key=lambda c: abs(rc(s, c) - 1.4 * st["rmw_km"]))
            self.assertGreater(sum(cl[r][col] for r in range(2, 11)) / 9, 0.55, sid)
            # clear sky between the bands and the far field (a moat) exists at mid levels
            self.assertLess(min(cl[6][c] for c in range(s["cols"]) if rc(s, c) > 2 * st["rmw_km"]), 0.15, sid)

    def test_tangential_wind_peaks_at_the_rmw_and_weakens_aloft(self):
        for sid in ("cat3", "cat5"):
            s, st = self.sections[sid], self.frames[sid]["state"]
            v = grid(s, "v")
            best = max(((v[r][c], r, c) for r in range(s["rows"]) for c in range(s["cols"])))
            vmax, row, col = best
            self.assertLess(zc(s, row), 3.0, sid)
            self.assertAlmostEqual(vmax, st["vmax_ms"], delta=0.25 * st["vmax_ms"], msg=sid)
            self.assertLessEqual(abs(rc(s, col) - st["rmw_km"]), 1.0 * s["dr_km"], sid)
            self.assertLess(v[11][col], 0.5 * vmax, sid)         # decays with height
            self.assertLess(v[0][0], 0.25 * vmax, sid)            # calm eye
            self.assertLess(max(v[-1]), 0.2 * vmax, sid)

    def test_disturbance_is_weak_and_disorganised_cat45_sharp(self):
        def peak(s, name):
            return max(max(row) for row in grid(s, name)), min(min(row) for row in grid(s, name))
        d, c3, c5 = (self.sections[k] for k in ("disturbance", "cat3", "cat5"))
        self.assertLess(peak(d, "w")[0], 0.5)
        self.assertGreater(peak(c5, "w")[0], 3.0)
        self.assertLess(abs(peak(d, "u")[1]), 3.0)
        self.assertGreater(abs(peak(c5, "u")[1]), 15.0)
        self.assertLess(peak(d, "temp_anom")[0], 1.0)
        self.assertGreater(peak(c5, "temp_anom")[0], 10.0)
        self.assertLess(peak(d, "cloud")[0], 0.75)
        mean_cloud = lambda s: sum(s["cloud"]) / len(s["cloud"])   # noqa: E731
        self.assertLess(mean_cloud(d), 0.5 * mean_cloud(c5))
        self.assertGreater(peak(c5, "cloud")[0], 0.9)
        # sharpness of the eyewall cloud: steeper radial gradient at cat5 than cat1
        def grad(s):
            cl = grid(s, "cloud")
            row = cl[5]
            return max(abs(row[i + 1] - row[i]) for i in range(len(row) - 1))
        self.assertGreater(grad(c5), grad(self.sections["cat1"]) - 0.02)
        self.assertGreater(grad(c5), 2.0 * grad(d))

    def test_section_collapses_after_landfall(self):
        c5, ds = self.sections["cat5"], self.sections["dissipation"]
        fs = self.frames["dissipation"]["state"]
        self.assertTrue(fs["over_land"])
        w5 = max(max(r) for r in grid(c5, "w"))
        wd = max(max(r) for r in grid(ds, "w"))
        self.assertLess(wd, 0.5 * w5)
        self.assertGreater(min(min(r) for r in grid(ds, "u")), min(min(r) for r in grid(c5, "u")) * 0.5)
        eye_cloud = lambda s: sum(grid(s, "cloud")[r][0] for r in range(4, 12)) / 8     # noqa: E731
        self.assertGreater(eye_cloud(ds), 2.0 * eye_cloud(c5) + 0.05)                   # the eye clouds over
        self.assertLess(max(max(r) for r in grid(ds, "temp_anom")), 0.7 * max(max(r) for r in grid(c5, "temp_anom")))
        self.assertLess(max(max(r) for r in grid(ds, "v")), 0.6 * max(max(r) for r in grid(c5, "v")))

    def test_landfall_reduces_inflow_and_ascent(self):
        c4, lf = self.sections["cat4"], self.sections["landfall"]
        # landfall starts from ~130 kt (a Category 4): still a clear storm, but over land
        self.assertTrue(self.frames["landfall"]["state"]["over_land"])
        self.assertLess(max(max(r) for r in grid(lf, "w")), 1.15 * max(max(r) for r in grid(c4, "w")))


# ---------------------------------------------------------------------------------------------
class TestLifeCycle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sim = HurricaneSim(21, FPS)
        sim.handle_command({"cmd": "auto", "value": True}, [])
        cls.rows = []
        cls.events = []
        cls.sections = []
        t0 = None
        left = False
        for i in range(int(215 * FPS)):
            m = sim.step(DT)
            cls.rows.append(m)
            cls.events.extend(m["events"])
            if "section" in m:
                cls.sections.append((i, m["section"]))
            left = left or m["stage_index"] > 0
            if left and m["stage"] == "disturbance" and t0 is None:
                t0 = i
        cls.wrap_frame = t0
        cls.sim = sim

    def test_whole_life_runs_3_to_4_minutes_then_loops(self):
        self.assertIsNotNone(self.wrap_frame, "auto must loop back to the disturbance")
        life_s = self.wrap_frame * DT
        self.assertTrue(180 <= life_s <= 240, f"life took {life_s:.0f}s")
        seq = []
        for m in self.rows[:self.wrap_frame]:
            if not seq or seq[-1] != m["stage"]:
                seq.append(m["stage"])
        self.assertEqual(seq, STAGES)
        self.assertTrue(any("new tropical disturbance" in e for e in self.events))

    def test_intensification_is_monotone_then_decay_is_monotone(self):
        rows = self.rows[:self.wrap_frame]
        kt = [m["state"]["vmax_kt"] for m in rows]
        pres = [m["state"]["pressure_hpa"] for m in rows]
        peak = kt.index(max(kt))
        self.assertGreaterEqual(max(kt), 137, "the storm reaches Category 5")
        self.assertEqual(rows[peak]["state"]["category"], 5)
        up = kt[:peak + 1]
        self.assertEqual(up, sorted(up), "wind never drops while intensifying")
        down = kt[peak:]
        self.assertEqual(down, sorted(down, reverse=True), "wind never rises after the peak")
        pmin = pres.index(min(pres))
        self.assertEqual(pres[:pmin + 1], sorted(pres[:pmin + 1], reverse=True))
        self.assertEqual(pres[pmin:], sorted(pres[pmin:]))
        self.assertLess(min(pres), 920)
        self.assertGreater(kt[-1], 0)
        self.assertLess(kt[-1], 30)
        # the peak happens before landfall finishes, and landfall comes after the peak
        self.assertLess(rows[peak]["stage_index"], 9)
        land = [i for i, m in enumerate(rows) if m["state"]["over_land"]]
        self.assertGreater(land[0], peak - 30 * FPS)     # land is hit about when the peak is reached
        self.assertEqual(land, list(range(land[0], land[0] + len(land))), "over_land flips once")

    def test_category_sequence_is_ordered(self):
        rows = self.rows[:self.wrap_frame]
        cats = [m["state"]["category"] for m in rows]
        seen = []
        for c in cats:
            if not seen or seen[-1] != c:
                seen.append(c)
        self.assertEqual(seen, [-2, -1, 0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0, -1, -2])

    def test_events_narrate_the_life(self):
        text = "\n".join(self.events)
        for phrase in ("the eye cleared out", "the eye clouded over", "strengthened to a Category 3 hurricane",
                       "Category 5 hurricane", "landfall", "weakened to a tropical storm"):
            self.assertIn(phrase, text)
        for e in self.events:
            self.assertTrue(re.match(r"t=\d+\.\d ", e), e)

    def test_rotation_accumulates_faster_when_stronger(self):
        rot = [m["state"]["rotation"] for m in self.rows]
        self.assertEqual(rot, sorted(rot))
        early = rot[3 * FPS] - rot[2 * FPS]
        late_i = [i for i, m in enumerate(self.rows) if m["stage"] == "cat5"][len(self.rows) // 40]
        late = rot[late_i + FPS] - rot[late_i]
        self.assertGreater(late, 2.0 * early)
        self.assertLess(late, 1.2)       # at most ~1 rev per 5 s: readable

    def test_sections_are_rate_limited_and_only_when_changed(self):
        idx = [i for i, _ in self.sections]
        self.assertEqual(idx[0], 0)
        gaps = [b - a for a, b in zip(idx, idx[1:])]
        self.assertGreaterEqual(min(gaps), math.ceil(FPS / 6))
        per_second = {}
        for i in idx:
            per_second[i // FPS] = per_second.get(i // FPS, 0) + 1
        self.assertLessEqual(max(per_second.values()), 6)
        self.assertGreater(len(idx), 30)

    def test_frames_never_jump_in_auto_mode(self):
        self.assertSmooth(self.rows, "auto life")

    def assertSmooth(self, rows, label, bound=0.02, profile_bound=0.1, spd=1.0):
        scale = {"vmax_kt": 160, "pressure_hpa": 120, "rmw_km": 100, "eye_km": 30, "r34_km": 300,
                 "sst_c": 3, "warm_core_c": 14, "cloud_top_km": 12, "rain_mmh": 55, "surge_m": 7,
                 "organization": 1, "eye_clarity": 1}
        worst = (0, None)
        for a, b in zip(rows, rows[1:]):
            for k, sc in scale.items():
                d = abs(b["state"][k] - a["state"][k]) / sc
                if d > worst[0]:
                    worst = (d, f"{label}: {k} {a['state'][k]} -> {b['state'][k]} at frame {b['frame']}")
        self.assertLessEqual(worst[0], bound, worst[1])
        pscale = {"wind_ms": 80, "pressure_hpa": 120, "rain_mmh": 55, "cloud_top_km": 16}
        worst = (0, None)
        for a, b in zip(rows, rows[1:]):
            for k, sc in pscale.items():
                d = max(abs(x - y) for x, y in zip(a["profile"][k], b["profile"][k])) / sc
                if d > worst[0]:
                    worst = (d, f"{label}: profile.{k} jumped by {d:.3f} of range at frame {b['frame']}")
        self.assertLessEqual(worst[0], profile_bound, worst[1])
        for a, b in zip(rows, rows[1:]):
            for ba, bb in zip(a["bands"], b["bands"]):
                self.assertLess(abs(bb["intensity"] - ba["intensity"]), min(0.05 * spd, 0.6), f"{label}: band intensity")
                self.assertLess(abs(bb["r0_km"] - ba["r0_km"]), 4.0 * spd, f"{label}: band r0")
                self.assertLess(abs(bb["r1_km"] - ba["r1_km"]), 15.0 * spd, f"{label}: band r1")
            self.assertLess(abs(b["state"]["rotation"] - a["state"]["rotation"]), 0.04 * spd)


class TestSmoothnessAcrossStageJumps(unittest.TestCase):
    def run_jumps(self, order, speed=1.0, hold_s=7.0, seed=4):
        sim = HurricaneSim(seed, FPS)
        sim.handle_command({"cmd": "speed", "value": speed}, [])
        rows = []
        for sid in order:
            sim.handle_command({"cmd": "stage", "id": sid}, [])
            for _ in range(int(hold_s * FPS)):
                rows.append(sim.step(DT))
        return rows

    def test_no_snaps_right_after_a_jump(self):
        order = ["disturbance", "cat5", "disturbance", "cat3", "dissipation", "cat4", "landfall",
                 "storm", "cat5", "dissipation", "depression", "cat2"]
        rows = self.run_jumps(order)
        TestLifeCycle.assertSmooth(self, rows, "stage jumps", bound=0.02)

    def test_a_jump_takes_a_few_seconds(self):
        sim = HurricaneSim(4, FPS)
        settle(sim, "disturbance", 8)
        sim.handle_command({"cmd": "stage", "id": "cat5"}, [])
        series = [sim.step(DT)["state"]["vmax_kt"] for _ in range(14 * FPS)]
        target = 141
        start = series[0]
        self.assertLess(start, 20)
        t90 = next(i for i, v in enumerate(series) if v >= 12 + 0.9 * (target - 12)) / FPS
        t10 = next(i for i, v in enumerate(series) if v >= 12 + 0.1 * (target - 12)) / FPS
        self.assertGreater(t90, 2.5, "must not snap")
        self.assertLess(t90, 8.0, "must arrive within a few seconds")
        self.assertGreater(t90 - t10, 1.5)
        self.assertEqual(series, sorted(series), "a glide is monotone, no overshoot")
        self.assertAlmostEqual(series[-1], target, delta=2)

    def test_fast_speed_jumps_are_still_bounded(self):
        rows = self.run_jumps(["cat5", "disturbance", "cat3"], speed=8.0, hold_s=3.0)
        TestLifeCycle.assertSmooth(self, rows, "speed 8 jumps", bound=0.16, profile_bound=0.5, spd=8.0)

    def test_section_changes_gradually_between_emissions(self):
        sim = HurricaneSim(4, FPS)
        settle(sim, "disturbance", 8)
        sim.handle_command({"cmd": "stage", "id": "cat5"}, [])
        secs = []
        for _ in range(12 * FPS):
            m = sim.step(DT)
            if "section" in m:
                secs.append(m["section"])
        self.assertGreater(len(secs), 15)
        self.assertLessEqual(len(secs), 12 * 6 + 1)
        final = secs[-1]
        for name in ("u", "w", "v", "temp_anom"):
            rng = max(abs(x) for x in final[name]) or 1.0
            worst = max(max(abs(x - y) for x, y in zip(a[name], b[name])) for a, b in zip(secs, secs[1:]))
            self.assertLess(worst / rng, 0.45, f"section {name} jumps by {worst / rng:.2f} of its range")
        worst = max(max(abs(x - y) for x, y in zip(a["cloud"], b["cloud"])) for a, b in zip(secs, secs[1:]))
        self.assertLess(worst, 0.8)

    def test_settled_storm_stops_sending_sections(self):
        sim = HurricaneSim(4, FPS)
        settle(sim, "cat4", 20)
        n = sum("section" in sim.step(DT) for _ in range(10 * FPS))
        self.assertLessEqual(n, 1, "a settled, paused-in-time storm needs no section updates")

    def test_backward_jump_decays_monotonically(self):
        sim = HurricaneSim(4, FPS)
        settle(sim, "cat5", 16)
        sim.handle_command({"cmd": "stage", "id": "storm"}, [])
        kt = [sim.step(DT)["state"]["vmax_kt"] for _ in range(14 * FPS)]
        self.assertEqual(kt, sorted(kt, reverse=True))
        self.assertAlmostEqual(kt[-1], 40, delta=2)


# ---------------------------------------------------------------------------------------------
class TestCommands(unittest.TestCase):
    def setUp(self):
        self.sim = HurricaneSim(42, FPS)

    def test_pause_freezes_time_and_state(self):
        sim = self.sim
        sim.handle_command({"cmd": "auto", "value": True}, [])
        for _ in range(100):
            sim.step(DT)
        sim.handle_command({"cmd": "pause"}, [])
        a = sim.step(DT)
        b = sim.step(DT)
        self.assertTrue(a["paused"])
        self.assertEqual(a["t"], b["t"])
        self.assertEqual(a["state"], b["state"])
        self.assertEqual(a["stage_frac"], b["stage_frac"])
        self.assertEqual(a["profile"], b["profile"])
        self.assertEqual(b["frame"], a["frame"] + 1)
        self.assertNotIn("section", b)
        sim.handle_command({"cmd": "resume"}, [])
        c = sim.step(DT)
        self.assertFalse(c["paused"])
        self.assertGreater(c["t"], b["t"])
        self.assertNotEqual(c["state"]["rotation"], b["state"]["rotation"])

    def test_pause_then_stage_jump_still_holds_until_resume(self):
        sim = self.sim
        sim.handle_command({"cmd": "pause"}, [])
        sim.handle_command({"cmd": "stage", "id": "cat5"}, [])
        m = sim.step(DT)
        self.assertEqual(m["stage"], "cat5")
        self.assertEqual(m["state"]["category"], -2)
        sim.handle_command({"cmd": "resume"}, [])
        for _ in range(12 * FPS):
            m = sim.step(DT)
        self.assertEqual(m["state"]["category"], 5)

    def test_speed_is_applied_and_clamped(self):
        sim = self.sim
        sim.handle_command({"cmd": "speed", "value": 2.0}, [])
        m = sim.step(DT)
        self.assertEqual(m["speed"], 2.0)
        t0 = sim.t
        sim.step(DT)
        self.assertAlmostEqual(sim.t - t0, 2 * DT, places=9)
        sim.handle_command({"cmd": "speed", "value": 100}, [])
        self.assertEqual(sim.speed, 8.0)
        sim.handle_command({"cmd": "speed", "value": 0.001}, [])
        self.assertEqual(sim.speed, 0.25)
        sim.handle_command({"cmd": "speed", "value": "garbage"}, [])
        self.assertEqual(sim.speed, 0.25)
        sim.handle_command({"cmd": "speed", "value": float("nan")}, [])
        self.assertEqual(sim.speed, 0.25)

    def test_speed_makes_auto_play_faster(self):
        def stage_after(speed, seconds):
            s = HurricaneSim(1, FPS)
            s.handle_command({"cmd": "auto", "value": True}, [])
            s.handle_command({"cmd": "speed", "value": speed}, [])
            for _ in range(int(seconds * FPS)):
                m = s.step(DT)
            return m["stage_index"]
        self.assertGreater(stage_after(4.0, 30), stage_after(1.0, 30))
        self.assertEqual(stage_after(1.0, 5), 0)

    def test_stage_command_turns_auto_off_and_unknown_ids_are_ignored(self):
        sim = self.sim
        sim.handle_command({"cmd": "auto", "value": True}, [])
        self.assertTrue(sim.step(DT)["auto"])
        events = []
        sim.handle_command({"cmd": "stage", "id": "cat3"}, events)
        m = sim.step(DT)
        self.assertFalse(m["auto"])
        self.assertEqual((m["stage"], m["stage_index"], m["stage_frac"]), ("cat3", 5, 0.0))
        self.assertTrue(events)
        for _ in range(300):
            m = sim.step(DT)
        self.assertEqual((m["stage"], m["stage_frac"]), ("cat3", 0.0), "a chosen stage is held")
        for bad in ({"cmd": "stage", "id": "cat9"}, {"cmd": "stage"}, {"cmd": "stage", "id": 3}):
            sim.handle_command(bad, [])
            self.assertEqual(sim.step(DT)["stage"], "cat3")

    def test_auto_continues_from_the_chosen_stage(self):
        sim = self.sim
        sim.handle_command({"cmd": "stage", "id": "cat4"}, [])
        sim.handle_command({"cmd": "auto", "value": True}, [])
        seen = []
        for _ in range(int(125 * FPS)):
            m = sim.step(DT)
            if not seen or seen[-1] != m["stage"]:
                seen.append(m["stage"])
        self.assertEqual(seen[:4], ["cat4", "cat5", "landfall", "dissipation"])
        self.assertEqual(seen[4], "disturbance")
        sim.handle_command({"cmd": "auto", "value": False}, [])
        self.assertFalse(sim.step(DT)["auto"])
        frac = sim.step(DT)["stage_frac"]
        for _ in range(60):
            m = sim.step(DT)
        self.assertEqual(m["stage_frac"], frac, "auto off freezes the playback position")

    def test_save_is_refused(self):
        sim = self.sim
        sim.handle_command({"cmd": "save", "path": "x.json"}, [])
        out = sim.drain_outbox()
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["type"], "saved")
        self.assertFalse(out[0]["ok"])
        self.assertEqual(out[0]["error"], "saving is not available in hurricane mode")
        self.assertEqual(sim.drain_outbox(), [])
        sim.handle_command({"cmd": "save"}, [])
        self.assertFalse(sim.drain_outbox()[0]["ok"])

    def test_other_commands_are_ignored_and_quit_stops(self):
        sim = self.sim
        before = sim.step(DT)["state"]
        for cmd in ({"cmd": "spawn", "x": 3, "y": 4}, {"cmd": "wind", "delta": 4}, {"cmd": "temp", "delta": 4},
                    {"cmd": "reseed", "seed": 3}, {"cmd": "view", "x0": 1, "x1": 5}, {"cmd": "showcase"},
                    {"cmd": "nonsense"}, {}):
            sim.handle_command(cmd, [])
        self.assertEqual(sim.drain_outbox(), [])
        after = sim.step(DT)
        self.assertEqual(after["stage"], "disturbance")
        self.assertEqual(after["state"]["category"], before["category"])
        self.assertTrue(sim.running)
        sim.handle_command({"cmd": "quit"}, [])
        self.assertFalse(sim.running)

    def test_driver_attributes(self):
        sim = HurricaneSim(9, 24)
        self.assertEqual((sim.seed, sim.fps, sim.frame, sim.running), (9, 24, 0, True))
        sim.step(DT)
        sim.step(DT)
        self.assertEqual(sim.frame, 2)


# ---------------------------------------------------------------------------------------------
class TestDeterminismAndSeeds(unittest.TestCase):
    def run_seed(self, seed, n=500):
        sim = HurricaneSim(seed, FPS)
        out = []
        sim.handle_command({"cmd": "auto", "value": True}, [])
        for i in range(n):
            if i == 200:
                sim.handle_command({"cmd": "stage", "id": "cat4"}, [])
            if i == 350:
                sim.handle_command({"cmd": "auto", "value": True}, [])
            out.append(json.dumps(sim.step(DT), sort_keys=True))
        return out

    def test_same_seed_identical_frames(self):
        self.assertEqual(self.run_seed(7), self.run_seed(7))

    def test_different_seeds_vary_a_little(self):
        a, b = self.run_seed(7, 60), self.run_seed(8, 60)
        self.assertNotEqual(a, b)
        fa, fb = HurricaneSim(7, FPS), HurricaneSim(8, FPS)
        ma, mb = settle(fa, "cat3")[0], settle(fb, "cat3")[0]
        self.assertNotEqual([x["theta"] for x in ma["bands"]], [x["theta"] for x in mb["bands"]])
        # same stage, same category and same wind range; sizes differ by at most ~25 %
        self.assertEqual(ma["state"]["category"], mb["state"]["category"])
        self.assertTrue(0.75 < ma["state"]["rmw_km"] / mb["state"]["rmw_km"] < 1.33)
        for seed in range(12):
            m = settle(HurricaneSim(seed, FPS), "cat3", 14)[0]["state"]
            self.assertEqual(m["category"], 3, seed)
            self.assertGreaterEqual(m["sst_c"], 26.5, seed)

    def test_stage_table_rows_are_deterministic(self):
        self.assertEqual(H.stage_table(5), H.stage_table(5))


# ---------------------------------------------------------------------------------------------
class TestStageTable(unittest.TestCase):
    def test_print_stage_table(self):
        rows = H.stage_table(1)
        text = H.format_stage_table(rows)
        print("\n" + text)
        self.assertEqual([r["stage"] for r in rows], STAGES)
        self.assertEqual(len(text.splitlines()), 11)


@unittest.skipIf(os.environ.get("PRECIP_SKIP_PERF"), "PRECIP_SKIP_PERF set")
class TestPerf(unittest.TestCase):
    def test_average_step_time(self):
        best = None
        for _ in range(3):
            sim = HurricaneSim(1, FPS)
            sim.handle_command({"cmd": "auto", "value": True}, [])
            sim.handle_command({"cmd": "speed", "value": 8.0}, [])      # exercise every stage quickly
            times, sec_times = [], []
            for _i in range(1500):
                t0 = time.perf_counter()
                m = sim.step(DT)
                json.dumps(m, separators=(",", ":"))
                dt = (time.perf_counter() - t0) * 1000.0
                times.append(dt)
                if "section" in m:
                    sec_times.append(dt)
            mean = sum(times) / len(times)
            if best is None or mean < best[0]:
                best = (mean, max(times), sum(sec_times) / max(len(sec_times), 1), len(sec_times))
        print(f"\nstep+json: mean {best[0]:.2f} ms, worst {best[1]:.2f} ms, "
              f"frames with a section {best[3]}/1500 (mean {best[2]:.2f} ms)")
        self.assertLess(best[0], 5.0)
        self.assertLess(best[2], 15.0)


if __name__ == "__main__":
    unittest.main()
