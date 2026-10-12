"""Seeded 1-D heightmap terrain (PROTOCOL.md v2).

`y[i]` is the ground-surface y (pixels, y grows downward) at x = i*step. Layered
value noise plus a few broad massifs and valleys give four terrain kinds. The
lowest point of the surface (largest y) always sits exactly on an integer
`ground_y`; relief never exceeds MAX_RELIEF_FRAC of the world height so peaks
stay below the cloud spawn band.
"""
from __future__ import annotations

import math
import random

STEP = 8
KINDS = ("flat", "rolling", "hilly", "mountainous")
GROUND_FRAC = 0.92        # ground_y (lowest point) as a fraction of world height
MAX_RELIEF_FRAC = 0.40    # highest peak is at most this fraction of h above ground_y
MIN_M_PER_PX = 4.0        # presets: a low range is exaggerated at most 4 m of real height per px
PRESET_KIND = "preset"    # Terrain.kind of a region-preset heightmap (PROTOCOL.md v2.2)


class _ValueNoise:
    """Smooth 1-D value noise, lattice spacing 1.0, quintic interpolation."""

    def __init__(self, rng, n):
        self.v = [rng.random() for _ in range(n + 3)]

    def at(self, u):
        i = int(u)
        f = u - i
        f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0)
        a = self.v[i]
        return a + (self.v[i + 1] - a) * f


def _smooth(t):
    if t <= 0.0:
        return 0.0
    if t >= 1.0:
        return 1.0
    return t * t * (3.0 - 2.0 * t)


def profile_m_per_px(range_m: float, h: int) -> float:
    """Vertical scale of a preset heightmap: real metres per pixel of relief."""
    return max(MIN_M_PER_PX, range_m / (MAX_RELIEF_FRAC * h))


def profile_x(km: list, span_km: float, w: int) -> list:
    """World x of each transect control point: stretched over the width and snapped to the heightmap
    grid, so a summit sits exactly on a sample and the surface there is the real height."""
    return [min(float(w), round(k / span_km * w / STEP) * STEP) for k in km]


def _pchip(xs: list, ys: list):
    """Monotone cubic Hermite interpolant (Fritsch-Carlson) through (xs, ys); xs strictly ascending.
    Returns f(x), clamped to the end values outside [xs[0], xs[-1]]."""
    n = len(xs)
    dx = [xs[i + 1] - xs[i] for i in range(n - 1)]
    sl = [(ys[i + 1] - ys[i]) / dx[i] for i in range(n - 1)]
    m = [0.0] * n
    if n == 2:
        m[0] = m[1] = sl[0]
    else:
        for i in range(1, n - 1):
            if sl[i - 1] * sl[i] > 0.0:
                w1 = 2.0 * dx[i] + dx[i - 1]
                w2 = dx[i] + 2.0 * dx[i - 1]
                m[i] = (w1 + w2) / (w1 / sl[i - 1] + w2 / sl[i])
        for end, (d0, d1, s0, s1) in ((0, (dx[0], dx[1], sl[0], sl[1])),
                                      (n - 1, (dx[-1], dx[-2], sl[-1], sl[-2]))):
            v = ((2.0 * d0 + d1) * s0 - d0 * s1) / (d0 + d1)
            if v * s0 <= 0.0:
                v = 0.0
            elif s0 * s1 <= 0.0 and abs(v) > 3.0 * abs(s0):
                v = 3.0 * s0
            m[end] = v

    def f(x):
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        lo, hi = 0, n - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if xs[mid] <= x:
                lo = mid
            else:
                hi = mid
        h_ = dx[lo]
        t = (x - xs[lo]) / h_
        t2 = t * t
        t3 = t2 * t
        return ((2 * t3 - 3 * t2 + 1) * ys[lo] + (t3 - 2 * t2 + t) * h_ * m[lo]
                + (-2 * t3 + 3 * t2) * ys[lo + 1] + (t3 - t2) * h_ * m[lo + 1])
    return f


# kind -> (relief fraction of h for the noise layers, [(wavelength px, weight)],
#          (min, max massif count per 3840 px), massif height frac range,
#          massif half-width range px, valley count per 3840 px)
_KIND_PARAMS = {
    "flat":        (0.012, [(900, 1.0), (300, 0.5), (90, 0.25)], (0, 0), (0, 0), (0, 0), 0),
    "rolling":     (0.085, [(1400, 1.0), (600, 0.5), (240, 0.22), (90, 0.08)], (0, 1), (0.03, 0.06), (500, 900), 0),
    "hilly":       (0.150, [(1200, 1.0), (500, 0.6), (200, 0.3), (80, 0.1)], (1, 3), (0.06, 0.12), (250, 520), 1),
    "mountainous": (0.120, [(1500, 1.0), (600, 0.6), (240, 0.3), (90, 0.12), (40, 0.05)], (3, 5), (0.20, 0.40), (260, 700), 2),
}


