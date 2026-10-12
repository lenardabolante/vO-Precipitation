"""Protocol v2.1: fair-weather clouds, developing into rain clouds, and cumulus towering into
cumulonimbus with a smooth `morph`. Outcomes are forced by patching the hazard constants (1e9 =
"as soon as eligible", 0 = never) so the tests do not hunt for seeds."""
import glob
import json
import os
import tempfile
import unittest
from unittest import mock

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_fair_and_morph.py` (or sim.tests.test_fair_and_morph) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import clouds
from clouds import FAIR_PARAMS, STAGES, spawn_cloud, update_cloud
from savestate import load_state, save_state
from simulation import Simulation

DT = 1.0 / 30
FAIR_TYPES = tuple(FAIR_PARAMS)
NEW_CLOUD_KEYS = ("fair", "fill_cap", "from_type", "morph", "morph_v", "morph_from", "morph_to")
PROJECT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# any save the user has made (written before v2.1, so without fair / morph): read-only, only ever copied
REAL_SAVES = sorted(glob.glob(os.path.join(PROJECT, "saves", "*", "state.sav")))


def canon(x):
    return json.dumps(x, sort_keys=True)


def lone(ctype, seed=1, w=1920, h=900, fair=None):
    sim = Simulation(seed=seed, w=w, h=h, fps=30)
    sim.clouds.clear()
    c = spawn_cloud(500, seed, 500, w * 0.5, None, 0.0, sim.signatures, world_w=w, world_h=h,
                    terrain=sim.terrain, cloud_type=ctype, fair=fair)
    return sim, c


def run(sim, c, max_frames=20000):
    """Step one cloud alone to its death; yields (frame dict, events of that tick) after every update."""
    t = 0.0
    for _ in range(max_frames):
        t += DT
        sim.world.t = t
        ev = []
        alive = update_cloud(c, DT, sim.world, sim._build_wind(), sim.terrain, t, ev)
        if not alive:
            return
        yield Simulation._cloud_to_protocol(c), ev
    raise AssertionError("cloud never died")


class TestFairClouds(unittest.TestCase):
    def test_fair_cloud_that_never_develops_skips_precipitating(self):
        with mock.patch.object(clouds, "DEVELOP_HAZARD", 0.0), mock.patch.object(clouds, "TOWER_HAZARD", 0.0):
            for ctype in FAIR_TYPES:
                sim, c = lone(ctype, seed=4, fair=True)
                self.assertTrue(c.fair, ctype)
                dark_max = 0.0
                stages = []
                for fr, _ev in run(sim, c):
                    self.assertTrue(fr["fair"], ctype)
                    self.assertEqual((fr["precip_type"], fr["precip_rate"], fr["total_precip_mm"]),
                                     ("none", 0.0, 0.0), ctype)
                    dark_max = max(dark_max, fr["darkness"])
                    if not stages or stages[-1] != fr["stage"]:
                        stages.append(fr["stage"])
                self.assertEqual(stages, ["forming", "growing", "mature", "dissipating"], ctype)
                self.assertNotIn("precipitating", c.stage_times)
                self.assertEqual(c.total_precip_mm, 0.0)
                self.assertEqual(c.precip_type, "none")
                self.assertLess(dark_max, 0.36, f"{ctype}: a fair cloud stays light")

    def test_cirrus_and_altocumulus_are_always_fair_and_unchanged_in_look(self):
        for ctype in ("cirrus", "altocumulus"):
            sim, c = lone(ctype, seed=2, fair=False)       # can't be turned off for them
            self.assertTrue(c.fair)
            sim, c = lone(ctype, seed=2)
            self.assertTrue(c.fair)
            self.assertEqual(c.fill_cap, 1.0, "thin ice clouds keep their full reservoirs")
            for fr, ev in run(sim, c):
                self.assertTrue(fr["fair"])
                self.assertNotIn("developing", " ".join(ev))
            self.assertNotIn("precipitating", c.stage_times)

    def test_cumulonimbus_and_nimbostratus_are_never_fair(self):
        for ctype in ("cumulonimbus", "nimbostratus"):
            for seed in range(1, 12):
                _sim, c = lone(ctype, seed=seed)
                self.assertFalse(c.fair, f"{ctype} seed {seed}")
                self.assertEqual(c.fill_cap, 1.0)

    def test_most_of_the_four_types_are_born_fair_but_not_all(self):
        born_fair = {t: 0 for t in FAIR_TYPES}
        n = 40
        for ctype in FAIR_TYPES:
            for seed in range(1, n + 1):
                _sim, c = lone(ctype, seed=seed, w=1280, h=720)
                born_fair[ctype] += c.fair
        for ctype, k in born_fair.items():
            self.assertGreater(k, n * 0.6, f"{ctype} is mostly fair ({k}/{n})")
            self.assertLess(k, n, f"{ctype}: a few are born as ordinary rain clouds")

    def test_fair_is_in_every_frame_and_frames_validate(self):
        sim = Simulation(seed=9, w=1920, h=900, fps=30)
        ids = set(range(1, len(sim.objects.objs) + 1))
        seen = set()
        for _ in range(900):
            msg = sim.step(DT)
            S.validate_frame(msg, 1920, 900, ids)
            for cd in msg["clouds"]:
                self.assertIsInstance(cd["fair"], bool)
                seen.add(cd["fair"])
                if cd["type"] in ("cumulonimbus", "nimbostratus"):
                    self.assertFalse(cd["fair"])
                self.assertEqual("from_type" in cd, "morph" in cd)
        self.assertEqual(seen, {True, False})


class TestDeveloping(unittest.TestCase):
    def test_a_fair_cloud_develops_smoothly_then_rains(self):
        with mock.patch.object(clouds, "DEVELOP_HAZARD", 1e9), mock.patch.object(clouds, "TOWER_HAZARD", 0.0):
            for ctype in FAIR_TYPES:
                sim, c = lone(ctype, seed=3, fair=True)
                prev = None
                fair_dark = None
                events = []
                dev_frame = None
                worst = 0.0
                rained = False
                for i, (fr, ev) in enumerate(run(sim, c)):
                    rained = rained or fr["precip_type"] != "none"
                    events += [e for e in ev if "developing" in e]
                    if prev is not None:
                        worst = max(worst, abs(fr["darkness"] - prev["darkness"]),
                                    abs(fr["opacity"] - prev["opacity"]))
                        if prev["fair"] and not fr["fair"]:
                            dev_frame = i
                            fair_dark = prev["darkness"]
                        if not prev["fair"]:
                            self.assertFalse(fr["fair"], "fair never comes back")
                    prev = fr
                self.assertIsNotNone(dev_frame, ctype)
                self.assertEqual(len(events), 1, "exactly one event line")
                self.assertIn(c.name, events[0])
                self.assertLess(worst, 0.02, f"{ctype}: darkness/opacity steps when developing")
                self.assertIn("precipitating", c.stage_times, ctype)
                self.assertGreater(c.total_precip_mm, 0.0, ctype)
                self.assertGreater(c.peak_water, 0.0)
                self.assertTrue(rained, "it rains once developed")
                self.assertGreater(fair_dark, 0.0)

    def test_darkness_rises_after_developing(self):
        with mock.patch.object(clouds, "DEVELOP_HAZARD", 1e9), mock.patch.object(clouds, "TOWER_HAZARD", 0.0):
            sim, c = lone("stratocumulus", seed=5, fair=True)
            before = None
            peak_after = 0.0
            for fr, _ev in run(sim, c):
                if fr["fair"]:
                    before = fr["darkness"]
                else:
                    peak_after = max(peak_after, fr["darkness"])
            self.assertGreater(peak_after, before + 0.1)

    def test_development_happens_at_different_points_in_each_cloud_life(self):
        fracs = []
        for seed in range(1, 17):
            sim, c = lone("stratus", seed=seed, w=1280, h=720, fair=True)
            for fr, ev in run(sim, c):
                if any("developing" in e for e in ev):
                    fracs.append(fr["life_frac"])
                    break
        self.assertGreater(len(fracs), 5, "some of them develop")
        self.assertLess(len(fracs), 16, "and some never do")
        self.assertGreater(max(fracs) - min(fracs), 0.12, fracs)
        self.assertGreater(len({round(f, 2) for f in fracs}), 4, fracs)
        for f in fracs:
            self.assertTrue(0.05 <= f <= 0.5, f)

    def test_no_global_random_is_used(self):
        import random
        sim, c = lone("cumulus", seed=7, fair=True)
        state = random.getstate()
        for _ in run(sim, c):
            pass
        self.assertEqual(random.getstate(), state, "the cloud lifecycle must only use cloud.rng")


class TestTowering(unittest.TestCase):
    def tower_run(self, seed, ctype="cumulus", w=1920, h=900):
        """Run one cumulus that towers as soon as it is eligible; returns every frame and its events."""
        with mock.patch.object(clouds, "TOWER_HAZARD", 1e9), mock.patch.object(clouds, "DEVELOP_HAZARD", 0.0):
            sim, c = lone(ctype, seed=seed, w=w, h=h)
            return c, [(fr, ev) for fr, ev in run(sim, c)]

    def test_switch_frame_has_no_jump(self):
        for seed in (1, 2, 3, 4):
            c, frames = self.tower_run(seed)
            idx = next(i for i, (fr, _e) in enumerate(frames) if fr["type"] == "cumulonimbus")
            prev, cur = frames[idx - 1][0], frames[idx][0]
            self.assertEqual(cur["from_type"], "cumulus")
            self.assertEqual(cur["morph"], 0.0)
            self.assertFalse(cur["fair"])
            self.assertEqual((cur["id"], cur["seed"]), (prev["id"], prev["seed"]))
            self.assertTrue(cur["name"].startswith("Cb-0500 \""), cur["name"])
            self.assertEqual(cur["name"].split(" ", 1)[1], prev["name"].split(" ", 1)[1], "quoted part kept")
            # puffs: same prefix (index and value, within one frame of normal motion), extras at r ~ 0
            self.assertGreater(len(cur["puffs"]), len(prev["puffs"]))
            for a, b in zip(cur["puffs"], prev["puffs"]):
                self.assertLess(abs(a[2] - b[2]), 2.5)
                self.assertLess(abs(a[0] - b[0]) + abs(a[1] - b[1]), 6.0)
                self.assertEqual(a[3], b[3], "shade of an existing puff never changes")
            for extra in cur["puffs"][len(prev["puffs"]):]:
                self.assertLess(extra[2], 0.5, "appended puffs start at r ~ 0")
            # bounds, colour, opacity, darkness continuous
            for k, tol in (("x", 3.0), ("y", 3.0), ("w", 8.0), ("h", 4.0), ("opacity", 0.02), ("darkness", 0.02),
                           ("life_frac", 0.02), ("updraft", 0.8)):
                self.assertLess(abs(cur[k] - prev[k]), tol, f"seed {seed}: {k} jumped on the switch frame")
            self.assertEqual(cur["tint"], prev["tint"], "tint is still the cumulus's on the switch frame")
            self.assertEqual(cur["anvil"], 0.0)
            self.assertEqual(sum(1 for fr, _e in frames if fr["type"] == "cumulus"), idx)

    def test_morph_is_monotonic_and_the_stage_never_goes_backwards(self):
        for seed in (1, 2, 5):
            c, frames = self.tower_run(seed)
            last_morph = 0.0
            last_stage = 0
            morphing = [(fr, ev) for fr, ev in frames if "morph" in fr]
            self.assertTrue(morphing)
            for fr, _ev in morphing:
                self.assertGreaterEqual(fr["morph"], last_morph - 1e-9)
                last_morph = fr["morph"]
                self.assertEqual(fr["from_type"], "cumulus")
                self.assertEqual(fr["type"], "cumulonimbus")
                if fr["stage"] == "precipitating":
                    self.assertGreaterEqual(fr["morph"], clouds.MORPH_READY, "no rain before the tower is built")
            for fr, _ev in frames:
                i = STAGES.index(fr["stage"])
                self.assertGreaterEqual(i, last_stage)
                last_stage = i
            self.assertGreater(last_morph, 0.99)
            self.assertIn("precipitating", c.stage_times)
            self.assertGreater(c.total_precip_mm, 0.0)
            self.assertTrue(c.is_cb)
            self.assertEqual(c.from_type, "cumulus")

    def test_it_becomes_a_real_cumulonimbus(self):
        c, frames = self.tower_run(2)
        last = frames[-1][0]
        self.assertGreater(max(fr["anvil"] for fr, _e in frames), 0.6, "an anvil spreads")
        self.assertLessEqual(len(c.layout), clouds.MAX_PUFFS)
        mid = next(fr for fr, _e in frames if fr.get("morph", 0) > 0.99 and fr["form"] > 0.95)
        first = next(fr for fr, _e in frames if fr["type"] == "cumulonimbus")
        self.assertGreater(mid["h"], 1.8 * first["h"], "much taller")
        self.assertGreater(mid["w"], 1.5 * first["w"], "much wider")
        self.assertLess(sum(mid["tint"]), sum(first["tint"]) - 100, "greyer")
        self.assertGreater(c.lifetime, 95.0)
        self.assertLess(last["form"], 0.1)

    @staticmethod
    def live_groups(c):
        """(drive, [dx, dy, r]) of every puff of the cloud object, for the connectivity checks."""
        return [(lp[0], p) for lp, p in zip(c.layout, c.puffs)]

    def test_towered_cloud_is_a_connected_body_with_a_grown_tower(self):
        """At morph = 1 the appended puffs must have swollen: a stack of turrets joins the old cumulus to
        the anvil. (A bug once left every drive-2 puff at r = 0 with the anvil floating above.)"""
        for seed in (1, 2, 3, 4, 5, 6):
            for kind, (w, h) in (("small", (1920, 900)), ("big", (3840, 1080))):
                with mock.patch.object(clouds, "TOWER_HAZARD", 1e9), mock.patch.object(clouds, "DEVELOP_HAZARD", 0.0):
                    sim, c = lone("cumulus", seed=seed, w=w, h=h)
                    t = 0.0
                    best = None                 # the frame (morph ~ 1) where the anvil is furthest out
                    for _ in range(20000):
                        t += DT
                        sim.world.t = t
                        if not update_cloud(c, DT, sim.world, sim._build_wind(), sim.terrain, t, []):
                            break
                        if c.morph_to is not None and c.morph > 0.97 and c.form > 0.97:
                            if best is None or c.anvil > best[0]:
                                best = (c.anvil, [list(p) for p in c.puffs], c.base_h)
                    label = f"seed {seed} {kind}"
                    self.assertIsNotNone(best, f"{label}: reached morph ~1")
                    self.assertGreater(best[0], 0.8 * c.anvil_max, f"{label}: anvil near its max")
                    c.puffs = best[1]
                    groups = self.live_groups(c)
                    tower = [p for d, p in groups if d == 2]
                    anvil = [p for d, p in groups if d == 1]
                    self.assertGreaterEqual(len(tower), 5, label)
                    self.assertTrue(anvil)
                    for p in tower:
                        self.assertGreater(p[2], 3.0, f"{label}: a tower puff never grew {p}")
                    for p in anvil:
                        self.assertGreater(p[2], 3.0, f"{label}: an anvil puff never grew {p}")
                    # puffs that have swollen to a real size (a puff still near r = 0 is just emerging)
                    live = [p for lp, p in zip(c.layout, c.puffs) if p[2] > 0.25 * lp[5]]
                    # vertically connected: the circles' dy extents merge into one interval
                    spans = sorted((p[1] - p[2], p[1] + p[2]) for p in live)
                    reach = spans[0][1]
                    for lo, hi in spans[1:]:
                        self.assertLessEqual(lo, reach + 1e-6, f"{label}: a vertical gap above dy={reach:.0f}")
                        reach = max(reach, hi)
                    # every live puff (anvil ones included) touches another live puff, and the whole thing is
                    # one connected cluster of overlapping circles
                    def touching(a, b):
                        return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 <= (a[2] + b[2]) ** 2
                    for p in live:
                        self.assertTrue(any(touching(p, q) for q in live if q is not p),
                                        f"{label}: puff {p} floats alone")
                    seen = {0}
                    todo = [0]
                    while todo:
                        k = todo.pop()
                        for m, q in enumerate(live):
                            if m not in seen and touching(live[k], q):
                                seen.add(m)
                                todo.append(m)
                    self.assertEqual(len(seen), len(live), f"{label}: the puffs form separate clusters")
                    # the tower really joins the old cumulus to the anvil: its top is above the old cumulus and
                    # the anvil sits at the tower's top
                    old_top = min(p[1] - p[2] for p in c.puffs[:len(c.layout) - len(tower) - len(anvil)] if p[2] > 1.0)
                    self.assertLess(min(p[1] - p[2] for p in tower), old_top - 20.0, label)
                    tower_top = min(p[1] - p[2] for p in tower)
                    anvil_mid = sum(p[1] for p in anvil) / len(anvil)
                    self.assertLess(abs(anvil_mid - tower_top), 0.35 * best[2], label)

    def test_tower_stays_below_the_world_top_and_above_the_terrain(self):
        for seed, kind in ((1, "rolling"), (2, "mountainous"), (3, "hilly")):
            sim = Simulation(seed=seed, w=1920, h=900, fps=30, terrain_kind=kind)
            sim.clouds.clear()
            c = spawn_cloud(500, seed, 500, 960.0, None, 0.0, sim.signatures, world_w=1920, world_h=900,
                            terrain=sim.terrain, cloud_type="cumulus")
            with mock.patch.object(clouds, "TOWER_HAZARD", 1e9), mock.patch.object(clouds, "DEVELOP_HAZARD", 0.0):
                top = 1e9
                t = 0.0
                settled = 0
                for _ in range(20000):
                    t += DT
                    sim.world.t = t
                    if not update_cloud(c, DT, sim.world, sim._build_wind(), sim.terrain, t, []):
                        break
                    if c.morph > 0.0 and c.form > 0.9:
                        top = min(top, min(c.y + p[1] - p[2] for p in c.puffs if p[2] > 1.0))
                    if c.morph > 0.995 and c.form > 0.9:
                        settled += 1
                        if settled > 10 * 30:        # the base eases clear of the ground within seconds
                            half = c.base_w * 0.35
                            surf = min(sim.terrain.height_at(c.ax + d) for d in (-half, 0, half))
                            self.assertLessEqual(c.ay, surf - 40.0 + 2.0, f"{kind}: base inside the ground")
                self.assertGreater(top, 0.0, f"{kind}: the tower stays inside the world")
                self.assertEqual(c.from_type, "cumulus", f"{kind}: it did tower")

    def test_no_tower_without_room_above(self):
        sim, c = lone("cumulus", seed=3)
        c.ay = c.ay_pref = 120.0          # nearly at the top of the world
        layout = list(c.layout)
        ev = []
        self.assertFalse(clouds.begin_tower(c, sim.world, sim.terrain, 1.0, ev))
        self.assertEqual((c.type, c.from_type, c.layout, ev), ("cumulus", None, layout, []))

    def test_only_cumulus_tower_and_only_once(self):
        with mock.patch.object(clouds, "TOWER_HAZARD", 1e9):
            for ctype in ("stratus", "stratocumulus", "altostratus", "nimbostratus", "cumulonimbus"):
                sim, c = lone(ctype, seed=2)
                for fr, _ev in run(sim, c):
                    self.assertNotIn("from_type", fr, ctype)
            c, frames = self.tower_run(3)
            self.assertEqual(sum(1 for _fr, ev in frames for e in ev if "towering" in e), 1)
            sim, c2 = lone("cumulus", seed=3)
            c2.from_type = "cumulus"
            self.assertFalse(clouds.begin_tower(c2, sim.world, sim.terrain, 1.0, []))

    def test_event_lines_and_deaths(self):
        # (opens at 14 h: the Phase O day cycle ages a storm that towers in the morning ten times faster, so one forced at
        # the default 7.2 h opening hour would die before it rained)
        sim = Simulation(seed=2, w=1920, h=900, fps=30, hour=14.0)
        sim.clouds.clear()
        c = spawn_cloud(500, 2, 500, 960.0, None, 0.0, sim.signatures, world_w=1920, world_h=900,
                        terrain=sim.terrain, cloud_type="cumulus", fair=True)
        sim.clouds.append(c)
        sim.next_cloud_id = 501
        lines, deaths = [], []
        with mock.patch.object(clouds, "TOWER_HAZARD", 1e9):
            for _ in range(6000):
                msg = sim.step(DT)
                lines += [e for e in msg["events"] if "Cu-0500" in e or "Cb-0500" in e]
                deaths += [d for d in msg["deaths"] if d["id"] == 500]
                if deaths:
                    break
        tower = [e for e in lines if "towering" in e]
        self.assertEqual(len(tower), 1, lines)
        self.assertRegex(tower[0], r'^t=\d+\.\d Cu-0500 "[A-Za-z]+ [A-Za-z]+" began towering into a '
                                   r'cumulonimbus \(now Cb-0500 "[A-Za-z]+ [A-Za-z]+"\)$')
        self.assertEqual(len(deaths), 1)
        d = deaths[0]
        self.assertEqual((d["type"], d["from_type"]), ("cumulonimbus", "cumulus"))
        self.assertTrue(d["name"].startswith("Cb-0500"))
        self.assertGreater(d["total_precip_mm"], 0.0)


class TestStateCompatibility(unittest.TestCase):
    @staticmethod
    def strip_v21(state):
        for cs in state["clouds"]:
            for k in NEW_CLOUD_KEYS:
                cs.pop(k, None)
        return state

    def test_old_format_state_loads_and_keeps_old_behaviour(self):
        sim = Simulation(seed=11, w=1920, h=900, fps=30)
        for _ in range(500):
            sim.step(DT)
        state = self.strip_v21(json.loads(json.dumps(sim.to_state())))
        old_ids = {cs["id"] for cs in state["clouds"]}
        self.assertTrue(old_ids)
        b = Simulation.from_state(state)
        for cl in b.clouds:
            self.assertFalse(cl.fair)
            self.assertFalse(cl.from_type)
            self.assertEqual(cl.fill_cap, 1.0)
        rained = set()
        with mock.patch.object(clouds, "TOWER_HAZARD", 1e9), mock.patch.object(clouds, "DEVELOP_HAZARD", 1e9):
            for _ in range(2500):
                msg = b.step(DT)
                for cd in msg["clouds"]:
                    if cd["id"] in old_ids:
                        self.assertFalse(cd["fair"])
                        self.assertNotIn("from_type", cd, "a pre-v2.1 cloud never morphs")
                        self.assertNotIn("morph", cd)
                        if cd["stage"] == "precipitating":
                            rained.add(cd["id"])
                for d in msg["deaths"]:
                    if d["id"] in old_ids:
                        self.assertNotIn("from_type", d)
        self.assertTrue(rained, "old clouds still reach the precipitating stage")

    def test_real_user_saves_load_and_run_from_a_copy(self):
        if not REAL_SAVES:
            self.skipTest("no user save present")
        for real in REAL_SAVES:
            with tempfile.TemporaryDirectory() as tmp:
                copy_path = os.path.join(tmp, "state.sav")
                with open(real, "rb") as src, open(copy_path, "wb") as dst:
                    dst.write(src.read())
                sim = Simulation.from_state(load_state(copy_path))
                self.assertTrue(sim.clouds)
                ids = set(range(1, len(sim.objects.objs) + 1))
                for _ in range(100):
                    S.validate_frame(sim.step(DT), sim.world.w, sim.world.h, ids)

    def test_new_state_round_trips_through_json_unchanged(self):
        with mock.patch.object(clouds, "TOWER_HAZARD", 1e9):
            sim = Simulation(seed=4, w=1920, h=900, fps=30, hour=14.0)      # (14 h, see test_event_lines_and_deaths)
            for _ in range(900):
                sim.step(DT)
        state = sim.to_state()
        self.assertEqual(canon(json.loads(json.dumps(state))), canon(state))
        self.assertTrue(any(cs["from_type"] == "cumulus" for cs in state["clouds"]), "a morph was saved")


class TestSaveLoadMidChange(unittest.TestCase):
    def check(self, hazards, ready, label):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "s", "state.sav")
            with mock.patch.object(clouds, "TOWER_HAZARD", hazards[0]), \
                    mock.patch.object(clouds, "DEVELOP_HAZARD", hazards[1]):
                sim = Simulation(seed=6, w=1280, h=720, fps=30)
                sim.clouds.clear()
                c = spawn_cloud(500, 6, 500, 640.0, None, 0.0, sim.signatures, world_w=1280, world_h=720,
                                terrain=sim.terrain, cloud_type="cumulus", fair=True)
                sim.clouds.append(c)
                sim.next_cloud_id = 501
                for _ in range(6000):
                    sim.step(DT)
                    if ready(c):
                        break
                else:
                    self.fail(f"{label}: never reached the mid-change state")
                ok, err = save_state(sim.to_state(), path)
                self.assertTrue(ok, err)
                b = Simulation.from_state(load_state(path))
                bad = []
                for i in range(450):
                    fa, fb = sim.step(DT), b.step(DT)
                    if i == 0:
                        fa = {k: v for k, v in fa.items() if k not in ("ground", "water", "flood", "flow")}
                        fb = {k: v for k, v in fb.items() if k not in ("ground", "water", "flood", "flow")}
                    if canon(fa) != canon(fb):
                        bad.append(i)
                self.assertEqual(bad, [], f"{label}: frames differ after load at {bad[:5]}")
                self.assertEqual(canon(sim.to_state()), canon(b.to_state()))

    def test_save_load_in_the_middle_of_a_morph_is_frame_identical(self):
        self.check((1e9, 0.0), lambda c: c.morph_to is not None and 0.2 < c.morph < 0.6, "morph")

    def test_save_load_while_a_cloud_is_developing_is_frame_identical(self):
        self.check((0.0, 1e9), lambda c: (not c.fair) and 0.3 < c.fill_cap < 0.8, "develop")

    def test_save_load_of_fair_clouds_with_real_hazards(self):
        self.check((clouds.TOWER_HAZARD, clouds.DEVELOP_HAZARD), lambda c: c.age > 20.0, "default hazards")


class TestEventsAreDeterministic(unittest.TestCase):
    def test_same_seed_same_frames_including_fair_and_morph(self):
        def trace():
            sim = Simulation(seed=21, w=1280, h=720, fps=30)
            out = []
            for _ in range(900):
                m = sim.step(DT)
                out.append(canon([m["clouds"], m["events"], m["deaths"]]))
            return out

        with mock.patch.object(clouds, "TOWER_HAZARD", 4.0):
            self.assertEqual(trace(), trace())


if __name__ == "__main__":
    unittest.main()
