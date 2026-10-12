"""Cloud model v2: smooth condensation and evaporation (no steps in form / w / h / opacity / puff
radius), clouds born and dying as invisible vapour, per-type skeletons, anvil, shear, cirrus never
rains, and the initial clouds are internally consistent."""
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_clouds.py` (or sim.tests.test_clouds) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
from clouds import MAX_PUFFS, MIN_PUFFS, SUPERCELL_MAX_PUFFS, TYPE_NAMES, spawn_cloud, update_cloud
from simulation import Simulation

DT = 1.0 / 30


def lone_cloud(ctype, seed=1, w=1920, h=900):
    """A Simulation with its own clouds removed and one cloud of the given type to drive by hand."""
    sim = Simulation(seed=seed, w=w, h=h, fps=30)
    sim.clouds.clear()
    c = spawn_cloud(500, seed, 500, w * 0.5, None, 0.0, sim.signatures, world_w=w, world_h=h,
                    terrain=sim.terrain, cloud_type=ctype)
    return sim, c


def run_cloud(sim, c, max_frames=20000):
    """Step the cloud alone to its death, yielding the cloud after every update."""
    events = []
    t = 0.0
    for _ in range(max_frames):
        t += DT
        sim.world.t = t
        wind = sim._build_wind()
        alive = update_cloud(c, DT, sim.world, wind, sim.terrain, t, events)
        yield alive
        if not alive:
            return
    raise AssertionError("cloud never died")


class TestCloudSmoothness(unittest.TestCase):
    def test_no_steps_over_a_long_run(self):
        sim = Simulation(seed=5, w=1920, h=900, fps=30)
        prev = {}
        worst = {"form": 0.0, "w": 0.0, "h": 0.0, "opacity": 0.0, "r": 0.0, "darkness": 0.0}
        births, deaths = [], []
        types = set()
        for f in range(5000):
            msg = sim.step(DT)
            cur = {c["id"]: c for c in msg["clouds"]}
            for cid, c in cur.items():
                types.add(c["type"])
                p = prev.get(cid)
                if p is None:
                    if f > 0:
                        births.append(c)
                    continue
                for k in ("form", "w", "h", "opacity", "darkness"):
                    worst[k] = max(worst[k], abs(c[k] - p[k]))
                # stable order; the only change allowed is appending r~0 puffs (a cumulus towering)
                self.assertGreaterEqual(len(c["puffs"]), len(p["puffs"]), "puffs are never dropped")
                for a, b in zip(c["puffs"], p["puffs"]):
                    worst["r"] = max(worst["r"], abs(a[2] - b[2]))
                for a in c["puffs"][len(p["puffs"]):]:
                    worst["r"] = max(worst["r"], a[2])
            for cid, p in prev.items():
                if cid not in cur:
                    deaths.append(p)
            prev = cur
        self.assertLess(worst["form"], 0.02, worst)
        self.assertLess(worst["opacity"], 0.02, worst)
        self.assertLess(worst["darkness"], 0.02, worst)
        self.assertLess(worst["w"], 8.0, worst)
        self.assertLess(worst["h"], 4.0, worst)
        self.assertLess(worst["r"], 2.5, worst)     # px per frame, largest puffs are ~100 px
        self.assertGreater(len(births), 8)       # (the climate-driven spawner is calmer than the v2.2 timer)
        self.assertGreater(len(deaths), 5, "clouds die and are removed")
        self.assertGreaterEqual(len(types), 7)
        for c in births:
            self.assertLess(c["form"], 0.1, "born as vapour")
            self.assertLess(c["opacity"], 0.1)
            self.assertLess(c["w"], 30.0)
        for c in deaths:
            self.assertLess(c["form"], 0.1, f"{c['name']} vanished while still visible")
            self.assertEqual(c["stage"], "dissipating")

    def test_every_type_condenses_peaks_and_evaporates_smoothly(self):
        for ctype in TYPE_NAMES:
            sim, c = lone_cloud(ctype, seed=3)
            last_form = c.form
            peak = 0.0
            forms = []
            for alive in run_cloud(sim, c):
                if not alive:
                    break
                self.assertLess(abs(c.form - last_form), 0.02, ctype)
                last_form = c.form
                peak = max(peak, c.form)
                forms.append(c.form)
            self.assertGreater(peak, 0.95, f"{ctype} should reach a solid body")
            self.assertLess(last_form, 0.05, f"{ctype} must evaporate back to ~0 before removal")
            self.assertEqual(forms[0] < 0.05, True)
            # form is unimodal: rises, plateaus near 1, falls (never bounces)
            i = forms.index(max(forms))
            self.assertTrue(all(forms[k] <= forms[k + 1] + 1e-9 for k in range(0, i)), ctype)
            tail = forms[forms.index(max(forms)):]
            hi = [k for k, v in enumerate(tail) if v > 0.9]
            self.assertTrue(all(tail[k] >= tail[k + 1] - 1e-9 for k in range(hi[-1], len(tail) - 1)), ctype)

    def test_puffs_are_a_smooth_function_of_time_never_popping_in_at_size(self):
        for ctype in ("cumulus", "cumulonimbus", "stratus", "cirrus"):
            sim, c = lone_cloud(ctype, seed=8)
            prev = None
            for alive in run_cloud(sim, c):
                if not alive:
                    break
                cur = [p[2] for p in c.puffs]
                if prev is not None:
                    for a, b in zip(cur, prev):
                        self.assertLess(abs(a - b), 2.5, f"{ctype}: puff radius jumped {b} -> {a}")
                prev = cur

    def test_a_forced_cumulus_to_cumulonimbus_morph_has_no_steps(self):
        """Same smoothness bounds as every other cloud, across the switch frame and the whole morph."""
        import clouds
        from unittest import mock
        with mock.patch.object(clouds, "TOWER_HAZARD", 1e9):
            for seed in (1, 2, 3):
                sim, c = lone_cloud("cumulus", seed=seed, w=1920, h=900)
                prev = None
                worst = {"form": 0.0, "w": 0.0, "h": 0.0, "opacity": 0.0, "darkness": 0.0, "r": 0.0}
                switched = False
                for alive in run_cloud(sim, c):
                    if not alive:
                        break
                    cur = Simulation._cloud_to_protocol(c)
                    if prev is not None:
                        switched = switched or cur["type"] != prev["type"]
                        for k in ("form", "w", "h", "opacity", "darkness"):
                            worst[k] = max(worst[k], abs(cur[k] - prev[k]))
                        self.assertGreaterEqual(len(cur["puffs"]), len(prev["puffs"]))
                        for a, b in zip(cur["puffs"], prev["puffs"]):
                            worst["r"] = max(worst["r"], abs(a[2] - b[2]))
                        for a in cur["puffs"][len(prev["puffs"]):]:
                            worst["r"] = max(worst["r"], a[2])      # appended puffs start at r ~ 0
                    prev = cur
                self.assertTrue(switched, f"seed {seed}: the cumulus should have towered")
                self.assertLess(worst["form"], 0.02, worst)
                self.assertLess(worst["opacity"], 0.02, worst)
                self.assertLess(worst["darkness"], 0.02, worst)
                self.assertLess(worst["w"], 8.0, worst)
                self.assertLess(worst["h"], 4.0, worst)
                self.assertLess(worst["r"], 2.5, worst)

    def test_spawned_cloud_is_vapour(self):
        sim = Simulation(seed=4, w=1280, h=720, fps=30)
        sim.handle_command({"cmd": "spawn", "x": 400, "y": 250}, [])
        c = sim.clouds[-1]
        self.assertEqual((c.form, c.w, c.h, c.opacity, c.darkness), (0.0, 0.0, 0.0, 0.0, 0.0))
        self.assertTrue(all(p[2] == 0.0 for p in c.puffs))
        msg = sim.step(DT)
        cd = next(x for x in msg["clouds"] if x["id"] == c.id)
        self.assertLess(cd["form"], 0.01)
        self.assertEqual(cd["stage"], "forming")

    def test_initial_clouds_are_internally_consistent(self):
        for seed in (1, 2, 3, 4, 5, 6):
            sim = Simulation(seed=seed, w=3840, h=1080, fps=30)
            for c in sim.clouds:
                frac = c.age / c.lifetime
                self.assertLessEqual(frac, 0.41)
                if c.stage == "mature":
                    self.assertGreater(c.form, 0.84, f"{c.name} is mature but only {c.form:.2f} formed")
                if c.stage == "precipitating":
                    self.assertGreater(c.form, 0.92, f"{c.name} rains but only {c.form:.2f} formed")
                if c.stage == "forming":
                    self.assertLess(c.age / c.lifetime, 0.2)
                if c.stage == "precipitating":
                    self.assertGreater(c.water + c.ice, 0.0)
                self.assertEqual(c.stage_times["forming"], 0.0)
                for stage, t in c.stage_times.items():
                    self.assertEqual(t, 0.0, f"{c.name} {stage} started at {t}")
                self.assertLessEqual(c.opacity, c.form + 1e-9 if c.form < 0.5 else 1.0)


class TestCloudSkeletons(unittest.TestCase):
    def test_puff_counts_and_ranges_for_every_type(self):
        for ctype in TYPE_NAMES:
            for seed in range(1, 25):
                _sim, c = lone_cloud(ctype, seed=seed, w=1280, h=720)
                cap = SUPERCELL_MAX_PUFFS if ctype == "supercell" else MAX_PUFFS
                self.assertTrue(MIN_PUFFS <= len(c.layout) <= cap, f"{ctype} seed {seed}: {len(c.layout)}")
                for p in c.full_puffs():
                    self.assertGreater(p[2], 0.0)
                    self.assertTrue(0.0 <= p[3] <= 1.0)
                for pf in c.layout:
                    d, a = pf[2], pf[1]
                    self.assertTrue(0.0 <= a and a + d <= 1.0 + 1e-9, "growth window inside 0..1")
                    self.assertTrue(pf[7] < len(c.layout))

    def test_parents_precede_children(self):
        for ctype in TYPE_NAMES:
            _sim, c = lone_cloud(ctype, seed=5)
            for i, pf in enumerate(c.layout):
                self.assertLess(pf[7], i)

    def test_shapes_differ_by_type(self):
        def aspect(ctype):
            vals = []
            for seed in range(1, 13):
                _sim, c = lone_cloud(ctype, seed=seed, w=3840, h=1080)
                vals.append(c.base_w / c.base_h)
            return sum(vals) / len(vals)

        a = {t: aspect(t) for t in TYPE_NAMES}
        self.assertLess(a["cumulonimbus"], a["cumulus"], "cumulonimbus is a tall tower")
        self.assertLess(a["cumulus"], a["stratus"])
        for t in ("stratus", "altostratus", "altocumulus", "cirrus"):
            self.assertGreater(a[t], 2.8, f"{t} is a long flat sheet / streak field")
        self.assertGreater(a["cirrus"], 4.0)

    def test_cumulus_has_a_flat_base_and_a_dome(self):
        for seed in range(1, 10):
            _sim, c = lone_cloud("cumulus", seed=seed, w=3840, h=1080)
            pf = c.full_puffs()
            bottoms = sorted(p[1] + p[2] for p in pf)
            lowest = bottoms[-1]
            near_base = sum(1 for b in bottoms if lowest - b < 0.15 * c.base_h)
            self.assertGreaterEqual(near_base, 3, "several puffs share the flat base")
            tops = min(p[1] - p[2] for p in pf)
            self.assertLess(tops, lowest - 0.7 * c.base_h)

    def test_cumulonimbus_has_a_wide_base_tower_and_anvil(self):
        for seed in range(1, 10):
            _sim, c = lone_cloud("cumulonimbus", seed=seed, w=3840, h=1080)
            anvil = [p for p in c.layout if p[0] == 1]
            body = [p for p in c.layout if p[0] == 0]
            self.assertGreaterEqual(len(anvil), 5)
            self.assertGreater(c.base_w, c.body_w * 1.05, "anvil widens the cloud")
            tops = [p for p in body if p[4] < -0.5 * c.base_h]
            self.assertGreaterEqual(len(tops), 3, "stacked turrets")
            self.assertTrue(all(p[3] * 0 + p[4] <= -0.6 * c.base_h for p in anvil if p[1] >= 0.0))

    def test_anvil_and_shear(self):
        sim, cb = lone_cloud("cumulonimbus", seed=2)
        peak = 0.0
        last = 0.0
        shear_peak = 0.0
        for alive in run_cloud(sim, cb):
            if not alive:
                break
            self.assertLess(abs(cb.anvil - last), 0.02)
            last = cb.anvil
            peak = max(peak, cb.anvil)
            shear_peak = max(shear_peak, abs(cb.shear))
        self.assertGreater(peak, 0.6, "the anvil spreads at the top")
        self.assertLess(last, 0.05, "and recedes")
        self.assertGreater(shear_peak, 3.0, "tops lean with wind shear")
        import clouds
        from unittest import mock
        with mock.patch.object(clouds, "TOWER_HAZARD", 0.0):      # a cumulus that does not tower
            sim, cu = lone_cloud("cumulus", seed=2)
            for alive in run_cloud(sim, cu):
                if not alive:
                    break
                self.assertEqual(cu.anvil, 0.0)

    def test_shear_leans_the_tops_downwind(self):
        from clouds import eval_layout
        _sim, c = lone_cloud("cumulus", seed=4)
        flat, _ = eval_layout(c.layout, 1.0, 0.0, 0.0, 0.0, c.core)
        leaned, _ = eval_layout(c.layout, 1.0, 0.0, 30.0, 0.0, c.core)
        top = max(range(len(flat)), key=lambda i: -flat[i][1])
        base = max(range(len(flat)), key=lambda i: flat[i][1])
        self.assertGreater(leaned[top][0] - flat[top][0], 10.0)
        self.assertLess(abs(leaned[base][0] - flat[base][0]), leaned[top][0] - flat[top][0])
        # negative shear leans the other way
        left, _ = eval_layout(c.layout, 1.0, 0.0, -30.0, 0.0, c.core)
        self.assertLess(left[top][0] - flat[top][0], -10.0)

    def test_clouds_ride_over_high_ground(self):
        sim = Simulation(seed=2, w=3840, h=1080, fps=30, terrain_kind="mountainous")
        for c in sim.clouds:
            half = c.base_w * 0.35
            surf = min(sim.terrain.height_at(c.ax + d) for d in (-half, 0, half))
            self.assertLessEqual(c.ay, surf - 39.0, f"{c.name} base is inside the mountain")


class TestPrecipitationRules(unittest.TestCase):
    def test_cirrus_and_altocumulus_never_precipitate(self):
        """They are always fair (v2.1): they never even enter the precipitating stage."""
        for ctype in ("cirrus", "altocumulus"):
            sim, c = lone_cloud(ctype, seed=6)
            stages = set()
            for alive in run_cloud(sim, c):
                if not alive:
                    break
                stages.add(c.stage)
                self.assertEqual(c.precip_type, "none")
                self.assertEqual(c.precip_rate, 0.0)
                self.assertEqual(c.total_precip_mm, 0.0)
            self.assertNotIn("precipitating", stages, "fair clouds go mature -> dissipating")
            self.assertTrue({"forming", "growing", "mature", "dissipating"} <= stages, stages)

    def test_cirrus_is_high_icy_and_thin(self):
        for seed in range(1, 8):
            sim, c = lone_cloud("cirrus", seed=seed, w=1920, h=900)
            self.assertLess(c.ay, 0.3 * 900, "cirrus lives in the upper sky")
            self.assertGreater(c.ice_capacity, 3 * c.water_capacity, "icy")
            self.assertLessEqual(c.op_max, 0.55, "thin")
        sim = Simulation(seed=3, w=3840, h=1080, fps=30)
        for _ in range(3):
            sim.handle_command({"cmd": "spawn", "x": 1000, "y": 90}, [])
        for _ in range(800):
            msg = sim.step(DT)
            for cl in msg["clouds"]:
                if cl["type"] == "cirrus":
                    self.assertEqual(cl["precip_type"], "none")

    def test_altitude_bands_use_the_taller_sky(self):
        by_type = {}
        for ctype in TYPE_NAMES:
            ys = []
            for seed in range(1, 15):
                _sim, c = lone_cloud(ctype, seed=seed, w=3840, h=1080)
                ys.append(c.ay)
            by_type[ctype] = sum(ys) / len(ys)
        self.assertLess(by_type["cirrus"], by_type["altostratus"])
        self.assertLess(by_type["altostratus"], by_type["cumulus"])
        self.assertLess(by_type["altocumulus"], by_type["stratocumulus"])
        self.assertLess(by_type["cirrus"], 0.25 * 1080)
        self.assertGreater(by_type["cumulus"], 0.3 * 1080)

    def test_full_frames_stay_valid_while_clouds_change(self):
        sim = Simulation(seed=12, w=1920, h=900, fps=30)
        ids = set(range(1, len(sim.objects.objs) + 1))
        for _ in range(1500):
            S.validate_frame(sim.step(DT), 1920, 900, ids)


if __name__ == "__main__":
    unittest.main()
