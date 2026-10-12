"""Mode wiring in precip_sim.py (PROTOCOL.md v2.2): --mode sandbox|showcase|hurricane, --preset,
--cloud-type; `hello.mode` in every mode; hurricane is imported lazily and a missing module is a clear
one-line error with a non-zero exit; --preset only applies to the sandbox."""
import io
import json
import os
import subprocess
import sys
import unittest
from contextlib import redirect_stderr
from unittest import mock

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_modes.py` (or sim.tests.test_modes) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import precip_sim

SIM_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIM_ENTRY = os.path.join(SIM_DIR, "precip_sim.py")


def run(*args, frames=2):
    return subprocess.run([sys.executable, SIM_ENTRY, "--seed", "5", "--headless-frames", str(frames),
                           "--dump-every", "1", *args], capture_output=True, text=True, timeout=120)


def first(r):
    return json.loads(r.stdout.splitlines()[0])


class TestModeWiring(unittest.TestCase):
    def test_sandbox_is_the_default_and_hello_says_so(self):
        r = run("--width", "1280", "--height", "720")
        self.assertEqual(r.returncode, 0, r.stderr)
        h = first(r)
        self.assertEqual(h["mode"], "sandbox")
        self.assertEqual(h["version"], 2)
        self.assertNotIn("preset", h)
        self.assertNotIn("showcase", h)
        self.assertEqual((h["world"]["w"], h["world"]["h"]), (1280, 720))

    def test_default_sandbox_size_is_still_3840_by_1080(self):
        h = first(run("--width", "3840", frames=1))
        self.assertEqual((h["world"]["w"], h["world"]["h"]), (3840, 1080))
        args = precip_sim.parse_args(["--seed", "1"])
        self.assertEqual((args.width, args.height, args.mode), (3840, 1080, "sandbox"))
        args = precip_sim.parse_args(["--mode", "showcase"])
        self.assertEqual((args.width, args.height, args.cloud_type), (1920, 1080, "cumulus"))

    def test_preset_in_the_sandbox(self):
        r = run("--preset", "united_kingdom", "--terrain", "mountainous", "--width", "1920")
        self.assertEqual(r.returncode, 0, r.stderr)
        h = first(r)
        S.validate_hello(h)
        self.assertEqual(h["mode"], "sandbox")
        self.assertEqual(h["world"]["terrain_kind"], "preset", "--terrain is ignored with a preset")
        self.assertEqual(h["preset"]["id"], "united_kingdom")

    def test_preset_is_ignored_outside_the_sandbox(self):
        r = run("--mode", "showcase", "--preset", "tibet")
        self.assertEqual(r.returncode, 0, r.stderr)
        h = first(r)
        self.assertEqual(h["mode"], "showcase")
        self.assertNotIn("preset", h)

    def test_every_preset_id_is_accepted(self):
        for pid in ("washington", "philippines", "tibet", "russia", "united_kingdom", "central_europe"):
            r = run("--preset", pid, "--width", "1280", "--height", "720", frames=1)
            self.assertEqual(r.returncode, 0, f"{pid}: {r.stderr}")
            self.assertEqual(first(r)["preset"]["id"], pid)

    def test_unknown_mode_is_a_usage_error(self):
        r = run("--mode", "tornado")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")

    def test_size_range_is_still_checked_with_the_new_defaults(self):
        r = run("--width", "100")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("--width", r.stderr)


class TestHurricaneImport(unittest.TestCase):
    def test_a_missing_hurricane_module_is_a_clear_error_and_a_non_zero_exit(self):
        err = io.StringIO()
        with mock.patch.dict(sys.modules, {"hurricane": None}), redirect_stderr(err):
            code = precip_sim.main(["--mode", "hurricane", "--headless-frames", "1"])
        self.assertEqual(code, 2)
        lines = [ln for ln in err.getvalue().splitlines() if ln.strip()]
        self.assertEqual(len(lines), 1, lines)
        self.assertIn("--mode hurricane", lines[0])
        self.assertIn("hurricane.py", lines[0])

    def test_the_other_modes_never_import_it(self):
        code = ("import sys; sys.argv=['x']; sys.path.insert(0, %r); import precip_sim; "
                "a=precip_sim.parse_args(['--mode','showcase']); precip_sim.build_sim(a, 1, 30); "
                "b=precip_sim.parse_args([]); precip_sim.build_sim(b, 1, 30); "
                "print('hurricane' in sys.modules)" % SIM_DIR)
        r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120)
        self.assertEqual(r.stdout.strip(), "False", r.stderr)

    @unittest.skipUnless(os.path.exists(os.path.join(SIM_DIR, "hurricane.py")), "hurricane.py not written yet")
    def test_hurricane_mode_runs_with_the_shared_driver_interface(self):
        r = run("--mode", "hurricane", frames=3)
        self.assertEqual(r.returncode, 0, r.stderr)
        msgs = [json.loads(ln) for ln in r.stdout.splitlines()]
        self.assertEqual((msgs[0]["type"], msgs[0]["mode"]), ("hello", "hurricane"))
        self.assertEqual([m["type"] for m in msgs[1:]], ["hurricane"] * 3)

    @unittest.skipUnless(os.path.exists(os.path.join(SIM_DIR, "hurricane.py")), "hurricane.py not written yet")
    def test_hurricane_has_the_driver_interface(self):
        from hurricane import HurricaneSim
        sim = HurricaneSim(1, 30)
        for name in ("hello_message", "step", "handle_command", "drain_outbox"):
            self.assertTrue(callable(getattr(sim, name)), name)
        for attr in ("running", "frame", "seed"):
            self.assertTrue(hasattr(sim, attr), attr)
        self.assertEqual(sim.hello_message()["mode"], "hurricane")


if __name__ == "__main__":
    unittest.main()
