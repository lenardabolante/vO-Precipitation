"""Gases, instability and the climate-driven spawner (protocol v2.3): every number responds in the
documented direction to temperature, wind speed and each gas, clamps hold, nothing steps."""
import math
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_atmosphere.py` (or sim.tests.test_atmosphere) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import atmosphere as A
from simulation import Simulation

DT = 1.0 / 30
MIX = {"cumulus": 5, "cumulonimbus": 1, "nimbostratus": 2, "stratocumulus": 3, "altostratus": 2,
       "altocumulus": 3, "stratus": 2, "cirrus": 3}


def snap(temp=10.0, heat=None, speed=5.5, hum=0.575, aer=0.3, wu=3.0, mix=MIX):
    """The atmosphere for a given surface temperature (heat defaults to a typical afternoon offset)."""
    heat = (temp - 6.0) * 0.7 if heat is None else heat
    inst = A.instability(heat, hum)
    sh = A.shear(speed)
    t1280 = A.cloud_target_per_1280(temp, inst, speed, hum, aer)
    return {"inst": inst, "shear": sh, "target": t1280 * wu,
            "spawn": A.spawn_per_min_per_1280(t1280, inst, speed) * wu,
            "w": A.type_weights(mix, temp, inst, sh, hum)}


class TestGases(unittest.TestCase):
    def test_doubling_co2_is_3_degrees_per_doubling(self):
        self.assertAlmostEqual(A.gas_offset_c(420), 0.0)
        self.assertAlmostEqual(A.gas_offset_c(840), 3.0)
        self.assertAlmostEqual(A.gas_offset_c(210), -3.0)
        self.assertAlmostEqual(A.gas_offset_c(1680), 6.0)

    def test_every_gas_clamps(self):
        a = A.Atmosphere()
        self.assertEqual(a.set_gas("humidity", delta=5.0), 1.0)
        self.assertEqual(a.set_gas("humidity", delta=-9.0), 0.0)
        self.assertEqual(a.set_gas("aerosol", value=3.0), 1.0)
        self.assertEqual(a.set_gas("aerosol", delta=-2.0), 0.0)
        self.assertEqual(a.set_gas("co2", value=99999), 2000.0)
        self.assertEqual(a.set_gas("co2", value=1), 180.0)
        self.assertEqual(a.set_gas("co2", delta=100), 280.0)
        self.assertIsNone(a.set_gas("oxygen", delta=1))
        self.assertIsNone(a.set_gas("co2", delta="x"))
        self.assertIsNone(a.set_gas("co2", value=float("nan")))
        self.assertIsNone(a.set_gas("humidity"))
        c = A.Atmosphere(9.0, 99999.0, -4.0)
        self.assertEqual((c.humidity, c.co2_ppm, c.aerosol), (1.0, 2000.0, 0.0))

    def test_delta_and_value_in_the_gas_own_unit(self):
        a = A.Atmosphere()
        self.assertAlmostEqual(a.set_gas("humidity", delta=0.05), 0.625)
        self.assertEqual(a.set_gas("co2", delta=140), 560.0)
        self.assertEqual(a.set_gas("co2", value=560), 560.0)

    def test_state_round_trip(self):
        a = A.Atmosphere(0.4, 700.0, 0.8)
        b = A.Atmosphere.from_state(a.to_state())
        self.assertEqual(a.to_state(), b.to_state())


class TestInstabilityAndShear(unittest.TestCase):
    def test_instability_rises_with_warm_humid_air_under_a_cold_sky(self):
        h = [A.instability(x, 0.6) for x in range(-10, 25, 2)]
        self.assertTrue(all(b >= a for a, b in zip(h, h[1:])), "monotone in how much warmer the surface is")
        self.assertLess(h[0], 0.05)
        self.assertGreater(h[-1], 0.4)
        hum = [A.instability(14.0, x / 10) for x in range(0, 11)]
        self.assertTrue(all(b > a for a, b in zip(hum, hum[1:])), "monotone in humidity")
        for x in (-30, 0, 14, 40):
            for y in (0.0, 0.5, 1.0):
                self.assertTrue(0.0 <= A.instability(x, y) <= 1.0)

    def test_heating_follows_day_and_user_and_gas_but_the_season_only_partly(self):
        base = {"diurnal_c": 0.0, "season_c": 0.0, "gas_c": 0.0, "user_c": 0.0}
        h0 = A.heating_c(base)
        self.assertGreater(A.heating_c(dict(base, diurnal_c=5.0)), h0 + 4.9)
        self.assertGreater(A.heating_c(dict(base, user_c=5.0)), h0 + 4.9)
        self.assertGreater(A.heating_c(dict(base, gas_c=3.0)), h0 + 2.9)
        self.assertAlmostEqual(A.heating_c(dict(base, season_c=10.0)) - h0, 6.5)

    def test_shear_grows_with_wind(self):
        s = [A.shear(v) for v in (0, 2, 5, 9, 14, 20, 30)]
        self.assertEqual(s[0], 0.0)
        self.assertTrue(all(b > a for a, b in zip(s, s[1:])))
        self.assertLess(s[-1], 1.0 + 1e-9)
        self.assertAlmostEqual(A.shear(-9.0), A.shear(9.0))


class TestSpawnAndWeightsDirection(unittest.TestCase):
    def test_more_humidity_more_clouds_and_faster_spawning(self):
        rows = [snap(hum=h / 10) for h in range(1, 10)]
        self.assertTrue(all(b["target"] > a["target"] for a, b in zip(rows, rows[1:])))
        self.assertTrue(all(b["spawn"] > a["spawn"] for a, b in zip(rows, rows[1:])))
        self.assertGreater(rows[-1]["w"]["cumulonimbus"], rows[0]["w"]["cumulonimbus"])
        self.assertGreater(rows[-1]["w"]["nimbostratus"], rows[0]["w"]["nimbostratus"])

    def test_warm_air_spawns_more_and_favours_convective_types(self):
        cold, mild, hot = snap(temp=-8), snap(temp=10), snap(temp=30)
        self.assertLess(cold["target"], mild["target"])
        self.assertLess(mild["target"], hot["target"])
        self.assertLess(cold["spawn"], mild["spawn"])
        self.assertLess(mild["spawn"], hot["spawn"])
        self.assertLess(cold["w"]["cumulonimbus"], mild["w"]["cumulonimbus"])
        self.assertLess(mild["w"]["cumulonimbus"], hot["w"]["cumulonimbus"])
        self.assertLess(cold["w"]["cumulus"], hot["w"]["cumulus"])
        # sheets want cool, stable, humid air
        self.assertGreater(cold["w"]["nimbostratus"], hot["w"]["nimbostratus"])
        self.assertGreater(cold["w"]["stratus"], hot["w"]["stratus"])

    def test_calm_slow_moderate_fastest_gale_fewer(self):
        speeds = [0.0, 2.0, 4.0, 7.0, 10.0, 15.0, 25.0, 40.0]
        spawn = [snap(speed=v)["spawn"] for v in speeds]
        target = [snap(speed=v)["target"] for v in speeds]
        peak = spawn.index(max(spawn))
        self.assertIn(speeds[peak], (4.0, 7.0, 10.0), "a moderate wind spawns fastest")
        self.assertLess(spawn[0], 0.6 * max(spawn), "a calm sky spawns slowly")
        self.assertLess(spawn[-2], 0.5 * max(spawn), "a gale spawns clearly less")
        self.assertLess(target[-2], target[peak] - 3.0, "and holds fewer clouds")
        self.assertTrue(all(b <= a for a, b in zip(spawn[peak:], spawn[peak + 1:])), "falls (to the floor) past the peak")

    def test_more_aerosol_more_clouds(self):
        rows = [snap(aer=a / 10) for a in range(0, 11, 2)]
        self.assertTrue(all(b["target"] > a["target"] for a, b in zip(rows, rows[1:])))
        self.assertTrue(all(b["spawn"] > a["spawn"] for a, b in zip(rows, rows[1:])))

    def test_greenhouse_gas_acts_through_the_temperature(self):
        base = snap(temp=10.0)
        for co2 in (840, 1680):
            gas = A.gas_offset_c(co2)
            warm = snap(temp=10.0 + gas, heat=(10.0 + gas - 6.0) * 0.7)
            self.assertGreater(warm["spawn"], base["spawn"], co2)
            self.assertGreater(warm["target"], base["target"], co2)
            self.assertGreater(warm["w"]["cumulonimbus"], base["w"]["cumulonimbus"], co2)
        gas = A.gas_offset_c(210)
        cool = snap(temp=10.0 + gas, heat=(10.0 + gas - 6.0) * 0.7)
        self.assertLess(cool["spawn"], base["spawn"])

    def test_dry_cold_still_clouds_over_and_hot_humid_is_capped(self):
        dry = snap(temp=-30, hum=0.0, aer=0.0, speed=0.0, wu=3.0)
        self.assertGreaterEqual(dry["spawn"], 3 * A.SPAWN_FLOOR_PER_1280 - 1e-9)
        self.assertGreater(dry["target"], 3.0)
        wet = snap(temp=45, hum=1.0, aer=1.0, heat=30.0, wu=3.0)
        self.assertLessEqual(wet["target"], 3 * (A.TARGET_MIN_PER_1280 + A.TARGET_SPAN_PER_1280) + 1e-9)
        self.assertLessEqual(wet["target"], 21.0)

    def test_default_sky_is_about_the_old_cloud_count(self):
        d = snap(wu=3.0)
        self.assertTrue(9 <= d["target"] <= 21, d["target"])

    def test_type_weights_are_nine_nonnegative_numbers_summing_to_one(self):
        for temp in (-30, 0, 15, 40):
            for hum in (0.0, 0.5, 1.0):
                for speed in (0, 6, 30):
                    w = snap(temp=temp, hum=hum, speed=speed)["w"]
                    self.assertEqual(set(w), set(A.TYPES9))
                    self.assertTrue(all(v >= 0 for v in w.values()))
                    self.assertAlmostEqual(sum(w.values()), 1.0, places=9)

    def test_default_sky_keeps_the_v22_mix(self):
        ref = A.type_weights(MIX, A.REF_TEMP_C, A.REF_INSTABILITY, A.shear(A.REF_SPEED), A.REF_HUMIDITY)
        tot = sum(MIX.values())
        for k, v in MIX.items():
            self.assertAlmostEqual(ref[k], v / tot, delta=0.01, msg=k)
        self.assertLess(ref["supercell"], 0.001)

    def test_supercell_needs_instability_and_shear(self):
        t = lambda inst, sh: A.type_weights(MIX, 25.0, inst, sh, 0.7)["supercell"]
        self.assertLess(t(0.9, 0.05), 0.005)         # unstable but no shear
        self.assertLess(t(0.1, 0.9), 0.005)          # sheared but stable
        both = t(0.8, 0.8)
        self.assertGreater(both, 0.05)
        self.assertGreater(both, 10 * max(t(0.9, 0.05), t(0.1, 0.9)))

    def test_base_mix_is_respected(self):
        mix = {"cirrus": 10.0, "stratus": 1.0}
        w = A.type_weights(mix, 10.0, 0.25, 0.14, 0.575)
        self.assertGreater(w["cirrus"], 0.8)
        self.assertEqual(w["cumulus"], 0.0)

    def test_aerosol_slows_the_rain_and_lengthens_the_life(self):
        self.assertGreater(A.lifetime_factor(0.9, 0.575), A.lifetime_factor(0.1, 0.575))
        self.assertGreater(A.rain_onset_lf(0.9, 0.575), A.rain_onset_lf(0.1, 0.575))
        self.assertLess(A.drain_scale(0.9, 0.575), A.drain_scale(0.1, 0.575))
        self.assertLess(A.capacity_factor(0.9), A.capacity_factor(0.1))
        self.assertGreater(A.fair_probability(0.8, 0.9, 0.575), A.fair_probability(0.8, 0.1, 0.575))
        self.assertLess(A.fair_probability(0.8, 0.3, 0.9), A.fair_probability(0.8, 0.3, 0.2))
        self.assertAlmostEqual(A.lifetime_factor(A.REF_AEROSOL, A.REF_HUMIDITY), 1.0)
        self.assertAlmostEqual(A.rain_onset_lf(A.REF_AEROSOL, A.REF_HUMIDITY), 0.5)
        self.assertAlmostEqual(A.drain_scale(A.REF_AEROSOL, A.REF_HUMIDITY), 1.0)
        self.assertAlmostEqual(A.develop_scale(A.REF_AEROSOL, A.REF_HUMIDITY), 1.0)
        self.assertAlmostEqual(A.fair_probability(0.8, A.REF_AEROSOL, A.REF_HUMIDITY), 0.8)


class TestSimulationAtmosphere(unittest.TestCase):
    def test_frame_atmosphere_block(self):
        sim = Simulation(5, 3840, 1080, 30)
        f = sim.step(DT)
        a = f["atmosphere"]
        self.assertEqual(set(a), {"humidity", "co2_ppm", "aerosol", "instability", "shear", "spawn_per_min",
                                  "cloud_target", "type_weights"})
        self.assertEqual(a["co2_ppm"], 420)
        self.assertEqual(set(a["type_weights"]), set(A.TYPES9))
        self.assertAlmostEqual(sum(a["type_weights"].values()), 1.0, delta=0.001)
        self.assertTrue(0.45 <= a["humidity"] <= 0.70)
        self.assertEqual(a["aerosol"], 0.3)
        self.assertIsInstance(a["cloud_target"], int)

    def test_gas_commands_change_the_atmosphere(self):
        sim = Simulation(5, 1280, 720, 30)
        sim.step(DT)
        sim.handle_command({"cmd": "gas", "name": "humidity", "delta": 0.2}, [])
        h = sim.atmo.humidity
        sim.handle_command({"cmd": "gas", "name": "humidity", "value": 0.9}, [])
        self.assertEqual(sim.atmo.humidity, 0.9)
        sim.handle_command({"cmd": "gas", "name": "aerosol", "delta": 5}, [])
        self.assertEqual(sim.atmo.aerosol, 1.0)
        sim.handle_command({"cmd": "gas", "name": "co2", "value": 560}, [])
        f = sim.step(DT)
        self.assertEqual(f["atmosphere"]["humidity"], 0.9)
        self.assertEqual(f["atmosphere"]["aerosol"], 1.0)
        self.assertEqual(f["atmosphere"]["co2_ppm"], 560)
        before = sim.atmo.to_state()
        sim.handle_command({"cmd": "gas", "name": "nitrogen", "delta": 1}, [])
        sim.handle_command({"cmd": "gas", "name": "co2", "delta": "lots"}, [])
        self.assertEqual(sim.atmo.to_state(), before)
        self.assertNotEqual(h, 0.9)

    def test_spawn_rate_and_weights_follow_temperature_wind_and_each_gas_in_the_frames(self):
        def frame_after(cmds, steps=900):
            sim = Simulation(5, 1280, 720, 30, year_days=365)
            for c in cmds:
                sim.handle_command(c, [])
            f = None
            for _ in range(steps):
                f = sim.step(DT)
            return f["atmosphere"]
        base = frame_after([])
        humid = frame_after([{"cmd": "gas", "name": "humidity", "value": 0.95}])
        dry = frame_after([{"cmd": "gas", "name": "humidity", "value": 0.1}])
        self.assertGreater(humid["spawn_per_min"], base["spawn_per_min"])
        self.assertGreater(base["spawn_per_min"], dry["spawn_per_min"])
        hot = frame_after([{"cmd": "temp", "delta": 12}])
        cold = frame_after([{"cmd": "temp", "delta": -20}])
        self.assertGreater(hot["spawn_per_min"], base["spawn_per_min"])
        self.assertGreater(base["spawn_per_min"], cold["spawn_per_min"])
        self.assertGreater(hot["type_weights"]["cumulonimbus"], base["type_weights"]["cumulonimbus"])
        self.assertGreater(hot["instability"], base["instability"])
        co2 = frame_after([{"cmd": "gas", "name": "co2", "value": 1680}])      # +6 C once the glide is done
        self.assertGreater(co2["spawn_per_min"], base["spawn_per_min"])
        self.assertGreater(co2["type_weights"]["cumulonimbus"], base["type_weights"]["cumulonimbus"])
        aer = frame_after([{"cmd": "gas", "name": "aerosol", "value": 0.9}])
        self.assertGreater(aer["spawn_per_min"], base["spawn_per_min"])
        self.assertGreater(aer["cloud_target"], base["cloud_target"] - 1)
        calm = frame_after([{"cmd": "wind", "delta": -50}, {"cmd": "wind", "delta": 0.0}])
        gale = frame_after([{"cmd": "wind", "delta": 25}])
        self.assertGreater(gale["shear"], base["shear"])
        self.assertLess(gale["spawn_per_min"], base["spawn_per_min"])
        self.assertLess(gale["cloud_target"], base["cloud_target"])

    def test_nothing_in_the_atmosphere_block_steps_between_frames(self):
        sim = Simulation(5, 1280, 720, 30)
        sim.handle_command({"cmd": "gas", "name": "co2", "value": 840}, [])
        prev = sim.step(DT)["atmosphere"]
        worst = {"spawn_per_min": 0.0, "instability": 0.0, "shear": 0.0}
        for _ in range(600):
            cur = sim.step(DT)["atmosphere"]
            for k in worst:
                worst[k] = max(worst[k], abs(cur[k] - prev[k]))
            prev = cur
        self.assertLess(worst["spawn_per_min"], 0.05)
        self.assertLess(worst["instability"], 0.01)
        self.assertLess(worst["shear"], 0.01)

    def test_realised_birth_rate_follows_the_climate(self):
        """Measured, not assumed: clouds born per sim minute in a hot humid world vs a cold dry one, a
        gale, and a moderate wind (several seeds, the sky holding its own count down by merging/dying)."""
        def births(setup, seeds=(1, 2, 3), secs=180):
            total = 0
            for sd in seeds:
                sim = Simulation(sd, 1280, 720, 30, year_days=365)
                for c in setup:
                    sim.handle_command(c, [])
                for _ in range(30):
                    sim.step(DT)
                b0 = sim.clouds_born
                for _ in range(secs * 30):
                    sim.step(DT)
                total += sim.clouds_born - b0
            return total / (len(seeds) * secs / 60.0)
        hot_wet = births([{"cmd": "temp", "delta": 15}, {"cmd": "gas", "name": "humidity", "value": 0.95}])
        cold_dry = births([{"cmd": "temp", "delta": -25}, {"cmd": "gas", "name": "humidity", "value": 0.15}])
        self.assertGreater(hot_wet, 1.5 * cold_dry, f"hot humid {hot_wet:.2f}/min vs cold dry {cold_dry:.2f}/min")
        self.assertGreater(cold_dry, 0.3, "a cold dry sky still gets some cloud")
        calm = births([{"cmd": "wind", "delta": -100}, {"cmd": "wind", "delta": 0.3}], seeds=(1, 2))
        gale = births([{"cmd": "wind", "delta": 30}], seeds=(1, 2))
        usual = births([], seeds=(1, 2))
        self.assertLess(gale, 0.8 * usual, f"gale {gale:.2f}/min vs usual {usual:.2f}/min")
        self.assertLess(calm, 0.8 * usual, f"calm {calm:.2f}/min vs usual {usual:.2f}/min")

    def test_a_sky_is_never_empty_for_long_even_when_dry_and_cold(self):
        sim = Simulation(7, 1280, 720, 30)
        sim.handle_command({"cmd": "temp", "delta": -30}, [])
        sim.handle_command({"cmd": "gas", "name": "humidity", "value": 0.0}, [])
        empty = longest = 0
        for _ in range(30 * 400):
            f = sim.step(DT)
            empty = empty + 1 if not f["clouds"] else 0
            longest = max(longest, empty)
        self.assertLess(longest / 30.0, 40.0, "never zero clouds for minutes on end")


class TestDesertAridity(unittest.TestCase):
    """v2.3 desert presets (Atmosphere.arid): bone-dry air clears the sky and keeps it dry; nothing else changes."""

    def test_aridity_factor_values_and_default_identity(self):
        self.assertEqual(A.aridity_factor(0.30), 1.0)
        self.assertEqual(A.aridity_factor(0.9), 1.0)
        self.assertAlmostEqual(A.aridity_factor(0.21), 0.2 + 0.8 * 0.49, places=6)
        self.assertAlmostEqual(A.aridity_factor(0.0), 0.2)
        vals = [A.aridity_factor(h / 20.0) for h in range(0, 21)]
        self.assertEqual(vals, sorted(vals))
        # the generic functions with af = 1.0 are bit for bit what they were
        for hum in (0.0, 0.2, 0.5, 0.9):
            self.assertEqual(A.cloud_target_per_1280(12.0, 0.3, 5.0, hum, 0.3),
                             A.cloud_target_per_1280(12.0, 0.3, 5.0, hum, 0.3, 1.0))
            self.assertEqual(A.spawn_per_min_per_1280(3.0, 0.3, 5.0), A.spawn_per_min_per_1280(3.0, 0.3, 5.0, 1.0))
            self.assertEqual(A.develop_scale(0.3, hum), A.develop_scale(0.3, hum, 1.0))
            self.assertEqual(A.type_weights(MIX, 12.0, 0.3, 0.3, hum), A.type_weights(MIX, 12.0, 0.3, 0.3, hum, 1.0))
        self.assertAlmostEqual(A.fair_probability(0.7, 0.3, 0.3), A.fair_probability(0.7, 0.3, 0.3, 1.0))

    def test_an_arid_atmosphere_clears_the_sky_and_favours_thin_high_cloud(self):
        parts = {"surface_temp_c": 32.0, "diurnal_c": 4.0, "season_c": 6.0, "gas_c": 0.0, "user_c": 0.0}
        mix = {"cirrus": 5, "altocumulus": 3, "cumulus": 1.5, "altostratus": 1.5, "stratocumulus": 0.8,
               "cumulonimbus": 0.3, "stratus": 0.2, "nimbostratus": 0.1}
        wet = A.Atmosphere(0.21, 420, 0.38)
        dry = A.Atmosphere(0.21, 420, 0.38, arid=True)
        sw = wet.snapshot(parts, 5.0, 3.0, mix)
        sd = dry.snapshot(parts, 5.0, 3.0, mix)
        self.assertLess(sd["target_f"], 0.7 * sw["target_f"])
        self.assertLess(sd["target_f"], 4.5, "a few clouds over a 3840 px world")
        self.assertLess(sd["spawn_per_min_f"], sw["spawn_per_min_f"])
        for k in A.RAIN_TYPES:
            self.assertLess(sd["weights_f"][k], sw["weights_f"][k], k)
        thin = sd["weights_f"]["cirrus"] + sd["weights_f"]["altocumulus"]
        self.assertGreater(thin, sw["weights_f"]["cirrus"] + sw["weights_f"]["altocumulus"])
        self.assertGreater(thin, 0.7)
        self.assertAlmostEqual(sum(sd["weights_f"].values()), 1.0)
        self.assertGreater(A.fair_probability(0.6, 0.38, 0.21, dry.af()), 0.9)
        self.assertLess(A.develop_scale(0.38, 0.21, dry.af()), A.develop_scale(0.38, 0.21))

    def test_an_arid_atmosphere_with_humid_air_is_exactly_the_ordinary_one(self):
        parts = {"surface_temp_c": 14.0, "diurnal_c": 1.0, "season_c": 2.0, "gas_c": 0.0, "user_c": 0.0}
        for hum in (0.3, 0.5, 0.95):
            a = A.Atmosphere(hum, 420, 0.3).snapshot(parts, 5.0, 3.0, MIX)
            b = A.Atmosphere(hum, 420, 0.3, arid=True).snapshot(parts, 5.0, 3.0, MIX)
            self.assertEqual(a, b, hum)
        self.assertEqual(A.Atmosphere(0.5).af(), 1.0)
        self.assertEqual(A.Atmosphere(0.1).af(), 1.0, "only a desert preset is arid")

    def test_the_flag_is_saved_and_old_states_default_to_not_arid(self):
        a = A.Atmosphere(0.2, 420, 0.4, arid=True)
        b = A.Atmosphere.from_state(a.to_state())
        self.assertTrue(b.arid)
        st = a.to_state()
        st.pop("arid")
        self.assertFalse(A.Atmosphere.from_state(st).arid)

    def test_clouds_born_in_desert_air_hold_little_water_and_are_fair(self):
        from clouds import spawn_cloud
        def born(arid):
            atmo = A.Atmosphere(0.21, 420, 0.38, arid=arid)
            out = []
            for i in range(120):
                c = spawn_cloud(i + 1, 5, i + 1, 500.0, None, 0.0, set(), world_w=3840, world_h=1080,
                                type_mix={"cumulus": 1.0}, atmo=atmo)
                out.append((c.water_capacity, c.fair))
            return sum(w for w, _f in out) / len(out), sum(1 for _w, f in out if f) / len(out)
        cap_dry, fair_dry = born(True)
        cap_wet, fair_wet = born(False)
        self.assertLess(cap_dry, 0.3 * cap_wet)
        self.assertGreater(fair_dry, 0.95)
        self.assertGreater(fair_dry, fair_wet)


if __name__ == "__main__":
    unittest.main()
