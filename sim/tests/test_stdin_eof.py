"""The real process: exits cleanly when stdin closes (Java died), prints only protocol JSON lines to
stdout (no stray debug output), validates its command line, and a headless run emits valid v2."""
import json
import os
import subprocess
import sys
import unittest

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_stdin_eof.py` (or sim.tests.test_stdin_eof) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S

SIM_ENTRY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "precip_sim.py")
SMALL = ["--width", "1280", "--height", "720", "--fps", "30"]


def popen(*args):
    return subprocess.Popen([sys.executable, "-u", SIM_ENTRY, *args], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


class TestStdinEof(unittest.TestCase):
    def test_exits_cleanly_on_stdin_eof(self):
        proc = popen("--seed", "1", *SMALL)
        proc.stdin.close()  # immediate EOF, like Java exiting
        try:
            out, err = proc.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
            self.fail("process did not exit within 20s of stdin EOF")

        self.assertEqual(proc.returncode, 0, err)
        lines = [ln for ln in out.splitlines() if ln.strip()]
        self.assertTrue(lines, "expected at least the hello line on stdout")
        for ln in lines:
            obj = json.loads(ln)  # every stdout line must be valid JSON
            self.assertIn(obj.get("type"), ("hello", "frame", "saved"))
        self.assertEqual(json.loads(lines[0])["type"], "hello")
        self.assertEqual(json.loads(lines[0])["version"], 2)

    def test_reseed_emits_a_fresh_hello(self):
        proc = popen("--seed", "1", *SMALL)
        try:
            proc.stdin.write(json.dumps({"cmd": "reseed", "seed": 777}) + "\n")
            proc.stdin.flush()

            hellos = []
            deadline_lines = 400  # generous cap so a stalled pipe can't hang the test
            for _ in range(deadline_lines):
                line = proc.stdout.readline()
                if not line:
                    break
                obj = json.loads(line)
                if obj["type"] == "hello":
                    hellos.append(obj)
                    if len(hellos) >= 2:
                        break
        finally:
            proc.stdin.close()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
            proc.stdout.close()
            proc.stderr.close()

        self.assertEqual(len(hellos), 2, "expected an initial hello and a reseed hello")
        self.assertEqual(hellos[0]["seed"], 1)
        self.assertEqual(hellos[1]["seed"], 777)
        self.assertFalse(hellos[1]["loaded"])
        S.validate_hello(hellos[1])

    def test_quit_command_exits_cleanly(self):
        proc = popen("--seed", "1", *SMALL)
        proc.stdin.write(json.dumps({"cmd": "quit"}) + "\n")
        proc.stdin.close()
        try:
            out, err = proc.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
            self.fail("process did not exit within 20s of a quit command")
        self.assertEqual(proc.returncode, 0, err)


class TestCommandLine(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-u", SIM_ENTRY, *args], capture_output=True, text=True,
                              timeout=120)

    def test_bad_sizes_exit_nonzero_with_a_message_and_empty_stdout(self):
        for args in (["--width", "1000"], ["--width", "9601"], ["--height", "700"], ["--height", "1441"],
                     ["--width", "0", "--height", "0"], ["--terrain", "swamp"], ["--settlements", "lots"]):
            r = self.run_cli(*args, "--headless-frames", "2")
            self.assertNotEqual(r.returncode, 0, args)
            self.assertEqual(r.stdout, "", args)
            self.assertTrue(r.stderr.strip(), args)

    def test_supported_extremes_run(self):
        for size in (("1280", "720"), ("9600", "1440")):
            r = self.run_cli("--seed", "2", "--width", size[0], "--height", size[1],
                             "--headless-frames", "2", "--dump-every", "2")
            self.assertEqual(r.returncode, 0, r.stderr)
            lines = [json.loads(x) for x in r.stdout.splitlines()]
            self.assertEqual([m["type"] for m in lines], ["hello", "frame"])
            self.assertEqual(lines[0]["world"]["w"], int(size[0]))

    def test_defaults_are_3840_by_1080_rolling_normal(self):
        r = self.run_cli("--seed", "3", "--headless-frames", "1")
        self.assertEqual(r.returncode, 0, r.stderr)
        hello = json.loads(r.stdout.splitlines()[0])
        self.assertEqual((hello["world"]["w"], hello["world"]["h"]), (3840, 1080))
        self.assertEqual(hello["world"]["terrain_kind"], "rolling")
        kinds = {s["kind"] for s in hello["settlements"]}
        self.assertTrue({"village", "city"} <= kinds)

    def test_terrain_and_settlement_options(self):
        r = self.run_cli("--seed", "3", "--terrain", "mountainous", "--settlements", "none", "--headless-frames", "1")
        self.assertEqual(r.returncode, 0, r.stderr)
        hello = json.loads(r.stdout.splitlines()[0])
        self.assertEqual(hello["world"]["terrain_kind"], "mountainous")
        self.assertEqual(hello["settlements"], [])
        self.assertFalse([o for o in hello["objects"] if o["kind"] in S.BUILDING_KINDS])

    def test_headless_run_emits_valid_v2(self):
        r = self.run_cli("--seed", "7", "--headless-frames", "300", "--dump-every", "100")
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = r.stdout.splitlines()
        self.assertEqual(len(lines), 4, "hello + frames 100, 200, 300")
        msgs = [json.loads(x) for x in lines]
        ids = S.validate_hello(msgs[0])
        self.assertEqual([m["frame"] for m in msgs[1:]], [100, 200, 300])
        for m in msgs[1:]:
            S.validate_frame(m, 3840, 1080, ids)
        for ln in r.stderr.splitlines():
            self.assertFalse(ln.lstrip().startswith("{"), "stderr is for humans, stdout for protocol")

    def test_lines_end_in_a_bare_newline(self):
        r = subprocess.run([sys.executable, "-u", SIM_ENTRY, "--seed", "1", "--width", "1280", "--height", "720",
                            "--headless-frames", "3"], capture_output=True, timeout=60)
        self.assertEqual(r.returncode, 0)
        self.assertNotIn(b"\r", r.stdout)
        self.assertEqual(r.stdout.count(b"\n"), 4)
        self.assertTrue(r.stdout.endswith(b"\n"))
        r.stdout.decode("ascii")

    def test_same_seed_is_deterministic_across_processes(self):
        a = self.run_cli("--seed", "5", "--width", "1280", "--height", "720", "--headless-frames", "120",
                         "--dump-every", "60")
        b = self.run_cli("--seed", "5", "--width", "1280", "--height", "720", "--headless-frames", "120",
                         "--dump-every", "60")
        self.assertEqual(a.stdout, b.stdout)
        self.assertGreater(len(a.stdout), 1000)


if __name__ == "__main__":
    unittest.main()
