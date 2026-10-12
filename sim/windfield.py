"""Wind grid: prevailing wind + gusts + terrain-driven orographic lift, and streamlines.

Protocol shape (frozen, PROTOCOL.md v2): wind.cols/rows/cell/u/v, row-major,
cell (c, r) centred at ((c+0.5)*cell, (r+0.5)*cell). Positive u = right,
positive v = down. `cell` grows for big worlds so the grid stays affordable.

Terrain coupling: flow is deflected along the ground. A column's `lift` factor is
the surface slope there (dy/dx, negative where the ground rises to the right) faded
out with height above the surface, so v = u * lift is negative (upward) on windward
slopes and positive (downward) on lee slopes. The factor is static per world and is
computed once; only the cheap gust terms are rebuilt per tick.
"""
from __future__ import annotations

import math

CELLS = (80, 100, 120, 160, 200, 240)
MAX_CELLS = 800
ALOFT_GAIN = 0.55        # wind aloft is stronger: u *= 1 - ALOFT_GAIN/2 .. 1 + ALOFT_GAIN/2


def choose_grid(w: int, h: int):
    """(cols, rows, cell): smallest standard cell keeping the grid under MAX_CELLS cells."""
    for cell in CELLS:
        cols = max(2, math.ceil(w / cell))
        rows = max(2, math.ceil(h / cell))
        if cols * rows <= MAX_CELLS:
            return cols, rows, cell
    cell = CELLS[-1]
    return max(2, math.ceil(w / cell)), max(2, math.ceil(h / cell)), cell


class WindField:
    __slots__ = ("cols", "rows", "cell", "u", "v", "inv", "mean_mag")

    def __init__(self, cols, rows, cell, u, v, mean_mag=0.0):
        self.cols = cols
        self.rows = rows
        self.cell = cell
        self.u = u
        self.v = v
        self.inv = 1.0 / cell
        self.mean_mag = mean_mag

    def sample(self, x: float, y: float):
        """Bilinear-interpolated (u, v) in m/s at world point (x, y)."""
        inv = self.inv
        cols = self.cols
        rows = self.rows
        fx = x * inv - 0.5
        fy = y * inv - 0.5
        c0 = int(fx) if fx > 0.0 else 0
        r0 = int(fy) if fy > 0.0 else 0
        if c0 > cols - 1:
            c0 = cols - 1
        if r0 > rows - 1:
            r0 = rows - 1
        c1 = c0 + 1 if c0 < cols - 1 else c0
        r1 = r0 + 1 if r0 < rows - 1 else r0
        tx = fx - c0
        ty = fy - r0
        tx = 0.0 if tx < 0.0 else (1.0 if tx > 1.0 else tx)
        ty = 0.0 if ty < 0.0 else (1.0 if ty > 1.0 else ty)
        u = self.u
        v = self.v
        i00 = r0 * cols + c0
        i10 = r0 * cols + c1
        i01 = r1 * cols + c0
        i11 = r1 * cols + c1
        a = u[i00] + (u[i10] - u[i00]) * tx
        b = u[i01] + (u[i11] - u[i01]) * tx
        c = v[i00] + (v[i10] - v[i00]) * tx
        d = v[i01] + (v[i11] - v[i01]) * tx
        return a + (b - a) * ty, c + (d - c) * ty

    def to_protocol(self) -> dict:
        return {"cols": self.cols, "rows": self.rows, "cell": self.cell, "u": self.u, "v": self.v}

    def to_state(self) -> dict:
        return {"cols": self.cols, "rows": self.rows, "cell": self.cell,
                "u": list(self.u), "v": list(self.v), "mean_mag": self.mean_mag}

    @classmethod
    def from_state(cls, s: dict) -> "WindField":
        return cls(int(s["cols"]), int(s["rows"]), int(s["cell"]),
                   [float(x) for x in s["u"]], [float(x) for x in s["v"]], float(s["mean_mag"]))


