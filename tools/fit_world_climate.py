#!/usr/bin/env python3
"""Fit the temperature constants (worldsim.FIT) to the cited station normals in sim/presets.py.

    python tools/fit_world_climate.py            # print the fitted FIT dict and the residual of every station

The model is sim/worldsim.py:zonal_temp(): a zonal annual mean A + B cos^2 + C cos^4 (latitude), plus a
seasonal swing |sin lat|^p * tanh(lat / 6) that is larger over land (`sl`, phase `phl`) than over sea (`so`,
phase `pho`), weighted by the cell's land fraction from the Natural Earth mask, plus `ice` degrees over land
poleward of 66-78 deg (the ice sheets stand 2-3 km high; elevation is otherwise ignored; McMurdo is the only
polar-land station in the data, so `ice` is nearly a single-station fit). The data are the twelve
monthly mean temperatures of the presets' stations, lapse-corrected to sea level (6.5 C/km, the station
elevation the cited page gives; stations above 1000 m are left out because the lapse rate is not the
right correction on a plateau). Stdlib only: plain coordinate descent on the RMSE. Paste the printed dict
into worldsim.py. The residuals are the model's honest error: it is a smooth zonal climate, not a
station-exact one (the location readouts use the station normals themselves).
"""
from __future__ import annotations

import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "sim"))

import presets as P  # noqa: E402
import worldsim as W  # noqa: E402

ELEV_LIMIT_M = 1000.0


def dataset() -> list:
    mask = W.blur_mask(W.load_landmask())
    rows = []
    for pid in P.PRESET_IDS:
        p = P.PRESETS[pid]
        c = p["climate"]
        elev = c["elev_m"] if c["elev_m"] is not None else 0.0
        if elev > ELEV_LIMIT_M:
            continue
        r, col = W.cell_of(p["lat"], p["lon"])
        fl = mask[r][col]
        sea = [t + P.LAPSE_C_PER_KM * elev / 1000.0 for t in c["monthly_temp_c"]]
        rows.append((pid, p["lat"], fl, sea))
    return rows


def rmse(fit: dict, rows: list) -> float:
    se = n = 0
    for _pid, lat, fl, sea in rows:
        for m, t in enumerate(sea):
            d = W.zonal_temp(lat, (m + 0.5) / 12.0, fl, fit) - t
            se += d * d
            n += 1
    return math.sqrt(se / n)


def fit(rows: list, start: dict) -> dict:
    cur = dict(start)
    steps = {"A": 4.0, "B": 6.0, "C": 6.0, "sl": 3.0, "so": 3.0, "p": 0.2, "phl": 0.02, "pho": 0.02, "ice": 4.0}
    best = rmse(cur, rows)
    for _ in range(400):
        improved = False
        for k in cur:
            for sgn in (1.0, -1.0):
                trial = dict(cur)
                trial[k] = cur[k] + sgn * steps[k]
                e = rmse(trial, rows)
                if e < best - 1e-9:
                    cur, best, improved = trial, e, True
        if not improved:
            for k in steps:
                steps[k] *= 0.5
            if max(steps["A"], steps["B"]) < 1e-3:
                break
    return {k: round(v, 3) for k, v in cur.items()}


def main() -> int:
    rows = dataset()
    out = fit(rows, W.FIT)
    print(f"stations used: {len(rows)} ({', '.join(r[0] for r in rows)})")
    print("FIT =", out)
    print(f"RMSE {rmse(out, rows):.2f} C over {len(rows) * 12} monthly means")
    for pid, lat, fl, sea in rows:
        res = [W.zonal_temp(lat, (m + 0.5) / 12.0, fl, out) - t for m, t in enumerate(sea)]
        ann = sum(res) / 12.0
        print(f"  {pid:15s} lat {lat:7.2f} land {fl:4.2f}  mean bias {ann:6.2f}  worst month {max(res, key=abs):6.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
