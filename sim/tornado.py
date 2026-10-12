"""Tornadoes (protocol v2.3 "Tornadoes"): the life cycle of one vortex under a supercell, the wind it
puts into the grid, and the numbers other modules need to damage, toss and sweep things.

Life cycle (each `stage` is a function of the time spent in it, so every number is smooth):

    funnel      the funnel cloud descends from the wall cloud: `reach` 0 -> 1 (smoothstep over `funnel_s`)
    touchdown   on the ground (reach 1); the vortex swells from its funnel size to full size (`touch_s`)
    mature      full size, a slow pulsation, runs for `mature_s`
    roping      `width` shrinks, |`lean`| grows, `reach` falls back; removed once `width` < ROPE_GONE_PX

Roping begins early when the parent supercell starts dissipating (or is gone). Whatever the stage a
tornado is in when roping starts, roping shrinks *the numbers it has right now* (a snapshot), so there
is no step in any field at the change.

Strength -> wind -> Enhanced Fujita rating
    wind_ms = WIND0 + WIND_K * strength        (strength 0..1, the 3-second gust at the vortex, m/s)
    ef      = the highest EF_MS threshold the gust reaches (0 below the first one)
The EF thresholds are the 3-second-gust wind speed ranges of the Enhanced Fujita Scale: Texas Tech
University Wind Science and Engineering Research Center, Fujita Scale Enhancement Project (developed 2000-2004,
unveiled 4 October 2004, in operational use in the US since 1 February 2007; NWS Storm Prediction Center, "Enhanced
Fujita Scale"; the same table is on Wikipedia, "Enhanced Fujita scale"): EF0 65-85 mph, EF1 86-110, EF2 111-135,
EF3 136-165, EF4 166-200, EF5 201+ mph. In m/s (1 mph = 0.44704 m/s) the lower bounds of EF0..EF5 are
29.1, 38.4, 49.6, 60.8, 74.2, 89.9.

The tornado's `x` is the ground contact point; its top is attached under the wall cloud (x_top follows the
parent's meso_x plus a slow wander), so `x = x_top - lean` and a roping tornado leans its foot away.
"""
from __future__ import annotations

import math
import random

STAGES = ("funnel", "touchdown", "mature", "roping")
EF_MS = (29.1, 38.4, 49.6, 60.8, 74.2, 89.9)       # lower bound of EF0 .. EF5 (m/s, 3-second gust)
WIND0 = 18.0
WIND_K = 88.0
ROPE_GONE_PX = 0.8
GROUND_REACH = 0.9              # the vortex's effects fade in between this reach and 1
# peak-rating draw for a sandbox tornado: EF0..EF5
EF_WEIGHTS = (0.30, 0.32, 0.20, 0.11, 0.06, 0.01)
# durations in seconds (funnel, touchdown, mature, roping): drawn per tornado inside these ranges
FUNNEL_S = (13.0, 17.0)
TOUCH_S = (3.0, 5.0)
MATURE_S = (38.0, 56.0)
ROPE_S = (15.0, 21.0)


def ef_from_wind(ms: float) -> int:
    ef = 0
    for i, lo in enumerate(EF_MS):
        if ms >= lo:
            ef = i
    return ef if ms >= EF_MS[0] else 0


def wind_from_strength(s: float) -> float:
    return WIND0 + WIND_K * s


def strength_for_ef(ef: int, u: float) -> float:
    """The strength that gives a gust inside EF band `ef` (u 0..1 picks where inside the band)."""
    lo = EF_MS[ef]
    hi = EF_MS[ef + 1] if ef < 5 else 104.0
    return (lo + (hi - 1e-6 - lo) * u - WIND0) / WIND_K


def _s(x):
    """smoothstep"""
    x = 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)
    return x * x * (3.0 - 2.0 * x)


def draw_peak_ef(rng: random.Random) -> int:
    return rng.choices(range(6), weights=EF_WEIGHTS, k=1)[0]


