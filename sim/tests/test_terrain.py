"""Terrain heightmap: deterministic per seed, varies across seeds, four distinct kinds,
ground_y == lowest point, objects stand on it, particles land on it (never below),
orographic wind lift on windward slopes, lightning ends on the surface."""
import math
import random
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_terrain.py` (or sim.tests.test_terrain) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
from clouds import spawn_cloud
from objects import generate_world
from simulation import Simulation
from terrain import KINDS, MAX_RELIEF_FRAC, Terrain


def make(seed, kind="rolling", w=3840, h=1080):
    return Terrain.generate(random.Random(seed), w, h, kind)


def relief(t):
    return t.ground_y - t.min_y


class TestTerrain(unittest.TestCase):
    def test_deterministic_per_seed(self):
        for kind in KINDS:
            self.assertEqual(make(5, kind).y, make(5, kind).y)

    def test_different_across_seeds(self):
        for kind in KINDS:
            self.assertNotEqual(make(1, kind).y, make(2, kind).y)

    def test_length_step_and_ground_y_is_the_lowest_point(self):
        for kind in KINDS:
            for w in (1280, 3840, 9600):
                t = make(3, kind, w, 1080)
                self.assertEqual(t.step, 8)
                self.assertEqual(len(t.y), math.ceil(w / 8) + 1)
                self.assertEqual(t.ground_y, max(t.y))
                self.assertIsInstance(t.ground_y, int)
                self.assertTrue(all(isinstance(v, float) for v in t.y))

    def test_kinds_are_visibly_different_and_capped(self):
        avg = {}
        for kind in KINDS:
            vals = [relief(make(s, kind)) for s in range(1, 9)]
            avg[kind] = sum(vals) / len(vals)
            for s, v in zip(range(1, 9), vals):
                self.assertLessEqual(v, MAX_RELIEF_FRAC * 1080 + 1.0, f"{kind} seed {s} too tall")
        self.assertLess(avg["flat"], 30)
        self.assertLess(avg["flat"], avg["rolling"])
        self.assertLess(avg["rolling"], avg["hilly"])
        self.assertLess(avg["hilly"], avg["mountainous"])
        self.assertGreater(avg["mountainous"], 250)

    def test_a_finished_world_respects_the_documented_relief_cap(self):
        """generate() is exact, but the ponds dug by plan_ponds are up to 5 px below the lowest ground and
        normalize() used to carry that into the relief: 437 px vs the 432 cap at 3840x1080 (seed 5, no
        settlements), 485 vs 480 at 6400x1200, 565 vs 560 at 9600x1400 (testing pass 2026-10-09)."""
        known = [(5, 3840, 1080, "none"), (3, 3840, 1080, "sparse"), (4, 6400, 1200, "none"),
                 (3, 6400, 1200, "sparse"), (8, 9600, 1400, "normal"), (1, 9600, 1400, "normal")]
        cases = known + [(s, w, h, m) for (w, h) in ((1920, 900), (3840, 1080), (6400, 1200), (9600, 1400))
                         for m in ("none", "normal", "dense") for s in range(1, 7)]
        worst = 0.0
        for seed, w, h, mode in cases:
            t, _st, _objs = generate_world(random.Random(seed), w, h, "mountainous", mode)
            self.assertEqual(t.ground_y, max(t.y))
            worst = max(worst, relief(t) - MAX_RELIEF_FRAC * h)
            self.assertLessEqual(relief(t), MAX_RELIEF_FRAC * h + 0.1, (seed, w, h, mode))
        self.assertLessEqual(worst, 0.1)
        for kind in ("flat", "rolling", "hilly"):
            for seed in range(1, 6):
                t, _st, _objs = generate_world(random.Random(seed), 3840, 1080, kind, "normal")
                self.assertLessEqual(relief(t), MAX_RELIEF_FRAC * 1080 + 0.1, (kind, seed))

    def test_cap_relief_levels_only_ground_deeper_than_the_cap_allows(self):
        h = 1000
        cap = MAX_RELIEF_FRAC * h
        base = [500.0, 300.0, 200.0, 350.0, 600.0, 580.0, 600.0]
        t = Terrain(800, h, "mountainous", list(base))
        self.assertEqual(t.cap_relief(), 0, "relief 400 = the cap: nothing to do")
        self.assertEqual(t.y, base)
        t = Terrain(800, h, "mountainous", base[:5] + [604.0, 598.0])      # a pond pad 4 px too deep
        self.assertEqual(t.cap_relief(), 1)
        self.assertEqual(t.y, base[:5] + [200.0 + cap, 598.0])
        t = Terrain(800, h, "mountainous", base[:5] + [600.08, 600.0])      # within the 0.1 px rounding allowance
        self.assertEqual(t.cap_relief(), 0)
        t = Terrain(800, h, "preset", base[:5] + [650.0, 600.0])            # a region preset keeps its real heights
        self.assertEqual(t.cap_relief(), 0)
        self.assertEqual(t.y[5], 650.0)

    def test_height_at_interpolates_and_clamps(self):
        t = make(7, "hilly")
        for x in (0.0, 3.0, 8.0, 123.4, 1919.9, 3839.0, 3840.0):
            i = min(int(x / 8), len(t.y) - 2)
            f = min(1.0, x / 8 - i)
            self.assertAlmostEqual(t.height_at(x), t.y[i] + (t.y[i + 1] - t.y[i]) * f, places=9)
        self.assertEqual(t.height_at(-50.0), t.y[0])
        self.assertEqual(t.height_at(99999.0), t.y[-1])

    def test_slope_sign_rising_ground_is_negative(self):
        t = make(2, "mountainous")
        i = min(range(len(t.y) - 1), key=lambda k: t.y[k + 1] - t.y[k])   # steepest rise to the right
        self.assertLess(t.slope_at(i * 8 + 1.0), 0.0)

    def test_objects_sit_on_the_surface(self):
        for kind in KINDS:
            terr, _s, objs = generate_world(random.Random(4), 3840, 1080, kind, "normal")
            for o in objs:
                self.assertAlmostEqual(o["y"], terr.height_at(o["x"]), delta=0.11,
                                       msg=f"{o['kind']} id {o['id']} floats/sinks on {kind}")

    def test_world_ground_y_matches_terrain(self):
        sim = Simulation(seed=9, w=1920, h=900, fps=30, terrain_kind="mountainous")
        self.assertEqual(sim.world.ground_y, sim.terrain.ground_y)
        self.assertEqual(sim.hello_message()["world"]["ground_y"], max(sim.terrain.y))
        f = sim.step(1 / 30)
        self.assertEqual(f["world"]["ground_y"], sim.terrain.ground_y)

    def test_freezing_level_is_measured_from_the_lowest_ground(self):
        sim = Simulation(seed=9, w=1920, h=900, fps=30, terrain_kind="mountainous")
        sim.world.surface_temp_c = 1.0
        fy = sim.world.freezing_y
        self.assertLess(fy, sim.terrain.ground_y)
        self.assertGreater(sim.terrain.ground_y - sim.terrain.min_y, sim.terrain.ground_y - fy,
                           "mountain tops poke above a low freezing level")

    def test_particles_never_end_below_the_surface(self):
        sim = Simulation(seed=21, w=1280, h=720, fps=30, terrain_kind="mountainous")
        for k in range(6):
            sim.handle_command({"cmd": "spawn", "x": 150 + k * 200, "y": 200}, [])
        sim.handle_command({"cmd": "wind", "delta": 6.0}, [])    # push rain into the slopes
        worst = -1e9
        n = 0
        for _ in range(2500):
            f = sim.step(1 / 30)
            for p in f["particles"]:
                n += 1
                worst = max(worst, p[2] - sim.terrain.height_at(p[1]))
        self.assertGreater(n, 5000, "scenario produced too few particles to mean anything")
        self.assertLessEqual(worst, 0.101, f"a particle ended {worst:.2f}px below the surface")

    def test_wind_rises_on_windward_slopes_and_sinks_on_lee_slopes(self):
        sim = Simulation(seed=2, w=3840, h=1080, fps=30, terrain_kind="mountainous")
        wind = sim.step(1 / 30)["wind"]
        cell, cols = wind["cell"], wind["cols"]
        up, down = [], []
        for c in range(cols):
            cx = (c + 0.5) * cell
            slope = sim.terrain.slope_at(cx)
            # lowest row whose centre is above the surface
            for r in range(wind["rows"]):
                cy = (r + 0.5) * cell
                above = sim.terrain.height_at(cx) - cy
                if 0 < above < 140:
                    v = wind["v"][r * cols + c]
                    if slope < -0.25:
                        up.append(v)
                    elif slope > 0.25:
                        down.append(v)
        self.assertGreater(len(up), 2)
        self.assertGreater(len(down), 2)
        self.assertLess(sum(up) / len(up), -0.5, "windward slopes lift the air (v < 0)")
        self.assertGreater(sum(down) / len(down), 0.3, "lee slopes sink the air (v > 0)")

    def test_lightning_ends_on_the_surface(self):
        sim = Simulation(seed=3, w=1280, h=720, fps=30, terrain_kind="mountainous")
        sim.clouds.clear()
        cb = spawn_cloud(99, 3, 99, 640.0, None, 0.0, sim.signatures, world_w=1280, world_h=720,
                         terrain=sim.terrain, cloud_type="cumulonimbus")
        cb.stage = "precipitating"
        cb.form = 1.0
        cb.refresh()
        sim.clouds.append(cb)
        events = []
        for _ in range(3000):
            sim._update_lightning(1 / 30, events)
            if sim._lightning:
                break
        self.assertTrue(sim._lightning, "a precipitating cumulonimbus should throw lightning")
        for lt in sim._lightning:
            x, y = lt["pts"][-1]
            self.assertAlmostEqual(y, sim.terrain.height_at(x), delta=0.6)
            for px, py in lt["pts"]:
                self.assertLessEqual(py, sim.terrain.height_at(px) + 0.6, "bolt dips below the ground")

    def test_streamlines_stay_above_the_surface(self):
        sim = Simulation(seed=6, w=3840, h=1080, fps=30, terrain_kind="mountainous")
        f = sim.step(1 / 30)
        self.assertGreaterEqual(len(f["streamlines"]), 20, "streamline count scales with width")
        for sl in f["streamlines"]:
            for x, y in sl["pts"]:
                self.assertLess(y, sim.terrain.height_at(x))


if __name__ == "__main__":
    unittest.main()
