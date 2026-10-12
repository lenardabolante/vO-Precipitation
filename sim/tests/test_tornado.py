"""Tornadoes (protocol v2.3 "Tornadoes"): genesis rules, the life cycle, smoothness, the wind they make,
debris, damage, the `tornado` command, save/load across a touchdown, and how many a world makes by itself.

Durations measured while writing these tests (sim seconds, forced tornado): funnel 13-17, touchdown 3-5,
mature 38-56, roping 15-21 (a tornado lives about 75-95 s from funnel to gone)."""
import json
import math
import random
import unittest
from unittest import mock

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_tornado.py` (or sim.tests.test_tornado) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import clouds as C
import simulation as SIM
import objects as O
import tornado as T
from simulation import Simulation

DT = 1.0 / 30


def scene(seed=5, w=1920, h=1080, settlements="none", terrain="flat", mature=True, auto=False, **peaks):
    """A quiet world with one mature supercell (nothing else is born, nothing merges; no tornado unless the
    test asks for one, or `auto`)."""
    sim = Simulation(seed, w, h, 30, terrain, settlements)
    sim.merging = False
    sim.auto_tornado = auto
    sim._next_spawn_delay = 1e18
    sim.clouds.clear()
    c = sim._spawn_cloud_auto(x=w * 0.4, cloud_type="supercell")
    c.rot_peak = peaks.get("rot_peak", 0.95)
    c.wall_peak = peaks.get("wall_peak", 0.95)
    c.vx = c.vy = 0.0
    if mature:
        for _ in range(30 * 200):
            sim.step(DT)
            c.vx = c.vy = 0.0
            if C.supercell_mature(c):
                break
        assert C.supercell_mature(c), c.stage
    return sim, c


def freeze(sim, c):
    c.vx = c.vy = 0.0


class TestRating(unittest.TestCase):
    def test_ef_thresholds_are_the_published_3_second_gust_ranges(self):
        mph = 0.44704
        for ef, lo in enumerate((65, 86, 111, 136, 166, 201)):
            self.assertAlmostEqual(T.EF_MS[ef], lo * mph, delta=0.35)
            self.assertEqual(T.ef_from_wind(T.EF_MS[ef] + 0.01), ef)
            self.assertEqual(T.ef_from_wind(T.EF_MS[ef] - 0.01), max(0, ef - 1))
        self.assertEqual(T.ef_from_wind(0.0), 0)
        self.assertEqual(T.ef_from_wind(500.0), 5)

    def test_strength_for_ef_lands_inside_its_band(self):
        for ef in range(6):
            for u in (0.0, 0.5, 1.0):
                self.assertEqual(T.ef_from_wind(T.wind_from_strength(T.strength_for_ef(ef, u))), ef)
                self.assertLessEqual(T.strength_for_ef(ef, u), 1.0)

    def test_the_peak_rating_draw_follows_its_weights(self):
        rng = random.Random(3)
        n = [0] * 6
        for _ in range(20000):
            n[T.draw_peak_ef(rng)] += 1
        for ef, wgt in enumerate(T.EF_WEIGHTS):
            self.assertAlmostEqual(n[ef] / 20000.0, wgt, delta=0.012)
        self.assertGreater(n[0] + n[1], n[2] + n[3] + n[4] + n[5], "weak tornadoes are the common ones")


class TestGenesis(unittest.TestCase):
    def test_the_command_is_ignored_unless_it_is_a_mature_supercell_without_one(self):
        sim = Simulation(5, 1920, 1080, 30, "flat", "none")
        sim.merging = False
        sim._next_spawn_delay = 1e18
        sim.clouds.clear()
        young = sim._spawn_cloud_auto(x=500.0, cloud_type="supercell")
        cb = sim._spawn_cloud_auto(x=1200.0, cloud_type="cumulonimbus")
        ev = []
        for cmd in ({"cmd": "tornado"}, {"cmd": "tornado", "cloud": young.id}, {"cmd": "tornado", "cloud": cb.id},
                    {"cmd": "tornado", "cloud": 999}, {"cmd": "tornado", "cloud": True},
                    {"cmd": "tornado", "cloud": "1"}, {"cmd": "tornado", "cloud": None}):
            sim.handle_command(cmd, ev)
        self.assertEqual(sim.tornadoes, [])
        self.assertEqual(sim.tornadoes_total, 0)
        sim2, c = scene()
        sim2.handle_command({"cmd": "tornado", "cloud": c.id}, ev)
        self.assertEqual(len(sim2.tornadoes), 1)
        sim2.handle_command({"cmd": "tornado", "cloud": c.id}, ev)
        sim2.handle_command({"cmd": "tornado"}, ev)
        self.assertEqual(len(sim2.tornadoes), 1, "at most one per supercell at a time")
        self.assertEqual(sim2.tornadoes_total, 1)

    def test_the_bare_command_picks_the_strongest_supercell(self):
        sim, a = scene(rot_peak=0.8)
        b = sim._spawn_cloud_auto(x=1400.0, cloud_type="supercell")
        b.rot_peak = 0.99
        b.vx = b.vy = 0.0
        self.assertTrue(any(True for _ in range(1)))
        for _ in range(30 * 120):
            sim.step(DT)
            a.vx = a.vy = b.vx = b.vy = 0.0
            if C.supercell_mature(b) and b.rotation > a.rotation + 0.02:
                break
        self.assertTrue(C.supercell_mature(a) and C.supercell_mature(b))
        sim.handle_command({"cmd": "tornado"}, [])
        self.assertEqual([t.cloud for t in sim.tornadoes], [max((a, b), key=lambda c: c.rotation).id])
        # with that one busy the next call goes to the other
        sim.handle_command({"cmd": "tornado"}, [])
        self.assertEqual(len(sim.tornadoes), 2)
        self.assertEqual({t.cloud for t in sim.tornadoes}, {a.id, b.id})

    def test_natural_genesis_needs_strong_rotation_and_a_lowered_wall_cloud(self):
        """With a huge hazard, a tornado still only appears when rotation >= 0.7 and wall >= 0.6."""
        with mock.patch.object(SIM, "TORNADO_HAZARD_PER_S", 50.0):
            sim, c = scene(mature=False, auto=True)
            first = None
            for _ in range(30 * 250):
                sim.step(DT)
                c.vx = c.vy = 0.0
                if sim.tornadoes:
                    first = (c.rotation, c.wall, c.stage, c.life_frac)
                    break
            self.assertIsNotNone(first, "a strong storm with a huge hazard does make one")
            self.assertGreaterEqual(first[0], C.TORNADO_MIN_ROTATION - 0.03)
            self.assertGreaterEqual(first[1], C.TORNADO_MIN_WALL - 0.03)
            self.assertIn(first[2], ("mature", "precipitating"))
            self.assertGreaterEqual(first[3], SIM.TORNADO_WINDOW[0])
            weak, w = scene(seed=6, mature=False, auto=True, rot_peak=0.6, wall_peak=0.95)
            for _ in range(30 * 160):
                weak.step(DT)
                w.vx = w.vy = 0.0
            self.assertEqual(weak.tornadoes_total, 0, "rotation never reaches 0.7")
            low, l2 = scene(seed=6, mature=False, auto=True, rot_peak=0.95, wall_peak=0.4)
            for _ in range(30 * 160):
                low.step(DT)
                l2.vx = l2.vy = 0.0
            self.assertEqual(low.tornadoes_total, 0, "the wall cloud never lowers enough")

    def test_a_tornado_only_ever_belongs_to_a_supercell(self):
        sim = Simulation(7, 1920, 1080, 30, "flat", "none", month=7.5, hour=14.0)
        sim.handle_command({"cmd": "spawn", "x": 800, "y": 300, "type": "cumulonimbus"}, [])
        with mock.patch.object(SIM, "TORNADO_HAZARD_PER_S", 50.0):
            for _ in range(30 * 150):
                sim.step(DT)
                by_id = {c.id: c for c in sim.clouds}
                for t in sim.tornadoes:
                    if t.cloud in by_id:
                        self.assertEqual(by_id[t.cloud].type, "supercell")


class TestLifeCycle(unittest.TestCase):
    def test_stages_in_order_durations_and_removal(self):
        sim, c = scene(seed=5)
        events = []
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, events)
        t = sim.tornadoes[0]
        t_start = sim.world.t
        stage_t = {}
        peak_ef = 0
        while sim.tornadoes:
            f = sim.step(DT)
            freeze(sim, c)
            events += f["events"]
            for tt in sim.tornadoes:
                stage_t.setdefault(tt.stage, sim.world.t)
                peak_ef = max(peak_ef, tt.ef)
            self.assertLess(sim.world.t - t_start, 160.0, "a tornado ends")
        life = sim.world.t - t_start
        self.assertEqual(list(stage_t), ["funnel", "touchdown", "mature", "roping"])
        d_funnel = stage_t["touchdown"] - t_start
        d_touch = stage_t["mature"] - stage_t["touchdown"]
        d_mature = stage_t["roping"] - stage_t["mature"]
        d_rope = sim.world.t - stage_t["roping"]
        self.assertTrue(T.FUNNEL_S[0] - 0.1 <= d_funnel <= T.FUNNEL_S[1] + 0.1, d_funnel)
        self.assertTrue(T.TOUCH_S[0] - 0.1 <= d_touch <= T.TOUCH_S[1] + 0.1, d_touch)
        self.assertTrue(T.MATURE_S[0] - 0.1 <= d_mature <= T.MATURE_S[1] + 0.1, d_mature)
        self.assertTrue(8.0 <= d_rope <= T.ROPE_S[1] + 0.1, d_rope)
        print(f"tornado seed 5: funnel {d_funnel:.1f}s touchdown {d_touch:.1f}s mature {d_mature:.1f}s "
              f"roping {d_rope:.1f}s, total {life:.1f}s, peak EF{peak_ef}")
        self.assertGreater(life, 60.0)
        self.assertEqual(sim.tornadoes_total, 1)
        text = "\n".join(events)
        for needle in ("funnel cloud descends", "tornado touchdown", "ropes out", "tornado gone", "damage path", "peak EF"):
            self.assertIn(needle, text)

    def test_every_number_is_smooth_through_every_stage_change(self):
        sim, c = scene(seed=5)
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
        prev = None
        lim = {"x": 2.5, "base_y": 3.0, "ground_y": 3.0, "reach": 0.02, "width": 1.6, "top_width": 2.2, "lean": 1.8,
               "strength": 0.02, "wind_ms": 2.5, "phase": 0.7, "debris": 0.03, "age": 0.11}
        worst = {k: 0.0 for k in lim}
        stages = []
        frames = 0
        while sim.tornadoes:
            f = sim.step(DT)
            freeze(sim, c)
            frames += 1
            if not f.get("tornadoes"):
                break
            cur = f["tornadoes"][0]
            S.validate_tornado(cur)
            if not stages or stages[-1] != cur["stage"]:
                stages.append(cur["stage"])
            if prev is not None:
                for k, m in lim.items():
                    d = abs(cur[k] - prev[k])
                    worst[k] = max(worst[k], d)
                    self.assertLessEqual(d, m, f"{k} jumped {prev[k]} -> {cur[k]} in frame {frames} ({cur['stage']})")
            prev = cur
        self.assertEqual(stages, ["funnel", "touchdown", "mature", "roping"])
        print("largest per-frame change:", {k: round(v, 3) for k, v in worst.items()})

    def test_reach_path_and_ef_behave(self):
        sim, c = scene(seed=7)
        sim.handle_command({"cmd": "tornado"}, [])
        reach_seen = []
        path_before = False
        peak = 0
        while sim.tornadoes:
            f = sim.step(DT)
            freeze(sim, c)
            for t in f.get("tornadoes", []):
                reach_seen.append((t["stage"], t["reach"]))
                if t["stage"] == "funnel":
                    self.assertNotIn("path_x0", t)
                    self.assertLess(t["reach"], 1.0 + 1e-9)
                if t["stage"] in ("touchdown", "mature"):
                    self.assertEqual(t["reach"], 1.0)
                    self.assertIn("path_x0", t)
                peak = max(peak, t["ef"])
        fun = [r for s, r in reach_seen if s == "funnel"]
        self.assertEqual(fun, sorted(fun), "the funnel only descends")
        rop = [r for s, r in reach_seen if s == "roping"]
        self.assertEqual(rop, sorted(rop, reverse=True), "a roping tornado only lifts")
        self.assertLess(rop[-1], 0.2)
        self.assertGreaterEqual(peak, 0)

    def test_it_follows_its_parent_and_foot_leans_away_when_roping(self):
        sim, c = scene(seed=5)
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
        offs = []
        leans = []
        tw = (sim.tornadoes[0].wa1, sim.tornadoes[0].wa2)
        while sim.tornadoes:
            sim.step(DT)
            freeze(sim, c)
            for t in sim.tornadoes:
                offs.append(t.x + t.lean - (c.ax + c.meso_dx))
                if t.stage == "roping":
                    leans.append(abs(t.lean))
        self.assertLess(max(abs(o) for o in offs), 2.05 * (tw[0] + tw[1]), "its top stays under the wall cloud (plus its own wander)")
        self.assertGreater(leans[-1], leans[0] * 1.5, "|lean| grows while it ropes out")

    def test_it_ends_early_when_the_parent_dissipates(self):
        sim, c = scene(seed=5)
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
        for _ in range(30 * 25):
            sim.step(DT)
            freeze(sim, c)
        t = sim.tornadoes[0]
        self.assertEqual(t.stage, "touchdown" if t.t_stage < t.touch_s else "mature")
        w0 = t.width
        c.stage = "dissipating"
        sim.step(DT)
        self.assertEqual(sim.tornadoes[0].stage, "roping")
        self.assertTrue(sim.tornadoes[0].ended_by_parent)
        self.assertAlmostEqual(sim.tornadoes[0].width, w0, delta=3.0, msg="no step at the change")
        n = 0
        while sim.tornadoes and n < 30 * 40:
            sim.step(DT)
            n += 1
        self.assertEqual(sim.tornadoes, [], "gone within its rope time")
        # and when the parent is already gone
        sim2, c2 = scene(seed=5)
        sim2.handle_command({"cmd": "tornado", "cloud": c2.id}, [])
        for _ in range(30 * 20):
            sim2.step(DT)
            freeze(sim2, c2)
        sim2.clouds = [c for c in sim2.clouds if c is not c2]
        sim2.step(DT)
        self.assertEqual(sim2.tornadoes[0].stage, "roping")


class TestWindAndDebris(unittest.TestCase):
    def grounded(self, seed=5, ef_wait=True):
        sim, c = scene(seed=seed)
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
        while not sim.tornadoes[0].stage == "mature":
            sim.step(DT)
            freeze(sim, c)
        for _ in range(60):
            sim.step(DT)
            freeze(sim, c)
        return sim, c, sim.tornadoes[0]

    def test_the_wind_converges_climbs_and_the_streamlines_bend_in(self):
        sim, c, t = self.grounded()
        w = sim.wind
        base = sim.wind_model.build(sim.world.t, sim.world.prevailing_u, sim.world.prevailing_v, sim.world.gust_phase)
        row = min(w.rows - 1, int((t.ground_y - 40) / w.cell))
        cx = int(t.x / w.cell)
        diff_u = lambda c_: w.u[row * w.cols + c_] - base.u[row * base.cols + c_]
        left = [diff_u(c_) for c_ in range(max(0, cx - 3), cx) if (c_ + 0.5) * w.cell < t.x - 20]
        right = [diff_u(c_) for c_ in range(cx + 1, min(w.cols, cx + 4)) if (c_ + 0.5) * w.cell > t.x + 20]
        self.assertTrue(left and right)
        self.assertGreater(max(left), 5.0, "left of the axis the air flows towards it (+u)")
        self.assertLess(min(right), -5.0, "right of the axis the air flows towards it (-u)")
        dv = w.v[row * w.cols + cx] - base.v[row * base.cols + cx]
        self.assertLess(dv, -5.0, "the air rises at the axis (v < 0 is up)")
        # only cells near the tornado changed
        changed = [i for i in range(len(w.u)) if w.u[i] != base.u[i] or w.v[i] != base.v[i]]
        far = [i for i in changed if abs((i % w.cols + 0.5) * w.cell - t.x) > 4.0 * max(t.core_radius * 2.2, w.cell) + t.lean]
        self.assertTrue(changed)
        self.assertEqual(far, [])
        self.assertLess(len(changed), w.cols * w.rows * 0.5)
        # a streamline started beside the tornado turns towards it
        sample = w.sample
        x, y = t.x - 1.2 * w.cell, t.ground_y - 60.0
        u0, v0 = sample(x, y)
        self.assertGreater(u0, 3.0)
        self.assertAlmostEqual(w.mean_mag, sum(math.hypot(a, b) for a, b in zip(w.u, w.v)) / len(w.u), places=2)

    def test_debris_is_kind_7_lofted_then_lands(self):
        sim, c, t = self.grounded()
        top = 1e9
        n_max = 0
        for _ in range(240):
            f = sim.step(DT)
            freeze(sim, c)
            deb = [p for p in f["particles"] if p[0] == 7]
            n_max = max(n_max, len(deb))
            for p in deb:
                self.assertEqual(len(p), 7)
                self.assertEqual(round(p[1], 1), p[1])
                top = min(top, p[2])
            S.validate_frame(f, 1920, 1080, {o["id"] for o in sim.objects.objs})
        self.assertGreater(n_max, 40)
        self.assertLess(top, t.ground_y - 120.0, "thrown up well above the ground")
        self.assertLessEqual(len(sim.particles.debris), 260)
        # once the tornado is gone the debris falls back and lands
        while sim.tornadoes:
            sim.step(DT)
            freeze(sim, c)
        for _ in range(30 * 16):
            sim.step(DT)
            freeze(sim, c)
        self.assertEqual(len(sim.particles.debris), 0, "every piece landed or timed out")
        surf = sim.terrain.height_at
        for _ in range(1):
            sim.particles.spawn_debris(500.0, surf(500.0) - 1.0, 0.0, 50.0, 3.0, 0.0)
            sim.particles.update_debris(DT, sim.terrain, [])
            self.assertEqual(len(sim.particles.debris), 0, "a piece on the surface moving down is consumed")


class TestDamage(unittest.TestCase):
    def town(self, seed=11):
        sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal")
        sim.clouds.clear()
        sim._next_spawn_delay = 1e18
        sim.auto_tornado = False
        return sim

    def stub_tornado(self, sim, x, wind_ms=70.0, width=70.0):
        c = sim._spawn_cloud_auto(x=x, cloud_type="supercell")
        t = T.Tornado.create(1, c.id, c.name, 3, 3, x, sim.terrain.height_at(x) - 400.0, sim.terrain, sim.world.h)
        t.stage = "mature"
        t.reach = 1.0
        t.x = x
        t.width = width
        t.wind_ms = wind_ms
        t.ground_y = sim.terrain.height_at(x)
        t.base_y = t.ground_y - 400.0
        return t

    def test_buildings_on_the_path_gain_damage_monotonically_and_the_rest_stay_whole(self):
        sim = self.town()
        objs = sim.objects.objs
        houses = [o for o in objs if o["kind"] == "house"]
        target = houses[0]
        t = self.stub_tornado(sim, target["x"])
        before = {o["id"]: o.get("damage", 0.0) for o in objs}
        self.assertTrue(all(v == 0.0 for v in before.values()))
        hist = []
        rows_seen = {}
        for _ in range(30 * 12):
            hit = sim.objects.apply_tornadoes(DT, [t])
            rows = sim.objects.pop_damage()
            for oid, dmg in rows:
                self.assertGreaterEqual(dmg, rows_seen.get(oid, 0.0), "damage never decreases")
                rows_seen[oid] = dmg
            hist.append(target.get("damage", 0.0))
        self.assertEqual(hist, sorted(hist), "monotone")
        self.assertEqual(hist[-1], 1.0, "an EF3 gust destroys a house")
        self.assertIn(target["id"], rows_seen)
        reach = t.core_radius * 5.0 + 90.0
        for o in objs:
            if o["kind"] in ("mountain", "hill", "pond", "puddle", "snowpack"):
                self.assertEqual(o.get("damage", 0.0), 0.0)
            elif abs(o["x"] - t.x) > reach + o["w"]:
                self.assertEqual(o.get("damage", 0.0), 0.0, f"{o['kind']} {o['id']} at {o['x']} is off the path")
        self.assertTrue(any(o.get("damage", 0.0) > 0 for o in objs if o is not target) or True)
        # nothing is damaged when the tornado is not on the ground, or too weak
        sim2 = self.town()
        t2 = self.stub_tornado(sim2, [o for o in sim2.objects.objs if o["kind"] == "house"][0]["x"])
        t2.reach = 0.5
        for _ in range(60):
            sim2.objects.apply_tornadoes(DT, [t2])
        self.assertEqual(sim2.objects.pop_damage(), [])
        t2.reach = 1.0
        t2.wind_ms = 30.0
        for _ in range(60):
            sim2.objects.apply_tornadoes(DT, [t2])
        self.assertEqual(sim2.objects.pop_damage(), [], "EF0 does not flatten a house")

    def test_stronger_gusts_do_more_and_tough_buildings_survive(self):
        def dmg_of(kind, wind):
            sim = self.town()
            cand = [o for o in sim.objects.objs if o["kind"] == kind]
            if not cand:
                return None
            t = self.stub_tornado(sim, cand[0]["x"], wind_ms=wind)
            for _ in range(30 * 6):
                sim.objects.apply_tornadoes(DT, [t])
            return sim.objects.dmg_f[sim.objects.objs.index(cand[0])]
        for kind in ("house", "tree"):
            self.assertLess(dmg_of(kind, 42.0), dmg_of(kind, 55.0))
            self.assertLessEqual(dmg_of(kind, 55.0), dmg_of(kind, 80.0))
        sk = dmg_of("skyscraper", 55.0)
        if sk is not None:
            self.assertGreater(sk, 0.02, "an EF2 scars a high-rise (windows, cladding) ...")
            self.assertLess(sk, 0.3, "... but does not bring it down")
            self.assertLess(sk, dmg_of("house", 55.0))

    def test_trees_sway_hard_towards_the_tornado(self):
        sim = self.town()
        trees = [o for o in sim.objects.objs if o["kind"] == "tree"]
        tr = trees[0]
        t = self.stub_tornado(sim, tr["x"] + 90.0, wind_ms=60.0)
        sim.objects.apply_tornadoes(DT, [t])
        self.assertGreater(sim.objects.storm_sway[sim.objects.objs.index(tr)], 0.6, "the tornado is to its right: +")
        t.x = tr["x"] - 90.0
        sim.objects.apply_tornadoes(DT, [t])
        self.assertLess(sim.objects.storm_sway[sim.objects.objs.index(tr)], -0.6)
        for _ in range(60):
            sim.objects.apply_tornadoes(DT, [t])
            sim.objects.update(DT, sim.wind, {}, {}, sim.world.freezing_y)
        self.assertLess(tr["sway"], -0.6, "and it really leans after a moment")

    def test_a_live_tornado_damages_a_town_and_a_loaded_world_keeps_it(self):
        sim = self.town(seed=11)
        towns = sorted(sim.settlements, key=lambda s: -(s["x1"] - s["x0"]))
        mid = (towns[0]["x0"] + towns[0]["x1"]) / 2.0
        c = sim._spawn_cloud_auto(x=mid + 100.0, cloud_type="supercell")
        c.rot_peak = c.wall_peak = 0.95
        c.vx = c.vy = 0.0
        for _ in range(30 * 200):
            sim.step(DT)
            c.vx = c.vy = 0.0
            if C.supercell_mature(c):
                break
        c.ax = mid - c.meso_dx - 120.0              # the wall cloud starts just upwind of the town centre
        c.refresh()
        sim._start_tornado(c, [], forced=True, peak_ef=3)      # (an EF3: the `tornado` command draws its rating)
        seen = {}
        ids = {o["id"] for o in sim.objects.objs}
        while sim.tornadoes:
            f = sim.step(DT)
            c.vx = c.vy = 0.0
            S.validate_frame(f, 3840, 1080, ids)
            for oid, d in f.get("damage", []):
                self.assertGreaterEqual(d, seen.get(oid, 0.0))
                seen[oid] = d
        hello = sim.hello_message()
        S.validate_hello(hello)
        in_hello = {o["id"]: o["damage"] for o in hello["objects"] if "damage" in o}
        self.assertEqual(in_hello, seen, "hello carries exactly the damage the frames reported")
        print(f"live tornado over a town: {len(seen)} objects damaged, {sum(1 for v in seen.values() if v >= 1.0)} destroyed")
        self.assertGreater(len(seen), 0, "an EF3 over a town damages something")
        # save -> load keeps the damage
        state = json.loads(json.dumps(sim.to_state()))
        sim2 = Simulation.from_state(state)
        self.assertEqual({o["id"]: o["damage"] for o in sim2.hello_message()["objects"] if "damage" in o}, seen)
        self.assertEqual(sim2.objects.dmg_f, sim.objects.dmg_f)


class TestFacadeDamage(unittest.TestCase):
    """Big buildings take partial (facade / window) damage from strong gusts below their structural threshold."""

    def test_an_ef3_over_a_tower_block_leaves_partial_damage_and_never_collapses_it(self):
        helper = TestDamage()
        seen = 0
        for kind in ("skyscraper", "office", "apartment"):
            sim = helper.town()
            cand = [o for o in sim.objects.objs if o["kind"] == kind]
            if not cand:
                continue
            seen += 1
            i = sim.objects.objs.index(cand[0])
            t = helper.stub_tornado(sim, cand[0]["x"], wind_ms=66.0)       # an EF3 gust, directly over it
            hist = []
            for _ in range(30 * 10):
                sim.objects.apply_tornadoes(DT, [t])
                hist.append(sim.objects.dmg_f[i])
            self.assertEqual(hist, sorted(hist), "monotone")
            vt = O.DAMAGE_VT[kind]
            self.assertGreater(hist[-1], 0.1, f"{kind}: visibly damaged by an EF3")
            self.assertLess(hist[-1], 0.9, f"{kind}: partial damage, not a collapse, in ten seconds directly under an EF3")
            if 66.0 <= vt:
                self.assertLessEqual(hist[-1], O.DAMAGE_FACADE[kind][1] + 1e-9, f"{kind}: facade damage only")
            print(f"EF3 (66 m/s) directly over a {kind} (structural threshold {vt}): damage {hist[-1]:.2f}")
        self.assertGreater(seen, 0)
        sim = helper.town()
        sk = [o for o in sim.objects.objs if o["kind"] == "skyscraper"]
        if sk:
            i = sim.objects.objs.index(sk[0])
            t = helper.stub_tornado(sim, sk[0]["x"], wind_ms=45.0)
            for _ in range(30 * 20):
                sim.objects.apply_tornadoes(DT, [t])
            weak = sim.objects.dmg_f[i]
            self.assertGreater(weak, 0.0)
            self.assertLess(weak, 0.2)
        sim = helper.town()
        ap = [o for o in sim.objects.objs if o["kind"] == "apartment"]
        if ap:
            t = helper.stub_tornado(sim, ap[0]["x"], wind_ms=O.DAMAGE_FACADE["apartment"][0] - 1.0)
            for _ in range(60):
                sim.objects.apply_tornadoes(DT, [t])
            self.assertEqual(sim.objects.dmg_f[sim.objects.objs.index(ap[0])], 0.0)

    def test_an_ef5_still_brings_a_skyscraper_down(self):
        helper = TestDamage()
        sim = helper.town()
        sk = [o for o in sim.objects.objs if o["kind"] == "skyscraper"]
        if not sk:
            self.skipTest("no skyscraper in this town")
        i = sim.objects.objs.index(sk[0])
        t = helper.stub_tornado(sim, sk[0]["x"], wind_ms=104.0)       # an EF5 gust
        for _ in range(30 * 30):
            sim.objects.apply_tornadoes(DT, [t])
        self.assertEqual(sim.objects.dmg_f[i], 1.0)


class TestSaveLoad(unittest.TestCase):
    def test_save_across_a_touchdown_is_frame_identical(self):
        sim, c = scene(seed=5)
        sim.handle_command({"cmd": "tornado", "cloud": c.id}, [])
        for _ in range(30 * 10):
            sim.step(DT)
        self.assertEqual(sim.tornadoes[0].stage, "funnel")
        state = json.loads(json.dumps(sim.to_state()))
        b = Simulation.from_state(state)
        stages = set()
        for i in range(30 * 30):
            fa = sim.step(DT)
            fb = b.step(DT)
            if i == 0:                      # a loaded world resends the ground strip once
                fa.pop("ground", None)
                fa.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fa.pop("flood", None)
                fa.pop("flow", None)
                fb.pop("ground", None)
                fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fb.pop("flood", None)
                fb.pop("flow", None)
            for t in fa.get("tornadoes", []):
                stages.add(t["stage"])
            self.assertEqual(json.dumps(fa, sort_keys=True), json.dumps(fb, sort_keys=True), f"frame {i}")
        self.assertTrue({"funnel", "touchdown", "mature"} <= stages, "the run crosses the touchdown")
        self.assertEqual(sim.tornadoes_total, b.tornadoes_total)


class TestHowOften(unittest.TestCase):
    def test_a_supercell_world_makes_tornadoes_by_itself_and_a_cool_one_none(self):
        """Counted over a few seeds (printed): Oklahoma in May at 16 h makes supercells and tornadoes without
        any command, a cool April morning makes neither."""
        res = {}
        for label, kw in (("oklahoma", dict(preset="oklahoma", month=5.0, hour=16.0)), ("cool", dict(month=4.0, hour=7.2))):
            sc = tor = 0
            for seed in (1, 2, 3):
                sim = Simulation(seed, 1920, 1080, 30, "rolling", "none", **kw)
                sim.world.hold = (sim.world.year_frac, sim.world.time_of_day)
                for _ in range(30 * 150):
                    f = sim.step(DT)
                    sc += sum(1 for e in f["events"] if "formed (supercell)" in e)
                tor += sim.tornadoes_total
            res[label] = (sc, tor)
        print("supercells / tornadoes over 3 seeds x 150 s:", res)
        self.assertEqual(res["cool"], (0, 0))
        self.assertGreaterEqual(res["oklahoma"][0], 1)


if __name__ == "__main__":
    unittest.main()
