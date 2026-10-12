"""Gases and the climate-driven cloud spawner (protocol v2.3 "Climate: day, season and gases").

Everything numeric in here is a pure function of a handful of inputs, so the direction of every
effect is unit-testable (see tests/test_atmosphere.py). `Atmosphere` only holds the three gases the
user can change; `Simulation` calls `snapshot()` once per tick.

Inputs
    temp_c    surface temperature at the lowest ground (world.surface_temp_c)
    heat_c    how much warmer the surface air is than the air aloft (see `heating_c`)
    speed     prevailing wind speed in m/s, hypot(prevailing_u, prevailing_v)
    humidity  0..1 water vapour available to clouds      aerosol  0..1 condensation nuclei
    co2_ppm   180..2000; only acts through the temperature (3.0 C per doubling against 420 ppm)

Formulas (every constant is named below; each factor is 1.0 at the reference sky so the default
temperate world behaves like the v2.2 sandbox did)
    hum_factor(h)      = (0.15 + h^1.6) / (0.15 + 0.575^1.6)                    0.27 .. 2.0
    temp_factor(T)     = (0.45 + 1.75 sigmoid((T - 12)/9)) / (same at 10 C)    0.48 .. 1.7
    wind_factor(s)     = (0.45 + 0.95 g(s)) / (1 + (s/20)^3) / ref,  g(s) = (s/7) e^(1 - s/7)
                         calm 0.33, peak ~1.4 near 7 m/s, gale (25 m/s) 0.18 -- non-monotone on purpose
    aerosol_factor(a)  = 0.85 + 0.5 a                                            1.0 at a = 0.3
    instability        = sigmoid((heat_c - 9)/4.5) * (0.35 + 0.65 humidity)      0 .. 1
    shear              = 1 - exp(-(speed/12)^1.6)                                0 .. 1
    x                  = hum_factor * temp_factor^0.6 * (1 + 0.5 (inst - 0.3)) * aerosol_factor * wind_factor^0.5
    cloud_target       = width_units * (1.2 + 5.8 (1 - e^-x))   clouds, capped below 7 per 1280 px
    spawn_per_min      = width_units * 60 * (target per 1280 px) / 115 s * (1 + 0.8 (inst - 0.3)) * wind_factor^0.35
                         (never below SPAWN_FLOOR_PER_1280 per minute per 1280 px: a dry cold sky still clouds over)
    type_weights       = base mix x per-type factor (relative to the reference sky), normalised to 1.
                         Convective types want warmth and instability, sheets want cool humid stable air,
                         cirrus is nearly indifferent, supercell needs instability AND shear.

The day cycle of convection (Phase O). Given the time of day (`tod`, 0 = midnight, 0.5 = noon, local solar time)
`snapshot` multiplies the weights of the convective types by a gate that is 1 in the afternoon and falls to a floor
at night, lifts the sheets towards dawn, and shrinks the cloud target a little at night in proportion to the
convective share of the mix (clear spells). The same gates, handed to clouds.update_cloud through `Atmosphere`, make a
cumulus tower only while the ground is hot, and make convective clouds age faster after dark (the life cycle ends them;
nothing is removed). tod=None (every caller that does not know the hour) is exactly the pre-Phase-O behaviour.
    cumulus_day(tod)   0 before 07.5 h, 1 from 11.5 to 16.5 h, 0 again from 21 h (fair-weather cumulus needs surface heat)
    cb_day(tod)        0 before 09.0 h, 1 from 12.5 to 16 h, 0 again from 20 h (deep convection: births 12.5-16 h, none 20-9 h)
    cb_support(tod)    0 before 06.0 h, 1 from 10 to 16 h, 0 again from 20 h (how well the sun feeds a storm that already exists)
    gate(day, floor)   floor + (1 - floor) * day: the chance of a night storm is `floor` (night_storm_floor, climate-dependent)
"""
from __future__ import annotations

import math

CO2_REF_PPM = 420.0
CO2_RANGE = (180.0, 2000.0)
GREENHOUSE_C_PER_DOUBLING = 3.0
GAS_NAMES = ("humidity", "co2", "aerosol")

# the reference sky: every factor is exactly 1 here
REF_HUMIDITY = 0.575
REF_TEMP_C = 10.0
REF_SPEED = 5.5
REF_AEROSOL = 0.3
REF_INSTABILITY = 0.25

