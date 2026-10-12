"""Phase O: clouds follow the day. Convective spawning and towering follow the time of day (cumulonimbus peak in the
afternoon, almost none at night, fair-weather cumulus fade after sunset, sheets and clear spells at night, a small
climate-dependent chance of night storms), in the sandbox and in World mode.

The project's failure fingerprint is a test that asserts an event but not its size, so every test here asserts a
MAGNITUDE: a count or a ratio per hour bin, with the margin written next to it. The measured numbers (printed) are in
tools/debug_plots/v26/cloud_diurnal_*.txt; `tools/measure_cloud_diurnal.py` produces them."""
import json
import os
import sys
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim.tests.test_diurnal_clouds` from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import atmosphere as A
import clouds as C
import worldsim as W
from simulation import Simulation
from world import DAY_LENGTH_S

PROJECT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(PROJECT, "tools"))
import measure_cloud_diurnal as M          # noqa: E402

DT = 1.0 / 30


def hr(h):
    return h / 24.0


def quiet(seed=5, hour=12.0, **kw):
    """A sandbox with an empty sky and no automatic births, the clock held at `hour`."""
    sim = Simulation(seed, 1920, 1080, 30, "flat", "none", hour=hour, **kw)
    sim.merging = False
    sim._next_spawn_delay = 1e18
    sim.clouds.clear()
    sim.world.hold = (sim.world.year_frac, hr(hour))
    return sim


class TestTheGates(unittest.TestCase):
    def test_deep_convection_is_zero_from_22_to_09_h_and_full_from_13_to_16_h(self):
        for h in (22.0, 23.0, 0.0, 3.0, 6.0, 9.0):
            self.assertEqual(A.cb_day(hr(h)), 0.0, h)
        for h in (13.0, 14.0, 15.0, 16.0):
            self.assertEqual(A.cb_day(hr(h)), 1.0, h)
        self.assertAlmostEqual(A.cb_day(hr(18.0)), 0.5, places=6, msg="mid-way down at 18 h")
        rising = [A.cb_day(hr(h)) for h in (9.5, 10.5, 11.5, 12.4)]
        self.assertEqual(rising, sorted(rising), "monotone up in the morning")

    def test_fair_weather_cumulus_follow_the_sun(self):
        self.assertEqual(A.cumulus_day(hr(3.0)), 0.0)
        self.assertEqual(A.cumulus_day(hr(7.2)), 0.0)         # the default opening hour: no cumulus yet
        self.assertEqual(A.cumulus_day(hr(12.0)), 1.0)
        self.assertEqual(A.cumulus_day(hr(16.0)), 1.0)
        self.assertEqual(A.cumulus_day(hr(21.0)), 0.0, "gone by 21 h, after sunset")
        self.assertTrue(0.2 < A.cumulus_day(hr(18.75)) < 0.8)

    def test_night_storm_floor_is_small_by_default_and_larger_in_the_tropics_and_on_the_plains(self):
        temperate = A.night_storm_floor(10.0, 0.575)
        tropical = A.night_storm_floor(28.0, 0.80)
        plains = A.night_storm_floor(20.0, 0.60, sc_share=0.5 / 17.7)
        desert = A.night_storm_floor(28.0, 0.21, af=A.aridity_factor(0.21))
        self.assertAlmostEqual(temperate, A.NIGHT_STORM_BASE, places=6)
        self.assertGreaterEqual(tropical, 0.20)
        self.assertGreaterEqual(plains, 0.15)
        self.assertLessEqual(max(tropical, plains), A.NIGHT_STORM_MAX)
        self.assertLess(desert, 0.75 * temperate, "dry air: well below the base chance")

    def test_a_supercell_ages_less_fast_than_a_cumulonimbus_so_it_can_still_make_its_tornado(self):
        floor = A.NIGHT_STORM_BASE
        self.assertEqual(A.cb_age_rate(hr(15.0), floor, A.SC_AGE_K), 1.0)
        sc, cb = A.cb_age_rate(hr(3.0), floor, A.SC_AGE_K), A.cb_age_rate(hr(3.0), floor)
        self.assertAlmostEqual(sc, 1.0 + A.SC_AGE_K * (1.0 - floor), places=6)
        self.assertTrue(3.0 < sc < 3.6 and cb > 9.0, (sc, cb))
        sim = quiet(hour=3.0)
        a = sim._spawn_cloud_auto(x=700.0, y=300.0, cloud_type="supercell", diurnal=True)
        b = sim._spawn_cloud_auto(x=1300.0, y=300.0, cloud_type="cumulonimbus", diurnal=True)
        c = sim._spawn_cloud_auto(x=1000.0, y=300.0, cloud_type="nimbostratus", diurnal=True)
        self.assertAlmostEqual(C.day_age_rate(a, sim.world, sim.atmo), sc, places=6)
        self.assertAlmostEqual(C.day_age_rate(b, sim.world, sim.atmo), cb, places=6)
        self.assertEqual(C.day_age_rate(c, sim.world, sim.atmo), 1.0, "a nimbostratus sheet is not convective")
        self.assertEqual(C.day_age_rate(a, sim.world, None), 1.0, "no atmosphere (a bare update_cloud): no day cycle")
        a.sc_force = 1.0
        self.assertEqual(C.day_age_rate(a, sim.world, sim.atmo), 1.0, "a storm the tornado command holds keeps the clock")

    def test_a_storm_ages_about_ten_times_faster_at_3_h_than_at_15_h_and_a_cumulus_twice(self):
        self.assertEqual(A.cb_age_rate(hr(15.0), A.NIGHT_STORM_BASE), 1.0)
        night = A.cb_age_rate(hr(3.0), A.NIGHT_STORM_BASE)
        self.assertAlmostEqual(night, 1.0 + A.CB_AGE_K * (1.0 - A.NIGHT_STORM_BASE), places=6)
        self.assertGreater(night, 9.0)
        self.assertLess(A.cb_age_rate(hr(3.0), 0.40), night, "a stormier climate lets storms live longer at night")
        self.assertEqual(A.cb_age_rate(hr(11.0), A.NIGHT_STORM_BASE), 1.0, "a storm that towers at 11 h is fed by the sun")
        self.assertTrue(2.0 < A.cb_age_rate(hr(8.0), A.NIGHT_STORM_BASE) < 6.0, "8 h: half-way between the night rate (9.6) and 1.0")
        self.assertEqual(A.cu_age_rate(hr(13.0)), 1.0)
        self.assertGreater(A.cu_age_rate(hr(3.0)), 1.7)


class TestTypeWeights(unittest.TestCase):
    MIX = {"cumulus": 5.0, "cumulonimbus": 4.0, "nimbostratus": 3.0, "stratocumulus": 2.0, "stratus": 2.0,
           "altocumulus": 1.5, "cirrus": 2.0}

    def w(self, h, **kw):
        return A.type_weights(self.MIX, 24.0, 0.45, 0.2, 0.7, tod=None if h is None else hr(h), **kw)

    def test_afternoon_is_exactly_the_old_weights_and_night_cuts_the_convective_types(self):
        old = self.w(None)
        for h in (13.0, 14.0, 15.5, 16.0):
            self.assertEqual(self.w(h), old, h)           # (stratus / stratocumulus gains are 1.0 by day)
        d, n = self.w(15.0), self.w(3.0)
        self.assertAlmostEqual(sum(n.values()), 1.0, places=9)
        # (the raw weight is 4 % of the day's; as a share of the whole it is ~10 %, because the other types shrink too)
        self.assertLess(n["cumulonimbus"], 0.15 * d["cumulonimbus"], (n["cumulonimbus"], d["cumulonimbus"]))
        self.assertLess(n["cumulus"], 0.35 * d["cumulus"], (n["cumulus"], d["cumulus"]))   # (raw 12 %, as a share ~30 %)
        sheets = ("nimbostratus", "stratocumulus", "stratus", "altocumulus", "cirrus")
        self.assertGreater(sum(n[k] for k in sheets), sum(d[k] for k in sheets) + 0.2, "the night sky is sheets and high cloud")
        self.assertGreater(self.w(5.0)["stratus"], 1.5 * self.w(15.0)["stratus"], "low stratus towards dawn")

    def test_a_stormier_climate_keeps_more_of_its_night_storms(self):
        calm = self.w(2.0)["cumulonimbus"] / self.w(15.0)["cumulonimbus"]
        stormy = self.w(2.0, night_storm=0.18)["cumulonimbus"] / self.w(15.0, night_storm=0.18)["cumulonimbus"]
        self.assertGreater(stormy, 2.5 * calm, (calm, stormy))
        self.assertLess(stormy, 0.50, "still under half the afternoon's share, not a switch")

    def test_the_snapshot_clears_the_night_where_the_mix_is_convective_and_not_where_it_is_not(self):
        sim = Simulation(5, 1280, 720, 30, "flat", "none", preset="florida", hour=15.0, year_days=365)
        conv = dict(sim._mix0)
        parts = sim.world.temp_parts()
        day = sim.atmo.snapshot(parts, 5.0, 1.0, conv, hr(15.0))
        night = sim.atmo.snapshot(parts, 5.0, 1.0, conv, hr(3.0))
        old = sim.atmo.snapshot(parts, 5.0, 1.0, conv)
        self.assertEqual(day["target_f"], old["target_f"])
        self.assertEqual(day["weights_f"], old["weights_f"])
        self.assertEqual(night["target_open_f"], old["target_f"], "the opening sky counts clouds by the daytime target")
        r = night["target_f"] / day["target_f"]
        self.assertTrue(0.65 < r < 0.80, f"a convective sky: 20-35 % fewer clouds at night, got {r:.3f}")
        sheets = {"stratus": 4.0, "nimbostratus": 4.0, "altostratus": 4.0, "stratocumulus": 4.0}
        d2 = sim.atmo.snapshot(parts, 5.0, 1.0, sheets, hr(15.0))
        n2 = sim.atmo.snapshot(parts, 5.0, 1.0, sheets, hr(3.0))
        self.assertGreater(n2["target_f"] / d2["target_f"], 0.95, "a sheet-cloud sky keeps its cloud at night (only the odd supercell weight is convective)")


class TestTowering(unittest.TestCase):
    def towers(self, hour, n=48, seed0=100):
        """How many of `n` lone cumulus (the automatic spawner's kind) tower while the clock is held at `hour`."""
        sim = quiet(hour=hour)
        count = 0
        for k in range(n):
            c = C.spawn_cloud(900 + k, seed0 + k, 900 + k, 960.0, None, 0.0, sim.signatures, world_w=1920,
                              world_h=1080, terrain=sim.terrain, cloud_type="cumulus", fair=True)
            c.diurnal = True
            c.lifetime = 60.0
            c.vx = c.vy = 0.0
            ev = []
            t = 0.0
            while t < 140.0 and c.stage != "dissipating":
                t += 0.2
                sim.world.t = t
                if not C.update_cloud(c, 0.2, sim.world, sim.wind, sim.terrain, t, ev, sim.atmo, True):
                    break
                if c.from_type == "cumulus":
                    count += 1
                    break
        return count

    def test_cumulus_tower_in_the_afternoon_and_almost_never_at_night(self):
        a = self.towers(15.0)
        b = self.towers(3.0)
        print(f"\ncumulus that towered out of 48: 15 h {a}, 3 h {b}")
        self.assertGreaterEqual(a, 8, "about a third of 48 tower by day (TOWER_HAZARD 2.0 over the window)")
        self.assertLessEqual(b, 2, "at 3 h the hazard is 4 % of the day's: expected 0.6 of 48")

    def test_a_storm_decays_after_dark_unless_the_player_made_it(self):
        sim = quiet(hour=2.0)
        mine = sim._spawn_cloud_auto(x=700.0, y=300.0, cloud_type="cumulonimbus", diurnal=False)
        auto = sim._spawn_cloud_auto(x=1300.0, y=300.0, cloud_type="cumulonimbus", diurnal=True)
        for c in (mine, auto):
            c.vx = c.vy = 0.0
        for _ in range(30 * 5):
            sim.step(DT)
        self.assertAlmostEqual(mine.age, 5.0, delta=0.3, msg="the player's cloud keeps the clock")
        self.assertTrue(40.0 < auto.age < 50.0, f"an automatic storm ages at 1 + 9 x 0.96 = 9.6 x at 2 h: {auto.age:.1f} s in 5 s")
        sim2 = quiet(hour=15.0)
        a15 = sim2._spawn_cloud_auto(x=700.0, y=300.0, cloud_type="cumulonimbus", diurnal=True)
        for _ in range(30 * 10):
            sim2.step(DT)
        self.assertAlmostEqual(a15.age, 10.0, delta=0.5, msg="by day the clock is the clock")


class TestPlayerCommandsAtAnyHour(unittest.TestCase):
    def test_spawn_with_a_type_lives_its_whole_life_at_3_h_and_the_climate_pick_does_too(self):
        sim = quiet(hour=3.0)
        ev = []
        for ctype in ("cumulonimbus", "supercell", "cumulus", None):
            cmd = {"cmd": "spawn", "x": 600 + 200 * len(sim.clouds), "y": 300}
            if ctype:
                cmd["type"] = ctype
            sim.handle_command(cmd, ev)
        self.assertEqual(len(sim.clouds), 4)
        self.assertEqual([c.diurnal for c in sim.clouds], [False] * 4)
        self.assertEqual([c.type for c in sim.clouds[:3]], ["cumulonimbus", "supercell", "cumulus"])
        for c in sim.clouds:
            c.vx = c.vy = 0.0
        for _ in range(30 * 20):
            sim.step(DT)
        for c in sim.clouds:
            self.assertAlmostEqual(c.age, 20.0, delta=0.5, msg=c.type)

    def test_the_tornado_command_still_works_on_a_supercell_at_3_h(self):
        sim = quiet(hour=3.0)
        c = sim._spawn_cloud_auto(x=sim.world.w * 0.4, cloud_type="supercell")
        c.rot_peak, c.wall_peak, c.ovs_peak = 0.95, 0.95, 0.9
        for _ in range(30 * 150):
            sim.step(DT)
            c.vx = c.vy = 0.0
            if C.supercell_mature(c):
                break
        self.assertTrue(C.supercell_mature(c), "a supercell spawned at 3 h matures like any other")
        sim.handle_command({"cmd": "tornado"}, [])
        self.assertEqual(len(sim.tornadoes), 1)
        life = c.life_frac
        for _ in range(30 * 20):
            sim.step(DT)
            c.vx = c.vy = 0.0
        self.assertLess(c.life_frac - life, 20.0 / c.lifetime * 1.2, "a held-up storm keeps the clock too")


class TestAfternoonIsWhatItWas(unittest.TestCase):
    def test_a_held_16_h_oklahoma_sky_is_bit_for_bit_the_old_one(self):
        """cb_day(16 h) = cumulus_day(16 h) = 1, no dawn stratus: every gate is exactly 1.0, so the storm climate that
        PLAN.md Phase M measured at hour 16 (a tornado per ~800 s) runs the identical numbers."""
        sim = Simulation(1, 1920, 1080, 30, "rolling", "none", preset="oklahoma", month=5.0, hour=16.0)
        sim.world.hold = (sim.world.year_frac, hr(16.0))
        parts = sim.world.temp_parts()
        new = sim.atmo.snapshot(parts, 5.0, 1.5, sim._mix0, hr(16.0))
        old = sim.atmo.snapshot(parts, 5.0, 1.5, sim._mix0)
        self.assertEqual(new["target_f"], old["target_f"])
        self.assertEqual(new["spawn_per_min_f"], old["spawn_per_min_f"])
        self.assertEqual(new["weights_f"], old["weights_f"])
        self.assertGreater(new["weights_f"]["supercell"], 0.03, "and a storm climate really has supercells in its mix")

    def test_the_default_opening_sky_has_no_cumulonimbus_to_start_with(self):
        for seed in (1, 2, 3, 4):
            sim = Simulation(seed, 1920, 720, 30)
            self.assertEqual(sim.world.time_of_day, 7.2 / 24.0)
            self.assertEqual([c.type for c in sim.clouds if c.is_cb], [], seed)


class TestHowManyAtNight(unittest.TestCase):
    """The user's complaint, measured over whole sim days (tools/measure_cloud_diurnal.py)."""
    @classmethod
    def setUpClass(cls):
        cls.res = {}
        for name in ("central_europe", "oklahoma"):
            preset, month = M.CONFIGS[name]
            accs = [M.run_sandbox(preset, month, s, 2.0, 1.0, 1920, 720, 10, 365) for s in (11, 12)]
            cls.res[name] = M.per_hour(M.merge(accs))
            print(f"\n{name}: ", {k: round(v, 3) for k, v in M.summary(cls.res[name]).items()})

    def test_cumulonimbus_alive_at_22_to_05_h_is_at_most_a_quarter_of_14_to_18_h(self):
        for name, limit in (("central_europe", 0.25), ("oklahoma", 0.35)):       # (the plains keep more of their nights)
            s = M.summary(self.res[name])
            self.assertGreater(s["cb_aft"], 1.0, f"{name}: the afternoon has storms to compare with ({s['cb_aft']:.2f} alive)")
            self.assertLessEqual(s["cb_ratio"], limit, f"{name}: night/afternoon ratio {s['cb_ratio']:.2f}")

    def test_new_storms_between_23_and_06_h_are_an_order_of_magnitude_rarer(self):
        """Births (spawns + towerings) per hour bin per sim day, the two climates pooled (a 2-seed, 2-day sample of one
        climate has only a handful of births)."""
        born_aft = born_night = 0.0
        for name, res in self.res.items():
            s = M.summary(res)
            print(f"{name}: Cb born per hour bin per day, 23-05 h {s['born_night']:.3f}, 14-18 h {s['born_aft']:.3f}")
            born_aft += s["born_aft"] / len(self.res)
            born_night += s["born_night"] / len(self.res)
        self.assertGreater(born_aft, 0.2, f"afternoon births {born_aft:.3f} per hour bin per day")
        self.assertLessEqual(born_night, 0.1 * born_aft, f"{born_night:.3f} at night vs {born_aft:.3f}")

    def test_fair_weather_cumulus_fade_and_the_night_is_not_empty(self):
        s = M.summary(self.res["central_europe"])
        self.assertLessEqual(s["cu_night"], 0.35 * s["cu_day"], (s["cu_night"], s["cu_day"]))
        self.assertGreater(s["tot_night"], 4.0, "a cloudy climate still has about six clouds at night")
        self.assertGreater(s["strat_night"], 0.8 * s["strat_day"], "the sheets fill in: stratiform cloud does not drop at night")


class TestSaveAndDeterminism(unittest.TestCase):
    def test_a_save_without_the_diurnal_key_loads_and_its_clouds_follow_the_day(self):
        sim = Simulation(3, 1280, 720, 30, hour=14.0)
        for _ in range(30 * 5):
            sim.step(DT)
        state = json.loads(json.dumps(sim.to_state()))
        self.assertTrue(state["clouds"])
        for cs in state["clouds"]:
            self.assertIn("diurnal", cs)
            del cs["diurnal"]                              # what a file written before Phase O looks like
        b = Simulation.from_state(state)
        self.assertEqual([c.diurnal for c in b.clouds], [True] * len(b.clouds))
        for _ in range(30 * 5):
            b.step(DT)

    def test_a_players_cloud_stays_the_players_through_a_save(self):
        sim = quiet(hour=3.0)
        sim.handle_command({"cmd": "spawn", "x": 800, "y": 300, "type": "cumulonimbus"}, [])
        b = Simulation.from_state(json.loads(json.dumps(sim.to_state())))
        self.assertEqual([c.diurnal for c in b.clouds], [False])

    def test_the_same_seed_gives_the_same_run(self):
        runs = []
        for _ in range(2):
            sim = Simulation(9, 1280, 720, 30, hour=17.0)
            for _ in range(30 * 20):
                sim.step(DT)
            runs.append(json.dumps([sim.next_cloud_id, [(c.id, c.type, round(c.age, 6)) for c in sim.clouds]]))
        self.assertEqual(runs[0], runs[1])


class TestWorldMode(unittest.TestCase):
    def test_the_great_plains_outbreak_follows_the_local_day(self):
        ws = W.WorldSim(1, 15, month=5.5, hour=0.0, year_days=365)
        lon = -97.0                                        # local hour = utc hour - 6.5
        ws._base_uh = (15.0 - lon / 15.0) % 24.0
        self.assertEqual(ws._outbreak_gate(lon), 1.0, "15 h local")
        ws._base_uh = (3.0 - lon / 15.0) % 24.0
        self.assertAlmostEqual(ws._outbreak_gate(lon), W.OUTBREAK_NIGHT_FLOOR, places=9)

    def test_oklahoma_shows_no_storm_label_in_the_dead_of_night_but_many_in_the_afternoon(self):
        night = aft = 0
        n_night = n_aft = 0
        i_ok = [i for i, p in enumerate(W.P.PRESET_IDS) if p == "oklahoma"][0]
        for seed in (1, 2, 3):
            ws = W.WorldSim(seed, 15, month=5.5, hour=0.0, year_days=365)
            ws._build_grid = lambda: {}
            for k in range(3 * 300):
                loc = ws.step(1.0)["locations"][i_ok]
                if k < 300:
                    continue
                h = loc["local_hour"]
                if h >= 22 or h < 6:
                    n_night += 1
                    night += loc["weather"] == "storm"
                elif 14 <= h < 19:
                    n_aft += 1
                    aft += loc["weather"] == "storm"
        print(f"\nOklahoma storm labels: night {night}/{n_night}, afternoon {aft}/{n_aft}")
        self.assertGreater(aft / n_aft, 0.10, "a third of the afternoons show a storm")
        self.assertLessEqual(night / n_night, 0.1 * aft / n_aft, "the night rate is at most a tenth of the afternoon's")


if __name__ == "__main__":
    unittest.main()
