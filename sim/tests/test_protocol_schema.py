"""hello + frame message schema validity against PROTOCOL.md v2: required keys, types, ranges,
terrain length, unique object ids, delta rows referencing known ids, new cloud fields, and
reachability of every cloud type and building kind."""
import copy
import json
import math
import os
import subprocess
import sys
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_protocol_schema.py` (or sim.tests.test_protocol_schema) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
from objects import generate_world
from simulation import Simulation

import random


class TestProtocolSchema(unittest.TestCase):
    def setUp(self):
        self.sim = Simulation(seed=1234, w=1280, h=720, fps=30)

    def test_hello_shape(self):
        hello = self.sim.hello_message()
        ids = S.validate_hello(hello)
        self.assertEqual(hello["world"]["w"], 1280)
        self.assertEqual(hello["world"]["h"], 720)
        self.assertEqual(hello["seed"], 1234)
        self.assertFalse(hello["loaded"])
        self.assertEqual(hello["t"], 0.0)
        self.assertEqual(ids, set(range(1, len(hello["objects"]) + 1)), "ids are 1..N")
        step = hello["world"]["terrain"]["step"]
        self.assertEqual(len(hello["world"]["terrain"]["y"]), math.ceil(1280 / step) + 1)

    def test_default_size_is_3840_by_1080(self):
        hello = Simulation(seed=3, w=3840, h=1080, fps=30).hello_message()
        S.validate_hello(hello)
        self.assertEqual((hello["world"]["w"], hello["world"]["h"]), (3840, 1080))
        self.assertEqual(len(hello["world"]["terrain"]["y"]), 3840 // 8 + 1)

    def test_hello_ids_stable_and_objects_not_aliased(self):
        h1 = self.sim.hello_message()
        for _ in range(5):
            self.sim.step(1.0 / 30)
        h2 = self.sim.hello_message()
        self.assertEqual([o["id"] for o in h1["objects"]], [o["id"] for o in h2["objects"]])
        self.assertEqual([o["kind"] for o in h1["objects"]], [o["kind"] for o in h2["objects"]])

    def test_frame_schema_over_a_run(self):
        hello = self.sim.hello_message()
        ids = S.validate_hello(hello)
        ground_frames = 0
        object_rows = 0
        n = 600
        for _ in range(n):
            f = self.sim.step(1.0 / 30)
            S.validate_frame(f, 1280, 720, ids)
            ground_frames += "ground" in f
            object_rows += len(f["objects"])
        self.assertLessEqual(ground_frames, n // 10 + 1, "ground strip at most ~3 times a second")
        self.assertGreater(object_rows, 0, "objects change over a 20 s run")

    def test_wind_block_matches_cols_rows(self):
        msg = self.sim.step(1.0 / 30)
        wind = msg["wind"]
        self.assertEqual(len(wind["u"]), wind["cols"] * wind["rows"])
        self.assertGreaterEqual(wind["cell"], 80)
        big = Simulation(seed=2, w=9600, h=1400, fps=30).step(1.0 / 30)["wind"]
        self.assertGreater(big["cell"], 80, "cell grows for huge worlds")
        self.assertLessEqual(big["cols"] * big["rows"], 1000)

    def test_cloud_shape_and_new_fields(self):
        msg = None
        for _ in range(5):
            msg = self.sim.step(1.0 / 30)
        self.assertTrue(msg["clouds"], "expected clouds alive by frame 5")
        for c in msg["clouds"]:
            S.validate_cloud(c)
            self.assertIn("seed", c)
            self.assertIn("form", c)

    def test_particle_shape(self):
        msg = None
        for _ in range(2500):
            msg = self.sim.step(1.0 / 30)
            if msg["particles"]:
                break
        self.assertTrue(msg["particles"], "expected particles within 2500 frames")
        for p in msg["particles"]:
            self.assertEqual(len(p), 7)
            self.assertIn(p[0], {0, 1, 2, 3, 4, 5, 6, 7})

    def test_all_eight_cloud_types_reachable_across_seeds(self):
        seen = set()
        for seed in range(1, 9):
            # a July afternoon: unstable air, so the convective types are in the mix (a cool April
            # morning, the default start, makes a cumulonimbus rare on purpose)
            sim = Simulation(seed=seed, w=3840, h=1080, fps=30, month=7.5, hour=14.0)
            for _ in range(2):
                msg = sim.step(1.0 / 30)
            seen.update(c["type"] for c in msg["clouds"])
        self.assertTrue(S.BASE_CLOUD_TYPES <= seen <= S.CLOUD_TYPES, f"missing: {S.BASE_CLOUD_TYPES - seen}")

    def test_all_building_kinds_and_variants_reachable(self):
        kinds, variants = set(), set()
        for seed in range(1, 13):
            for mode in ("normal", "dense"):
                _t, _s, objs = generate_world(random.Random(seed), 3840, 1080, "rolling", mode)
                kinds.update(o["kind"] for o in objs)
                variants.update(o["variant"] for o in objs if o["kind"] == "house")
        self.assertTrue(S.BUILDING_KINDS <= kinds, f"missing building kinds: {S.BUILDING_KINDS - kinds}")
        self.assertTrue(S.SCENERY_KINDS <= kinds, f"missing scenery kinds: {S.SCENERY_KINDS - kinds}")
        self.assertEqual(variants, S.PLAIN_HOUSE_VARIANTS)       # (a plain world never builds adobe / hut / station)

    def test_tree_variants_reachable(self):
        seen = set()
        for seed in range(1, 6):
            _t, _s, objs = generate_world(random.Random(seed), 3840, 1080, "hilly", "normal")
            seen.update(o["variant"] for o in objs if o["kind"] == "tree")
        self.assertEqual(seen, S.PLAIN_TREE_VARIANTS)

    def test_the_biome_variants_are_reachable_in_preset_worlds(self):
        """v2.3: palm / cactus / acacia / shrub / dead trees and adobe / hut / station houses exist, and only
        preset worlds make them."""
        from simulation import Simulation
        trees, houses = set(), set()
        for pid in ("hawaii", "atacama", "kenya", "tibet", "oklahoma", "sahara", "antarctica", "meghalaya", "australia", "russia"):
            for seed in (1, 2, 3, 4):
                h = Simulation(seed, 3840, 1080, 30, "rolling", "normal", pid).hello_message()
                trees.update(o["variant"] for o in h["objects"] if o["kind"] == "tree")
                houses.update(o["variant"] for o in h["objects"] if o["kind"] == "house")
                S.validate_hello(h)
        self.assertEqual(trees, S.TREE_VARIANTS)
        self.assertEqual(houses, S.HOUSE_VARIANTS)

    def test_v22_optional_keys_validate(self):
        """mode / preset / showcase (PROTOCOL.md v2.2) are optional and additive."""
        hello = self.sim.hello_message()
        self.assertEqual(hello["mode"], "sandbox")
        self.assertNotIn("preset", hello)
        S.validate_hello(hello)
        preset = Simulation(seed=4, w=1920, h=1080, fps=30, preset="philippines")
        ph = preset.hello_message()
        S.validate_hello(ph)
        self.assertEqual((ph["mode"], ph["world"]["terrain_kind"], ph["preset"]["id"]),
                         ("sandbox", "preset", "philippines"))
        ids = {o["id"] for o in ph["objects"]}
        for _ in range(60):
            S.validate_frame(preset.step(1.0 / 30), 1920, 1080, ids)
        # a v2.1 viewer that ignores the new keys sees an ordinary world
        stripped = {k: v for k, v in ph.items() if k not in ("mode", "preset")}
        stripped["world"] = dict(ph["world"], terrain_kind="rolling")
        S.validate_hello(stripped)
        from showcase import ShowcaseSimulation
        sc = ShowcaseSimulation(seed=4)
        sh = sc.hello_message()
        S.validate_hello(sh)
        self.assertEqual(sh["mode"], "showcase")
        for _ in range(120):
            f = sc.step(1.0 / 30)
            S.validate_frame(f, 1920, 1080, {o["id"] for o in sh["objects"]})
            self.assertIn("showcase", f)

    def test_supported_size_range_builds_valid_hello(self):
        for (w, h) in ((1280, 720), (9600, 1400), (9600, 720), (1280, 1440)):
            sim = Simulation(seed=5, w=w, h=h, fps=30)
            S.validate_hello(sim.hello_message())
            S.validate_frame(sim.step(1.0 / 30), w, h, set(range(1, len(sim.objects.objs) + 1)))


def sheet(water, sid, depth):
    """Put `depth` px of film on every cell of settlement `sid`'s span (a test helper: the field has no street body to fill)."""
    for k in water.film.span_cells[sid]:
        water.film.S[k] = depth * 32.0
        water.film.act.add(k)


class TestProtocolV24(unittest.TestCase):
    """PROTOCOL.md v2.4: hello.water / hello.drains / settlements[].drain, frame.water / flood / drains, cars[].depth."""
    ENTRY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "precip_sim.py")

    def hello_and_frames(self, n=3, args=()):
        out = subprocess.run([sys.executable, self.ENTRY, "--seed", "12", "--headless-frames", str(n), *args],
                             capture_output=True, text=True, timeout=120)
        msgs = [json.loads(line) for line in out.stdout.splitlines() if line.startswith("{")]
        return out, msgs

    def test_a_sandbox_hello_has_water_drains_and_a_drain_per_settlement(self):
        sim = Simulation(seed=12, w=3840, h=1080, fps=30)
        h = sim.hello_message()
        S.validate_hello(h)
        self.assertEqual(h["water"], {"model": 1, "basin_min_depth": 1.0})
        self.assertEqual(h["drains"]["classes"], {"small": 3.0, "normal": 4.7, "large": 8.2})
        # (v2.4.1: there is no virtual curb any more: `curb` is 0.0 and the `overflowing` band is `overflow_depth`; was 10.0)
        self.assertEqual((h["drains"]["inlet_spacing"], h["drains"]["curb"], h["drains"]["flood_depth"]), (60.0, 0.0, 3.0))
        self.assertEqual(h["drains"]["overflow_depth"], 8.0)
        self.assertEqual(h["flow"], {"model": 1, "cell": 32, "min_h": 0.05})
        self.assertEqual((h["drains"]["size_min"], h["drains"]["size_max"]), (1.5, 12.0))
        self.assertTrue(h["settlements"] and all(set(s["drain"]) == {"size", "class", "inlets", "cap"} for s in h["settlements"]))

    def test_every_v24_key_is_optional_for_a_validator_of_older_streams(self):
        sim = Simulation(seed=12, w=3840, h=1080, fps=30)
        h = sim.hello_message()
        old = copy.deepcopy(h)
        for k in ("water", "drains", "flow"):
            old.pop(k)
        for st in old["settlements"]:
            st.pop("drain")
        S.validate_hello(old)                              # a v2.3 hello still validates
        v24 = copy.deepcopy(h)                             # (v2.4.1) and a v2.4 hello: no `flow`, a curb of 10, no overflow_depth
        v24.pop("flow")
        v24["drains"]["curb"] = 10.0
        v24["drains"].pop("overflow_depth")
        S.validate_hello(v24)
        f = sim.step(1.0 / 30)
        f_old = {k: v for k, v in f.items() if k not in ("water", "flood", "drains", "flow")}
        S.validate_frame(f_old, 3840, 1080, S.validate_hello(h))
        S.validate_frame(f_old, 3840, 1080, S.validate_hello(h), [s["id"] for s in h["settlements"]])

    def test_wrong_types_and_values_are_rejected(self):
        sim = Simulation(seed=12, w=3840, h=1080, fps=30)
        sim.handle_command({"cmd": "drains", "mode": "small"}, [])
        h = sim.hello_message()
        ids = S.validate_hello(h)
        sids = [s["id"] for s in h["settlements"]]
        town = max(sim.settlements, key=lambda t: t["x1"] - t["x0"])
        sheet(sim.water, town["id"], 4.0)         # (v2.4.1: 4 px of film over the street; there is no street body to fill)
        j = next(i for i, el in enumerate(sim.water.tmap.elems) if el.kind == "basin")
        sim.water._body_for(j).V = 600.0          # and a pool in the first natural hollow, so `water` has a body to break
        sim.water._finish()
        f = sim.step(1.0 / 30)
        self.assertTrue(f["flow"] and f["flood"] and f["water"])
        S.validate_frame(f, 3840, 1080, ids, sids)

        def broken(mut, frame=False):
            d = copy.deepcopy(f if frame else h)
            mut(d)
            with self.assertRaises((AssertionError, KeyError, TypeError)):
                S.validate_frame(d, 3840, 1080, ids, sids) if frame else S.validate_hello(d)
        broken(lambda d: d["water"].update(model="1"))
        broken(lambda d: d["drains"].update(mode="huge"))
        broken(lambda d: d["drains"]["classes"].pop("large"))
        broken(lambda d: d["settlements"][0]["drain"].update(inlets=2.5))
        broken(lambda d: d["settlements"][0]["drain"].update(**{"class": "large"}))   # the class must follow the size
        broken(lambda d: d.pop("drains"))
        broken(lambda d: d["settlements"][0].pop("drain"))
        broken(lambda d: d["water"][0].update(kind="lake"), True)
        broken(lambda d: d["water"][0].update(id="3"), True)
        broken(lambda d: d["water"][0].update(depth=-1.0), True)
        broken(lambda d: d["water"][0].update(settlement=99), True)
        broken(lambda d: d["water"][0].update(spill="yes"), True)
        broken(lambda d: d["water"][0].update(x0=d["water"][0]["x1"] + 5.0), True)
        broken(lambda d: d["flood"][0].update(state="soaked"), True)
        broken(lambda d: d["flood"].pop(), True)
        broken(lambda d: d["flood"][0].update(depth="deep"), True)
        broken(lambda d: d["flow"][0].update(dir=2), True)                       # v2.4.1 keys
        broken(lambda d: d["flow"][0].update(h=d["flow"][0]["h"][:-1]), True)     # h and q of one length
        broken(lambda d: d["flow"][0].update(h=[0.07] * len(d["flow"][0]["h"])), True)      # steps of 0.05
        broken(lambda d: d["flow"][0].update(dst=-3), True)
        broken(lambda d: d["flow"].append(dict(d["flow"][0])), True)             # a cell in two runs
        broken(lambda d: d["flood"][0].update(backed=False), True)               # `backed` only when true
        broken(lambda d: d["flow"].update(cell=16))

    def test_showcase_and_a_settlement_free_world_carry_only_what_applies(self):
        from showcase import ShowcaseSimulation
        sc = ShowcaseSimulation(3, 1920, 1080, 30, "cumulus")
        h = sc.hello_message()
        S.validate_hello(h)
        self.assertTrue("water" not in h and "drains" not in h)
        self.assertTrue(all("drain" not in s for s in h["settlements"]))
        none = Simulation(seed=5, w=2560, h=1080, fps=30, settlements="none")
        hn = none.hello_message()
        S.validate_hello(hn)
        self.assertIn("water", hn)
        self.assertEqual(hn["settlements"], [])

    def test_the_command_line_hello_has_the_keys_and_showcase_has_none(self):
        out, msgs = self.hello_and_frames(3)
        self.assertEqual(out.returncode, 0, out.stderr)
        hello = msgs[0]
        self.assertEqual(hello["type"], "hello")
        for k in ("water", "drains"):
            self.assertIn(k, hello)
        self.assertTrue(all("drain" in s for s in hello["settlements"]))
        self.assertEqual(hello["drains"]["mode"], "normal")
        self.assertIn("water", msgs[1])
        self.assertIn("drains=normal", out.stderr)
        out = subprocess.run([sys.executable, self.ENTRY, "--mode", "showcase", "--headless-frames", "2"],
                             capture_output=True, text=True, timeout=120)
        hello = json.loads(out.stdout.splitlines()[0])
        self.assertEqual(hello["mode"], "showcase")
        self.assertTrue("water" not in hello and "drains" not in hello)

    def test_the_drains_flag_chooses_the_mode_and_a_bad_value_exits_non_zero(self):
        for mode in ("small", "large", "random"):
            out, msgs = self.hello_and_frames(1, ("--drains", mode))
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertEqual(msgs[0]["drains"]["mode"], mode)
        small = self.hello_and_frames(1, ("--drains", "small"))[1][0]
        large = self.hello_and_frames(1, ("--drains", "large"))[1][0]
        self.assertEqual({s["drain"]["size"] for s in small["settlements"]}, {3.0})
        self.assertEqual({s["drain"]["size"] for s in large["settlements"]}, {8.2})
        out, msgs = self.hello_and_frames(1, ("--drains", "huge"))
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("--drains", out.stderr)
        self.assertEqual(msgs, [])
        # accepted without settlements, and then it changes nothing
        out, msgs = self.hello_and_frames(1, ("--drains", "large", "--settlements", "none"))
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(msgs[0]["settlements"], [])
        # accepted in a mode that has no drains
        out, msgs = self.hello_and_frames(1, ("--drains", "large", "--mode", "showcase"))
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertNotIn("drains", msgs[0])

    def test_a_ten_second_run_with_a_flooded_street_validates_frame_by_frame(self):
        sim = Simulation(seed=12, w=3840, h=1080, fps=30)
        sim.handle_command({"cmd": "drains", "mode": "small"}, [])
        h = sim.hello_message()
        ids = S.validate_hello(h)
        sids = [s["id"] for s in h["settlements"]]
        town = max(sim.settlements, key=lambda t: t["x1"] - t["x0"])
        sheet(sim.water, town["id"], 5.0)         # (v2.4.1: a 5 px sheet on the street, running off its low end)
        kinds = set()
        runs = 0
        for _ in range(300):
            f = sim.step(1.0 / 30)
            S.validate_frame(f, 3840, 1080, ids, sids, h["world"]["terrain"]["y"])
            kinds |= {b["kind"] for b in f.get("water", [])}
            runs += len(f.get("flow", []))
        self.assertGreater(runs, 0, "the sheet is on the wire as runs of film")


class TestProtocolV23(unittest.TestCase):
    """The v2.3 keys of the climate / merging half (Sim Core 1): present in sandbox frames, valid over a
    run that includes merges, and the validators reject wrong types."""

    def test_hello_and_frames_carry_climate_and_atmosphere(self):
        sim = Simulation(seed=3, w=1920, h=1080, fps=30)
        hello = sim.hello_message()
        ids = S.validate_hello(hello)
        self.assertIn("climate", hello)
        f = sim.step(1.0 / 30)
        S.validate_frame(f, 1920, 1080, ids)
        self.assertIn("atmosphere", f)
        self.assertIn("temp", f["world"])

    def test_showcase_frames_carry_them_too(self):
        from showcase import ShowcaseSimulation
        sim = ShowcaseSimulation(2, 1920, 1080, 30, "cumulus")
        hello = sim.hello_message()
        S.validate_hello(hello)
        f = sim.step(1.0 / 30)
        S.validate_frame(f, 1920, 1080, {o["id"] for o in hello["objects"]})
        self.assertIn("atmosphere", f)
        self.assertEqual(f["world"]["season"], "spring")
        self.assertEqual(f["world"]["time_of_day"], 0.625)

    def test_a_run_with_merges_validates_frame_by_frame(self):
        sim = Simulation(seed=3, w=3840, h=1080, fps=30)
        ids = {o["id"] for o in sim.objects.objs}
        merged = 0
        for _ in range(30 * 240):
            f = sim.step(1.0 / 30)
            S.validate_frame(f, 3840, 1080, ids)
            merged += sum(1 for c in f["clouds"] if "parts" in c or "merging_into" in c)
        self.assertGreater(merged, 0, "the run contains a merge in flight")

    def test_wrong_types_are_rejected(self):
        sim = Simulation(seed=3, w=1280, h=720, fps=30)
        ids = {o["id"] for o in sim.objects.objs}
        good = sim.step(1.0 / 30)

        def broken(edit):
            import copy
            f = copy.deepcopy(good)
            edit(f)
            with self.assertRaises(AssertionError):
                S.validate_frame(f, 1280, 720, ids)

        broken(lambda f: f["world"].update(season="monsoon"))
        broken(lambda f: f["world"].update(year_frac=1.5))
        broken(lambda f: f["world"].update(day=1.5))
        broken(lambda f: f["world"]["temp"].update(gas_c="warm"))
        broken(lambda f: f["world"].update(surface_temp_c=f["world"]["surface_temp_c"] + 3.0))
        broken(lambda f: f["world"].pop("temp"))
        broken(lambda f: f["atmosphere"].update(humidity=1.4))
        broken(lambda f: f["atmosphere"].update(co2_ppm=50))
        broken(lambda f: f["atmosphere"].update(cloud_target=3.5))
        broken(lambda f: f["atmosphere"]["type_weights"].pop("supercell"))
        broken(lambda f: f["atmosphere"]["type_weights"].update(cumulus=0.9))
        c = good["clouds"][0]
        broken(lambda f: f["clouds"][0].update(parts=[{"id": 1, "type": "stratus", "name": "x"}]))
        broken(lambda f: f["clouds"][0].update(parts=[{"id": 1, "type": "stratus", "name": "x"}], merge=2.0,
                                                puff_part=[0] * len(c["puffs"])))
        broken(lambda f: f["clouds"][0].update(parts=[{"id": 1, "type": "stratus", "name": "x"}], merge=1.0,
                                                puff_part=[0, 1]))
        broken(lambda f: f["clouds"][0].update(merging_into="42"))
        broken(lambda f: f["clouds"][0].update(puffs=[[0.0, 0.0, 5.0, 0.5]] * 73))
        bad = dict(sim.hello_message())
        bad["climate"] = dict(bad["climate"], monthly_temp_c=[1.0] * 11)
        with self.assertRaises(AssertionError):
            S.validate_hello(bad)
        bad["climate"] = dict(sim.hello_message()["climate"], source="guessed")
        with self.assertRaises(AssertionError):
            S.validate_hello(bad)


if __name__ == "__main__":
    unittest.main()
