"""Object deltas and ground cover: a quiet frame lists (almost) no objects, rain/snow touch only the
objects under it, buildings collect wet/snow like houses, `ground` is sent only on change and at most
~3 times a second, snow lingers on high ground, and landing is bucketed by x (not O(hits x objects))."""
import hashlib
import json
import random
import time
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_objects.py` (or sim.tests.test_objects) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import objects
from objects import (GROUND_STEP, BUILDING_KINDS, PUDDLE_MAX_W, PUDDLE_TOL, GroundCover, ObjectField, generate_world,
                     puddle_cap, puddle_rests, settle_puddle)
from simulation import Simulation
from terrain import Terrain

DT = 1.0 / 30

# sha256 of repr(random.Random.getstate()) after generate_world, taken from the code as it was before the
# 2026-10-09 ground-spot fix: the fix draws the same numbers in the same order (it does not consume extra ones).
RNG_END_STATE = {
    (1, 3840, 1080, "rolling", "normal"): "8225b718a8ac001f3b7a0fb0fd771fb9150621926601041d27a0b365eac0e55e",
    (2, 3840, 1080, "hilly", "dense"): "b24ab0eec947428ab6077c7f1edc4548da191da3cf4cb97ae6dde86337228fa4",
    (5, 2560, 1080, "rolling", "none"): "84543a8f20cfb6232c6e3d3747877e7ffa52333db160838ab023c065c2f7b31e",
    (3, 1920, 1080, "mountainous", "sparse"): "fd23894b4b61a3a664f5bdb4592aca84d3682047e54e1c7c5f8e2352b2a5102b",
}


def field_for(seed=3, w=3840, h=1080, kind="rolling"):
    terr, _s, objs = generate_world(random.Random(seed), w, h, kind, "normal")
    return terr, objs, ObjectField(objs)


class FlatWind:
    def sample(self, x, y):
        return 4.0, 0.0


class TestDeltas(unittest.TestCase):
    def test_quiet_frames_list_no_rain_objects(self):
        sim = Simulation(seed=14, w=3840, h=1080, fps=30)
        sim.clouds.clear()
        sim._spawn_timer = -1e9           # keep the sky empty: nothing can fall
        sim._next_spawn_delay = 1e9
        kinds = {o["id"]: o["kind"] for o in sim.objects.objs}
        rows_total = 0
        for _ in range(120):
            # empty the sky each tick so only wind can move things
            sim.clouds.clear()
            f = sim.step(DT)
            for row in f["objects"]:
                self.assertEqual(kinds[row[0]], "tree", "only wind-driven sway in a dry world")
            rows_total += len(f["objects"])
        self.assertLess(rows_total / 120.0, 12.0, "a dry world is mostly quiet")

    def test_rain_frame_lists_only_exposed_objects(self):
        terr, objs, field = field_for()
        kinds = {o["id"]: o["kind"] for o in objs}
        target = next(o for o in objs if o["kind"] == "house")
        key = int(target["x"] / GROUND_STEP + 0.5)
        field.pop_rows()
        field.update(DT, FlatWind(), {key: 5}, {}, 500.0)
        rows = field.pop_rows()
        ids = {r[0] for r in rows}
        self.assertIn(target["id"], ids)
        for r in rows:
            self.assertIn(kinds[r[0]], set(BUILDING_KINDS) | {"tree", "puddle"},
                          "rain only touches things under it")
        self.assertLess(len(rows), 40)
        row = next(r for r in rows if r[0] == target["id"])
        self.assertGreater(row[3], 0.0, "wet went up")
        self.assertEqual(row[2], 0.0)
        self.assertEqual(field.pop_rows(), [], "rows are consumed: a quiet tick lists nothing")

    def test_buildings_collect_snow_and_wet_like_houses(self):
        terr, objs, field = field_for(seed=4)
        for kind in ("church", "apartment", "office", "skyscraper", "barn", "shop", "house"):
            b = next((o for o in objs if o["kind"] == kind), None)
            if b is None:
                continue
            key = int(b["x"] / GROUND_STEP + 0.5)
            before = (b["wet"], b["snow"])
            field.update(DT, FlatWind(), {key: 20}, {key: 20}, 500.0)
            self.assertGreater(b["wet"], before[0], kind)
            self.assertGreater(b["snow"], before[1], kind)

    def test_puddles_and_snowpacks_grow_where_it_lands(self):
        terr, objs, field = field_for(seed=5)
        pud = [o for o in objs if o["kind"] == "puddle"]
        snp = [o for o in objs if o["kind"] == "snowpack"]
        near = min(pud, key=lambda o: o["x"])
        w0 = near["w"]
        key = int(near["x"] / GROUND_STEP + 0.5)
        for _ in range(50):
            field.update(DT, FlatWind(), {key: 10}, {}, 500.0)
        self.assertGreater(near["w"], w0)
        self.assertLessEqual(near["w"], 140.0)
        self.assertEqual(near["wet"], 1.0)
        sp = min(snp, key=lambda o: o["x"])
        w0 = sp["w"]
        key = int(sp["x"] / GROUND_STEP + 0.5)
        for _ in range(50):
            field.update(DT, FlatWind(), {}, {key: 10}, 500.0)
        self.assertGreater(sp["w"], w0)
        self.assertLessEqual(sp["w"], 160.0)
        self.assertEqual(sp["snow"], 1.0)

    def test_puddles_dry_up_and_snowpacks_melt_away_again(self):
        # regression (testing pass 2026-10-09): w / h only ever grew, so one shower left a 140 px puddle and
        # one snowfall a 160 px white drift for the rest of the world's life
        terr, objs, field = field_for(seed=5)
        pud = min((o for o in objs if o["kind"] == "puddle"), key=lambda o: o["x"])
        snp = min((o for o in objs if o["kind"] == "snowpack"), key=lambda o: o["x"])
        base_p, base_s = (pud["w"], pud["h"]), (snp["w"], snp["h"])
        kp, ks = int(pud["x"] / GROUND_STEP + 0.5), int(snp["x"] / GROUND_STEP + 0.5)
        for _ in range(30 * 20):
            field.update(DT, FlatWind(), {kp: 10}, {ks: 10}, 0.0)
        self.assertGreater(pud["w"], 100.0)
        self.assertGreater(snp["w"], 100.0)
        field.pop_rows()
        last_p, last_s = pud["w"], snp["w"]
        last_ph, last_sh = pud["h"], snp["h"]
        steps_p = steps_s = 0
        rows_seen = set()
        for k in range(30 * 320):                 # warm and dry: wet is gone after 100 s, snow after 200 s
            field.update(DT, FlatWind(), {}, {}, 0.0)    # freezing level far above every object: warm
            self.assertLessEqual(pud["w"], last_p, "a drying puddle never grows")
            self.assertLessEqual(snp["w"], last_s, "a melting snowpack never grows")
            self.assertLess(last_p - pud["w"], 2.0, "the puddle shrinks smoothly (no jump)")
            self.assertLess(last_s - snp["w"], 2.0, "the snowpack shrinks smoothly (no jump)")
            self.assertLess(last_ph - pud["h"], 0.5, "the puddle's height shrinks smoothly too")
            self.assertLess(last_sh - snp["h"], 0.5, "the snowpack's height shrinks smoothly too")
            last_ph, last_sh = pud["h"], snp["h"]
            steps_p += pud["w"] != last_p
            steps_s += snp["w"] != last_s
            last_p, last_s = pud["w"], snp["w"]
            rows_seen.update(r[0] for r in field.pop_rows())
        self.assertEqual((pud["w"], pud["h"], pud["wet"]), (base_p[0], base_p[1], 0.0))
        self.assertEqual((snp["w"], snp["h"], snp["snow"]), (base_s[0], base_s[1], 0.0))
        self.assertGreater(steps_p, 100)
        self.assertGreater(steps_s, 100)
        self.assertIn(pud["id"], rows_seen, "the shrinking is sent to the viewer")
        self.assertIn(snp["id"], rows_seen)
        # cold: a snowpack above the freezing level lasts much longer
        terr, objs, field = field_for(seed=5)
        snp = min((o for o in objs if o["kind"] == "snowpack"), key=lambda o: o["x"])
        ks = int(snp["x"] / GROUND_STEP + 0.5)
        for _ in range(30 * 20):
            field.update(DT, FlatWind(), {}, {ks: 10}, 2000.0)
        for _ in range(30 * 200):
            field.update(DT, FlatWind(), {}, {}, 2000.0)      # freezing level below the ground: everything is in frost
        self.assertGreater(snp["snow"], 0.8)
        self.assertGreater(snp["w"], 120.0)

    def test_a_shrinking_puddle_survives_save_and_load_frame_for_frame(self):
        terr, objs, a = field_for(seed=5)
        pud = min((o for o in objs if o["kind"] == "puddle"), key=lambda o: o["x"])
        kp = int(pud["x"] / GROUND_STEP + 0.5)
        for _ in range(30 * 20):
            a.update(DT, FlatWind(), {kp: 10}, {kp: 10}, 0.0)
        for _ in range(30 * 30):
            a.update(DT, FlatWind(), {}, {}, 0.0)
        b = ObjectField.from_state(json.loads(json.dumps(a.to_state())))
        for _ in range(30 * 40):
            a.update(DT, FlatWind(), {}, {}, 0.0)
            b.update(DT, FlatWind(), {}, {}, 0.0)
            self.assertEqual(a.pop_rows(), b.pop_rows())
        self.assertEqual(a.objs, b.objs)

    def test_drying_and_melt_happen_slowly_and_emit_rows(self):
        terr, objs, field = field_for(seed=6)
        b = next(o for o in objs if o["kind"] == "house")
        key = int(b["x"] / GROUND_STEP + 0.5)
        field.update(DT, FlatWind(), {key: 100}, {key: 100}, 900.0)    # warm: freezing level above ground
        field.pop_rows()
        wet0, snow0 = b["wet"], b["snow"]
        for _ in range(30 * 20):
            field.update(DT, FlatWind(), {}, {}, 900.0)
        self.assertLess(b["wet"], wet0)
        self.assertLess(b["snow"], snow0)
        self.assertTrue(any(r[0] == b["id"] for r in field.pop_rows()))

    def test_update_is_bucketed_not_quadratic(self):
        terr, objs, field = field_for(seed=7, w=9600, h=1400)
        self.assertGreater(len(objs), 300)
        hits = {k: 3 for k in range(0, 300, 3)}      # 100 buckets, 300 landings
        best = 1e9
        for _ in range(5):
            t0 = time.perf_counter()
            field.update(DT, FlatWind(), hits, hits, 500.0)
            best = min(best, time.perf_counter() - t0)
        self.assertLess(best, 0.010, f"object update took {best * 1000:.1f} ms")

    def test_tick_state_round_trips(self):
        terr, objs, field = field_for(seed=8)
        key = int(objs[40]["x"] / GROUND_STEP + 0.5)
        for _ in range(7):
            field.update(DT, FlatWind(), {key: 4}, {key: 2}, 500.0)
        copy = ObjectField.from_state(json.loads(json.dumps(field.to_state())))
        for _ in range(40):
            field.update(DT, FlatWind(), {key: 1}, {}, 500.0)
            copy.update(DT, FlatWind(), {key: 1}, {}, 500.0)
            self.assertEqual(field.pop_rows(), copy.pop_rows())


class TestGround(unittest.TestCase):
    def test_ground_strip_only_on_change_and_at_most_three_times_a_second(self):
        sim = Simulation(seed=7, w=1920, h=900, fps=30)
        sent = []
        for i in range(900):
            f = sim.step(DT)
            if "ground" in f:
                sent.append(i)
                self.assertEqual(f["ground"]["step"], 32)
                self.assertEqual(len(f["ground"]["snow"]), 1920 // 32 + 1)
        self.assertGreater(len(sent), 0, "rain wets the ground")
        gaps = [b - a for a, b in zip(sent, sent[1:])]
        self.assertGreaterEqual(min(gaps), 10, "no more than ~3 per second")
        # nothing changes in a world with no weather at all: no ground strips
        quiet = Simulation(seed=7, w=1920, h=900, fps=30)
        quiet.clouds.clear()
        for _ in range(300):
            quiet.clouds.clear()
            self.assertNotIn("ground", quiet.step(DT))

    def test_snow_lingers_on_high_ground_and_melts_on_low_ground(self):
        sim = Simulation(seed=2, w=3840, h=1080, fps=30, terrain_kind="mountainous")
        sim.world.surface_temp_c = 4.0
        fz = sim.world.freezing_y
        g = sim.ground
        high = min(range(g.n), key=lambda i: g.surf[i])
        low = max(range(g.n), key=lambda i: g.surf[i])
        self.assertLess(g.surf[high], fz)
        self.assertGreater(g.surf[low], fz)
        for _ in range(5):
            g.update(DT, {}, {high: 60, low: 60}, fz)
        self.assertGreater(g.snow[high], 0.5)
        self.assertGreater(g.snow[low], 0.5)
        for _ in range(30 * 70):
            g.update(DT, {}, {}, fz)
        self.assertGreater(g.snow[high], 0.4, "snow lingers above the freezing level")
        self.assertEqual(g.snow[low], 0.0, "snow melts on ground below the freezing level")

    def test_ground_cover_state_round_trips(self):
        sim = Simulation(seed=2, w=1920, h=900, fps=30)
        g = sim.ground
        g.update(DT, {5: 30, 6: 30}, {20: 30}, 500.0)
        copy = GroundCover.from_state(json.loads(json.dumps(g.to_state())),
                                      sim.terrain, 1920)
        for i in range(60):
            g.update(DT, {}, {}, 500.0)
            copy.update(DT, {}, {}, 500.0)
            self.assertEqual(g.maybe_emit(i), copy.maybe_emit(i))


def _spans(items, kinds):
    return [(o["x"] - o["w"] / 2.0, o["x"] + o["w"] / 2.0) for o in items if o["kind"] in kinds]


def _overlaps(o, spans, margin=0.0):
    a, b = o["x"] - o["w"] / 2.0 - margin, o["x"] + o["w"] / 2.0 + margin
    return any(sa < b and sb > a for sa, sb in spans)


class TestGroundSpots(unittest.TestCase):
    """Puddles and snowpacks used to be put on "the flattest of 5 draws", and the flattest ground of a world is the
    levelled pad under a settlement: they stood in the buildings and the streets (testing pass 2026-10-09). They now
    keep clear of what is built."""

    @staticmethod
    def worlds():
        """(label, settlements, objects): 4 sizes x 4 terrains x 4 settlement densities (64 worlds), seeds vary."""
        n = 0
        for (w, h) in ((1920, 900), (3840, 1080), (6400, 1200), (9600, 1400)):
            for kind in ("flat", "rolling", "hilly", "mountainous"):
                for mode in ("none", "sparse", "normal", "dense"):
                    n += 1
                    seed = n * 3 + 1
                    _t, st, objs = generate_world(random.Random(seed), w, h, kind, mode)
                    yield (seed, w, h, kind, mode), st, objs

    def test_no_puddle_or_snowpack_is_inside_a_building_or_a_pond_and_no_snowpack_in_a_street(self):
        for label, st, objs in self.worlds():
            built = _spans(objs, BUILDING_KINDS)
            ponds = _spans(objs, ("pond",))
            streets = [(s["x0"], s["x1"]) for s in st]
            for o in objs:
                if o["kind"] == "puddle":
                    self.assertFalse(_overlaps(o, built), f"puddle in a building {label} x={o['x']}")
                    self.assertFalse(_overlaps(o, ponds), f"puddle in a pond {label} x={o['x']}")
                elif o["kind"] == "snowpack":
                    self.assertFalse(_overlaps(o, built), f"snowpack in a building {label} x={o['x']}")
                    self.assertFalse(_overlaps(o, ponds), f"snowpack in a pond {label} x={o['x']}")
                    self.assertFalse(_overlaps(o, streets), f"snowpack on a street {label} x={o['x']}")

    def test_the_spots_are_on_flat_ground_and_every_world_still_has_them(self):
        """(A steep mountain world with nothing built keeps the flattest of its draws, as before: that can be 0.5.)"""
        slopes = []
        for label, _st, objs in self.worlds():
            w = label[1]
            terr, _s, _o = generate_world(random.Random(label[0]), *label[1:])
            for kind, least in (("puddle", 3), ("snowpack", 3)):
                got = [o for o in objs if o["kind"] == kind]
                self.assertGreaterEqual(len(got), least, (label, kind))
                for o in got:
                    self.assertTrue(20.0 <= o["x"] <= w - 20.0, (label, kind, o["x"]))
                    sl = abs(terr.slope_at(o["x"]))
                    if kind == "puddle":
                        # a puddle comes to rest at the lowest point of its footprint, the bottom of a hollow, whose
                        # steeper side is what slope_at reads; whether it rests is TestPuddlesSettle's business. A
                        # puddle blocked on a slope may be forced down onto the ground under it (settle_puddle).
                        drop = o["y"] - terr.height_at(o["x"])
                        self.assertGreaterEqual(drop, -0.1 - 0.05 * sl, (label, kind))
                        self.assertLessEqual(drop, 8.0 + 0.05 * sl, (label, kind, drop))
                        continue
                    slopes.append(sl)
                    self.assertLessEqual(sl, 0.75, (label, kind, o["x"]))
                    self.assertAlmostEqual(o["y"], terr.height_at(o["x"]), delta=0.1 + 0.05 * sl, msg=(label, kind))
                    if label[3] in ("flat", "rolling"):
                        self.assertLessEqual(sl, 0.2, (label, kind, o["x"]))
        slopes.sort()
        self.assertLessEqual(slopes[len(slopes) // 2], 0.02, "the typical spot is level ground")
        self.assertLessEqual(slopes[int(len(slopes) * 0.9)], 0.06)

    def test_preset_worlds_keep_their_spots_clear_too(self):
        for pid in ("oklahoma", "japan", "sahara", "antarctica"):
            sim = Simulation(3, 3840, 1080, 30, "rolling", "normal", pid)
            objs = sim.objects.objs
            built = _spans(objs, BUILDING_KINDS)
            ponds = _spans(objs, ("pond",))
            streets = [(s["x0"], s["x1"]) for s in sim.settlements]
            for o in objs:
                if o["kind"] in ("puddle", "snowpack"):
                    self.assertFalse(_overlaps(o, built), (pid, o["kind"], o["x"]))
                    self.assertFalse(_overlaps(o, ponds), (pid, o["kind"], o["x"]))
                if o["kind"] == "snowpack":
                    self.assertFalse(_overlaps(o, streets), (pid, o["kind"], o["x"]))

    def test_a_world_with_no_clear_room_has_fewer_spots_and_the_same_stream_and_world(self):
        """If nowhere is clear the spot is left out, but its seed is still drawn: the rest of the world and the
        generator state afterwards are exactly those of a normal world."""
        import objects
        from unittest import mock
        key = (2, 3840, 1080, "hilly", "dense")
        rng_a = random.Random(key[0])
        _t, _s, normal = generate_world(rng_a, *key[1:])
        rng_b = random.Random(key[0])
        with mock.patch.object(objects, "_clear", return_value=False):
            _t, _s, bare = generate_world(rng_b, *key[1:])
        spots = ("puddle", "snowpack")
        self.assertEqual([o for o in bare if o["kind"] in spots], [])
        self.assertEqual([o for o in normal if o["kind"] not in spots], [o for o in bare if o["kind"] not in spots])
        self.assertEqual(rng_a.getstate(), rng_b.getstate())

    def test_the_spot_search_draws_exactly_as_many_random_numbers_as_before(self):
        """Everything seeded after generate_world (clouds, wind...) must not move: the generator ends in the same state."""
        import hashlib
        for seed, w, h, kind, mode in ((1, 3840, 1080, "rolling", "normal"), (2, 3840, 1080, "hilly", "dense"),
                                       (5, 2560, 1080, "rolling", "none"), (3, 1920, 1080, "mountainous", "sparse")):
            rng = random.Random(seed)
            generate_world(rng, w, h, kind, mode)
            got = hashlib.sha256(repr(rng.getstate()).encode()).hexdigest()
            self.assertEqual(got, RNG_END_STATE[(seed, w, h, kind, mode)], (seed, kind, mode))


# (seed, w, h, terrain, settlements): (objects without the windsocks, sha256 of json(terrain.y, settlements, those objects),
#  sha256 of repr(rng.getstate()) after generate_world). Taken 2026-10-10 from the code as it was before the windsock was
#  removed, with the windsocks filtered out of the object list (they were the last objects, so no id moves).
WINDSOCK_FREE_WORLDS = {
    (103, 3840, 1080, "flat", "sparse"): (109, "413ab855399d053e98027ceb315d0989d3a66c0bc0e857f05ccbe811ab2ee457",
                                                 "0c08d30dcc31c5e00443d7d28f8ade1c2185d3598149a7fb5d5e35dcbddf9ee6"),
    (112, 3840, 1080, "rolling", "none"): (94, "5712bd7810d8238317d16a4a4267afe49097762eaea6a46c8a96d2d49c9b93ae",
                                                 "20ce82f38ec686ff488e645cf749e9a872db80b4138cef1a43c588fb40cf0ded"),
    (13, 1280, 720, "flat", "dense"): (58, "b3ed39d9249b93c6aa70dd892fabbde1f56c7cb66f106838e85ecf6b43be5f6b",
                                                 "b324a8429e16495f107c073c7147d32a80cb338bc55013e33267eca54bc38459"),
    (130, 3840, 1080, "hilly", "normal"): (149, "2ca2b37c2b28786ad4df05d04c3cbc523d908f65360313df5c12eafb20204bc6",
                                                 "6b9c06d5099ee1aec8fedc5da1b64b888d6fb185530dc2700c867ab44f966711"),
    (139, 3840, 1080, "mountainous", "sparse"): (117, "c215d34bbe5e55083d1863b906e3cb6e3ab4ba330fac7cee0ba617cd3efa9e72",
                                                 "f2b1bc90b7a8caca5351f97bd8f9ada7cf4084118e04b8642083ab86e239292b"),
    (148, 6400, 1200, "flat", "none"): (158, "0d8d601085c99d179b5c9c216ec13b88e769542d8925ae2202215f8267a41639",
                                                 "8c5db29740af7ac6cd5a1d4ea73f40b2f52867fecffe878bf257ecd7e7564055"),
    (166, 6400, 1200, "rolling", "normal"): (248, "2abb691975ed2961c9c4f94be9e46915f61a5bcb391461f26886e06502b12616",
                                                 "a2ecf75b6fbea9786770e44be9edb93164b995970b3ca1e6ba2b4f1b9ca74f74"),
    (175, 6400, 1200, "hilly", "sparse"): (216, "a4d85085c3632a2f3438ddb34f296a9d7b0984330c16fae74a059894af13c1d3",
                                                 "a3b39873830134a792e4e7e25e8794e2d39883484f29fa356d9001bd4a515b3b"),
    (22, 1280, 720, "rolling", "normal"): (46, "18cfe624448530e23c4a7702e832fa8b46d628ee71779b786dfb95a417dedd84",
                                                 "b9bd0ff7a28ea9f65b414fd94e82e4c1f4f3639974bbf257acd357218bcb8cbd"),
    (31, 1280, 720, "hilly", "sparse"): (42, "bab1d8dae9cdfaf7de4ed296bacda4ffa9ef47fd2734cd34168e7db76d994171",
                                                 "d16dac2df8c456c4b975988767ebf56a549fe656bc279a3c4278ebb606573ca3"),
    (58, 1920, 900, "flat", "normal"): (63, "d08d799b58e68d0bfe007f24897983f51c8a04ded80b2c4ecf402198d99d71ab",
                                                 "1bcbc42acca7c3095c87b5a3bbf0569fdd693155e6922e6171231080a843bc7a"),
    (94, 1920, 900, "mountainous", "normal"): (59, "a7a40578393b75e6be9b79c2908ddea5c62c2be22d91101363941c1a72c9ceb7",
                                                 "e7c803380bdda42997eec98e8242e0e361a66a1ae0dd7f11ca411815229161a5"),
    (184, 6400, 1200, "mountainous", "none"): (158, "d1ef7b8cb39f3a2b9b46773c7c8418c16f9e8c200649d52b8171fcabeac31af8",
                                                 "7f88f195ea46c0c94a5ac0edf96d7518c31dcec0ebb8040e62f2f0aeefa66cee"),
}

# (seed, w, h, terrain, settlements, preset): the same for a whole Simulation, plus what is seeded after generate_world
# (the world's end state, the opening clouds, the streamlines): all of it must be exactly what it was.
# Phase O (clouds follow the day), the six cloud hashes (4th entry of each tuple) changed and ONLY those: every world opens
# at 7.2 h, when the cumulus / cumulonimbus weights are gated down and the sheets lifted, so the opening clouds have other
# TYPES (and so other widths and drift). Checked on the old and the new code: the number of opening clouds (13, 12, 12, 8,
# 7, 6), next_cloud_id, the world hash, the world_rng state hash and the streamlines hash are identical.
WINDSOCK_FREE_SIMS = {
    (3, 3840, 1080, "rolling", "normal", None): (142, "acb8291c72ddd3c13805a00a7f6f9571d9d72ee142586ea590e290dd2250bf85",
                                                      "9e1a7b7d74dbdf5e1068a1603f09fed059b9ff64ea53b17563fecdae8ffb7eca",
                                                      "f4561c9e8c1bc9b53b55c2dc4863f1e89722191898c7e379e9fdd873fef8ea07",
                                                      "0b22f911a8fe9f51c4ff13eb00d5b08868712fb406717c7c665e69a807a7400e"),
    (3, 3840, 1080, "rolling", "normal", "oklahoma"): (95, "06e596517d906e6f60047cd9b6dc741e7127bc352eb6fecde3de6583ff6e7e6e",
                                                      "dc53ab97d15abc7899ee22a08f37f7af2b09e5aea457323d049240e28ef15a18",
                                                      "4eeec76c3d0949e4247d576c6ee371328a9d803d835b4de0dff3fce6d3d9212f",
                                                      "4fd5ae8625fd816f75e74d7ecc4551edcb071a1389a23b3adb903fde2cff75b8"),
    (3, 3840, 1080, "rolling", "normal", "japan"): (131, "6e4cec29e2d582981bc741a496b71c5c6fece39fc27be115376125520c858cb0",
                                                      "1a636a1f33f0fdf4a270240fd4c90074723d16e2c8be5f4ef262422b8aeee371",
                                                      "664e18a87419070d0c1730ecea7aa7d4b4f1b0e6477df40b50bf0d854c3839e8",
                                                      "7cfb86ce22a4c36d79ce0b2ebede86c9a15fbf894b97548beaeea732807670be"),
    (3, 3840, 1080, "rolling", "normal", "antarctica"): (33, "d06d60ef7614f8a206bf5595d15a0a2da5b9660e98bb0cf2f2d7dbf893a898df",
                                                      "22cc2e396a77db7f4f201a45d794a2fed06e7a21f6c58b59457b8d3128169ba5",
                                                      "7a318222dabc0990d75e9add34b062554cf8d9db6116293ae9ffae5da44b8736",
                                                      "b4ba0d8b855e4d786d4efad139a58b44a2f7c43ac0f5387e2a5c69af6e5ce418"),
    (4, 2560, 1080, "mountainous", "sparse", None): (76, "9b2e2800fe0c34e026de4789973f9820324424ee7285ed7a4cfc7a02e6951018",
                                                      "5f5a6fda43a1e9ab84dc69d9435152b8e53240ef0478f758883ff28e9c83e5f6",
                                                      "e81cfa4d2561f1c5f3f06b6dc08baa00add6c9e5fa647e1cc5fed82283d2c09b",
                                                      "6d2a9f00fa6157d285e211462cd10b45957a5e206a67a048c387e1f34637877c"),
    (9, 1920, 900, "hilly", "dense", None): (76, "9573213086a043b71a2226b391c5dba65946bdf64a11f69ce401cc7a72d14ccc",
                                                      "16d1b16084e0c5726ddf41228f321ef744725698f605f35e1f57dd0019ad6fe6",
                                                      "44b52435583ce0b5bb69ec32c972b1cce4078adb01698ce3e92c193cafd0c651",
                                                      "787b48cc8243f26a0a9c90b7d909d26818a6d4e00e5c73b789e87f9392872fa2"),
}


def _sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


class TestWindsockRemoved(unittest.TestCase):
    """The windsock was taken out of the game (2026-10-10; scrap/windsock/SIM.md). Its draws from the seeded stream
    are still made, so every other part of every world is what it was: the objects, their ids (the windsocks were the
    last, nothing renumbers), the terrain, the settlements, and everything seeded after generate_world."""

    def test_a_new_world_has_no_windsock_and_is_otherwise_what_it_was(self):
        self.assertNotIn("windsock", objects.SCENERY_KINDS)
        self.assertNotIn("windsock", objects.SWAYERS)
        self.assertNotIn("windsock", objects.DAMAGE_VT)
        for (seed, w, h, kind, mode), (n, world_sha, rng_sha) in WINDSOCK_FREE_WORLDS.items():
            label = (seed, w, h, kind, mode)
            rng = random.Random(seed)
            t, st, objs = generate_world(rng, w, h, kind, mode)
            self.assertNotIn("windsock", {o["kind"] for o in objs}, label)
            self.assertEqual(len(objs), n, label)
            self.assertEqual([o["id"] for o in objs], list(range(1, n + 1)), label)
            blob = json.dumps({"y": t.y, "s": st, "o": objs}, sort_keys=True, separators=(",", ":"))
            self.assertEqual(_sha(blob), world_sha, label)
            self.assertEqual(_sha(repr(rng.getstate())), rng_sha, "the seeded stream ends where it did: " + str(label))

    def test_what_is_seeded_after_the_world_is_unchanged_plain_and_preset(self):
        """The clouds, the wind and the streamlines are drawn from the same stream after generate_world."""
        for (seed, w, h, kind, mode, pid), (n, world_sha, rng_sha, clouds_sha, streams_sha) in WINDSOCK_FREE_SIMS.items():
            label = (seed, w, h, kind, mode, pid)
            sim = Simulation(seed, w, h, 30, kind, mode, pid)
            objs = sim.objects.objs
            self.assertNotIn("windsock", {o["kind"] for o in objs}, label)
            self.assertEqual([o["id"] for o in objs], list(range(1, n + 1)), label)
            blob = json.dumps({"y": sim.terrain.y, "s": sim.settlements, "o": objs}, sort_keys=True, separators=(",", ":"))
            self.assertEqual(_sha(blob), world_sha, label)
            self.assertEqual(_sha(repr(sim.world_rng.getstate())), rng_sha, label)
            clouds = [(c.type, round(c.ax, 3), round(c.w, 3)) for c in sim.clouds]
            self.assertEqual(_sha(json.dumps(clouds, sort_keys=True, separators=(",", ":"))), clouds_sha, label)
            self.assertEqual(_sha(json.dumps(sim.streamlines, sort_keys=True, separators=(",", ":"))), streams_sha, label)


class TestPuddlesSettle(unittest.TestCase):
    """Puddles used to be a 20 px lens at (x, ground at x) wherever flat_spot put them, and rain grew them to 140 px
    from there: 166 of 1239 measured puddles floated over ground that fell away under them (up to 7 px), and 637 at
    the width rain grew them to (up to 194 px). Now a puddle comes to rest at the lowest point of its footprint and
    only grows as wide as its hollow holds (objects.settle_puddle, puddle_cap). Tolerance PUDDLE_TOL (1 px; the
    viewer draws the lens centred 1 px below its y) plus 0.1 for the one-decimal rounding of x and y on the wire."""
    SLACK = PUDDLE_TOL + 0.1

    @staticmethod
    def terrain(f, w=1280, h=720, step=8):
        """A heightmap y = f(x) (screen y: larger is lower), n samples like a generated one."""
        return Terrain(w, h, "flat", [float(f(i * step)) for i in range(w // step + 1)], step)

    @staticmethod
    def valley(x):
        """Ground that is 128 px lower at x = 640 than at 320 and 960 (a 0.4 slope), level beyond."""
        return 400.0 + 0.4 * (320.0 - abs(x - 640.0)) if abs(x - 640.0) < 320 else 400.0

    @staticmethod
    def gap(terr, o, w=None):
        """How far the ground under the puddle's footprint falls below its y, worst point (px)."""
        hw = (o["w"] if w is None else w) / 2.0
        a, b = max(0.0, o["x"] - hw), min(float(terr.w), o["x"] + hw)
        xs = [a, b] + [i * terr.step for i in range(terr.n) if a < i * terr.step < b]
        return max(0.0, max(terr.height_at(x) for x in xs) - o["y"])

    @staticmethod
    def unsettled(key):
        """The same world as before the puddles settled: every puddle where flat_spot put it (the old behaviour)."""
        from unittest import mock
        with mock.patch.object(objects, "puddle_rests", return_value=True):
            return generate_world(random.Random(key[0]), *key[1:])

    # -- (a) level ground: nothing happens --------------------------------------------------------
    def test_a_puddle_on_level_ground_keeps_its_exact_x_and_y(self):
        t = self.terrain(lambda x: 500.0)
        for x in (20.0, 123.456, 640.0, 1259.9):
            self.assertEqual(settle_puddle(t, x), (x, 500.0))
        t = self.terrain(lambda x: 500.0 + 0.02 * x)          # a gentle 2 % slope: 0.4 px across the lens, still level
        self.assertEqual(settle_puddle(t, 300.0), (300.0, t.height_at(300.0)))

    def test_puddles_that_were_on_level_ground_in_a_generated_world_are_exactly_where_they_were(self):
        kept = moved = 0
        for label, _st, objs in TestGroundSpots.worlds():
            terr, _s, _o = generate_world(random.Random(label[0]), *label[1:])
            _t, _s, before = self.unsettled(label)
            old = [o for o in before if o["kind"] == "puddle"]
            new = [o for o in objs if o["kind"] == "puddle"]
            self.assertEqual(len(old), len(new), label)
            for a, b in zip(old, new):
                if puddle_rests(terr, a["x"]):
                    kept += 1
                    self.assertEqual((b["x"], b["y"]), (a["x"], a["y"]), label)
                else:
                    moved += 1
        self.assertGreater(kept, 150, "most puddles are on level ground")
        self.assertGreater(moved, 5, "and some are not: the test must see both")

    # -- (b) a slope: down to the lowest point ----------------------------------------------------
    def test_a_puddle_on_a_slope_runs_down_to_the_lowest_point(self):
        valley = self.terrain(self.valley)          # (y = 528 at the bottom, x = 640)
        for x0 in (400.0, 500.0, 777.7, 900.0):
            self.assertFalse(puddle_rests(valley, x0))
            x, y = settle_puddle(valley, x0)
            self.assertGreaterEqual(y, valley.height_at(x0), "never higher than where it started")
            self.assertAlmostEqual(x, 640.0, delta=0.6, msg=x0)
            self.assertEqual(y, valley.height_at(x))
            self.assertLessEqual(self.gap(valley, {"x": x, "y": y, "w": 20.0}), PUDDLE_TOL, "its footprint rests on the ground")
        # it stops at the foot of the slope, where the ground is level again
        ramp = self.terrain(lambda x: 520.0 - 0.3 * max(0.0, 400.0 - x))      # down from x = 0 to a level floor at x = 400
        x, y = settle_puddle(ramp, 100.0)
        self.assertGreaterEqual(x, 390.0)
        self.assertAlmostEqual(y, 520.0, delta=1.5)

    def test_a_walk_stops_short_of_a_building_a_pond_the_world_and_another_puddle(self):
        valley = self.terrain(lambda x: 400.0 + 0.2 * (320.0 - abs(x - 640.0)) if abs(x - 640.0) < 320 else 400.0)
        built = (objects._merge([(600.0, 700.0)]),)                 # a building over the bottom of the valley
        x, y = settle_puddle(valley, 400.0, built)
        self.assertLessEqual(x + 14.0, 600.0 + 1e-9, "stops a margin short of the building")
        self.assertGreater(x, 560.0, "but still ran down as far as it could")
        self.assertLessEqual(self.gap(valley, {"x": x, "y": y, "w": 20.0}), PUDDLE_TOL, "and rests on the ground there")
        self.assertTrue(objects._clear(built, x, 14.0))
        taken = objects._merge([(630.0, 650.0)])                    # another puddle already in the hollow
        x, y = settle_puddle(valley, 400.0, (), taken)
        self.assertGreaterEqual(abs(x - 640.0), 24.0 - 1e-9, "not on top of it")
        edge = self.terrain(lambda x: 400.0 + 0.2 * (1280.0 - x))    # runs down to the world's edge
        x, y = settle_puddle(edge, 1000.0)
        self.assertLessEqual(x, 1260.0, "not past 20 px from the edge")

    # -- (c) an edge: forced down onto the ground -------------------------------------------------
    def test_a_puddle_on_an_edge_drops_onto_the_ground_below(self):
        ledge = self.terrain(lambda x: 400.0 if x <= 520 else 450.0)   # a 50 px drop between x = 520 and 528
        for x0 in (505.0, 512.0, 519.0, 524.0, 527.0):
            x, y = settle_puddle(ledge, x0)
            self.assertLessEqual(self.gap(ledge, {"x": x, "y": y, "w": 20.0}), PUDDLE_TOL, (x0, x, y))
            self.assertGreaterEqual(y, ledge.height_at(x0), "down, never up")
        # a small drop whose far side may not be stood on (a building at the foot of it): forced down onto it
        small = self.terrain(lambda x: 400.0 if x <= 520 else 402.5)
        built = (objects._merge([(541.0, 700.0)]),)
        x, y = settle_puddle(small, 520.0, built)
        self.assertLessEqual(self.gap(small, {"x": x, "y": y, "w": 20.0}), PUDDLE_TOL)
        # a big drop the puddle cannot follow is not buried in the ground: it goes to level ground nearby instead
        x, y = settle_puddle(ledge, 520.0, built)
        self.assertLessEqual(self.gap(ledge, {"x": x, "y": y, "w": 20.0}), PUDDLE_TOL)
        self.assertAlmostEqual(y, ledge.height_at(x), delta=objects.PUDDLE_FORCE_MAX)

    # -- (d) where a settled puddle may be --------------------------------------------------------
    def test_no_settled_puddle_is_in_a_building_a_pond_or_outside_the_world(self):
        n = 0
        for label, _st, objs in TestGroundSpots.worlds():
            w = label[1]
            built = _spans(objs, BUILDING_KINDS)
            ponds = _spans(objs, ("pond",))
            _t, _s, before = self.unsettled(label)
            old = [o for o in before if o["kind"] == "puddle"]
            pud = [o for o in objs if o["kind"] == "puddle"]
            for o in pud:
                n += 1
                self.assertTrue(20.0 <= o["x"] <= w - 20.0, (label, o["x"]))
                self.assertFalse(_overlaps(o, built, objects.SPOT_MARGIN["puddle"]), f"puddle by a building {label} x={o['x']}")
                self.assertFalse(_overlaps(o, ponds, objects.SPOT_MARGIN["puddle"]), f"puddle by a pond {label} x={o['x']}")
            terr, _s, _o = generate_world(random.Random(label[0]), *label[1:])
            for a, b in zip(old, pud):
                if not puddle_rests(terr, a["x"]):        # one that had to settle: never onto another puddle
                    for q in pud:
                        if q is not b:
                            self.assertGreaterEqual(abs(q["x"] - b["x"]), 24.0 - 0.1, (label, b["x"], q["x"]))
        self.assertGreater(n, 300)

    # -- (e) nothing else in the world moves ------------------------------------------------------
    def test_only_the_puddles_x_and_y_differ_from_before_and_the_stream_is_the_same(self):
        from unittest import mock
        changed = 0
        for label, _st, objs in TestGroundSpots.worlds():
            rng_a, rng_b = random.Random(label[0]), random.Random(label[0])
            t_a, st_a, new = generate_world(rng_a, *label[1:])
            with mock.patch.object(objects, "puddle_rests", return_value=True):
                t_b, st_b, old = generate_world(rng_b, *label[1:])
            self.assertEqual(t_a.y, t_b.y, label)
            self.assertEqual(st_a, st_b, label)
            self.assertEqual(rng_a.getstate(), rng_b.getstate(), "no draw from the seeded stream")
            self.assertEqual(len(new), len(old), label)
            for a, b in zip(new, old):
                self.assertEqual(a["id"], b["id"])
                if a["kind"] != "puddle":
                    self.assertEqual(a, b, label)
                else:
                    changed += (a["x"], a["y"]) != (b["x"], b["y"])
                    self.assertEqual({k: v for k, v in a.items() if k not in ("x", "y")},
                                     {k: v for k, v in b.items() if k not in ("x", "y")}, label)
        self.assertGreater(changed, 5)

    # -- (f) at the size rain grows them to ---------------------------------------------------------
    def test_no_puddle_floats_at_the_size_a_long_rain_grows_it_to(self):
        worlds = [(label, generate_world(random.Random(label[0]), *label[1:])[0], objs)
                  for label, _st, objs in TestGroundSpots.worlds()]
        for pid in ("oklahoma", "japan", "sahara", "antarctica", "tibet", "washington"):
            sim = Simulation(3, 3840, 1080, 30, "rolling", "normal", pid)
            worlds.append((pid, sim.terrain, [dict(o) for o in sim.objects.objs]))
        grown = capped = 0
        for label, terr, objs in worlds:
            objs = [dict(o) for o in objs]
            field = ObjectField(objs, terr)
            hits = {k: 6 for k in range(int(terr.w / GROUND_STEP) + 1)}
            for _ in range(450):
                field.update(DT, FlatWind(), hits, {}, 900.0)
            for o in objs:
                if o["kind"] != "puddle":
                    continue
                grown += o["w"] > 20.0
                capped += o["w"] < PUDDLE_MAX_W
                self.assertLessEqual(self.gap(terr, o), self.SLACK, (label, o["x"], o["w"]))
                self.assertGreaterEqual(o["x"] - o["w"] / 2.0, -0.05, (label, "stays inside the world"))
                self.assertLessEqual(o["x"] + o["w"] / 2.0, terr.w + 0.05, (label, "stays inside the world"))
        self.assertGreater(grown, 300, "rain does grow puddles")
        self.assertGreater(capped, 100, "and a hollow limits some of them")

    def test_a_puddle_grows_as_wide_as_its_hollow_and_a_level_floor_is_not_limited(self):
        flat = self.terrain(lambda x: 500.0)
        self.assertEqual(puddle_cap(flat, 640.0, 500.0), PUDDLE_MAX_W, "flat ground: growth as it always was")
        hump = self.terrain(lambda x: 400.0 + 0.5 * max(0.0, abs(x - 640.0) - 40.0))     # level for 80 px, then falls away
        cap = puddle_cap(hump, 640.0, 400.0)
        self.assertGreater(cap, 70.0)
        self.assertLess(cap, 100.0)
        o = {"x": 640.0, "y": 400.0, "w": cap}
        self.assertLessEqual(self.gap(hump, o), PUDDLE_TOL + 1e-6)
        self.assertGreater(self.gap(hump, o, cap + 4.0), PUDDLE_TOL, "a little wider and it would hang over the slope")
        valley = self.terrain(self.valley)                  # the other way: the ground climbs a bank on both sides
        self.assertEqual(puddle_cap(valley, 640.0, valley.height_at(640.0)), 20.0, "no room to grow up a 0.4 bank")
        self.assertEqual(puddle_cap(flat, 30.0, 500.0), 60.0, "not past the world's edge")
        self.assertEqual(puddle_cap(hump, 300.0, 100.0), 20.0, "never below the size it starts at")

    def test_a_puddle_wider_than_its_hollow_from_an_old_save_does_not_snap_back(self):
        valley = self.terrain(self.valley)
        o = objects._obj("puddle", 640.0, valley.height_at(640.0), 140.0, 3.0, 1)
        o["id"] = 1
        field = ObjectField([o], valley)
        self.assertEqual(field.puddle_cap[0], 20.0)
        field.update(DT, FlatWind(), {int(640 / GROUND_STEP + 0.5): 5}, {}, 900.0)
        self.assertEqual(o["w"], 140.0)

    def test_the_cap_comes_from_the_terrain_so_a_save_needs_nothing_new(self):
        sim = Simulation(seed=3, w=3840, h=1080, fps=30, terrain_kind="hilly")
        self.assertTrue(sim.objects.puddle_cap)
        state = json.loads(json.dumps(sim.to_state()))
        self.assertNotIn("puddle_cap", state["objects"])
        again = Simulation.from_state(state)
        self.assertEqual(again.objects.puddle_cap, sim.objects.puddle_cap)


class TestBiomeObjects(unittest.TestCase):
    def biome(self, **kw):
        b = {"trees": 1.0, "tree_mix": {"pine": 1.0}, "high_tree": "pine", "tree_line": 0.27}
        b.update(kw)
        return b

    def plan(self, **kw):
        p = {"named": [], "max": "city", "density": 0.5, "style": None, "ponds": 1.0, "lone": 1.0}
        p.update(kw)
        return p

    def test_tree_density_and_variants_follow_the_biome(self):
        base = None
        for trees, mix in ((1.0, {"palm": 1.0}), (0.0, {"palm": 1.0}), (0.1, {"cactus": 1.0}), (1.0, {"dead": 1.0})):
            _t, _s, objs = generate_world(random.Random(3), 3840, 1080, "rolling", "normal",
                                          biome=self.biome(trees=trees, tree_mix=mix, high_tree=list(mix)[0]),
                                          plan=self.plan())
            ts = [o for o in objs if o["kind"] == "tree"]
            if trees == 0.0:
                self.assertEqual(ts, [])
            else:
                self.assertTrue(ts)
                self.assertEqual({o["variant"] for o in ts} - {"bush"}, set(mix), "high ground may keep a few bushes")
            if trees == 1.0 and "palm" in mix:
                base = len(ts)
            if trees == 0.1:
                self.assertLessEqual(len(ts), 0.15 * base + 1)
        sizes = {}
        for v in ("palm", "cactus", "acacia", "shrub", "dead"):
            _t, _s, objs = generate_world(random.Random(5), 3840, 1080, "rolling", "normal",
                                          biome=self.biome(tree_mix={v: 1.0}, high_tree=v), plan=self.plan())
            sizes[v] = [(o["w"], o["h"]) for o in objs if o["kind"] == "tree"]
            self.assertTrue(sizes[v], v)
        self.assertGreater(min(h for _w, h in sizes["palm"]), 80)
        self.assertGreater(min(w for w, _h in sizes["acacia"]), 48)
        self.assertLess(max(h for _w, h in sizes["shrub"]), 23)
        self.assertLess(max(w for w, _h in sizes["cactus"]), 25)
        for v, vs in sizes.items():
            for w, h in vs:
                self.assertTrue(w > 0 and h > 0)

    def test_stable_seeds_for_the_new_variants(self):
        def ids(seed):
            _t, _s, objs = generate_world(random.Random(seed), 3840, 1080, "rolling", "normal",
                                          biome=self.biome(tree_mix={"palm": 1, "acacia": 1, "dead": 1}),
                                          plan=self.plan())
            return json.dumps(objs, sort_keys=True)
        self.assertEqual(ids(8), ids(8))
        self.assertNotEqual(ids(8), ids(9))

    def test_apply_snow_start_covers_the_cold_high_ground_and_what_stands_on_it(self):
        from objects import apply_snow_start
        terr, objs, _f = field_for(4)
        ground = GroundCover(terr, 3840, 6)
        fy = (terr.ground_y + min(terr.y)) / 2.0                 # the freezing level halfway up the relief
        n = apply_snow_start(objs, ground, fy, 0.8)
        self.assertTrue(0 < n < ground.n)
        for y, snow in zip(ground.surf, ground.snow):
            self.assertEqual(snow > 0, y <= fy)
            self.assertIn(snow, (0.0, 0.8))
        for o in objs:
            if o["y"] <= fy and o["kind"] in ("tree", "house", "apartment", "office", "skyscraper", "church"):
                self.assertAlmostEqual(o["snow"], 0.64)
            elif o["y"] > fy and o["kind"] not in ("snowpack",):
                self.assertEqual(o["snow"], 0.0)
        self.assertTrue(ground.force, "the ground message is sent on the first frame")
        field = ObjectField(objs)
        self.assertTrue(field.active, "the white objects are tracked so that they melt")
        # nothing at all when the start has no snow
        terr2, objs2, _f2 = field_for(4)
        g2 = GroundCover(terr2, 3840, 6)
        self.assertEqual(apply_snow_start(objs2, g2, fy, 0.0), 0)
        self.assertEqual(max(g2.snow), 0.0)
        self.assertFalse(g2.force)
        # a freezing level above every peak = a frozen world: all of it is white
        g3 = GroundCover(terr2, 3840, 6)
        self.assertEqual(apply_snow_start(objs2, g3, 0.0 + terr2.ground_y + 10, 1.0), g3.n)

    def test_a_world_without_a_biome_never_makes_the_new_variants(self):
        for seed in range(1, 6):
            _t, _s, objs = generate_world(random.Random(seed), 3840, 1080, "hilly", "normal")
            self.assertTrue({o.get("variant") for o in objs if o["kind"] == "tree"} <= {"pine", "oak", "birch", "bush"})
            self.assertTrue({o.get("variant") for o in objs if o["kind"] == "house"}
                            <= {"cottage", "cabin", "farmhouse", "townhouse", "bungalow", "manor"})


if __name__ == "__main__":
    unittest.main()
