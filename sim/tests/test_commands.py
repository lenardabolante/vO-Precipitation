"""Commands from Java (pause/resume/speed/spawn/wind/temp/reseed/save/view/quit) are
handled and visibly affect subsequent frames. Unknown commands are ignored."""
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_commands.py` (or sim.tests.test_commands) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
from simulation import Simulation


class TestCommands(unittest.TestCase):
    def setUp(self):
        self.sim = Simulation(seed=42, w=1280, h=720, fps=30)
        self.dt = 1.0 / 30

    def test_pause_stops_sim_time_resume_continues_it(self):
        self.sim.handle_command({"cmd": "pause"}, [])
        msg1 = self.sim.step(self.dt)
        t_after_pause_1 = msg1["t"]
        self.assertTrue(msg1["paused"])
        msg2 = self.sim.step(self.dt)
        self.assertEqual(msg2["t"], t_after_pause_1, "t must not advance while paused")

        self.sim.handle_command({"cmd": "resume"}, [])
        msg3 = self.sim.step(self.dt)
        self.assertFalse(msg3["paused"])
        self.assertGreater(msg3["t"], t_after_pause_1)

    def test_speed_is_applied_and_clamped(self):
        self.sim.handle_command({"cmd": "speed", "value": 2.0}, [])
        msg = self.sim.step(self.dt)
        self.assertEqual(msg["speed"], 2.0)
        # Check the underlying (unrounded) sim clock, not the 2-decimal
        # protocol field, so the assertion isn't fighting output rounding.
        t_before = self.sim.world.t
        self.sim.step(self.dt)
        t_after = self.sim.world.t
        self.assertAlmostEqual(t_after - t_before, self.dt * 2.0, places=6)

        self.sim.handle_command({"cmd": "speed", "value": 100.0}, [])
        self.assertEqual(self.sim.speed, 8.0)
        self.sim.handle_command({"cmd": "speed", "value": 0.001}, [])
        self.assertEqual(self.sim.speed, 0.25)

    def test_spawn_adds_a_cloud_at_the_given_point(self):
        before = len(self.sim.clouds)
        born_before = self.sim.clouds_born
        self.sim.handle_command({"cmd": "spawn", "x": 111.0, "y": 222.0}, [])
        self.assertEqual(len(self.sim.clouds), before + 1)
        self.assertEqual(self.sim.clouds_born, born_before + 1)
        new_cloud = self.sim.clouds[-1]
        self.assertAlmostEqual(new_cloud.x, 111.0, places=3)
        self.assertAlmostEqual(new_cloud.y, 222.0, places=3)
        self.assertEqual(new_cloud.form, 0.0, "a spawned cloud starts as invisible vapour")

    def test_wind_delta_changes_prevailing_wind(self):
        before = self.sim.world.prevailing_u
        self.sim.handle_command({"cmd": "wind", "delta": 5.0}, [])
        self.assertAlmostEqual(self.sim.world.prevailing_u, before + 5.0)

    def test_temp_delta_moves_freezing_level(self):
        msg0 = self.sim.step(self.dt)
        fy_before = msg0["world"]["freezing_y"]
        self.sim.handle_command({"cmd": "temp", "delta": 20.0}, [])
        msg1 = self.sim.step(self.dt)
        fy_after = msg1["world"]["freezing_y"]
        self.assertLess(fy_after, fy_before, "warming should raise the freezing level (smaller y)")

    def test_reseed_restarts_the_world(self):
        for _ in range(30):
            self.sim.step(self.dt)
        old_seed = self.sim.seed
        self.sim.handle_command({"cmd": "reseed", "seed": old_seed + 999}, [])
        self.assertEqual(self.sim.seed, old_seed + 999)
        self.assertEqual(self.sim.clouds_born, len(self.sim.clouds))  # freshly reseeded
        self.assertEqual(self.sim.clouds_dead, 0)
        self.assertEqual(len(self.sim.particles), 0)

    def test_reseed_queues_a_fresh_hello_with_the_same_size_and_terrain(self):
        sim = Simulation(seed=5, w=1920, h=900, fps=30, terrain_kind="hilly", settlements="dense")
        sim.handle_command({"cmd": "reseed", "seed": 77}, [])
        out = sim.drain_outbox()
        self.assertEqual([m["type"] for m in out], ["hello"])
        hello = out[0]
        self.assertEqual(hello["seed"], 77)
        self.assertFalse(hello["loaded"])
        self.assertEqual((hello["world"]["w"], hello["world"]["h"]), (1920, 900))
        self.assertEqual(hello["world"]["terrain_kind"], "hilly")
        self.assertEqual(sim.drain_outbox(), [], "outbox is drained")

    def test_quit_stops_the_run_loop(self):
        self.assertTrue(self.sim.running)
        self.sim.handle_command({"cmd": "quit"}, [])
        self.assertFalse(self.sim.running)

    def test_unknown_command_is_ignored(self):
        before_clouds = len(self.sim.clouds)
        before_speed = self.sim.speed
        self.sim.handle_command({"cmd": "not_a_real_command", "value": 999}, [])
        self.assertEqual(len(self.sim.clouds), before_clouds)
        self.assertEqual(self.sim.speed, before_speed)
        self.assertTrue(self.sim.running)

    # -- v2.3 -----------------------------------------------------------------------------------
    def test_temp_delta_changes_user_c_and_nothing_else(self):
        f0 = self.sim.step(self.dt)["world"]
        self.sim.handle_command({"cmd": "temp", "delta": -2.0}, [])
        self.sim.handle_command({"cmd": "temp", "delta": -1.0}, [])
        f1 = self.sim.step(self.dt)["world"]
        self.assertAlmostEqual(f1["temp"]["user_c"], -3.0)
        self.assertAlmostEqual(f1["temp"]["base_c"], f0["temp"]["base_c"])
        self.assertAlmostEqual(f1["surface_temp_c"] - f0["surface_temp_c"], -3.0, delta=0.05)

    def test_gas_command_delta_value_and_clamps(self):
        self.sim.handle_command({"cmd": "gas", "name": "humidity", "value": 0.5}, [])
        self.sim.handle_command({"cmd": "gas", "name": "humidity", "delta": 0.05}, [])
        self.assertAlmostEqual(self.sim.atmo.humidity, 0.55)
        self.sim.handle_command({"cmd": "gas", "name": "humidity", "delta": 9}, [])
        self.assertEqual(self.sim.atmo.humidity, 1.0)
        self.sim.handle_command({"cmd": "gas", "name": "aerosol", "delta": -9}, [])
        self.assertEqual(self.sim.atmo.aerosol, 0.0)
        self.sim.handle_command({"cmd": "gas", "name": "co2", "delta": 80}, [])
        self.assertEqual(self.sim.atmo.co2_ppm, 500.0)
        self.sim.handle_command({"cmd": "gas", "name": "co2", "value": 560}, [])
        self.assertEqual(self.sim.step(self.dt)["atmosphere"]["co2_ppm"], 560)
        self.sim.handle_command({"cmd": "gas", "name": "co2", "value": 5000}, [])
        self.assertEqual(self.sim.atmo.co2_ppm, 2000.0)
        self.sim.handle_command({"cmd": "gas", "name": "co2", "value": 10}, [])
        self.assertEqual(self.sim.atmo.co2_ppm, 180.0)

    def test_bad_gas_commands_are_ignored(self):
        before = self.sim.atmo.to_state()
        for bad in ({"cmd": "gas"}, {"cmd": "gas", "name": "neon", "delta": 1},
                    {"cmd": "gas", "name": "co2", "delta": "x"}, {"cmd": "gas", "name": "co2", "value": None},
                    {"cmd": "gas", "name": "humidity", "delta": float("inf")}, {"cmd": "gas", "name": 7, "delta": 1}):
            self.sim.handle_command(bad, [])
        self.assertEqual(self.sim.atmo.to_state(), before)

    def test_season_command_jumps_the_calendar_and_glides_the_temperature(self):
        self.sim.step(self.dt)
        t0 = self.sim.world.surface_temp_c
        self.sim.handle_command({"cmd": "season", "month": 7.5}, [])
        f = self.sim.step(self.dt)["world"]
        self.assertAlmostEqual(f["month"], 7.5, delta=0.01)
        self.assertEqual(f["season"], "summer")
        self.assertAlmostEqual(f["surface_temp_c"], t0, delta=0.3, msg="no step on the next frame")

    def test_hour_command_jumps_the_clock_and_glides_the_temperature(self):
        self.sim.step(self.dt)
        t0 = self.sim.world.surface_temp_c
        self.sim.handle_command({"cmd": "hour", "value": 15.0}, [])
        f = self.sim.step(self.dt)["world"]
        self.assertAlmostEqual(f["time_of_day"], 15 / 24, delta=0.001)
        self.assertAlmostEqual(f["surface_temp_c"], t0, delta=0.3)

    def test_numbers_too_large_for_a_float_never_raise(self):
        """Regression (2026-10-09 test pass): `{"cmd":"reseed","seed":1e400}` killed the sim process with an
        OverflowError (JSON 1e400 parses to inf, int(inf) raises), and an integer of 400 digits did the same
        through float() / math.isfinite() in every numeric command. A bad number is ignored, never fatal."""
        big = 10 ** 400
        inf = float("inf")
        before = (self.sim.speed, self.sim.world.prevailing_u, self.sim.world.user_c, self.sim.atmo.to_state(),
                  len(self.sim.clouds), self.sim.world.month, self.sim.world.time_of_day, self.sim.view)
        for bad in ({"cmd": "speed", "value": big}, {"cmd": "spawn", "x": big, "y": 100}, {"cmd": "spawn", "x": 100, "y": big},
                    {"cmd": "wind", "delta": big}, {"cmd": "temp", "delta": big}, {"cmd": "temp", "delta": -big},
                    {"cmd": "gas", "name": "co2", "value": big}, {"cmd": "gas", "name": "humidity", "delta": big},
                    {"cmd": "season", "month": big}, {"cmd": "hour", "value": big},
                    {"cmd": "view", "x0": big, "x1": 5}, {"cmd": "view", "x0": 0, "x1": big},
                    {"cmd": "tornado", "cloud": big}):
            self.sim.handle_command(bad, [])
        after = (self.sim.speed, self.sim.world.prevailing_u, self.sim.world.user_c, self.sim.atmo.to_state(),
                 len(self.sim.clouds), self.sim.world.month, self.sim.world.time_of_day, self.sim.view)
        self.assertEqual(before, after, "every one of them is ignored")
        for seed in (inf, -inf, float("nan")):
            self.sim.handle_command({"cmd": "reseed", "seed": seed}, [])
            self.assertEqual(self.sim.seed, 0, "a seed that is not a number restarts the world with seed 0")
        self.assertEqual([m["type"] for m in self.sim.drain_outbox()], ["hello"] * 3)
        self.sim.step(self.dt)

    def test_absurd_but_finite_wind_and_temp_deltas_are_limited_and_the_frames_stay_valid_json(self):
        """Regression (2026-10-09 test pass): `wind` with delta 1e308 raised OverflowError inside the wind grid
        (the process died) and two `temp` deltas of 1e308 put `Infinity` on the wire. Both are limited now."""
        import json
        from simulation import TEMP_USER_LIMIT, WIND_LIMIT
        for _ in range(60):
            self.sim.step(self.dt)
        for delta in (1e308, 1e308, -1e308, -1e308, -1e308):
            self.sim.handle_command({"cmd": "wind", "delta": delta}, [])
            self.sim.handle_command({"cmd": "temp", "delta": delta}, [])
            self.assertLessEqual(abs(self.sim.world.prevailing_u), WIND_LIMIT)
            self.assertLessEqual(abs(self.sim.world.user_c), TEMP_USER_LIMIT)
            for _ in range(20):
                json.dumps(self.sim.step(self.dt), allow_nan=False)      # raises on NaN / Infinity
        self.assertEqual(self.sim.world.prevailing_u, -WIND_LIMIT)
        self.assertEqual(self.sim.world.user_c, -TEMP_USER_LIMIT)
        # ordinary use is untouched: the keys step by 1
        self.sim.handle_command({"cmd": "wind", "delta": 1.0}, [])
        self.sim.handle_command({"cmd": "temp", "delta": 1.0}, [])
        self.assertEqual(self.sim.world.prevailing_u, -WIND_LIMIT + 1.0)
        self.assertEqual(self.sim.world.user_c, -TEMP_USER_LIMIT + 1.0)

    def test_the_showcase_stage_ignores_the_calendar_commands(self):
        from showcase import ShowcaseSimulation
        s = ShowcaseSimulation(3, 1920, 1080, 30, "cumulus")
        before = s.step(self.dt)["world"]
        s.handle_command({"cmd": "season", "month": 12.0}, [])
        s.handle_command({"cmd": "hour", "value": 2.0}, [])
        after = s.step(self.dt)["world"]
        for k in ("month", "time_of_day", "surface_temp_c", "season"):
            self.assertEqual(before[k], after[k], k)


class TestSupercellCommands(unittest.TestCase):
    """v2.3: `spawn` takes a `type`, `tornado` is ignored unless a mature supercell can take it."""

    def setUp(self):
        self.sim = Simulation(seed=42, w=1280, h=720, fps=30)
        self.dt = 1.0 / 30

    def test_spawn_with_a_type(self):
        self.sim.handle_command({"cmd": "spawn", "x": 300.0, "y": 200.0, "type": "supercell"}, [])
        c = self.sim.clouds[-1]
        self.assertEqual(c.type, "supercell")
        self.assertTrue(c.name.startswith("Sp-"))
        self.sim.handle_command({"cmd": "spawn", "x": 600.0, "y": 200.0, "type": "cirrus"}, [])
        self.assertEqual(self.sim.clouds[-1].type, "cirrus")
        n = len(self.sim.clouds)
        self.sim.handle_command({"cmd": "spawn", "x": 600.0, "y": 200.0, "type": "tornado"}, [])
        self.assertEqual(len(self.sim.clouds), n + 1, "a bad type is ignored, the cloud still comes")

    def test_tornado_is_ignored_without_a_mature_supercell(self):
        ev = []
        self.sim.handle_command({"cmd": "tornado"}, ev)
        self.sim.handle_command({"cmd": "spawn", "x": 300.0, "y": 200.0, "type": "supercell"}, ev)
        sc = self.sim.clouds[-1]
        self.sim.handle_command({"cmd": "tornado", "cloud": sc.id}, ev)
        self.sim.handle_command({"cmd": "tornado", "cloud": "x"}, ev)
        f = self.sim.step(self.dt)
        self.assertEqual(self.sim.tornadoes, [])
        self.assertNotIn("tornadoes", f)
        self.assertEqual((f["stats"]["tornadoes"], f["stats"]["tornadoes_total"]), (0, 0))
        self.assertEqual([e for e in ev if "tornado" in e or "funnel" in e], [])


class TestDrainsCommands(unittest.TestCase):
    """v2.4: the `drains` command (PROTOCOL.md, Commands v2.4)."""

    def setUp(self):
        self.sim = Simulation(seed=12, w=3840, h=1080, fps=30)
        self.dt = 1.0 / 30
        self.ids = [s["id"] for s in self.sim.settlements]
        self.assertGreaterEqual(len(self.ids), 2)

    def sizes(self, f):
        return {e["id"]: e["size"] for e in f["drains"]["settlements"]}

    def test_the_four_modes_give_every_settlement_that_modes_size(self):
        for mode, want in (("small", 3.0), ("large", 8.2)):
            self.sim.handle_command({"cmd": "drains", "mode": mode}, [])
            f = self.sim.step(self.dt)
            self.assertEqual(f["drains"]["mode"], mode)
            self.assertEqual(set(self.sizes(f).values()), {want})
        self.sim.handle_command({"cmd": "drains", "mode": "normal"}, [])
        normal = self.sizes(self.sim.step(self.dt))
        self.assertEqual(normal, {s["id"]: self.sim.water.sizes[s["id"]] for s in self.sim.settlements})
        self.assertGreater(len(set(normal.values())), 1, "normal sizes differ from town to town")
        self.sim.handle_command({"cmd": "drains", "mode": "random"}, [])
        f = self.sim.step(self.dt)
        self.assertEqual(f["drains"]["mode"], "random")
        self.assertTrue(all(2.5 <= v <= 10.0 for v in self.sizes(f).values()))

    def test_one_settlement_by_size_or_by_delta_and_everyone_by_delta(self):
        a, b = self.ids[0], self.ids[1]
        self.sim.handle_command({"cmd": "drains", "mode": "normal"}, [])
        base = self.sizes(self.sim.step(self.dt))
        self.sim.handle_command({"cmd": "drains", "settlement": a, "size": 6.5}, [])
        f = self.sim.step(self.dt)
        self.assertEqual((f["drains"]["mode"], self.sizes(f)[a], self.sizes(f)[b]), ("custom", 6.5, base[b]))
        e = next(e for e in f["drains"]["settlements"] if e["id"] == a)
        self.assertEqual((e["class"], e["inlets"]), ("large", self.sim.water.inlets[a]))
        self.sim.handle_command({"cmd": "drains", "settlement": a, "delta": 1.0}, [])
        self.assertEqual(self.sizes(self.sim.step(self.dt))[a], 7.5)
        self.sim.handle_command({"cmd": "drains", "delta": -0.5}, [])
        f = self.sim.step(self.dt)
        self.assertEqual(self.sizes(f)[a], 7.0)
        self.assertAlmostEqual(self.sizes(f)[b], max(1.5, base[b] - 0.5), places=2)
        self.sim.handle_command({"cmd": "drains", "settlement": a, "size": 500}, [])
        self.assertEqual(self.sizes(self.sim.step(self.dt))[a], 12.0)
        self.sim.handle_command({"cmd": "drains", "settlement": a, "delta": -50}, [])
        self.assertEqual(self.sizes(self.sim.step(self.dt))[a], 1.5)

    def test_the_new_size_changes_what_the_drains_can_do_at_once(self):
        a = self.ids[0]
        cap0 = self.sim.water.cap_of(a)
        self.sim.handle_command({"cmd": "drains", "settlement": a, "size": 2 * self.sim.water.sizes[a]}, [])
        self.assertAlmostEqual(self.sim.water.cap_of(a), 4.0 * cap0, delta=0.01 * cap0 * 4)

    def test_water_already_standing_stays_when_the_size_changes(self):
        a = self.ids[0]
        s = self.sim.settlements[0]
        f = self.sim.water.film
        for k in f.span_cells[a]:                                   # (v2.4.1: 3 px of film over the street)
            f.S[k] = 3.0 * 32.0
            f.act.add(k)
        v = f.total()
        self.assertGreater(v, 3.0 * 32.0)
        self.sim.handle_command({"cmd": "drains", "mode": "large"}, [])
        self.assertEqual(f.total(), v)

    def test_bad_values_unknown_ids_and_missing_keys_are_ignored_and_answer_nothing(self):
        self.sim.step(self.dt)
        before = dict(self.sim.water.sizes)
        a = self.ids[0]
        for bad in ({"cmd": "drains"}, {"cmd": "drains", "mode": "huge"}, {"cmd": "drains", "mode": 4},
                    {"cmd": "drains", "mode": "custom"}, {"cmd": "drains", "settlement": 999, "size": 5},
                    {"cmd": "drains", "settlement": "1", "size": 5}, {"cmd": "drains", "settlement": a},
                    {"cmd": "drains", "settlement": a, "size": "big"},
                    {"cmd": "drains", "settlement": a, "size": float("nan")},
                    {"cmd": "drains", "delta": float("inf")}, {"cmd": "drains", "delta": "x"},
                    {"cmd": "drains", "delta": True}, {"cmd": "drains", "size": 5}, {"cmd": "drains", "settlement": True, "delta": 1}):
            self.sim.handle_command(dict(bad), [])
            f = self.sim.step(self.dt)
            self.assertNotIn("drains", f, bad)
        self.assertEqual(self.sim.water.sizes, before)
        self.assertEqual(self.sim.water.mode, "normal")

    def test_each_accepted_command_is_answered_by_one_frame_and_not_repeated(self):
        self.sim.handle_command({"cmd": "drains", "mode": "small"}, [])
        self.assertIn("drains", self.sim.step(self.dt))
        for _ in range(5):
            self.assertNotIn("drains", self.sim.step(self.dt))

    def test_a_world_without_settlements_and_the_other_modes_ignore_it(self):
        none = Simulation(seed=2, w=2560, h=1080, fps=30, settlements="none")
        none.handle_command({"cmd": "drains", "mode": "small"}, [])
        self.assertNotIn("drains", none.step(self.dt))
        from showcase import ShowcaseSimulation
        sc = ShowcaseSimulation(3, 1920, 1080, 30, "cumulus")
        sc.handle_command({"cmd": "drains", "mode": "small"}, [])
        f = sc.step(self.dt)
        self.assertTrue("drains" not in f and "water" not in f and "flood" not in f)

    def test_a_paused_sim_still_answers_the_command(self):
        self.sim.handle_command({"cmd": "pause"}, [])
        self.sim.step(self.dt)
        self.sim.handle_command({"cmd": "drains", "mode": "large"}, [])
        f = self.sim.step(self.dt)
        self.assertEqual(f["drains"]["mode"], "large")


if __name__ == "__main__":
    unittest.main()