class Tornado:
    __slots__ = (
        # protocol fields
        "id", "cloud", "stage", "x", "base_y", "ground_y", "reach", "width", "top_width", "lean",
        "strength", "ef", "wind_ms", "age", "phase", "debris", "path_x0",
        # internal: plan
        "t_stage", "funnel_s", "touch_s", "mature_s", "rope_s", "s_pk", "w_pk", "tw_pk", "lean_pk",
        "lean_sign", "wa1", "wa2", "wp1", "wp2", "wander0", "hscale",
        # internal: bookkeeping
        "last_meso_x", "last_base_y", "peak_wind", "peak_ef", "path_lo", "path_hi", "env", "rope0",
        "name", "touched", "ended_by_parent")

    # -- creation ---------------------------------------------------------------------------
    @classmethod
    def create(cls, tid, cloud_id, name, seed, peak_ef, meso_x, base_y, terrain, world_h, mature_s=None):
        """A new tornado at funnel stage. All of its randomness comes from `seed`."""
        rng = random.Random(seed)
        t = cls()
        t.id = tid
        t.cloud = cloud_id
        t.name = name
        t.stage = "funnel"
        t.t_stage = 0.0
        t.age = 0.0
        t.hscale = max(1.0, min(1.5, world_h / 720.0))
        t.funnel_s = rng.uniform(*FUNNEL_S)
        t.touch_s = rng.uniform(*TOUCH_S)
        t.mature_s = mature_s if mature_s is not None else rng.uniform(*MATURE_S)
        t.rope_s = rng.uniform(*ROPE_S)
        t.s_pk = strength_for_ef(peak_ef, rng.uniform(0.15, 0.85))
        t.w_pk = (24.0 + 80.0 * t.s_pk + rng.uniform(-6.0, 8.0)) * t.hscale
        t.tw_pk = t.w_pk * rng.uniform(1.9, 2.5) + 18.0 * t.hscale
        t.lean_pk = rng.uniform(26.0, 58.0) * t.hscale
        t.lean_sign = 1.0 if rng.random() < 0.5 else -1.0
        t.wa1 = rng.uniform(16.0, 28.0) * t.hscale
        t.wa2 = rng.uniform(7.0, 14.0) * t.hscale
        t.wp1 = rng.uniform(0.0, 6.283)
        t.wp2 = rng.uniform(0.0, 6.283)
        t.wander0 = t.wa1 * math.sin(t.wp1) + t.wa2 * math.sin(t.wp2)
        t.phase = rng.uniform(0.0, 6.283)
        t.last_meso_x = meso_x
        t.last_base_y = base_y
        t.peak_wind = 0.0
        t.peak_ef = 0
        t.rope0 = None
        t.touched = False
        t.ended_by_parent = False
        t.path_x0 = None
        t.env = 0.0
        t.reach = 0.0
        t.width = t.top_width = t.lean = t.strength = t.wind_ms = t.debris = 0.0
        t.ef = 0
        t.x = meso_x
        t.base_y = base_y
        t.ground_y = terrain.height_at(meso_x)
        t.path_lo = t.path_hi = meso_x
        t._shape(0.0, terrain)
        return t

    # -- the time functions -----------------------------------------------------------------
    def _wander(self):
        a = self.age
        return (self.wa1 * math.sin(0.21 * a + self.wp1) + self.wa2 * math.sin(0.47 * a + self.wp2)
                - self.wander0)

    def _plan(self):
        """(env, reach, lean magnitude) for the stage and time in it (before roping)."""
        st = self.stage
        t = self.t_stage
        if st == "funnel":
            p = t / self.funnel_s
            r = _s(p)
            return 0.10 + 0.20 * r, r, 10.0 + (self.lean_pk * 0.45 - 10.0) * r
        if st == "touchdown":
            q = _s(t / self.touch_s)
            return 0.30 + 0.70 * q, 1.0, self.lean_pk * (0.45 + 0.55 * q)
        # mature
        return 1.0 + 0.06 * math.sin(0.45 * t), 1.0, self.lean_pk * (1.0 + 0.10 * math.sin(0.3 * t))

    def _shape(self, dt, terrain):
        if self.stage == "roping":
            u = min(1.0, self.t_stage / self.rope_s)
            r0, e0, l0 = self.rope0
            g = (1.0 - _s(u)) ** 1.2
            env = e0 * g
            reach = r0 * (1.0 - _s((u - 0.45) / 0.55))
            lean_mag = l0 * (1.0 + 2.6 * _s(u))
        else:
            env, reach, lean_mag = self._plan()
        self.env = env
        self.reach = reach
        self.width = self.w_pk * env
        self.top_width = self.tw_pk * (0.35 + 0.65 * min(1.0, env))
        self.lean = self.lean_sign * lean_mag
        self.strength = min(1.0, self.s_pk * env ** 0.8)
        self.wind_ms = wind_from_strength(self.strength)
        self.ef = ef_from_wind(self.wind_ms)
        self.phase += dt * (2.5 + 9.0 * self.strength)
        g = _s((reach - GROUND_REACH) / (1.0 - GROUND_REACH))
        self.debris = g * min(1.0, env) ** 0.9 * (0.3 + 0.7 * min(1.0, self.strength * 1.4))

    # -- per tick ---------------------------------------------------------------------------
    def update(self, dt, meso_x, attach_y, parent_ok, terrain, world_t, events):
        """Advance by dt. `meso_x` / `attach_y` = where the parent's wall cloud is (None when the parent is
        gone: the last known values hold); `parent_ok` = the parent still feeds it. Returns False when the
        tornado has roped out and must be removed."""
        if meso_x is not None:
            self.last_meso_x = meso_x
            self.last_base_y = attach_y
        self.age += dt
        self.t_stage += dt
        if not parent_ok and self.stage != "roping":
            self.ended_by_parent = True
            self._begin_roping(world_t, events, "its storm is dying")
        # stage changes
        st = self.stage
        if st == "funnel" and self.t_stage >= self.funnel_s:
            self._set_stage("touchdown")
            self.touched = True
            events.append(f"t={world_t:.1f} {self.name}: tornado touchdown at x={self.x:.0f}")
        elif st == "touchdown" and self.t_stage >= self.touch_s:
            self._set_stage("mature")
        elif st == "mature" and self.t_stage >= self.mature_s:
            self._begin_roping(world_t, events, "run its course")
        self._shape(dt, terrain)
        wind = self.wind_ms
        if self.stage != "funnel" and wind > self.peak_wind:
            self.peak_wind = wind
            self.peak_ef = self.ef
        self.base_y = self.last_base_y
        x_top = self.last_meso_x + self._wander()
        self.x = x_top - self.lean
        self.ground_y = max(terrain.height_at(self.x), self.base_y + 40.0)
        if self.touched and self.path_x0 is None and self.reach >= 0.999:
            self.path_x0 = self.x
        if self.path_x0 is not None and self.reach >= GROUND_REACH:
            if self.x < self.path_lo:
                self.path_lo = self.x
            if self.x > self.path_hi:
                self.path_hi = self.x
        if self.stage == "roping" and (self.width < ROPE_GONE_PX or self.t_stage >= self.rope_s):
            self.width = 0.0
            events.append(f"t={world_t:.1f} {self.name}: tornado gone; damage path "
                          f"{self.path_length():.0f} px, peak EF{self.peak_ef} ({self.peak_wind:.0f} m/s)")
            return False
        return True

    def _set_stage(self, st):
        self.stage = st
        self.t_stage = 0.0

    def _begin_roping(self, world_t, events, why):
        self.rope0 = (self.reach, self.env, abs(self.lean))
        self._set_stage("roping")
        events.append(f"t={world_t:.1f} {self.name}: tornado ropes out ({why}; peak EF{self.peak_ef}, "
                      f"{self.peak_wind:.0f} m/s)")

    def path_length(self) -> float:
        return (self.path_hi - self.path_lo) if self.path_x0 is not None else 0.0

    # -- what the rest of the world sees -------------------------------------------------------
    @property
    def ground_factor(self) -> float:
        """0 -> 1 as the funnel reaches the ground: scales every effect on the world."""
        return _s((self.reach - GROUND_REACH) / (1.0 - GROUND_REACH))

    @property
    def core_radius(self) -> float:
        return max(self.width * 0.5, 8.0) * 1.25

    def axis_x(self, y: float) -> float:
        """x of the vortex axis at height y (the foot at ground_y, the top at base_y offset by `lean`)."""
        span = self.ground_y - self.base_y
        f = (self.ground_y - y) / span if span > 1.0 else 0.0
        f = 0.0 if f < 0.0 else (1.0 if f > 1.0 else f)
        return self.x + self.lean * f

    def local_wind(self, d: float) -> float:
        """Wind speed (m/s) at horizontal distance d from the axis near the ground: the core plateau, then
        Rankine-like decay."""
        rc = self.core_radius
        v = self.wind_ms * self.ground_factor
        if d <= rc:
            return v
        return v * (rc / d) ** 1.2

    def to_protocol(self) -> dict:
        d = {"id": self.id, "cloud": self.cloud, "stage": self.stage, "x": round(self.x, 1),
             "base_y": round(self.base_y, 1), "ground_y": round(self.ground_y, 1),
             "reach": round(self.reach, 3), "width": round(self.width, 1),
             "top_width": round(self.top_width, 1), "lean": round(self.lean, 1),
             "strength": round(self.strength, 3), "ef": self.ef, "wind_ms": round(self.wind_ms, 1),
             "age": round(self.age, 1), "phase": round(self.phase, 2), "debris": round(self.debris, 3)}
        if self.path_x0 is not None:
            d["path_x0"] = round(self.path_x0, 1)
        return d

    # -- state ------------------------------------------------------------------------------
    def to_state(self) -> dict:
        s = {k: getattr(self, k) for k in self.__slots__}
        s["rope0"] = list(self.rope0) if self.rope0 is not None else None
        return s

    @classmethod
    def from_state(cls, s: dict) -> "Tornado":
        t = cls()
        for k in cls.__slots__:
            setattr(t, k, s[k])
        t.rope0 = tuple(s["rope0"]) if s["rope0"] is not None else None
        return t


