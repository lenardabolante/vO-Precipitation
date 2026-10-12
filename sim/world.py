"""World state: size, ground line, freezing level, calendar and temperature.

A simple lapse-rate model sets `freezing_y` (screen-space, pixels) from
`surface_temp_c`, measured from the *lowest* ground point (`ground_y`): warmer
surface pushes the freezing level up (smaller y, closer to the top of the
screen), colder surface pushes it down toward (and eventually onto) the lowest
ground. Higher terrain therefore pokes above the freezing level first. The
lapse rate is scaled with world height so a taller sky keeps the same
proportions as the 720 px reference world.

Calendar and temperature (protocol v2.3). One sim day is DAY_LENGTH_S = 300 sim seconds, one year is
`year_days` days (default 8). The surface temperature is the sum of five parts, all smooth in time:

    surface_temp_c = base_c + season_c + diurnal_c + gas_c + user_c

  base_c     the climate's annual mean
  season_c   this point of the year minus the annual mean: the preset's twelve monthly means joined by
             a periodic cubic (Catmull-Rom), or for a plain world a sine with a seeded amplitude,
             coldest mid January for the northern hemisphere
  diurnal_c  the day cycle, half the day's range times a smooth asymmetric wave: coldest at
             time_of_day 0.24 (just before sunrise), warmest at 0.62, zero mean over the day
  gas_c      the greenhouse offset of the CO2 level (3.0 C per doubling), approached with a 3 s
             time constant so a `gas` command never steps the temperature
  user_c     the sum of the `temp` commands (and what assigning `surface_temp_c` amounts to)

`season` / `hour` commands move the calendar instantly (time_of_day and month change at once) but
the temperature glides: the difference is parked in season_c / diurnal_c and fades out over
GLIDE_S seconds, so the five parts still add up to the shown temperature at every instant.
"""
from __future__ import annotations

import math
import random

LAPSE_C_PER_PX = 0.09  # degrees C per pixel of "altitude" in a 720 px tall world
REF_H = 720.0
DAY_LENGTH_S = 300.0
DEFAULT_HOUR = 7.2     # every run (incl. reseed) opens at 7.2 h = 0.30 of the day = mid-morning,
DAY_START_FRAC = DEFAULT_HOUR / 24.0   # not 0.0 = midnight; `t` itself still starts at 0.0
DEFAULT_MONTH = 4.0
DEFAULT_YEAR_DAYS = 8
YEAR_DAYS_RANGE = (2, 365)
GLIDE_S = 6.0          # a calendar jump fades its temperature step out over this long
GAS_TAU_S = 3.0        # greenhouse offset time constant

TOD_MIN = 0.24         # coldest time of the day (fraction of the day)
TOD_MAX = 0.62         # warmest
COLD_MONTH_N = 1.5     # mid January (month value 1.5), the coldest point of a plain northern year

SEASONS = ("winter", "spring", "summer", "autumn")


def _smoothstep(x):
    return x * x * (3.0 - 2.0 * x)


def diurnal_shape(tod: float) -> float:
    """-1 at TOD_MIN, +1 at TOD_MAX, smooth (zero slope at both ends), mean 0 over the day."""
    tod %= 1.0
    if tod < TOD_MIN:
        tod += 1.0
    if tod <= TOD_MAX:                 # rising: the long morning-to-afternoon warm-up
        u = (tod - TOD_MIN) / (TOD_MAX - TOD_MIN)
        return -math.cos(math.pi * u)
    v = (tod - TOD_MAX) / (1.0 + TOD_MIN - TOD_MAX)    # falling: the longer evening-to-dawn cool-down
    return math.cos(math.pi * v)


def _interp12(vals, yf):
    """Periodic Catmull-Rom through twelve monthly values placed at the month centres."""
    x = yf * 12.0 - 0.5
    i = math.floor(x)
    f = x - i
    p0, p1, p2, p3 = vals[(i - 1) % 12], vals[i % 12], vals[(i + 1) % 12], vals[(i + 2) % 12]
    return 0.5 * (2.0 * p1 + (p2 - p0) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f * f
                  + (3.0 * p1 - p0 - 3.0 * p2 + p3) * f * f * f)