class Terrain:
    def __init__(self, w: int, h: int, kind: str, y: list, step: int = STEP):
        self.w = int(w)
        self.h = int(h)
        self.kind = kind
        self.step = int(step)
        self.y = y
        self.protected: list = []     # [(x0, x1)] spans flatten() must leave alone (preset landmarks)
        self.summits: list = []       # the part of `protected` that is a summit: nothing may stand there
        self._refresh()

    def _refresh(self):
        self.inv = 1.0 / self.step
        self.n = len(self.y)
        self.ground_y = int(round(max(self.y)))
        self.min_y = min(self.y)

    # -- generation -----------------------------------------------------------
    @classmethod
    def generate(cls, rng: random.Random, w: int, h: int, kind: str) -> "Terrain":
        if kind not in _KIND_PARAMS:
            raise ValueError(f"unknown terrain kind {kind!r}")
        relief, octaves, (m_lo, m_hi), (mh_lo, mh_hi), (ms_lo, ms_hi), valleys = _KIND_PARAMS[kind]
        n = int(math.ceil(w / STEP)) + 1
        noises = [(wl, wt, _ValueNoise(rng, int(w / wl) + 2)) for wl, wt in octaves]
        wsum = sum(wt for _, wt, _ in noises)

        massifs = []
        per = w / 3840.0
        count = 0
        if m_hi > 0:
            count = max(m_lo, int(round(rng.uniform(m_lo, m_hi) * max(1.0, per))))
        for _ in range(count):
            massifs.append((rng.uniform(w * 0.04, w * 0.96), rng.uniform(mh_lo, mh_hi) * h,
                            rng.uniform(ms_lo, ms_hi), rng.uniform(0.0, 1000.0)))
        wells = []
        for _ in range(int(round(valleys * max(1.0, per)))):
            wells.append((rng.uniform(w * 0.05, w * 0.95), rng.uniform(0.04, 0.09) * h,
                          rng.uniform(250, 520)))
        jag = _ValueNoise(rng, int(w / 60) + 2)

        hgt = []
        for i in range(n):
            x = i * STEP
            s = 0.0
            for wl, wt, nz in noises:
                s += (nz.at(x / wl) - 0.5) * wt
            v = s / wsum * 2.0 * relief * h
            for cx, mh, ms, _p in massifs:
                d = (x - cx) / ms
                if -4.0 < d < 4.0:
                    base = 0.65 * math.exp(-d * d) + 0.35 / (1.0 + d * d * 2.5) ** 2
                    ridge = 0.85 + 0.30 * jag.at(x / 60.0)
                    v += mh * base * ridge
            for cx, depth, ws in wells:
                d = (x - cx) / ws
                if -4.0 < d < 4.0:
                    v -= depth * math.exp(-d * d)
            hgt.append(v)
        lo = min(hgt)
        hgt = [v - lo for v in hgt]
        top = max(hgt)
        cap = MAX_RELIEF_FRAC * h
        if top > cap:
            k = cap / top
            hgt = [v * k for v in hgt]
        g = int(h * GROUND_FRAC)
        t = cls(w, h, kind, [round(g - v, 1) for v in hgt])
        t.normalize()
        return t

    @classmethod
    def from_profile(cls, rng: random.Random, w: int, h: int, km: list, elev_m: list,
                     span_km: float, protect_above_frac: float = 0.3):
        """Heightmap from a real elevation cross-section (PROTOCOL.md v2.2 presets).

        (km[i], elev_m[i]) are control points along the transect (km ascending, first = 0, last =
        span_km); the span is stretched over the world width and the elevation between the points
        follows a monotone cubic (PCHIP) curve, so nothing overshoots between two real heights.
        Real metres become pixels with `m_per_px` = range / (MAX_RELIEF_FRAC * h) -- the real
        profile is compressed to the relief cap, but a low range is never exaggerated beyond
        MIN_M_PER_PX. Small seeded detail noise is added on top; it only ever raises the ground and
        fades to nothing at every control point, so landmark heights and the lowest ground
        (sea level) stay exact. Returns (terrain, m_per_px, [x of each control point])."""
        lo, hi = min(elev_m), max(elev_m)
        m_per_px = profile_m_per_px(hi - lo, h)
        n = int(math.ceil(w / STEP)) + 1
        xs = profile_x(km, span_km, w)
        curve = _pchip(xs, [(e - lo) / m_per_px for e in elev_m])
        relief_px = (hi - lo) / m_per_px
        amp = 0.02 * relief_px
        octaves = [(260.0, 0.65, _ValueNoise(rng, int(w / 260.0) + 2)),
                   (95.0, 0.35, _ValueNoise(rng, int(w / 95.0) + 2))]
        taper = 140.0
        hgt = []
        for i in range(n):
            x = i * STEP
            base = curve(x)
            d = min(abs(x - cx) for cx in xs)
            if d < taper:
                f = d / taper
                fade = f * f * (3.0 - 2.0 * f)
            else:
                fade = 1.0
            nz = sum(wt * nz_.at(x / wl) for wl, wt, nz_ in octaves)
            hgt.append(base + amp * fade * nz)
        g = int(h * GROUND_FRAC)
        t = cls(w, h, PRESET_KIND, [round(g - v, 1) for v in hgt])
        t.normalize()
        # summits (+-120 px) and the lowest ground (+-64 px, the sea level the scale is measured from)
        # must not be levelled or built on: that would move the landmark heights / shift the profile
        t.protected = [(max(0.0, x - (120.0 if e > lo else 64.0)), min(float(w), x + (120.0 if e > lo else 64.0)))
                       for x, e in zip(xs, elev_m) if e - lo > protect_above_frac * (hi - lo) or e == lo]
        # (a town may stand on the lowest ground: flatten() leaves those samples alone, so its height stays real)
        t.summits = [(max(0.0, x - 120.0), min(float(w), x + 120.0))
                     for x, e in zip(xs, elev_m) if e > lo and e - lo > protect_above_frac * (hi - lo)]
        return t, m_per_px, xs

    def normalize(self):
        """Shift so the lowest point is exactly the integer ground line and round."""
        g = int(self.h * GROUND_FRAC)
        shift = g - max(self.y)
        y = [round(v + shift, 1) for v in self.y]
        y[y.index(max(y))] = float(g)
        self.y = y
        self._refresh()

    def cap_relief(self) -> int:
        """Keep the finished heightmap inside the documented relief cap (MAX_RELIEF_FRAC * h above the lowest
        ground). `generate` makes it exact, but a pond is dug by `settlements.plan_ponds` to 5 px below the
        lowest ground within its reach, and in a mountain world that can be below the lowest ground of the whole
        map: `normalize` then moves the peaks up with it and the relief grows by up to 5 px. Ground deeper than
        the cap allows is levelled off at the cap (only a pond pad and its shoulders are ever that deep).
        Only for the four generated kinds; a region preset's heights are real ones. 0.1 px of rounding is
        allowed before it acts, so a world that is already within the cap is not touched. Returns the samples lowered.
        Call before `normalize()`."""
        if self.kind not in KINDS:
            return 0
        top = min(self.y)
        lim = top + MAX_RELIEF_FRAC * self.h
        if max(self.y) <= lim + 0.1:
            return 0
        n = 0
        for i, v in enumerate(self.y):
            if v > lim:
                self.y[i] = lim
                n += 1
        return n

    # -- sampling -------------------------------------------------------------
    def height_at(self, x: float) -> float:
        """Surface y at world x (linear interpolation, clamped to the world)."""
        f = x * self.inv
        if f <= 0.0:
            return self.y[0]
        i = int(f)
        if i >= self.n - 1:
            return self.y[-1]
        y0 = self.y[i]
        return y0 + (self.y[i + 1] - y0) * (f - i)

    def slope_at(self, x: float) -> float:
        """dy/dx of the surface (negative = ground rises to the right)."""
        f = x * self.inv
        i = int(f)
        if i < 0:
            i = 0
        elif i > self.n - 2:
            i = self.n - 2
        return (self.y[i + 1] - self.y[i]) * self.inv

    def span_range(self, x0: float, x1: float) -> float:
        """Max-min of the surface over [x0, x1] (flatness score)."""
        i0 = max(0, int(x0 * self.inv))
        i1 = min(self.n - 1, int(math.ceil(x1 * self.inv)))
        seg = self.y[i0:i1 + 1] or [self.y[0]]
        return max(seg) - min(seg)

    def mean_over(self, x0: float, x1: float) -> float:
        i0 = max(0, int(math.ceil(x0 * self.inv)))
        i1 = min(self.n - 1, int(x1 * self.inv))
        seg = self.y[i0:i1 + 1] or [self.height_at((x0 + x1) / 2.0)]
        return sum(seg) / len(seg)

    # -- editing (generation time only; callers must normalize() afterwards) ----
    def flatten(self, x0: float, x1: float, level: float | None = None, margin: float = 60.0):
        """Level [x0, x1] to a plateau and blend smoothly into the old surface over `margin`."""
        if level is None:
            level = self.mean_over(x0, x1)
        ia = max(0, int((x0 - margin) * self.inv))
        ib = min(self.n - 1, int(math.ceil((x1 + margin) * self.inv)))
        step = self.step
        prot = self.protected
        for i in range(ia, ib + 1):
            x = i * step
            if prot and any(a <= x <= b for a, b in prot):
                continue
            if x0 <= x <= x1:
                self.y[i] = level
            else:
                d = (x0 - x) if x < x0 else (x - x1)
                wgt = _smooth(1.0 - d / margin)
                if wgt > 0.0:
                    self.y[i] = level * wgt + self.y[i] * (1.0 - wgt)
        return level

    # -- state ----------------------------------------------------------------
    def to_state(self) -> dict:
        return {"w": self.w, "h": self.h, "kind": self.kind, "step": self.step, "y": list(self.y)}

    @classmethod
    def from_state(cls, s: dict) -> "Terrain":
        return cls(int(s["w"]), int(s["h"]), str(s["kind"]), [float(v) for v in s["y"]], int(s["step"]))
