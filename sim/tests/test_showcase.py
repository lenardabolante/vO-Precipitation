"""Showcase mode (PROTOCOL.md v2.2): one cloud of the forced type at a time, whole life in view.

Every one of the eight types is run through a full headless cycle: never more than one cloud, the
type never changes (a cumulus does not tower), all five stages for the types that precipitate (and
rain/snow particles actually emitted) and no `precipitating` for cirrus / altocumulus, the cloud
stays inside the world until it has died, then a pause and a new unique cloud. Plus the `showcase`
command, the ignored sandbox commands, the hello lessons and the frame block. And the rain programme:
every precipitating type rains for at least 30 % of its life and 25 s, nimbostratus longest and
steadiest, cumulonimbus heavy with a clear peak, stratus / stratocumulus a light drizzle, the rate smooth."""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_showcase.py` (or sim.tests.test_showcase) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
from clouds import STAGES, TYPE_NAMES
from showcase import (DEFAULT_H, DEFAULT_W, END_FILL, HOLD_LF, LESSONS, MATURE_FILL, MIN_RAIN_S, PAUSE_S, RAIN_PLAN,
                      ShowcaseSimulation, plan_rain_length, rain_fill)

DT = 1.0 / 30
SIM_ENTRY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "precip_sim.py")
PRECIP_KINDS = (1, 2, 3, 4, 6)          # raindrop, snowflake, ice crystal, graupel, melting flake
NO_PRECIP = ("cirrus", "altocumulus")


def run_cycles(cloud_type, cycles=1, seed=11, limit_s=1200.0):
    """Step a showcase world until `cycles` clouds have died; returns one record per cycle."""
    sim = ShowcaseSimulation(seed, DEFAULT_W, DEFAULT_H, 30, cloud_type)
    emitted = {"n": 0, "kinds": set()}
    orig = sim.particles.spawn

    def spawn(kind, *a, **k):
        if kind in PRECIP_KINDS:
            emitted["n"] += 1
            emitted["kinds"].add(kind)
        return orig(kind, *a, **k)

    sim.particles.spawn = spawn
    recs = []
    cur = None
    pause_frames = 0
    while len(recs) < cycles and sim.world.t < limit_s:
        f = sim.step(DT)
        assert len(f["clouds"]) <= 1, f"{len(f['clouds'])} clouds on stage at t={f['t']}"
        sc = f["showcase"]
        for c in f["clouds"]:
            if cur is None or cur["id"] != c["id"]:
                cur = {"id": c["id"], "seed": c["seed"], "name": c["name"], "cycle": sc["cycle"], "stages": [],
                       "types": set(), "xmin": 1e9, "xmax": -1e9, "frames": 0, "emitted0": emitted["n"],
                       "fair": [], "from_type": False, "precip_types": set(), "stage_times": {},
                       "first_t": f["t"], "form_max": 0.0, "rate_series": [], "life_series": []}
            if c["stage"] not in cur["stages"]:
                cur["stages"].append(c["stage"])
            cur["types"].add(c["type"])
            cur["xmin"] = min(cur["xmin"], c["x"] - c["w"] / 2.0)
            cur["xmax"] = max(cur["xmax"], c["x"] + c["w"] / 2.0)
            cur["frames"] += 1
            cur["from_type"] = cur["from_type"] or "from_type" in c
            cur["fair"].append(c.get("fair", False))
            cur["precip_types"].add(c["precip_type"])
            cur["stage_times"] = c["stage_times"]
            cur["form_max"] = max(cur["form_max"], c["form"])
            cur["rate_series"].append((c["stage"], c["precip_rate"]))
            cur["life_series"].append((c["age"], c["lifetime"], c["life_frac"]))
            assert sc["cloud_id"] == c["id"]
            assert sc["type"] == cloud_type
        if not f["clouds"]:
            assert sc["cloud_id"] is None, "cloud_id is null while the stage is empty"
        for d in f["deaths"]:
            assert d["id"] == cur["id"]
            cur["death"] = d
            cur["emitted"] = emitted["n"] - cur["emitted0"]
            cur["kinds"] = set(emitted["kinds"])
            cur["died_t"] = f["t"]
            recs.append(cur)
    return sim, recs


_CACHE: dict = {}


def cycle_of(cloud_type):
    if cloud_type not in _CACHE:
        _CACHE[cloud_type] = run_cycles(cloud_type)
    return _CACHE[cloud_type][1][0]


class TestEveryTypeCompletesACycle(unittest.TestCase):
    def test_each_type_runs_a_whole_life_with_one_cloud_of_the_forced_type(self):
        for ty in TYPE_NAMES:
            with self.subTest(ty):
                r = cycle_of(ty)
                self.assertEqual(r["types"], {ty}, "the type is forced for the whole life")
                self.assertFalse(r["from_type"], "a cumulus never towers into another type on this stage")
                self.assertEqual(r["stages"][0], "forming")
                self.assertEqual(r["stages"][-1], "dissipating")
                self.assertEqual(r["stages"], [s for s in STAGES if s in r["stages"]], "stages in order")
                self.assertIn("mature", r["stages"])
                self.assertGreater(r["death"]["lifetime"], 30.0)
                self.assertEqual(r["death"]["type"], ty)
                self.assertGreater(r["form_max"], 0.9, "the cloud reached its full body")

    def test_precipitating_types_precipitate_every_cycle_and_emit_particles(self):
        for ty in TYPE_NAMES:
            if ty in NO_PRECIP:
                continue
            with self.subTest(ty):
                r = cycle_of(ty)
                self.assertEqual(r["stages"], STAGES, "all five stages")
                self.assertFalse(any(r["fair"][-5:]), "never left fair for its whole life")
                self.assertGreater(r["death"]["total_precip_mm"], 1.0)
                self.assertGreater(r["emitted"], 100, f"{ty} emitted {r['emitted']} precipitation particles")
                self.assertTrue(r["kinds"] & {1, 2, 6}, f"{ty}: rain / snow / sleet particles, got {r['kinds']}")
                self.assertTrue(r["precip_types"] & {"rain", "snow", "mixed", "graupel"})

    def test_cirrus_and_altocumulus_stay_fair_and_never_precipitate(self):
        for ty in NO_PRECIP:
            with self.subTest(ty):
                r = cycle_of(ty)
                self.assertNotIn("precipitating", r["stages"])
                self.assertEqual(r["stages"], ["forming", "growing", "mature", "dissipating"])
                self.assertTrue(all(r["fair"]))
                self.assertEqual(r["emitted"], 0)
                self.assertEqual(r["death"]["total_precip_mm"], 0.0)

    def test_the_cloud_stays_inside_the_world_for_its_whole_life(self):
        for ty in TYPE_NAMES:
            with self.subTest(ty):
                r = cycle_of(ty)
                self.assertGreaterEqual(r["xmin"], 0.0, f"{ty} left through the left edge ({r['xmin']:.0f})")
                self.assertLessEqual(r["xmax"], float(DEFAULT_W), f"{ty} left through the right edge ({r['xmax']:.0f})")
                self.assertNotIn("dissipating", r["stages"][:-1])
                self.assertGreater(r["xmax"] - r["xmin"], 100)

    def test_stage_times_cover_the_stages_reached(self):
        for ty in TYPE_NAMES:
            r = cycle_of(ty)
            self.assertEqual(set(r["stage_times"]), set(r["stages"]), ty)
            times = [r["stage_times"][s] for s in r["stages"]]
            self.assertEqual(times, sorted(times), ty)


RAIN_TYPES = tuple(t for t in TYPE_NAMES if t not in NO_PRECIP and t != "supercell")   # (the supercell has its own programme)
_RAIN_CACHE: dict = {}


def rain_cycles(cloud_type):
    """Two cycles of seed 3 (the seed the duration problem was measured on), with the rain measurements."""
    if cloud_type not in _RAIN_CACHE:
        out = []
        for r in run_cycles(cloud_type, cycles=2, seed=3)[1]:
            st = r["stage_times"]
            rates = [v for stage, v in r["rate_series"] if stage == "precipitating"]
            out.append({"before": st["precipitating"] - r["first_t"],
                        "rain": st["dissipating"] - st["precipitating"],
                        "life": r["died_t"] - r["first_t"],
                        "peak": max(v for _s, v in r["rate_series"]),
                        "mean": sum(rates) / len(rates), "rates": rates,
                        "series": r["rate_series"], "rec": r})
        _RAIN_CACHE[cloud_type] = out
    return _RAIN_CACHE[cloud_type]


class TestRainDuration(unittest.TestCase):
    """Showcase precipitation is long enough to see: the Cloud Formations screen shows each cloud's life
    *with its rain*, so the rain is a real share of the life, not a ten-second blip after a minute of nothing."""

    def test_every_precipitating_type_rains_at_least_30_percent_of_its_life_and_25_seconds(self):
        for ty in RAIN_TYPES:
            for i, c in enumerate(rain_cycles(ty), 1):
                with self.subTest(f"{ty} cycle {i}"):
                    self.assertGreaterEqual(c["rain"], 25.0, f"{ty} rained {c['rain']:.1f} s")
                    self.assertGreaterEqual(c["rain"] / c["life"], 0.30,
                                            f"{ty} rained {c['rain']:.1f} of {c['life']:.1f} s")
                    self.assertGreater(c["before"], 15.0, "forming / growing / mature stay in view first")

    def test_nimbostratus_rains_the_longest_and_the_steadiest(self):
        def mean(ty, key):
            return sum(c[key] for c in rain_cycles(ty)) / len(rain_cycles(ty))
        for ty in RAIN_TYPES:
            if ty != "nimbostratus":
                self.assertGreater(min(c["rain"] for c in rain_cycles("nimbostratus")),
                                   max(c["rain"] for c in rain_cycles(ty)) + 10.0, f"longer than {ty}")
        for c in rain_cycles("nimbostratus"):
            self.assertLess(c["peak"] / c["mean"], 1.3, "steady: the peak is close to the mean rate")
            self.assertGreater(c["mean"], 0.12, "moderate, not a drizzle")
        self.assertGreater(mean("nimbostratus", "mean"), mean("stratus", "mean") * 2.0)

    def test_cumulonimbus_is_heavy_with_a_clear_peak(self):
        for c in rain_cycles("cumulonimbus"):
            self.assertGreater(c["peak"], 0.35)
            self.assertGreater(c["peak"] / c["mean"], 1.4, "a burst, not a plateau")
            i = c["rates"].index(max(c["rates"]))
            self.assertTrue(0.15 < i / len(c["rates"]) < 0.5, "the peak is early in the rain, not at either end")
        for ty in RAIN_TYPES:
            if ty != "cumulonimbus":
                self.assertLess(max(c["peak"] for c in rain_cycles(ty)),
                                min(c["peak"] for c in rain_cycles("cumulonimbus")), f"{ty} is lighter")

    def test_stratus_and_stratocumulus_are_a_light_drizzle(self):
        for ty in ("stratus", "stratocumulus"):
            for c in rain_cycles(ty):
                self.assertLess(c["peak"], 0.12, ty)
                self.assertGreater(c["mean"], 0.03, f"{ty} still emits particles")
                self.assertLess(c["peak"], 0.5 * min(x["peak"] for x in rain_cycles("cumulonimbus")))

    def test_the_rate_changes_smoothly(self):
        for ty in RAIN_TYPES:
            for i, c in enumerate(rain_cycles(ty), 1):
                with self.subTest(f"{ty} cycle {i}"):
                    rates = c["rates"]
                    self.assertEqual(rates[0], 0.0, "(the first frame of the stage is the onset: the rate is set next tick)")
                    rates = rates[1:]
                    steps = [abs(b - a) for a, b in zip(rates, rates[1:])]
                    # (frames carry precip_rate rounded to 0.01, so one step is one quantum at most)
                    self.assertLessEqual(max(steps), 0.0101, "no jumps once the rain is under way")
                    self.assertLess(rates[0], 0.25 * c["peak"] + 0.05, "it starts light and builds")
                    self.assertGreater(min(rates[:-3]), 0.0, "no gaps in the rain")
                    after = [v for stage, v in c["series"] if stage == "dissipating"]
                    self.assertTrue(all(b <= a + 1e-9 for a, b in zip(after, after[1:])), "it fades out")
                    self.assertLess(after[0], 0.2 * c["peak"] + 0.02, "and does not stop with a jump")

    def test_stages_stay_in_order_and_one_cloud_stays_in_the_world(self):
        for ty in RAIN_TYPES:
            for c in rain_cycles(ty):
                r = c["rec"]
                self.assertEqual(r["stages"], STAGES)
                self.assertGreaterEqual(r["xmin"], 0.0)
                self.assertLessEqual(r["xmax"], float(DEFAULT_W))

    def test_programme_profiles(self):
        for ty, plan in RAIN_PLAN.items():
            with self.subTest(ty):
                self.assertAlmostEqual(rain_fill(plan, 0.0), MATURE_FILL, places=6, msg="starts where the mature cloud was")
                self.assertAlmostEqual(rain_fill(plan, 1.0), END_FILL, places=6)
                self.assertLess(END_FILL, 0.12, "ends under the engine's exit level so the stage ends by itself")
                xs = [k / 400.0 for k in range(401)]
                ys = [rain_fill(plan, x) for x in xs]
                self.assertLess(max(abs(b - a) for a, b in zip(ys, ys[1:])), 0.02, "continuous")
                self.assertTrue(all(END_FILL - 1e-9 <= y <= 1.0 + 1e-9 for y in ys))
                for start in (30.0, 40.0, 50.0):
                    length, whole = plan_rain_length(dict(plan), start, plan["life"][0])
                    self.assertGreaterEqual(length, MIN_RAIN_S)
                    self.assertGreaterEqual(length / whole, plan["rain_share"] - 0.01)
        self.assertEqual(set(RAIN_PLAN), set(RAIN_TYPES), "every type that can rain has a programme")


class TestCycles(unittest.TestCase):
    def test_pause_then_a_new_unique_cloud_with_the_next_cycle_number(self):
        sim, recs = run_cycles("cumulus", cycles=3)
        self.assertEqual([r["cycle"] for r in recs], [1, 2, 3])
        self.assertEqual(len({r["id"] for r in recs}), 3)
        self.assertEqual(len({r["seed"] for r in recs}), 3, "every cloud has its own seed")
        self.assertEqual(len({r["name"] for r in recs}), 3)
        for r in recs:
            self.assertEqual(r["types"], {"cumulus"})
        # between two clouds the stage is empty for about PAUSE_S
        gaps = [recs[i + 1]["first_t"] - recs[i]["died_t"] for i in range(2)]
        for g in gaps:
            self.assertGreaterEqual(g, PAUSE_S - 0.2)
            self.assertLessEqual(g, PAUSE_S + 1.0)

    def test_deterministic_per_seed(self):
        a = ShowcaseSimulation(5, 1920, 1080, 30, "stratocumulus")
        b = ShowcaseSimulation(5, 1920, 1080, 30, "stratocumulus")
        for _ in range(600):
            self.assertEqual(json.dumps(a.step(DT), sort_keys=True), json.dumps(b.step(DT), sort_keys=True))
        c = ShowcaseSimulation(6, 1920, 1080, 30, "stratocumulus")
        d = [c.step(DT) for _ in range(300)][-1]
        self.assertNotEqual(json.dumps(d["clouds"], sort_keys=True),
                            json.dumps(ShowcaseSimulation(5, 1920, 1080, 30, "stratocumulus").step(DT)["clouds"]))

    def test_the_first_cloud_starts_almost_at_once(self):
        sim = ShowcaseSimulation(2, 1920, 1080, 30, "nimbostratus")
        f = None
        for _ in range(40):
            f = sim.step(DT)
        self.assertEqual(len(f["clouds"]), 1)
        self.assertEqual(f["showcase"], {"type": "nimbostratus", "cycle": 1, "cloud_id": f["clouds"][0]["id"]})
        self.assertLess(f["clouds"][0]["form"], 0.3, "it starts as thin haze, the life is in view from the start")


class TestStageWorld(unittest.TestCase):
    def setUp(self):
        self.sim = ShowcaseSimulation(4, DEFAULT_W, DEFAULT_H, 30, "cumulus")

    def test_default_world_is_1920_by_1080_flat_with_no_settlements(self):
        h = self.sim.hello_message()
        self.assertEqual((h["world"]["w"], h["world"]["h"]), (1920, 1080))
        self.assertEqual(h["world"]["terrain_kind"], "flat")
        self.assertEqual(h["settlements"], [])
        self.assertFalse([o for o in h["objects"] if o["kind"] in S.BUILDING_KINDS])
        y = h["world"]["terrain"]["y"]
        self.assertLess(max(y) - min(y), 0.03 * 1080, "flat ground")
        self.assertNotIn("preset", h)

    def test_light_steady_wind(self):
        u = []
        for _ in range(300):
            f = self.sim.step(DT)
            u.extend(f["wind"]["u"])
        self.assertGreater(min(u), 0.0, "never blows backwards")
        self.assertLess(max(u), 6.0, "light wind")
        self.assertEqual(self.sim.world.prevailing_u, 3.0)

    def test_hello_block(self):
        h = self.sim.hello_message()
        S.validate_hello(h)
        self.assertEqual(h["mode"], "showcase")
        sc = h["showcase"]
        self.assertEqual(sc["type"], "cumulus")
        self.assertEqual(sc["types"], ["cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus",
                                       "altocumulus", "stratus", "cirrus", "supercell"])
        json.dumps(h)

    def test_lessons_cover_every_type_with_one_to_three_sentences(self):
        sc = self.sim.hello_message()["showcase"]
        self.assertEqual(set(sc["lessons"]), set(TYPE_NAMES))
        for ty, ls in sc["lessons"].items():
            self.assertEqual(set(ls), {"title", "altitude", "formation", "life", "precipitation", "wind", "ending"})
            self.assertEqual(ls["title"].lower(), ty)
            for k in ("formation", "life", "precipitation", "wind", "ending"):
                n = len([s for s in re.split(r"(?<=[.!?])\s+", ls[k].strip()) if s])
                self.assertTrue(1 <= n <= 3, f"{ty}.{k} has {n} sentences")
                self.assertTrue(ls[k].rstrip().endswith("."), f"{ty}.{k}")
            self.assertTrue(ls["altitude"].strip())
        self.assertIn("ice", sc["lessons"]["cirrus"]["formation"])
        self.assertIn("anvil", sc["lessons"]["cumulonimbus"]["wind"])
        self.assertIn("front", sc["lessons"]["nimbostratus"]["formation"])
        self.assertEqual(set(LESSONS), set(TYPE_NAMES))

    def test_frames_are_ordinary_frames_with_a_showcase_block_and_everything_the_viewer_draws(self):
        ids = S.validate_hello(self.sim.hello_message())
        saw_particles = saw_arrows = False
        for _ in range(60 * 30):
            f = self.sim.step(DT)
            S.validate_frame(f, DEFAULT_W, DEFAULT_H, ids)
            self.assertEqual(set(f["showcase"]), {"type", "cycle", "cloud_id"})
            self.assertTrue(f["streamlines"])
            self.assertTrue(f["wind"]["u"])
            saw_particles = saw_particles or bool(f["particles"])
            saw_arrows = saw_arrows or any(a["kind"] in ("updraft", "downdraft", "fall") for a in f["arrows"])
            self.assertEqual(f["stats"]["clouds_alive"], len(f["clouds"]))
        self.assertTrue(saw_arrows)
        self.assertTrue(saw_particles)


class TestCommands(unittest.TestCase):
    def setUp(self):
        self.sim = ShowcaseSimulation(8, DEFAULT_W, DEFAULT_H, 30, "cumulus")
        for _ in range(900):                       # well into the cloud's life
            self.sim.step(DT)

    def test_showcase_type_switches_at_once_without_a_deaths_entry(self):
        self.assertEqual(len(self.sim.clouds), 1)
        old = self.sim.clouds[0]
        dead_before = self.sim.clouds_dead
        ev: list = []
        self.sim.handle_command({"cmd": "showcase", "type": "cirrus"}, ev)
        f = self.sim.step(DT)
        self.assertEqual(f["clouds"], [], "the old cloud is gone at once")
        self.assertEqual(f["deaths"], [], "and gets no deaths entry")
        self.assertEqual(self.sim.clouds_dead, dead_before)
        self.assertEqual(f["showcase"], {"type": "cirrus", "cycle": 0, "cloud_id": None})
        for _ in range(60):
            f = self.sim.step(DT)
        self.assertEqual(len(f["clouds"]), 1)
        c = f["clouds"][0]
        self.assertEqual(c["type"], "cirrus")
        self.assertNotEqual(c["id"], old.id)
        self.assertEqual(f["showcase"], {"type": "cirrus", "cycle": 1, "cloud_id": c["id"]})
        self.assertEqual(self.sim.hello_message()["showcase"]["type"], "cirrus")

    def test_switching_clears_the_old_cloud_particles(self):
        for _ in range(900):
            self.sim.step(DT)
            if len(self.sim.particles):
                break
        self.sim.handle_command({"cmd": "showcase", "type": "stratus"}, [])
        self.assertEqual(len(self.sim.particles), 0)

    def test_restart_keeps_the_type_and_starts_cycle_one(self):
        self.sim.sc_cycle = 5
        self.sim.handle_command({"cmd": "showcase", "restart": True}, [])
        f = self.sim.step(DT)
        self.assertEqual(f["clouds"], [])
        for _ in range(60):
            f = self.sim.step(DT)
        self.assertEqual(f["showcase"]["type"], "cumulus")
        self.assertEqual(f["showcase"]["cycle"], 1)
        self.assertEqual(len(f["clouds"]), 1)

    def test_bad_showcase_commands_change_nothing(self):
        before = self.sim.sc_cloud_id
        for cmd in ({"cmd": "showcase"}, {"cmd": "showcase", "type": "nope"}, {"cmd": "showcase", "type": 3}):
            self.sim.handle_command(cmd, [])
        self.assertEqual(self.sim.sc_cloud_id, before)
        self.assertEqual(self.sim.sc_type, "cumulus")
        self.assertEqual(len(self.sim.clouds), 1)

    def test_every_type_can_be_switched_to(self):
        for ty in TYPE_NAMES:
            self.sim.handle_command({"cmd": "showcase", "type": ty}, [])
            for _ in range(45):
                f = self.sim.step(DT)
            self.assertEqual([c["type"] for c in f["clouds"]], [ty])

    def test_spawn_reseed_and_save_are_ignored(self):
        seed = self.sim.seed
        n = len(self.sim.clouds)
        self.sim.handle_command({"cmd": "spawn", "x": 400, "y": 200}, [])
        self.assertEqual(len(self.sim.clouds), n)
        self.sim.handle_command({"cmd": "reseed", "seed": 99}, [])
        self.assertEqual(self.sim.seed, seed)
        self.assertEqual(self.sim.drain_outbox(), [], "no new hello")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "x.sav")
            self.sim.handle_command({"cmd": "save", "path": path}, [])
            out = self.sim.drain_outbox()
            self.assertEqual(len(out), 1)
            self.assertEqual((out[0]["type"], out[0]["ok"], out[0]["path"]), ("saved", False, path))
            self.assertTrue(out[0]["error"])
            self.assertFalse(os.path.exists(path), "nothing is written")
        f = self.sim.step(DT)
        self.assertLessEqual(len(f["clouds"]), 1)

    def test_wind_temp_pause_speed_view_quit_work_as_usual(self):
        s = self.sim
        t0, u0 = s.world.surface_temp_c, s.world.prevailing_u
        s.handle_command({"cmd": "temp", "delta": -2}, [])
        s.handle_command({"cmd": "wind", "delta": 1.0}, [])
        self.assertAlmostEqual(s.world.surface_temp_c, t0 - 2)
        self.assertAlmostEqual(s.world.prevailing_u, u0 + 1.0)
        s.handle_command({"cmd": "pause"}, [])
        t = s.world.t
        for _ in range(10):
            f = s.step(DT)
        self.assertTrue(f["paused"])
        self.assertEqual(s.world.t, t)
        self.assertEqual(f["showcase"]["type"], "cumulus", "frames keep coming while paused")
        s.handle_command({"cmd": "resume"}, [])
        s.handle_command({"cmd": "speed", "value": 4.0}, [])
        s.step(DT)
        self.assertAlmostEqual(s.world.t - t, 4.0 * DT, places=3)
        s.handle_command({"cmd": "view", "x0": 0, "x1": 500}, [])
        self.assertEqual(s.view, (0.0, 500.0))
        s.handle_command({"cmd": "quit"}, [])
        self.assertFalse(s.running)

    def test_a_cold_stage_snows(self):
        self.sim.handle_command({"cmd": "showcase", "type": "nimbostratus"}, [])
        self.sim.handle_command({"cmd": "temp", "delta": -20}, [])
        kinds = set()
        orig = self.sim.particles.spawn

        def spawn(kind, *a, **k):
            kinds.add(kind)
            return orig(kind, *a, **k)

        self.sim.particles.spawn = spawn
        for _ in range(6000):
            self.sim.step(DT)
            if 2 in kinds:
                break
        self.assertIn(2, kinds, "snowflakes fall when it is cold")


class TestProcess(unittest.TestCase):
    def run_sim(self, *args, frames=3):
        return subprocess.run([sys.executable, SIM_ENTRY, "--seed", "3", "--headless-frames", str(frames),
                               "--dump-every", "1", *args], capture_output=True, text=True, timeout=120)

    def test_mode_showcase_defaults_to_1920_by_1080_and_says_so_in_hello(self):
        r = self.run_sim("--mode", "showcase", "--cloud-type", "stratus")
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = [json.loads(ln) for ln in r.stdout.splitlines()]
        hello = lines[0]
        self.assertEqual((hello["type"], hello["mode"]), ("hello", "showcase"))
        self.assertEqual((hello["world"]["w"], hello["world"]["h"]), (1920, 1080))
        self.assertEqual(hello["showcase"]["type"], "stratus")
        S.validate_hello(hello)
        self.assertEqual([m["type"] for m in lines[1:]], ["frame"] * 3)
        self.assertTrue(all("showcase" in m for m in lines[1:]))

    def test_an_explicit_size_wins_in_showcase_mode(self):
        r = self.run_sim("--mode", "showcase", "--width", "1280", "--height", "720")
        hello = json.loads(r.stdout.splitlines()[0])
        self.assertEqual((hello["world"]["w"], hello["world"]["h"]), (1280, 720))
        self.assertEqual(hello["showcase"]["type"], "cumulus", "default cloud type")

    def test_bad_cloud_type_is_a_usage_error(self):
        r = self.run_sim("--mode", "showcase", "--cloud-type", "fog")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")

    def test_showcase_command_through_stdin(self):
        proc = subprocess.Popen([sys.executable, "-u", SIM_ENTRY, "--seed", "1", "--mode", "showcase"],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            proc.stdin.write(json.dumps({"cmd": "showcase", "type": "cirrus"}) + "\n")
            proc.stdin.write(json.dumps({"cmd": "save", "path": os.path.join(tempfile.gettempdir(), "nope.sav")}) + "\n")
            proc.stdin.flush()
            types, saved = set(), None
            for _ in range(400):
                m = json.loads(proc.stdout.readline())
                if m["type"] == "saved":
                    saved = m
                if m["type"] == "frame":
                    types.add(m["showcase"]["type"])
                    if "cirrus" in types and saved:
                        break
            self.assertIn("cirrus", types)
            self.assertIsNotNone(saved)
            self.assertFalse(saved["ok"])
        finally:
            proc.stdin.close()
            proc.communicate(timeout=20)


if __name__ == "__main__":
    unittest.main()


# -- the supercell tab (protocol v2.3): an ordered lifetime tour and exactly one tornado per cycle ---------------
from showcase import SC_TORNADO_EF, TOUR_IDS as _TOUR_IDS, TOURS, TOUR_MIN_DWELL_S  # noqa: E402

TOUR_IDS = _TOUR_IDS["supercell"]

_SC_CACHE: dict = {}


def supercell_run(cycles=2, seed=3):
    """Run the supercell tab for `cycles` whole cycles, keeping what the tests need from every frame."""
    key = (cycles, seed)
    if key in _SC_CACHE:
        return _SC_CACHE[key]
    sim = ShowcaseSimulation(seed, DEFAULT_W, DEFAULT_H, 30, "supercell")
    ids = S.validate_hello(sim.hello_message())
    rows = []
    finished = 0
    while finished < cycles and sim.world.t < 900.0:
        f = sim.step(DT)
        S.validate_frame(f, DEFAULT_W, DEFAULT_H, ids)
        sc = f["showcase"]
        c = f["clouds"][0] if f["clouds"] else None
        rows.append({"t": f["t"], "cycle": sc["cycle"], "step": sc.get("tour_step", "MISSING"), "cloud_id": sc["cloud_id"],
                     "tors": [dict(t) for t in f.get("tornadoes", [])], "total": f["stats"]["tornadoes_total"],
                     "rot": c["rotation"] if c else None, "wall": c["wall"] if c else None,
                     "stage": c["stage"] if c else None, "xmin": c["x"] - c["w"] / 2 if c else None,
                     "xmax": c["x"] + c["w"] / 2 if c else None, "events": f["events"],
                     "debris": sum(1 for p in f["particles"] if p[0] == 7), "life_frac": c["life_frac"] if c else None,
                     "age": c["age"] if c else None, "lifetime": c["lifetime"] if c else None,
                     "anvil": c["anvil"] if c else None, "ovs": c["overshoot"] if c else None,
                     "hook": c["hook"] if c else None})
        finished += len(f["deaths"])
    _SC_CACHE[key] = (sim, rows)
    return sim, rows


class TestSupercellTab(unittest.TestCase):
    def test_hello_has_the_type_the_lesson_and_the_ordered_tour(self):
        sim = ShowcaseSimulation(2, DEFAULT_W, DEFAULT_H, 30, "supercell")
        h = sim.hello_message()
        S.validate_hello(h)
        sc = h["showcase"]
        self.assertEqual(sc["type"], "supercell")
        self.assertEqual(sc["types"][-1], "supercell")
        self.assertEqual([st["id"] for st in sc["tours"]["supercell"]], TOUR_IDS)
        self.assertEqual(TOUR_IDS, ["towering", "mesocyclone", "mature", "wall_cloud", "funnel", "tornado", "rope_out", "decay"])
        self.assertEqual(set(sc["lessons"]["supercell"]), {"title", "altitude", "formation", "life", "precipitation", "wind", "ending"})
        text = " ".join(st["text"] for st in TOURS["supercell"]).lower()
        for needle in ("mesocyclone", "wall cloud", "rear flank downdraft", "funnel", "ef0", "rope", "overshoot", "forward flank"):
            self.assertIn(needle, text)
        json.dumps(h)

    def test_every_cycle_walks_all_eight_steps_in_order_with_exactly_one_tornado(self):
        sim, rows = supercell_run(2)
        for cyc in (1, 2):
            seq = []
            for r in rows:
                if r["cycle"] == cyc and r["step"] not in (None, "MISSING") and (not seq or seq[-1] != r["step"]):
                    seq.append(r["step"])
            self.assertEqual(seq, TOUR_IDS, f"cycle {cyc}")
            ids = {t["id"] for r in rows if r["cycle"] == cyc for t in r["tors"]}
            self.assertEqual(len(ids), 1, f"exactly one tornado in cycle {cyc}")
            reach = [t["reach"] for r in rows if r["cycle"] == cyc for t in r["tors"]]
            self.assertEqual(max(reach), 1.0, "it touches down")
            stages = []
            for r in rows:
                if r["cycle"] == cyc:
                    for t in r["tors"]:
                        if not stages or stages[-1] != t["stage"]:
                            stages.append(t["stage"])
            self.assertEqual(stages, ["funnel", "touchdown", "mature", "roping"])
            peak = max(t["ef"] for r in rows if r["cycle"] == cyc for t in r["tors"])
            self.assertEqual(peak, SC_TORNADO_EF)
        self.assertEqual(sim.tornadoes_total, 2)
        self.assertEqual(rows[-1]["total"], 2)

    def test_each_step_has_the_state_its_name_says(self):
        _sim, rows = supercell_run(2)
        for r in rows:
            st = r["step"]
            if st == "wall_cloud":
                self.assertFalse(r["tors"])
                self.assertGreaterEqual(r["wall"], 0.2)
            elif st == "mesocyclone":
                self.assertFalse(r["tors"])
            elif st == "funnel":
                self.assertEqual([t["stage"] for t in r["tors"]], ["funnel"])
            elif st == "tornado":
                self.assertEqual(len(r["tors"]), 1)
                self.assertIn(r["tors"][0]["stage"], ("touchdown", "mature"))
                self.assertEqual(r["tors"][0]["reach"], 1.0)
            elif st == "rope_out":
                self.assertEqual([t["stage"] for t in r["tors"]], ["roping"])
            elif st == "decay":
                self.assertFalse(r["tors"])
            elif st == "towering":
                self.assertFalse(r["tors"])
                self.assertLess(r["rot"], 0.6)

    def test_every_step_is_on_stage_long_enough_to_read(self):
        sim, rows = supercell_run(2)
        durations = {}
        for cyc in (1, 2):
            t_prev, step_prev = None, None
            for r in rows:
                if r["cycle"] != cyc or r["step"] in (None, "MISSING"):
                    continue
                if r["step"] != step_prev:
                    if step_prev is not None:
                        durations.setdefault(step_prev, []).append(r["t"] - t_prev)
                    step_prev, t_prev = r["step"], r["t"]
            last = [r for r in rows if r["cycle"] == cyc and r["step"] == step_prev][-1]
            durations.setdefault(step_prev, []).append(last["t"] - t_prev)
        line = ", ".join(f"{k} {min(v):.1f}-{max(v):.1f}s" for k, v in durations.items())
        print("seconds per tour step (2 cycles):", line)
        self.assertEqual(list(durations), TOUR_IDS)
        for k, v in durations.items():
            self.assertGreaterEqual(min(v), 8.0, f"{k}: {v}")
        self.assertGreaterEqual(min(min(v) for v in durations.values()), TOUR_MIN_DWELL_S - 0.1)
        for k, e in (("tornado", 20.0), ("rope_out", 8.0), ("funnel", 8.0)):
            self.assertGreaterEqual(min(durations[k]), e)
        for cyc, name, t0, t1 in sim.tour_log:
            if t1 is not None:
                self.assertGreaterEqual(t1 - t0, 8.0, (cyc, name))

    def test_the_anvil_and_the_overshoot_spread_during_the_mature_step(self):
        """The mature step's text is about the anvil and the overshooting top, so the picture shows them."""
        _sim, rows = supercell_run(2)
        for cyc in (1, 2):
            m = [r for r in rows if r["cycle"] == cyc and r["step"] == "mature"]
            self.assertTrue(m)
            self.assertGreaterEqual(m[0]["anvil"], 0.3, "the anvil is already spreading when the step starts")
            self.assertGreaterEqual(m[0]["ovs"], 0.25)
            self.assertGreater(m[-1]["anvil"], 0.3, "and it is well spread by the end of it")
            self.assertGreater(m[-1]["anvil"], m[0]["anvil"] + 0.25, "visibly more spread at the end than at the start")
            self.assertGreater(m[-1]["ovs"], 0.3)
            at2 = next(r for r in m if r["t"] >= m[0]["t"] + 2.0)
            self.assertGreater(at2["anvil"], 0.3, "two seconds into the step")
            print(f"cycle {cyc} mature step: anvil {m[0]['anvil']:.2f} -> {m[-1]['anvil']:.2f}, overshoot {m[0]['ovs']:.2f} -> {m[-1]['ovs']:.2f}")

    def test_the_supercell_stage_hook_follows_its_rotation(self):
        _sim, rows = supercell_run(2)
        for r in rows:
            if r["step"] in ("wall_cloud", "funnel", "tornado", "rope_out"):
                self.assertGreater(r["hook"], 0.3, r["step"])

    def test_the_tornado_ropes_out_before_the_storm_decays_and_stays_in_view(self):
        _sim, rows = supercell_run(2)
        for cyc in (1, 2):
            rr = [r for r in rows if r["cycle"] == cyc and r["cloud_id"] is not None]
            last_tor = max(i for i, r in enumerate(rr) if r["tors"])
            dec = next(i for i, r in enumerate(rr) if r["stage"] == "dissipating")
            self.assertLess(last_tor, dec, "the tornado is gone before the cloud starts to dissipate")
            self.assertGreaterEqual(min(r["xmin"] for r in rr), 0.0)
            self.assertLessEqual(max(r["xmax"] for r in rr), float(DEFAULT_W))
            for r in rr:
                for t in r["tors"]:
                    self.assertTrue(0.0 <= t["x"] <= DEFAULT_W)
            self.assertGreater(max(r["debris"] for r in rr), 30, "debris is thrown up")

    def test_tour_step_is_null_in_the_pause_and_the_pause_is_between_cycles(self):
        _sim, rows = supercell_run(2)
        pause = [r for r in rows if r["cloud_id"] is None]
        self.assertTrue(pause)
        self.assertTrue(all(r["step"] is None for r in pause))
        self.assertTrue(all(r["step"] in TOUR_IDS for r in rows if r["cloud_id"] is not None))
        self.assertTrue(all(not r["tors"] for r in pause))

    def test_restart_works_on_the_supercell_tab_and_other_tabs_are_unchanged(self):
        sim = ShowcaseSimulation(4, DEFAULT_W, DEFAULT_H, 30, "supercell")
        for _ in range(30 * 70):
            sim.step(DT)
        self.assertTrue(sim.clouds)
        sim.handle_command({"cmd": "tornado"}, [])
        sim.handle_command({"cmd": "spawn", "x": 400, "y": 200, "type": "supercell"}, [])
        self.assertEqual(len(sim.clouds), 1, "spawn is ignored on the stage")
        self.assertLessEqual(sim.tornadoes_total, 1, "the stage makes its own one tornado only")
        sim.handle_command({"cmd": "showcase", "restart": True}, [])
        f = sim.step(DT)
        self.assertEqual((f["clouds"], f["showcase"]["tour_step"], f.get("tornadoes")), ([], None, None))
        self.assertEqual(len(sim.particles), 0)
        steps = []
        for _ in range(30 * 60):
            f = sim.step(DT)
            if f["showcase"]["tour_step"] and (not steps or steps[-1] != f["showcase"]["tour_step"]):
                steps.append(f["showcase"]["tour_step"])
        self.assertEqual(steps[:3], ["towering", "mesocyclone", "mature"])
        self.assertEqual(f["showcase"]["cycle"], 1)
        sim.handle_command({"cmd": "showcase", "type": "cumulus"}, [])
        for _ in range(60):
            f = sim.step(DT)
        self.assertEqual(set(f["showcase"]), {"type", "cycle", "cloud_id"}, "other tabs carry no tour_step")
        self.assertNotIn("tornadoes", f)
        sim.handle_command({"cmd": "showcase", "type": "supercell"}, [])
        for _ in range(60):
            f = sim.step(DT)
        self.assertEqual(f["showcase"]["tour_step"], "towering")

    def test_deterministic_per_seed(self):
        a = ShowcaseSimulation(5, DEFAULT_W, DEFAULT_H, 30, "supercell")
        b = ShowcaseSimulation(5, DEFAULT_W, DEFAULT_H, 30, "supercell")
        for _ in range(30 * 100):
            self.assertEqual(json.dumps(a.step(DT), sort_keys=True), json.dumps(b.step(DT), sort_keys=True))


def longest_stall_s(fracs):
    """The longest time (s) a series of per-frame life fractions stays on one value."""
    best = run = 1
    for a, b in zip(fracs, fracs[1:]):
        run = run + 1 if b == a else 1
        best = max(best, run)
    return best * DT


class TestLifeBar(unittest.TestCase):
    """The frame's `lifetime` / `life_frac` of the stage cloud drive the screen's life bar ("N s old of about
    M s", "X % of its life"). The programmes hold the engine's life fraction still while it rains / while the
    tornado lives, and the frame used to pass that on: the bar ran to 77 % and stood still for up to a minute
    (the supercell's stood at 48 %, jumped to 90 % and the storm vanished there). The frame now reports the
    programme's planned whole life, so the bar moves steadily from 0 to about 100 %."""

    def check(self, label, ages, lifetimes, fracs, actual_life):
        self.assertTrue(all(b >= a for a, b in zip(fracs, fracs[1:])), f"{label}: the life fraction never goes back")
        self.assertLessEqual(max(b - a for a, b in zip(fracs, fracs[1:])), 0.0201, f"{label}: no jumps")
        self.assertLess(longest_stall_s(fracs), 4.5, f"{label}: the bar never stands still")
        self.assertGreaterEqual(fracs[-1], 0.95, f"{label}: the cloud is gone at the end of the bar")
        self.assertLessEqual(max(fracs), 1.0)
        for age, life, frac in zip(ages, lifetimes, fracs):
            self.assertAlmostEqual(frac, min(1.0, age / life), delta=0.0101, msg=f"{label}: life_frac = age / lifetime")
            self.assertLess(abs(life - actual_life), 0.08 * actual_life,
                            f"{label}: 'of about {life:.0f} s' at age {age:.0f} when the life was {actual_life:.0f} s")

    def test_rain_types_report_a_steady_life_fraction_and_the_real_length(self):
        for ty in RAIN_TYPES:
            for i, c in enumerate(rain_cycles(ty), 1):
                ages, lifetimes, fracs = zip(*c["rec"]["life_series"])
                self.check(f"{ty} cycle {i}", ages, lifetimes, fracs, c["life"])
                # the rain is a real share of the bar too, not a point on it
                raining = [f for (stage, _r), f in zip(c["series"], fracs) if stage == "precipitating"]
                self.assertGreater(raining[-1] - raining[0], 0.28, ty)

    def test_the_supercell_reports_a_steady_life_fraction_and_the_real_length(self):
        _sim, rows = supercell_run(2)
        for cyc in (1, 2):
            rr = [r for r in rows if r["cycle"] == cyc and r["cloud_id"] is not None]
            self.check(f"supercell cycle {cyc}", [r["age"] for r in rr], [r["lifetime"] for r in rr],
                       [r["life_frac"] for r in rr], rr[-1]["age"])

    def test_the_types_without_a_programme_keep_the_engine_clock(self):
        for ty in NO_PRECIP:
            r = cycle_of(ty)
            ages, lifetimes, fracs = zip(*r["life_series"])
            self.assertEqual(len(set(lifetimes)), 1, f"{ty}: the lifetime is the spawned one, unchanged")
            self.assertGreaterEqual(fracs[-1], 0.95)
            self.assertLess(longest_stall_s(fracs), 4.5)

    def test_the_engine_clock_is_still_held_under_the_report(self):
        """The fix is in what the frame says, not in how the cloud is driven: the engine's own life fraction is
        still held at HOLD_LF while it rains (that is what keeps the rain going)."""
        sim = ShowcaseSimulation(3, DEFAULT_W, DEFAULT_H, 30, "nimbostratus")
        held = 0
        while sim.world.t < 400.0:
            f = sim.step(DT)
            if f["deaths"]:
                break
            c = sim.sc_cloud
            if c is not None and c.stage == "precipitating" and abs(c.life_frac - HOLD_LF) < 0.005:
                held += 1
                self.assertLess(f["clouds"][0]["life_frac"], HOLD_LF + 0.02)
        self.assertGreater(held * DT, 20.0, "the engine clock is held for most of the rain")
