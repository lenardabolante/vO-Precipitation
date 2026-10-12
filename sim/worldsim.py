"""World simulation (PROTOCOL.md v2.3, `--mode world`): the whole Earth as a smooth parametric climate.

Not a fluid solver. Every field is a closed-form function of (latitude, longitude, season, UTC hour) plus
the contributions of a few moving pressure systems:

  temperature  zonal annual mean + a seasonal swing that grows with latitude, larger over land than over
               sea (cell land fraction from the Natural Earth 110m land mask) + a day cycle that follows
               the subsolar point. The constants in FIT were fitted by tools/fit_world_climate.py to the
               twelve monthly normals of the cited stations in sim/presets.py (lapse-corrected to sea level,
               stations above 1000 m left out); the residuals it prints are the honest error of the model.
  sun          declination = -23.44 deg * cos(360/365 * (N + 10)) (https://en.wikipedia.org/wiki/Position_of_the_Sun);
               the subsolar longitude is 15 deg per hour west of the UTC-noon meridian.
  wind belts   trades (easterly, about 14 deg), westerlies (about 47 deg), polar easterlies (about 80 deg),
               shifted with the season; the Hadley cell shifts north in June-July and south in
               December-January (https://en.wikipedia.org/wiki/Atmospheric_circulation).
  ITCZ         a rain band that follows the sun, lagging it a little and moving farther from the equator
               in the northern summer (https://en.wikipedia.org/wiki/Intertropical_Convergence_Zone).
  systems      `low` / `front` born in the winter-weighted storm tracks, `high` near 30 deg, named
               `cyclone`s over warm sea in their basin's season (months from
               https://en.wikipedia.org/wiki/Tropical_cyclone, none within 5 deg of the equator) and a
               `tornado_outbreak` over the Great Plains peaking April-June (https://en.wikipedia.org/wiki/Climate_of_Oklahoma).
               They drift with the belt wind at their latitude (time-compressed, see DRIFT_BOOST).
  locations    each preset's dot reads the preset's real monthly normal (smoothly interpolated), the day
               cycle from its real daily range, plus the weather the systems bring: a dot never
               contradicts its climate card.

What is data and what is tuning: the land mask, the station normals and the sun geometry are data; every
amplitude, width and speed in this file is tuning (the model is a game-grade picture of the climate).
Cyclone names are fictional. The south-west Indian Ocean season (Nov-Apr) is a model assumption: the cited
page gives only an administrative season.

Time: 300 sim seconds are one day, `year_days` days are one year, speed 0.25..64. `season` and `hour`
commands move a clock offset that glides to zero over several seconds, so no field ever jumps.
Same driver interface as hurricane.py: hello_message(), step(dt, extra_events), handle_command(cmd, events),
drain_outbox(), running / frame / seed. The heavy `grid` block is built at most ~4 times a second.
"""
from __future__ import annotations

import json
import math
import os
import random

import atmosphere as atm
import presets as P

HERE =os.path.dirname(os.path.abspath(__file__))
MASK_PATH = os.path.join(HERE, "data", "landmask_5deg.json")

COLS, ROWS = 72, 36
DLON = DLAT = 5.0
DAY_S = 300.0
MIN_SPEED, MAX_SPEED = 0.25, 64.0
GRID_HZ = 4.0
MIN_YEAR_DAYS, MAX_YEAR_DAYS = 2, 365
TWO_PI = 2.0 * math.pi
DRIFT_BOOST = 8.0                       # systems drift this much faster than the (time-compressed) wind
DRIFT_K = (86400.0 / DAY_S) * DRIFT_BOOST
DEFAULT_MONTH = 4.0
DEFAULT_HOUR = 12.0
# Phase O: the Great Plains tornado outbreak is convection, so it follows the local day (atmosphere.cb_day shifted OUTBREAK_LAG_H
# later, because organised severe storms peak after the isolated ones: full from 14.5 to 18 h local time, gone by 22 h) down to
# this share of its strength at night (the nocturnal storms the low-level jet feeds over the plains); lows, fronts and tropical
# cyclones are not convective and ignore the hour.
OUTBREAK_NIGHT_FLOOR = 0.20
OUTBREAK_LAG_H = 2.0

# fitted by tools/fit_world_climate.py (see the module docstring)
FIT = {"A": -7.972, "B": 51.89, "C": -15.854, "sl": 15.433, "so": 5.009, "p": 0.57, "phl": 0.543, "pho": 0.569,
       "ice": -28.594}
# (16 stations, 192 monthly means, RMSE 2.8 C; the largest station biases are Sahara -5.5, Japan +3.5, New Zealand +3.6)

DIURNAL_LAND_C = 5.0                    # half the clear-sky day/night swing over land (tuning)
DIURNAL_SEA_C = 0.7
ITCZ_MEAN_LAT = 3.0
ITCZ_GAIN = 0.55
ITCZ_LAG_YEAR = 0.04
# rain over land (tuning): how much farther than over sea the ITCZ swings, where the summer monsoon rain sits,
# and the belt of year-round convection over equatorial land. With an overshoot of 0.7 and a monsoon belt at
# 20 deg the heaviest rain of the map fell on the Sahara and Arabia in July and on central Australia in January
# while the Amazon, the Congo and Indonesia stayed dry in both months.
ITCZ_LAND_OVERSHOOT = 0.10
MONSOON_LAT = 14.0
MONSOON_WIDTH = 8.0
EQUATORIAL_WIDTH = 8.0
EQUATORIAL_CLOUD = 0.35
EQUATORIAL_RAIN = 0.60

SOURCES = [
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson",
    P.WIKI + "Position_of_the_Sun", P.WIKI + "Atmospheric_circulation",
    P.WIKI + "Intertropical_Convergence_Zone", P.WIKI + "Tropical_cyclone",
    P.WIKI + "Tornado_Alley", P.WIKI + "Climate_of_Oklahoma",
]

CYCLONE_NAMES = ["Aria", "Bruno", "Calla", "Dario", "Elin", "Fabio", "Greta", "Hugo", "Isla", "Jonas", "Kira",
                 "Lenox", "Mira", "Nico", "Odile", "Pavel", "Quinn", "Rosa", "Silas", "Tala", "Ulric", "Vera",
                 "Wren", "Xavi", "Yara", "Zane"]

