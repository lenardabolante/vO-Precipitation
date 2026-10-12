"""Cloud merging (protocol v2.3): the larger cloud survives, the other is absorbed over a few seconds,
the survivor's skeleton gains the absorbed cloud's puffs where they were, and nothing jumps.

A previous morph shipped with puffs stuck at r = 0 while every test was green, so these tests also
measure the finished sizes of the appended puffs, and the shape itself was rendered to an image
(tools/debug_plots/v23/merge_sheet_into_cumulus.png) and looked at."""
import copy
import json
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_merge.py` (or sim.tests.test_merge) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import clouds as C
from simulation import Simulation

DT = 1.0 / 30


def scene(seed=11, a_type="cumulus", b_type="stratus", dx=40.0, w=3840, h=1080):
    """A quiet flat world with two fully formed, fair, non-towering clouds that overlap."""
    sim = Simulation(seed, w, h, 30, "flat", "none")
    sim.merging = False                 # the test decides when they merge
    sim._next_spawn_delay = 1e18        # ... and nothing else is born in the meantime
    sim.clouds.clear()
    sim.world.prevailing_u = 0.0
    a = sim._spawn_cloud_auto(x=1500.0, y=400.0, cloud_type=a_type, fair=True)
    b = sim._spawn_cloud_auto(x=1500.0 + dx, y=400.0, cloud_type=b_type, fair=True)
    for c in (a, b):
        c.vx = c.vy = 0.0
        c.from_type = ""                # never tower on their own
        sim._fast_forward_new_cloud(c, 0.38)
        c.lifetime = max(c.lifetime, 600.0)
    for _ in range(30):                 # settle the springs
        sim.step(DT)
        for c in (a, b):
            c.vx = c.vy = 0.0
    # put the sheet's band across the middle of the other cloud (the wind moved them while they formed)
    a.ax = 1500.0
    b.ax = 1500.0 + dx
    b.ay += a.y - b.y
    b.ay_pref = b.ay
    a.refresh()
    b.refresh()
    return sim, a, b


def run_merge(sim, s, a, frames=600, track=None):
    """Begin the merge, then step until it is over. `track` gets (frame dict, survivor, absorbed) per tick."""
    ev = []
    ok = C.begin_merge(s, a, sim.world.t, ev)
    assert ok, "begin_merge refused"
    out = []
    for i in range(frames):
        for c in (s, a):
            c.vx = c.vy = 0.0
        f = sim.step(DT, extra_events=None)
        out.append(f)
        if s.mg is None and not any(c["id"] == a.id for c in f["clouds"]):
            break
    return out, ev


def cloud_json(f, cid):
    for c in f["clouds"]:
        if c["id"] == cid:
            return c
    return None


class TestMergeRules(unittest.TestCase):
    def test_the_larger_capacity_survives_and_a_cumulonimbus_always_does(self):
        sim, a, b = scene(b_type="stratus")
        big, small = (a, b) if a.water_capacity + a.ice_capacity > b.water_capacity + b.ice_capacity else (b, a)
        self.assertIs(C.merge_survivor(a, b)[0], big)
        self.assertIs(C.merge_survivor(b, a)[0], big)
        sim2, cb, cu = scene(a_type="cumulonimbus", b_type="nimbostratus")
        cb.water_capacity = cb.ice_capacity = 0.001      # tiny capacity, but a cb
        self.assertIs(C.merge_survivor(cb, cu)[0], cb)
        self.assertIs(C.merge_survivor(cu, cb)[0], cb)

    def test_overlap_test_needs_horizontal_and_vertical_overlap(self):
        sim, a, b = scene(dx=40.0)
        self.assertTrue(C.merge_overlap(a, b))
        b.ax += 3000.0
        b.refresh()
        self.assertFalse(C.merge_overlap(a, b), "too far apart")
        b.ax -= 3000.0
        b.ay -= 600.0
        b.ay_pref = b.ay
        b.refresh()
        self.assertFalse(C.merge_overlap(a, b), "one is far above the other")

    def test_ready_needs_condensed_and_not_dissipating_or_merging(self):
        sim, a, b = scene()
        self.assertTrue(C.merge_ready(a))
        a.stage = "dissipating"
        self.assertFalse(C.merge_ready(a))
        a.stage = "mature"
        a.form = 0.5
        self.assertFalse(C.merge_ready(a))
        a.form = 1.0
        a.merging_into = 99
        self.assertFalse(C.merge_ready(a))


class TestMergeShape(unittest.TestCase):
    def setUp(self):
        self.sim, self.cu, self.st = scene()
        self.s, self.a = C.merge_survivor(self.cu, self.st)
        self.L0 = len(self.s.layout)
        self.before = copy.deepcopy(self.s.to_state())

    def test_nothing_jumps_on_the_merge_frame(self):
        s, a = self.s, self.a
        f0 = self.sim._cloud_to_protocol(s)
        ev = []
        self.assertTrue(C.begin_merge(s, a, self.sim.world.t, ev))
        s.refresh()
        f1 = self.sim._cloud_to_protocol(s)
        # own puffs identical, appended puffs at r = 0, bounds / tint / opacity / darkness identical
        self.assertEqual(f1["puffs"][:self.L0], f0["puffs"])
        self.assertTrue(all(p[2] == 0.0 for p in f1["puffs"][self.L0:]), "appended puffs start at r = 0")
        for k in ("x", "y", "w", "h", "opacity", "darkness", "tint", "form", "shear", "anvil", "water", "ice"):
            self.assertEqual(f1[k], f0[k], k)
        self.assertEqual(f1["merge"], 0.0)
        self.assertEqual(len(f1["puff_part"]), len(f1["puffs"]))
        self.assertEqual(f1["puff_part"][:self.L0], [0] * self.L0)
        self.assertEqual(set(f1["puff_part"][self.L0:]), {1})
        self.assertEqual(f1["parts"], [{"id": a.id, "type": a.type, "name": a.name}])
        self.assertEqual(len(ev), 1)
        self.assertIn("absorbed", ev[0])

    def test_nothing_jumps_while_it_merges(self):
        s, a = self.s, self.a
        frames, _ = run_merge(self.sim, s, a)
        prev = None
        worst = {"r": 0.0, "pos": 0.0, "w": 0.0, "h": 0.0, "x": 0.0, "y": 0.0, "op": 0.0, "tint": 0}
        for f in frames:
            c = cloud_json(f, s.id)
            if prev is not None:
                for j, (p, q) in enumerate(zip(prev["puffs"], c["puffs"])):
                    worst["r"] = max(worst["r"], abs(q[2] - p[2]))
                    # the survivor's own puffs stay where they are (the appended ones bud off a parent
                    # and travel while they swell, like a tower does)
                    if j < self.L0 and p[2] > 1.0 and q[2] > 1.0:
                        worst["pos"] = max(worst["pos"], abs(q[0] - p[0]), abs(q[1] - p[1]))
                for k in ("w", "h", "x", "y"):
                    worst[k] = max(worst[k], abs(c[k] - prev[k]))
                worst["op"] = max(worst["op"], abs(c["opacity"] - prev["opacity"]))
                worst["tint"] = max(worst["tint"], max(abs(i - j) for i, j in zip(c["tint"], prev["tint"])))
            prev = c
        self.assertLess(worst["r"], 4.0, worst)
        self.assertLess(worst["pos"], 8.0, worst)
        self.assertLess(worst["w"], 4.0, worst)
        self.assertLess(worst["h"], 4.0, worst)
        self.assertLess(worst["x"], 4.0, worst)
        self.assertLess(worst["y"], 4.0, worst)
        self.assertLess(worst["op"], 0.02, worst)
        self.assertLessEqual(worst["tint"], 2, worst)

    def test_the_finished_skeleton_contains_the_absorbed_puffs_at_their_relative_positions(self):
        s, a = self.s, self.a
        raw_a, _ = C.eval_layout(a.layout, 1.0, a.anvil, a.shear, 0.0, a.core, False, 1.0, None)
        want = [(a.ax + x, a.ay + y, r) for x, y, r, _sh in raw_a if r >= 1.5]
        ev = []
        C.begin_merge(s, a, self.sim.world.t, ev)
        raw_s, _ = C.eval_layout(s.layout, 1.0, 1.0, s.shear, 0.0, s.core, False, 1.0, None)
        got = [(s.ax + x, s.ay + y, r) for x, y, r, _sh in raw_s[self.L0:]]
        self.assertEqual(len(got), len(want))
        for (gx, gy, gr), (wx, wy, wr) in zip(got, want):
            self.assertAlmostEqual(gx, wx, delta=0.3)
            self.assertAlmostEqual(gy, wy, delta=0.3)
            self.assertAlmostEqual(gr, wr, delta=0.3)

    def test_appended_puffs_really_reach_their_size(self):
        """The morph bug: every test green, puffs stuck at r = 0. Measure the finished radii."""
        s, a = self.s, self.a
        raw_a, _ = C.eval_layout(a.layout, 1.0, a.anvil, a.shear, 0.0, a.core, False, 1.0, None)
        sizes = sorted(r for _x, _y, r, _s in raw_a if r >= 1.5)
        frames, _ = run_merge(self.sim, s, a)
        last = cloud_json(frames[-1], s.id)
        for _ in range(60):
            last = cloud_json(self.sim.step(DT), s.id)
        self.assertEqual(last["merge"], 1.0)
        grown = sorted(p[2] for p, part in zip(last["puffs"], last["puff_part"]) if part == 1)
        self.assertEqual(len(grown), len(sizes))
        for g, w in zip(grown, sizes):
            self.assertGreater(g, 0.9 * w, "an appended puff is stuck below its size")
            self.assertLess(g, 1.12 * w)
        self.assertEqual(min(grown) > 3.0, True)

    def test_a_sheet_into_a_cumulus_leaves_a_flat_band_through_it(self):
        sim, cu, st = scene(a_type="cumulus", b_type="stratus")
        s, a = C.merge_survivor(cu, st)
        # make the sheet the absorbed one regardless of capacities
        s, a = cu, st
        L0 = len(s.layout)
        frames, _ = run_merge(sim, s, a)
        for _ in range(60):
            sim.step(DT)
        c = sim._cloud_to_protocol(s)
        band = [p for p, part in zip(c["puffs"], c["puff_part"]) if part == 1]
        body = [p for p, part in zip(c["puffs"], c["puff_part"]) if part == 0 and p[2] > 1]
        self.assertGreaterEqual(len(band), 10)
        ys = [p[1] for p in band]
        xs0 = min(p[0] - p[2] for p in band)
        xs1 = max(p[0] + p[2] for p in band)
        self.assertLess(max(ys) - min(ys), 0.5 * (xs1 - xs0) * 0.25, "the band is flat: tall spread << width")
        self.assertLess(max(ys) - min(ys), 60.0)
        # it crosses the cumulus: the band's horizontal span overlaps most of the narrower of the two
        bx0 = min(p[0] - p[2] for p in body)
        bx1 = max(p[0] + p[2] for p in body)
        overlap = min(xs1, bx1) - max(xs0, bx0)
        self.assertGreater(overlap, 0.7 * min(xs1 - xs0, bx1 - bx0), (xs0, xs1, bx0, bx1))
        # and it sits at the body's height: inside its vertical range
        by0 = min(p[1] - p[2] for p in body)
        by1 = max(p[1] + p[2] for p in body)
        mid = sum(ys) / len(ys)
        self.assertTrue(by0 < mid < by1, "the band runs through the cumulus, not above or below it")
        # horizontal coverage of the band has no gap (neighbouring discs overlap)
        spans = sorted((p[0] - p[2], p[0] + p[2]) for p in band)
        reach = spans[0][1]
        for lo, hi in spans[1:]:
            self.assertLessEqual(lo, reach + 1.0, "a gap in the band")
            reach = max(reach, hi)

    def test_absorbed_cloud_fades_dry_and_leaves_with_merged_into(self):
        s, a = self.s, self.a
        a.stage = "precipitating"           # even a raining cloud stops the moment it is absorbed
        a.precip_type = "rain"
        a.precip_rate = 0.5
        a.water = 0.5 * a.water_capacity
        ev = []
        self.assertTrue(C.begin_merge(s, a, self.sim.world.t, ev))
        frames = []
        offsets = []
        for _ in range(600):
            for c in (s, a):
                c.vx = c.vy = 0.0
            f = self.sim.step(DT)
            frames.append(f)
            if cloud_json(f, a.id) is not None:
                offsets.append((a.ax - s.ax, a.ay - s.ay))
            if s.mg is None and cloud_json(f, a.id) is None:
                break
        seen = [cloud_json(f, a.id) for f in frames]
        present = [c for c in seen if c is not None]
        self.assertTrue(present)
        forms = [c["form"] for c in present]
        self.assertTrue(all(y <= x + 1e-9 for x, y in zip(forms, forms[1:])), "form only falls")
        self.assertLess(forms[-1], 0.05)
        for c in present:
            self.assertEqual(c["merging_into"], s.id)
            self.assertEqual(c["stage"], "dissipating")
            self.assertEqual(c["precip_rate"], 0.0, "it never precipitates during the crossfade")
        # a few seconds, not a blink and not a minute
        self.assertTrue(3.0 <= len(present) / 30.0 <= 15.0, len(present) / 30.0)
        deaths = [d for f in frames for d in f["deaths"] if d["id"] == a.id]
        self.assertEqual(len(deaths), 1)
        self.assertEqual(deaths[0]["merged_into"], s.id)
        self.assertGreaterEqual(self.sim.clouds_dead, 1)
        # it moves with the survivor: the anchor offset stays what it was when the merge began
        self.assertTrue(len(offsets) > 60)
        for ox, oy in offsets:
            self.assertAlmostEqual(ox, a.merge_dx, delta=1e-6)
            self.assertAlmostEqual(oy, a.merge_dy, delta=1e-6)

    def test_capacities_water_ice_and_lifetime_add_and_never_shorten(self):
        s, a = self.s, self.a
        wc, ic = s.water_capacity + a.water_capacity, s.ice_capacity + a.ice_capacity
        life0 = s.lifetime
        w0, i0 = s.water, s.ice
        frames, _ = run_merge(self.sim, s, a)
        self.assertAlmostEqual(s.water_capacity, wc, places=6)
        self.assertAlmostEqual(s.ice_capacity, ic, places=6)
        self.assertGreaterEqual(s.lifetime, life0)
        self.assertGreaterEqual(s.water + 1e-9, w0 * 0.99)
        lifetimes = [cloud_json(f, s.id)["lifetime"] for f in frames]
        self.assertTrue(all(y >= x - 1e-6 for x, y in zip(lifetimes, lifetimes[1:])))

    def test_tint_glides_to_the_capacity_weighted_mean(self):
        s, a = self.s, self.a
        cs, ca = s.water_capacity + s.ice_capacity, a.water_capacity + a.ice_capacity
        want = [(s.tint[i] * cs + a.tint[i] * ca) / (cs + ca) for i in range(3)]
        run_merge(self.sim, s, a)
        for i in range(3):
            self.assertAlmostEqual(s.tint[i], want[i], delta=1.0)

    def test_signatures_are_not_touched(self):
        before = set(self.sim.signatures)
        run_merge(self.sim, self.s, self.a)
        self.assertEqual(self.sim.signatures, before)

    def test_a_fair_survivor_that_absorbs_a_rain_cloud_stops_being_fair(self):
        sim, a, b = scene(a_type="cumulus", b_type="nimbostratus")
        a.fair = True
        b.fair = False
        a.water_capacity = 5.0           # make a the survivor
        s, ab = C.merge_survivor(a, b)
        self.assertIs(s, a)
        events = []
        C.begin_merge(s, ab, sim.world.t, events)
        self.assertFalse(s.fair)
        self.assertTrue(any("developing" in e for e in events))

    def test_a_fair_cloud_absorbing_a_fair_cloud_stays_fair(self):
        s, a = self.s, self.a
        s.fair = a.fair = True
        C.begin_merge(s, a, self.sim.world.t, [])
        self.assertTrue(s.fair)


class TestMergeLimits(unittest.TestCase):
    @staticmethod
    def settle(sim, s, frames=900):
        for _ in range(frames):
            if s.mg is None:
                return
            sim.step(DT)
        raise AssertionError("the merge never finished")

    def test_at_most_three_parts_and_72_puffs(self):
        sim, s, a = scene(a_type="cumulus", b_type="altostratus")
        s.from_type = ""
        for k in range(3):
            extra = sim._spawn_cloud_auto(x=s.ax + 30.0 * k, y=s.y, cloud_type="stratocumulus", fair=True)
            extra.vx = extra.vy = 0.0
            extra.from_type = ""
            sim._fast_forward_new_cloud(extra, 0.38)
            extra.ay = s.ay
            extra.refresh()
            for _ in range(5):
                sim.step(DT)
            if k == 0:
                self.assertTrue(C.begin_merge(s, a, sim.world.t, []))
                self.settle(sim, s)
            self.assertTrue(C.begin_merge(s, extra, sim.world.t, []) or len(s.parts) >= 3
                            or C.merge_room(s) < C.MIN_COPIES)
            self.settle(sim, s)
            self.assertLessEqual(len(s.layout), C.MERGED_MAX_PUFFS)
        self.assertGreaterEqual(len(s.parts), 2)
        self.assertLessEqual(len(s.parts), C.MAX_PARTS)
        self.assertEqual(len(s.puff_part), len(s.layout))
        extra2 = sim._spawn_cloud_auto(x=s.ax, y=s.y, cloud_type="stratus", fair=True)
        extra2.refresh()
        if len(s.parts) >= 3:
            self.assertFalse(C.begin_merge(s, extra2, sim.world.t, []), "a fourth part is refused")

    def test_short_of_room_the_largest_puffs_are_kept(self):
        sim, s, a = scene(a_type="cumulonimbus", b_type="stratus")
        s, a = C.merge_survivor(s, a)
        # fill the survivor's layout nearly to the cap with dummy tiny puffs
        pad = C.MERGED_MAX_PUFFS - len(s.layout) - 6
        for _ in range(pad):
            s.layout.append((0, 0.5, 0.3, 0.0, -10.0, 0.5, 0.5, 0, 0.1, 0.01, 0.5, 0.0))
            s.puff_part.append(0)
        raw_a, _ = C.eval_layout(a.layout, 1.0, a.anvil, a.shear, 0.0, a.core, False, 1.0, None)
        room = C.merge_room(s)
        self.assertEqual(room, 6)
        biggest = sorted((r for _x, _y, r, _s in raw_a if r >= 1.5), reverse=True)[:6]
        self.assertTrue(C.begin_merge(s, a, sim.world.t, []))
        self.assertEqual(len(s.layout), C.MERGED_MAX_PUFFS)
        raw_s, _ = C.eval_layout(s.layout, 1.0, 1.0, s.shear, 0.0, s.core, False, 1.0, None)
        got = sorted((q[2] for q in raw_s[-6:]), reverse=True)
        for g, w in zip(got, biggest):
            self.assertAlmostEqual(g, w, delta=0.3)

    def test_no_room_means_no_merge_and_nothing_changes(self):
        sim, s, a = scene(a_type="cumulonimbus", b_type="stratus")
        s, a = C.merge_survivor(s, a)
        while C.merge_room(s) >= C.MIN_COPIES:
            s.layout.append((0, 0.5, 0.3, 0.0, -10.0, 0.5, 0.5, 0, 0.1, 0.01, 0.5, 0.0))
            s.puff_part.append(0)
        n = len(s.layout)
        self.assertFalse(C.begin_merge(s, a, sim.world.t, []))
        self.assertEqual(len(s.layout), n)
        self.assertIsNone(a.merging_into)
        self.assertEqual(s.parts, [])

    def test_a_cumulus_keeps_room_to_tower(self):
        sim, s, a = scene(a_type="cumulus", b_type="stratus")
        s.from_type = None
        self.assertEqual(C.merge_room(s), C.MERGED_MAX_PUFFS - C.TOWER_RESERVE - len(s.layout))
        s.from_type = ""
        self.assertEqual(C.merge_room(s), C.MERGED_MAX_PUFFS - len(s.layout))

    def test_a_cumulonimbus_survivor_keeps_its_own_anvil_only(self):
        sim, cb, other = scene(a_type="cumulonimbus", b_type="cumulonimbus")
        s, a = C.merge_survivor(cb, other)
        L0 = len(s.layout)
        self.assertTrue(C.begin_merge(s, a, sim.world.t, []))
        self.assertTrue(all(p[0] >= 3 for p in s.layout[L0:]), "copies grow on the merge axis")
        # (the absorbed anvil puffs are not copied: the survivor has its own)
        n_anvil_a = sum(1 for p in a.layout if p[0] == 1)
        self.assertGreater(n_anvil_a, 0)
        self.assertLessEqual(len(s.layout) - L0, len(a.layout) - n_anvil_a)


class TestMergeWithTower(unittest.TestCase):
    def test_a_merged_cumulus_can_still_tower_and_keeps_its_merge_puffs(self):
        sim, s, a = scene(a_type="cumulus", b_type="stratus")
        s, a = s, a            # the cumulus is s unless the sheet has more capacity
        if C.merge_survivor(s, a)[0] is not s:
            s, a = a, s
        s.from_type = None          # may tower again
        if s.type != "cumulus":
            self.skipTest("survivor is not a cumulus")
        run_merge(sim, s, a)
        for _ in range(40):
            sim.step(DT)
        layout_before = list(s.layout)
        parts_before = list(s.puff_part)
        s.stage = "growing"
        events = []
        did = C.begin_tower(s, sim.world, sim.terrain, sim.world.t, events)
        self.assertTrue(did, "a merged cumulus keeps its right to tower")
        self.assertEqual(s.layout[:len(layout_before)], layout_before, "merge puffs survive the tower")
        self.assertEqual(s.puff_part[:len(parts_before)], parts_before)
        self.assertEqual(len(s.puff_part), len(s.layout))
        self.assertEqual(set(s.puff_part[len(parts_before):]), {0})
        self.assertLessEqual(len(s.layout), C.MERGED_MAX_PUFFS)
        # the whole thing stays smooth while it towers
        prev = sim._cloud_to_protocol(s)["puffs"]
        worst = 0.0
        for _ in range(900):
            sim.step(DT)
            cur = cloud_json(sim.step(DT), s.id)
            if cur is None:
                break
            worst = max(worst, max(abs(q[2] - p[2]) for p, q in zip(prev, cur["puffs"])))
            prev = cur["puffs"]
        self.assertLess(worst, 8.0)

    def test_a_tower_is_refused_while_a_merge_is_settling(self):
        sim, s, a = scene(a_type="cumulus", b_type="stratus")
        s.from_type = None
        C.begin_merge(s, a, sim.world.t, [])
        self.assertFalse(C.begin_tower(s, sim.world, sim.terrain, sim.world.t, []))

    def test_a_towered_cloud_can_absorb_and_the_merge_is_the_only_thing_moving(self):
        sim, s, a = scene(a_type="cumulus", b_type="stratus")
        s.from_type = None
        s.stage = "growing"
        s.lifetime = 5000.0
        self.assertTrue(C.begin_tower(s, sim.world, sim.terrain, sim.world.t, []))
        s.lifetime = 5000.0
        s.morph_to["lifetime"] = 5000.0
        for _ in range(30 * 200):        # let the tower finish (morph -> 1)
            sim.step(DT)
            s.vx = s.vy = a.vx = a.vy = 0.0
            if s.morph >= 0.9995:
                break
        self.assertGreaterEqual(s.morph, 0.999)
        s.stage = "mature"               # (it has rained itself out by now; the merge needs a live cloud)
        s.form = max(s.form, 0.95)
        a.merging_into = None
        a.ay = s.ay
        a.refresh()
        sim.merging = False
        if not C.merge_ready(s) or not C.merge_ready(a):
            self.skipTest("clouds not both ready after the tower")
        before = sim._cloud_to_protocol(s)
        self.assertTrue(C.begin_merge(s, a, sim.world.t, []))
        s.refresh()
        after = sim._cloud_to_protocol(s)
        self.assertEqual(after["puffs"][:len(before["puffs"])], before["puffs"])
        for k in ("w", "h", "x", "y", "tint", "opacity"):
            self.assertEqual(after[k], before[k], k)
        worst = 0.0
        prev = after
        for _ in range(30 * 20):
            s.vx = s.vy = a.vx = a.vy = 0.0
            f = sim.step(DT)
            c = cloud_json(f, s.id)
            worst = max(worst, abs(c["w"] - prev["w"]), abs(c["h"] - prev["h"]))
            prev = c
        self.assertLess(worst, 4.0)
        self.assertEqual(prev["merge"], 1.0)


class TestMergeInTheSandbox(unittest.TestCase):
    def test_default_world_merges_now_and_then_and_never_clumps(self):
        sim = Simulation(1, 3840, 1080, 30)
        merges = 0
        counts = []
        for _ in range(30 * 300):
            f = sim.step(DT)
            merges += sum(1 for e in f["events"] if "absorbed" in e)
            counts.append(len(f["clouds"]))
        self.assertGreaterEqual(merges, 1, "in five sim minutes of a default world something merges")
        self.assertLessEqual(merges, 12, "but clouds do not all clump together")
        self.assertGreaterEqual(min(counts[60:]), 5)

    def test_every_merge_leaves_a_consistent_record(self):
        sim = Simulation(3, 3840, 1080, 30)
        absorbed_ids = set()
        merged_into = {}
        for _ in range(30 * 400):
            f = sim.step(DT)
            for c in f["clouds"]:
                if "parts" in c:
                    self.assertLessEqual(len(c["parts"]), 3)
                    self.assertLessEqual(len(c["puffs"]), C.MERGED_MAX_PUFFS)
                    self.assertEqual(len(c["puff_part"]), len(c["puffs"]))
                    self.assertTrue(0.0 <= c["merge"] <= 1.0)
                    for part in c["parts"]:
                        absorbed_ids.add(part["id"])
                else:
                    self.assertLessEqual(len(c["puffs"]), C.SUPERCELL_MAX_PUFFS if c["type"] == "supercell" else C.MAX_PUFFS)
                    self.assertNotIn("puff_part", c)
            for d in f["deaths"]:
                if "merged_into" in d:
                    merged_into[d["id"]] = d["merged_into"]
        self.assertTrue(merged_into, "this seed merges at least once")
        self.assertTrue(set(merged_into) <= absorbed_ids)
        self.assertEqual(sim.clouds_born - sim.clouds_dead, len(sim.clouds))

    def test_showcase_never_merges(self):
        from showcase import ShowcaseSimulation
        sim = ShowcaseSimulation(4, 1920, 1080, 30, "cumulus")
        self.assertFalse(sim.merging)

    def test_no_puff_jumps_over_a_long_run(self):
        sim = Simulation(7, 3840, 1080, 30)
        prev = {}
        worst = 0.0
        for _ in range(5000):
            f = sim.step(DT)
            cur = {}
            for c in f["clouds"]:
                cur[c["id"]] = c["puffs"]
                p = prev.get(c["id"])
                if p is not None and len(p) <= len(c["puffs"]):
                    for a, b in zip(p, c["puffs"]):
                        worst = max(worst, abs(b[2] - a[2]))
            prev = cur
        self.assertLess(worst, 8.0, f"largest radius change in one tick {worst:.2f}px")


class TestMergeSaveLoad(unittest.TestCase):
    def test_save_load_through_a_merge_is_frame_identical(self):
        def build():
            sim, a, b = scene(seed=11, a_type="cumulus", b_type="stratus")
            sim.merging = False
            s, ab = C.merge_survivor(a, b)
            C.begin_merge(s, ab, sim.world.t, [])
            return sim
        ref = build()
        cut = build()
        for _ in range(45):                       # into the middle of the merge
            ref.step(DT)
            cut.step(DT)
        s = next(c for c in cut.clouds if c.mg is not None)
        self.assertTrue(0.05 < s.merge < 0.95, s.merge)
        state = json.loads(json.dumps(cut.to_state()))
        loaded = Simulation.from_state(state)
        loaded.merging = False
        for i in range(400):
            fa, fb = ref.step(DT), loaded.step(DT)
            if i == 0:                    # a loaded world resends the ground strip on its first frame
                fa.pop("ground", None)
                fa.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fa.pop("flood", None)
                fa.pop("flow", None)
                fb.pop("ground", None)
                fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fb.pop("flood", None)
                fb.pop("flow", None)
            self.assertEqual(json.dumps(fa, sort_keys=True), json.dumps(fb, sort_keys=True),
                             f"frame {i} differs after load")

    def test_save_load_with_the_merge_detector_running_is_identical(self):
        a = Simulation(3, 3840, 1080, 30)
        b_state = None
        for _ in range(900):
            a.step(DT)
        b = Simulation.from_state(json.loads(json.dumps(a.to_state())))
        for i in range(900):
            fa, fb = a.step(DT), b.step(DT)
            if i == 0:
                fa.pop("ground", None)
                fa.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fa.pop("flood", None)
                fa.pop("flow", None)
                fb.pop("ground", None)
                fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fb.pop("flood", None)
                fb.pop("flow", None)
            self.assertEqual(json.dumps(fa, sort_keys=True), json.dumps(fb, sort_keys=True), f"frame {i}")


if __name__ == "__main__":
    unittest.main()
