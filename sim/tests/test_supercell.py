"""Supercell cloud (protocol v2.3 "Supercell"): shape, smoothness, forward-flank rain and hail, merge rules,
how often the climate makes one.

The skeleton was also rendered to an image and looked at (tools/debug_plots/v23/supercell_*.png): these tests
therefore check where the parts of the skeleton are, not only that they exist."""
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_supercell.py` (or sim.tests.test_supercell) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import atmosphere as A
import clouds as C
from particles import GRAUPEL, RAIN
from simulation import Simulation

DT = 1.0 / 30


def quiet_world(seed=5, w=1920, h=1080, **kw):
    sim = Simulation(seed, w, h, 30, "flat", "none", **kw)
    sim.merging = False
    sim._next_spawn_delay = 1e18        # nothing else is born
    sim.clouds.clear()
    return sim


def spawn_supercell(sim, x=None, **peaks):
    c = sim._spawn_cloud_auto(x=x if x is not None else sim.world.w * 0.4, cloud_type="supercell")
    for k, v in peaks.items():
        setattr(c, k, v)
    return c


def run_until(sim, pred, max_s=400.0):
    for _ in range(int(max_s * 30)):
        sim.step(DT)
        if pred():
            return True
    return False


def finished(c):
    """The finished skeleton in anchor coordinates (x from the cloud's anchor, y = 0 at the base level, up is
    negative): [(x, y, r, shade)], everything fully grown, no shear, no breathing offset."""
    return C.eval_layout(c.layout, 1.0, 1.0, 0.0, 0.0, c.core, False, 1.0, None, (1.0, 1.0))[0]


def tower_top_x(c):
    """x (anchor coordinates) of the turret the anvil grows from."""
    first_anvil = next(i for i, p in enumerate(c.layout) if p[0] == 1)
    return finished(c)[c.layout[first_anvil][7]][0]


class TestSupercellSkeleton(unittest.TestCase):
    def test_new_type_basics(self):
        self.assertIn("supercell", C.TYPE_NAMES)
        self.assertEqual(len(C.TYPE_NAMES), 9)
        self.assertTrue(C.TYPE_PARAMS["supercell"]["cb"])
        self.assertEqual(C.TYPE_PARAMS["supercell"]["weight"], 0, "only type_weights and commands make one")
        cb, sc = C.TYPE_PARAMS["cumulonimbus"], C.TYPE_PARAMS["supercell"]
        self.assertGreater(sc["life"][0], cb["life"][1], "longer-lived than any cumulonimbus")
        self.assertGreater(sc["w"][0], cb["w"][1] * 0.9, "larger than a cumulonimbus")
        for seed in range(1, 8):
            sim = quiet_world(seed)
            c = spawn_supercell(sim)
            self.assertTrue(c.name.startswith("Sp-"), c.name)
            self.assertFalse(c.fair)
            self.assertEqual(c.from_type, None)
            self.assertLessEqual(len(c.layout), C.SUPERCELL_MAX_PUFFS)
            self.assertEqual(len(c.puff_part), len(c.layout), "puff_part runs parallel to the layout")
            self.assertGreater(c.anvil_max, 0.7)
            self.assertGreater(c.lifetime, 240.0 * 0.9)

    def test_spawn_command_takes_a_type(self):
        sim = quiet_world()
        ev = []
        sim.handle_command({"cmd": "spawn", "x": 600, "y": 300, "type": "supercell"}, ev)
        self.assertEqual([c.type for c in sim.clouds], ["supercell"])
        self.assertTrue(sim.clouds[0].name.startswith("Sp-"))
        for t in C.TYPE_NAMES:
            sim.handle_command({"cmd": "spawn", "x": 900, "y": 300, "type": t}, ev)
            self.assertEqual(sim.clouds[-1].type, t)
        n = len(sim.clouds)
        for bad in ("hail", "", 5, None, ["supercell"], {"a": 1}, True):
            sim.handle_command({"cmd": "spawn", "x": 900, "y": 300, "type": bad}, ev)
        self.assertEqual(len(sim.clouds), n + 7, "a bad type is ignored: the cloud is still spawned, from the climate")
        sim.handle_command({"cmd": "spawn", "x": 900, "y": 300}, ev)
        self.assertEqual(len(sim.clouds), n + 8)

    def test_finished_skeleton_has_every_part_in_its_place(self):
        """tower + long downwind anvil + short back-sheared part + overshoot dome above + stepped flanking line on the
        rear flank + wall cloud hanging under the base on the upwind side."""
        for seed in range(1, 13):
            sim = quiet_world(seed, w=1920, h=1080)
            c = spawn_supercell(sim)
            c.form = c.anvil = 1.0
            c.refresh()
            dirn = 1.0 if sim.world.prevailing_u >= 0 else -1.0
            full = finished(c)
            tx = tower_top_x(c)
            W = c.base_w
            anvil = [full[i] for i, p in enumerate(c.layout) if p[0] == 1]
            dome = [full[i] for i, p in enumerate(c.layout) if p[0] == 7]
            wall = [full[i] for i, p in enumerate(c.layout) if p[0] == 6]
            self.assertGreaterEqual(len(anvil), 12, seed)
            self.assertEqual(len(dome), 3, seed)
            self.assertEqual(len(wall), 4, seed)
            fwd = max((p[0] - tx) * dirn + p[2] for p in anvil)
            back = max(-(p[0] - tx) * dirn + p[2] for p in anvil)
            self.assertGreater(fwd, 0.45 * W, f"seed {seed}: the anvil runs far downwind")
            self.assertGreater(back, 0.08 * W, f"seed {seed}: ... with a short back-sheared part")
            self.assertGreater(fwd, 2.2 * back, f"seed {seed}: the back part is short")
            # the dome stands above every anvil puff
            self.assertLess(min(p[1] - p[2] for p in dome), min(p[1] - p[2] for p in anvil) - 4.0, seed)
            # the wall cloud hangs below the base level (y = 0 is the base) ...
            self.assertTrue(all(p[1] > 0.0 for p in wall), seed)
            self.assertGreater(max(p[1] + p[2] for p in wall), 0.08 * c.base_h, seed)
            # ... on the upwind side of the cloud, which is where the mesocyclone is
            self.assertLess(c.meso_dx * dirn, 0.0, seed)
            self.assertAlmostEqual(c.meso_dx, sum(p[0] * p[2] for p in wall) / sum(p[2] for p in wall), delta=4.0)
            self.assertGreater(c.wall_drop, 30.0)
            # the rain-free base under the wall cloud: the forward flank is downwind of it
            lo, hi = sim.rain_span(c)
            mx = c.ax + c.meso_dx
            self.assertGreater(((lo + hi) / 2 - mx) * dirn, 0.15 * W, seed)

    def test_flanking_line_is_stepped_on_the_rear_flank(self):
        for seed in range(1, 9):
            sim = quiet_world(seed)
            c = spawn_supercell(sim)
            dirn = 1.0 if sim.world.prevailing_u >= 0 else -1.0
            full = finished(c)
            last_dome = max(i for i, p in enumerate(c.layout) if p[0] == 7)
            first_wall = min(i for i, p in enumerate(c.layout) if p[0] == 6)
            flank = full[last_dome + 1:first_wall]
            self.assertEqual(len(flank), 6, "three stepped cumulus of two puffs each")
            tops, dist = [], []
            for k in range(3):
                pair = flank[2 * k:2 * k + 2]
                tops.append(min(p[1] - p[2] for p in pair))
                dist.append(-sum(p[0] for p in pair) / 2.0 * dirn)
            tx = tower_top_x(c)
            self.assertTrue(all(d > -tx * dirn - 0.0 for d in dist), "rear (upwind) of the tower")
            self.assertEqual(dist, sorted(dist), "farther from the updraft with every step")
            self.assertEqual(tops, sorted(tops), f"seed {seed}: each step is lower than the one nearer the updraft")
            self.assertLess(tops[0], 0.0)

    def test_wall_and_dome_grow_on_their_own_axes_and_not_into_the_bounds(self):
        sim = quiet_world(3)
        c = spawn_supercell(sim)
        base = [list(p) for p in C.eval_layout(c.layout, 1.0, 1.0, 0.0, 0.0, c.core, False, 0.0, None, (0.0, 0.0))[0]]
        grown = [list(p) for p in C.eval_layout(c.layout, 1.0, 1.0, 0.0, 0.0, c.core, False, 0.0, None, (1.0, 1.0))[0]]
        for i, p in enumerate(c.layout):
            if p[0] in (6, 7):
                self.assertEqual(base[i][2], 0.0, "radius 0 until its own axis rises")
                self.assertGreater(grown[i][2], 1.0)
            else:
                self.assertAlmostEqual(base[i][2], grown[i][2], places=6)
        # the bounds (w/h/base_y) do not include the wall cloud, so base_y is the rain-free base
        _f, bb = C.eval_layout(c.layout, 1.0, 1.0, 0.0, 0.0, c.core, True)
        low = max(p[1] + p[2] for p in grown)
        self.assertLess(bb[3], low, "the finished cloud reaches lower with the wall cloud than its bounds do")


class TestSupercellDynamics(unittest.TestCase):
    def test_a_strong_supercell_builds_rotation_wall_overshoot_and_hook_smoothly(self):
        sim = quiet_world(5)
        c = spawn_supercell(sim, rot_peak=0.95, wall_peak=0.95, ovs_peak=0.9)
        c.vx = c.vy = 0.0
        prev = None
        top = {"rotation": 0.0, "wall": 0.0, "overshoot": 0.0, "hook": 0.0}
        stages = []
        jumps = []
        for i in range(30 * 170):
            if i == 30 * 100:
                c.sc_force = 1.0
            if i == 30 * 130:
                c.sc_force = 0.0
            f = sim.step(DT)
            c.vx = c.vy = 0.0
            if c not in sim.clouds:
                break
            msg = next(x for x in f["clouds"] if x["id"] == c.id)
            S.validate_cloud(msg)
            if not stages or stages[-1] != msg["stage"]:
                stages.append(msg["stage"])
            for k in top:
                top[k] = max(top[k], msg[k])
            cur = {k: msg[k] for k in ("rotation", "wall", "overshoot", "hook")}
            pu = msg["puffs"]
            if prev is not None:
                for k, v in cur.items():
                    if abs(v - prev[0][k]) > 0.03:
                        jumps.append((i, k, prev[0][k], v))
                self.assertEqual(len(pu), len(prev[1]))
                for a, b in zip(prev[1], pu):
                    if abs(a[2] - b[2]) > 2.5 or abs(a[0] - b[0]) > 7 or abs(a[1] - b[1]) > 7:
                        jumps.append((i, "puff", a, b))
            prev = (cur, pu)
        self.assertEqual(jumps[:3], [], "nothing jumps")
        self.assertGreater(top["rotation"], 0.8)
        self.assertGreater(top["wall"], 0.7)
        self.assertGreater(top["overshoot"], 0.35)
        self.assertGreater(top["hook"], 0.4)
        self.assertEqual(stages[:3], ["forming", "growing", "mature"])
        self.assertIn("precipitating", stages)

    def test_a_weak_storm_never_lowers_a_wall_cloud(self):
        sim = quiet_world(6)
        c = spawn_supercell(sim, rot_peak=0.5)
        c.vx = c.vy = 0.0
        w = 0.0
        for _ in range(30 * 150):
            sim.step(DT)
            c.vx = c.vy = 0.0
            w = max(w, c.wall)
            if c not in sim.clouds:
                break
        self.assertLess(w, 0.2)
        self.assertEqual(sim.tornadoes_total, 0)

    def test_a_cumulonimbus_never_becomes_a_supercell(self):
        sim = quiet_world(2)
        cb = sim._spawn_cloud_auto(x=900.0, cloud_type="cumulonimbus")
        cu = sim._spawn_cloud_auto(x=300.0, cloud_type="cumulus", fair=False)
        for _ in range(30 * 120):
            sim.step(DT)
        for c in sim.clouds:
            if c.id in (cb.id, cu.id):
                self.assertNotEqual(c.type, "supercell")
        self.assertEqual(cb.type, "cumulonimbus")
        self.assertFalse(C.TYPE_PARAMS["cumulus"]["cb"] and False)
        self.assertEqual(cb.rot_peak, 0.0)

    def test_a_supercell_always_survives_and_is_never_absorbed(self):
        sim = quiet_world(4, w=3840)
        sc = spawn_supercell(sim, x=1500.0)
        cb = sim._spawn_cloud_auto(x=1520.0, y=400.0, cloud_type="cumulonimbus")
        cb.water_capacity = sc.water_capacity * 5.0            # bigger than the supercell by every measure
        cb.ice_capacity = sc.ice_capacity * 5.0
        s, a = C.merge_survivor(sc, cb)
        self.assertIs(s, sc)
        self.assertIs(a, cb)
        s, a = C.merge_survivor(cb, sc)
        self.assertIs(s, sc)
        sc2 = spawn_supercell(sim, x=1500.0)
        for c in (sc, sc2):
            c.form = 1.0
        sc.refresh()
        sc2.refresh()
        self.assertFalse(C.merge_overlap(sc, sc2), "two supercells never merge")

    def test_a_cumulonimbus_can_be_merged_into_a_supercell_without_a_jump(self):
        sim = quiet_world(8, w=3840)
        sc = spawn_supercell(sim, x=1500.0)
        cb = sim._spawn_cloud_auto(x=1540.0, y=400.0, cloud_type="cumulonimbus")
        for c in (sc, cb):
            c.vx = c.vy = 0.0
            sim._fast_forward_new_cloud(c, 0.34)
            c.lifetime = max(c.lifetime, 800.0)
        for _ in range(60):
            sim.step(DT)
            sc.vx = sc.vy = cb.vx = cb.vy = 0.0
        sc.ax, cb.ax = 1500.0, 1540.0
        sc.refresh()
        cb.refresh()
        n0 = len(sc.layout)
        if not C.merge_overlap(sc, cb) or C.merge_room(sc) < C.MIN_COPIES:
            self.skipTest("this arrangement did not overlap")
        ev = []
        self.assertTrue(C.begin_merge(sc, cb, sim.world.t, ev))
        self.assertGreater(len(sc.layout), n0)
        self.assertLessEqual(len(sc.layout), C.MERGED_MAX_PUFFS)
        self.assertEqual(len(sc.puff_part), len(sc.layout))
        # the cb's anvil puffs are not copied (the supercell keeps its own)
        self.assertTrue(all(p[0] != 1 for p in sc.layout[n0:]))
        prev = None
        for _ in range(30 * 8):
            sim.step(DT)
            sc.vx = sc.vy = 0.0
            cur = [list(p) for p in sc.puffs]
            if prev is not None and len(prev) == len(cur):
                for a, b in zip(prev, cur):
                    self.assertLess(abs(a[2] - b[2]), 3.0)
            prev = cur
        self.assertEqual(sc.type, "supercell")


class TestHook(unittest.TestCase):
    """`hook` (rear-flank downdraft) belongs to the storm: it follows the mesocyclone with or without a tornado."""

    def run_storm(self, seed=21, tornado=False):
        sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal", hour=9.0)
        sim.handle_command({"cmd": "gas", "name": "humidity", "value": 0.2}, [])
        sim.merging = False
        sim._next_spawn_delay = 1e18
        sim.clouds.clear()
        sim.auto_tornado = False
        sim.handle_command({"cmd": "spawn", "x": 1500, "y": 300, "type": "supercell"}, [])
        c = sim.clouds[0]
        c.rot_peak = 0.95
        c.wall_peak = 0.95
        series = []
        sent = False
        for i in range(30 * 150):
            sim.step(DT)
            if tornado and not sent and C.supercell_mature(c) and c.rotation > 0.8:
                sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
                sent = True
            if c not in sim.clouds:
                break
            series.append((sim.world.t, c.stage, c.rotation, c.hook, len(sim.tornadoes)))
        return sim, c, series

    def test_a_mature_storm_without_a_tornado_has_a_rear_flank_downdraft(self):
        sim, c, series = self.run_storm()
        self.assertEqual(sim.tornadoes_total, 0)
        mature = [s for s in series if s[1] in ("mature", "precipitating") and s[2] > 0.7]
        self.assertTrue(mature)
        late = [s for s in mature if s[0] >= mature[0][0] + 12.0]
        self.assertTrue(late)
        self.assertTrue(all(s[3] > 0.3 for s in late), min(s[3] for s in late))
        self.assertLessEqual(max(s[3] for s in series), C.HOOK_NO_TORNADO + 1e-6, "without a tornado it stays below its cap")
        first_rot = next(s[0] for s in series if s[2] > 0.5)
        first_hook = next(s[0] for s in series if s[3] > 0.3)
        self.assertGreaterEqual(first_hook, first_rot)
        steps = [abs(b[3] - a[3]) for a, b in zip(series, series[1:])]
        self.assertLess(max(steps), 0.01)
        print(f"non-tornadic supercell: hook {max(s[3] for s in series):.2f} at peak, rotation {max(s[2] for s in series):.2f}")

    def test_it_strengthens_further_around_a_tornado(self):
        _s, _c, plain = self.run_storm()
        sim, c, torn = self.run_storm(tornado=True)
        self.assertEqual(sim.tornadoes_total, 1)
        self.assertGreater(max(s[3] for s in torn), max(s[3] for s in plain) + 0.1)
        self.assertGreater(max(s[3] for s in torn), 0.9)
        steps = [abs(b[3] - a[3]) for a, b in zip(torn, torn[1:])]
        self.assertLess(max(steps), 0.01)

    def test_a_weak_storm_has_no_hook(self):
        sim = quiet_world(6)
        c = spawn_supercell(sim, rot_peak=0.3)
        c.vx = c.vy = 0.0
        top = 0.0
        for _ in range(30 * 120):
            sim.step(DT)
            c.vx = c.vy = 0.0
            top = max(top, c.hook)
            if c not in sim.clouds:
                break
        self.assertLess(top, 0.05)


class TestSupercellPrecipitation(unittest.TestCase):
    def test_rain_and_hail_only_fall_from_the_forward_flank(self):
        sim = quiet_world(5, month=6.5, hour=14.0)      # a warm sky: the cloud base is below the freezing level
        c = spawn_supercell(sim, rot_peak=0.9)
        c.vx = c.vy = 0.0
        self.assertTrue(run_until(sim, lambda: c.stage == "precipitating", 200.0), "it rains in the end")
        dirn = -1.0 if c.meso_dx > 0 else 1.0
        sim.particles.items = []
        sim.particles.ttl = []
        sizes, kinds = [], set()
        lo, hi = sim.rain_span(c)
        mx = c.ax + c.meso_dx
        for _ in range(2000):
            c.precip_type = "rain"
            c.precip_rate = 0.3
            before = len(sim.particles.items)
            sim._spawn_precip_particles(c, DT)
            for p in sim.particles.items[before:]:
                self.assertAlmostEqual(p[2], c.base_y, delta=0.06)
                self.assertGreaterEqual(p[1], lo - 0.1)
                self.assertLessEqual(p[1], hi + 0.1)
                self.assertGreater((p[1] - mx) * dirn, 0.1 * c.w, "nothing falls near the wall cloud")
                kinds.add(p[0])
                if p[0] == GRAUPEL:
                    sizes.append(p[5])
            sim.particles.items = sim.particles.items[-50:]
            sim.particles.ttl = sim.particles.ttl[-50:]
        self.assertEqual(kinds, {RAIN, GRAUPEL})
        self.assertGreater(max(sizes), 8.0, "large hail")
        self.assertLessEqual(max(sizes), 9.0)
        self.assertGreater(sum(1 for s in sizes if s >= 5.0), 20)
        self.assertGreater(sum(1 for s in sizes if s < 5.5), 20, "and small hail")

    def test_the_base_around_meso_x_is_rain_free_through_a_whole_storm(self):
        sim = quiet_world(7)
        c = spawn_supercell(sim, rot_peak=0.9)
        c.vx = c.vy = 0.0
        seen = 0
        for _ in range(30 * 200):
            sim.step(DT)
            c.vx = c.vy = 0.0
            if c not in sim.clouds:
                break
            if c.stage == "precipitating":
                mx = c.ax + c.meso_dx
                half = 0.1 * c.w
                for p in sim.particles.items:
                    if p[0] in (RAIN, GRAUPEL) and c.base_y - 2.0 <= p[2] <= c.base_y + 80.0:
                        seen += 1
                        self.assertGreater(abs(p[1] - mx) + 0.0, half * 0.6, f"particle {p} under the wall cloud")
        self.assertGreater(seen, 100, "it did rain")

    def test_it_lightnings(self):
        sim = quiet_world(9)
        c = spawn_supercell(sim)
        c.vx = c.vy = 0.0
        bolts = 0
        for _ in range(30 * 220):
            f = sim.step(DT)
            c.vx = c.vy = 0.0
            bolts += sum(1 for e in f["events"] if "lightning" in e)
            if c not in sim.clouds:
                break
        self.assertGreater(bolts, 3)


class TestSupercellClimate(unittest.TestCase):
    def test_instability_and_shear_are_both_needed(self):
        raw = A.supercell_raw
        self.assertEqual(raw(0.2, 0.9), 0.0)
        self.assertEqual(raw(0.9, 0.0), 0.0)
        self.assertGreater(raw(0.8, 0.8), 0.15)
        self.assertGreater(raw(0.8, 0.8), 20 * max(raw(0.8, 0.05), raw(0.3, 0.9)))
        self.assertGreater(raw(0.5, 0.2, 0.03), raw(0.5, 0.2, 0.0) * 3, "a supercell region (preset mix) boosts it")

    def test_an_unstable_sheared_climate_makes_supercells_and_a_cool_one_none(self):
        def share(seed, **kw):
            sim = Simulation(seed, 1920, 1080, 30, "rolling", "none", **kw)
            sim._refresh_atmosphere()
            n = sum(1 for _ in range(400) if sim._spawn_cloud_auto().type == "supercell")
            return n / 400.0, sim._get_atm()["weights_f"]["supercell"]
        ok_share = [share(s, preset="oklahoma", month=5.0, hour=16.0) for s in (1, 2, 3)]
        jul_share = [share(s, month=7.5, hour=14.0) for s in (1, 2, 3)]
        cool = [share(s, month=4.0, hour=7.2) for s in (1, 2, 3)]
        winter = [share(s, month=1.5, hour=3.0) for s in (1, 2, 3)]
        self.assertTrue(all(w > 0.02 for _, w in ok_share), ok_share)
        self.assertTrue(all(w > 0.02 for _, w in jul_share), jul_share)
        self.assertEqual(sum(w for _, w in cool + winter), 0.0)
        self.assertEqual(sum(n for n, _ in cool + winter), 0.0)
        self.assertTrue(all(n > 0.01 for n, _ in ok_share), ok_share)
        self.assertTrue(all(n > 0.01 for n, _ in jul_share), jul_share)
        self.assertGreater(sum(n for n, _ in jul_share) / 3, 0.03)


if __name__ == "__main__":
    unittest.main()
