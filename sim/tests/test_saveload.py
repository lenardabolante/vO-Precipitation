"""Save / load: versioned JSON state (no pickle), atomic writes, `saved` replies, and the round trip
guarantee -- run N frames, save, load into a fresh Simulation, run M frames == an uninterrupted run
of N+M frames, frame for frame."""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

try:
    import _pathfix  # noqa: F401
except ImportError:          # `python -m unittest sim/tests/test_saveload.py` (or sim.tests.test_saveload) from the project root
    import os as _os
    import sys as _sys
    _sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
    import _pathfix  # noqa: F401
import _schema as S
import savestate
import water as W
from savestate import StateError, load_state, save_state
from simulation import Simulation

DT = 1.0 / 30
SIM_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIM_ENTRY = os.path.join(SIM_DIR, "precip_sim.py")


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def canon(frame):
    return json.dumps(frame, sort_keys=True)


def busy(seed=7, w=1920, h=900, kind="hilly", frames=450):
    sim = Simulation(seed, w, h, 30, kind, "normal")
    sim.handle_command({"cmd": "wind", "delta": 2.0}, [])
    sim.handle_command({"cmd": "temp", "delta": -6.0}, [])
    sim.handle_command({"cmd": "speed", "value": 2.0}, [])
    for k in range(4):
        sim.handle_command({"cmd": "spawn", "x": 300 + k * 350, "y": 220}, [])
    for _ in range(frames):
        sim.step(DT)
    return sim


class TestRoundTrip(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "world", "state.sav")

    def test_n_plus_m_equals_uninterrupted_run(self):
        a = busy()
        ok, err = save_state(a.to_state(), self.path)
        self.assertTrue(ok, err)
        b = Simulation.from_state(load_state(self.path))
        c = busy()                        # same history, never saved
        self.assertGreater(len(c.particles), 100, "scenario should be busy")
        bad = []
        for i in range(400):
            fb = b.step(DT)
            fc = c.step(DT)
            if i == 0:
                self.assertIn("ground", fb, "a loaded world resends the ground strip on its first frame")
                fb = {k: v for k, v in fb.items() if k not in ("ground", "water", "flood", "flow")}
                fc = {k: v for k, v in fc.items() if k not in ("ground", "water", "flood", "flow")}
            if canon(fb) != canon(fc):
                bad.append(i)
        self.assertEqual(bad, [], f"frames differ after load at {bad[:5]}")
        self.assertEqual(canon(b.to_state()), canon(c.to_state()), "full state identical after the run")

    def test_round_trip_survives_a_reseed_pause_and_every_terrain(self):
        for kind in ("flat", "mountainous"):
            a = busy(seed=3, kind=kind, frames=200)
            a.handle_command({"cmd": "pause"}, [])
            a.step(DT)
            ok, err = save_state(a.to_state(), self.path)
            self.assertTrue(ok, err)
            b = Simulation.from_state(load_state(self.path))
            self.assertTrue(b.paused)
            b.handle_command({"cmd": "resume"}, [])
            a.handle_command({"cmd": "resume"}, [])
            for i in range(150):
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
                self.assertEqual(canon(fa), canon(fb), f"{kind} frame {i}")

    def test_loaded_hello(self):
        a = busy(frames=300)
        save_state(a.to_state(), self.path)
        b = Simulation.from_state(load_state(self.path))
        h = b.hello_message()
        S.validate_hello(h)
        self.assertTrue(h["loaded"])
        self.assertAlmostEqual(h["t"], round(a.world.t, 2))
        self.assertEqual(h["seed"], a.seed)
        self.assertEqual((h["world"]["w"], h["world"]["h"]), (1920, 900))
        self.assertEqual(h["world"]["terrain"]["y"], a.hello_message()["world"]["terrain"]["y"])
        # current dynamic object state travels in hello so a fresh viewer matches the world
        ha = a.hello_message()
        self.assertEqual(h["objects"], ha["objects"])
        self.assertTrue(any(o["wet"] > 0 or o["snow"] > 0 for o in h["objects"]))
        f = b.step(DT)
        self.assertEqual(f["frame"], a.frame + 1)
        self.assertGreater(f["t"], h["t"])

    def test_state_is_json_not_pickle_and_has_a_version(self):
        a = busy(frames=100)
        save_state(a.to_state(), self.path)
        with open(self.path, "rb") as f:
            raw = f.read()
        self.assertEqual(raw[:1], b"{")
        doc = json.loads(raw.decode("utf-8"))
        self.assertEqual(doc["format"], savestate.FORMAT)
        self.assertEqual(doc["version"], savestate.VERSION)
        for k in ("rng", "terrain", "objects", "clouds", "particles", "signatures", "ground", "wind"):
            self.assertIn(k, doc["state"])
        # every cloud carries its own RNG state
        self.assertTrue(doc["state"]["clouds"])
        for c in doc["state"]["clouds"]:
            self.assertEqual(c["rng"][0], 3)
            self.assertEqual(len(c["rng"][1]), 625)
        for name in os.listdir(SIM_DIR):
            if name.endswith(".py"):
                with open(os.path.join(SIM_DIR, name), encoding="utf-8") as f:
                    src = f.read()
                self.assertIsNone(re.search(r"^\s*(import|from)\s+(pickle|marshal|shelve)\b", src, re.M), name)

    def test_signatures_survive_so_uniqueness_continues(self):
        a = busy(frames=100)
        save_state(a.to_state(), self.path)
        b = Simulation.from_state(load_state(self.path))
        self.assertEqual(a.signatures, b.signatures)
        self.assertEqual(b.next_cloud_id, a.next_cloud_id)

    def test_view_is_connection_state_and_not_saved(self):
        a = busy(frames=50)
        a.handle_command({"cmd": "view", "x0": 0, "x1": 500}, [])
        save_state(a.to_state(), self.path)
        b = Simulation.from_state(load_state(self.path))
        self.assertIsNone(b.view)


