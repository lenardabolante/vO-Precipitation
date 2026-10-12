"""Roads and cars (protocol v2.3 "Cars").

`hello.roads` = spans of the terrain surface painted as road: one `street` per settlement (its whole span)
and, when there is at least one settlement, one `highway` across the whole world. Cars drive on the
terrain surface along those spans -- no lanes, no intersections. Traffic in one direction is one queue
(`dir` +1 / -1): a car keeps a Gipps-style safe gap to the car ahead in its direction, wherever on
the world it is, so two cars never overlap and a street car and a highway car in the same place queue
politely. Street cars turn back at the end of their street (they wait there until the other direction
is clear); the highway's cars enter and leave at the world edges.

Speeds are px/s. A car's cruising speed is its own (a street 24-38, the highway 46-72, trucks and buses
slower), reduced on wet or snowy ground and steep slopes. `wet` / `snow` are the ground cover under it,
`lights` (night, rain, snow, a dark storm overhead) and `wipers` (rain or snow falling on it) switch with
hysteresis. A tornado that reaches a car tosses it: `lift` (px above the surface) and `spin` (radians)
change smoothly through the air (an updraft near the axis against gravity), then the car lands and stays
`wrecked` -- never moving again -- and is removed after WRECK_LIFE_S. Traffic drives past wrecks.

Everything random comes from the system's own `random.Random` (seeded from the world seed and saved), so
a world's cars are the same for the same seed and the rest of the simulation's random stream is untouched.
"""
from __future__ import annotations

import math
import random

from settlements import ROAD_HIGHWAY, ROAD_STREET, plan_roads  # noqa: F401

VARIANTS = ("sedan", "hatchback", "pickup", "van", "truck", "bus")
LENGTH = {"sedan": 30.0, "hatchback": 26.0, "pickup": 34.0, "van": 33.0, "truck": 48.0, "bus": 56.0}
STREET_MIX = (("sedan", 0.38), ("hatchback", 0.22), ("pickup", 0.14), ("van", 0.10), ("truck", 0.04), ("bus", 0.12))
HIGHWAY_MIX = (("sedan", 0.36), ("hatchback", 0.19), ("pickup", 0.16), ("van", 0.10), ("truck", 0.17), ("bus", 0.02))
PAINT = ((178, 34, 34), (32, 64, 150), (236, 236, 232), (46, 46, 52), (150, 154, 160), (30, 110, 70),
         (214, 170, 40), (120, 50, 140), (200, 110, 40), (90, 130, 180), (240, 240, 244), (70, 74, 84))
BUS_PAINT = ((230, 180, 30), (236, 236, 232), (36, 90, 160), (200, 60, 50))
TRUCK_PAINT = ((236, 236, 232), (178, 34, 34), (32, 64, 150), (90, 96, 104))

STREET_SPEED = (24.0, 38.0)
HIGHWAY_SPEED = (46.0, 72.0)
ACCEL = 40.0                  # px/s^2
BRAKE = 120.0                 # comfortable braking, px/s^2 (also what a follower assumes of its leader)
REACTION = 0.35               # s
MIN_GAP = 6.0                 # px kept between bumpers
STREET_CARS_PER_PX = 1.0 / 110.0
STREET_CARS_MAX = 7
HIGHWAY_CARS_PER_1280 = 4.0
CAP_BASE, CAP_PER_1280 = 30, 20     # most cars in the world: 90 at 3840 px
WRECK_LIFE_S = 50.0
TOSS_MIN_WIND = 35.0          # m/s: a gust below EF1 leaves cars alone
GRAVITY = 520.0
LIFT_MAX = 320.0              # px: the highest a tornado throws a car
TOSS_MAX_S = 9.0              # a car is never held up longer than this: the updraft lets go
TORNADO_SEE_PX = 420.0        # a car brakes for a grounded tornado this far ahead ...
TORNADO_STOP_PX = 150.0       # ... and a driver of average caution (1.0) stops this far (beyond its core) short of it;
                              # each car's `caution` is 0 (does not notice) .. 1.5, so a few are caught
EDGE_OUT = 90.0               # px past the world edge where a highway car is removed / enters
# standing water (v2.4): a car's wheel radius (px, CarPainter.dims) and the depth at which it stands still, 1.8 x the radius
# (the water reaches the middle of the wheels: a sedan stands at 5.8 px); in shallower water it slows in proportion
WHEEL_R = {"sedan": 3.2, "hatchback": 3.1, "pickup": 3.5, "van": 3.4, "truck": 3.9, "bus": 3.9}
STALL_PER_WHEEL_R = 1.8