TARGET_MIN_PER_1280 = 1.2         # clouds per 1280 px of sky for the driest, coldest, calmest air
TARGET_SPAN_PER_1280 = 5.8        # -> asymptote 7.0 per 1280 px (21 at 3840), never reached
MEAN_LIFE_S = 115.0               # mean cloud life the nominal birth rate is sized for
SPAWN_FLOOR_PER_1280 = 0.9        # births per minute per 1280 px at the very least
CONTROL_GAIN = 1.6                # the spawner speeds up / slows down by this x the relative cloud deficit
CONTROL_MAX = 3.0

SUPERCELL_BASE = 0.18             # raw weight (before normalising) of a supercell when inst and shear are maxed
SUPERCELL_REGION_GAIN = 200.0     # a preset that lists `supercell` in its cloud_mix (Oklahoma: 0.5 of 17.7) is a
                                  # supercell region: its raw weight is multiplied by 1 + gain * its mix share


def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def sigmoid(x):
    if x < -40.0:
        return 0.0
    if x > 40.0:
        return 1.0
    return 1.0 / (1.0 + math.exp(-x))


# -- the factors -------------------------------------------------------------------------------
def gas_offset_c(co2_ppm: float) -> float:
    """Greenhouse warming: 3.0 C per doubling of CO2 against 420 ppm."""
    return GREENHOUSE_C_PER_DOUBLING * math.log2(max(1.0, co2_ppm) / CO2_REF_PPM)


ARID_HUMIDITY = 0.30              # below this a DESERT preset's air is dry enough to clear the sky (Sahara 0.21)


def aridity_factor(h):
    """What a desert preset's bone-dry air does to the sky: 1.0 for humidity of 0.30 or more, falling to 0.2 at
    humidity 0: a desert sky is nearly clear, its few clouds high and thin, and it rarely builds a storm.
    aridity_factor(0.21) = 0.59, (0.19) = 0.52, (0.0) = 0.2. Only `Atmosphere.arid` worlds (a preset whose biome
    is `desert`) use it; every other world passes af = 1.0 and is exactly as before (the generic functions
    below take `af` and default to 1.0)."""
    return 0.2 + 0.8 * min(1.0, max(0.0, h) / ARID_HUMIDITY) ** 2


def _hum_raw(h):
    return 0.15 + max(0.0, h) ** 1.6


def hum_factor(h):
    return _hum_raw(h) / _hum_raw(REF_HUMIDITY)


def _temp_raw(t):
    return 0.45 + 1.75 * sigmoid((t - 12.0) / 9.0)


def temp_factor(t):
    return _temp_raw(t) / _temp_raw(REF_TEMP_C)


def _wind_raw(s):
    s = abs(s)
    g = (s / 7.0) * math.exp(1.0 - s / 7.0)
    return (0.45 + 0.95 * g) / (1.0 + (s / 20.0) ** 3)


def wind_factor(s):
    """Calm skies spawn slowly, a moderate wind fastest, a gale tears small clouds apart again."""
    return _wind_raw(s) / _wind_raw(REF_SPEED)


def aerosol_factor(a):
    return 0.85 + 0.5 * a


def heating_c(parts: dict) -> float:
    """How much warmer the surface is than the air aloft. The air aloft barely follows the day cycle,
    the user's `temp` command or the greenhouse offset, and follows the season only a third as much."""
    return parts["diurnal_c"] + 0.65 * parts["season_c"] + parts["gas_c"] + parts["user_c"] + 4.0


def instability(heat_c, humidity):
    return clamp(sigmoid((heat_c - 9.0) / 4.5) * (0.35 + 0.65 * humidity), 0.0, 1.0)


def shear(speed):
    return clamp(1.0 - math.exp(-((abs(speed) / 12.0) ** 1.6)), 0.0, 1.0)


def cloud_target_per_1280(temp_c, inst, speed, humidity, aerosol, af=1.0):
    x = (hum_factor(humidity) * temp_factor(temp_c) ** 0.6 * (1.0 + 0.5 * (inst - 0.3))
         * aerosol_factor(aerosol) * wind_factor(speed) ** 0.5)
    x = max(x, 0.0)
    return TARGET_MIN_PER_1280 * af * af + TARGET_SPAN_PER_1280 * (1.0 - math.exp(-x * af ** 3))


