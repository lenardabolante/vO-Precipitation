"""Roads and cars (protocol v2.3 "Cars"): the road spans, ids, traffic rules (gap, wet and slope slowing, turning back,
entering and leaving), lights and wipers, and what a tornado does to a car (toss, wreck, removal)."""
import json
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_cars.py` (or sim.tests.test_cars) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import cars as K
from simulation import Simulation
from settlements import plan_roads

DT = 1.0 / 30


def world(seed=5, w=3840, h=1080, settlements="normal", **kw):
    sim = Simulation(seed, w, h, 30, "rolling", settlements, **kw)
    sim._next_spawn_delay = 1e18        # a clear sky: no clouds to spoil the weather
    sim.clouds.clear()
    return sim


class FakeTornado:
    """Just what the car system reads from a tornado."""
    def __init__(self, x, wind_ms=70.0, width=60.0, ground_y=900.0):
        self.x = x
        self.wind_ms = wind_ms
        self.width = width
        self.ground_factor = 1.0
        self.core_radius = max(width * 0.5, 8.0) * 1.25
        self.base_y = ground_y - 400.0
        self.ground_y = ground_y
        self.lean = 0.0

    def axis_x(self, y):
        return self.x


class TestRoads(unittest.TestCase):
    def test_streets_inside_settlements_and_one_highway(self):
        for seed in (1, 2, 3, 4):
            sim = world(seed)
            roads = sim.roads
            streets = [r for r in roads if r["kind"] == "street"]
            hw = [r for r in roads if r["kind"] == "highway"]
            self.assertEqual(len(hw), 1)
            self.assertEqual((hw[0]["x0"], hw[0]["x1"]), (0.0, 3840.0), "it crosses the world")
            self.assertEqual(len(streets), len(sim.settlements))
            for r, s in zip(sorted(streets, key=lambda r: r["x0"]), sorted(sim.settlements, key=lambda s: s["x0"])):
                self.assertTrue(s["x0"] <= r["x0"] < r["x1"] <= s["x1"], "a street lies inside its settlement")
            hello = sim.hello_message()
            S.validate_hello(hello)
            self.assertEqual(hello["roads"], roads)

    def test_none_has_no_roads_and_no_cars(self):
        sim = world(3, settlements="none")
        self.assertEqual(sim.roads, [])
        self.assertNotIn("roads", sim.hello_message())
        for _ in range(60):
            f = sim.step(DT)
        self.assertNotIn("cars", f)
        self.assertEqual(f["stats"]["cars"], 0)
        self.assertEqual(plan_roads([], 3840), [])
        showcase_free = Simulation(3, 1920, 1080, 30, "flat", "none")
        self.assertEqual(showcase_free.cars.cars, [])


class TestTraffic(unittest.TestCase):
    def test_ids_are_unique_stable_and_the_same_for_the_same_seed(self):
        a, b = world(7), world(7)
        other = world(8)
        ids_a, first_seen = set(), {}
        for i in range(30 * 90):
            fa, fb = a.step(DT), b.step(DT)
            self.assertEqual(json.dumps(fa["cars"]), json.dumps(fb["cars"]), f"frame {i}")
            ids = [c["id"] for c in fa["cars"]]
            self.assertEqual(len(ids), len(set(ids)))
            for c in fa["cars"]:
                S.validate_car(c)
                first_seen.setdefault(c["id"], c["variant"])
                self.assertEqual(first_seen[c["id"]], c["variant"], "an id keeps its car")
            ids_a |= set(ids)
        self.assertGreater(len(ids_a), len(world(7).cars.cars), "cars come and go, ids are never reused")
        self.assertNotEqual([c.color for c in a.cars.cars], [c.color for c in other.cars.cars])

    def test_count_scales_with_width_and_is_capped(self):
        small = world(5, w=1920)
        big = world(5, w=3840)
        huge = world(5, w=9600)
        self.assertLess(len(small.cars.cars), len(big.cars.cars))
        for sim in (small, big, huge):
            for _ in range(30 * 60):
                sim.step(DT)
            self.assertLessEqual(len(sim.cars.cars), sim.cars.cap)
        self.assertGreaterEqual(len(huge.cars.cars), len(big.cars.cars))
        self.assertLessEqual(huge.cars.cap, 30 + 20 * 9600 / 1280.0 + 1)

    def test_variants_and_colours_come_from_the_car_seed(self):
        sim = world(5)
        for _ in range(30 * 60):
            sim.step(DT)
        variants = {c.variant for c in sim.cars.cars}
        self.assertGreaterEqual(len(variants), 3)
        self.assertTrue(variants <= set(K.VARIANTS))
        import random
        for c in sim.cars.cars:
            r = random.Random(c.seed)
            mix = K.HIGHWAY_MIX if c.road == 1 else K.STREET_MIX
            self.assertEqual(r.choices([v for v, _ in mix], weights=[p for _, p in mix], k=1)[0], c.variant)
            self.assertTrue(all(0 <= v <= 255 for v in c.color))
            self.assertEqual(c.length, K.LENGTH[c.variant])

    def test_no_two_cars_overlap_in_a_lane(self):
        for seed in (3, 5):
            sim = world(seed)
            worst = 1e9
            for i in range(30 * 150):
                sim.step(DT)
                for d in (1, -1):
                    lane = sorted((c for c in sim.cars.cars if c.dir == d and c.state in ("driving", "stopped")),
                                  key=lambda c: c.x)
                    for a, b in zip(lane, lane[1:]):
                        gap = b.x - a.x - 0.5 * (a.length + b.length)
                        worst = min(worst, gap)
                        self.assertGreaterEqual(gap, -0.01, f"seed {seed} frame {i}: cars {a.id} and {b.id} overlap by {-gap:.1f}")
            print(f"seed {seed}: smallest bumper gap over 150 s = {worst:.1f} px")

    def test_x_speed_and_state_are_smooth(self):
        sim = world(5)
        prev = {}
        for _ in range(30 * 100):
            sim.step(DT)
            for c in sim.cars.cars:
                p = prev.get(c.id)
                if p is not None and c.state in ("driving", "stopped") and p[2] in ("driving", "stopped"):
                    self.assertLessEqual(abs(c.x - p[0]), 4.0, f"car {c.id} jumped")
                    self.assertLessEqual(abs(c.speed - p[1]), 2.0 * K.BRAKE * DT + 0.01, f"car {c.id} speed step")
                prev[c.id] = (c.x, c.speed, c.state)

    def test_wet_and_snowy_ground_slow_the_traffic_and_the_slope_too(self):
        def mean_speed(wet=0.0, snow=0.0):
            sim = world(5)
            for _ in range(30 * 40):
                for k in range(len(sim.ground.wet)):
                    sim.ground.wet[k] = wet
                    sim.ground.snow[k] = snow
                sim.step(DT)
            v = [c.speed / c.vcruise for c in sim.cars.cars if c.state == "driving"]
            return sum(v) / len(v)
        dry, wet, snow = mean_speed(), mean_speed(wet=1.0), mean_speed(snow=1.0)
        print(f"relative speed: dry {dry:.2f}, wet {wet:.2f}, snow {snow:.2f}")
        self.assertLess(wet, dry * 0.9)
        self.assertLess(snow, wet)

        # slope: the same car on a steep road drives slower than on a flat one
        class Steep:
            def slope_at(self, x):
                return 0.8
        sim = world(5)
        car = next(c for c in sim.cars.cars if c.road == 1)
        car.speed = car.vcruise
        car.x = 1000.0
        sim.cars.cars = [car]
        flat_v = car.vcruise
        sim.cars._drive(car, 1.0, car.dir, None, [], lambda x: 0.0, [])
        v_flat = car.speed
        car.speed = car.vcruise
        sim.cars._drive(car, 1.0, car.dir, None, [], lambda x: 0.8, [])
        self.assertLess(car.speed, v_flat * 0.7)
        self.assertEqual(flat_v, car.vcruise)

    def test_street_cars_turn_back_and_the_highway_cars_enter_and_leave(self):
        sim = world(5)
        flips = 0
        dir0 = {c.id: c.dir for c in sim.cars.cars}
        entered, left = set(), set()
        seen = {c.id: c.x for c in sim.cars.cars}
        for _ in range(30 * 200):
            sim.step(DT)
            now = {c.id: c for c in sim.cars.cars}
            for cid, c in now.items():
                if cid in dir0 and c.dir != dir0[cid] and c.road == 0:
                    flips += 1
                dir0[cid] = c.dir
                if cid not in seen and c.road == 1 and (c.x < 5 or c.x > sim.world.w - 5):
                    entered.add(cid)
            for cid, x in seen.items():
                if cid not in now and (x > sim.world.w - 200 or x < 200):
                    left.add(cid)
            seen = {cid: c.x for cid, c in now.items()}
            for c in sim.cars.cars:
                if c.road == 0:
                    streets = [r for r in sim.roads if r["kind"] == "street"]
                    self.assertTrue(any(r["x0"] - 2 <= c.x <= r["x1"] + 2 for r in streets) or c.state == "tossed",
                                    f"street car {c.id} left its street at {c.x}")
        self.assertGreater(flips, 2, "street cars turn back at the end of their street")
        self.assertGreater(len(entered), 3, "highway cars come in at the world edges")
        self.assertGreater(len(left), 3, "... and leave there")

    def test_lights_by_night_wipers_in_rain(self):
        sim = world(5)
        sim.handle_command({"cmd": "hour", "value": 13.0}, [])
        for _ in range(60):
            sim.step(DT)
        self.assertFalse(any(c.lights or c.wipers for c in sim.cars.cars), "a dry noon: lights off")
        sim.handle_command({"cmd": "hour", "value": 2.0}, [])
        for _ in range(60):
            f = sim.step(DT)
        self.assertTrue(all(c.lights for c in sim.cars.cars), "night: lights on")
        self.assertFalse(any(c.wipers for c in sim.cars.cars))
        self.assertTrue(all(c["lights"] for c in f["cars"]))
        sim.handle_command({"cmd": "hour", "value": 13.0}, [])
        for _ in range(60):
            sim.step(DT)
        self.assertFalse(any(c.lights for c in sim.cars.cars))
        # rain falling on them (a rain span over the whole world) turns the wipers and the lights on
        for _ in range(60):
            sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [(-100.0, 5000.0, 0.8)], [], [])
        self.assertTrue(all(c.wipers and c.lights for c in sim.cars.cars))
        for _ in range(120):
            sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [], [], [])
        self.assertFalse(any(c.wipers or c.lights for c in sim.cars.cars), "dry again")
        # a dark storm overhead: lights, but no wipers
        for _ in range(60):
            sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [], [(-100.0, 5000.0, 0.9)], [])
        self.assertTrue(all(c.lights and not c.wipers for c in sim.cars.cars))


class TestTornadoAndCars(unittest.TestCase):
    def setUp(self):
        self.sim = world(5)
        self.hw_car = next(c for c in self.sim.cars.cars if c.road == 1)

    def test_a_car_in_the_funnel_is_tossed_smoothly_and_ends_wrecked_then_is_removed(self):
        sim = self.sim
        car = self.hw_car
        car.dir = 1
        car.speed = car.vcruise
        sim.cars.cars = [car]
        tor = FakeTornado(car.x + 30.0, ground_y=sim.terrain.height_at(car.x))
        trace = []
        for i in range(30 * 20):
            sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [], [], [tor])
            trace.append((car.state, car.x, car.lift, car.spin, car.speed))
        states = [t[0] for t in trace]
        self.assertIn("tossed", states)
        self.assertEqual(states[-1], "wrecked")
        self.assertTrue(states.index("tossed") < states.index("wrecked"))
        self.assertNotIn("driving", states[states.index("tossed"):])
        lift = [t[2] for t in trace]
        self.assertGreater(max(lift), 60.0, "thrown well off the ground")
        self.assertLessEqual(max(lift), 330.0)
        self.assertEqual(lift[-1], 0.0)
        for a, b in zip(trace, trace[1:]):
            self.assertLessEqual(abs(b[1] - a[1]), 14.0, "x is continuous")
            self.assertLessEqual(abs(b[2] - a[2]), 30.0, "lift is continuous")
            self.assertLessEqual(abs(b[3] - a[3]), 0.3, "spin is continuous")
        self.assertGreater(abs(trace[-1][3]), 1.0, "it tumbled")
        wreck_i = states.index("wrecked")
        self.assertEqual(len({t[1] for t in trace[wreck_i:]}), 1, "a wreck never moves again")
        self.assertEqual({t[4] for t in trace[wreck_i:]}, {0.0})
        # removed after a while
        for _ in range(int(K.WRECK_LIFE_S * 30) + 30):
            sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [], [], [])
            if car not in sim.cars.cars:
                break
        self.assertNotIn(car, sim.cars.cars, "the wreck is removed after WRECK_LIFE_S")
        self.assertEqual([c for c in sim.cars.cars if c.state == "wrecked"], [])

    def test_a_weak_gust_leaves_cars_alone_and_a_tornado_that_is_not_down_does_too(self):
        sim = self.sim
        car = self.hw_car
        sim.cars.cars = [car]
        for wind, gf in ((30.0, 1.0), (70.0, 0.3)):
            tor = FakeTornado(car.x, wind_ms=wind)
            tor.ground_factor = gf
            for _ in range(10):
                sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [], [], [tor])
            self.assertIn(car.state, ("driving", "stopped"))

    def test_drivers_slow_for_a_tornado_ahead_and_most_stop_short(self):
        sim = self.sim
        sim.cars.cars = [c for c in sim.cars.cars if c.road == 1][:0]
        cars = []
        for k in range(12):
            c = sim.cars._make(K.ROAD_HIGHWAY, 400.0 - k * 120.0, 1, 1.0)
            cars.append(c)
            sim.cars.cars.append(c)
        tor = FakeTornado(1500.0, width=50.0, ground_y=sim.terrain.height_at(1500.0))
        for _ in range(30 * 40):
            sim.cars.update(DT, sim.terrain, 0.55, sim.ground, [], [], [tor])
        tossed = [c for c in cars if c.state in ("tossed", "wrecked")]
        stopped = [c for c in cars if c.state in ("driving", "stopped") and abs(c.x - tor.x) > 100]
        self.assertGreaterEqual(len(stopped), 6, "most drivers stop short of it")
        self.assertTrue(all(c.x < tor.x for c in stopped))
        self.assertLessEqual(len(tossed), 6, "only the careless ones are caught")

    def test_live_tornado_over_the_highway_tosses_cars_in_a_real_run(self):
        import clouds as C
        sim = world(5, w=3840)
        sim.merging = False
        c = sim._spawn_cloud_auto(x=1700.0, cloud_type="supercell")
        c.rot_peak = c.wall_peak = 0.95
        c.vx = c.vy = 0.0
        for _ in range(30 * 200):
            sim.step(DT)
            c.vx = c.vy = 0.0
            if C.supercell_mature(c):
                break
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
        tossed, wrecked, max_lift = set(), set(), 0.0
        prev = {}
        while sim.tornadoes:
            f = sim.step(DT)
            c.vx = c.vy = 0.0
            for car in f["cars"]:
                S.validate_car(car)
                if car["state"] == "tossed":
                    tossed.add(car["id"])
                    max_lift = max(max_lift, car["lift"])
                if car["state"] == "wrecked":
                    wrecked.add(car["id"])
                p = prev.get(car["id"])
                if p is not None:
                    self.assertLessEqual(abs(car["x"] - p["x"]), 14.0)
                    self.assertLessEqual(abs(car["lift"] - p["lift"]), 30.0)
                    self.assertLessEqual(abs(car["spin"] - p["spin"]), 0.31)
                prev[car["id"]] = car
        print(f"live tornado over a road: {len(tossed)} cars tossed, {len(wrecked)} wrecked, max lift {max_lift:.0f} px")
        self.assertGreaterEqual(len(tossed), 1)
        self.assertEqual(tossed, wrecked, "every tossed car ends up wrecked")


class FlatGround:
    """Just what CarSystem.update reads of the ground and the terrain: a level, dry road."""
    def __init__(self, n=70):
        self.wet = [0.0] * n
        self.snow = [0.0] * n


class FlatTerrain:
    @staticmethod
    def slope_at(x):
        return 0.0

    @staticmethod
    def height_at(x):
        return 900.0


class DepthEverywhere:
    """A stand-in for the WaterField: `depth_at(x)` is `depth` inside [x0, x1] and 0 outside."""
    def __init__(self, depth=0.0, x0=0.0, x1=1e9):
        self.depth, self.x0, self.x1 = depth, x0, x1

    def depth_at(self, x):
        return self.depth if self.x0 <= x <= self.x1 else 0.0


class TestStandingWater(unittest.TestCase):
    """v2.4: cars slow as the water deepens and stand when it reaches the middle of their wheels."""

    def one_car(self, variant="sedan", x=200.0):
        roads = [{"x0": 0.0, "x1": 3000.0, "kind": "street"}]
        cs = K.CarSystem(roads, 3000, 5)
        c = cs._make("street", x, 1, 1.0)
        c.variant = variant
        c.length = K.LENGTH[variant]
        c.vcruise = 30.0
        c.speed = 30.0
        cs.cars.append(c)
        return cs, c

    def drive(self, cs, water, secs):
        for _ in range(int(secs * 30)):
            cs.update(DT, FlatTerrain, 0.5, FlatGround(), [], [], [], water)

    def test_the_stall_depth_is_1_8_wheel_radii_and_a_sedan_stands_at_5_8_px(self):
        self.assertEqual(K.WHEEL_R, {"sedan": 3.2, "hatchback": 3.1, "pickup": 3.5, "van": 3.4, "truck": 3.9, "bus": 3.9})
        self.assertAlmostEqual(K.STALL_PER_WHEEL_R * K.WHEEL_R["sedan"], 5.76, places=2)
        self.assertEqual(set(K.WHEEL_R), set(K.VARIANTS))

    def test_speed_falls_in_proportion_to_the_depth_and_is_zero_at_the_stall_depth(self):
        for variant in K.VARIANTS:
            stall = K.STALL_PER_WHEEL_R * K.WHEEL_R[variant]
            cs, c = self.one_car(variant)
            self.drive(cs, DepthEverywhere(0.0), 3.0)
            dry = c.speed
            self.assertAlmostEqual(dry, 30.0, delta=0.5, msg=variant)
            for frac in (0.25, 0.5, 0.75):
                cs, c = self.one_car(variant)
                self.drive(cs, DepthEverywhere(frac * stall), 6.0)
                self.assertAlmostEqual(c.speed, 30.0 * (1.0 - frac), delta=1.0, msg=f"{variant} at {frac} of the stall depth")
                self.assertEqual(c.state, "driving")
            cs, c = self.one_car(variant)
            self.drive(cs, DepthEverywhere(stall), 6.0)
            self.assertLess(c.speed, 0.8, variant)
            self.assertEqual(c.state, "stopped", variant)
            x_stopped = c.x
            self.drive(cs, DepthEverywhere(stall + 3.0), 3.0)
            self.assertEqual(c.x, x_stopped, "a stopped car stays put in deeper water")

    def test_a_stopped_car_moves_off_again_as_the_water_drains(self):
        cs, c = self.one_car("sedan")
        self.drive(cs, DepthEverywhere(8.0), 5.0)
        self.assertEqual(c.state, "stopped")
        x0 = c.x
        self.drive(cs, DepthEverywhere(1.0), 6.0)
        self.assertEqual(c.state, "driving")
        self.assertGreater(c.x - x0, 60.0, "it drives off: well over 60 px in 6 s")
        self.drive(cs, DepthEverywhere(0.0), 4.0)
        self.assertAlmostEqual(c.speed, 30.0, delta=0.5)

    def test_water_only_where_the_car_is_matters_and_the_traffic_queues_behind_a_stopped_car(self):
        roads = [{"x0": 0.0, "x1": 3000.0, "kind": "street"}]
        cs = K.CarSystem(roads, 3000, 5)
        lead = cs._make("street", 900.0, 1, 1.0)
        back = cs._make("street", 400.0, 1, 1.0)
        for c in (lead, back):
            c.variant, c.length, c.vcruise, c.speed = "sedan", K.LENGTH["sedan"], 30.0, 30.0
        cs.cars += [lead, back]
        pool = DepthEverywhere(7.0, 1000.0, 1300.0)               # a flooded stretch ahead
        for _ in range(30 * 25):
            cs.update(DT, FlatTerrain, 0.5, FlatGround(), [], [], [], pool)
        self.assertEqual(lead.state, "stopped")
        self.assertTrue(1000.0 - 40.0 < lead.x < 1010.0, f"the leader stands at the edge of the water ({lead.x:.0f})")
        self.assertEqual(back.state, "stopped", "the car behind queues")
        self.assertGreater(lead.x - back.x, 0.5 * (lead.length + back.length), "and does not run into it")
        self.assertEqual(back.depth, 0.0, "it is not in the water itself")
        self.assertGreater(lead.depth, 0.0)

    def test_depth_goes_out_on_the_wire_only_when_there_is_water_and_never_as_zero(self):
        cs, c = self.one_car("sedan")
        self.drive(cs, DepthEverywhere(0.0), 1.0)
        self.assertNotIn("depth", cs.to_protocol()[0])
        self.drive(cs, DepthEverywhere(2.346), 0.2)
        row = cs.to_protocol()[0]
        self.assertEqual(row["depth"], 2.3)
        S.validate_car(row)
        self.drive(cs, DepthEverywhere(0.04), 0.1)                  # a film that rounds to 0.0 is not sent
        self.assertNotIn("depth", cs.to_protocol()[0])

    def test_a_car_without_a_water_field_is_exactly_what_it_was_before(self):
        """No `water` argument (every caller before v2.4, and the other modes) changes nothing."""
        for seed in (3, 9):
            a = world(seed)
            b = world(seed)
            b.water = None
            for _ in range(30 * 40):
                fa, fb = a.step(DT), b.step(DT)
                self.assertEqual(json.dumps(fa["cars"], sort_keys=True), json.dumps(fb["cars"], sort_keys=True))
                self.assertTrue(all("depth" not in c for c in fa["cars"]), "no water, no depth")

    def test_a_flooded_street_stops_the_cars_in_a_real_world_and_they_move_again_when_it_drains(self):
        sim = world(5)         # (v2.4.1: seed 5, where the sheet runs off to the world's edge; on seed 4 it backs a 13 px pool up over the highway)
        sim.handle_command({"cmd": "drains", "mode": "small"}, [])
        s = max(sim.settlements, key=lambda t: t["x1"] - t["x0"])
        span = s["x1"] - s["x0"]
        # (v2.4.1: a street has no body of its own unless the terrain makes it a hollow: 7 px of film over the whole street
        # is the flood, and the cars read the film's depth the way they read a body's; it runs off and drains)
        for k in sim.water.film.span_cells[s["id"]]:
            sim.water.film.S[k] = 7.0 * 32.0
            sim.water.film.act.add(k)
        street_cars = [c for c in sim.cars.cars if c.road == 0 and s["x0"] <= c.x <= s["x1"]]
        self.assertGreaterEqual(len(street_cars), 2)
        ids = {c.id for c in street_cars}
        seen_depth = False
        for _ in range(30 * 8):
            f = sim.step(DT)
            for row in f["cars"]:
                if row["id"] in ids and "depth" in row:
                    seen_depth = True
                    S.validate_car(row)
        self.assertTrue(seen_depth, "cars report the depth of the water they stand in")
        stuck = [c for c in sim.cars.cars if c.id in ids and c.depth >= K.STALL_PER_WHEEL_R * K.WHEEL_R[c.variant]]
        self.assertTrue(stuck or any(c.state == "stopped" for c in sim.cars.cars if c.id in ids),
                        "in a 7 px sheet the cars are past their stall depth (they stop)")
        self.assertTrue(all(c.state == "stopped" for c in stuck))
        # the sheet runs off the low end and the drains take the rest; then the cars are moving again
        for _ in range(30 * 150):
            sim.step(DT)
        left = sum(sim.water.film.S[k] for k in sim.water.film.span_cells[s["id"]])
        self.assertLess(left / 32.0 / len(sim.water.film.span_cells[s["id"]]), 0.05, "the street is dry again")
        moving = [c for c in sim.cars.cars if c.id in ids and c.state == "driving"]
        self.assertGreaterEqual(len(moving), 2, "the traffic is back once the street is dry")


class TestSaveLoad(unittest.TestCase):
    def test_cars_round_trip_frame_identical(self):
        a = world(9)
        for _ in range(30 * 40):
            a.step(DT)
        b = Simulation.from_state(json.loads(json.dumps(a.to_state())))
        self.assertEqual(a.roads, b.roads)
        for i in range(30 * 60):
            fa, fb = a.step(DT), b.step(DT)
            if i == 0:                      # a loaded world resends the ground strip once
                fa.pop("ground", None)
                fa.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fa.pop("flood", None)
                fa.pop("flow", None)        # (v2.4.1: and frame.flow)
                fb.pop("ground", None)
                fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fb.pop("flood", None)
                fb.pop("flow", None)
            self.assertEqual(json.dumps(fa, sort_keys=True), json.dumps(fb, sort_keys=True), f"frame {i}")

    def test_a_v22_state_has_no_roads_and_no_cars(self):
        a = world(9)
        for _ in range(60):
            a.step(DT)
        s = json.loads(json.dumps(a.to_state()))
        for k in ("cars", "tornadoes", "next_tornado_id", "tornadoes_total", "tor_rng", "tor_cool"):
            s.pop(k, None)
        s["particles"].pop("debris", None)
        s["particles"].pop("debris_ttl", None)
        for k in ("dmg_f", "dmg_changed", "storm_sway"):
            s["objects"].pop(k, None)
        b = Simulation.from_state(s)
        self.assertEqual(b.roads, [])
        self.assertEqual(b.cars.cars, [])
        self.assertNotIn("roads", b.hello_message())
        for _ in range(60):
            f = b.step(DT)
        self.assertNotIn("cars", f)
        self.assertEqual((f["stats"]["cars"], f["stats"]["tornadoes"], f["stats"]["tornadoes_total"]), (0, 0, 0))
        S.validate_hello(b.hello_message())


if __name__ == "__main__":
    unittest.main()