def _smooth01(x, lo, hi):
    if x <= lo:
        return 0.0
    if x >= hi:
        return 1.0
    u = (x - lo) / (hi - lo)
    return u * u * (3.0 - 2.0 * u)


def night_factor(tod: float) -> float:
    """0 by day, 1 at night, smooth dusk (0.70-0.80 of the day) and dawn (0.20-0.30)."""
    return max(0.0, min(1.0, (1.0 - _smooth01(tod, 0.20, 0.30)) + _smooth01(tod, 0.70, 0.80)))


class Car:
    __slots__ = ("id", "seed", "road", "x", "dir", "speed", "vcruise", "variant", "length", "color",
                 "state", "lift", "spin", "wet", "snow", "lights", "wipers", "rain_f", "dark_f",
                 "vx_t", "vlift", "vspin", "wreck_t", "y", "angle", "caution", "depth")

    def to_state(self) -> dict:
        s = {k: getattr(self, k) for k in self.__slots__}
        s["color"] = list(self.color)
        return s

    @classmethod
    def from_state(cls, s: dict) -> "Car":
        c = cls()
        for k in cls.__slots__:
            setattr(c, k, s.get(k, 0.0) if k == "depth" else s[k])        # (a state from before v2.4 has no `depth`)
        c.color = tuple(c.color)
        return c


