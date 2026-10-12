#!/usr/bin/env python3
"""Write assets/world/presets.json from sim/presets.py (PROTOCOL.md v2.3, world mode).

    python tools/export_presets.py            # (re)write assets/world/presets.json
    python tools/export_presets.py --check    # exit 1 if the file is out of sync (nothing is written)

The Java viewer reads this file for its region list and its fake world; sim/tests/test_presets.py fails
when it differs from what this tool would write, so rerun it after every change to the presets.
Shape: {"version":1,"presets":[{"id","name","lat","lon","region","country","kind","summary","station",
"annual_temp_c","annual_precip_mm","monthly_temp_c":[12],"highlights":[]}]}  (one preset per line).
The monthly values are the station's real normals, not lapse-corrected to the lowest ground.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "sim"))

import presets  # noqa: E402

OUT_PATH = os.path.join(ROOT, "assets", "world", "presets.json")


def render() -> str:
    """The exact text of presets.json (deterministic, LF line ends, one preset per line)."""
    lines = [json.dumps(e, ensure_ascii=False, separators=(",", ":")) for e in presets.world_entries()]
    return '{"version":1,"presets":[\n' + ",\n".join(lines) + "\n]}\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="only compare, never write; exit 1 when out of sync")
    ap.add_argument("--out", default=OUT_PATH, help="output file (default assets/world/presets.json)")
    args = ap.parse_args(argv)
    text = render()
    try:
        with open(args.out, "r", encoding="utf-8", newline="") as f:
            have = f.read()
    except OSError:
        have = None
    if args.check:
        if have == text:
            print(f"{args.out}: in sync ({len(presets.PRESET_IDS)} presets)")
            return 0
        print(f"{args.out}: OUT OF SYNC with sim/presets.py; run python tools/export_presets.py", file=sys.stderr)
        return 1
    if have == text:
        print(f"{args.out}: unchanged ({len(presets.PRESET_IDS)} presets)")
        return 0
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"{args.out}: wrote {len(presets.PRESET_IDS)} presets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