class TestSaveCommand(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "a1b2", "state.sav")

    def test_save_command_replies_with_exactly_one_saved_message(self):
        sim = busy(frames=60)
        sim.handle_command({"cmd": "save", "path": self.path}, [])
        out = sim.drain_outbox()
        self.assertEqual(len(out), 1)
        m = out[0]
        self.assertEqual(m["type"], "saved")
        self.assertTrue(m["ok"], m)
        self.assertIsNone(m["error"])
        self.assertEqual(m["path"], self.path)
        self.assertEqual(m["frame"], sim.frame)
        self.assertAlmostEqual(m["t"], round(sim.world.t, 2))
        self.assertTrue(os.path.isfile(self.path))
        self.assertEqual(sim.drain_outbox(), [])
        self.assertEqual(sorted(os.listdir(os.path.dirname(self.path))), ["state.sav"], "no temp file left")

    def test_save_does_not_disturb_the_simulation(self):
        a = busy(frames=100)
        b = busy(frames=100)
        a.handle_command({"cmd": "save", "path": self.path}, [])
        for _ in range(60):
            self.assertEqual(canon(a.step(DT)), canon(b.step(DT)))

    def test_bad_save_requests_report_ok_false(self):
        sim = busy(frames=20)
        for cmd in ({"cmd": "save"}, {"cmd": "save", "path": ""}, {"cmd": "save", "path": 5}):
            sim.handle_command(cmd, [])
            m = sim.drain_outbox()[0]
            self.assertEqual(m["type"], "saved")
            self.assertFalse(m["ok"])
            self.assertTrue(m["error"])
        self.assertTrue(sim.running)

    def test_unwritable_path_reports_failure_and_keeps_the_old_file(self):
        sim = busy(frames=20)
        sim.handle_command({"cmd": "save", "path": self.path}, [])
        sim.drain_outbox()
        before = read_bytes(self.path)
        # a directory where the file should go: the replace must fail, the old file stays
        blocker = os.path.join(self.tmp.name, "blocked")
        os.makedirs(os.path.join(blocker, "state.sav"))
        sim.handle_command({"cmd": "save", "path": os.path.join(blocker, "state.sav")}, [])
        m = sim.drain_outbox()[0]
        self.assertFalse(m["ok"])
        self.assertTrue(m["error"])
        self.assertEqual(sorted(os.listdir(blocker)), ["state.sav"], "no stray temp files")
        self.assertEqual(read_bytes(self.path), before)


class TestAtomicity(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "state.sav")
        self.state = busy(frames=30).to_state()

    def test_failure_while_serialising_leaves_previous_file_intact(self):
        ok, _ = save_state(self.state, self.path)
        self.assertTrue(ok)
        before = read_bytes(self.path)
        with mock.patch.object(savestate.json, "dumps", side_effect=RuntimeError("boom")):
            ok, err = save_state(self.state, self.path)
        self.assertFalse(ok)
        self.assertIn("boom", err)
        self.assertEqual(read_bytes(self.path), before)
        self.assertEqual(os.listdir(self.tmp.name), ["state.sav"])

    def test_failure_during_the_write_leaves_no_partial_file(self):
        real_fsync = os.fsync

        def die(fd):
            raise OSError("disk full")

        with mock.patch.object(savestate.os, "fsync", side_effect=die):
            ok, err = save_state(self.state, self.path)
        self.assertFalse(ok)
        self.assertIn("disk full", err)
        self.assertEqual(os.listdir(self.tmp.name), [], "neither a partial target nor a temp file")
        self.assertIs(os.fsync, real_fsync)

    def test_failure_at_the_replace_step_keeps_the_old_file(self):
        save_state(self.state, self.path)
        before = read_bytes(self.path)
        with mock.patch.object(savestate.os, "replace", side_effect=OSError("locked")):
            ok, err = save_state(self.state, self.path)
        self.assertFalse(ok)
        self.assertEqual(read_bytes(self.path), before)
        self.assertEqual(os.listdir(self.tmp.name), ["state.sav"])

    def test_nan_is_refused_not_written(self):
        bad = dict(self.state)
        bad["speed"] = float("nan")
        ok, err = save_state(bad, self.path)
        self.assertFalse(ok)
        self.assertFalse(os.path.exists(self.path))