def season_name(year_frac: float, hemisphere: str = "N") -> str:
    """Meteorological season (Dec-Feb winter ...), flipped for the southern hemisphere."""
    m = int(1 + 12.0 * (year_frac % 1.0))
    m = min(12, max(1, m))
    if hemisphere == "S":
        m = (m + 5) % 12 + 1
    return SEASONS[{12: 0, 1: 0, 2: 0, 3: 1, 4: 1, 5: 1, 6: 2, 7: 2, 8: 2}.get(m, 3)]


class Climate:
    """Annual mean, seasonal course and daily range of one place (temperatures at the lowest ground)."""

    def __init__(self, base_c, diurnal_range_c, amp_c=8.0, monthly=None, hemisphere="N", lat=45.0,
                 source="generated"):
        self.base_c = float(base_c)
        self.diurnal_range_c = float(diurnal_range_c)
        self.amp_c = float(amp_c)
        self.monthly = [float(v) for v in monthly] if monthly else None
        self.hemisphere = hemisphere
        self.lat = float(lat)
        self.source = source

    @classmethod
    def from_monthly(cls, monthly, diurnal_range_c, lat, source="preset"):
        vals = [float(v) for v in monthly]
        if len(vals) != 12:
            raise ValueError("a climate needs twelve monthly means")
        return cls(sum(vals) / 12.0, diurnal_range_c, 0.0, vals, "S" if lat < 0 else "N", lat, source)

    @classmethod
    def generated(cls, seed: int, base_c: float):
        """A plain world's climate: the base temperature is the caller's (seeded 5..18 C as in v2.2),
        the seasonal amplitude and daily range come from a stream of their own so no other draw moves."""
        r = random.Random((int(seed) * 2654435761 + 0xC11A7E) & 0xFFFFFFFF)
        return cls(base_c, round(r.uniform(6.0, 12.0), 1), round(r.uniform(6.0, 12.0), 1))

    def season_c(self, yf: float) -> float:
        if self.monthly is not None:
            return _interp12(self.monthly, yf) - self.base_c
        cold = (COLD_MONTH_N - 1.0) / 12.0 + (0.5 if self.hemisphere == "S" else 0.0)
        return -self.amp_c * math.cos(2.0 * math.pi * (yf - cold))

    def monthly_list(self):
        if self.monthly is not None:
            return list(self.monthly)
        return [round(self.base_c + self.season_c((m + 0.5) / 12.0), 2) for m in range(12)]

    def to_state(self) -> dict:
        return {"base_c": self.base_c, "diurnal_range_c": self.diurnal_range_c, "amp_c": self.amp_c,
                "monthly": self.monthly, "hemisphere": self.hemisphere, "lat": self.lat,
                "source": self.source}

    @classmethod
    def from_state(cls, s: dict) -> "Climate":
        return cls(s["base_c"], s["diurnal_range_c"], s["amp_c"], s["monthly"], s["hemisphere"],
                   s["lat"], s["source"])


