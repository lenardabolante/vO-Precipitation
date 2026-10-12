"""Particle system: cloud droplets, rain, snow, ice crystals, graupel, splashes and
melting flakes (sleet), and (v2.3) tornado debris.

Protocol shape (PROTOCOL.md v2): `[kind, x, y, vx, vy, size, rot]`, 7 numbers, floats
rounded to 1 decimal; kind 0 droplet, 1 raindrop, 2 snowflake, 3 ice crystal,
4 graupel/hail, 5 splash, 6 melting flake (sleet), 7 debris (v2.3). Particles land on the terrain
surface (the heightmap), not on a flat line.

Debris (kind 7) lives in its own short list (`debris`, at most DEBRIS_CAP rows): it is thrown up at a
grounded tornado's foot, lofted by its updraft, pulled to and swung around its axis, falls back with
gravity and is consumed when it lands. Keeping it out of the main loop leaves the hot path untouched.

This is the hot loop of the whole simulation, so it is written for speed:
* every particle reads the wind through nearest-cell lookup into the flat u/v lists
  (the field is smooth and particle velocity relaxes toward it, so no interpolation),
* state is stored already rounded to the wire precision, so a frame can reuse the rows
  as they are (rows are replaced, never mutated, so old frames stay intact),
* ground contacts are tallied per 32 px ground sample for objects.py, not as events.

Each particle only ever looks at the wind field, the terrain and the freezing level,
never at other particles: O(n).
"""
from __future__ import annotations

import math

from objects import INV_G

DROPLET, RAIN, SNOW, ICE, GRAUPEL, SPLASH, MELT = 0, 1, 2, 3, 4, 5, 6
DEBRIS = 7
DEBRIS_CAP = 260
DEBRIS_GRAVITY = 480.0

# per-kind tables, index = kind
TERMINAL = (0.0, 300.0, 55.0, 22.0, 380.0, 0.0, 140.0)
ACCEL = (0.0, 700.0, 60.0, 25.0, 900.0, 0.0, 300.0)
COUPLING = (0.0, 0.25, 0.85, 0.95, 0.12, 0.0, 0.5)

MIN_CAP = 3500            # the v1 cap, kept for the smallest world
CAP_BASE = 2000
CAP_PER_PX = 0.65
CAP_MAX = 6600            # keeps even a 9600 px world near real time when the sky is full


def particle_cap(world_w: int) -> int:
    """Particle cap grows with world width (3500 at 1280 px, ~4500 at 3840, ~6200 at 6400, 6600 from
    ~7000 px up). It is deliberately sub-linear in area: every particle costs ~2 us to step and
    ~1.6 us to encode in pure Python, so this is what keeps 30 ticks/s reachable on big worlds."""
    return max(MIN_CAP, min(CAP_MAX, int(CAP_BASE + CAP_PER_PX * world_w)))