def spawn_per_min_per_1280(target1280, inst, speed, af=1.0):
    r = 60.0 * target1280 / MEAN_LIFE_S * max(0.2, 1.0 + 0.8 * (inst - 0.3)) * wind_factor(speed) ** 0.35
    return max(r, SPAWN_FLOOR_PER_1280 * af * af)


# -- cloud type mix ----------------------------------------------------------------------------
TYPES9 = ("cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus", "altocumulus",
          "stratus", "cirrus", "supercell")


def _raw_factors(temp_c, inst, shear_v, humidity):
    warm = sigmoid((temp_c - 12.0) / 9.0)
    cold = 1.25 - 0.6 * warm
    sheet = (0.4 + humidity) * max(0.1, 1.3 - 1.1 * inst) * cold
    return {
        "cumulus": 0.25 + 1.5 * inst + 0.5 * warm,
        "cumulonimbus": 0.04 + 6.0 * inst * inst * (0.3 + 0.7 * warm),
        "nimbostratus": sheet,
        "stratocumulus": sheet,
        "altostratus": sheet,
        "stratus": sheet * (1.0 + 0.3 * (1.0 - inst)),
        "altocumulus": (0.7 + 0.6 * inst) * (0.5 + 0.5 * humidity),
        "cirrus": 1.0 + 0.3 * (1.0 - humidity),
    }


RAIN_TYPES = ("nimbostratus", "stratocumulus", "altostratus", "stratus", "cumulonimbus")
_REF = _raw_factors(REF_TEMP_C, REF_INSTABILITY, shear(REF_SPEED), REF_HUMIDITY)


def _smooth01(x, lo, hi):
    if x <= lo:
        return 0.0
    if x >= hi:
        return 1.0
    u = (x - lo) / (hi - lo)
    return u * u * (3.0 - 2.0 * u)


# -- the day cycle of convection (hours are local solar time) ----------------------------------------------
CU_RISE = (7.5, 11.5)             # fair-weather cumulus: builds from mid-morning ...
CU_FALL = (16.5, 21.0)            # ... and fades from the late afternoon to well after sunset
CB_RISE = (9.0, 12.5)             # deep convection: first towers late morning, births full from 12.5 h ...
CB_FALL = (16.0, 20.0)            # ... to 16 h, none after 20 h (a night storm is the `floor`, see below); the storms
                                  # they start are alive most between 15 and 19 h and gone by about 22 h
CB_SUPPORT_RISE = (6.0, 10.0)     # a storm that is already up decays only while the sun's heat is missing: from sunrise it
                                  # is fed again (a tower born at 11 h must live through its afternoon; at 8 h it is a weak one)
CU_NIGHT_FLOOR = 0.12             # a few cumulus-like clouds survive the night (leftover, dawn)
NIGHT_STORM_BASE = 0.04           # chance of a night storm relative to the afternoon, an ordinary temperate sky
NIGHT_STORM_MAX = 0.45
NIGHT_CLEAR_K = 0.35              # the cloud target shrinks by up to this share at night (x the convective share of the mix)
CB_AGE_K = 9.0                   # a convective cloud ages 1 + K x faster in the dead of night than in the afternoon
                                  # (measured: storms born 12-16 h peak alive about 18 h and are gone by 22 h; K 3 left a third of them at 22-05 h)
SC_AGE_K = 2.5                    # a supercell is organised and long-lived: it decays after dark, but 3.4 x faster at most (a tornado
                                  # needs its mesocyclone and wall cloud to spin up in real seconds, see Simulation._update_tornadoes)
CU_AGE_K = 1.0                    # ... a cumulus 1 + K faster
STRATUS_DAWN_GAIN = 1.2           # extra weight of low stratus towards dawn
SHEET_NIGHT_GAIN = 0.35           # and of stratocumulus all night


def _hour(tod):
    return (tod % 1.0) * 24.0


def _rise_fall(h, rise, fall):
    """0 below rise[0], smoothstep up to 1 at rise[1], 1 until fall[0], smoothstep down to 0 at fall[1]."""
    if h <= rise[0] or h >= fall[1]:
        return 0.0
    if h < rise[1]:
        return _smooth01(h, rise[0], rise[1])
    if h <= fall[0]:
        return 1.0
    return 1.0 - _smooth01(h, fall[0], fall[1])