# name, hemisphere, lat range, lon range, peak month(s), half-width in months, floor, cap on active storms
BASINS = [
    ("North Atlantic", 1, (8.0, 20.0), (-80.0, -20.0), (8.8,), 3.0, 0.0, 2),
    ("East Pacific", 1, (8.0, 18.0), (-120.0, -90.0), (8.3,), 3.3, 0.0, 2),
    ("West Pacific", 1, (7.0, 20.0), (120.0, 170.0), (9.0,), 6.0, 0.12, 3),
    ("North Indian", 1, (8.0, 18.0), (60.0, 100.0), (5.0, 11.0), 1.6, 0.0, 1),
    ("South-West Indian", -1, (-20.0, -8.0), (40.0, 90.0), (2.0,), 3.0, 0.0, 2),
    ("Australian", -1, (-20.0, -8.0), (90.0, 160.0), (2.4,), 3.2, 0.0, 2),
    ("South Pacific", -1, (-22.0, -8.0), (160.0, 210.0), (2.4,), 3.2, 0.0, 2),
]

STORM_TRACK_SEEDS = {1: [(-72.0, 38.0), (140.0, 36.0), (-10.0, 52.0), (-150.0, 47.0), (30.0, 50.0)]}


# ---------------------------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------------------------
def _smoothstep(x: float) -> float:
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    return x * x * (3.0 - 2.0 * x)


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


def _wrap_lon(lon: float) -> float:
    return (lon + 180.0) % 360.0 - 180.0


def _wrap_signed(d: float, period: float) -> float:
    """Shortest signed difference, in (-period/2, period/2]."""
    d = d % period
    return d - period if d > period / 2.0 else d


def _r1(x: float) -> float:
    return round(float(x) + 0.0, 1)


def _r2(x: float) -> float:
    return round(float(x) + 0.0, 2)


def _month_out(yf: float) -> float:
    """Calendar month for the wire: 1.00 .. 12.99 (rounding never reaches 13)."""
    return min(12.99, round(1.0 + 12.0 * yf, 2))


def _r3(x: float) -> float:
    return round(float(x) + 0.0, 3)


# ---------------------------------------------------------------------------------------------
# land mask (sim/data/landmask_5deg.json, generated by tools/make_landmask.py)
# ---------------------------------------------------------------------------------------------
def load_landmask(path: str = MASK_PATH) -> list:
    """ROWS lists of COLS land fractions 0..1 (row 0 = 90N..85N, column 0 = 180W..175W)."""
    with open(path, "r", encoding="utf-8") as f:
        d = json.load(f)
    if d.get("cols") != COLS or d.get("rows") != ROWS:
        raise ValueError(f"{path}: expected a {COLS}x{ROWS} land mask")
    return [[v / 100.0 for v in row] for row in d["land_pct"]]


def blur_mask(m: list, passes: int = 2) -> list:
    """1-2-1 smoothing in both directions (longitude wraps) so coasts blend over a cell or two."""
    for _ in range(passes):
        a = [[(row[(c - 1) % COLS] + 2.0 * row[c] + row[(c + 1) % COLS]) / 4.0 for c in range(COLS)] for row in m]
        m = [[(a[max(r - 1, 0)][c] + 2.0 * a[r][c] + a[min(r + 1, ROWS - 1)][c]) / 4.0 for c in range(COLS)]
             for r in range(ROWS)]
    return m


def cell_of(lat: float, lon: float) -> tuple:
    r = min(ROWS - 1, max(0, int((90.0 - lat) / DLAT)))
    c = int((_wrap_lon(lon) + 180.0) / DLON) % COLS
    return r, c


# ---------------------------------------------------------------------------------------------
# sun, season, climate formulas (shared by the grid, the locations and tools/fit_world_climate.py)
# ---------------------------------------------------------------------------------------------
def declination_deg(yf: float) -> float:
    """Sun's declination; yf = year fraction, 0 = 1 January 00:00 (N counts days from 1 January)."""
    n = yf * 365.0
    return -23.44 * math.cos(TWO_PI * (n + 10.0) / 365.0)


def subsolar(yf: float, utc_hour: float) -> tuple:
    """(lat, lon) of the subsolar point."""
    return declination_deg(yf), _wrap_lon((12.0 - utc_hour) * 15.0)


def itcz_lat(yf: float) -> float:
    """Latitude of the ITCZ rain band: follows the sun with a lag, farther out in the northern summer."""
    return ITCZ_MEAN_LAT + ITCZ_GAIN * declination_deg(yf - ITCZ_LAG_YEAR)


def winter_factor(hemi: int, yf: float) -> float:
    """1 in the hemisphere's mid-winter, 0 in its mid-summer."""
    return 0.5 + 0.5 * math.cos(TWO_PI * (yf - (0.04 if hemi > 0 else 0.54)))


def zonal_temp(lat: float, yf: float, fl: float, fit: dict | None = None) -> float:
    """Sea-level daily-mean 2 m temperature (no day cycle, no weather) at latitude `lat`, land fraction `fl`."""
    f = fit or FIT
    s = math.sin(math.radians(lat))
    c2 = 1.0 - s * s
    tann = f["A"] + f["B"] * c2 + f["C"] * c2 * c2
    hg = math.tanh(lat / 6.0) * abs(s) ** f["p"]
    return (tann + hg * (f["sl"] * fl * math.cos(TWO_PI * (yf - f["phl"]))
                         + f["so"] * (1.0 - fl) * math.cos(TWO_PI * (yf - f["pho"])))
            + f["ice"] * fl * _smoothstep((abs(lat) - 66.0) / 12.0))


def _belt(lat: float, itcz: float) -> tuple:
    """Zonal-mean (u, v, pressure_hpa) at `lat`: trades, westerlies, polar easterlies, shifted with the season."""
    latp = lat - 0.6 * itcz
    a = abs(latp)
    sgn = 1.0 if latp >= 0.0 else -1.0
    trade = math.exp(-((a - 14.0) / 11.0) ** 2)
    west = math.exp(-((a - 47.0) / 12.0) ** 2)
    polar = math.exp(-((a - 80.0) / 9.0) ** 2)
    u = -5.5 * trade + 9.0 * west - 3.5 * polar
    v = sgn * (-2.0 * math.exp(-((a - 14.0) / 12.0) ** 2) + 1.5 * math.exp(-((a - 45.0) / 10.0) ** 2))
    p = 1012.0 - 5.0 * math.cos(math.radians(6.0 * latp))
    return u, v, p


