"""Wire-protocol helpers: rounding + writing one JSON object per line to stdout.

PROTOCOL.md is authoritative. Python must never print anything but protocol JSON
lines to stdout -- all debug/log output goes to stderr (see precip_sim.py).
"""
from __future__ import annotations

import json
import sys


def r1(x: float) -> float:
    """Round to 1 decimal, the precision PROTOCOL.md requires for particle floats."""
    return round(float(x) + 0.0, 1)


def encode_message(obj: dict) -> str:
    """One compact JSON line (with the trailing newline)."""
    return json.dumps(obj, separators=(",", ":")) + "\n"


def write_message(obj: dict) -> None:
    """Serialize obj as one compact JSON line to stdout and flush immediately.

    The line goes out in a single write so the reader never wakes up on a half frame
    (with ``python -u`` every write call is its own pipe write).
    """
    sys.stdout.write(encode_message(obj))
    sys.stdout.flush()