def cumulus_day(tod):
    """How much the sun is heating the ground for fair-weather cumulus (0 at night, 1 from late morning to late afternoon)."""
    return _rise_fall(_hour(tod), CU_RISE, CU_FALL)


def cb_day(tod):
    """Deep convection's share of its afternoon peak (1 from 12.5 to 16 h, 0 from 20 to 9 h)."""
    return _rise_fall(_hour(tod), CB_RISE, CB_FALL)


def cb_support(tod):
    """How well the sun's heat feeds a storm that exists (0 in the dark, 1 from 10 to 16 h, 0 again by 20 h). Births use
    `cb_day`; ageing uses this (it rises earlier, so a storm that forms at 11 h is not aged like a night storm)."""
    return _rise_fall(_hour(tod), CB_SUPPORT_RISE, CB_FALL)


def night_depth(tod):
    """0 by day, 1 in the deep night (the complement of the fair-weather cumulus window)."""
    return 1.0 - cumulus_day(tod)


def dawn_stratus(tod):
    """Low stratus and fog: 0 by day, rising from 22 h, full 03-07 h, gone by 10 h."""
    h = _hour(tod)
    if h < 12.0:
        h += 24.0
    return _rise_fall(h, (22.0, 27.0), (31.0, 34.0))


def gate(day, floor):
    """`day` (0..1) lifted to `floor` at its lowest."""
    return floor + (1.0 - floor) * day


def night_storm_floor(mean_temp_c, humidity, sc_share=0.0, af=1.0):
    """How likely a storm is at night relative to the afternoon (0.04 .. 0.45): a small number almost everywhere,
    more over warm moist tropical / maritime air (night rain is normal there: 21 .. 27 C, humidity 0.65 .. 0.85,
    up to +0.20) and over the Great Plains (a preset whose cloud mix is a supercell region, +0.14, the nocturnal storms
    fed by the low-level jet; only in a warm season). A desert preset's dry air scales it by af^2."""
    warm_wet = _smooth01(mean_temp_c, 21.0, 27.0) * _smooth01(humidity, 0.65, 0.85)
    plains = _smooth01(sc_share, 0.0, 0.03) * _smooth01(mean_temp_c, 8.0, 17.0)
    return min(NIGHT_STORM_MAX, NIGHT_STORM_BASE + 0.20 * warm_wet + 0.14 * plains) * af * af


def cb_age_rate(tod, floor, k=None):
    """How much faster than the clock a convective cloud ages at this hour (1.0 at the afternoon peak, 1 + CB_AGE_K at night):
    without the sun's heat the updraft is not fed and the storm decays; its life cycle (precipitating, dissipating) still runs."""
    return 1.0 + (CB_AGE_K if k is None else k) * (1.0 - gate(cb_support(tod), floor))


def cu_age_rate(tod):
    return 1.0 + CU_AGE_K * (1.0 - gate(cumulus_day(tod), CU_NIGHT_FLOOR))


def supercell_raw(inst, shear_v, region_share=0.0):
    """Raw weight of a supercell: needs instability AND shear (a product of two smoothsteps: instability
    0.30 -> 0.75, shear 0.03 -> 0.40, so a calm or a stable sky gives exactly 0). `region_share` is the
    supercell's share of the preset's own cloud mix (0 for a plain world); it multiplies the weight by
    1 + SUPERCELL_REGION_GAIN * share. Examples (share 0): inst 0.57 / shear 0.44 -> raw 0.08 (about 7 % of
    new clouds); inst 0.48 / shear 0.16 (the Oklahoma preset in May at 16 h, share 0.028) -> 0.06."""
    s = _smooth01(inst, 0.30, 0.75) ** 1.3 * _smooth01(shear_v, 0.03, 0.40)
    return SUPERCELL_BASE * s * (1.0 + SUPERCELL_REGION_GAIN * region_share)


