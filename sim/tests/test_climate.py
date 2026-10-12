"""Calendar and temperature (protocol v2.3): surface_temp_c = base_c + season_c + diurnal_c + gas_c +
user_c, a day is 300 sim seconds, night is colder than day, winter colder than summer, nothing ever
steps, `season` / `hour` commands glide, a preset's monthly means drive the season."""
import json
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_climate.py` (or sim.tests.test_climate) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import world as W
from simulation import Simulation

DT = 1.0 / 30


def make_world(seed=3, base=10.0):
    import random
    return W.World(1280, 720, 700, random.Random(seed), seed)


class TestClockAndTemperature(unittest.TestCase):
    def test_a_day_is_300_s_and_the_default_start_is_april_mid_morning(self):
        w = make_world()
        self.assertAlmostEqual(w.time_of_day, 0.30)           # 7.2 h
        self.assertAlmostEqual(w.month, 4.0)
        self.assertEqual(w.day, 0)
        w.t = 300.0
        self.assertAlmostEqual(w.time_of_day, 0.30)
        self.assertEqual(w.day, 1)
        w.t = 8 * 300.0                                         # a whole default year later
        self.assertAlmostEqual(w.year_frac, 0.25)
        self.assertEqual(w.day, 8)

    def test_seasons_and_hemispheres(self):
        self.assertEqual(W.season_name(1.5 / 12 - 0.04, "N"), "winter")     # mid January
        self.assertEqual([W.season_name((m - 0.5) / 12, "N") for m in (1, 4, 7, 10, 12)],
                         ["winter", "spring", "summer", "autumn", "winter"])
        self.assertEqual([W.season_name((m - 0.5) / 12, "S") for m in (1, 4, 7, 10, 12)],
                         ["summer", "autumn", "winter", "spring", "summer"])

    def test_diurnal_shape_extremes_and_zero_mean(self):
        self.assertAlmostEqual(W.diurnal_shape(W.TOD_MIN), -1.0)
        self.assertAlmostEqual(W.diurnal_shape(W.TOD_MAX), 1.0)
        vals = [W.diurnal_shape(i / 3000.0) for i in range(3000)]
        self.assertLess(abs(sum(vals) / len(vals)), 0.005)
        self.assertAlmostEqual(min(range(3000), key=lambda i: vals[i]) / 3000.0, 0.24, delta=0.002)
        self.assertAlmostEqual(max(range(3000), key=lambda i: vals[i]) / 3000.0, 0.62, delta=0.002)

    def test_surface_temp_is_the_sum_of_its_parts_every_frame(self):
        sim = Simulation(5, 1280, 720, 30)
        sim.handle_command({"cmd": "temp", "delta": -2.5}, [])
        sim.handle_command({"cmd": "gas", "name": "co2", "value": 840}, [])
        for i in range(400):
            f = sim.step(DT)
            w = f["world"]
            t = w["temp"]
            self.assertAlmostEqual(w["surface_temp_c"],
                                   t["base_c"] + t["season_c"] + t["diurnal_c"] + t["gas_c"] + t["user_c"],
                                   places=6, msg=f"frame {i}")
        self.assertAlmostEqual(t["user_c"], -2.5)

    def test_over_a_sim_year_a_day_night_wave_rides_on_a_season_wave_with_no_jump(self):
        w = make_world(seed=9)
        year = w.year_days * W.DAY_LENGTH_S
        n = int(year * 30)
        prev = w.surface_temp_c
        worst = 0.0
        day_min, day_max = {}, {}
        season_mean = {}
        for i in range(1, n + 1):
            w.t = i * DT
            cur = w.surface_temp_c
            worst = max(worst, abs(cur - prev))
            prev = cur
            d = int(w.t // W.DAY_LENGTH_S)
            day_min[d] = min(day_min.get(d, 1e9), cur)
            day_max[d] = max(day_max.get(d, -1e9), cur)
            season_mean.setdefault(d, []).append(cur)
        rng = w.climate.diurnal_range_c
        # smooth: the steepest slope of the day wave is ~ range * pi / (0.38 day) / 30 per tick
        self.assertLess(worst, rng * 3.1416 / (0.38 * 300 * 30) * 1.3, "no per-tick jump (midnight, year wrap)")
        # every day has a clear night/day swing close to the climate's range
        for d in range(w.year_days):
            self.assertGreater(day_max[d] - day_min[d], 0.8 * rng)
            self.assertLess(day_max[d] - day_min[d], 1.2 * rng + 1.5)
        means = [sum(v) / len(v) for k, v in sorted(season_mean.items()) if k < w.year_days]
        self.assertGreater(max(means) - min(means), 0.8 * w.climate.amp_c, "a season wave under the day wave")

    def test_coldest_before_sunrise_warmest_in_the_afternoon_in_the_frames(self):
        sim = Simulation(5, 1280, 720, 30, hour=0.0, year_days=365)     # a slow year: the season stays put
        temps = []
        for _ in range(int(300 * 30)):
            f = sim.step(DT)
            temps.append((f["world"]["surface_temp_c"], f["world"]["time_of_day"]))
        tmin = min(temps)[1]
        tmax = max(temps)[1]
        self.assertAlmostEqual(tmin, 0.24, delta=0.03)
        self.assertAlmostEqual(tmax, 0.62, delta=0.03)

    def test_user_c_is_what_assigning_the_temperature_means(self):
        w = make_world()
        before = w.surface_temp_c
        w.surface_temp_c = 4.0
        self.assertEqual(w.surface_temp_c, 4.0)
        self.assertAlmostEqual(w.user_c, 4.0 - before, places=9)
        w.surface_temp_c += 3.0
        self.assertAlmostEqual(w.surface_temp_c, 7.0)

    def test_year_days_and_month_and_hour_arguments(self):
        sim = Simulation(5, 1280, 720, 30, month=7.5, hour=14.0, year_days=20)
        f = sim.step(DT)["world"]
        self.assertAlmostEqual(f["month"], 7.5, delta=0.01)
        self.assertAlmostEqual(f["time_of_day"], 14 / 24, delta=0.001)
        self.assertEqual(f["season"], "summer")
        self.assertEqual(sim.climate_hello()["year_days"], 20)
        sim.world.t = 20 * 300.0 / 2
        self.assertAlmostEqual(sim.world.year_frac, ((7.5 - 1) / 12 + 0.5) % 1.0)
        with self.assertRaises(ValueError):
            Simulation(5, 1280, 720, 30, year_days=1)

    def test_reseed_keeps_the_calendar_arguments(self):
        sim = Simulation(5, 1280, 720, 30, month=11.0, hour=3.0, year_days=40)
        sim.handle_command({"cmd": "reseed", "seed": 77}, [])
        self.assertAlmostEqual(sim.world.month, 11.0)
        self.assertEqual(sim.world.year_days, 40)
        self.assertAlmostEqual(sim.world.time_of_day, 3 / 24)


class TestJumpsGlide(unittest.TestCase):
    def run_cmd(self, cmd):
        sim = Simulation(5, 1280, 720, 30)
        for _ in range(30):
            sim.step(DT)
        before = sim.world.surface_temp_c
        sim.handle_command(cmd, [])
        temps = [before]
        tods = []
        for _ in range(int(W.GLIDE_S * 30) + 60):
            f = sim.step(DT)
            temps.append(f["world"]["surface_temp_c"])
            tods.append(f["world"]["time_of_day"])
        return sim, temps, tods

    def test_season_command_glides_the_temperature_and_jumps_the_calendar(self):
        sim, temps, _ = self.run_cmd({"cmd": "season", "month": 7.5})
        self.assertAlmostEqual(sim.world.month, 7.5, delta=0.1)
        steps = [abs(b - a) for a, b in zip(temps, temps[1:])]
        self.assertLess(max(steps), 0.25, "no step in the temperature (a 13 C change over 6 s is ~0.11 per tick)")
        self.assertGreater(abs(temps[-1] - temps[0]), 5.0, "April -> July really is warmer")
        # it takes a few seconds, not one frame
        early = abs(temps[10] - temps[0])
        self.assertLess(early, 0.5 * abs(temps[-1] - temps[0]))

    def test_hour_command_glides_and_time_of_day_jumps(self):
        sim, temps, tods = self.run_cmd({"cmd": "hour", "value": 14.0})
        self.assertAlmostEqual(tods[0], 14 / 24, delta=0.01)
        steps = [abs(b - a) for a, b in zip(temps, temps[1:])]
        self.assertLess(max(steps), 0.25)
        self.assertGreater(temps[-1] - temps[0], 3.0, "mid-morning -> mid-afternoon is warmer")

    def test_bad_values_are_ignored(self):
        sim = Simulation(5, 1280, 720, 30)
        m0 = sim.world.month
        sim.handle_command({"cmd": "season", "month": "x"}, [])
        sim.handle_command({"cmd": "season", "month": float("nan")}, [])
        sim.handle_command({"cmd": "hour"}, [])
        self.assertEqual(sim.world.month, m0)

    def test_season_month_wraps(self):
        sim = Simulation(5, 1280, 720, 30)
        sim.handle_command({"cmd": "season", "month": 14.5}, [])
        self.assertAlmostEqual(sim.world.month, 2.5, delta=0.1)


class TestGasesWarm(unittest.TestCase):
    def test_doubling_co2_adds_3_degrees_without_a_step(self):
        sim = Simulation(5, 1280, 720, 30)
        sim.step(DT)
        base = sim.world.temp_parts()
        sim.handle_command({"cmd": "gas", "name": "co2", "value": 840}, [])
        prev = sim.world.surface_temp_c
        worst = 0.0
        for _ in range(30 * 25):
            f = sim.step(DT)
            worst = max(worst, abs(f["world"]["surface_temp_c"] - prev))
            prev = f["world"]["surface_temp_c"]
        self.assertLess(worst, 0.1)
        self.assertAlmostEqual(sim.world.gas_c, 3.0, places=2)
        self.assertEqual(base["gas_c"], 0.0)

    def test_halving_co2_cools(self):
        sim = Simulation(5, 1280, 720, 30)
        sim.handle_command({"cmd": "gas", "name": "co2", "value": 210}, [])
        for _ in range(30 * 25):
            sim.step(DT)
        self.assertAlmostEqual(sim.world.gas_c, -3.0, places=2)


class TestPresetMonthlyMeans(unittest.TestCase):
    MONTHLY = [5.5, 6.3, 8.0, 10.4, 13.6, 16.2, 19.0, 19.2, 16.3, 11.8, 7.6, 5.2]

    def test_monthly_means_drive_season_c(self):
        c = W.Climate.from_monthly(self.MONTHLY, 8.5, 47.4)
        self.assertAlmostEqual(c.base_c, sum(self.MONTHLY) / 12)
        for m, v in enumerate(self.MONTHLY):
            self.assertAlmostEqual(c.base_c + c.season_c((m + 0.5) / 12), v, places=6)
        # smooth and periodic through the year wrap
        pts = [c.season_c(i / 4000.0) for i in range(4001)]
        self.assertLess(max(abs(b - a) for a, b in zip(pts, pts[1:])), 0.02)
        self.assertAlmostEqual(pts[0], pts[-1], places=6)
        self.assertEqual(c.hemisphere, "N")
        self.assertEqual(W.Climate.from_monthly(self.MONTHLY, 8.5, -33.0).hemisphere, "S")

    def test_a_preset_world_uses_its_own_monthly_means(self):
        sim = Simulation(5, 1280, 720, 30, "rolling", "normal", "washington")
        h = sim.hello_message()["climate"]
        self.assertEqual(h["source"], "preset")
        self.assertEqual(len(h["monthly_temp_c"]), 12)
        self.assertAlmostEqual(h["base_c"], sum(h["monthly_temp_c"]) / 12, places=1)
        self.assertEqual(h["hemisphere"], "N")
        # winter month vs summer month in the world's own frames
        a = Simulation(5, 1280, 720, 30, "rolling", "normal", "washington", month=1.5, hour=14)
        b = Simulation(5, 1280, 720, 30, "rolling", "normal", "washington", month=7.5, hour=14)
        self.assertGreater(b.step(DT)["world"]["surface_temp_c"], a.step(DT)["world"]["surface_temp_c"] + 5.0)


class TestHelloClimate(unittest.TestCase):
    def test_hello_climate_block_for_a_plain_world(self):
        sim = Simulation(5, 1280, 720, 30)
        c = sim.hello_message()["climate"]
        self.assertEqual(set(c), {"year_days", "day_length_s", "hemisphere", "lat", "monthly_temp_c",
                                  "diurnal_range_c", "base_c", "start_month", "source"})
        self.assertEqual((c["year_days"], c["day_length_s"], c["source"], c["hemisphere"]), (8, 300, "generated", "N"))
        self.assertEqual(c["start_month"], 4.0)
        self.assertEqual(len(c["monthly_temp_c"]), 12)
        self.assertGreaterEqual(c["diurnal_range_c"], 6.0)
        self.assertLessEqual(c["diurnal_range_c"], 12.0)
        self.assertGreaterEqual(c["base_c"], 5.0)
        self.assertLessEqual(c["base_c"], 18.0)
        json.dumps(c)


if __name__ == "__main__":
    unittest.main()