class WindModel:
    """Static per-world part of the wind (grid shape, terrain lift, height factor)."""

    def __init__(self, world, terrain):
        self.cols, self.rows, self.cell = choose_grid(world.w, world.h)
        self.gust_scale = 1.0       # 1.0 = sandbox gusts; showcase mode turns them down (steady wind)
        cols, rows, cell = self.cols, self.rows, self.cell
        ground = float(terrain.ground_y)
        self.col_cx = [(c + 0.5) * cell for c in range(cols)]
        self.row_cy = [(r + 0.5) * cell for r in range(rows)]
        fade = max(90.0, 0.16 * world.h)
        # lift[r*cols+c]: slope * fade-with-height (0 below the surface)
        self.lift = [0.0] * (cols * rows)
        for c in range(cols):
            cx = self.col_cx[c]
            slope = (terrain.height_at(cx + cell * 0.5) - terrain.height_at(cx - cell * 0.5)) / cell
            slope = max(-1.2, min(1.2, slope))
            surf = terrain.height_at(cx)
            for r in range(rows):
                above = surf - self.row_cy[r]
                if above < 0.0:
                    f = 0.0
                else:
                    f = math.exp(-above / fade)
                self.lift[r * cols + c] = slope * f * 0.85
        # stronger wind higher up (also drives cloud shear)
        self.height_gain = []
        for r in range(rows):
            alt = max(0.0, min(1.0, (ground - self.row_cy[r]) / max(1.0, ground)))
            self.height_gain.append(1.0 - ALOFT_GAIN * 0.5 + ALOFT_GAIN * alt)

    def build(self, t: float, prevailing_u: float, prevailing_v: float, gust_phase: float) -> WindField:
        cols, rows = self.cols, self.rows
        sin = math.sin
        cos = math.cos
        col_cx = self.col_cx
        row_cy = self.row_cy
        gs = self.gust_scale
        gu_c = [1.3 * gs * sin(cx * 0.010 + t * 0.6 + gust_phase) for cx in col_cx]
        gv_c = [0.5 * gs * cos(cx * 0.015 - t * 0.4 + gust_phase * 0.9) for cx in col_cx]
        gu_r = [0.6 * gs * sin(cy * 0.021 - t * 0.35 + gust_phase * 1.7) for cy in row_cy]
        lift = self.lift
        u = [0.0] * (cols * rows)
        v = [0.0] * (cols * rows)
        total = 0.0
        i = 0
        for r in range(rows):
            hg = self.height_gain[r]
            gur = gu_r[r]
            base_u = prevailing_u * hg
            for c in range(cols):
                uu = base_u + gu_c[c] + gur
                vv = prevailing_v + gv_c[c] + uu * lift[i]
                uu = round(uu, 2)
                vv = round(vv, 2)
                u[i] = uu
                v[i] = vv
                total += math.sqrt(uu * uu + vv * vv)
                i += 1
        return WindField(cols, rows, self.cell, u, v, total / (cols * rows))


def build_streamlines(wind: WindField, world, terrain, rng, count: int, steps: int = 24):
    """Trace polylines through the field for the viewer's decorative overlay.
    Lines start anywhere along the world and stay above the terrain surface."""
    lines = []
    step_len = 20.0
    sample = wind.sample
    height_at = terrain.height_at
    for _ in range(count):
        x = rng.uniform(0.0, world.w * 0.96)
        top = height_at(x) - 40.0
        if top < 30.0:
            top = 30.0
        y = rng.uniform(10.0, top)
        pts = [[round(x, 1), round(y, 1)]]
        speed_acc = 0.0
        for _ in range(steps):
            su, sv = sample(x, y)
            mag = math.hypot(su, sv)
            speed_acc += mag
            mag += 1e-6
            x += su / mag * step_len
            y += sv / mag * step_len * 0.4
            if x < 0 or x > world.w or y < 0 or y > height_at(x) - 4.0:
                break
            pts.append([round(x, 1), round(y, 1)])
        if len(pts) >= 2:
            lines.append({
                "pts": pts,
                "speed": round(speed_acc / max(1, len(pts) - 1), 1),
                "phase": round(rng.uniform(0, 1), 2),
            })
    return lines


def mean_wind_magnitude(wind: WindField) -> float:
    return wind.mean_mag