def type_weights(base_mix: dict, temp_c, inst, shear_v, humidity, af=1.0, tod=None, night_storm=NIGHT_STORM_BASE) -> dict:
    """Chance of each of the nine types for the next spawned cloud (sums to 1, never negative).
    `tod` (time of day, optional): cumulus follow the sun's heating, cumulonimbus and supercells deep convection (both
    lifted to `night_storm` at night), the sheets gain towards dawn; None leaves every weight as before."""
    total0 = sum(max(0.0, base_mix.get(k, 0.0)) for k in TYPES9 if k != "supercell") or 1.0
    sc_mix = max(0.0, base_mix.get("supercell", 0.0))
    f = _raw_factors(temp_c, inst, shear_v, humidity)
    raw = {}
    for k in TYPES9:
        if k == "supercell":
            raw[k] = supercell_raw(inst, shear_v, sc_mix / (total0 + sc_mix))
        else:
            raw[k] = max(0.0, base_mix.get(k, 0.0)) / total0 * f[k] / _REF[k]
    for k in RAIN_TYPES:                    # clouds that rain need moist air (x1.0 for humidity >= 0.30)
        raw[k] *= af ** 5
    if tod is not None:
        cb_g = gate(cb_day(tod), night_storm)
        night = night_depth(tod)
        raw["cumulus"] *= gate(cumulus_day(tod), CU_NIGHT_FLOOR)
        raw["cumulonimbus"] *= cb_g
        raw["supercell"] *= cb_g
        raw["stratus"] *= 1.0 + STRATUS_DAWN_GAIN * dawn_stratus(tod) + 0.15 * night
        raw["stratocumulus"] *= 1.0 + SHEET_NIGHT_GAIN * night
    s = sum(raw.values())
    if s <= 0.0:
        raw = {k: (1.0 if k == "cumulus" else 0.0) for k in TYPES9}
        s = 1.0
    return {k: v / s for k, v in raw.items()}


# -- the aerosol / humidity effect on a single cloud (used by clouds.py) ----------------------------
def lifetime_factor(aerosol, humidity):
    """More nuclei -> longer-lived clouds; a little more in humid air."""
    return (1.0 + 0.5 * (aerosol - REF_AEROSOL)) * (1.0 + 0.2 * (humidity - REF_HUMIDITY))


def capacity_factor(aerosol):
    """Smaller droplets from more nuclei: a cloud holds back (rains less)."""
    return 1.0 - 0.25 * (aerosol - REF_AEROSOL)


def fair_probability(base, aerosol, humidity, af=1.0):
    """Chance of being born fair: nuclei make fair-weather clouds more likely, humid air less."""
    p = clamp(base + 0.25 * (aerosol - REF_AEROSOL) - 0.35 * (humidity - REF_HUMIDITY), 0.45, 0.95)
    # a desert preset's bone-dry air (af < 1): almost every cloud is a fair-weather one
    return p + (0.995 - p) * min(1.0, (1.0 - af) * 2.5)


def develop_scale(aerosol, humidity, af=1.0):
    """Multiplier on the develop hazard of a fair cloud."""
    return clamp((0.6 + 0.8 * humidity) / (0.6 + 0.8 * REF_HUMIDITY) * (1.0 - 0.35 * (aerosol - REF_AEROSOL)) * af * af,
                 0.25 if af >= 1.0 else 0.08, 2.5)


def rain_onset_lf(aerosol, humidity):
    """life_frac at which a cloud may start to rain (v2.2: 0.5). Later with more nuclei, earlier in humid air."""
    return clamp(0.5 + 0.2 * (aerosol - REF_AEROSOL) - 0.1 * (humidity - REF_HUMIDITY), 0.35, 0.7)


def drain_scale(aerosol, humidity):
    """Multiplier on how fast a raining cloud empties: lighter with more nuclei, heavier in humid air."""
    return (1.0 - 0.25 * (aerosol - REF_AEROSOL)) * (0.8 + 0.4 * humidity) / (0.8 + 0.4 * REF_HUMIDITY)