# -- the wind the vortex puts into the grid -------------------------------------------------------
def apply_to_wind(wind, tornadoes) -> None:
    """Add every grounded tornado's convergence, updraft and swirl to the wind grid, touching only the
    cells near it (rows from the cloud base to the ground, columns within ~3 core radii). The tornado is
    a column leaning from its foot (ground_y) to its top (base_y); inflow points at the axis, the updraft
    is strongest on it, the swirl is a horizontal wobble that turns with `phase` and the height."""
    cols, rows, cell = wind.cols, wind.rows, wind.cell
    u = wind.u
    v = wind.v
    delta = 0.0
    hypot = math.hypot
    for t in tornadoes:
        gf = t.ground_factor
        if gf <= 0.0:
            continue
        peak = t.wind_ms * gf
        if peak < 5.0:
            continue
        scale = 0.55 * peak
        s_len = max(2.2 * t.core_radius, 0.9 * cell)
        reach_x = 3.0 * s_len
        span = t.ground_y - t.base_y
        if span < 1.0:
            continue
        r0 = max(0, int((t.base_y - 0.5 * cell) / cell))
        r1 = min(rows - 1, int((t.ground_y + 0.5 * cell) / cell))
        for r in range(r0, r1 + 1):
            cy = (r + 0.5) * cell
            f = (t.ground_y - cy) / span
            if f < -0.15 or f > 1.05:
                continue
            f = 0.0 if f < 0.0 else (1.0 if f > 1.0 else f)
            axis = t.x + t.lean * f
            env_h = 1.0 - 0.5 * f
            c0 = max(0, int((axis - reach_x) / cell))
            c1 = min(cols - 1, int((axis + reach_x) / cell))
            swirl_a = 0.35 * peak * math.sin(t.phase + 5.0 * f)
            for c in range(c0, c1 + 1):
                dx = (c + 0.5) * cell - axis
                d = abs(dx)
                q = d / s_len
                shape = q * math.exp(1.0 - q)                  # 0 on the axis, 1 at one s_len, then decays
                gauss = math.exp(-q * q)
                du = -(1.0 if dx > 0 else -1.0) * scale * shape * env_h + swirl_a * gauss
                dv = -0.6 * peak * gauss * (1.0 - 0.3 * f)
                i = r * cols + c
                u0 = u[i]
                v0 = v[i]
                u1 = round(u0 + du, 2)
                v1 = round(v0 + dv, 2)
                u[i] = u1
                v[i] = v1
                delta += hypot(u1, v1) - hypot(u0, v0)
    if delta:
        wind.mean_mag += delta / (cols * rows)