class TestCorruptFiles(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "state.sav")
        save_state(busy(frames=30).to_state(), self.path)
        self.good = read_bytes(self.path)

    def write(self, data):
        with open(self.path, "wb") as f:
            f.write(data)

    def test_missing_truncated_and_garbage_files(self):
        with self.assertRaises(StateError):
            load_state(os.path.join(self.tmp.name, "nope.sav"))
        for data in (self.good[: len(self.good) // 2], b"", b"\x00\xff\xfe garbage", b"[1, 2, 3]",
                     b'{"format": "something-else", "version": 2, "state": {}}'):
            self.write(data)
            with self.assertRaises(StateError, msg=data[:20]):
                load_state(self.path)

    def test_incompatible_version_is_refused_with_a_clear_message(self):
        doc = json.loads(self.good)
        doc["version"] = 1
        self.write(json.dumps(doc).encode())
        with self.assertRaises(StateError) as cm:
            load_state(self.path)
        self.assertIn("version", str(cm.exception))

    def test_missing_fields_do_not_build_a_broken_world(self):
        doc = json.loads(self.good)
        del doc["state"]["terrain"]
        self.write(json.dumps(doc).encode())
        state = load_state(self.path)
        with self.assertRaises((KeyError, TypeError, ValueError)):
            Simulation.from_state(state)

    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-u", SIM_ENTRY, *args], capture_output=True, text=True,
                              timeout=60)

    def test_cli_load_of_a_corrupt_file_fails_cleanly(self):
        for data in (self.good[:1000], b"not json at all"):
            self.write(data)
            r = self.run_cli("--load", self.path, "--headless-frames", "3")
            self.assertNotEqual(r.returncode, 0)
            self.assertEqual(r.stdout, "", "nothing on stdout when the load fails")
            self.assertIn("cannot load state", r.stderr)

    def test_cli_load_of_an_incompatible_version_and_missing_file(self):
        doc = json.loads(self.good)
        doc["version"] = 99
        self.write(json.dumps(doc).encode())
        r = self.run_cli("--load", self.path, "--headless-frames", "3")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertIn("incompatible state version", r.stderr)
        r = self.run_cli("--load", os.path.join(self.tmp.name, "missing.sav"), "--headless-frames", "3")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")

    def test_cli_load_continues_the_saved_world(self):
        r = self.run_cli("--load", self.path, "--headless-frames", "4", "--seed", "999",
                         "--width", "2000", "--height", "800")   # size/seed args are ignored
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = [json.loads(x) for x in r.stdout.splitlines() if x.strip()]
        hello = lines[0]
        self.assertEqual(hello["type"], "hello")
        self.assertTrue(hello["loaded"])
        self.assertEqual((hello["world"]["w"], hello["world"]["h"]), (1920, 900))
        self.assertNotEqual(hello["seed"], 999)
        frames = [m for m in lines if m["type"] == "frame"]
        self.assertEqual(len(frames), 4)
        self.assertEqual(frames[0]["frame"], hello_frame(self.good) + 1)
        self.assertIn("ground", frames[0])


def hello_frame(raw):
    return json.loads(raw)["state"]["frame"]


class TestSaveOverTheWire(unittest.TestCase):
    def test_save_command_through_the_real_process_then_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "slot", "state.sav")
            proc = subprocess.Popen([sys.executable, "-u", SIM_ENTRY, "--seed", "5", "--width", "1280",
                                     "--height", "720", "--fps", "60"],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    text=True)
            try:
                proc.stdin.write(json.dumps({"cmd": "save", "path": path}) + "\n")
                proc.stdin.flush()
                saved = []
                kinds = []
                for _ in range(400):
                    line = proc.stdout.readline()
                    if not line:
                        break
                    m = json.loads(line)
                    kinds.append(m["type"])
                    if m["type"] == "saved":
                        saved.append(m)
                        break
                proc.stdin.write(json.dumps({"cmd": "quit"}) + "\n")
                proc.stdin.flush()
            finally:
                proc.stdin.close()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
                proc.stdout.close()
                proc.stderr.close()
            self.assertEqual(len(saved), 1)
            self.assertTrue(saved[0]["ok"], saved[0])
            self.assertEqual(saved[0]["path"], path)
            self.assertEqual(kinds[0], "hello")
            self.assertTrue(os.path.isfile(path))
            sim = Simulation.from_state(load_state(path))
            self.assertEqual(sim.seed, 5)
            self.assertEqual(sim.frame, saved[0]["frame"])


REPO_SAVES = os.path.join(os.path.dirname(SIM_DIR), "saves")


def strip_to_v22(state):
    """What a v2.2 writer produced: none of the v2.3 keys."""
    st = json.loads(json.dumps(state))
    for k in ("atmo", "merge_acc", "start_month", "start_hour", "year_days"):
        st.pop(k, None)
    for k in ("climate", "year_days", "year0", "hour0", "user_c", "hold", "glide", "gas"):
        st["world"].pop(k, None)
    for c in st["clouds"]:
        for k in ("parts", "merge", "merge_v", "mg", "puff_part", "merging_into", "merge_f0", "merge_dx",
                  "merge_dy", "merge_w0", "merge_i0"):
            c.pop(k, None)
    return st