class CarSystem:
    def __init__(self, roads: list, w: int, seed: int, traffic: float = 1.0, mix=None):
        """`traffic` scales the cars on every street and the highway; `mix` (a tuple of variant names, or None)
        restricts the vehicles to those variants (a research station: pickups and vans). Both default to
        today's behaviour; a preset biome's road plan sets them (v2.3)."""
        self.traffic = float(traffic)
        self.mix = tuple(mix) if mix else None
        self.roads = roads
        self.w = int(w)
        self.rng = random.Random((int(seed) * 2654435761 + 0x0CA2) & 0xFFFFFFFF)
        self.cars: list = []
        self.next_id = 1
        self.spawn_acc = 0.0
        self.next_spawn = 1.0 if self._has_highway() else 0.0
        self.gen_total = 0
        self.hw_target = max(1, int(round(HIGHWAY_CARS_PER_1280 * w / 1280.0 * self.traffic)))
        self.cap = int(CAP_BASE + CAP_PER_1280 * w / 1280.0)

    def _has_highway(self) -> bool:
        return any(r["kind"] == ROAD_HIGHWAY for r in self.roads)

    # -- creation ---------------------------------------------------------------------------
    def _make(self, kind: str, x: float, direction: int, speed_frac: float = 1.0) -> Car:
        rng = self.rng
        car = Car()
        car.id = self.next_id
        self.next_id += 1
        car.seed = rng.getrandbits(31)
        r = random.Random(car.seed)
        mix = HIGHWAY_MIX if kind == ROAD_HIGHWAY else STREET_MIX
        if self.mix is not None:
            mix = tuple((v, p) for v, p in mix if v in self.mix) or tuple((v, 1.0) for v in self.mix)
        car.variant = r.choices([v for v, _ in mix], weights=[p for _, p in mix], k=1)[0]
        pal = BUS_PAINT if car.variant == "bus" else (TRUCK_PAINT if car.variant == "truck" else PAINT)
        base = r.choice(pal)
        car.color = tuple(max(0, min(255, int(c + r.uniform(-9, 9)))) for c in base)
        car.length = LENGTH[car.variant]
        car.caution = round(r.uniform(0.0, 1.5), 2)        # how far a driver stays from a tornado (x 150 px)
        lo, hi = HIGHWAY_SPEED if kind == ROAD_HIGHWAY else STREET_SPEED
        car.vcruise = round(r.uniform(lo, hi) * (0.85 if car.variant in ("truck", "bus") else 1.0), 2)
        car.road = 0 if kind == ROAD_STREET else 1       # only used as a marker: street vs highway
        car.x = x
        car.dir = direction
        car.speed = car.vcruise * speed_frac
        car.state = "driving"
        car.lift = car.spin = car.vspin = car.vlift = car.vx_t = car.wreck_t = 0.0
        car.wet = car.snow = 0.0
        car.lights = car.wipers = False
        car.rain_f = car.dark_f = 0.0
        car.y = 0.0
        car.angle = 0.0
        car.depth = 0.0
        self.gen_total += 1
        return car

    def populate(self, terrain) -> None:
        """The cars already on the road when a world starts: some on every street, some on the highway."""
        rng = self.rng
        for road in self.roads:
            if road["kind"] != ROAD_STREET:
                continue
            L = road["x1"] - road["x0"]
            n = max(1, min(max(1, int(round(STREET_CARS_MAX * self.traffic))), int(round(L * STREET_CARS_PER_PX))))
            for _ in range(n):
                self._place(ROAD_STREET, road["x0"] + 20.0, road["x1"] - 20.0)
        hw = [r for r in self.roads if r["kind"] == ROAD_HIGHWAY]
        if hw:
            for _ in range(self.hw_target):
                self._place(ROAD_HIGHWAY, 40.0, self.w - 40.0)
        self.cars.sort(key=lambda c: c.id)

    def _place(self, kind, lo, hi):
        """Put one new car at a random spot with room around it in its lane (a few tries, else skip)."""
        rng = self.rng
        for _ in range(12):
            x = rng.uniform(lo, hi)
            d = 1 if rng.random() < 0.5 else -1
            if self._lane_clear(x, d, 60.0):
                car = self._make(kind, x, d, rng.uniform(0.6, 1.0))
                self.cars.append(car)
                return car
        return None

    def _lane_clear(self, x: float, d: int, margin: float, behind: float = 0.0) -> bool:
        for c in self.cars:
            if c.dir == d and c.state in ("driving", "stopped"):
                lo = x - margin - c.length * 0.5 - behind * (1 if d > 0 else 0)
                hi = x + margin + c.length * 0.5 + behind * (1 if d < 0 else 0)
                if lo < c.x < hi:
                    return False
        return True

    # -- per tick ---------------------------------------------------------------------------
    def update(self, dt: float, terrain, tod: float, ground, rain_spans: list, dark_spans: list,
               tornadoes: list, water=None) -> None:
        if not self.roads:
            return
        night = night_factor(tod)
        wet_a = ground.wet
        snow_a = ground.snow
        gmax = len(wet_a) - 1
        ginv = 1.0 / 32.0
        slope_at = terrain.slope_at
        height_at = terrain.height_at
        ease = min(1.0, dt * 2.5)
        streets = [r for r in self.roads if r["kind"] == ROAD_STREET]
        hw = any(r["kind"] == ROAD_HIGHWAY for r in self.roads)
        live = [t for t in tornadoes if t.ground_factor > 0.0 and t.wind_ms >= 20.0]

        # 1. environment, toss and landing for every car
        for c in self.cars:
            x = c.x
            gi = int(x * ginv + 0.5)
            gi = 0 if gi < 0 else (gmax if gi > gmax else gi)
            c.wet = wet_a[gi]
            c.snow = snow_a[gi]
            c.depth = water.depth_at(x) if water is not None else 0.0     # standing water under the car (v2.4)
            rain = 0.0
            for x0, x1, k in rain_spans:
                if x0 <= x <= x1 and k > rain:
                    rain = k
            dark = 0.0
            for x0, x1, k in dark_spans:
                if x0 <= x <= x1 and k > dark:
                    dark = k
            c.rain_f += (rain - c.rain_f) * ease
            c.dark_f += (dark - c.dark_f) * ease
            if c.wipers:
                c.wipers = c.rain_f > 0.10
            else:
                c.wipers = c.rain_f > 0.25
            score = max(night, 1.2 * c.rain_f, c.dark_f, 0.9 * c.snow if c.rain_f > 0.05 else 0.0)
            if c.lights:
                c.lights = score > 0.3
            else:
                c.lights = score > 0.5
            if c.state in ("driving", "stopped"):
                for t in live:
                    if (t.wind_ms >= TOSS_MIN_WIND and t.ground_factor > 0.6
                            and abs(x - t.x) < t.width * 0.5 + 12.0):
                        self._toss(c, t)
                        break
            if c.state == "tossed":
                self._fly(c, dt, live, terrain)
            elif c.state == "wrecked":
                c.wreck_t += dt

        # 2. traffic: the driving cars of each direction, front to back
        lanes = {1: [], -1: []}
        for c in self.cars:
            if c.state in ("driving", "stopped"):
                lanes[c.dir].append(c)
        for d, lane in lanes.items():
            lane.sort(key=lambda c: -c.x if d > 0 else c.x)        # leader first
            leader = None
            for c in lane:
                self._drive(c, dt, d, leader, streets, slope_at, live)
                leader = c

        # 3. wrecks time out, cars past the edge leave, surface y and angle
        keep = []
        for c in self.cars:
            if c.state == "wrecked" and c.wreck_t > WRECK_LIFE_S:
                continue
            if c.road == 1 and c.state in ("driving", "stopped") and (c.x > self.w + EDGE_OUT or c.x < -EDGE_OUT):
                continue
            c.y = height_at(c.x)
            c.angle = math.atan(slope_at(c.x))
            keep.append(c)
        self.cars = keep

        # 4. highway arrivals
        if hw:
            self._arrivals(dt)

    def _drive(self, c, dt, d, leader, streets, slope_at, live):
        x = c.x
        slope = abs(slope_at(x))
        k = (1.0 - 0.30 * c.wet - 0.55 * c.snow) / (1.0 + 2.2 * max(0.0, slope - 0.05))
        v_des = c.vcruise * max(0.15, k)
        if c.depth > 0.0:                          # standing water: slower as it deepens, standing at the middle of the wheels
            v_des *= max(0.0, 1.0 - c.depth / (STALL_PER_WHEEL_R * WHEEL_R[c.variant]))
        # the end of a street: slow to a crawl there, turn back when the other direction is clear
        end_x = None
        if c.road == 0:
            for s in streets:
                if s["x0"] - 1.0 <= x <= s["x1"] + 1.0:
                    end_x = s["x1"] - c.length * 0.5 - 2.0 if d > 0 else s["x0"] + c.length * 0.5 + 2.0
                    break
        if end_x is not None:
            rem = (end_x - x) * d
            v_des = min(v_des, math.sqrt(2.0 * 60.0 * rem) + 5.0 if rem > 0.0 else 0.0)
        # nobody drives into a tornado: stop TORNADO_STOP_PX short of one that is ahead and on the ground
        for t in live:
            ahead = (t.x - x) * d - 0.5 * c.length
            if 0.0 < ahead < TORNADO_SEE_PX:
                v_des = min(v_des, math.sqrt(2.0 * 60.0 * max(0.0, ahead - t.core_radius - TORNADO_STOP_PX * c.caution)))
        # Gipps: the fastest speed from which this car can still stop behind its leader
        if leader is not None:
            gap = (leader.x - x) * d - 0.5 * (leader.length + c.length)
            b = BRAKE
            tau = REACTION
            vl = leader.speed
            rad = b * b * tau * tau + vl * vl + 2.0 * b * max(0.0, gap - MIN_GAP)
            v_safe = -b * tau + math.sqrt(rad)
            if gap <= MIN_GAP:
                v_safe = 0.0 if gap < MIN_GAP * 0.5 else min(v_safe, vl)
            v_des = min(v_des, max(0.0, v_safe))
        if v_des > c.speed:
            c.speed = min(v_des, c.speed + ACCEL * dt)
        else:
            c.speed = max(v_des, c.speed - 2.0 * BRAKE * dt)
        step = c.speed * dt
        if leader is not None:                       # never run into the leader, whatever the numbers say
            room = (leader.x - x) * d - 0.5 * (leader.length + c.length) - 1.0
            if step > room:
                step = max(0.0, room)
                c.speed = step / dt if dt > 0 else 0.0
        c.x = x + step * d
        c.state = "stopped" if c.speed < 0.8 else "driving"
        if end_x is not None and (end_x - c.x) * d <= 0.5 and c.speed < 14.0:
            nd = -d
            if self._lane_clear(c.x, nd, c.length * 0.5 + 14.0, behind=60.0 + 1.2 * 40.0):
                c.dir = nd

    def _arrivals(self, dt):
        """Cars come in at the world edges of the highway as a Poisson process sized to keep ~hw_target on it."""
        rng = self.rng
        n_hw = sum(1 for c in self.cars if c.road == 1)
        if len(self.cars) >= self.cap or n_hw >= self.hw_target * 1.4:
            return
        mean_speed = 0.5 * (HIGHWAY_SPEED[0] + HIGHWAY_SPEED[1]) * 0.9
        rate = self.hw_target / max(10.0, self.w / mean_speed)         # cars per second
        self.spawn_acc += rate * dt
        if self.spawn_acc >= self.next_spawn:
            d = 1 if rng.random() < 0.5 else -1
            x = -60.0 if d > 0 else self.w + 60.0
            if self._lane_clear(x, d, 60.0 + 40.0):
                self.cars.append(self._make(ROAD_HIGHWAY, x, d, 1.0))
                self.spawn_acc = 0.0
                self.next_spawn = -math.log(1.0 - rng.random())
            else:
                self.next_spawn = self.spawn_acc + 0.2       # try again a little later

    # -- the tornado --------------------------------------------------------------------------
    def _toss(self, c, t):
        rng = self.rng
        c.state = "tossed"
        c.vlift = 150.0 + 2.4 * t.wind_ms
        c.wreck_t = 0.0
        c.vx_t = c.speed * c.dir * 0.8 + (t.x - c.x) * 1.2 + rng.uniform(-40.0, 40.0)
        c.vspin = (1.0 if rng.random() < 0.5 else -1.0) * (3.0 + 0.05 * t.wind_ms)
        c.speed = abs(c.vx_t)

    def _fly(self, c, dt, live, terrain):
        up = 0.0
        pull = 0.0
        for t in live:
            dx = c.x - t.axis_x(terrain.height_at(c.x) - c.lift)
            q = dx / (2.0 * t.core_radius + 30.0)
            if -2.5 < q < 2.5:
                g = math.exp(-q * q) * t.ground_factor
                up = max(up, (900.0 + 14.0 * t.wind_ms) * g)
                pull += -dx * 1.4 * g
        c.wreck_t += dt                       # (while tossed this counts the time in the air)
        if c.wreck_t > TOSS_MAX_S:
            up = 0.0
        c.vlift += (up - GRAVITY) * dt
        v_max = math.sqrt(2.0 * GRAVITY * max(0.0, LIFT_MAX - c.lift))      # (a ceiling: the apex stays below LIFT_MAX)
        if c.vlift > v_max:
            c.vlift = v_max
        c.vx_t += (pull - c.vx_t * 0.35) * dt
        c.lift += c.vlift * dt
        c.x += c.vx_t * dt
        c.spin += c.vspin * dt
        c.speed = abs(c.vx_t)
        if c.lift <= 0.0 and c.vlift < 0.0:
            c.lift = 0.0
            c.vlift = 0.0
            c.vx_t = 0.0
            c.vspin = 0.0
            c.speed = 0.0
            c.state = "wrecked"
            c.wreck_t = 0.0
        if c.lift < 0.0:
            c.lift = 0.0

    # -- output -------------------------------------------------------------------------------
    def to_protocol(self) -> list:
        out = [{"id": c.id, "x": round(c.x, 1), "y": round(c.y, 1), "angle": round(c.angle, 3), "dir": c.dir,
                "speed": round(c.speed, 1), "variant": c.variant, "color": list(c.color),
                "lights": c.lights, "wipers": c.wipers, "state": c.state, "lift": round(c.lift, 1),
                "spin": round(c.spin, 2), "wet": round(c.wet, 2), "snow": round(c.snow, 2)}
               for c in self.cars]
        for d, c in zip(out, self.cars):
            if round(c.depth, 1) > 0.0:             # v2.4: depth of standing water at the car, omitted when dry
                d["depth"] = round(c.depth, 1)
        return out

    def to_state(self) -> dict:
        return {"roads": [dict(r) for r in self.roads], "w": self.w, "rng": _rng_state(self.rng),
                "cars": [c.to_state() for c in self.cars], "next_id": self.next_id,
                "spawn_acc": self.spawn_acc, "next_spawn": self.next_spawn, "gen_total": self.gen_total,
                "hw_target": self.hw_target, "cap": self.cap,
                "traffic": self.traffic, "mix": list(self.mix) if self.mix else None}

    @classmethod
    def from_state(cls, s: dict) -> "CarSystem":
        o = cls.__new__(cls)
        o.roads = [dict(r) for r in s["roads"]]
        o.w = int(s["w"])
        o.rng = random.Random()
        ver, internal, gauss = s["rng"]
        o.rng.setstate((int(ver), tuple(int(v) for v in internal), gauss))
        o.cars = [Car.from_state(c) for c in s["cars"]]
        o.next_id = int(s["next_id"])
        o.spawn_acc = float(s["spawn_acc"])
        o.next_spawn = float(s["next_spawn"])
        o.gen_total = int(s["gen_total"])
        o.hw_target = int(s["hw_target"])
        o.cap = int(s["cap"])
        o.traffic = float(s.get("traffic", 1.0))          # (saves from before the biome road plans)
        o.mix = tuple(s["mix"]) if s.get("mix") else None
        return o


def _rng_state(rng):
    ver, internal, gauss = rng.getstate()
    return [ver, list(internal), gauss]
