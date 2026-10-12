"""Versioned, atomic save files for the simulation.

The file is plain JSON (never pickle, so loading cannot execute code): a small header plus
the dict produced by `Simulation.to_state()`. Each simulation class contributes its own
`to_state()` / `from_state()`, including every `random.Random.getstate()`, so a loaded
world continues frame for frame as if it had never stopped.

The format version stays 2 across v2.1 / v2.2 / v2.3: every newer key is optional on load and a file
without it gets the old behaviour (v2.3 adds the world's climate, calendar and gases, the spawn hazard
and per-cloud merge state; a v2.2 file loads with the default climate tuned to its saved temperature,
default gases and no merges).

Writing is atomic: the JSON goes to a temp file next to the target, is flushed to disk and
then `os.replace`d over it, so a crash or error leaves the previous file untouched.
"""
from __future__ import annotations

import json
import os
import tempfile

FORMAT = "precipitation-sim-state"
VERSION = 2


class StateError(Exception):
    """The state file is missing, corrupt or from an incompatible version."""


def save_state(state: dict, path: str):
    """Write `state` to `path` atomically. Returns (ok, error_message_or_None)."""
    tmp = None
    try:
        path = os.fspath(path)
        directory = os.path.dirname(os.path.abspath(path))
        os.makedirs(directory, exist_ok=True)
        doc = {"format": FORMAT, "version": VERSION, "state": state}
        # allow_nan=False: NaN/Infinity would make an invalid file, refuse instead of writing it
        text = json.dumps(doc, separators=(",", ":"), allow_nan=False)
        fd, tmp = tempfile.mkstemp(prefix=".state-", suffix=".tmp", dir=directory)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        tmp = None
        return True, None
    except Exception as exc:  # noqa: BLE001 - any failure is reported, never raised into the loop
        return False, f"{type(exc).__name__}: {exc}"
    finally:
        if tmp is not None:
            try:
                os.unlink(tmp)
            except OSError:
                pass


def load_state(path: str) -> dict:
    """Read and validate a state file; returns the inner state dict. Raises StateError."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            doc = json.load(f)
    except FileNotFoundError:
        raise StateError(f"state file not found: {path}") from None
    except (OSError, UnicodeDecodeError) as exc:
        raise StateError(f"cannot read state file {path}: {exc}") from None
    except ValueError as exc:
        raise StateError(f"state file {path} is corrupt (not valid JSON: {exc})") from None
    if not isinstance(doc, dict) or doc.get("format") != FORMAT:
        raise StateError(f"{path} is not a Precipitation state file")
    ver = doc.get("version")
    if ver != VERSION:
        raise StateError(f"incompatible state version {ver!r} in {path} (this build reads version {VERSION})")
    state = doc.get("state")
    if not isinstance(state, dict):
        raise StateError(f"state file {path} has no state")
    return state