class World:
    def __init__(self, w: int, h: int, ground_y: int, rng, seed: int = 0):
        self.w = w
        self.h = h
        self.ground_y = int(ground_y)
        base = round(rng.uniform(5.0, 18.0), 1)
        self.prevailing_u = round(rng.uniform(2.0, 9.0), 2)
        self.prevailing_v = 0.0
        self.gust_phase = rng.uniform(0.0, 100.0)
        self.t = 0.0
        self.px_per_c = (h / REF_H) / LAPSE_C_PER_PX
        self._init_climate(Climate.generated(seed, base), DEFAULT_MONTH, DEFAULT_HOUR, DEFAULT_YEAR_DAYS)

    def _init_climate(self, climate, month, hour, year_days):
        self.climate = climate
        self.year_days = int(year_days)
        self.year0 = ((month - 1.0) / 12.0) % 1.0      # year_frac at t = 0
        self.hour0 = (hour / 24.0) % 1.0               # time_of_day at t = 0
        self.user_c = 0.0
        self.hold = None                               # (year_frac, time_of_day) fixed clock, or None
        self._glide = {"season": [0.0, 0.0], "diurnal": [0.0, 0.0]}      # [value at t0, t0]
        self._gas = [0.0, 0.0, 0.0]                    # [target, value at t0, t0]
        self._rev = 0
        self._pkey = None
        self._pval = None

    # -- calendar --------------------------------------------------------------------------
    @property
    def year_frac(self) -> float:
        if self.hold is not None:
            return self.hold[0]
        return (self.year0 + self.t / (self.year_days * DAY_LENGTH_S)) % 1.0

    @property
    def month(self) -> float:
        return 1.0 + 12.0 * self.year_frac

    @property
    def day(self) -> int:
        return 0 if self.hold is not None else int(self.t // DAY_LENGTH_S)

    @property
    def hemisphere(self) -> str:
        return self.climate.hemisphere

    @property
    def season(self) -> str:
        return season_name(self.year_frac, self.climate.hemisphere)

    @property
    def time_of_day(self) -> float:
        # 0.0 = midnight, 0.5 = noon, full cycle = DAY_LENGTH_S seconds
        if self.hold is not None:
            return self.hold[1]
        return (self.hour0 + self.t / DAY_LENGTH_S) % 1.0

    # -- configuration ---------------------------------------------------------------------
    def configure(self, climate=None, month=None, hour=None, year_days=None):
        """Install a climate and/or restart the calendar (used when a world is built or reseeded)."""
        if climate is not None:
            self.climate = climate
        if year_days is not None:
            self.year_days = int(year_days)
        if month is not None:
            self.year0 = ((month - 1.0) / 12.0 - self.t / (self.year_days * DAY_LENGTH_S)) % 1.0
        if hour is not None:
            self.hour0 = (hour / 24.0 - self.t / DAY_LENGTH_S) % 1.0
        self.user_c = 0.0
        self._glide = {"season": [0.0, 0.0], "diurnal": [0.0, 0.0]}
        self._rev += 1

    def fit_generated_climate(self, temp_now: float):
        """Make the (generated) climate's annual mean such that the temperature right now is `temp_now`."""
        self.climate.base_c = 0.0
        self.climate.source = "generated"
        self._rev += 1
        p = self.temp_parts()
        self.climate.base_c = float(temp_now) - (p["season_c"] + p["diurnal_c"] + p["gas_c"] + p["user_c"])
        self._rev += 1

    def hold_clock(self, month: float, hour: float):
        """Freeze the calendar at a fixed point (the showcase stage: no day or season drift)."""
        self.hold = (((month - 1.0) / 12.0) % 1.0, (hour / 24.0) % 1.0)
        self._rev += 1

    def jump_clock(self, month=None, hour=None):
        """Move the calendar now; the temperature step is parked in season_c / diurnal_c and fades."""
        if self.hold is not None:
            return
        old = self.temp_parts()
        if month is not None:
            self.year0 = ((month - 1.0) / 12.0 - self.t / (self.year_days * DAY_LENGTH_S)) % 1.0
        if hour is not None:
            self.hour0 = (hour / 24.0 - self.t / DAY_LENGTH_S) % 1.0
        self._rev += 1
        raw_s = self.climate.season_c(self.year_frac)
        raw_d = 0.5 * self.climate.diurnal_range_c * diurnal_shape(self.time_of_day)
        self._glide["season"] = [old["season_c"] - raw_s, self.t]
        self._glide["diurnal"] = [old["diurnal_c"] - raw_d, self.t]
        self._rev += 1

    def set_gas_target(self, offset_c: float):
        if abs(offset_c - self._gas[0]) > 1e-12:
            cur = self.gas_c
            self._gas = [float(offset_c), cur, self.t]
            self._rev += 1

    # -- temperature -----------------------------------------------------------------------
    def _glide_val(self, name):
        g0, t0 = self._glide[name]
        if g0 == 0.0:
            return 0.0
        x = (self.t - t0) / GLIDE_S
        if x >= 1.0:
            return 0.0
        return g0 * (1.0 - _smoothstep(max(0.0, x)))

    @property
    def gas_c(self) -> float:
        target, v0, t0 = self._gas
        if v0 == target:
            return target
        return target + (v0 - target) * math.exp(-max(0.0, self.t - t0) / GAS_TAU_S)

    def temp_parts(self) -> dict:
        """The five parts plus their sum and today's expected extremes (unrounded)."""
        key = (self.t, self.user_c, self._rev)
        if key == self._pkey:
            return self._pval
        c = self.climate
        season = c.season_c(self.year_frac) + self._glide_val("season")
        diurnal = 0.5 * c.diurnal_range_c * diurnal_shape(self.time_of_day) + self._glide_val("diurnal")
        gas = self.gas_c
        total = round(c.base_c + season + diurnal + gas + self.user_c, 9)
        mid = c.base_c + season + gas + self.user_c
        val = {"base_c": c.base_c, "season_c": season, "diurnal_c": diurnal, "gas_c": gas,
               "user_c": self.user_c, "surface_temp_c": total,
               "day_min_c": mid - 0.5 * c.diurnal_range_c, "day_max_c": mid + 0.5 * c.diurnal_range_c}
        self._pkey, self._pval = key, val
        return val

    @property
    def surface_temp_c(self) -> float:
        return self.temp_parts()["surface_temp_c"]

    @surface_temp_c.setter
    def surface_temp_c(self, value: float):
        """Assigning the temperature moves `user_c` so that the temperature right now is `value`."""
        self.user_c += float(value) - self.surface_temp_c

    @property
    def freezing_y(self) -> float:
        y = self.ground_y - self.surface_temp_c * self.px_per_c
        return max(0.0, min(float(self.ground_y), y))

    # -- serialisation ---------------------------------------------------------------------
    def to_protocol(self) -> dict:
        p = self.temp_parts()
        parts = {k: round(p[k], 2) for k in ("base_c", "season_c", "diurnal_c", "gas_c", "user_c")}
        parts["day_min_c"] = round(p["day_min_c"], 2)
        parts["day_max_c"] = round(p["day_max_c"], 2)
        yf = self.year_frac
        return {
            "w": self.w, "h": self.h, "ground_y": self.ground_y,
            "freezing_y": round(self.freezing_y, 1),
            "surface_temp_c": round(parts["base_c"] + parts["season_c"] + parts["diurnal_c"]
                                    + parts["gas_c"] + parts["user_c"], 2),
            "time_of_day": min(round(self.time_of_day, 3), 0.999),      # (never rounds up to 1.0)
            "year_frac": min(round(yf, 4), 0.9999), "month": min(round(1.0 + 12.0 * yf, 3), 12.999),
            "day": self.day,
            "season": self.season, "hemisphere": self.climate.hemisphere,
            "temp": parts,
        }

    def to_state(self) -> dict:
        return {"w": self.w, "h": self.h, "ground_y": self.ground_y,
                "surface_temp_c": self.surface_temp_c, "prevailing_u": self.prevailing_u,
                "prevailing_v": self.prevailing_v, "gust_phase": self.gust_phase, "t": self.t,
                # v2.3
                "climate": self.climate.to_state(), "year_days": self.year_days,
                "year0": self.year0, "hour0": self.hour0, "user_c": self.user_c,
                "hold": list(self.hold) if self.hold is not None else None,
                "glide": {k: list(v) for k, v in self._glide.items()},
                "gas": list(self._gas)}

    @classmethod
    def from_state(cls, s: dict, seed: int = 0) -> "World":
        o = cls.__new__(cls)
        o.w = int(s["w"])
        o.h = int(s["h"])
        o.ground_y = int(s["ground_y"])
        o.prevailing_u = float(s["prevailing_u"])
        o.prevailing_v = float(s["prevailing_v"])
        o.gust_phase = float(s["gust_phase"])
        o.t = float(s["t"])
        o.px_per_c = (o.h / REF_H) / LAPSE_C_PER_PX
        if "climate" in s:
            o._init_climate(Climate.from_state(s["climate"]), DEFAULT_MONTH, DEFAULT_HOUR, s["year_days"])
            o.year0 = float(s["year0"])
            o.hour0 = float(s["hour0"])
            o.user_c = float(s["user_c"])
            o.hold = tuple(s["hold"]) if s["hold"] is not None else None
            o._glide = {k: [float(v[0]), float(v[1])] for k, v in s["glide"].items()}
            o._gas = [float(v) for v in s["gas"]]
        else:
            # a v2.2 file: the default climate, tuned so that the temperature right now is the saved one
            # (a v2.2 world began on 1 April at 7.2 h at t = 0, which is exactly the default calendar)
            o._init_climate(Climate.generated(seed, 10.0), DEFAULT_MONTH, DEFAULT_HOUR, DEFAULT_YEAR_DAYS)
            o.fit_generated_climate(float(s["surface_temp_c"]))
        return o