class TestClimateSaveLoad(unittest.TestCase):
    def test_round_trip_across_a_day_change_and_a_calendar_jump(self):
        def build():
            sim = Simulation(5, 1920, 900, 30, "hilly", "normal", month=6.0, hour=22.0, year_days=12)
            sim.handle_command({"cmd": "gas", "name": "co2", "value": 700}, [])
            sim.handle_command({"cmd": "gas", "name": "aerosol", "value": 0.6}, [])
            sim.handle_command({"cmd": "temp", "delta": -1.5}, [])
            return sim
        a, c = build(), build()
        for _ in range(200):
            a.step(DT)
            c.step(DT)
        a.handle_command({"cmd": "season", "month": 10.0}, [])       # glide in progress at the save
        c.handle_command({"cmd": "season", "month": 10.0}, [])
        for _ in range(60):
            a.step(DT)
            c.step(DT)
        b = Simulation.from_state(json.loads(json.dumps(a.to_state())))
        tods = []
        for i in range(30 * 120):            # past midnight (22 h + 2 sim minutes) and the end of the glide
            fb, fc = b.step(DT), c.step(DT)
            tods.append(fc["world"]["time_of_day"])
            if i == 0:
                fb.pop("ground", None)
                fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fb.pop("flood", None)
                fb.pop("flow", None)
                fc.pop("ground", None)
                fc.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fc.pop("flood", None)
                fc.pop("flow", None)
            if canon(fb) != canon(fc):
                self.fail(f"frame {i} differs after load")
        self.assertTrue(any(y < x for x, y in zip(tods, tods[1:])), "the run crossed midnight")
        self.assertEqual(canon(b.to_state()), canon(c.to_state()))

    def test_v22_state_loads_with_the_default_climate_and_continues_from_its_temperature(self):
        a = busy(seed=9, frames=300)
        state = a.to_state()
        v22 = strip_to_v22(state)
        saved_temp = a.world.surface_temp_c
        b = Simulation.from_state(v22)
        self.assertAlmostEqual(b.world.surface_temp_c, saved_temp, places=6)
        self.assertEqual(b.climate_hello()["source"], "generated")
        self.assertEqual(b.climate_hello()["hemisphere"], "N")
        self.assertEqual((b.atmo.co2_ppm, b.atmo.aerosol), (420.0, 0.3))
        self.assertTrue(0.45 <= b.atmo.humidity <= 0.70)
        self.assertTrue(all(c.parts == [] and c.mg is None and c.merging_into is None for c in b.clouds))
        prev = saved_temp
        worst = 0.0
        for _ in range(900):
            f = b.step(DT)
            S.validate_frame(f, 1920, 900, set(range(1, len(b.objects.objs) + 1)))
            worst = max(worst, abs(f["world"]["surface_temp_c"] - prev))
            prev = f["world"]["surface_temp_c"]
        self.assertLess(worst, 0.05, "no jump from the saved temperature on")
        # and it behaves like a sandbox now: the day cycle rides on top
        self.assertIn("atmosphere", f)
        self.assertEqual(b.world.year_days, 8)

    def test_v22_state_round_trips_again_as_v23(self):
        a = busy(seed=4, frames=120)
        b = Simulation.from_state(strip_to_v22(a.to_state()))
        for _ in range(50):
            b.step(DT)
        c = Simulation.from_state(json.loads(json.dumps(b.to_state())))
        for i in range(200):
            fb, fc = b.step(DT), c.step(DT)
            if i == 0:
                fb.pop("ground", None)
                fb.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fb.pop("flood", None)
                fb.pop("flow", None)
                fc.pop("ground", None)
                fc.pop("water", None)       # (v2.4: a loaded world also repeats frame.water / flood once)
                fc.pop("flood", None)
                fc.pop("flow", None)
            self.assertEqual(canon(fb), canon(fc), f"frame {i}")

    def test_v22_preset_state_loads_too(self):
        a = Simulation(6, 1920, 900, 30, "rolling", "normal", "philippines")
        for _ in range(200):
            a.step(DT)
        v22 = strip_to_v22(a.to_state())
        saved = a.world.surface_temp_c
        b = Simulation.from_state(v22)
        self.assertEqual(b.preset["id"], "philippines")
        self.assertAlmostEqual(b.world.surface_temp_c, saved, places=6)
        prev = saved
        for _ in range(300):
            f = b.step(DT)
            self.assertLess(abs(f["world"]["surface_temp_c"] - prev), 0.05)
            prev = f["world"]["surface_temp_c"]

    @unittest.skipUnless(os.path.isdir(REPO_SAVES), "no saves/ folder")
    def test_the_real_v22_saves_in_the_repo_still_load_and_run(self):
        found = 0
        for name in sorted(os.listdir(REPO_SAVES)):
            path = os.path.join(REPO_SAVES, name, "state.sav")
            if not os.path.isfile(path):
                continue
            state = load_state(path)
            if "climate" in state["world"]:
                continue                     # already written by a v2.3 build: not a v2.2 file
            found += 1
            sim = Simulation.from_state(state)
            saved = float(state["world"]["surface_temp_c"])
            self.assertAlmostEqual(sim.world.surface_temp_c, saved, places=6, msg=name)
            prev = saved
            for _ in range(300):
                f = sim.step(DT)
                self.assertLess(abs(f["world"]["surface_temp_c"] - prev), 0.05, name)
                prev = f["world"]["surface_temp_c"]
            S.validate_hello(sim.hello_message())
        if not found:
            self.skipTest("no v2.2 save in saves/")


def pour(sim):
    """Extra rain on every bucket, a deterministic function of the water step's own tick counter (saved with the water), so a
    world and its reloaded copy get the same rain. Used to flood a street and fill the hollows without waiting for a cloud."""
    w = sim.water
    orig = w.update
    nb = len(w.tmap.buckets)

    def upd(dt, hits):
        h = dict(hits)
        t = w.tick
        for k in range(nb):
            if (k * 7 + t) % 5:
                h[k] = h.get(k, 0) + 1
        orig(dt, h)
    w.update = upd
    return sim


V24_BLOCK = os.path.join(SIM_DIR, "tests", "data", "water_block_v24.json")      # a `water` block written by the v2.4 code (Phase L), mid-flood