def _row_climate(lat: float, yf: float) -> tuple:
    """(cloud_storm, precip_storm, arid, monsoon, deck, equatorial) for a latitude; the land fraction and the
    ITCZ modulate them per cell. arid = subtropical dry belt, monsoon = summer-hemisphere land rain around
    14 deg (Sahel, India, northern Australia: it must not reach the deserts near 25 deg), deck = the subtropical
    stratocumulus belt off the west coasts, equatorial = the year-round convection over equatorial land (Amazon,
    Congo, Indonesia), which stays where it is while the ITCZ follows the sun."""
    a = abs(lat)
    hemi = 1.0 if lat >= 0.0 else -1.0
    arid = math.exp(-((a - 27.0) / 7.0) ** 2)
    summer = hemi * math.cos(TWO_PI * (yf - 0.5))
    mons = max(0.0, summer) * math.exp(-((a - MONSOON_LAT) / MONSOON_WIDTH) ** 2)
    cloud_st = 0.27 + 0.31 * math.exp(-((a - 52.0) / 10.0) ** 2) - 0.12 * arid + 0.05 * math.exp(-((a - 80.0) / 10.0) ** 2)
    precip_st = 0.24 * math.exp(-((a - 50.0) / 8.0) ** 2)
    deck = math.exp(-((a - 22.0) / 9.0) ** 2)
    equat = math.exp(-(lat / EQUATORIAL_WIDTH) ** 2)
    return cloud_st, precip_st, arid, mons, deck, equat


def _cyc_interp(vals: list, yf: float) -> float:
    """Smooth (Catmull-Rom) interpolation of twelve monthly values; value i sits at the middle of month i."""
    x = yf * 12.0 - 0.5
    i = math.floor(x)
    t = x - i
    p0, p1, p2, p3 = (vals[(i + k) % 12] for k in (-1, 0, 1, 2))
    return 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t * t
                  + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t * t * t)


def _season_bump(month: float, peaks: tuple, half: float, floor: float = 0.0) -> float:
    """Smooth 0..1 seasonal weight around the nearest peak month (cyclic, `half` months to zero)."""
    best = 0.0
    for pk in peaks:
        d = abs(_wrap_signed(month - pk, 12.0))
        best = max(best, 1.0 - (d / half) ** 2 if d < half else 0.0)
    return floor + (1.0 - floor) * best