class ParticleSystem:
    def __init__(self, max_particles: int = MIN_CAP):
        self.max = int(max_particles)
        # items[i] is the protocol row, ttl[i] the remaining life in seconds for
        # ephemeral kinds (droplet/splash) or None for particles that live until they land.
        self.items: list = []
        self.ttl: list = []
        self.tick = 0
        self.debris: list = []        # rows [7, x, y, vx, vy, size, rot]
        self.debris_ttl: list = []

    def __len__(self):
        return len(self.items) + len(self.debris)

    def spawn_debris(self, x, y, vx, vy, size, rot, ttl=12.0):
        if len(self.debris) >= DEBRIS_CAP:
            return
        self.debris.append([DEBRIS, round(x, 1), round(y, 1), round(vx, 1), round(vy, 1),
                            round(size, 1), round(rot, 1)])
        self.debris_ttl.append(ttl)

    def update_debris(self, dt, terrain, tornadoes):
        """Advance the debris: gravity, plus for every grounded tornado an updraft near its axis (up to
        (900 + 14 wind) px/s^2 against gravity, up to 420 px/s, up to the cloud base), a pull towards the axis and a swing that turns with the vortex's phase.
        A row that lands on the terrain (or leaves the world / its time) is dropped."""
        if not self.debris:
            return
        out = []
        out_t = []
        ty = terrain.y
        tinv = terrain.inv
        tlast = terrain.n - 1
        sin = math.sin
        exp = math.exp
        live = [(t, t.ground_factor) for t in tornadoes]
        live = [(t, g) for t, g in live if g > 0.0]
        for p, ttl in zip(self.debris, self.debris_ttl):
            kind, x, y, vx, vy, size, rot = p
            ttl -= dt
            if ttl <= 0.0:
                continue
            vy += DEBRIS_GRAVITY * dt
            for t, g in live:
                rc = t.core_radius
                dx = x - t.axis_x(y)
                wide = 3.2 * rc + 40.0
                q = dx / wide
                if q > 1.6 or q < -1.6:
                    continue
                gw = exp(-q * q)
                gc = exp(-(dx / (2.0 * rc + 25.0)) ** 2)
                if y > t.base_y + 30.0:
                    vy -= (900.0 + 14.0 * t.wind_ms) * g * gc * dt       # the updraft beats gravity near the axis
                    if vy < -420.0:
                        vy = -420.0
                vx += (-dx * 2.4 * g - vx * 0.7) * gw * dt + 260.0 * g * gw * sin(t.phase + y * 0.02 + rot) * dt
            if vy > 520.0:
                vy = 520.0
            x += vx * dt
            y += vy * dt
            f = x * tinv
            if f <= 0.0:
                surf = ty[0]
            elif f >= tlast:
                surf = ty[tlast]
            else:
                i = int(f)
                surf = ty[i] + (ty[i + 1] - ty[i]) * (f - i)
            if y >= surf - 0.05 and vy > 0.0:
                continue                    # landed
            if x < -40.0 or x > terrain.w + 40.0 or y < -60.0:
                continue
            out.append([kind, round(x, 1), round(y, 1), round(vx, 1), round(vy, 1), size, round(rot + 6.0 * dt, 1)])
            out_t.append(ttl)
        self.debris = out
        self.debris_ttl = out_t

    def spawn(self, kind, x, y, vx, vy, size, rot, ttl=None):
        if len(self.items) >= self.max:
            return  # at the cap: the sky simply cannot get any busier
        self.items.append([kind, round(x, 1), round(y, 1), round(vx, 1), round(vy, 1),
                           round(size, 1), round(rot, 1)])
        self.ttl.append(ttl)

    def update(self, dt, world, terrain, wind, rng):
        """Advance all particles by dt. Returns (rain_hits, snow_hits, ice_alive); the hit dicts
        map ground-sample index (x/32, nearest) -> number of particles that landed there."""
        self.tick += 1
        rain_hits: dict = {}
        snow_hits: dict = {}
        new_items = []
        new_ttl = []
        add_i = new_items.append
        add_t = new_ttl.append
        ty = terrain.y
        tinv = terrain.inv
        tlast = terrain.n - 1
        tmin = terrain.min_y - 0.5
        wu = wind.u
        wv = wind.v
        winv = wind.inv
        cmax = wind.cols - 1
        rmax = wind.rows - 1
        cols = wind.cols
        fz = world.freezing_y
        if fz >= terrain.ground_y:
            fz = 1.0e9      # the whole surface is at or below 0 C: nothing has a warm layer to melt in
        xmax = world.w + 40
        cap = self.max
        ease2 = min(1.0, dt * 2.0)
        ease3 = min(1.0, dt * 3.0)
        ease15 = min(1.0, dt * 1.5)
        rot_fast = (self.tick % 3 == 0)
        rot_slow = (self.tick % 6 == 0)
        ice_alive = 0
        uniform = rng.uniform
        rnd = round

        accel = ACCEL
        terminal = TERMINAL
        coupling = COUPLING
        rot_any = rot_fast or rot_slow

        for p, ttl in zip(self.items, self.ttl):
            kind, x, y, vx, vy, size, rot = p
            if ttl is not None:
                ttl -= dt
                if ttl <= 0.0:
                    continue  # expired droplet / splash

            ci = int(x * winv)
            ri = int(y * winv)
            if ci > cmax:
                ci = cmax
            if ri > rmax:
                ri = rmax
            wi = ri * cols + ci

            if kind != 5 and kind != 0:
                # Falling precipitation: gravity-dominated, wind-advected.
                vy += accel[kind] * dt
                term = terminal[kind]
                if vy > term:
                    vy = term
                vx += (wu[wi] * coupling[kind] - vx) * ease15
                x += vx * dt
                y += vy * dt

                # Melting: snow/ice crossing below the freezing level warms up.
                if y > fz and (kind == 2 or kind == 3):
                    if rng.random() < 0.6:
                        kind = 1
                        size = max(1.0, rnd(size * 0.7, 1))
                    else:
                        kind = 6
                        size = max(1.0, rnd(size * 0.85, 1))

                if y >= tmin:
                    f = x * tinv
                    if f <= 0.0:
                        surf = ty[0]
                    elif f >= tlast:
                        surf = ty[tlast]
                    else:
                        i = int(f)
                        surf = ty[i] + (ty[i + 1] - ty[i]) * (f - i)
                    if y >= surf - 0.05:
                        key = int(x * INV_G + 0.5)
                        if kind == 2 or kind == 3:
                            snow_hits[key] = snow_hits.get(key, 0) + 1
                        else:
                            rain_hits[key] = rain_hits.get(key, 0) + 1
                            if len(new_items) < cap:
                                add_i([5, rnd(x, 1), rnd(surf - 0.15, 1), rnd(uniform(-30, 30), 1),
                                       rnd(-uniform(20, 60), 1), max(1.0, rnd(size * 0.6, 1)), 0.0])
                                add_t(uniform(0.15, 0.35))
                        continue  # consumed by the ground
            elif kind == 5:
                vy += 220.0 * dt  # brief upward-then-down flick, small gravity
                vx -= vx * ease3
                x += vx * dt
                y += vy * dt
                if y >= tmin:
                    f = x * tinv
                    if f <= 0.0:
                        surf = ty[0]
                    elif f >= tlast:
                        surf = ty[tlast]
                    else:
                        i = int(f)
                        surf = ty[i] + (ty[i + 1] - ty[i]) * (f - i)
                    if y >= surf - 0.05:
                        continue
            else:  # droplet
                vx += (wu[wi] * 0.5 - vx) * ease2
                vy += (wv[wi] * 0.3 - vy) * ease2
                x += vx * dt
                y += vy * dt
                if y >= tmin:
                    f = x * tinv
                    if f <= 0.0:
                        surf = ty[0]
                    elif f >= tlast:
                        surf = ty[tlast]
                    else:
                        i = int(f)
                        surf = ty[i] + (ty[i + 1] - ty[i]) * (f - i)
                    if y >= surf - 0.05:
                        continue

            if x < -40 or x > xmax or y < -60:
                continue  # off-world, drop

            if rot_any:
                if rot_fast and kind != 2 and kind != 3:
                    rot = rnd(rot + 0.2, 1)
                if rot_slow and (kind == 2 or kind == 3 or kind == 0):
                    rot = rnd(rot + 0.1, 1)
            if kind == 3:
                ice_alive += 1

            add_i([kind, rnd(x, 1), rnd(y, 1), rnd(vx, 1), rnd(vy, 1), size, rot])
            add_t(ttl)

        self.items = new_items
        self.ttl = new_ttl
        return rain_hits, snow_hits, ice_alive

    def ice_count(self) -> int:
        return sum(1 for p in self.items if p[0] == ICE)

    def protocol_list(self, x0=None, x1=None):
        """Rows for a frame (a fresh list; rows are never mutated afterwards).
        With a view range only particles with x0-200 <= x <= x1+200 are listed."""
        if x0 is None:
            return self.items + self.debris if self.debris else list(self.items)
        lo = x0 - 200.0
        hi = x1 + 200.0
        rows = [p for p in self.items if lo <= p[1] <= hi]
        if self.debris:
            rows += [p for p in self.debris if lo <= p[1] <= hi]
        return rows

    # -- state ----------------------------------------------------------------
    def to_state(self) -> dict:
        return {"max": self.max, "tick": self.tick, "items": [list(p) for p in self.items],
                "ttl": list(self.ttl), "debris": [list(p) for p in self.debris],
                "debris_ttl": list(self.debris_ttl)}

    @classmethod
    def from_state(cls, s: dict) -> "ParticleSystem":
        ps = cls(int(s["max"]))
        ps.tick = int(s["tick"])
        ps.items = [[int(p[0]), float(p[1]), float(p[2]), float(p[3]), float(p[4]), float(p[5]),
                     float(p[6])] for p in s["items"]]
        ps.ttl = [None if t is None else float(t) for t in s["ttl"]]
        # v2.3: debris (a v2.2 file has none)
        ps.debris = [[int(p[0]), float(p[1]), float(p[2]), float(p[3]), float(p[4]), float(p[5]), float(p[6])]
                     for p in s.get("debris", [])]
        ps.debris_ttl = [float(t) for t in s.get("debris_ttl", [])]
        return ps
