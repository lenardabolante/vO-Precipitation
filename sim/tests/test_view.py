"""`view` command: particle culling for the stream only. Simulation is unaffected, stats.particles stays
the full count, a bare `view` lists everything again."""
import json
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_view.py` (or sim.tests.test_view) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
from simulation import Simulation

DT = 1.0 / 30


def busy_pair(frames=800):
    a = Simulation(7, 3840, 1080, 30)
    b = Simulation(7, 3840, 1080, 30)
    for _ in range(frames):
        a.step(DT)
        b.step(DT)
    return a, b


class TestView(unittest.TestCase):
    def test_culled_frames_only_list_particles_in_the_view_plus_margin(self):
        a, b = busy_pair()
        a.handle_command({"cmd": "view", "x0": 500, "x1": 1700}, [])
        for _ in range(60):
            fa = a.step(DT)
            fb = b.step(DT)
            full = fb["particles"]
            self.assertGreater(len(full), 100, "scenario needs a busy sky")
            self.assertTrue(all(500 - 200 <= p[1] <= 1700 + 200 for p in fa["particles"]))
            self.assertEqual(fa["particles"], [p for p in full if 300 <= p[1] <= 1900])
            self.assertLess(len(fa["particles"]), len(full))
            self.assertEqual(fa["stats"]["particles"], fb["stats"]["particles"], "stats keep the full count")
            self.assertEqual(fa["stats"]["particles"], len(full))

    def test_view_does_not_change_the_simulation(self):
        a, b = busy_pair(300)
        a.handle_command({"cmd": "view", "x0": 0, "x1": 800}, [])
        for _ in range(120):
            fa, fb = a.step(DT), b.step(DT)
            fa.pop("particles")
            fb.pop("particles")
            self.assertEqual(json.dumps(fa, sort_keys=True), json.dumps(fb, sort_keys=True))
        self.assertEqual(json.dumps(a.to_state(), sort_keys=True), json.dumps(b.to_state(), sort_keys=True))

    def test_bare_view_resets_to_everything(self):
        a, b = busy_pair(300)
        a.handle_command({"cmd": "view", "x0": 0, "x1": 100}, [])
        self.assertLess(len(a.step(DT)["particles"]), len(b.step(DT)["particles"]))
        a.handle_command({"cmd": "view"}, [])
        self.assertIsNone(a.view)
        self.assertEqual(a.step(DT)["particles"], b.step(DT)["particles"])

    def test_reversed_and_invalid_ranges(self):
        a, _ = busy_pair(100)
        a.handle_command({"cmd": "view", "x0": 900, "x1": 100}, [])
        self.assertEqual(a.view, (100.0, 900.0), "a reversed range is normalised")
        a.handle_command({"cmd": "view", "x0": "left", "x1": 5}, [])
        self.assertEqual(a.view, (100.0, 900.0), "garbage is ignored")
        a.handle_command({"cmd": "view", "x0": 5}, [])
        self.assertEqual(a.view, (100.0, 900.0), "a half range is ignored")
        a.handle_command({"cmd": "view", "x0": float("nan"), "x1": 5}, [])
        self.assertEqual(a.view, (100.0, 900.0))

    def test_view_survives_reseed_and_empty_view_lists_nothing_far_away(self):
        a, _ = busy_pair(100)
        a.handle_command({"cmd": "view", "x0": 10000, "x1": 11000}, [])
        self.assertEqual(a.step(DT)["particles"], [])
        a.handle_command({"cmd": "reseed", "seed": 3}, [])
        self.assertEqual(a.view, (10000.0, 11000.0))


if __name__ == "__main__":
    unittest.main()
