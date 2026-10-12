"""All 5 stages (forming, growing, mature, precipitating, dissipating) are
reached within a reasonably long headless run, both on live clouds and in
`deaths[].stage_times`."""
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_lifecycle.py` (or sim.tests.test_lifecycle) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
from simulation import Simulation

ALL_STAGES = {"forming", "growing", "mature", "precipitating", "dissipating"}


class TestLifecycle(unittest.TestCase):
    def test_all_stages_reached(self):
        sim = Simulation(seed=7, w=1280, h=720, fps=30)
        stages_seen = set()
        dt = 1.0 / 30
        for _ in range(4000):
            msg = sim.step(dt)
            for c in msg["clouds"]:
                stages_seen.add(c["stage"])
            for d in msg["deaths"]:
                stages_seen.update(d["stage_times"].keys())
            if stages_seen == ALL_STAGES:
                break
        self.assertEqual(stages_seen, ALL_STAGES,
                          f"missing stages: {ALL_STAGES - stages_seen}")

    def test_deaths_have_well_formed_stage_times_and_fields(self):
        sim = Simulation(seed=99, w=1280, h=720, fps=30)
        dt = 1.0 / 30
        deaths = []
        for _ in range(4000):
            msg = sim.step(dt)
            deaths.extend(msg["deaths"])
            if len(deaths) >= 2:
                break
        self.assertTrue(deaths, "expected at least one cloud death within 4000 frames")
        for d in deaths:
            for key in ("id", "name", "type", "born", "died", "lifetime",
                        "peak_water", "total_precip_mm", "precip_type",
                        "stage_times", "puffs", "tint"):
                self.assertIn(key, d)
            self.assertIn("forming", d["stage_times"])
            self.assertLessEqual(d["born"], d["died"])
            self.assertEqual(len(d["tint"]), 3)

    def test_cloud_population_stays_in_expected_band(self):
        sim = Simulation(seed=5, w=1280, h=720, fps=30)
        dt = 1.0 / 30
        counts = []
        for i in range(3000):
            msg = sim.step(dt)
            if i > 60:  # allow the run to settle past the initial seeding
                counts.append(msg["stats"]["clouds_alive"])
        self.assertTrue(counts)
        self.assertLessEqual(max(counts), 7)     # MAX_CLOUDS scales with width: 7 at 1280
        self.assertGreaterEqual(min(counts), 1)  # never fully empty for long

    def test_default_world_holds_roughly_9_to_21_clouds(self):
        sim = Simulation(seed=5, w=3840, h=1080, fps=30)
        counts = []
        dt = 1.0 / 30
        for i in range(2000):
            msg = sim.step(dt)
            if i > 60:
                counts.append(msg["stats"]["clouds_alive"])
        self.assertLessEqual(max(counts), 21)
        self.assertGreaterEqual(min(counts), 9)

    def test_cloud_counts_scale_with_world_width(self):
        small = Simulation(seed=5, w=1280, h=720, fps=30)
        big = Simulation(seed=5, w=9600, h=1400, fps=30)
        self.assertEqual((small.min_clouds, small.max_clouds), (1, 7))     # hard floor / ceiling; the climate steers in between
        self.assertGreater(big.max_clouds, 3 * small.max_clouds)
        self.assertGreater(len(big.clouds), 2 * len(small.clouds))


class TestSandboxRainLasts(unittest.TestCase):
    """Regression (2026-10-09 test pass). Measured before the fix (6 seeds x 900 s): a sandbox cumulonimbus
    rained 9.5 s of an 84 s life and a nimbostratus 9.4 s of 109 s, then faded at about half its stated
    lifetime. The sandbox now keeps a raining cloud raining until life_frac 0.8 (clouds.update_cloud,
    `sustained`); the showcase stage is not touched (it passes nothing and runs its own rain programme)."""

    def _life(self, ctype):
        sim = Simulation(seed=21, w=3840, h=1080, fps=30, hour=11.0)
        sim.handle_command({"cmd": "spawn", "x": 400, "y": 320, "type": ctype}, [])
        cid = sim.clouds[-1].id
        planned = sim.clouds[-1].lifetime
        rain = rate_frames = 0
        for _ in range(30 * 400):
            f = sim.step(1.0 / 30)
            c = next((c for c in f["clouds"] if c["id"] == cid), None)
            if c is None:
                d = next(d for d in f["deaths"] if d["id"] == cid)
                return planned, d["lifetime"], rain / 30.0, rate_frames / 30.0, d["total_precip_mm"]
            if c["stage"] == "precipitating":
                rain += 1
                rate_frames += c["precip_rate"] > 0.01
        self.fail(f"the {ctype} never died")

    def test_storm_clouds_rain_for_a_real_share_of_their_life_and_live_it_out(self):
        for ctype in ("cumulonimbus", "nimbostratus"):
            with self.subTest(ctype):
                planned, lived, rain_s, falling_s, mm = self._life(ctype)
                self.assertGreaterEqual(rain_s, 25.0, f"{ctype} rained {rain_s:.1f} s of {lived:.1f} s")
                self.assertGreaterEqual(rain_s / lived, 0.25, f"{ctype} rained {rain_s:.1f} s of {lived:.1f} s")
                self.assertGreaterEqual(falling_s, 0.95 * rain_s, "it really rains for the whole stage")
                self.assertGreaterEqual(lived, 0.85 * planned, f"{ctype} lived {lived:.1f} of {planned:.1f} planned s")
                self.assertGreater(mm, 0.0)

    def test_the_rain_rate_falls_to_zero_once_the_rain_stage_is_over(self):
        """Regression (2026-10-09): the dissipating stage rounded precip_rate to 3 places every tick, and
        round(0.018 * (1 - 0.8 / 30), 3) == 0.018, so the rate stuck at 0.018 (0.037 at speed 0.5, 0.074 at
        0.25) until the cloud died. At every speed it must reach 0 within 15 s of the rain ending."""
        for speed in (1.0, 0.5, 0.25):
            with self.subTest(speed=speed):
                sim = Simulation(seed=21, w=3840, h=1080, fps=30, hour=11.0)
                sim.handle_command({"cmd": "spawn", "x": 400, "y": 320, "type": "cumulonimbus"}, [])
                cid = sim.clouds[-1].id
                sim.handle_command({"cmd": "speed", "value": 8.0}, [])
                ended = None
                last = None
                for _ in range(30 * 400):
                    f = sim.step(1.0 / 30)
                    c = next((c for c in f["clouds"] if c["id"] == cid), None)
                    if c is None:
                        break
                    if c["stage"] == "dissipating":
                        if ended is None:
                            ended = f["t"]
                            sim.handle_command({"cmd": "speed", "value": speed}, [])
                        elif f["t"] - ended >= 15.0:
                            last = c["precip_rate"]
                            break
                self.assertIsNotNone(ended, "it reached the dissipating stage")
                self.assertEqual(last, 0.0, f"precip_rate {last} 15 s after the rain ended at speed {speed}")

    def test_the_plain_engine_is_unchanged_without_the_flag(self):
        """clouds.update_cloud without `sustained` (what the showcase stage calls) still empties in ~10 s."""
        import clouds
        from atmosphere import Atmosphere
        sim = Simulation(seed=21, w=3840, h=1080, fps=30, hour=11.0)
        sim.handle_command({"cmd": "spawn", "x": 400, "y": 320, "type": "nimbostratus"}, [])
        c = sim.clouds[-1]
        rain = 0
        for _ in range(30 * 400):
            if not clouds.update_cloud(c, 1.0 / 30, sim.world, sim.wind, sim.terrain, 0.0, [], Atmosphere()):
                break
            rain += c.stage == "precipitating"
        self.assertLess(rain / 30.0, 20.0)
        self.assertGreater(rain, 0)


if __name__ == "__main__":
    unittest.main()
