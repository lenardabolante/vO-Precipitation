"""Cloud uniqueness: the shape-signature set has no collisions across many
spawns, and no two clouds in a run share identical puffs+type+tint."""
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_uniqueness.py` (or sim.tests.test_uniqueness) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
from clouds import spawn_cloud
from simulation import Simulation


class TestUniqueness(unittest.TestCase):
    def test_many_direct_spawns_have_distinct_signatures(self):
        signatures = set()
        clouds = []
        for i in range(400):
            c = spawn_cloud(cloud_id=i, world_seed=2026, counter=i, x=100.0, y=100.0,
                             world_t=0.0, signatures=signatures)
            clouds.append(c)
        # Every cloud's own (type, size-bucket, tint-bucket, puff-fingerprint)
        # fingerprint must be distinct -- that IS the contents of `signatures`.
        self.assertEqual(len(signatures), len(clouds))

        # Cross-check with a directly-recomputed fingerprint of the finished skeleton (a newborn
        # cloud is vapour, so its live `puffs` are all r = 0) to make sure the registry actually
        # reflects what got stored on each cloud.
        seen = set()
        for c in clouds:
            fp = (c.type, tuple(sorted(tuple(p) for p in c.full_puffs())), tuple(c.tint))
            self.assertNotIn(fp, seen, f"duplicate cloud silhouette for {c.name}")
            seen.add(fp)

    def test_cloud_names_and_seeds_distinct_within_a_run(self):
        sim = Simulation(seed=2026, w=1280, h=720, fps=30)
        seen_ids = set()
        seen_names = set()
        seen_seeds = set()
        dt = 1.0 / 30
        for _ in range(2500):
            sim.step(dt)
            for c in sim.clouds:
                seen_ids.add(c.id)
                seen_names.add(c.name)
                seen_seeds.add(c.seed)
        self.assertGreater(len(seen_names), 5)
        self.assertEqual(len(seen_names), len(seen_ids))
        self.assertEqual(len(seen_seeds), len(seen_ids))

    def test_two_worlds_with_different_seeds_diverge(self):
        sim_a = Simulation(seed=1, w=1280, h=720, fps=30)
        sim_b = Simulation(seed=2, w=1280, h=720, fps=30)
        names_a = {c.name for c in sim_a.clouds}
        names_b = {c.name for c in sim_b.clouds}
        # Overwhelmingly unlikely to coincide across two independently seeded worlds.
        self.assertNotEqual(names_a, names_b)


if __name__ == "__main__":
    unittest.main()
