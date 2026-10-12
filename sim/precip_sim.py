#!/usr/bin/env python3
"""Precipitation sim entry point (protocol v2).

Python owns the simulation and streams protocol JSON on stdout, one object per line
(see PROTOCOL.md). Commands come from Java on stdin, read on a background thread, and
never anything but protocol JSON reaches stdout -- all logging goes to stderr.

Modes (v2.2): sandbox (default; optionally a region --preset), showcase (one cloud type at a
time, --cloud-type) and hurricane (sim/hurricane.py, imported only when asked for).
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from clouds import TYPE_NAMES as CLOUD_TYPES
from presets import PRESET_IDS
from protocol_io import write_message
from savestate import StateError, load_state
from simulation import Simulation
from settlements import MODES as SETTLEMENT_MODES
from terrain import KINDS as TERRAIN_KINDS
from water import MODES as DRAIN_MODES

MIN_W, MAX_W = 1280, 9600
MIN_H, MAX_H = 720, 1440
MODES = ("sandbox", "showcase", "hurricane", "world")
DEFAULT_SIZE = {"sandbox": (3840, 1080), "showcase": (1920, 1080), "hurricane": (1920, 1080),
                "world": (1920, 1080)}


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Precipitation 2D sim (protocol v2)")
    p.add_argument("--seed", type=int, default=None, help="RNG seed (default: time-based)")
    p.add_argument("--width", type=int, default=None,
                   help=f"world width, {MIN_W}-{MAX_W} (default 3840; 1920 in showcase mode)")
    p.add_argument("--height", type=int, default=None, help=f"world height, {MIN_H}-{MAX_H} (default 1080)")
    p.add_argument("--mode", choices=MODES, default="sandbox",
                   help="sandbox (default), showcase (one cloud type at a time), hurricane or world "
                        "(the whole Earth, sim/worldsim.py)")
    p.add_argument("--preset", choices=PRESET_IDS, default=None,
                   help="sandbox only: real elevation cross-section and climate of a region (replaces --terrain)")
    p.add_argument("--cloud-type", choices=CLOUD_TYPES, default="cumulus",
                   help="showcase only: the cloud type on stage")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--terrain", choices=TERRAIN_KINDS, default="rolling")
    p.add_argument("--settlements", choices=SETTLEMENT_MODES, default="normal")
    p.add_argument("--drains", choices=DRAIN_MODES, default="normal",
                   help="sandbox only: how the settlements' storm-drain sizes are chosen (v2.4): small, normal "
                        "(by settlement kind, seeded), large or random; no effect with --settlements none; "
                        "--load keeps the saved choice")
    p.add_argument("--load", metavar="FILE", default=None,
                   help="restore a state written by the save command (seed/size/terrain/settlements ignored)")
    p.add_argument("--headless-frames", type=int, default=None,
                   help="Run N frames with no stdin, print frames, exit (tests/benchmarking).")
    p.add_argument("--dump-every", type=int, default=1, help="Print only every Kth frame.")
    p.add_argument("--month", type=float, default=None,
                   help="calendar month the world starts in, 1..12.99 (1.0 = 1 January, 7.5 = mid July; "
                        "default: the preset's start month, else 4)")
    p.add_argument("--hour", type=float, default=None,
                   help="local solar hour the world starts at, 0..24 (default 7.2 = mid-morning)")
    p.add_argument("--year-days", type=int, default=None, dest="year_days",
                   help="sim days that make one year, 2..365 (default 8; one sim day is 300 s)")
    args = p.parse_args(argv)
    if args.month is not None and not (1.0 <= args.month < 13.0):
        p.error(f"--month must be between 1 and 12.99 (got {args.month})")
    if args.hour is not None and not (0.0 <= args.hour <= 24.0):
        p.error(f"--hour must be between 0 and 24 (got {args.hour})")
    if args.year_days is not None and not (2 <= args.year_days <= 365):
        p.error(f"--year-days must be between 2 and 365 (got {args.year_days})")
    dw, dh = DEFAULT_SIZE[args.mode]
    if args.width is None:
        args.width = dw
    if args.height is None:
        args.height = dh
    if args.load is None:
        if not (MIN_W <= args.width <= MAX_W):
            p.error(f"--width must be between {MIN_W} and {MAX_W} (got {args.width})")
        if not (MIN_H <= args.height <= MAX_H):
            p.error(f"--height must be between {MIN_H} and {MAX_H} (got {args.height})")
    return args


def _stdin_reader(cmd_queue: "queue.Queue", stop_event: threading.Event) -> None:
    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                cmd = json.loads(line)
            except ValueError:
                continue
            if isinstance(cmd, dict):
                cmd_queue.put(cmd)
    except Exception as exc:  # pragma: no cover - defensive
        log(f"stdin reader error: {exc}")
    finally:
        stop_event.set()  # stdin closed (Java died, or EOF) -> exit cleanly


def build_sim(args, seed: int, fps: int):
    """The driver for --mode. Every driver offers hello_message(), step(dt, extra_events),
    handle_command(cmd, events), drain_outbox() and the attributes running / frame / seed."""
    if args.mode == "hurricane":
        try:
            from hurricane import HurricaneSim      # imported lazily: the other modes never need it
        except ImportError as exc:
            raise StartupError(f"precip_sim: --mode hurricane needs sim/hurricane.py, which cannot be "
                              f"imported ({exc})") from None
        return HurricaneSim(seed, fps)
    if args.mode == "world":
        try:
            from worldsim import WorldSim           # imported lazily, like hurricane
        except ImportError as exc:
            raise StartupError(f"precip_sim: --mode world needs sim/worldsim.py, which cannot be "
                              f"imported ({exc})") from None
        try:
            return WorldSim(seed, fps, month=getattr(args, "month", None), hour=getattr(args, "hour", None),
                            year_days=getattr(args, "year_days", None) or 8)
        except (OSError, ValueError) as exc:     # e.g. the land mask data file is missing
            raise StartupError(f"precip_sim: --mode world cannot start: {exc}") from None
    if args.mode == "showcase":
        from showcase import ShowcaseSimulation
        return ShowcaseSimulation(seed, args.width, args.height, fps, args.cloud_type)
    return Simulation(seed, args.width, args.height, fps, args.terrain, args.settlements, args.preset,
                      month=args.month, hour=7.2 if args.hour is None else args.hour,
                      year_days=args.year_days or 8, drains=args.drains)


class StartupError(Exception):
    """A start-up failure with a one-line message for stderr (exit status 2)."""


def flush_outbox(sim: Simulation) -> None:
    """Messages produced by commands (a fresh hello after reseed, saved replies), between frames."""
    for msg in sim.drain_outbox():
        write_message(msg)
        if msg.get("type") == "hello":
            log(f"reseed -> new hello emitted, seed={sim.seed}")


def run_headless(sim: Simulation, n_frames: int, dump_every: int, dt_fixed: float) -> None:
    for _ in range(n_frames):
        msg = sim.step(dt_fixed)
        if sim.frame % dump_every == 0:
            write_message(msg)
        if not sim.running:
            break


def run_interactive(sim: Simulation, dump_every: int, dt_fixed: float) -> None:
    cmd_queue: "queue.Queue" = queue.Queue()
    stop_event = threading.Event()
    reader = threading.Thread(target=_stdin_reader, args=(cmd_queue, stop_event), daemon=True)
    reader.start()

    # Fixed-deadline pacing: frame k is due at start + k*dt, so sleep overshoot does not
    # accumulate (the old "sleep the rest of this frame" loop drifted to ~29.2 fps).
    next_due = time.perf_counter()
    while sim.running and not stop_event.is_set():

        extra_events: list = []
        while True:
            try:
                cmd = cmd_queue.get_nowait()
            except queue.Empty:
                break
            sim.handle_command(cmd, extra_events)
            flush_outbox(sim)   # saved / hello replies go out between frames, in order

        msg = sim.step(dt_fixed, extra_events=extra_events)
        if sim.frame % dump_every == 0:
            write_message(msg)

        if not sim.running:
            break

        next_due += dt_fixed
        remaining = next_due - time.perf_counter()
        if remaining > 0:
            time.sleep(remaining)
        elif remaining < -0.25:
            next_due = time.perf_counter()  # fell far behind (e.g. suspended): resync, no burst

    log("precip_sim exiting (stdin closed or quit command)")


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        # PROTOCOL.md: UTF-8 lines ending in a bare LF (Windows text mode would write CR LF)
        sys.stdout.reconfigure(newline="\n", encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    fps = max(1, args.fps)
    dt_fixed = 1.0 / fps
    dump_every = max(1, args.dump_every)

    if args.load is not None:
        try:
            sim = Simulation.from_state(load_state(args.load))
        except StateError as exc:
            log(f"precip_sim: cannot load state: {exc}")
            return 2
        except (KeyError, TypeError, ValueError, IndexError, AttributeError) as exc:
            log(f"precip_sim: cannot load state: {args.load} is corrupt or incompatible "
                f"({type(exc).__name__}: {exc})")
            return 2
        sim.fps = fps
        log(f"precip_sim loaded {args.load}: seed={sim.seed} size={sim.world.w}x{sim.world.h} "
            f"t={sim.world.t:.1f} frame={sim.frame}")
    else:
        seed = args.seed if args.seed is not None else int(time.time() * 1000) & 0xFFFFFFFF
        try:
            sim = build_sim(args, seed, fps)
        except StartupError as exc:
            log(str(exc))
            return 2
        extra = f" preset={args.preset}" if args.preset else ""
        terrain, settlements = ("preset" if args.preset else args.terrain), args.settlements
        if args.mode == "showcase":
            extra = f" cloud-type={args.cloud_type}"
            terrain, settlements = "flat", "none"      # the stage ignores --terrain / --settlements (it said rolling / normal)
        if args.mode == "sandbox":
            extra += f" drains={args.drains}"
        log(f"precip_sim started: mode={args.mode} seed={seed} size={args.width}x{args.height} fps={fps} "
            f"terrain={terrain} settlements={settlements}{extra}")
    write_message(sim.hello_message())

    try:
        if args.headless_frames is not None:
            run_headless(sim, args.headless_frames, dump_every, dt_fixed)
        else:
            run_interactive(sim, dump_every, dt_fixed)
    except (BrokenPipeError, KeyboardInterrupt):
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
