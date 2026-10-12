"""Settlements and buildings: a village and a city are guaranteed at default size, buildings stand
on flattened ground (not on slopes, not in ponds), rows never overlap, cities have a tall dense core,
villages are low houses round a church, trees thin out in towns and above the tree line."""
import random
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_settlements.py` (or sim.tests.test_settlements) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
from objects import generate_world
from settlements import plan_counts

SEEDS = range(1, 21)


def world(seed, kind="rolling", w=3840, h=1080, mode="normal"):
    return generate_world(random.Random(seed), w, h, kind, mode)


def buildings(objs):
    return [o for o in objs if o["kind"] in S.BUILDING_KINDS]


def span(o):
    return o["x"] - o["w"] / 2.0, o["x"] + o["w"] / 2.0


class TestSettlements(unittest.TestCase):
    def test_village_and_city_guaranteed_at_default_size_across_20_seeds(self):
        for seed in SEEDS:
            for kind in ("rolling", "mountainous"):
                _t, setts, _o = world(seed, kind)
                kinds = {s["kind"] for s in setts}
                self.assertIn("village", kinds, f"seed {seed} {kind}: no village")
                self.assertIn("city", kinds, f"seed {seed} {kind}: no city")

    def test_guarantee_holds_from_2560_wide(self):
        for seed in SEEDS:
            _t, setts, _o = world(seed, "hilly", w=2560, h=900)
            kinds = {s["kind"] for s in setts}
            self.assertTrue({"village", "city"} <= kinds, f"seed {seed}: {kinds}")

    def test_settlements_none_has_none_and_no_buildings(self):
        for seed in (1, 2, 3):
            _t, setts, objs = world(seed, mode="none")
            self.assertEqual(setts, [])
            self.assertEqual(buildings(objs), [])

    def test_sparse_still_has_a_village_dense_has_more(self):
        for seed in (1, 2, 3, 4):
            _t, sp, _o = world(seed, mode="sparse")
            _t, de, _o = world(seed, mode="dense")
            self.assertIn("village", {s["kind"] for s in sp})
            self.assertGreaterEqual(len(de), 3)
            self.assertGreaterEqual(len(de), len(sp))
        self.assertGreaterEqual(plan_counts("dense", 3840)["village"], plan_counts("normal", 3840)["village"])
        self.assertGreater(plan_counts("normal", 9600)["city"], plan_counts("normal", 3840)["city"] - 1)

    def test_settlement_records(self):
        for seed in (1, 2, 3):
            _t, setts, objs = world(seed)
            names = [s["name"] for s in setts]
            self.assertEqual(len(names), len(set(names)), "names are unique")
            spans = sorted((s["x0"], s["x1"]) for s in setts)
            for (a0, a1), (b0, b1) in zip(spans, spans[1:]):
                self.assertLess(a1, b0, "settlement spans do not overlap")
            for s in setts:
                inside = [o for o in objs if o.get("settlement") == s["id"]]
                self.assertTrue(inside)
                for o in inside:
                    l, r = span(o)
                    self.assertGreaterEqual(l, s["x0"] - 1.0)
                    self.assertLessEqual(r, s["x1"] + 1.0)

    def test_buildings_stand_on_the_surface_and_flat_ground(self):
        for seed in (1, 2, 3, 4, 5):
            for kind in ("hilly", "mountainous"):
                terr, _s, objs = world(seed, kind)
                for o in buildings(objs):
                    l, r = span(o)
                    self.assertAlmostEqual(o["y"], terr.height_at(o["x"]), delta=0.11)
                    # footprint (minus a rounding margin) lies on a level plateau
                    ys = [terr.height_at(x) for x in (l + 1, o["x"], r - 1)]
                    self.assertLess(max(ys) - min(ys), 1.0, f"{o['kind']} on a slope ({kind} seed {seed})")
                    self.assertLess(abs(terr.slope_at(o["x"])), 0.05)

    def test_nothing_is_built_in_a_pond(self):
        for seed in SEEDS:
            _t, _s, objs = world(seed, "hilly")
            ponds = [span(o) for o in objs if o["kind"] == "pond"]
            self.assertTrue(ponds, "every world has at least one pond")
            for b in buildings(objs):
                l, r = span(b)
                for p0, p1 in ponds:
                    self.assertFalse(l < p1 and r > p0, f"{b['kind']} overlaps a pond (seed {seed})")

    def test_ponds_sit_in_local_minima(self):
        for seed in (1, 2, 3):
            terr, _s, objs = world(seed, "hilly")
            for o in objs:
                if o["kind"] != "pond":
                    continue
                # the pond floor is no higher than anything within 130 px of it
                near = [terr.height_at(o["x"] + d) for d in range(-130, 131, 10)]
                self.assertGreaterEqual(terr.height_at(o["x"]), max(near) - 6.0)

    def test_buildings_in_a_row_do_not_overlap(self):
        for seed in SEEDS:
            _t, _s, objs = world(seed, "rolling", mode="dense")
            rows = {}
            for b in buildings(objs):
                rows.setdefault((b.get("settlement"), b.get("layer")), []).append(b)
            lone = rows.pop((None, 0), [])
            for key, row in list(rows.items()) + [(("lone", 0), lone)]:
                row = sorted(row, key=lambda o: o["x"])
                for a, b in zip(row, row[1:]):
                    self.assertLessEqual(span(a)[1], span(b)[0] + 0.25,
                                         f"{a['kind']} / {b['kind']} overlap in row {key}, seed {seed}")

    def test_city_has_a_tall_dense_core_with_a_back_row(self):
        for seed in SEEDS:
            _t, setts, objs = world(seed)
            city = next(s for s in setts if s["kind"] == "city")
            inside = [o for o in objs if o.get("settlement") == city["id"]]
            front = [o for o in inside if o["layer"] == 0]
            back = [o for o in inside if o["layer"] == 1]
            towers = [o for o in front if o["kind"] in ("office", "skyscraper")]
            self.assertGreaterEqual(len(towers), 4, f"seed {seed}: no dense core")
            self.assertGreaterEqual(len(back), 3, f"seed {seed}: no second row")
            self.assertTrue(all(o["layer"] in (0, 1) for o in inside))
            tallest = max(towers, key=lambda o: o["h"])
            third = (city["x1"] - city["x0"]) / 3.0
            self.assertTrue(city["x0"] + third <= tallest["x"] <= city["x1"] - third,
                            f"seed {seed}: tallest tower not in the middle third")
            self.assertGreaterEqual(len({round(o["h"]) for o in towers}), 4, "heights vary")
            self.assertGreaterEqual(tallest["h"], 180)
            width = city["x1"] - city["x0"]
            ends = [o for o in front if o["x"] < city["x0"] + 0.12 * width or o["x"] > city["x1"] - 0.12 * width]
            self.assertTrue(all(o["h"] < tallest["h"] for o in ends))
            for o in towers:
                self.assertGreaterEqual(o["floors"], 6)

    def test_village_is_low_houses_round_a_church(self):
        for seed in SEEDS:
            _t, setts, objs = world(seed)
            for v in (s for s in setts if s["kind"] == "village"):
                inside = [o for o in objs if o.get("settlement") == v["id"]]
                kinds = {o["kind"] for o in inside}
                self.assertIn("church", kinds)
                self.assertIn("house", kinds)
                self.assertFalse(kinds & {"skyscraper", "office", "apartment"})
                church = next(o for o in inside if o["kind"] == "church")
                mid = (v["x0"] + v["x1"]) / 2.0
                self.assertLess(abs(church["x"] - mid), 0.3 * (v["x1"] - v["x0"]), "church near the centre")
                self.assertTrue(kinds & {"barn", "windmill"}, "barns/windmill at the edge")
                width = v["x1"] - v["x0"]
                for o in inside:
                    if o["kind"] in ("barn", "windmill"):
                        self.assertTrue(o["x"] < v["x0"] + 0.3 * width or o["x"] > v["x1"] - 0.3 * width)

    def test_lone_farmhouses_and_cabins_exist_between_settlements(self):
        _t, setts, objs = world(7)
        lone = [o for o in buildings(objs) if "settlement" not in o]
        self.assertGreaterEqual(len(lone), 3)
        self.assertTrue({o["variant"] for o in lone if o["kind"] == "house"} & {"farmhouse", "cabin"})
        for o in lone:
            for s in setts:
                self.assertFalse(s["x0"] < o["x"] < s["x1"], "lone building inside a settlement")

    def test_ids_and_layout_are_stable_per_seed(self):
        a = world(11)[2]
        b = world(11)[2]
        self.assertEqual(a, b)
        c = world(12)[2]
        self.assertNotEqual([o["x"] for o in a], [o["x"] for o in c])

    def test_trees_thin_out_in_towns_and_stop_at_the_tree_line(self):
        for seed in (1, 2, 3, 4):
            terr, setts, objs = world(seed, "mountainous", mode="dense")
            trees = [o for o in objs if o["kind"] == "tree"]
            self.assertGreater(len(trees), 30, "more trees than v1")
            for t in trees:
                self.assertLessEqual(terr.ground_y - t["y"], 0.27 * 1080 + 1, "tree above the tree line")
            in_town = sum(1 for t in trees if any(s["x0"] - 10 <= t["x"] <= s["x1"] + 10 for s in setts))
            town_len = sum(s["x1"] - s["x0"] + 20 for s in setts)
            dens_in = in_town / max(town_len, 1)
            dens_out = (len(trees) - in_town) / max(3840 - town_len, 1)
            self.assertLess(dens_in, dens_out, "trees thin out inside towns")

    def test_world_scales_with_width(self):
        small = world(3, w=1280, h=720)[2]
        big = world(3, w=9600, h=1400)[2]

        def count(objs, k):
            return sum(1 for o in objs if o["kind"] == k)

        self.assertGreater(count(big, "tree"), 3 * count(small, "tree"))
        self.assertGreater(len(buildings(big)), len(buildings(small)))


class TestBiomePlan(unittest.TestCase):
    """v2.3 preset biomes: named settlements on landmarks, a style for the buildings, caps and densities."""

    @staticmethod
    def flat(seed=1, w=3840, h=1080):
        from terrain import Terrain
        return Terrain.generate(random.Random(seed), w, h, "flat")

    @staticmethod
    def plan(named, **kw):
        base = {"named": named, "max": "city", "density": 0.0, "style": None, "ponds": 0.0, "lone": 0.0}
        base.update(kw)
        return base

    @staticmethod
    def spec(x, kind, name, style=None, min_kind="hamlet", top=False):
        return {"x": float(x), "kind": kind, "min": min_kind, "name": name, "style": style, "landmark": name,
                "top": top}

    def test_a_named_settlement_takes_the_name_the_kind_and_the_spot_of_its_landmark(self):
        import settlements as ST
        t = self.flat()
        plan = self.plan([self.spec(1000, "city", "Metropolis"), self.spec(2900, "town", "Smallville")])
        sts, blds, occ, ponds = ST.plan_settlements(random.Random(4), t, "normal", plan)
        by = {s["name"]: s for s in sts}
        self.assertEqual(set(by), {"Metropolis", "Smallville"})
        self.assertEqual(by["Metropolis"]["kind"], "city")
        self.assertEqual(by["Smallville"]["kind"], "town")
        for name, x in (("Metropolis", 1000), ("Smallville", 2900)):
            s = by[name]
            self.assertLess(abs((s["x0"] + s["x1"]) / 2 - x), 1.0 + (s["x1"] - s["x0"]) / 2)
        self.assertEqual([s["id"] for s in sts], [1, 2])
        self.assertTrue(all(b["settlement"] in (1, 2) for b in blds))
        self.assertIsNone(ST._STYLE, "the style is back to None after planning")

    def test_a_town_that_does_not_fit_shrinks_to_the_kind_that_does_or_is_left_out(self):
        import settlements as ST
        t = self.flat()
        t.summits = [(700.0, 1100.0)]                # a summit beside the wish
        t.protected = list(t.summits)
        near = self.plan([self.spec(1250, "city", "Wedged", min_kind="hamlet")])
        sts, *_ = ST.plan_settlements(random.Random(2), self.flat(), "normal", near)
        self.assertEqual(sts[0]["kind"], "city")
        t2 = self.flat()
        t2.summits = [(500.0, 3300.0)]               # no room anywhere near
        t2.protected = list(t2.summits)
        sts, *_ = ST.plan_settlements(random.Random(2), t2, "normal",
                                      self.plan([self.spec(1900, "city", "Nowhere", min_kind="hamlet", top=True)]))
        self.assertEqual(sts, [], "a place that cannot be placed near its landmark is not placed at all")
        # a minimum kind: a city that would only fit as a hamlet is left out when the floor is a town
        sts, *_ = ST.plan_settlements(random.Random(2), t2, "normal",
                                      self.plan([self.spec(3600, "city", "Edge", min_kind="town", top=True)]))
        self.assertEqual(sts, [])

    def test_a_town_may_stand_on_its_own_summit_unless_it_is_the_highest_point(self):
        import settlements as ST
        for top, expect_inside in ((False, True), (True, False)):
            t = self.flat()
            t.summits = [(1900.0, 2140.0)]
            t.protected = list(t.summits)
            sts, *_ = ST.plan_settlements(random.Random(3), t, "normal",
                                          self.plan([self.spec(2020, "town", "Plateau", top=top)]))
            if sts:
                s = sts[0]
                inside = s["x0"] < 2140.0 and s["x1"] > 1900.0
                self.assertEqual(inside, expect_inside, (top, s))
            else:
                self.assertFalse(expect_inside)

    def test_styles_dress_the_buildings(self):
        import settlements as ST
        expect = {"adobe": ({"adobe"}, {"church", "barn", "windmill", "apartment", "office", "skyscraper", "factory"}),
                  "hut": ({"hut"}, set()), "station": ({"station"}, {"church", "barn", "windmill", "shop"})}
        for style, (variants, banned) in expect.items():
            for seed in (1, 2, 3):
                t = self.flat(seed)
                kinds = ("town",) if style != "station" else ("hamlet",)
                plan = self.plan([self.spec(1900, kinds[0], "Place", style=style)])
                sts, blds, *_ = ST.plan_settlements(random.Random(seed), t, "normal", plan)
                self.assertTrue(blds)
                houses = [b for b in blds if b["kind"] == "house"]
                self.assertTrue(houses)
                self.assertTrue(all(b["kind"] not in banned for b in blds), (style, {b["kind"] for b in blds}))
                if style == "hut":
                    self.assertTrue(any(b.get("variant") == "hut" for b in houses))
                else:
                    self.assertTrue(all(b["variant"] in variants for b in houses), (style, {b["variant"] for b in houses}))
                for b in blds:
                    self.assertTrue(len(b["wall"]) == 3 and b["w"] > 0 and b["h"] > 0)
        self.assertIsNone(ST._STYLE)
        # a plain building stays plain
        b = ST.make_building(random.Random(1), "church")
        self.assertEqual(b["kind"], "church")

    def test_cap_density_and_none_mode(self):
        import settlements as ST
        t = self.flat()
        sts, *_ = ST.plan_settlements(random.Random(5), t, "normal", self.plan([], density=1.0, max="village"))
        self.assertTrue(sts)
        self.assertTrue(all(s["kind"] in ("village", "hamlet") for s in sts), [s["kind"] for s in sts])
        sts, *_ = ST.plan_settlements(random.Random(5), self.flat(), "normal", self.plan([], density=0.0))
        self.assertEqual(sts, [])
        sts, *_ = ST.plan_settlements(random.Random(5), self.flat(), "none",
                                      self.plan([self.spec(1900, "city", "Ghost")], density=1.0))
        self.assertEqual(sts, [], "--settlements none builds nothing, not even a named town")
        # a dense world gets more of the unnamed ones than a sparse one
        d = len(ST.plan_settlements(random.Random(5), self.flat(), "dense", self.plan([], density=1.0))[0])
        sp = len(ST.plan_settlements(random.Random(5), self.flat(), "sparse", self.plan([], density=1.0))[0])
        self.assertGreater(d, sp)

    def test_ponds_follow_the_plan(self):
        import settlements as ST
        for rarity, lo, hi in ((0.0, 0, 0), (1.0, 2, 4), (0.3, 0, 1)):
            t = self.flat()
            _s, _b, _o, ponds = ST.plan_settlements(random.Random(6), t, "normal", self.plan([], ponds=rarity))
            self.assertTrue(lo <= len(ponds) <= hi, (rarity, len(ponds)))

    def test_lone_buildings_follow_the_plan(self):
        import settlements as ST
        t = self.flat()
        _s, _b, occ, _p = ST.plan_settlements(random.Random(7), t, "normal", self.plan([]))
        none = ST.plan_lone_buildings(random.Random(7), t, occ, "normal", self.plan([], lone=0.0))
        self.assertEqual(none, [])
        some = ST.plan_lone_buildings(random.Random(7), t, occ, "normal", self.plan([], lone=1.0, style="adobe"))
        self.assertTrue(some)
        self.assertTrue(all(b["variant"] == "adobe" for b in some if b["kind"] == "house"))
        self.assertTrue(all(b["kind"] in ("house",) for b in some), {b["kind"] for b in some})
        self.assertIsNone(ST._STYLE)

    def test_the_new_house_variants_are_well_formed(self):
        import settlements as ST
        rng = random.Random(9)
        for v in ("adobe", "hut", "station"):
            self.assertIn(v, ST.HOUSE_VARIANTS)
            for _ in range(20):
                b = ST.make_building(rng, "house", v)
                self.assertEqual(b["variant"], v)
                self.assertTrue(30 <= b["w"] <= 100 and 28 <= b["h"] <= 55 and b["floors"] >= 1)
                self.assertTrue(all(0 <= c <= 255 for c in b["wall"] + b["roof"]))


class TestBiomePolish(unittest.TestCase):
    @staticmethod
    def flat(seed=1):
        from terrain import Terrain
        return Terrain.generate(random.Random(seed), 3840, 1080, "flat")

    def test_avoided_kinds_become_houses_and_water_towers(self):
        import settlements as ST
        plan = {"named": [{"x": 1900.0, "kind": "town", "min": "village", "name": "Place", "style": None,
                           "landmark": "Place", "top": False}],
                "max": "city", "density": 1.0, "style": None, "ponds": 0.0, "lone": 1.0,
                "avoid": ("barn", "windmill", "church")}
        for seed in range(1, 6):
            sts, blds, occ, _p = ST.plan_settlements(random.Random(seed), self.flat(seed), "normal", plan)
            lone = ST.plan_lone_buildings(random.Random(seed), self.flat(seed), occ, "normal", plan)
            kinds = {b["kind"] for b in blds + lone}
            self.assertFalse(kinds & {"barn", "windmill", "church"}, kinds)
            self.assertIn("house", kinds)
        self.assertEqual(ST._AVOID, frozenset(), "the avoid set is cleared after planning")
        self.assertEqual(ST.make_building(random.Random(1), "windmill")["kind"], "windmill")
        # with a style the substitute house wears it
        plan["style"] = "adobe"
        plan["named"][0]["style"] = "adobe"
        _s, blds, *_ = ST.plan_settlements(random.Random(2), self.flat(2), "normal", plan)
        self.assertTrue(all(b["variant"] == "adobe" for b in blds if b["kind"] == "house"))

    def test_the_station_town_takes_the_nearest_free_span_when_its_landmark_has_no_room(self):
        import settlements as ST
        t = self.flat()
        t.summits = [(3344.0, 3584.0)]                 # the Sydney case: a summit between the town and the coast
        t.protected = list(t.summits)
        spec = {"x": 3840.0, "kind": "city", "min": "town", "name": "Coastal", "style": None, "landmark": "Coastal",
                "top": False, "station": False}
        plan = {"named": [spec], "max": "city", "density": 0.0, "style": None, "ponds": 0.0, "lone": 0.0}
        self.assertEqual(ST.plan_settlements(random.Random(2), t, "normal", plan)[0], [], "an ordinary place is left out")
        t2 = self.flat()
        t2.summits = [(3344.0, 3584.0)]
        t2.protected = list(t2.summits)
        plan["named"] = [dict(spec, station=True)]
        sts = ST.plan_settlements(random.Random(2), t2, "normal", plan)[0]
        self.assertEqual([s["name"] for s in sts], ["Coastal"])
        self.assertLessEqual(sts[0]["x1"], 3344.0, "beside the summit, not on it")

    def test_plan_roads_without_a_highway(self):
        import settlements as ST
        sts = [{"x0": 100.0, "x1": 500.0}, {"x0": 900.0, "x1": 1300.0}]
        self.assertEqual([r["kind"] for r in ST.plan_roads(sts, 3840)], ["street", "street", "highway"])
        self.assertEqual([r["kind"] for r in ST.plan_roads(sts, 3840, highway=False)], ["street", "street"])
        self.assertEqual(ST.plan_roads([], 3840, highway=False), [])


if __name__ == "__main__":
    unittest.main()