class TestWaterSaveLoad(unittest.TestCase):
    """v2.4: the `water` block of the state file."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "flood.sav")

    def flooding_world(self):
        sim = pour(Simulation(3, 3840, 1080, 30, "hilly", "normal", drains="small"))
        sim.handle_command({"cmd": "speed", "value": 2.0}, [])
        for _ in range(30 * 25):
            sim.step(DT)
        return sim

    def test_run_n_save_load_run_m_equals_the_uninterrupted_run_while_a_street_is_flooded_and_a_pool_spills(self):
        a = self.flooding_world()
        w = a.water
        # (v2.4.1: a street has no body of its own unless the terrain makes it a hollow: it floods as a sheet of film or
        # under a pool that covers it; what is saved mid-flood is the film, the pools and the flood states)
        depths = [(e["id"], e["depth"], e["state"]) for e in w.flood_list()]
        self.assertTrue(any(d >= 3.0 for _s, d, _st in depths), f"a street stands flooded when it is saved: {depths}")
        self.assertGreater(len(w.film.act), 5, "and the ground carries a film of running water")
        spilling = [b for b in w.pools.values() if b.V >= b.vcap * 0.999]
        self.assertTrue(spilling, "a pool stands at its rim, spilling, when it is saved")
        self.assertGreater(w.ledger["lost"], 0.0, "and water is already running off the world")
        ok, err = save_state(a.to_state(), self.path)
        self.assertTrue(ok, err)
        state = load_state(self.path)
        self.assertIn("water", state)
        self.assertEqual(state["water"]["mode"], "small")
        b = pour(Simulation.from_state(state))
        self.assertEqual(b.water.mode, "small")
        self.assertEqual(json.dumps(b.water.to_state(), sort_keys=True), json.dumps(a.water.to_state(), sort_keys=True))
        bad = []
        sent_a = sent_b = 0
        for i in range(30 * 20):
            fb = b.step(DT)
            fa = a.step(DT)
            if i == 0:
                self.assertIn("water", fb, "a loaded world repeats the lists on its first frame")
                self.assertIn("flood", fb)
                self.assertIn("flow", fb, "(v2.4.1) and the runs of film")
                fb = {k: v for k, v in fb.items() if k not in ("ground", "water", "flood", "flow")}
                fa = {k: v for k, v in fa.items() if k not in ("ground", "water", "flood", "flow")}
            sent_a += "water" in fa
            sent_b += "water" in fb
            if canon(fb) != canon(fa):
                bad.append(i)
        self.assertEqual(bad, [], f"frames differ after the load at {bad[:5]}")
        self.assertGreater(sent_a, 5, "water frames keep flowing through the run")
        self.assertEqual(sent_a, sent_b)
        self.assertEqual(canon(b.to_state()), canon(a.to_state()), "full state identical after the run")

    def test_the_lists_after_a_load_equal_those_before_it(self):
        a = self.flooding_world()
        before_water, before_flood = a.water.water_list(), a.water.flood_list()
        b = Simulation.from_state(json.loads(json.dumps(a.to_state())))
        self.assertEqual(b.water.water_list(), before_water)
        self.assertEqual(b.water.flood_list(), before_flood)
        self.assertTrue(before_water and any(e["state"] in ("flooded", "overflowing") for e in before_flood))
        hello = b.hello_message()
        S.validate_hello(hello)
        self.assertEqual(hello["drains"]["mode"], "small")

    def test_custom_sizes_and_the_drain_mode_survive_a_round_trip(self):
        a = Simulation(12, 3840, 1080, 30, "rolling", "normal")
        sid = a.settlements[0]["id"]
        a.handle_command({"cmd": "drains", "settlement": sid, "size": 9.25}, [])
        for _ in range(10):
            a.step(DT)
        b = Simulation.from_state(json.loads(json.dumps(a.to_state())))
        self.assertEqual(b.water.sizes, a.water.sizes)
        self.assertEqual(b.water.sizes[sid], 9.25)
        h = b.hello_message()
        self.assertEqual(h["drains"]["mode"], "custom")
        self.assertEqual(next(s for s in h["settlements"] if s["id"] == sid)["drain"]["size"], 9.25)
        b.handle_command({"cmd": "reseed", "seed": 4}, [])
        self.assertEqual(b.drain_outbox()[0]["drains"]["mode"], "normal", "a reseed goes back to the last named mode")

    def test_a_state_without_a_water_block_loads_dry_with_the_sizes_of_a_new_world_of_that_seed(self):
        a = pour(Simulation(12, 3840, 1080, 30, "rolling", "normal", drains="large"))
        for _ in range(300):
            a.step(DT)
        st = json.loads(json.dumps(a.to_state()))
        st.pop("water")
        for c in st["cars"]["cars"]:
            c.pop("depth", None)                                    # (and cars from before v2.4 have no depth)
        b = Simulation.from_state(st)
        fresh = Simulation(12, 3840, 1080, 30, "rolling", "normal")
        self.assertEqual(b.water.mode, "normal")
        self.assertEqual(b.water.sizes, fresh.water.sizes, "the sizes a new world of that seed would get, not the saved mode's")
        self.assertNotEqual(b.water.sizes, a.water.sizes)
        self.assertEqual(b.water.pools, {})
        self.assertTrue(all(x.V == 0.0 for x in b.water.sbody.values()))
        h = b.hello_message()
        S.validate_hello(h)
        self.assertEqual(h["drains"]["mode"], "normal")
        f = b.step(DT)
        self.assertEqual(f["water"], [])
        self.assertTrue(all(e["state"] in ("dry", "wet") for e in f["flood"]))
        for _ in range(100):
            b.step(DT)
        self.assertIn("water", b.to_state(), "saving it again writes the v2.4 block")
        c = Simulation.from_state(json.loads(json.dumps(b.to_state())))
        self.assertEqual(canon(c.water.to_state()), canon(b.water.to_state()))

    def test_a_v24_water_block_loads_and_the_water_its_street_bodies_held_is_put_on_the_street_as_film(self):
        """PROTOCOL.md v2.4.1 "Save files": a v2.4 `water` block (written by the Phase L code, mid-flood: a 4.9 px street body, a
        small-drain mode) loads; the street's water becomes film on its cells and nothing is lost or made up."""
        with open(V24_BLOCK, encoding="utf-8") as fh:
            fix = json.load(fh)
        blk = fix["water"]
        self.assertNotIn("film", blk, "a v2.4 block has no film")
        held = {st["id"]: st["v"] for st in blk["streets"] if st["v"] > 0}
        self.assertTrue(held, "the fixture was written while a street held water")
        sim = Simulation(fix["seed"], 3840, 1080, 30, fix["terrain"], fix["settlements"])
        w = W.WaterField.from_state(sim.terrain, sim.settlements, sim.objects.objs, fix["seed"], json.loads(json.dumps(blk)))
        self.assertEqual(w.mode, "small")
        for sid, v in held.items():
            if sid not in w.sbody:                                 # the street is ground now: its water is film on its span
                cells = w.film.span_cells[sid]
                self.assertAlmostEqual(sum(w.film.S[k] for k in cells), v, delta=1e-6 * v)
                self.assertEqual(len(w.film.act), len(cells))
        self.assertAlmostEqual(w.stored(), sum(held.values()) + sum(p["v"] for p in blk["pools"]), delta=1e-6 * sum(held.values()))
        led0 = dict(w.ledger)
        self.assertAlmostEqual(led0["rain"], w.stored() + sum(v for k, v in led0.items() if k != "rain"), delta=1e-3 * led0["rain"])
        ids = S.validate_hello(sim.hello_message())
        sids = [x["id"] for x in sim.settlements]
        first = w.emit(1)
        for key in ("water", "flood", "flow"):
            self.assertIn(key, first, "a loaded world repeats the lists on its first frame")
        S.validate_frame(dict(sim.step(DT), **first), 3840, 1080, ids, sids)
        for i in range(2, 30 * 90):                                 # and the water carries on: it drains away, nothing is made up
            w.update(DT, {})
            if i % 30 == 0:
                e = next(x for x in w.flood_list() if x["id"] in held)
        self.assertLess(w.film.total(), 0.2 * sum(held.values()), "the sheet has run off and drained in 90 s")
        led = w.ledger
        self.assertAlmostEqual(led["rain"], w.stored() + sum(v for k, v in led.items() if k != "rain"), delta=1e-3 * led["rain"])

    def test_the_old_format_fixture_loads_continues_and_gets_seed_derived_drains(self):
        state = load_state(OLD_SAVE)
        self.assertNotIn("water", state)
        sim = Simulation.from_state(state)
        fresh = Simulation(state["seed"], state["w"], state["h"], 30, state["terrain_kind"], state["settlements_mode"])
        self.assertEqual(sim.water.sizes, fresh.water.sizes)
        self.assertEqual(sim.water.mode, "normal")
        self.assertEqual(sim.water.pools, {})
        S.validate_hello(sim.hello_message())
        info = S.validate_hello(sim.hello_message())
        sids = [x["id"] for x in sim.settlements]
        for _ in range(60):
            S.validate_frame(sim.step(DT), state["w"], state["h"], info, sids)

    def test_a_save_taken_during_a_flood_through_the_command(self):
        a = self.flooding_world()
        a.handle_command({"cmd": "save", "path": self.path}, [])
        saved = a.drain_outbox()
        self.assertTrue(saved[0]["ok"], saved)
        b = Simulation.from_state(load_state(self.path))
        self.assertEqual(canon(b.water.to_state()), canon(a.water.to_state()))


