"""Particle kinds 1 (rain), 2 (snow) and 3 (ice crystal) all appear, and the
freezing-level physics is respected: ice crystals overwhelmingly sample above
world.freezing_y (smaller y = colder/higher), while raindrops overwhelmingly
sample below it."""
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_particle_kinds.py` (or sim.tests.test_particle_kinds) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
from simulation import Simulation

RAIN, SNOW, ICE = 1, 2, 3


class TestParticleKinds(unittest.TestCase):
    def _run(self, seed, temp_override=None, frames=2500):
        sim = Simulation(seed=seed, w=1280, h=720, fps=30)
        if temp_override is not None:
            sim.world.surface_temp_c = temp_override
        kinds_seen = set()
        ice_samples = []  # (y, freezing_y)
        rain_samples = []
        dt = 1.0 / 30
        for _ in range(frames):
            msg = sim.step(dt)
            fy = msg["world"]["freezing_y"]
            for p in msg["particles"]:
                kind, x, y = p[0], p[1], p[2]
                kinds_seen.add(kind)
                if kind == ICE:
                    ice_samples.append((y, fy))
                elif kind == RAIN:
                    rain_samples.append((y, fy))
        return kinds_seen, ice_samples, rain_samples

    def test_rain_snow_ice_all_appear_default_conditions(self):
        kinds_seen, ice_samples, rain_samples = self._run(seed=7)
        self.assertIn(RAIN, kinds_seen)
        self.assertIn(SNOW, kinds_seen)
        self.assertIn(ICE, kinds_seen)

    def test_ice_crystals_form_above_freezing_level(self):
        # Force a cold world so ice/snow are common and well sampled.
        _, ice_samples, _ = self._run(seed=11, temp_override=-15.0)
        self.assertGreater(len(ice_samples), 20, "not enough ice-crystal samples to judge")
        above = sum(1 for y, fy in ice_samples if y < fy)
        # Ice crystals are spawned strictly above the freezing line and only
        # converted away the tick after they cross it, so an overwhelming
        # majority of samples must be above (allow a small tail for the
        # in-flight conversion frame).
        self.assertGreaterEqual(above / len(ice_samples), 0.95)

    def test_rain_forms_at_or_below_freezing_level(self):
        _, _, rain_samples = self._run(seed=13, temp_override=28.0)
        self.assertGreater(len(rain_samples), 20, "not enough rain samples to judge")
        below = sum(1 for y, fy in rain_samples if y >= fy - 5.0)  # small tolerance
        self.assertGreaterEqual(below / len(rain_samples), 0.85)


if __name__ == "__main__":
    unittest.main()
