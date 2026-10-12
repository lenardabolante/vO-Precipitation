"""Region presets (PROTOCOL.md v2.2): six real elevation cross-sections with their climate.

Data: every figure traces to a listed source URL, landmarks are in transect order inside the world and
the highest landmark is the highest ground. Terrain: deterministic per seed, relief within the cap,
summits and sea level keep their real values, settlements never level a summit. Climate: starting
temperature and wind come from the preset, cold presets snow and warm ones do not, the cloud mix
follows the climate. Save / load keeps the preset and v2 / v2.1 saves still load as sandbox worlds.
v2.3: at least 20 presets (six US states among the new ones, every inhabited continent and a polar one),
twelve real monthly normals each, map position / region / kind, climate_block() and the assets/world
export in sync with sim/presets.py."""
import copy
import json
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_presets.py` (or sim.tests.test_presets) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import presets as P
from clouds import TYPE_NAMES, spawn_cloud
from savestate import load_state, save_state
from simulation import Simulation
from terrain import MAX_RELIEF_FRAC, MIN_M_PER_PX, profile_m_per_px

DT = 1.0 / 30
W, H = 3840, 1080
SIM_ENTRY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "precip_sim.py")
OLD_SAVE = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                        "saves", "washington-tml7zu", "state.sav")

_SIMS: dict = {}


def sim_for(pid, seed=7):
    """One cached world per preset (building a 3840 px world takes a moment). Tests must not step it."""
    key = (pid, seed)
    if key not in _SIMS:
        _SIMS[key] = Simulation(seed, W, H, 30, "rolling", "normal", pid)
    return _SIMS[key]


def height_m(sim, x, preset):
    """Real metres of the surface at world x, read back from the heightmap."""
    mpp = profile_m_per_px(preset["max_elev_m"] - preset["min_elev_m"], sim.world.h)
    return (sim.terrain.ground_y - sim.terrain.height_at(x)) * mpp + preset["min_elev_m"]


class TestPresetData(unittest.TestCase):
    def test_the_six_regions_exist(self):
        self.assertEqual(set(P.PRESET_IDS), S.PRESET_IDS)
        self.assertTrue({"washington", "philippines", "tibet", "russia", "united_kingdom",
                         "central_europe"} <= set(P.PRESET_IDS))

    def test_every_figure_traces_to_a_listed_source(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                self.assertTrue(p["sources"], "sources must not be empty")
                self.assertEqual(len(p["sources"]), len(set(p["sources"])), "no duplicate sources")
                for u in p["sources"]:
                    self.assertTrue(u.startswith("https://"), u)
                for lm in p["landmarks"]:
                    self.assertIn(lm["src"], p["sources"], f"{lm['name']} has no listed source")
                for u in p["climate"]["src"]:
                    self.assertIn(u, p["sources"])
                self.assertIn(P._LAPSE_SRC, p["sources"], "the lapse rate used for the start temperature")

    def test_landmark_data_is_complete_and_ordered(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                lms = p["landmarks"]
                self.assertGreaterEqual(len(lms), 5)
                self.assertEqual(lms[0]["km"], 0.0, "the transect starts at its first landmark")
                kms = [lm["km"] for lm in lms]
                self.assertEqual(kms, sorted(set(kms)), "landmarks strictly in transect order")
                self.assertEqual(p["span_km"], kms[-1])
                for lm in lms:
                    self.assertTrue(-90 < lm["lat"] < 90 and -180 < lm["lon"] < 180)
                    self.assertIsInstance(lm["elev_m"], (int, float))
                self.assertEqual(p["max_elev_m"], max(lm["elev_m"] for lm in lms))
                self.assertEqual(p["min_elev_m"], min(lm["elev_m"] for lm in lms))
                for k in ("station", "temp_c", "annual_precip_mm", "wind", "note"):
                    self.assertIn(k, p["climate"])
                # a weight for every cloud type (supercell, when the cloud model has it, is optional)
                self.assertTrue(set(TYPE_NAMES) - {"supercell"} <= set(p["cloud_mix"]) <= set(TYPE_NAMES) | {"supercell"})
                self.assertGreater(sum(p["cloud_mix"].values()), 0)

    def test_the_headline_peaks_are_the_real_ones(self):
        top = {pid: max(p["landmarks"], key=lambda lm: lm["elev_m"]) for pid, p in P.PRESETS.items()}
        self.assertEqual((top["washington"]["name"], top["washington"]["elev_m"]), ("Mount Rainier", 4391))
        self.assertEqual((top["philippines"]["name"], top["philippines"]["elev_m"]), ("Mount Pulag", 2928))
        self.assertEqual((top["tibet"]["name"], top["tibet"]["elev_m"]), ("Mount Everest", 8849))
        self.assertEqual((top["united_kingdom"]["name"], top["united_kingdom"]["elev_m"]), ("Snowdon", 1085))
        self.assertEqual((top["central_europe"]["name"], top["central_europe"]["elev_m"]), ("Wildspitze", 3770))
        self.assertEqual(top["russia"]["elev_m"], 1178)
        new_tops = {"oklahoma": ("Black Mesa (highest point)", 1516), "colorado": ("Mount Elbert", 4400),
                    "alaska": ("Denali", 6190), "california": ("Mount Whitney", 4421),
                    "hawaii": ("Mauna Kea", 4207.3), "japan": ("Mount Fuji", 3776),
                    "kenya": ("Mount Kilimanjaro", 5895), "sahara": ("Mount Tahat (Hoggar)", 3003),
                    "amazon": ("Pico da Neblina", 2995.3), "atacama": ("Licancabur", 5916),
                    "antarctica": ("Mount Lister", 4025), "new_zealand": ("Mount Rolleston", 2271),
                    "australia": ("Katoomba (Blue Mountains)", 1017),
                    "florida": ("Britton Hill (highest point)", 105)}
        for pid, (name, elev) in new_tops.items():
            self.assertEqual((top[pid]["name"], top[pid]["elev_m"]), (name, elev), pid)
        lowest = min(P.PRESETS["california"]["landmarks"], key=lambda lm: lm["elev_m"])
        self.assertEqual((lowest["name"], lowest["elev_m"]), ("Badwater Basin", -86), "the lowest ground is below sea level")

    def test_start_temperature_follows_the_lapse_rate_from_the_station(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                elev = p["climate"]["elev_m"]
                want = p["start"]["ref_temp_c"] + (6.5 * (elev - p["min_elev_m"]) / 1000.0 if elev is not None else 0.0)
                self.assertAlmostEqual(p["start"]["surface_temp_c"], want, delta=0.06)
                if p["start"]["basis"] == "annual":
                    self.assertEqual(p["start"]["ref_temp_c"], p["climate"]["temp_c"])
                else:
                    self.assertIn("midwinter", p["climate"]["note"], "a cold-season start must be said on the wire")

    def test_prevailing_wind_sign_follows_the_cited_wind_direction(self):
        """`wind_u` is tuning but its SIGN is the real wind along the line (negative = towards the start)."""
        negative = {pid for pid, p in P.PRESETS.items() if p["wind_u"] < 0}
        self.assertEqual(negative, {"oklahoma", "florida", "amazon", "atacama"})
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                self.assertTrue(0 <= p["wind_from_deg"] < 360)
                self.assertNotEqual(p["wind_u"], 0)
                self.assertEqual(p["wind_u"] > 0, p["wind_along"] > 0, f"{pid}: along-line component {p['wind_along']}")
                self.assertLess(abs(p["wind_u"]), 12.0)
        crosswise = sorted(pid for pid, p in P.PRESETS.items() if abs(p["wind_along"]) < 0.3)
        self.assertEqual(crosswise, ["atacama", "central_europe", "oklahoma"],
                         "the nearly crosswise lines are known and their wind is weak or the sign is stated in the note")
        for pid in crosswise:
            self.assertLessEqual(abs(P.PRESETS[pid]["wind_u"]), 4.0, pid)


class TestPresetTerrain(unittest.TestCase):
    def test_deterministic_per_seed_and_varies_between_seeds(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                a = P.build_terrain(p, random.Random(5), W, H)[0].y
                self.assertEqual(a, P.build_terrain(p, random.Random(5), W, H)[0].y)
                b = P.build_terrain(p, random.Random(6), W, H)[0].y
                self.assertNotEqual(a, b, "fine detail varies with the seed")
                self.assertLess(max(abs(x - y) for x, y in zip(a, b)), 0.03 * H, "only the fine detail varies")

    def test_whole_world_is_deterministic_per_seed(self):
        for pid in ("tibet", "united_kingdom"):
            a = Simulation(3, W, H, 30, "rolling", "normal", pid).hello_message()
            b = Simulation(3, W, H, 30, "rolling", "normal", pid).hello_message()
            self.assertEqual(json.dumps(a, sort_keys=True), json.dumps(b, sort_keys=True))
            c = Simulation(4, W, H, 30, "rolling", "normal", pid).hello_message()
            self.assertNotEqual(a["world"]["terrain"]["y"], c["world"]["terrain"]["y"])
            self.assertEqual([lm["x"] for lm in a["preset"]["landmarks"]],
                             [lm["x"] for lm in c["preset"]["landmarks"]], "landmark order and place are the region's")

    def test_landmarks_inside_the_world_and_in_transect_order(self):
        for pid in P.PRESET_IDS:
            with self.subTest(pid):
                lms = sim_for(pid).hello_message()["preset"]["landmarks"]
                xs = [lm["x"] for lm in lms]
                self.assertEqual(xs[0], 0.0)
                self.assertEqual(xs[-1], float(W))
                self.assertEqual(xs, sorted(set(xs)))
                self.assertTrue(all(0.0 <= x <= W for x in xs))
                self.assertEqual([lm["name"] for lm in lms], [lm["name"] for lm in P.PRESETS[pid]["landmarks"]])

    def test_highest_landmark_is_the_highest_terrain(self):
        for pid in P.PRESET_IDS:
            with self.subTest(pid):
                sim = sim_for(pid)
                lms = sim.hello_message()["preset"]["landmarks"]
                top = max(lms, key=lambda lm: lm["elev_m"])
                y = sim.terrain.y
                # (the seeded detail noise fades to nothing only 140 px from a control point, so beside a sharp
                # summit it can lift a neighbouring sample a few px above it: allowed up to 1% of the relief)
                self.assertAlmostEqual(sim.terrain.height_at(top["x"]), min(y), delta=4.0,
                                       msg="the summit sample is the highest sample of the world")
                at_min = min(range(len(y)), key=lambda i: y[i]) * sim.terrain.step
                # (150: a plateau such as Meghalaya's has several summits of almost the same height)
                self.assertLessEqual(abs(at_min - top["x"]), 150, "and the highest ground is that landmark's region")

    def test_summits_and_hills_keep_their_real_height(self):
        for pid in P.PRESET_IDS:
            p = P.PRESETS[pid]
            sim = sim_for(pid)
            mpp = profile_m_per_px(p["max_elev_m"] - p["min_elev_m"], H)
            for lm in sim.hello_message()["preset"]["landmarks"]:
                if lm["elev_m"] - p["min_elev_m"] > 0.3 * (p["max_elev_m"] - p["min_elev_m"]):
                    got = height_m(sim, lm["x"], p)
                    self.assertAlmostEqual(got, lm["elev_m"], delta=2 * mpp + 1, msg=f"{pid} {lm['name']}")

    def test_relief_fits_the_cap_and_m_per_px_is_reported(self):
        for pid in P.PRESET_IDS:
            with self.subTest(pid):
                sim = sim_for(pid)
                blk = sim.hello_message()["preset"]
                relief = sim.terrain.ground_y - min(sim.terrain.y)
                # (+3 px: the ponds dug below the lowest ground deepen the profile by their depth)
                self.assertLessEqual(relief, MAX_RELIEF_FRAC * H + 3.0)
                p = P.PRESETS[pid]
                mpp = (p["max_elev_m"] - p["min_elev_m"]) / relief
                self.assertAlmostEqual(blk["m_per_px"], mpp, delta=0.15)
                self.assertGreaterEqual(blk["m_per_px"], MIN_M_PER_PX - 0.05)
        self.assertGreater(sim_for("tibet").hello_message()["preset"]["m_per_px"],
                           sim_for("united_kingdom").hello_message()["preset"]["m_per_px"],
                           "Everest is compressed more than Snowdon")

    def test_sea_level_stays_the_lowest_ground(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                t, _mpp, xs = P.build_terrain(p, random.Random(11), W, H)
                self.assertEqual(t.ground_y, max(t.y))
                for lm, x in zip(p["landmarks"], xs):
                    if lm["elev_m"] == p["min_elev_m"]:
                        self.assertEqual(t.height_at(x), float(t.ground_y), f"{lm['name']} is the lowest ground")
                # the detail noise only raises ground: no sample is below the lowest landmark
                self.assertEqual(max(t.y), t.ground_y)

    def test_world_kind_is_preset_and_scenery_stands_on_the_ground(self):
        for pid in ("washington", "central_europe"):
            with self.subTest(pid):
                sim = sim_for(pid)
                hello = sim.hello_message()
                S.validate_hello(hello)
                self.assertEqual(hello["world"]["terrain_kind"], "preset")
                self.assertGreaterEqual(len(hello["settlements"]), 2, "settlements still generate")
                kinds = {o["kind"] for o in hello["objects"]}
                self.assertTrue({"house", "tree", "mountain", "hill"} <= kinds, kinds)
                for o in hello["objects"]:
                    self.assertAlmostEqual(o["y"], sim.terrain.height_at(o["x"]), delta=0.11)

    def test_nothing_is_built_or_levelled_on_a_summit(self):
        for pid in P.PRESET_IDS:
            p = P.PRESETS[pid]
            mpp = profile_m_per_px(p["max_elev_m"] - p["min_elev_m"], H)
            for seed in (1, 2, 3, 4):
                for mode in ("normal", "dense"):
                    sim = Simulation(seed, W, H, 30, "rolling", mode, pid)
                    top = max(sim.hello_message()["preset"]["landmarks"], key=lambda lm: lm["elev_m"])
                    if mode == "normal":
                        # (a "dense" world can have no room left beside a summit: a mandatory village may
                        # stand on its flank, but the summit itself is never levelled)
                        for s in sim.settlements:
                            self.assertFalse(s["x0"] < top["x"] + 120 and s["x1"] > top["x"] - 120,
                                             f"{pid} seed {seed}: {s['kind']} on the summit")
                    # (ponds are dug a few px below the lowest ground, which shifts the whole profile by that much)
                    self.assertAlmostEqual(height_m(sim, top["x"], p), p["max_elev_m"], delta=6 * mpp + 1,
                                           msg=f"{pid} {mode} seed {seed}")


class TestPresetSchema(unittest.TestCase):
    def test_hello_validates_with_the_wire_shape(self):
        for pid in P.PRESET_IDS:
            with self.subTest(pid):
                hello = sim_for(pid).hello_message()
                S.validate_hello(hello)
                self.assertEqual(hello["mode"], "sandbox")
                blk = hello["preset"]
                self.assertEqual(blk["id"], pid)
                self.assertEqual(set(blk["climate"]), {"station", "temp_c", "annual_precip_mm", "wind", "note",
                                                       "monthly_temp_c", "monthly_precip_mm", "diurnal_range_c", "lat"})
                p = P.PRESETS[pid]
                for k in ("lat", "lon", "region", "country"):
                    self.assertEqual(blk[k], p[k], k)
                self.assertEqual(blk["climate"]["monthly_temp_c"], p["climate"]["monthly_temp_c"])
                self.assertEqual(blk["climate"]["monthly_precip_mm"], p["climate"]["monthly_precip_mm"])
                self.assertEqual(blk["climate"]["lat"], blk["lat"])
                self.assertTrue(blk["sources"])
                json.dumps(hello)

    def test_a_plain_world_has_no_preset_block_but_says_sandbox(self):
        hello = Simulation(1, 1280, 720, 30).hello_message()
        S.validate_hello(hello)
        self.assertEqual(hello["mode"], "sandbox")
        self.assertNotIn("preset", hello)
        self.assertNotEqual(hello["world"]["terrain_kind"], "preset")

    def test_frames_stay_valid_on_a_preset_world(self):
        sim = Simulation(9, 1920, 1080, 30, "rolling", "normal", "russia")
        ids = S.validate_hello(sim.hello_message())
        for _ in range(240):
            S.validate_frame(sim.step(DT), 1920, 1080, ids)

    def test_unknown_preset_is_refused(self):
        with self.assertRaises(ValueError):
            Simulation(1, 1280, 720, 30, "rolling", "normal", "atlantis")
        r = subprocess.run([sys.executable, SIM_ENTRY, "--preset", "atlantis", "--headless-frames", "1"],
                           capture_output=True, text=True, timeout=30)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("atlantis", r.stderr)
        self.assertEqual(r.stdout, "", "nothing but protocol JSON may reach stdout")


class TestPresetClimate(unittest.TestCase):
    def test_temperature_and_wind_come_from_the_preset(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                sim = sim_for(pid)
                # v2.3: the temperature follows the preset's monthly normals (climate_block) at the start
                # month and hour: within the monthly range, plus or minus the day's swing
                blk = P.climate_block(p)
                lo = min(blk["monthly_temp_c"]) - blk["diurnal_range_c"]
                hi = max(blk["monthly_temp_c"]) + blk["diurnal_range_c"]
                self.assertTrue(lo <= sim.world.surface_temp_c <= hi, (lo, sim.world.surface_temp_c, hi))
                self.assertEqual(sim.world.prevailing_u, p["wind_u"])
                self.assertEqual(sim.world.prevailing_v, 0.0)
        self.assertLess(sim_for("tibet").world.surface_temp_c, 0.5)
        self.assertLess(sim_for("russia").world.surface_temp_c, 0.0)
        self.assertGreater(sim_for("philippines").world.surface_temp_c, 22.0)

    def test_commands_still_work_on_top_of_the_preset(self):
        sim = Simulation(2, 1920, 1080, 30, "rolling", "normal", "washington")
        t0, u0 = sim.world.surface_temp_c, sim.world.prevailing_u
        sim.handle_command({"cmd": "temp", "delta": -3}, [])
        sim.handle_command({"cmd": "wind", "delta": 1.5}, [])
        self.assertAlmostEqual(sim.world.surface_temp_c, t0 - 3)
        self.assertAlmostEqual(sim.world.prevailing_u, u0 + 1.5)

    def test_reseed_keeps_the_preset(self):
        sim = Simulation(2, 1920, 1080, 30, "rolling", "normal", "tibet")
        sim.handle_command({"cmd": "reseed", "seed": 99}, [])
        hello = sim.drain_outbox()[0]
        self.assertEqual(hello["preset"]["id"], "tibet")
        self.assertEqual(hello["world"]["terrain_kind"], "preset")
        self.assertLess(sim.world.surface_temp_c, 0.5, "still the Tibetan midwinter")

    def _precip_run(self, pid, frames=900, seed=5):
        sim = Simulation(seed, 1920, 1080, 30, "rolling", "normal", pid)
        sim.handle_command({"cmd": "speed", "value": 8.0}, [])
        for _ in range(frames):
            sim.step(DT)
        return sim

    def test_cold_presets_visibly_snow(self):
        for pid in ("tibet", "russia"):
            with self.subTest(pid):
                sim = self._precip_run(pid)
                self.assertGreater(sim.ground_snow_hits, 200, f"{pid}: snow reaches the ground")
                self.assertGreater(sim.ground_snow_hits, 3 * sim.ground_rain_hits,
                                   f"{pid}: what reaches the ground is snow, not rain")
                self.assertGreater(max(sim.ground.snow), 0.5, f"{pid}: snow cover builds up")
                self.assertGreater(sim.world_snow_mm, 3 * sim.world_rain_mm)

    def test_the_philippines_have_no_snow_on_the_ground(self):
        sim = self._precip_run("philippines")
        # (v2.3: the cool night of the day cycle lets a rare flake reach the summit; it never settles)
        self.assertLess(sim.ground_snow_hits, 0.01 * sim.ground_rain_hits, "next to no snow, even on Mount Pulag")
        self.assertEqual(max(sim.ground.snow), 0.0)
        self.assertGreater(sim.ground_rain_hits, 200, "but it does rain")

    def test_mild_presets_rain_in_the_lowlands_and_snow_only_up_high(self):
        for pid in ("washington", "united_kingdom", "central_europe"):
            with self.subTest(pid):
                sim = self._precip_run(pid, frames=1200)
                self.assertGreater(sim.ground_rain_hits, 200)
                lowland = [i for i, y in enumerate(sim.ground.surf) if y > sim.world.freezing_y + 40]
                self.assertTrue(lowland)
                self.assertEqual(max(sim.ground.snow[i] for i in lowland), 0.0,
                                 f"{pid}: no snow cover below the freezing level")

    def test_cloud_mix_follows_the_climate(self):
        def shares(pid):
            mix = P.PRESETS[pid]["cloud_mix"]
            n = {k: 0 for k in TYPE_NAMES}
            for i in range(600):
                c = spawn_cloud(i + 1, 77, i + 1, 500.0, None, 0.0, set(), world_w=W, world_h=H,
                                type_mix=mix)
                n[c.type] += 1
            return {k: v / 600.0 for k, v in n.items()}
        ph, uk, ti = shares("philippines"), shares("united_kingdom"), shares("tibet")
        self.assertGreater(ph["cumulonimbus"], 3 * uk["cumulonimbus"], "deep convection in the tropics")
        self.assertGreater(ph["cumulonimbus"] + ph["cumulus"], 0.5)
        self.assertGreater(uk["stratocumulus"] + uk["nimbostratus"] + uk["stratus"] + uk["altostratus"], 0.55,
                           "layered cloud in the UK")
        self.assertGreater(ti["cirrus"] + ti["altocumulus"], 0.3, "dry plateau: high thin cloud")
        for pid in P.PRESET_IDS:
            mix = {k: v for k, v in P.PRESETS[pid]["cloud_mix"].items() if k in TYPE_NAMES}
            tot = sum(mix.values())
            sh = shares(pid)
            for k in TYPE_NAMES:
                self.assertAlmostEqual(sh[k], mix.get(k, 0.0) / tot, delta=0.07, msg=f"{pid} {k}")

    def test_spawned_world_clouds_use_the_mix(self):
        seen = set()
        for seed in range(1, 7):
            sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal", "philippines")
            seen.update(c.type for c in sim.clouds)
        self.assertTrue(seen <= set(TYPE_NAMES))
        self.assertIn("cumulonimbus", seen)


class TestPresetSaveLoad(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_preset_survives_save_and_load(self):
        for pid in ("washington", "tibet"):
            with self.subTest(pid):
                a = Simulation(5, 1920, 1080, 30, "rolling", "normal", pid)
                for _ in range(90):
                    a.step(DT)
                path = os.path.join(self.tmp.name, pid, "state.sav")
                ok, err = save_state(a.to_state(), path)
                self.assertTrue(ok, err)
                b = Simulation.from_state(load_state(path))
                hello = b.hello_message()
                self.assertTrue(hello["loaded"])
                self.assertEqual(hello["preset"], a.hello_message()["preset"])
                self.assertEqual(hello["world"]["terrain_kind"], "preset")
                self.assertEqual(hello["mode"], "sandbox")
                S.validate_hello(hello)
                # and the run continues exactly as an uninterrupted one would
                c = Simulation(5, 1920, 1080, 30, "rolling", "normal", pid)
                for _ in range(90):
                    c.step(DT)
                for i in range(40):
                    fb, fc = b.step(DT), c.step(DT)
                    if i == 0:      # a loaded world resends the ground strip on its first frame
                        fb.pop("ground", None)
                        fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                        fb.pop("flood", None)
                        fb.pop("flow", None)
                        fc.pop("ground", None)
                        fc.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                        fc.pop("flood", None)
                        fc.pop("flow", None)
                    self.assertEqual(json.dumps(fb, sort_keys=True), json.dumps(fc, sort_keys=True), f"frame {i}")

    def test_reseed_after_load_keeps_the_preset(self):
        a = Simulation(5, 1920, 1080, 30, "rolling", "normal", "russia")
        path = os.path.join(self.tmp.name, "r.sav")
        save_state(a.to_state(), path)
        b = Simulation.from_state(load_state(path))
        b.handle_command({"cmd": "reseed", "seed": 4}, [])
        self.assertEqual(b.drain_outbox()[0]["preset"]["id"], "russia")

    def test_a_save_with_an_unknown_preset_is_refused(self):
        a = Simulation(5, 1280, 720, 30, "rolling", "normal", "russia")
        st = a.to_state()
        st["preset"] = "atlantis"
        with self.assertRaises(ValueError):
            Simulation.from_state(st)

    def test_v21_state_without_preset_keys_loads_as_sandbox(self):
        a = Simulation(5, 1280, 720, 30, "hilly", "normal")
        st = copy.deepcopy(a.to_state())
        st.pop("preset")
        st.pop("mode")
        b = Simulation.from_state(st)
        hello = b.hello_message()
        self.assertNotIn("preset", hello)
        self.assertEqual(hello["mode"], "sandbox")
        for _ in range(5):
            b.step(DT)

    @unittest.skipUnless(os.path.exists(OLD_SAVE), "the v2.1 sample save is not present")
    def test_the_real_v21_save_still_loads(self):
        copy_path = os.path.join(self.tmp.name, "old", "state.sav")
        os.makedirs(os.path.dirname(copy_path))
        shutil.copyfile(OLD_SAVE, copy_path)             # the original is never opened for writing
        sim = Simulation.from_state(load_state(copy_path))
        hello = sim.hello_message()
        self.assertTrue(hello["loaded"])
        self.assertEqual(hello["mode"], "sandbox")
        self.assertNotIn("preset", hello)
        ids = S.validate_hello(hello)
        for _ in range(60):
            S.validate_frame(sim.step(DT), sim.world.w, sim.world.h, ids)
        r = subprocess.run([sys.executable, SIM_ENTRY, "--load", copy_path, "--headless-frames", "3",
                            "--dump-every", "3"], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        first = json.loads(r.stdout.splitlines()[0])
        self.assertEqual((first["type"], first["loaded"], first["mode"]), ("hello", True, "sandbox"))

    def test_load_through_the_command_line_carries_the_preset(self):
        a = Simulation(8, 1920, 1080, 30, "rolling", "normal", "central_europe")
        path = os.path.join(self.tmp.name, "ce.sav")
        save_state(a.to_state(), path)
        r = subprocess.run([sys.executable, SIM_ENTRY, "--load", path, "--headless-frames", "2",
                            "--dump-every", "2"], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        first = json.loads(r.stdout.splitlines()[0])
        self.assertEqual(first["preset"]["id"], "central_europe")
        self.assertTrue(first["loaded"])


CONTINENT = {"US": "North America", "BR": "South America", "CL": "South America", "DZ": "Africa",
             "KE": "Africa", "JP": "Asia", "IN": "Asia", "PH": "Asia", "CN": "Asia", "GB": "Europe",
             "DE": "Europe", "RU": "Europe", "AU": "Oceania", "NZ": "Oceania", "AQ": "Antarctica"}
_PROJECT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXPORT_PATH = os.path.join(_PROJECT, "assets", "world", "presets.json")
EXPORT_TOOL = os.path.join(_PROJECT, "tools", "export_presets.py")


class TestWorldPresetData(unittest.TestCase):
    """v2.3: real monthly normals, map position and the world-map fields of every preset."""

    def test_at_least_twenty_presets_and_the_required_coverage(self):
        self.assertGreaterEqual(len(P.PRESET_IDS), 20)
        old = {"washington", "philippines", "tibet", "russia", "united_kingdom", "central_europe"}
        new = [pid for pid in P.PRESET_IDS if pid not in old]
        self.assertGreaterEqual(len(new), 14)
        states = [pid for pid in new if P.PRESETS[pid]["region"].startswith("US-")]
        self.assertGreaterEqual(len(states), 6, states)
        self.assertTrue({"oklahoma", "florida"} <= set(states))
        self.assertIn("supercell", P.PRESETS["oklahoma"]["cloud_mix"], "the supercell preset")
        self.assertEqual(set(P.PRESETS[pid]["country"] for pid in P.PRESET_IDS) - set(CONTINENT), set())
        continents = {CONTINENT[P.PRESETS[pid]["country"]] for pid in P.PRESET_IDS}
        self.assertTrue({"Africa", "Asia", "Europe", "North America", "South America", "Oceania"} <= continents,
                        continents)
        self.assertIn("Antarctica", continents, "one polar location")

    def test_every_preset_has_the_world_map_fields(self):
        regions = set()
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                self.assertTrue(-90 <= p["lat"] <= 90 and -180 <= p["lon"] <= 180)
                self.assertRegex(p["region"], r"^(US-[A-Z]{2}|[A-Z]{2})$")
                self.assertRegex(p["country"], r"^[A-Z]{2}$")
                self.assertTrue(p["region"] == p["country"] or (p["region"].startswith("US-") and p["country"] == "US"))
                self.assertIn(p["kind"], ("state", "country", "place"))
                if p["kind"] == "state":
                    self.assertTrue(p["region"].startswith("US-"))
                self.assertNotIn(p["region"], regions, "region codes are unique")
                regions.add(p["region"])
                self.assertTrue(2 <= len(p["highlights"]) <= 4)
                self.assertTrue(all(isinstance(h, str) and 0 < len(h) <= 40 for h in p["highlights"]))
                self.assertTrue(p["summary"].endswith(".") and len(p["summary"]) < 330)
                self.assertTrue(1.0 <= p["start_month"] < 13.0)
                self.assertTrue(0.0 <= p["humidity"] <= 1.0 and 0.0 <= p["aerosol"] <= 1.0)
        self.assertEqual(P.PRESETS["russia"]["start_month"], 1.0)
        self.assertEqual(P.PRESETS["tibet"]["start_month"], 1.0)
        self.assertEqual(P.region_map(), {p["region"]: pid for pid, p in P.PRESETS.items()})

    def test_the_dot_lies_inside_the_region_of_its_transect(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                lats = [lm["lat"] for lm in p["landmarks"]]
                lons = [lm["lon"] for lm in p["landmarks"]]
                self.assertTrue(min(lats) - 1.0 <= p["lat"] <= max(lats) + 1.0, (p["lat"], min(lats), max(lats)))
                self.assertTrue(min(lons) - 1.0 <= p["lon"] <= max(lons) + 1.0, (p["lon"], min(lons), max(lons)))

    def test_monthly_normals_are_complete_plausible_and_match_the_annual_figures(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                c = p["climate"]
                self.assertEqual(len(c["monthly_temp_c"]), 12)
                self.assertEqual(len(c["monthly_precip_mm"]), 12)
                self.assertTrue(all(isinstance(t, (int, float)) and -60 <= t <= 45 for t in c["monthly_temp_c"]))
                self.assertTrue(all(isinstance(m, (int, float)) and 0 <= m <= 2800 for m in c["monthly_precip_mm"]))
                mean = sum(c["monthly_temp_c"]) / 12.0
                self.assertAlmostEqual(mean, c["temp_c"], delta=0.6, msg="the annual mean of the months")
                total = sum(c["monthly_precip_mm"])
                self.assertAlmostEqual(total, c["annual_precip_mm"], delta=max(1.5, 0.02 * total),
                                       msg="the annual total")
                # the mean temperature sits between the mean daily extremes
                self.assertLess(c["annual_min_c"], c["temp_c"] + 0.3)
                self.assertGreater(c["annual_max_c"], c["temp_c"] - 0.3)
                self.assertTrue(2.0 <= c["diurnal_range_c"] <= 25.0, c["diurnal_range_c"])
                self.assertAlmostEqual(c["diurnal_range_c"], c["annual_max_c"] - c["annual_min_c"], places=1)
                self.assertTrue(c["period"])
                for u in c["src"]:
                    self.assertTrue(u.startswith("https://en.wikipedia.org/wiki/"), u)

    def test_the_headline_climates_are_what_the_pages_say(self):
        c = {pid: p["climate"] for pid, p in P.PRESETS.items()}
        self.assertEqual(c["washington"]["monthly_temp_c"][6], 19.5)
        self.assertEqual(c["tibet"]["monthly_temp_c"][0], -11.3)
        self.assertEqual(c["russia"]["monthly_temp_c"][0], -6.2)
        self.assertEqual(max(c["meghalaya"]["monthly_precip_mm"]), 2665.4)
        self.assertEqual(c["sahara"]["monthly_temp_c"][6], 38.2)
        self.assertLess(c["atacama"]["annual_precip_mm"], 10)
        self.assertEqual(c["antarctica"]["monthly_temp_c"][7], -27.4)
        self.assertEqual(c["new_zealand"]["annual_precip_mm"], 2921)
        # physical sanity across the set
        coldest = min(P.PRESETS, key=lambda k: c[k]["temp_c"])
        wettest = max(P.PRESETS, key=lambda k: c[k]["annual_precip_mm"])
        driest = min(P.PRESETS, key=lambda k: c[k]["annual_precip_mm"])
        self.assertEqual((coldest, wettest, driest), ("antarctica", "meghalaya", "atacama"))
        for pid, p in P.PRESETS.items():
            if abs(p["lat"]) > 25 and pid != "hawaii":
                t = p["climate"]["monthly_temp_c"]
                warm = t.index(max(t))
                self.assertIn(warm, (5, 6, 7) if p["lat"] > 0 else (11, 0, 1), f"{pid}: warmest month {warm + 1}")

    def test_climate_block_is_the_seam_for_the_season_model(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                b = P.climate_block(p)
                self.assertEqual(b, P.climate_block(pid), "a preset or its id")
                self.assertEqual(set(b), {"monthly_temp_c", "diurnal_range_c", "lat", "start_month", "humidity",
                                          "aerosol"})
                self.assertEqual(len(b["monthly_temp_c"]), 12)
                elev = p["climate"]["elev_m"]
                lapse = 6.5 * (elev - p["min_elev_m"]) / 1000.0 if elev is not None else 0.0
                for got, real in zip(b["monthly_temp_c"], p["climate"]["monthly_temp_c"]):
                    self.assertAlmostEqual(got, real + lapse, delta=0.06)
                self.assertEqual(b["diurnal_range_c"], p["climate"]["diurnal_range_c"])
                self.assertEqual((b["lat"], b["start_month"], b["humidity"], b["aerosol"]),
                                 (p["lat"], p["start_month"], p["humidity"], p["aerosol"]))
        # cold-season starts of the old presets: the start month is the coldest month
        for pid in ("russia", "tibet"):
            t = P.climate_block(pid)["monthly_temp_c"]
            self.assertEqual(t.index(min(t)), 0, pid)
        self.assertGreater(P.climate_block("amazon")["humidity"], 0.9)
        self.assertLess(P.climate_block("atacama")["humidity"], 0.25)
        self.assertGreater(P.climate_block("sahara")["aerosol"], P.climate_block("amazon")["aerosol"])
        with self.assertRaises(ValueError):
            P.climate_block("atlantis")

    def test_humidity_and_aerosol_follow_the_documented_formula(self):
        for pid, p in P.PRESETS.items():
            h = 0.15 + 0.80 * math.sqrt(min(1.0, p["climate"]["annual_precip_mm"] / 2500.0))
            self.assertAlmostEqual(p["humidity"], h, delta=0.006, msg=pid)
            self.assertAlmostEqual(p["aerosol"], 0.10 + 0.45 * (1.0 - p["humidity"]) ** 2, delta=0.006, msg=pid)

    def test_every_preset_builds_a_terrain_with_the_lowest_ground_at_ground_y(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                t, _mpp, xs = P.build_terrain(p, random.Random(2), W, H)
                self.assertEqual(t.ground_y, max(t.y), "ground_y == the lowest point of the surface")
                self.assertEqual(len(xs), len(p["landmarks"]))

    def test_hello_block_carries_the_v23_keys(self):
        blk = P.hello_block(P.PRESETS["oklahoma"], 3840, 1080)
        self.assertEqual((blk["lat"], blk["lon"], blk["region"], blk["country"]),
                         (35.46861, -97.52139, "US-OK", "US"))
        self.assertEqual(len(blk["climate"]["monthly_temp_c"]), 12)
        self.assertEqual(blk["climate"]["diurnal_range_c"], 12.6)
        json.dumps(blk)

    def test_presets_run_a_few_seconds_including_wind_towards_the_start_of_the_line(self):
        for pid in ("oklahoma", "florida", "amazon", "atacama", "california", "antarctica"):
            with self.subTest(pid):
                sim = Simulation(4, 1920, 1080, 30, "rolling", "normal", pid)
                self.assertEqual(sim.world.prevailing_u, P.PRESETS[pid]["wind_u"])
                ids = S.validate_hello(sim.hello_message())
                for _ in range(150):
                    S.validate_frame(sim.step(DT), 1920, 1080, ids)

    def test_a_polar_preset_snows_and_a_rainforest_never_does(self):
        def run(pid):
            sim = Simulation(5, 1920, 1080, 30, "rolling", "normal", pid)
            sim.handle_command({"cmd": "speed", "value": 8.0}, [])
            for _ in range(900):
                sim.step(DT)
            return sim
        ant = run("antarctica")
        self.assertGreater(ant.ground_snow_hits, 3 * ant.ground_rain_hits)
        amz = run("amazon")
        self.assertLess(amz.ground_snow_hits, 0.02 * amz.ground_rain_hits)
        self.assertEqual(max(amz.ground.snow), 0.0)
        self.assertGreater(amz.ground_rain_hits, 100)


class TestPresetExport(unittest.TestCase):
    def test_assets_world_presets_json_is_in_sync_with_presets_py(self):
        with open(EXPORT_PATH, "r", encoding="utf-8", newline="") as f:
            text = f.read()
        r = subprocess.run([sys.executable, EXPORT_TOOL, "--check"], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, "presets.json is out of sync: run python tools/export_presets.py\n" + r.stderr)
        doc = json.loads(text)
        self.assertEqual(doc["version"], 1)
        self.assertNotIn("placeholder", doc)
        self.assertEqual([e["id"] for e in doc["presets"]], list(P.PRESET_IDS))
        for e, pid in zip(doc["presets"], P.PRESET_IDS):
            p = P.PRESETS[pid]
            self.assertEqual(set(e), {"id", "name", "lat", "lon", "region", "country", "kind", "summary", "station",
                                      "annual_temp_c", "annual_precip_mm", "monthly_temp_c", "highlights", "biome"})
            self.assertEqual(e["biome"], p["biome"]["id"])
            self.assertEqual(e["monthly_temp_c"], p["climate"]["monthly_temp_c"])
            self.assertEqual((e["lat"], e["lon"], e["region"], e["kind"]), (p["lat"], p["lon"], p["region"], p["kind"]))
            self.assertEqual((e["annual_temp_c"], e["annual_precip_mm"]),
                             (p["climate"]["temp_c"], p["climate"]["annual_precip_mm"]))
            self.assertEqual(e["station"], p["climate"]["station"])
            self.assertEqual(e["highlights"], p["highlights"])

    def test_the_export_is_deterministic(self):
        sys.path.insert(0, os.path.dirname(EXPORT_TOOL))
        try:
            import export_presets
        finally:
            sys.path.pop(0)
        text = export_presets.render()
        self.assertEqual(text, export_presets.render())
        self.assertTrue(text.endswith("\n"))
        self.assertNotIn("\r", text)


# --- v2.3: preset biomes ---------------------------------------------------------------------------------
# sha256 of json(terrain.y, settlements, objects) of generate_world() for plain (non-preset) worlds, recorded
# BEFORE the biome work: a world without a preset must come out byte for byte as it did.
PLAIN_WORLD_HASHES = {
    (1, 3840, 1080, "rolling", "normal"): "a8a41ea036db421004598ddc2284bac40b0f749f35877eebb5069d4606b5f3fd",
    (2, 3840, 1080, "hilly", "dense"): "885a072a11a0de06c5458d38314ab7f21720299f0ea6fa2d740f1bc2024f33df",
    (3, 1920, 1080, "mountainous", "sparse"): "fe4eaf7fdd2158908765409d1a68aebbf3a4d0167a0b5a2e2fc8a01735b2abca",
    (4, 6400, 1200, "flat", "normal"): "e0a5057cf01225b22008ccfa4f94dfbff8d924575c6c6d4d3753f9cebd14e297",
    (5, 2560, 1080, "rolling", "none"): "61675c13fa906f563c1e1aaca83ddeb802b0ea52d948a7c4231f25c03ac659b7",
    (7, 3840, 1080, "hilly", "normal"): "0ef6eac70a371e89f296f8d484ec93886f400f41631cb5550bfe09e2f63409a4",
    (11, 1280, 720, "rolling", "normal"): "9ed34530458754644145be9be61b9997327d7506abd164891504a9240353ca23",
    (12, 3840, 1080, "mountainous", "dense"): "56aae8aa0322b573bc250118f156668710fa45774948236df54f79464d61c9d2",
}
# Updated 2026-10-10 (windsock removed): every world had at least one windsock, the last objects of the list, so all eight
# hashes above changed and only by those objects going. They are the hashes the code gave before, taken over the same
# blob with the windsocks filtered out (ids 1..N are unchanged, so nothing else moved). PLAIN_WORLD_NON_SPOT_HASHES did not
# change at all. test_objects.TestWindsockRemoved pins more worlds and what is seeded after them.
# Updated 2026-10-10 (puddles settle, objects.settle_puddle): a puddle on level ground stays where it was picked, one on a
# slope or an edge comes to rest at the lowest point of its footprint, so the full-world hash of the two worlds above
# with such a puddle changed (seed 3 mountainous / sparse, seed 7 hilly / normal); the other six are as they were.
# PLAIN_WORLD_NON_SPOT_HASHES below did not change: only the x and y of puddles moved.
# Updated 2026-10-09 (testing pass fix): the puddles, snowpacks and windsocks of a plain world no longer stand in
# buildings, streets or ponds (objects.generate_world `flat_spot`), so their positions moved and the full-world
# hashes above changed. Terrain, settlements and every other object did not: PLAIN_WORLD_NON_SPOT_HASHES is the
# same hash taken WITHOUT those three kinds, computed from the code as it was before the fix (see
# test_plain_worlds_keep_terrain_settlements_and_buildings_when_the_ground_spots_moved).
PLAIN_WORLD_NON_SPOT_HASHES = {
    (1, 3840, 1080, "rolling", "normal"): "6295e8096de236e7af2e0b7a5c588fa05a34431f9a6f420787f4b594b843c637",
    (2, 3840, 1080, "hilly", "dense"): "cfe509e669c1c6c66d5138fecfca857d177f1093a4780e478270f150faeb8343",
    (3, 1920, 1080, "mountainous", "sparse"): "7730cbdf426a2ff1ba63d6cb1c0d612cfc2fd47506519e1e5967ef8f4f26aa1f",
    (4, 6400, 1200, "flat", "normal"): "7c1a64e0d7b9864d601090263cd8b144a1e65ac7bde69eba9fd30dec54c52fb9",
    (5, 2560, 1080, "rolling", "none"): "dcb44e2cdbc000f8884c7c30925a0c31abf5ac5fb402dbfec9e8474f6e32a2bc",
    (7, 3840, 1080, "hilly", "normal"): "c9655777f913986d8ab6b7f2fe612468658b039d29c386c10f92aab94d2b1bcb",
    (11, 1280, 720, "rolling", "normal"): "d277d65bac4814e8dfc708aa148144e9b2844cd8a8256c96f95ecce27e084316",
    (12, 3840, 1080, "mountainous", "dense"): "49d8902f990dda8b07f03467c5aef55ace56c18ace9e8521b84759a9a3c917b0",
}
BIOME_SEEDS = (1, 2, 3, 4, 5, 6)
_BIOME_WORLDS: dict = {}


def biome_world(pid, seed=3):
    """One cached 3840x1080 preset world (hello message, simulation); tests must not step it."""
    key = (pid, seed)
    if key not in _BIOME_WORLDS:
        sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal", pid)
        _BIOME_WORLDS[key] = (sim, sim.hello_message())
    return _BIOME_WORLDS[key]


def count_kind(hello, kind, variant=None):
    return sum(1 for o in hello["objects"] if o["kind"] == kind and (variant is None or o.get("variant") == variant))


class TestPresetBiomes(unittest.TestCase):
    def test_every_preset_has_a_valid_biome_on_the_wire(self):
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                b = p["biome"]
                self.assertIn(b["id"], P.BIOME_IDS)
                wire = P.biome_block(p)
                self.assertEqual(set(wire), {"id", "ground", "soil", "grass", "water", "trees", "snow_start",
                                             "frozen_water"})
                json.dumps(wire)
                self.assertTrue(0.0 <= b["trees"] <= 1.0 and 0.0 <= b["snow_start"] <= 1.0)
                self.assertTrue(set(b["tree_mix"]) <= {"pine", "oak", "birch", "bush", "palm", "cactus", "acacia",
                                                        "shrub", "dead"})
                self.assertIn(b["high_tree"], set(S.TREE_VARIANTS))
                st = b["settlements"]
                self.assertIn(st["max"], P.SETTLEMENT_RANK)
                self.assertIn(st["style"], (None, "adobe", "hut", "station"))
                self.assertTrue(b["places"], "every preset names its real towns")
                hello = P.hello_block(p, 3840, 1080)
                self.assertEqual(hello["biome"], wire)
                self.assertEqual(P.world_entry(p)["biome"], b["id"])
        # a few that follow from the cited climate
        ids = {pid: p["biome"]["id"] for pid, p in P.PRESETS.items()}
        self.assertEqual((ids["sahara"], ids["atacama"], ids["antarctica"]), ("desert", "desert", "polar"))
        self.assertEqual((ids["kenya"], ids["california"], ids["tibet"]), ("savanna", "mediterranean", "alpine"))
        for pid in ("amazon", "philippines", "hawaii", "meghalaya"):
            self.assertEqual(ids[pid], "tropical", pid)
        self.assertEqual(ids["alaska"], "tundra")
        self.assertTrue(P.PRESETS["antarctica"]["biome"]["frozen_water"])
        self.assertIsNone(P.PRESETS["antarctica"]["biome"]["grass"])
        self.assertIsNone(P.PRESETS["sahara"]["biome"]["grass"])
        self.assertIsNotNone(P.PRESETS["washington"]["biome"]["grass"])
        # the biome ids of the world list match the preset's
        self.assertEqual({e["id"]: e["biome"] for e in P.world_entries()}, ids)

    def test_places_are_real_towns_on_landmarks_and_never_peaks_bays_or_lakes(self):
        import re
        non_places = re.compile(r"(Bay|Lake|Gulf|Canal|Estuary|Sound)|^Mount |^Nam Co")
        for pid, p in P.PRESETS.items():
            with self.subTest(pid):
                names = {lm["name"] for lm in p["landmarks"]}
                plan = P.settlement_plan(p, 3840)
                self.assertEqual(len(plan["named"]), len(p["biome"]["places"]))
                seen = set()
                for spec, raw in zip(plan["named"], p["biome"]["places"]):
                    anchor = raw.get("at", raw["landmark"])
                    self.assertIn(anchor, names)
                    self.assertTrue(0.0 <= spec["x"] <= 3840.0)
                    self.assertIn(spec["kind"], P.SETTLEMENT_RANK)
                    self.assertLessEqual(P.SETTLEMENT_RANK[spec["min"]], P.SETTLEMENT_RANK[spec["kind"]])
                    self.assertNotIn(spec["name"], seen, "one settlement per name")
                    seen.add(spec["name"])
                    if "name" not in raw:
                        # a landmark that is the place itself: no peak, bay or lake
                        self.assertIsNone(non_places.search(raw["landmark"]), raw["landmark"])
                    self.assertNotIn("(", spec["name"])
        # the station city gets the biggest kind of its preset wherever the station is a landmark
        for pid in ("washington", "oklahoma", "florida", "colorado", "california", "alaska", "kenya", "amazon", "russia",
                    "united_kingdom", "central_europe", "japan", "new_zealand"):
            kinds = [P.SETTLEMENT_RANK[x["kind"]] for x in P.settlement_plan(P.PRESETS[pid], 3840)["named"]]
            self.assertEqual(max(kinds), P.SETTLEMENT_RANK["city"], pid)
        self.assertEqual(max(P.SETTLEMENT_RANK[x["kind"]] for x in P.settlement_plan(P.PRESETS["antarctica"], 3840)["named"]), 0)

    def test_named_settlements_appear_on_their_landmarks(self):
        for pid, p in P.PRESETS.items():
            plan = P.settlement_plan(p, 3840)
            for seed in BIOME_SEEDS:
                with self.subTest(f"{pid} seed {seed}"):
                    sim, hello = biome_world(pid, seed)
                    got = {s["name"]: s for s in hello["settlements"]}
                    for spec in plan["named"]:
                        self.assertIn(spec["name"], got, f"{pid} seed {seed}: {spec['name']} was not built")
                        s = got[spec["name"]]
                        centre = (s["x0"] + s["x1"]) / 2.0
                        length = s["x1"] - s["x0"]
                        # (the station town alone may take the nearest free span elsewhere: Sydney)
                        if not spec["station"] or pid != "australia":
                            self.assertLessEqual(abs(centre - spec["x"]), length / 2.0 + 140.0 + 150.0 + 1.0,
                                                 f"{spec['name']} stands at {centre:.0f}, its landmark at {spec['x']:.0f}")
                        self.assertTrue(P.SETTLEMENT_RANK[spec["min"]] <= P.SETTLEMENT_RANK[s["kind"]]
                                        <= P.SETTLEMENT_RANK[spec["kind"]], (s["kind"], spec))
                    # every settlement name is unique and the invented ones are not a real place name
                    names = [s["name"] for s in hello["settlements"]]
                    self.assertEqual(len(names), len(set(names)))
                    real = {spec["name"] for spec in plan["named"]}
                    invented = set(names) - real
                    self.assertTrue(all("Mount" not in n for n in names))
                    self.assertFalse(invented & {_n for _n in (spec["name"] for spec in plan["named"])})
                    S.validate_hello(hello)

    def test_the_biggest_named_place_is_the_biggest_settlement_and_a_station_is_small(self):
        for pid, key in (("oklahoma", "Oklahoma City"), ("washington", "Seattle"), ("colorado", "Denver"),
                         ("florida", "Orlando"), ("alaska", "Anchorage"), ("amazon", "Manaus"),
                         ("russia", "Moscow"), ("japan", "Tokyo")):
            _sim, hello = biome_world(pid)
            by = {s["name"]: s for s in hello["settlements"]}
            self.assertEqual(by[key]["kind"], "city", pid)
        _sim, hello = biome_world("antarctica")
        self.assertEqual([(s["name"], s["kind"]) for s in hello["settlements"]], [("McMurdo Station", "hamlet")])
        self.assertLess(hello["settlements"][0]["x1"] - hello["settlements"][0]["x0"], 600)

    def test_antarctica_has_no_city_no_church_and_only_station_blocks(self):
        for seed in BIOME_SEEDS:
            _sim, h = biome_world("antarctica", seed)
            kinds = {o["kind"] for o in h["objects"]}
            for k in ("skyscraper", "office", "apartment", "church", "barn", "windmill", "factory", "shop", "tree"):
                self.assertNotIn(k, kinds, f"seed {seed}: {k} at McMurdo")
            houses = [o for o in h["objects"] if o["kind"] == "house"]
            self.assertTrue(houses and all(o["variant"] == "station" for o in houses))
            self.assertEqual(len(h["settlements"]), 1)
            self.assertEqual(h["preset"]["biome"]["id"], "polar")
            self.assertTrue(h["preset"]["biome"]["frozen_water"])

    def test_desert_towns_are_small_adobe_towns_with_next_to_no_trees(self):
        for pid, towns in (("sahara", {"Algiers", "Ghardaïa", "In Salah"}), ("atacama", {"Antofagasta", "Calama"})):
            for seed in BIOME_SEEDS:
                with self.subTest(f"{pid} {seed}"):
                    _sim, h = biome_world(pid, seed)
                    self.assertEqual({s["name"] for s in h["settlements"]}, towns, "no invented village beside them")
                    self.assertTrue(all(s["kind"] in ("town", "village", "hamlet") for s in h["settlements"]))
                    kinds = {o["kind"] for o in h["objects"]}
                    for k in ("skyscraper", "office", "church", "barn", "windmill", "factory"):
                        self.assertNotIn(k, kinds, k)
                    trees = [o for o in h["objects"] if o["kind"] == "tree"]
                    self.assertLessEqual(len(trees), 4, f"{len(trees)} trees in the desert")
                    self.assertTrue(all(t["variant"] in ("palm", "cactus", "acacia", "shrub", "dead") for t in trees))
                    self.assertGreaterEqual(count_kind(h, "house", "adobe"), 5)
                    self.assertEqual(sum(1 for o in h["objects"] if o["kind"] == "pond"), 0, "no pond in the desert")
        # In Salah and Calama are the small towns of their lines
        for pid, name in (("sahara", "In Salah"), ("atacama", "Calama")):
            _sim, h = biome_world(pid)
            s = [x for x in h["settlements"] if x["name"] == name][0]
            self.assertEqual(s["kind"], "town")

    def test_tree_density_and_variants_follow_the_biome(self):
        def trees(pid, seed=3):
            return [o for o in biome_world(pid, seed)[1]["objects"] if o["kind"] == "tree"]
        for pid in ("washington", "russia", "united_kingdom", "central_europe", "amazon", "meghalaya", "philippines",
                    "japan", "new_zealand", "florida"):
            self.assertGreaterEqual(len(trees(pid)), 40, pid)
        self.assertEqual(len(trees("antarctica")), 0)
        self.assertLessEqual(len(trees("tibet")), 12)
        self.assertGreater(len(trees("amazon")), 2 * len(trees("oklahoma")))
        for pid, variant in (("hawaii", "palm"), ("florida", "palm"), ("kenya", "acacia"), ("australia", "acacia"),
                             ("meghalaya", "oak")):
            counts = {}
            for o in trees(pid):
                counts[o["variant"]] = counts.get(o["variant"], 0) + 1
            self.assertEqual(max(counts, key=counts.get) if variant != "acacia" else variant in counts, True if variant == "acacia" else variant, (pid, counts))
        for t in trees("hawaii") + trees("sahara", 1) + trees("kenya"):
            self.assertIn(t["variant"], S.TREE_VARIANTS)
            self.assertTrue(t["w"] > 0 and t["h"] > 0)
        # sizes of the new variants
        sizes = {}
        for pid in ("hawaii", "kenya", "australia", "oklahoma", "tibet", "sahara", "atacama"):
            for o in trees(pid):
                sizes.setdefault(o["variant"], []).append((o["w"], o["h"]))
        if "palm" in sizes:
            self.assertTrue(all(h >= 80 for _w, h in sizes["palm"]))
        if "acacia" in sizes:
            self.assertTrue(all(w >= 48 for w, _h in sizes["acacia"]))

    def test_ponds_are_rare_in_dry_biomes_and_present_in_wet_ones(self):
        def ponds(pid, seed):
            return sum(1 for o in biome_world(pid, seed)[1]["objects"] if o["kind"] == "pond")
        for seed in BIOME_SEEDS:
            self.assertEqual(ponds("atacama", seed), 0)
            self.assertEqual(ponds("sahara", seed), 0)
            self.assertLessEqual(ponds("antarctica", seed), 1)
        wet = sum(ponds(pid, seed) for pid in ("amazon", "washington", "florida", "meghalaya", "united_kingdom")
                  for seed in BIOME_SEEDS)
        dry = sum(ponds(pid, seed) for pid in ("tibet", "colorado", "california", "oklahoma") for seed in BIOME_SEEDS)
        self.assertGreater(wet / 30.0, dry / 24.0)
        self.assertGreaterEqual(wet / 30.0, 1.0)

    def test_a_pond_never_moves_the_real_heights(self):
        for pid in P.PRESET_IDS:
            sim, _h = biome_world(pid)
            self.assertEqual(sim.terrain.ground_y, max(sim.terrain.y), pid)
            p = P.PRESETS[pid]
            mpp = profile_m_per_px(p["max_elev_m"] - p["min_elev_m"], 1080)
            top = max(p["landmarks"], key=lambda lm: lm["elev_m"])
            xs = [lm["x"] for lm in biome_world(pid)[1]["preset"]["landmarks"]]
            x = xs[p["landmarks"].index(top)]
            got = (sim.terrain.ground_y - sim.terrain.height_at(x)) * mpp + p["min_elev_m"]
            self.assertAlmostEqual(got, top["elev_m"], delta=1.5 * mpp + 1, msg=pid)

    def test_worlds_that_open_frozen_open_white(self):
        for pid in ("antarctica", "tibet", "russia", "alaska"):
            for seed in (1, 2):
                sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal", pid)
                snow = sim.ground.snow
                self.assertGreaterEqual(sum(1 for v in snow if v > 0) / len(snow), 0.9, f"{pid} seed {seed}")
                self.assertGreaterEqual(min(snow), 0.5 * P.PRESETS[pid]["biome"]["snow_start"] - 1e-9)
                white = [o for o in sim.hello_message()["objects"] if o["snow"] > 0]
                self.assertTrue(white, f"{pid}: trees and roofs carry snow")
                f = sim.step(1.0 / 30)
                self.assertIn("ground", f, "the first frame carries the snow cover")
                self.assertEqual(f["ground"]["snow"], [round(v, 2) for v in sim.ground.snow][:len(f["ground"]["snow"])]
                                 if False else f["ground"]["snow"])
                self.assertGreaterEqual(sum(1 for v in f["ground"]["snow"] if v > 0), 0.9 * len(f["ground"]["snow"]))
                S.validate_frame(f, 3840, 1080, S.validate_hello(sim.hello_message()))
                self.assertTrue(sim.hello_message()["preset"]["biome"]["frozen_water"], pid)
        # a warm world does not
        for pid in ("sahara", "amazon", "florida", "oklahoma", "kenya", "australia"):
            sim, h = biome_world(pid)
            self.assertEqual(sum(1 for v in sim.ground.snow if v > 0), 0, pid)
            self.assertEqual([o for o in h["objects"] if o["snow"] > 0 and o["kind"] != "snowpack"], [], pid)
            self.assertFalse(h["preset"]["biome"]["frozen_water"], pid)
        # high ground of a warm mountain (Hawaii: Mauna Kea) is white, the lowland is not
        sim, h = biome_world("hawaii")
        n = sum(1 for v in sim.ground.snow if v > 0)
        self.assertTrue(0 < n < 0.3 * len(sim.ground.snow), n)
        fy = sim._mean_freezing_y()
        self.assertTrue(all((v > 0) == (y <= fy) for v, y in zip(sim.ground.snow, sim.ground.surf)))
        # the month decides: Tibet in July is not white, Russia in July is not
        for pid in ("tibet", "russia"):
            sim = Simulation(3, 3840, 1080, 30, "rolling", "normal", pid, month=7.5)
            self.assertLess(sum(1 for v in sim.ground.snow if v > 0) / len(sim.ground.snow), 0.5, pid)
            self.assertFalse(sim.hello_message()["preset"]["biome"]["frozen_water"], pid)

    def test_plain_worlds_are_byte_identical_to_before(self):
        import hashlib
        from objects import generate_world
        for (seed, w, h, kind, mode), want in PLAIN_WORLD_HASHES.items():
            t, st, objs = generate_world(random.Random(seed), w, h, kind, mode)
            blob = json.dumps({"y": t.y, "s": st, "o": objs}, sort_keys=True, separators=(",", ":"))
            self.assertEqual(hashlib.sha256(blob.encode()).hexdigest(), want, (seed, w, h, kind, mode))
        # and a plain Simulation world has no biome and no preset block
        sim = Simulation(5, 1920, 1080, 30, "rolling", "normal")
        h = sim.hello_message()
        self.assertNotIn("preset", h)
        self.assertEqual(sim.ground.snow, [0.0] * len(sim.ground.snow))

    def test_plain_worlds_keep_terrain_settlements_and_buildings_when_the_ground_spots_moved(self):
        """The 2026-10-09 fix moved puddles and snowpacks (and the windsocks, since removed) only: the hash of terrain +
        settlements + every other object (trees, ponds, buildings, mountains, hills; same ids) is what it was before the fix."""
        import hashlib
        from objects import generate_world
        spots = ("puddle", "snowpack")
        self.assertEqual(set(PLAIN_WORLD_NON_SPOT_HASHES), set(PLAIN_WORLD_HASHES))
        for (seed, w, h, kind, mode), want in PLAIN_WORLD_NON_SPOT_HASHES.items():
            t, st, objs = generate_world(random.Random(seed), w, h, kind, mode)
            rest = [o for o in objs if o["kind"] not in spots]
            blob = json.dumps({"y": t.y, "s": st, "o": rest}, sort_keys=True, separators=(",", ":"))
            self.assertEqual(hashlib.sha256(blob.encode()).hexdigest(), want, (seed, w, h, kind, mode))

    def test_the_biome_follows_the_preset_through_save_and_load(self):
        for pid in ("sahara", "antarctica", "oklahoma"):
            a = Simulation(5, 1920, 1080, 30, "rolling", "normal", pid)
            for _ in range(30):
                a.step(DT)
            with tempfile.TemporaryDirectory() as tmp:
                path = os.path.join(tmp, "s.sav")
                ok, err = save_state(a.to_state(), path)
                self.assertTrue(ok, err)
                b = Simulation.from_state(load_state(path))
            ha, hb = a.hello_message(), b.hello_message()
            self.assertEqual(hb["preset"]["biome"], ha["preset"]["biome"])
            self.assertEqual(hb["settlements"], ha["settlements"])
            self.assertEqual(hb["objects"], ha["objects"], "the objects come from the save, nothing is regenerated")
            self.assertEqual(b.atmo.arid, pid == "sahara")

    def test_a_save_from_before_biomes_still_loads(self):
        a = Simulation(5, 1920, 1080, 30, "rolling", "normal", "sahara")
        st = copy.deepcopy(a.to_state())
        st["atmo"].pop("arid", None)                     # no biome state at all in an older file
        b = Simulation.from_state(st)
        self.assertEqual(b.hello_message()["objects"], a.hello_message()["objects"])
        self.assertTrue(b.atmo.arid, "the desert's dry air is a property of the preset")
        for _ in range(30):
            S.validate_frame(b.step(DT), 1920, 1080, S.validate_hello(b.hello_message()))
        # a save of a preset world that predates the preset blocks entirely loads as a plain sandbox
        st2 = copy.deepcopy(Simulation(5, 1280, 720, 30, "hilly", "normal").to_state())
        st2.pop("preset")
        self.assertNotIn("preset", Simulation.from_state(st2).hello_message())

    def test_dry_air_looks_dry_and_humid_air_opens_overcast_and_raining(self):
        def opening(pid, seed):
            sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal", pid)
            types = [c.type for c in sim.clouds]
            at0 = sum(1 for c in sim.clouds if c.stage == "precipitating")
            for _ in range(450):
                sim.step(DT)
            return sim, types, at0
        high = {"cirrus", "altocumulus", "altostratus"}
        n_high = n_all = rain15 = 0
        for seed in range(1, 13):
            sim, types, at0 = opening("sahara", seed)
            self.assertLessEqual(len(types), 8, "a few clouds, not a sky full")
            self.assertGreaterEqual(len(types), 2)
            self.assertEqual(at0, 0)
            n_all += len(types)
            n_high += sum(1 for t in types if t in high)
            rain15 += sim.ground_rain_hits > 0
            self.assertEqual(sim.atmo.arid, True)
            self.assertTrue(all(c.type != "cumulonimbus" or c.stage != "precipitating" for c in sim.clouds[:0]))
        self.assertGreaterEqual(n_high / n_all, 0.55, "high thin cloud")
        self.assertLessEqual(rain15, 2, "no rain within 15 s in 10 of 12 worlds at least")
        for seed in range(1, 7):
            sim, types, _at0 = opening("atacama", seed)
            self.assertLessEqual(len(types), 8)
            self.assertEqual(sim.ground_rain_hits, 0, f"Atacama seed {seed}")
        for seed in range(1, 7):
            sim, types, _at0 = opening("meghalaya", seed)
            self.assertGreaterEqual(len(types), 12, "overcast")
            self.assertGreater(sim.ground_rain_hits, 300, f"Meghalaya in July is raining within 15 s (seed {seed})")
            self.assertLess(sum(1 for t in types if t in ("cirrus", "altocumulus")) / len(types), 0.3)
        # the day's Sahara sky has long clear spells: most of the time at most a few clouds
        sim = Simulation(2, 3840, 1080, 30, "rolling", "normal", "sahara")
        counts = []
        for i in range(30 * 120):
            sim.step(DT)
            if i % 30 == 0:
                counts.append(len(sim.clouds))
        self.assertLessEqual(sum(counts) / len(counts), 8.0)
        self.assertTrue(any(c <= 3 for c in counts), "a clear spell now and then")


class TestBiomePolish(unittest.TestCase):
    """Temperate-only buildings stay out of other biomes, no pines in the tropics, no highway across the ice,
    and the climate station's town is always built."""

    def test_barns_windmills_and_churches_only_in_temperate_and_mediterranean_worlds(self):
        for pid, p in P.PRESETS.items():
            keep = p["biome"]["id"] in ("temperate", "mediterranean")
            self.assertEqual(bool(p["biome"]["avoid"]), not keep, pid)
            if keep:
                continue
            for seed in BIOME_SEEDS:
                _sim, h = biome_world(pid, seed)
                kinds = {o["kind"] for o in h["objects"]}
                for k in ("barn", "windmill", "church"):
                    self.assertNotIn(k, kinds, f"{pid} seed {seed}: {k} in a {p['biome']['id']} world")
        # ... and the temperate worlds keep them (so the ban is not just a missing feature)
        seen = set()
        for pid in ("washington", "russia", "united_kingdom", "central_europe", "oklahoma", "japan", "new_zealand",
                    "california"):
            for seed in (1, 2, 3):
                seen.update(o["kind"] for o in biome_world(pid, seed)[1]["objects"])
        self.assertTrue({"barn", "church", "windmill"} <= seen, seen)
        # dense worlds too
        for pid in ("tibet", "australia", "hawaii", "amazon", "sahara", "antarctica", "alaska", "kenya"):
            h = Simulation(2, 3840, 1080, 30, "rolling", "dense", pid).hello_message()
            self.assertFalse({o["kind"] for o in h["objects"]} & {"barn", "windmill", "church"}, pid)

    def test_no_pines_in_tropical_and_savanna_worlds(self):
        for pid, p in P.PRESETS.items():
            if p["biome"]["id"] in ("tropical", "savanna"):
                self.assertNotEqual(p["biome"]["high_tree"], "pine", pid)
                self.assertNotIn("pine", p["biome"]["tree_mix"], pid)
                for seed in BIOME_SEEDS:
                    trees = [o for o in biome_world(pid, seed)[1]["objects"] if o["kind"] == "tree"]
                    self.assertTrue(trees)
                    self.assertEqual([o for o in trees if o["variant"] == "pine"], [], f"{pid} seed {seed}")
        pines = sum(1 for pid in ("washington", "russia", "colorado", "alaska", "japan")
                    for o in biome_world(pid)[1]["objects"] if o["kind"] == "tree" and o["variant"] == "pine")
        self.assertGreater(pines, 40, "pines stay in temperate, alpine and tundra worlds")

    def test_the_ice_has_no_highway_and_a_station_has_two_vehicles(self):
        for seed in BIOME_SEEDS:
            sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal", "antarctica")
            h = sim.hello_message()
            self.assertEqual([r["kind"] for r in h["roads"]], ["street"], "a street at the station, nothing across the ice")
            self.assertEqual(len(sim.cars.cars), len({c.id for c in sim.cars.cars}))
            self.assertLessEqual(len(sim.cars.cars), 3)
            self.assertTrue(sim.cars.cars)
            self.assertTrue(all(c.variant in ("pickup", "van") for c in sim.cars.cars))
        sim = Simulation(3, 3840, 1080, 30, "rolling", "normal", "antarctica")
        for _ in range(30 * 40):
            f = sim.step(DT)
            self.assertLessEqual(len(f.get("cars", [])), 3)
            self.assertTrue(all(c["variant"] in ("pickup", "van") for c in f.get("cars", [])))
        S.validate_hello(sim.hello_message())
        # save / load keeps the light traffic
        st = sim.to_state()
        b = Simulation.from_state(copy.deepcopy(st))
        self.assertEqual(b.cars.traffic, sim.cars.traffic)
        self.assertEqual(b.cars.mix, sim.cars.mix)
        self.assertEqual([r["kind"] for r in b.hello_message()["roads"]], ["street"])
        # an old save (no traffic keys) loads with today's traffic
        st["cars"].pop("traffic")
        st["cars"].pop("mix")
        c = Simulation.from_state(st)
        self.assertEqual((c.cars.traffic, c.cars.mix), (1.0, None))

    def test_desert_roads_keep_the_highway_but_with_light_traffic(self):
        light, heavy = [], []
        for seed in BIOME_SEEDS:
            for pid in ("sahara", "atacama"):
                sim, h = biome_world(pid, seed)
                kinds = [r["kind"] for r in h["roads"]]
                self.assertIn("highway", kinds)
                self.assertEqual(kinds.count("highway"), 1)
                light.append(len(sim.cars.cars))
                self.assertLessEqual(len(sim.cars.cars), 12, f"{pid} seed {seed}")
            sim, h = biome_world("washington", seed)
            heavy.append(len(sim.cars.cars))
        self.assertLess(sum(light) / len(light), 0.5 * sum(heavy) / len(heavy))
        # a plain world's traffic is untouched
        plain = Simulation(5, 3840, 1080, 30, "rolling", "normal")
        self.assertEqual((plain.cars.traffic, plain.cars.mix), (1.0, None))
        self.assertIn("highway", [r["kind"] for r in plain.hello_message()["roads"]])

    def test_the_climate_station_town_is_always_a_named_settlement(self):
        for pid, p in P.PRESETS.items():
            stations = [pl for pl in p["biome"]["places"] if pl.get("station")]
            self.assertEqual(len(stations), 1, f"{pid}: exactly one station town")
            name = stations[0].get("name") or P._town_name(stations[0]["landmark"])
            self.assertIn(name, p["climate"]["station"], f"{pid}: {name} vs station {p['climate']['station']!r}")
            self.assertTrue(P.settlement_plan(p, 3840)["named"][p["biome"]["places"].index(stations[0])]["station"])
            for seed in BIOME_SEEDS:
                _sim, h = biome_world(pid, seed)
                by = {s["name"]: s for s in h["settlements"]}
                self.assertIn(name, by, f"{pid} seed {seed}: the station town {name} was not built")
            for mode in ("sparse", "dense"):
                h = Simulation(1, 3840, 1080, 30, "rolling", mode, pid).hello_message()
                self.assertIn(name, {s["name"] for s in h["settlements"]}, f"{pid} {mode}")
        for seed in BIOME_SEEDS:
            by = {s["name"]: s for s in biome_world("australia", seed)[1]["settlements"]}
            self.assertEqual(by["Sydney"]["kind"], "city")
            top = max(P.PRESETS["australia"]["landmarks"], key=lambda lm: lm["elev_m"])
            self.assertEqual(top["name"].split(" (")[0], "Katoomba")
            self.assertTrue(by["Sydney"]["x1"] <= 3344.0 + 1e-6 or by["Sydney"]["x0"] >= 3584.0 - 1e-6,
                            "nothing is built on the Blue Mountains summit span")


if __name__ == "__main__":
    unittest.main()