# -- state -------------------------------------------------------------------------------------
class Atmosphere:
    """The three gases. Values change instantly with a `gas` command (the greenhouse temperature
    offset that follows from CO2 glides, see World.set_gas_target)."""

    def __init__(self, humidity=REF_HUMIDITY, co2_ppm=CO2_REF_PPM, aerosol=REF_AEROSOL, arid=False):
        self.arid = bool(arid)            # a desert preset: dry air clears the sky (see aridity_factor)
        self.night_storm = NIGHT_STORM_BASE   # set by snapshot(); read by clouds.update_cloud (not saved: recomputed every tick)
        self.humidity = clamp(float(humidity), 0.0, 1.0)
        self.co2_ppm = clamp(float(co2_ppm), *CO2_RANGE)
        self.aerosol = clamp(float(aerosol), 0.0, 1.0)

    def set_gas(self, name, delta=None, value=None):
        """Apply a `gas` command. Returns the new value, or None when the command is invalid."""
        if name not in GAS_NAMES:
            return None
        src = value if value is not None else delta
        if isinstance(src, bool) or not isinstance(src, (int, float)):
            return None
        try:                        # (an integer too large for a float is not a gas amount either)
            src = float(src)
        except OverflowError:
            return None
        if not math.isfinite(src):
            return None
        cur = {"humidity": self.humidity, "co2": self.co2_ppm, "aerosol": self.aerosol}[name]
        new = float(src) if value is not None else cur + float(src)
        if name == "co2":
            self.co2_ppm = clamp(new, *CO2_RANGE)
            return self.co2_ppm
        new = clamp(new, 0.0, 1.0)
        if name == "humidity":
            self.humidity = new
        else:
            self.aerosol = new
        return new

    def af(self) -> float:
        """aridity_factor of the current humidity for a desert preset, else exactly 1.0."""
        return aridity_factor(self.humidity) if self.arid else 1.0

    def snapshot(self, parts: dict, speed: float, width_units: float, base_mix: dict, tod=None) -> dict:
        """Everything derived from the current sky. `parts` is World.temp_parts(); `tod` the time of day (None = no day
        cycle of convection: the pre-Phase-O numbers)."""
        temp_c = parts["surface_temp_c"]
        inst = instability(heating_c(parts), self.humidity)
        sh = shear(speed)
        af = self.af()
        t1280 = cloud_target_per_1280(temp_c, inst, speed, self.humidity, self.aerosol, af)
        spm1280 = spawn_per_min_per_1280(t1280, inst, speed, af)
        t1280_open = t1280                   # (the cloud target without the night's clear spells: see Simulation._initial_clouds)
        if tod is None:
            weights = type_weights(base_mix, temp_c, inst, sh, self.humidity, af)
        else:
            mean_c = parts["base_c"] + parts["season_c"] + parts["gas_c"] + parts["user_c"]
            sc = max(0.0, base_mix.get("supercell", 0.0))
            tot = sum(max(0.0, base_mix.get(k, 0.0)) for k in TYPES9 if k != "supercell") + sc
            self.night_storm = night_storm_floor(mean_c, self.humidity, sc / tot if tot > 0.0 else 0.0, af)
            day_w = type_weights(base_mix, temp_c, inst, sh, self.humidity, af)
            weights = type_weights(base_mix, temp_c, inst, sh, self.humidity, af, tod, self.night_storm)
            # clear spells: where the sky is mostly convective, the night has fewer clouds in it altogether
            conv = day_w["cumulus"] + day_w["cumulonimbus"] + day_w["supercell"]
            k = 1.0 - NIGHT_CLEAR_K * night_depth(tod) * conv
            t1280 *= k
            spm1280 *= k
        return {"instability": inst, "shear": sh,
                "target_f": t1280 * width_units, "spawn_per_min_f": spm1280 * width_units,
                "weights_f": weights, "target_open_f": t1280_open * width_units}

    def to_protocol(self, snap: dict) -> dict:
        return {"humidity": round(self.humidity, 3), "co2_ppm": int(round(self.co2_ppm)),
                "aerosol": round(self.aerosol, 3),
                "instability": round(snap["instability"], 3), "shear": round(snap["shear"], 3),
                "spawn_per_min": round(snap["spawn_per_min_f"], 2),
                "cloud_target": int(round(snap["target_f"])),
                "type_weights": {k: round(v, 4) for k, v in snap["weights_f"].items()}}

    def to_state(self) -> dict:
        return {"humidity": self.humidity, "co2_ppm": self.co2_ppm, "aerosol": self.aerosol, "arid": self.arid}

    @classmethod
    def from_state(cls, s: dict) -> "Atmosphere":
        return cls(s["humidity"], s["co2_ppm"], s["aerosol"], s.get("arid", False))