# ---------------------------------------------------------------------------------------------
# the simulation
# ---------------------------------------------------------------------------------------------
class WorldSim:
    def __init__(self, seed: int, fps: int, month=None, hour=None, year_days=8):
        self.seed = int(seed)
        self.fps = max(1, int(fps))
        self.rng = random.Random(self.seed)
        try:
            yd = int(year_days)
        except (TypeError, ValueError):
            yd = 8
        self.year_days = max(MIN_YEAR_DAYS, min(MAX_YEAR_DAYS, yd))
        self.year_s = self.year_days * DAY_S

        m = DEFAULT_MONTH if month is None else float(month)
        h = DEFAULT_HOUR if hour is None else float(hour)
        if not math.isfinite(m):
            m = DEFAULT_MONTH
        if not math.isfinite(h):
            h = DEFAULT_HOUR
        self._base_yf = ((m - 1.0) / 12.0) % 1.0
        self._base_uh = h % 24.0
        self._off_yf = 0.0           # glide offsets (see _jump)
        self._off_uh = 0.0
        self._glide_t = 1.0
        self._glide_dur = 1.0

        self.running = True
        self.paused = False
        self.speed = 1.0
        self.frame = 0
        self.t = 0.0
        self.outbox: list = []

        self.mask = blur_mask(load_landmask())
        self._lat_rows = [90.0 - (r + 0.5) * DLAT for r in range(ROWS)]
        self._lon_cols = [-180.0 + (c + 0.5) * DLON for c in range(COLS)]
        self._cos_rows = [math.cos(math.radians(a)) for a in self._lat_rows]
        self._cos_lon = [math.cos(math.radians(a)) for a in self._lon_cols]
        self._sin_lon = [math.sin(math.radians(a)) for a in self._lon_cols]
        self._area_w = [math.cos(math.radians(a)) for a in self._lat_rows]
        self._area_sum = sum(self._area_w) * COLS

        # land within 4 cells to the east of an ocean cell: the stratocumulus decks sit off west coasts
        self._shield = [[max(self.mask[r][(c + k) % COLS] for k in range(1, 5)) for c in range(COLS)]
                        for r in range(ROWS)]

        self.locations = [P.PRESETS[pid] for pid in P.PRESET_IDS]
        self._loc_fl = [self.mask[cell_of(p["lat"], p["lon"])[0]][cell_of(p["lat"], p["lon"])[1]]
                        for p in self.locations]

        # the weather of each dot: three smooth seeded noises (rain, cloud, fog) in sim time and the last label
        # (the label has hysteresis, so it never flickers); separate streams, so the systems are unaffected
        self._wx_noise = [self._make_noise(i) for i in range(len(self.locations))]
        self._wlabel: list = [None] * len(self.locations)
        self._wcloud: list = [None] * len(self.locations)
        self._wcloud_raw: list = [None] * len(self.locations)
        self._wprecip: list = [None] * len(self.locations)
        self._wt: list = [0.0] * len(self.locations)

        self.systems: list = []
        self._next_id = 1
        self._name_i = self.rng.randrange(len(CYCLONE_NAMES))
        self._low_cd = {1: 0.0, -1: 0.0}
        self._events_day = 0
        self._prev_outbreak = False
        self._populate()

        self._grid = None
        self._grid_frame = -10 ** 9
        self._grid_gap = max(1, int(math.ceil(self.fps / GRID_HZ)))
        self._dirty = True
        self._stats = {"mean_temp_c": 0.0, "cloud_cover": 0.0}

    # -- clock ---------------------------------------------------------------------------------
    @property
    def yf(self) -> float:
        return (self._base_yf + self._off_yf * (1.0 - _smoothstep(self._glide_t))) % 1.0

    @property
    def uh(self) -> float:
        return (self._base_uh + self._off_uh * (1.0 - _smoothstep(self._glide_t))) % 24.0

    def month_now(self) -> float:
        return 1.0 + 12.0 * self.yf

    def itcz(self) -> float:
        return itcz_lat(self.yf)

    def sun(self) -> tuple:
        return subsolar(self.yf, self.uh)

    def _jump(self, new_yf=None, new_uh=None):
        """Move the calendar and/or the clock: the shown time glides from where it is to the new one."""
        cur_yf, cur_uh = self.yf, self.uh
        tgt_yf = self._base_yf if new_yf is None else new_yf % 1.0
        tgt_uh = self._base_uh if new_uh is None else new_uh % 24.0
        self._off_yf = _wrap_signed(cur_yf - tgt_yf, 1.0)
        self._off_uh = _wrap_signed(cur_uh - tgt_uh, 24.0)
        self._base_yf, self._base_uh = tgt_yf, tgt_uh
        self._glide_t = 0.0
        # long jumps glide longer so that a cell never changes much between two grid updates
        self._glide_dur = 3.0 + 15.0 * min(1.0, abs(self._off_yf) / 0.5) + 4.0 * min(1.0, abs(self._off_uh) / 12.0)
        self._dirty = True

    # -- commands ------------------------------------------------------------------------------
    def handle_command(self, cmd: dict, events: list):
        c = cmd.get("cmd")
        if c == "pause":
            self.paused = True
        elif c == "resume":
            self.paused = False
        elif c == "speed":
            try:
                v = float(cmd.get("value", 1.0))
            except (TypeError, ValueError):
                return
            if not math.isfinite(v):
                return
            self.speed = max(MIN_SPEED, min(MAX_SPEED, v))
        elif c == "season":
            try:
                mo = float(cmd.get("month"))
            except (TypeError, ValueError):
                return
            if not math.isfinite(mo):
                return
            mo = (mo - 1.0) % 12.0 + 1.0
            self._jump(new_yf=(mo - 1.0) / 12.0)
            events.append(f"t={self.t:.1f} the calendar moves to month {mo:.1f}")
        elif c == "hour":
            try:
                hv = float(cmd.get("value"))
            except (TypeError, ValueError):
                return
            if not math.isfinite(hv):
                return
            self._jump(new_uh=hv % 24.0)
            events.append(f"t={self.t:.1f} the clock moves to {hv % 24.0:.1f} UTC")
        elif c == "save":
            self.outbox.append({"type": "saved", "ok": False, "path": str(cmd.get("path") or ""),
                                "t": _r2(self.t), "frame": self.frame,
                                "error": "saving is not available in world mode"})
        elif c == "quit":
            self.running = False
        # everything else is ignored per PROTOCOL.md

    def drain_outbox(self) -> list:
        msgs = self.outbox
        self.outbox = []
        return msgs

    # -- hello ---------------------------------------------------------------------------------
    def hello_message(self) -> dict:
        step = 8
        w, h = 1920, 1080
        srcs = list(SOURCES)
        for pid in P.PRESET_IDS:
            for u in P.PRESETS[pid]["climate"]["src"]:
                if u not in srcs:
                    srcs.append(u)
        return {
            "type": "hello", "version": 2, "seed": self.seed, "loaded": False, "t": _r2(self.t),
            "world": {"w": w, "h": h, "ground_y": 1000, "terrain_kind": "flat",
                      "terrain": {"step": step, "y": [1000] * (int(math.ceil(w / step)) + 1)}},
            "settlements": [], "objects": [],
            "mode": "world",
            "world_map": {
                "grid": {"cols": COLS, "rows": ROWS, "dlon": DLON, "dlat": DLAT},
                "year_days": self.year_days, "day_length_s": int(DAY_S),
                "locations": P.world_entries(),
                "regions": P.region_map(),
                "sources": srcs,
            },
        }

    # -- systems -------------------------------------------------------------------------------
    def _new_system(self, kind: str, lat: float, lon: float, peak: float, radius: float, life: float,
                    age: float = 0.0, name=None, parent=None, basin=None) -> dict:
        s = {"id": self._next_id, "kind": kind, "lat": lat, "lon": _wrap_lon(lon), "peak": peak,
             "radius": radius, "life": life, "age": age, "name": name, "parent": parent, "basin": basin,
             "strength": 0.0, "hemi": 1 if lat >= 0.0 else -1}
        self._next_id += 1
        self.systems.append(s)
        return s

    def _low_life(self) -> float:
        return _clamp(0.12 * self.year_s, 120.0, 600.0)

    def _cyc_life(self) -> float:
        return _clamp(0.07 * self.year_s, 90.0, 420.0)

    def _spawn_low(self, hemi: int, age_frac: float = 0.0):
        rng = self.rng
        if hemi > 0:
            lon0, lat0 = rng.choice(STORM_TRACK_SEEDS[1])
            lon, lat = lon0 + rng.uniform(-14.0, 14.0), lat0 + rng.uniform(-6.0, 6.0)
        else:
            lon, lat = rng.uniform(-180.0, 180.0), -rng.uniform(40.0, 58.0)
        life = self._low_life() * rng.uniform(0.8, 1.2)
        low = self._new_system("low", lat, lon, rng.uniform(0.55, 1.0), rng.uniform(9.0, 14.0), life,
                               age=age_frac * life)
        self._new_system("front", lat, lon, 1.0, low["radius"] * 0.95, life, age=age_frac * life,
                         parent=low["id"])
        return low

    def _cyclone_season(self, basin: tuple, month: float) -> float:
        return _season_bump(month, basin[4], basin[5], basin[6])

    def _spawn_cyclone(self, basin: tuple, age_frac: float = 0.0, events=None):
        rng = self.rng
        name_b, hemi, (la0, la1), (lo0, lo1) = basin[0], basin[1], basin[2], basin[3]
        for _ in range(12):
            lat = rng.uniform(la0, la1)
            lon = _wrap_lon(rng.uniform(lo0, lo1))
            r, c = cell_of(lat, lon)
            if self.mask[r][c] < 0.15:
                break
        else:
            return None
        life = self._cyc_life() * rng.uniform(0.8, 1.2)
        nm = CYCLONE_NAMES[self._name_i % len(CYCLONE_NAMES)]
        self._name_i += 1
        s = self._new_system("cyclone", lat, lon, rng.uniform(0.55, 1.0), rng.uniform(3.5, 5.5), life,
                             age=age_frac * life, name=nm, basin=name_b)
        if events is not None:
            events.append(f"day {int(self.t // DAY_S)}: tropical cyclone {nm} formed in the {name_b}")
        return s

    def _populate(self):
        rng = self.rng
        yf = self.yf
        month = self.month_now()
        for hemi in (1, -1):
            n = int(round(2.5 + 2.5 * winter_factor(hemi, yf)))
            for _ in range(n):
                self._spawn_low(hemi, age_frac=rng.uniform(0.1, 0.8))
        # subtropical highs (ocean basins): lon, lat sign
        for hemi, lon in ((1, -40.0), (1, -140.0), (-1, -10.0), (-1, -100.0), (-1, 80.0)):
            s = self._new_system("high", hemi * 29.0, lon, rng.uniform(0.6, 0.9), rng.uniform(14.0, 18.0),
                                 1e12)
            s["base_lat"] = hemi * 29.0
        for b in BASINS:
            if self._cyclone_season(b, month) > 0.6 and rng.random() < 0.8:
                self._spawn_cyclone(b, age_frac=rng.uniform(0.15, 0.7))
        self._new_system("tornado_outbreak", 36.0, -97.0, 1.0, 7.0, 1e12)
        self._update_strengths()

    def _env(self, s: dict) -> float:
        L, a = s["life"], s["age"]
        if L > 1e11:
            return 1.0
        k = 0.22 if s["kind"] != "cyclone" else 0.35
        return _smoothstep(a / (k * L)) * _smoothstep((L - a) / (0.3 * L))

    def _season_weight(self, s: dict) -> float:
        yf = self.yf
        kind = s["kind"]
        if kind in ("low", "front"):
            return 0.55 + 0.45 * winter_factor(s["hemi"], yf)
        if kind == "cyclone":
            b = next(x for x in BASINS if x[0] == s["basin"])
            return self._cyclone_season(b, self.month_now())
        if kind == "tornado_outbreak":
            return math.exp(-((_wrap_signed(self.month_now() - 5.4, 12.0)) / 1.7) ** 2)
        if kind == "high":
            return 0.85 + 0.15 * (1.0 - winter_factor(s["hemi"], yf))
        return 1.0

    def _update_strengths(self):
        by_id = {s["id"]: s for s in self.systems}
        for s in self.systems:
            if s["kind"] == "front":
                par = by_id.get(s["parent"])
                s["strength"] = 0.85 * par["strength"] if par else 0.0
                continue
            s["strength"] = s["peak"] * self._env(s) * self._season_weight(s)

    def _advance_systems(self, dt: float, events: list):
        yf = self.yf
        itcz = self.itcz()
        by_id = {s["id"]: s for s in self.systems}
        for s in self.systems:
            kind = s["kind"]
            if kind == "front":
                par = by_id.get(s["parent"])
                if par is not None:
                    s["lat"] = par["lat"] - par["hemi"] * 0.55 * par["radius"]
                    s["lon"] = _wrap_lon(par["lon"] - 0.45 * par["radius"])
                    s["hemi"] = par["hemi"]
                    s["age"] = par["age"]
                continue
            if kind == "tornado_outbreak":
                m = self.month_now()
                s["lat"] = 33.0 + 1.5 * _clamp(m - 3.0, 0.0, 3.0)
                continue
            lat, lon = s["lat"], s["lon"]
            u, v, _p = _belt(lat, itcz)
            cl = max(0.2, math.cos(math.radians(lat)))
            if kind == "low":
                s["age"] += dt
                vlat = 0.25 * v
                if abs(lat) < 36.0:
                    vlat += s["hemi"] * 1.0
                elif abs(lat) > 66.0:
                    vlat -= s["hemi"] * 1.0
                dlon = u * DRIFT_K / (111320.0 * cl) * 1.0
                dlat = vlat * DRIFT_K / 110570.0
            elif kind == "high":
                s["age"] += dt
                target = s["base_lat"] + 0.5 * itcz
                dlon = 0.12 * u * DRIFT_K / (111320.0 * cl)
                dlat = (target - lat) * 0.02
            else:   # cyclone
                s["age"] += dt
                r, c = cell_of(lat, lon)
                if self.mask[r][c] > 0.45:
                    s["age"] += 2.0 * dt            # cut off from the warm sea
                dlon = u * DRIFT_K / (111320.0 * cl)
                dlat = s["hemi"] * 0.06 + (v * 0.8 * DRIFT_K / 110570.0)
                dlat = s["hemi"] * abs(dlat) * (0.45 if abs(lat) < 28.0 else 0.8)
            s["lat"] = _clamp(lat + dlat * dt, -85.0, 85.0)
            s["lon"] = _wrap_lon(lon + dlon * dt)
            s["hemi"] = 1 if s["lat"] >= 0.0 else -1
        self._update_strengths()
        # births and deaths
        dead = set()
        for s in self.systems:
            if s["kind"] in ("low", "cyclone") and s["age"] >= s["life"]:
                dead.add(s["id"])
                if s["kind"] == "cyclone":
                    events.append(f"day {int(self.t // DAY_S)}: tropical cyclone {s['name']} dissipated")
        if dead:
            dead |= {s["id"] for s in self.systems if s["parent"] in dead}
            self.systems = [s for s in self.systems if s["id"] not in dead]
        for hemi in (1, -1):
            self._low_cd[hemi] -= dt
            n_target = int(round(2.5 + 2.5 * winter_factor(hemi, yf)))
            n = sum(1 for s in self.systems if s["kind"] == "low" and s["hemi"] == hemi)
            if n < n_target and self._low_cd[hemi] <= 0.0:
                self._spawn_low(hemi)
                self._low_cd[hemi] = self._low_life() / (n_target + 1) * self.rng.uniform(0.7, 1.3)
        month = self.month_now()
        for b in BASINS:
            f = self._cyclone_season(b, month)
            n = sum(1 for s in self.systems if s["kind"] == "cyclone" and s["basin"] == b[0])
            if f > 0.35 and n < b[7]:
                mean_gap = self.year_s * 0.04 / f
                if self.rng.random() < dt / mean_gap:
                    self._spawn_cyclone(b, events=events)
        out = next(s for s in self.systems if s["kind"] == "tornado_outbreak")
        on = out["strength"] > 0.5
        if on != self._prev_outbreak:
            events.append(f"day {int(self.t // DAY_S)}: " + (
                "tornado outbreak conditions build over the Great Plains" if on
                else "the tornado outbreak risk over the Great Plains fades"))
            self._prev_outbreak = on

    # -- fields at a point ---------------------------------------------------------------------
    @staticmethod
    def _contrib(s: dict, lat: float, lon: float):
        """(d cloud, d precip, d pressure, d u, d v) that system `s` adds at (lat, lon)."""
        st = s["strength"]
        if st <= 0.002:
            return 0.0, 0.0, 0.0, 0.0, 0.0
        dlon = lon - s["lon"]
        if dlon > 180.0:
            dlon -= 360.0
        elif dlon < -180.0:
            dlon += 360.0
        dy = lat - s["lat"]
        dx = dlon * math.cos(math.radians(lat))
        R = s["radius"]
        kind = s["kind"]
        if kind == "front":
            th = math.radians(60.0 if s["hemi"] > 0 else -60.0)
            along = dx * math.cos(th) + dy * math.sin(th)
            perp = -dx * math.sin(th) + dy * math.cos(th)
            la = abs(along) / (1.2 * R)
            if la >= 1.0:
                return 0.0, 0.0, 0.0, 0.0, 0.0
            w = math.exp(-(perp / (0.22 * R)) ** 2) * _smoothstep(1.0 - la)
            return 0.55 * st * w, 0.45 * st * w, 0.0, 0.0, 0.0
        d2 = (dx * dx + dy * dy) / (R * R)
        if d2 > 6.0:
            return 0.0, 0.0, 0.0, 0.0, 0.0
        w = math.exp(-d2)
        if kind == "tornado_outbreak":
            return 0.45 * st * w, 0.55 * st * w, -3.0 * st * w, 0.0, 0.0
        x = math.sqrt(d2)
        hs = s["hemi"]
        # tangential wind: peaks near x = 0.7 radii, dies away with exp(-x^2) and is tapered to exactly 0 at the
        # edge of the influence window (d2 = 6) so that nothing jumps when a cell leaves it
        prof = x * math.exp(1.0 - d2) / 1.166 * (1.0 - _smoothstep((d2 - 4.0) / 2.0))
        if kind == "low":
            vt = 14.0 * st * prof
            circ, inflow = hs, 1.0
            dc, dp, dpr = 0.55 * st * w, 0.55 * st * w, -14.0 * st * w
        elif kind == "cyclone":
            vt = 38.0 * st * prof
            circ, inflow = hs, 1.0
            dc, dp, dpr = 0.85 * st * w, 0.95 * st * w, -35.0 * st * w
        else:   # high: clockwise in the north, air spreading out
            vt = 8.0 * st * prof
            circ, inflow = -hs, -1.0
            dc, dp, dpr = -0.35 * st * w, -0.5 * st * w, 9.0 * st * w
        if x < 1e-6:
            return dc, dp, dpr, 0.0, 0.0
        ex, ey = dx / (x * R), dy / (x * R)       # unit vector away from the centre (east, north)
        # circulation: counter-clockwise (north) means u = -vt * ey, v = +vt * ex; plus a little inflow
        du = -circ * vt * ey - 0.25 * inflow * vt * ex
        dv = circ * vt * ex - 0.25 * inflow * vt * ey
        return dc, dp, dpr, du, dv

    def _outbreak_gate(self, lon: float) -> float:
        """How much of its strength the (convective) tornado outbreak has at this longitude now: 1 in the local afternoon,
        OUTBREAK_NIGHT_FLOOR in the dead of night."""
        return atm.gate(atm.cb_day(((self.uh + lon / 15.0 - OUTBREAK_LAG_H) % 24.0) / 24.0), OUTBREAK_NIGHT_FLOOR)

    def _point_systems(self, lat: float, lon: float) -> tuple:
        sc = sp = spr = su = sv = 0.0
        for s in self.systems:
            if abs(s["lat"] - lat) > 3.0 * s["radius"] + 2.0:
                continue
            a, b, c, d, e = self._contrib(s, lat, lon)
            if s["kind"] == "tornado_outbreak" and (a or b or c):
                g = self._outbreak_gate(lon)
                a *= g
                b *= g
                c *= g
            sc += a
            sp += b
            spr += c
            su += d
            sv += e
        return sc, sp, spr, su, sv

    # -- the grid ------------------------------------------------------------------------------
    def _build_grid(self) -> dict:
        yf, uh = self.yf, self.uh
        itcz = self.itcz()
        cos_l = math.cos(TWO_PI * (yf - FIT["phl"]))
        cos_o = math.cos(TWO_PI * (yf - FIT["pho"]))
        th_u = TWO_PI * (uh - 14.5) / 24.0
        cu, su_ = math.cos(th_u), math.sin(th_u)
        f = FIT
        temp = [0.0] * (ROWS * COLS)
        tbase = [0.0] * (ROWS * COLS)
        cloud = [0.0] * (ROWS * COLS)
        precip = [0.0] * (ROWS * COLS)
        uu = [0.0] * (ROWS * COLS)
        vv = [0.0] * (ROWS * COLS)
        pres = [0.0] * (ROWS * COLS)
        snow = [0.0] * (ROWS * COLS)
        sl, so = f["sl"], f["so"]
        for r in range(ROWS):
            lat = self._lat_rows[r]
            s_ = math.sin(math.radians(lat))
            c2 = 1.0 - s_ * s_
            tann = f["A"] + f["B"] * c2 + f["C"] * c2 * c2
            hg = math.tanh(lat / 6.0) * abs(s_) ** f["p"]
            icer = f["ice"] * _smoothstep((abs(lat) - 66.0) / 12.0)       # the ice sheets stand 2-3 km high
            ub, vb, pb = _belt(lat, itcz)
            cloud_st, precip_st, arid, mons, deck, equat = _row_climate(lat, yf)
            cl = self._cos_rows[r]
            dscale = 0.5 + 0.5 * cl
            row_mask = self.mask[r]
            row_shield = self._shield[r]
            base_i = r * COLS
            for c in range(COLS):
                fl = row_mask[c]
                tm = tann + hg * (sl * fl * cos_l + so * (1.0 - fl) * cos_o) + icer * fl
                # the ITCZ swings farther from the equator over land than over sea (monsoons)
                zt = math.exp(-((lat - (itcz + fl * ITCZ_LAND_OVERSHOOT * (itcz - ITCZ_MEAN_LAT))) / 6.5) ** 2)
                cd = (cloud_st + 0.50 * zt * (1.0 + 0.2 * fl)
                      + fl * (0.30 * mons + EQUATORIAL_CLOUD * equat - 0.14 * arid * (1.0 - mons))
                      + 0.25 * (1.0 - fl) * row_shield[c] * deck)
                pr0 = (precip_st * (1.0 - 0.8 * fl * arid * (1.0 - mons)) + 0.70 * zt ** 1.4 * (1.0 + 0.35 * fl)
                       + fl * (0.5 * mons + EQUATORIAL_RAIN * equat))
                i = base_i + c
                tbase[i] = tm
                cloud[i] = cd
                precip[i] = pr0 * (0.4 + 0.6 * cd)
                uu[i] = ub
                vv[i] = vb
                pres[i] = pb - 0.12 * (tm - tann) * fl
                sn = (0.5 - tm) / 9.0
                snow[i] = (0.0 if sn <= 0.0 else 1.0 if sn >= 1.0 else sn) * (1.0 if fl >= 0.8 else 1.25 * fl)
                # day cycle: coldest before sunrise, warmest mid-afternoon, damped under cloud
                amp = (DIURNAL_LAND_C * fl + DIURNAL_SEA_C * (1.0 - fl)) * dscale
                temp[i] = amp * (cu * self._cos_lon[c] - su_ * self._sin_lon[c])
        # systems add their cloud, rain, pressure and circulation inside a window around each centre
        for s in self.systems:
            if s["strength"] <= 0.002:
                continue
            ext = 2.45 * s["radius"] + 2.0
            r0 = max(0, int((90.0 - (s["lat"] + ext)) / DLAT))
            r1 = min(ROWS - 1, int((90.0 - (s["lat"] - ext)) / DLAT))
            for r in range(r0, r1 + 1):
                lat = self._lat_rows[r]
                cl = max(0.15, self._cos_rows[r])
                span = int(ext / (DLON * cl)) + 1
                if span * 2 + 1 >= COLS:
                    cols = range(COLS)
                else:
                    cc = int((_wrap_lon(s["lon"]) + 180.0) / DLON) % COLS
                    cols = [(cc + k) % COLS for k in range(-span, span + 1)]
                for c in cols:
                    a, b, p_, du, dv = self._contrib(s, lat, self._lon_cols[c])
                    if a == 0.0 and b == 0.0 and p_ == 0.0 and du == 0.0 and dv == 0.0:
                        continue
                    if s["kind"] == "tornado_outbreak":
                        g = self._outbreak_gate(self._lon_cols[c])
                        a *= g
                        b *= g
                        p_ *= g
                    i = r * COLS + c
                    cloud[i] += a
                    precip[i] += b
                    pres[i] += p_
                    uu[i] += du
                    vv[i] += dv
        sum_t = sum_c = 0.0
        for r in range(ROWS):
            w = self._area_w[r]
            base_i = r * COLS
            row_t = row_c = 0.0
            for c in range(COLS):
                i = base_i + c
                cd = cloud[i]
                cd = 0.0 if cd < 0.0 else 1.0 if cd > 1.0 else cd
                cloud[i] = cd
                pr = precip[i]
                precip[i] = 0.0 if pr < 0.0 else 1.0 if pr > 1.0 else pr
                # clouds damp the day cycle and the clear cold nights
                t = tbase[i] + temp[i] * (1.0 - 0.5 * cd)
                temp[i] = t
                row_t += t
                row_c += cd
            sum_t += w * row_t
            sum_c += w * row_c
        self._stats["mean_temp_c"] = sum_t / self._area_sum
        self._stats["cloud_cover"] = sum_c / self._area_sum
        return {
            "temp_c": [round(x, 1) for x in temp],
            "cloud": [round(x, 2) for x in cloud],
            "precip": [round(x, 2) for x in precip],
            "u": [round(x, 1) for x in uu],
            "v": [round(x, 1) for x in vv],
            "pressure_hpa": [round(x, 1) for x in pres],
            "snow": [round(x, 2) for x in snow],
        }

    # -- locations -----------------------------------------------------------------------------
    # The weather of a dot follows its preset's real monthly precipitation. From the month's total `P` and
    # temperature a wet-day fraction is derived (a wet day delivers about 3 mm in the cold up to 10 mm in
    # the warm; very wet months rain more hours of the day), which is the chance that it is raining now.
    # Whether it IS raining is decided by a smooth seeded noise u(t) (sum of four sine waves of 0.2-1 sim
    # day through the normal CDF, so it is uniform in 0..1 over time): it rains while u < the chance, which the
    # day cycle (convective afternoons in warm wet months) and the passing systems modulate. A dry month
    # (Fresno in July, the Sahara, the Atacama) therefore never rains and a wet one (Cherrapunji in July)
    # nearly always does, with the rain coming and going over sim hours. Labels use hysteresis.
    _NOISE_DAYS = (0.2, 0.35, 0.6, 1.0)

    def _make_noise(self, i: int) -> dict:
        r = random.Random((self.seed * 1000003 + 7919 * (i + 1)) & 0xFFFFFFFF)
        out = {}
        for key in ("u", "c", "f"):
            out[key] = ([DAY_S * d * r.uniform(0.85, 1.15) for d in self._NOISE_DAYS],
                        [r.uniform(0.0, TWO_PI) for _ in self._NOISE_DAYS])
        return out

    def _noise(self, i: int, key: str) -> float:
        per, ph = self._wx_noise[i][key]
        t = self.t
        z = 0.0
        for T, f in zip(per, ph):
            z += math.sin(TWO_PI * t / T + f)
        z *= math.sqrt(2.0 / len(per))
        return 0.5 * (1.0 + math.erf(z / 1.4142135623730951))

    @staticmethod
    def rain_chance(rain_mm: float, temp_c: float) -> float:
        """Chance (0..1) that it is precipitating at a place whose month brings `rain_mm` at `temp_c`."""
        a = 3.0 + 7.0 * _smoothstep((temp_c + 5.0) / 25.0)            # mm per wet day
        wet_days = min(0.92, rain_mm / a / 30.4)
        g = (1.0 - math.exp(-rain_mm / 300.0)) ** 1.5
        h0 = 0.25 - 0.05 * _smoothstep((temp_c - 10.0) / 15.0)        # share of a wet day that it rains
        return wet_days * (h0 + (1.0 - h0) * g)

    @staticmethod
    def _follow(last, target: float, rise: float, fall: float, dt: float) -> float:
        """`target`, but moving from `last` by at most `rise` / `fall` per sim second (a first value is taken as is)."""
        if last is None:
            return target
        return last + max(-fall * dt, min(rise * dt, target - last))

    def _storm_proximity(self, lat: float, lon: float) -> float:
        """Strength of the nearest storm system (low, cyclone, tornado outbreak), fading out with distance."""
        best = 0.0
        for s in self.systems:
            if s["kind"] not in ("cyclone", "tornado_outbreak", "low"):
                continue
            R = s["radius"]
            if abs(s["lat"] - lat) > 2.2 * R:
                continue
            dx = _wrap_lon(lon - s["lon"]) * math.cos(math.radians(lat))
            d = math.hypot(dx, lat - s["lat"]) / R
            st = s["strength"] * (self._outbreak_gate(lon) if s["kind"] == "tornado_outbreak" else 1.0)
            best = max(best, st * (1.0 - _smoothstep((d - 0.6) / 0.8)))
        return best

    def _location(self, i: int, p: dict) -> dict:
        yf, uh = self.yf, self.uh
        c = p["climate"]
        lat, lon = p["lat"], p["lon"]
        lh = (uh + lon / 15.0) % 24.0
        sc, sp, spr, su, sv = self._point_systems(lat, lon)
        sc = _clamp(sc, -0.35, 1.0)
        normal = _cyc_interp(c["monthly_temp_c"], yf)
        rain_mm = max(0.0, _cyc_interp(c["monthly_precip_mm"], yf))
        day = max(0.0, math.cos(TWO_PI * (lh - 14.5) / 24.0))
        diurnal = 0.5 * c["diurnal_range_c"] * math.cos(TWO_PI * (lh - 14.5) / 24.0)
        temp = normal + diurnal * (1.0 - 0.4 * _clamp(sc, 0.0, 1.0)) - 1.2 * _clamp(sc, 0.0, 1.0) * day
        itcz = self.itcz()
        ub, vb, _pb = _belt(lat, itcz)
        # a cheap, smooth stand-in for the station wind: the belt wind scaled by the place's exposure
        wind = math.hypot(0.5 * ub + su, 0.5 * vb + sv) + 1.0

        # chance of rain now: the month's climate, the convective day cycle, the passing systems
        q = self.rain_chance(rain_mm, normal)
        wet_gate = min(1.0, rain_mm / 60.0)
        conv = 0.65 * _smoothstep((normal - 16.0) / 8.0) * min(1.0, rain_mm / 80.0)
        q_eff = q * (1.0 + conv * math.cos(TWO_PI * (lh - 16.0) / 24.0))
        spp = max(0.0, sp)
        q_eff = q_eff * (1.0 + 0.8 * spp) + 0.3 * spp * wet_gate - 0.6 * max(0.0, -sp) * q_eff
        q_eff = _clamp(q_eff, 0.0, 0.97)
        u = self._noise(i, "u")
        inten = 0.25 + 0.75 * (1.0 - math.exp(-rain_mm / 200.0))
        # (it precipitates while u < q_eff: the ramp is centred there and narrower in dry months; the gate keeps a
        # month that brings next to no rain completely dry)
        ramp = 0.08 + 0.17 * min(1.0, q_eff / 0.3)
        precip_t = _clamp(_smoothstep((q_eff - u) / ramp + 0.25) * inten * _smoothstep(q_eff / 0.03))
        # rain starts and stops over sim minutes, not at once: rate limits in sim seconds (1 s = 4.8 sim minutes)
        dt = abs(self.t - self._wt[i])
        precip = self._follow(self._wprecip[i], precip_t, 0.25, 0.12, dt)
        self._wprecip[i] = precip

        # cloud: its own chance from the climate and the humidity; it is overcast whenever it precipitates
        cq = _clamp(0.10 + 0.70 * q + 0.25 * p["humidity"] * (0.2 + 0.8 * wet_gate))
        w = self._noise(i, "c")
        raw = self._follow(self._wcloud_raw[i], _clamp(_smoothstep((cq - w) / 0.5 + 0.5) + 0.6 * sc), 0.4, 0.05, dt)
        self._wcloud_raw[i] = raw
        cloud = max(raw, 0.7 * _smoothstep(precip / 0.1) + 0.3 * precip)

        # fog: cool, humid, calm mornings, only now and then
        fog = ((1.0 - _smoothstep((temp - 8.0) / 8.0)) * _smoothstep((lh - 1.5) / 2.5)
               * (1.0 - _smoothstep((lh - 7.0) / 2.5)) * (1.0 - _smoothstep((wind - 4.0) / 4.0))
               * _smoothstep((p["humidity"] - 0.45) / 0.2) * _smoothstep((0.22 - self._noise(i, "f")) / 0.12)
               * (1.0 - _smoothstep(precip / 0.1)))
        cloud = max(cloud, min(1.0, 2.0 * fog))
        # a cloud deck thins out slowly after the rain (at most 0.02 per sim second: about four sim hours for 0..1)
        last = self._wcloud[i]
        if last is not None:
            cloud = max(cloud, last - 0.02 * dt)
        self._wcloud[i] = cloud
        self._wt[i] = self.t

        # label, with hysteresis (a different threshold to leave a state than to enter it)
        prev = self._wlabel[i]
        if precip > (0.07 if prev in ("rain", "snow", "storm") else 0.15):
            near = self._storm_proximity(lat, lon)
            peak = max(0.0, math.cos(TWO_PI * (lh - 16.0) / 24.0)) ** 2
            storm = max(_smoothstep((near - 0.35) / 0.4) * _smoothstep(precip / 0.5),
                        0.9 * peak * _smoothstep((rain_mm - 200.0) / 150.0) * _smoothstep((normal - 20.0) / 4.0)
                        * _smoothstep(precip / 0.55))
            if storm > (0.4 if prev == "storm" else 0.6):
                weather = "storm"
            elif temp < (2.0 if prev == "snow" else 1.0):
                weather = "snow"
            else:
                weather = "rain"
        elif fog > (0.3 if prev == "fog" else 0.5):
            weather = "fog"
        elif cloud > (0.42 if prev == "cloudy" else 0.58):
            weather = "cloudy"
        else:
            weather = "clear"
        self._wlabel[i] = weather
        return {"id": p["id"], "temp_c": _r1(temp), "cloud": _r2(cloud), "precip": _r2(precip),
                "wind_ms": _r1(min(40.0, wind)), "weather": weather, "local_hour": _r2(lh),
                "month": _month_out(yf)}

    # -- stepping ------------------------------------------------------------------------------
    def step(self, dt_real: float, extra_events=None) -> dict:
        events: list = list(extra_events) if extra_events else []
        if self._glide_t < 1.0:
            self._glide_t = min(1.0, self._glide_t + dt_real / self._glide_dur)
            self._dirty = True
        if not self.paused:
            dt = dt_real * self.speed
            self.t += dt
            self._base_yf = (self._base_yf + dt / self.year_s) % 1.0
            self._base_uh = (self._base_uh + dt * 24.0 / DAY_S) % 24.0
            self._advance_systems(dt, events)
            self._dirty = True
        self.frame += 1
        return self._build_frame(events)

    def _build_frame(self, events: list) -> dict:
        yf, uh = self.yf, self.uh
        slat, slon = subsolar(yf, uh)
        frame = {
            "type": "world", "frame": self.frame, "t": _r2(self.t), "speed": _r2(self.speed),
            "paused": self.paused, "year_frac": min(0.999, _r3(yf)), "month": _month_out(yf),
            "day": int(self.t // DAY_S), "utc_hour": _r2(uh),
            "sun": {"lat": _r1(slat), "lon": _r1(slon)},
        }
        if self._grid is None or (self._dirty and self.frame - self._grid_frame >= self._grid_gap):
            self._grid = self._build_grid()
            self._grid_frame = self.frame
            self._dirty = False
            frame["grid"] = self._grid
        frame["systems"] = [
            {"id": s["id"], "kind": s["kind"], "lat": _r1(s["lat"]), "lon": _r1(s["lon"]),
             "strength": _r2(s["strength"]), "radius_deg": _r1(s["radius"]), "name": s["name"]}
            for s in self.systems if s["strength"] >= 0.02
        ]
        frame["locations"] = [self._location(i, p) for i, p in enumerate(self.locations)]
        storms = sum(1 for s in self.systems if s["strength"] > 0.35 and s["kind"] in ("low", "cyclone", "tornado_outbreak"))
        frame["stats"] = {"mean_temp_c": _r1(self._stats["mean_temp_c"]),
                          "cloud_cover": _r2(self._stats["cloud_cover"]), "storms": storms}
        frame["events"] = events
        return frame