class TestSupercellWorldRoundTrip(unittest.TestCase):
    """v2.3 (Sim Core 2): a world with a supercell, a tornado on the ground, damaged objects, debris in the air and
    cars on the road survives save -> load -> continue frame for frame, and a v2.2 state still loads."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "state.sav")

    @staticmethod
    def scene():
        sim = Simulation(7, 1920, 1080, 30, "rolling", "normal")
        sim._next_spawn_delay = 1e18
        sim.clouds.clear()
        sim.handle_command({"cmd": "spawn", "x": 700, "y": 300, "type": "supercell"}, [])
        sc = sim.clouds[0]
        sc.rot_peak = sc.wall_peak = 0.95
        sc.vx = sc.vy = 0.0
        for _ in range(30 * 150):
            sim.step(DT)
            sc.vx = sc.vy = 0.0
            if sc.stage in ("mature", "precipitating") and sc.form >= 0.85:
                break
        sim.handle_command({"cmd": "tornado"}, [])
        return sim, sc

    def test_run_n_save_load_run_m_equals_the_uninterrupted_run_across_a_touchdown(self):
        a, sc = self.scene()
        for _ in range(30 * 9):                    # the funnel is on its way down
            a.step(DT)
            sc.vx = sc.vy = 0.0
        self.assertEqual(a.tornadoes[0].stage, "funnel")
        ok, err = save_state(a.to_state(), self.path)
        self.assertTrue(ok, err)
        b = Simulation.from_state(load_state(self.path))
        self.assertEqual(len(b.tornadoes), 1)
        self.assertEqual(len(b.cars.cars), len(a.cars.cars))
        self.assertGreater(len(b.cars.cars), 5, "there are cars on the road")
        stages, kinds, states = set(), set(), set()
        bad = []
        sb = b.clouds[0]
        for i in range(30 * 40):
            fa = a.step(DT)
            sc.vx = sc.vy = 0.0
            fb = b.step(DT)
            sb.vx = sb.vy = 0.0
            if i == 0:
                self.assertIn("ground", fb)
                fa = {k: v for k, v in fa.items() if k not in ("ground", "water", "flood", "flow")}
                fb = {k: v for k, v in fb.items() if k not in ("ground", "water", "flood", "flow")}
            for t in fa.get("tornadoes", []):
                stages.add(t["stage"])
            kinds.update(p[0] for p in fa["particles"])
            states.update(c["state"] for c in fa.get("cars", []))
            if canon(fa) != canon(fb):
                bad.append(i)
        self.assertEqual(bad, [], f"frames differ after load at {bad[:5]}")
        self.assertTrue({"funnel", "touchdown", "mature"} <= stages)
        self.assertIn(7, kinds, "debris was in the air")
        self.assertIn("driving", states)
        self.assertEqual(canon(a.to_state()), canon(b.to_state()), "full state identical after the run")

    def test_a_state_saved_with_a_tornado_on_the_ground_keeps_damage_and_the_wrecks(self):
        a, sc = self.scene()
        for _ in range(30 * 60):
            a.step(DT)
            sc.vx = sc.vy = 0.0
            if a.tornadoes and a.tornadoes[0].stage == "mature" and a.tornadoes[0].t_stage > 8.0:
                break
        self.assertTrue(a.tornadoes)
        ok, err = save_state(a.to_state(), self.path)
        self.assertTrue(ok, err)
        b = Simulation.from_state(load_state(self.path))
        self.assertEqual(a.objects.dmg_f, b.objects.dmg_f)
        self.assertEqual([c.to_state() for c in a.cars.cars], [c.to_state() for c in b.cars.cars])
        self.assertEqual(a.hello_message()["objects"], b.hello_message()["objects"])
        self.assertEqual(a.hello_message()["roads"], b.hello_message()["roads"])
        self.assertEqual(a.tornadoes_total, b.tornadoes_total)

    def test_a_v22_state_has_no_cars_no_roads_and_no_tornadoes(self):
        a = Simulation(9, 1920, 1080, 30, "rolling", "normal")
        for _ in range(90):
            a.step(DT)
        state = json.loads(json.dumps(a.to_state()))
        for k in ("cars", "tornadoes", "next_tornado_id", "tornadoes_total", "tor_rng", "tor_cool"):
            state.pop(k)
        state["particles"].pop("debris")
        state["particles"].pop("debris_ttl")
        for k in ("dmg_f", "dmg_changed", "storm_sway"):
            state["objects"].pop(k)
        b = Simulation.from_state(state)
        h = b.hello_message()
        self.assertNotIn("roads", h)
        self.assertTrue(all("damage" not in o for o in h["objects"]))
        for _ in range(120):
            f = b.step(DT)
        self.assertNotIn("cars", f)
        self.assertNotIn("tornadoes", f)
        self.assertEqual(f["stats"]["cars"], 0)
        S.validate_hello(b.hello_message())


# A save written before the windsock was removed (2026-10-10), by the code of that day: seed 11, 1280 x 720, rolling, 330
# frames in with wind and three clouds. Object 46 (the last) is the one windsock; it is leaning (sway 1.0) and 530 particles
# are falling. FIXTURE_FRAMES is the sha256 of the 150 frames that code went on to produce (digests of each frame in
# canonical json, joined), with the windsock's rows taken out of `objects` first: the new code must produce exactly those.
OLD_SAVE = os.path.join(SIM_DIR, "tests", "data", "save_with_windsock_v2.sav")
OLD_SAVE_FRAMES = "e26be6acd1ead38232cd8e42ee9b4a19773efef3b59657bb82689153860f12bc"
# (v2.4.1) the same 150 frames of the Phase L code with the `cars` key taken out as well (computed by running the baseline copy of sim/ of
# 2026-10-11 11:32 over this save): cars read the film under them now, so only they differ; the digest above is the one with cars in.
OLD_SAVE_FRAMES_WITHOUT_CARS = "4e1f92e30c276933cdfe29d0403bf2c4f30299e7653937692a8e5a879a7c008b"
# (Phase O, clouds follow the day) the same 150 frames with the day cycle of convection ON, which is what the game runs now: the save's
# clouds carry no `diurnal` key so they follow it, and new clouds are picked and age by the hour. Computed by the code of that day;
# the day cycle switched OFF reproduces OLD_SAVE_FRAMES_WITHOUT_CARS exactly (test below), so this digest differs from it only by that rule.
OLD_SAVE_FRAMES_DAY_CYCLE = "fd987dcba3d63dd1c1aba1104ef2a967f2093ed8b5933394d75a6b1f6c734abe"


class TestSaveFromBeforeTheWindsockWasRemoved(unittest.TestCase):
    """A save lists its objects, and a save made before the windsock was removed has windsocks in it (the last objects:
    generate_world places them after everything else). Chosen: they are dropped on load (ObjectField._without_retired),
    not kept as inert rows, so the viewer is never sent one. Nothing is renumbered: the ids of the rest stay and the
    per-object state keyed by index (wet, snow, sway, damage, active, changed, size_f) is cut to the same length."""

    def setUp(self):
        self.state = load_state(OLD_SAVE)

    def test_the_fixture_really_is_an_old_save_with_a_windsock_last(self):
        objs = self.state["objects"]["objs"]
        self.assertEqual([o["kind"] for o in objs if o["kind"] == "windsock"], ["windsock"])
        self.assertEqual(objs[-1]["kind"], "windsock")
        self.assertEqual(objs[-1]["id"], len(objs))
        self.assertEqual(objs[-1]["sway"], 1.0, "it is leaning in the wind, so its state is not all zeros")

    def test_it_loads_without_the_windsock_and_nothing_else_moves(self):
        st = self.state["objects"]
        saved = [dict(o) for o in st["objs"]]
        n = len(saved) - 1
        sim = Simulation.from_state(self.state)
        self.assertEqual(sim.objects.objs, saved[:n], "the rest of the objects, with their ids, as saved")
        self.assertEqual([o["id"] for o in sim.objects.objs], list(range(1, n + 1)))
        self.assertEqual(len(st["objs"]), n + 1, "the loaded state is not changed")
        f = sim.objects
        self.assertEqual(f.wet_f, st["wet_f"][:n])
        self.assertEqual(f.snow_f, st["snow_f"][:n])
        self.assertEqual(f.sway_f, st["sway_f"][:n])
        self.assertEqual(f.dmg_f, st["dmg_f"][:n])
        self.assertEqual(f.active, {i for i in st["active"] if i < n})
        self.assertEqual(f.size_f, {int(k): v for k, v in st["size_f"].items() if int(k) < n})
        self.assertTrue(f.active and f.size_f, "the fixture has puddles that are wet and shrinking: state keyed by index exists")
        self.assertEqual(len(f.wet_f), len(f.objs))
        hello = sim.hello_message()
        self.assertEqual(len(hello["objects"]), n)
        self.assertNotIn("windsock", {o["kind"] for o in hello["objects"]})
        S.validate_hello(hello)

    def run_old_save(self, day_cycle):
        """The digest of the 150 frames that follow the old save; `day_cycle` False switches Phase O's day cycle of convection
        off (the clouds of the save do not follow it and the sky ignores the hour), True is the game as it runs now."""
        import hashlib
        import atmosphere
        snapshot = atmosphere.Atmosphere.snapshot
        patch = mock.patch.object(atmosphere.Atmosphere, "snapshot",
                                  lambda self, parts, speed, wu, mix, tod=None: snapshot(self, parts, speed, wu, mix, None))
        with (mock.patch.object(atmosphere.Atmosphere, "snapshot", snapshot) if day_cycle else patch):
            sim = Simulation.from_state(self.state)
            if not day_cycle:
                for c in sim.clouds:
                    c.diurnal = False
            return self.old_save_digest(sim)

    def test_the_frames_that_follow_are_exactly_those_the_old_code_produced(self):
        self.assertEqual(self.run_old_save(day_cycle=False), OLD_SAVE_FRAMES_WITHOUT_CARS)

    def test_the_frames_that_follow_with_the_day_cycle_of_convection(self):
        self.assertEqual(self.run_old_save(day_cycle=True), OLD_SAVE_FRAMES_DAY_CYCLE)

    def old_save_digest(self, sim):
        import hashlib
        info = S.validate_hello(sim.hello_message())
        digests = []
        for i in range(150):
            f = sim.step(DT)
            if i < 10:
                S.validate_frame(f, 1280, 720, info)
            # (v2.4.1: a car now reads the depth of the film under it as it read a body's, so in rain the cars slow a little from frame 50 of this save
            # on; they are taken out of the digest, every other key of the frame is still exactly the old code's.) (v2.4 adds `water` / `flood` (and `flow`) to a sandbox stream, and an old save loads with the seed's drains: the digest is of
            # the frames as the old code wrote them, so those keys are taken out first; everything else must be identical)
            digests.append(hashlib.sha256(canon({k: v for k, v in f.items() if k not in ("water", "flood", "drains", "flow", "cars")}).encode()).hexdigest())
            if i == 0:
                self.assertEqual(f["water"], [], "an old save has no standing water")
                self.assertEqual([e["state"] for e in f["flood"]], ["dry"] * len(sim.settlements))
        return hashlib.sha256("".join(digests).encode()).hexdigest()

    def test_round_trip_of_an_old_save_is_frame_identical_and_the_new_save_has_no_windsock(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "again.sav")
            a = Simulation.from_state(self.state)
            for _ in range(45):
                a.step(DT)
            ok, err = save_state(a.to_state(), path)
            self.assertTrue(ok, err)
            again = load_state(path)
            self.assertNotIn("windsock", {o["kind"] for o in again["objects"]["objs"]})
            b = Simulation.from_state(again)
            bad = []
            for i in range(120):
                fb = b.step(DT)
                fa = a.step(DT)
                if i == 0:
                    fb = {k: v for k, v in fb.items() if k not in ("ground", "water", "flood", "flow")}
                    fa = {k: v for k, v in fa.items() if k not in ("ground", "water", "flood", "flow")}
                if canon(fb) != canon(fa):
                    bad.append(i)
            self.assertEqual(bad, [])
            self.assertEqual(canon(b.to_state()), canon(a.to_state()))

    def test_state_that_points_at_the_dropped_windsock_is_dropped_with_it(self):
        st = json.loads(json.dumps(self.state))
        o = st["objects"]
        last = len(o["objs"]) - 1
        o["changed"] = sorted(set(o["changed"]) | {last})
        o["active"] = sorted(set(o["active"]) | {last})
        o["dmg_changed"] = [last]
        o["dmg_f"][last] = 0.5
        o["storm_sway"] = {str(last): 0.7}
        o["size_f"][str(last)] = [8.0, 40.0]
        sim = Simulation.from_state(st)
        f = sim.objects
        self.assertEqual((f.changed, f.dmg_changed, f.storm_sway), (set(), set(), {}))
        self.assertTrue(all(i < last for i in f.active) and all(i < last for i in f.size_f))
        self.assertEqual(len(f.dmg_f), last)
        for _ in range(60):
            fr = sim.step(DT)
            self.assertNotIn("damage", fr)
            self.assertTrue(all(row[0] <= last for row in fr["objects"]))

    def test_a_retired_object_that_is_not_the_last_stays_where_it_is_as_an_inert_row(self):
        """No save the game wrote looks like this (the windsocks were last), so nothing may be renumbered to cope."""
        a = Simulation(5, 1280, 720, 30, "rolling", "normal")
        st = json.loads(json.dumps(a.to_state()))
        st["objects"]["objs"][0]["kind"] = "windsock"          # object 1, a mountain: nothing of it is keyed anywhere
        n = len(st["objects"]["objs"])
        sim = Simulation.from_state(st)
        self.assertEqual(len(sim.objects.objs), n)
        self.assertEqual(sim.objects.objs[0]["kind"], "windsock")
        self.assertEqual(sim.objects.sway_idx.count(0), 0, "nothing sways it")
        self.assertNotIn(0, [i for _x, i in sim.objects.dmg_sorted], "no tornado damages it")
        sim.handle_command({"cmd": "wind", "delta": 4.0}, [])
        for _ in range(90):
            fr = sim.step(DT)
            self.assertNotIn(1, [row[0] for row in fr["objects"]])
        self.assertEqual(sim.objects.objs[0]["sway"], 0.0)


if __name__ == "__main__":
    unittest.main()
