"""Showcase mode (PROTOCOL.md v2.2 "Cloud formations"): one cloud at a time, whole life in view.

`ShowcaseSimulation` is a `Simulation` with a different cloud policy, not a second cloud engine:
the world is a flat, settlement-free stage with a light steady wind, the auto-spawner is replaced
by a cycle manager that keeps exactly one cloud of the chosen type alive (forming -> growing ->
mature -> precipitating -> dissipating -> gone), waits a few seconds, then starts a new unique cloud.

* A type that can precipitate is born `fair = False` (it never stays a fair-weather cloud) and a
  cumulus is barred from towering into a cumulonimbus (`from_type = ""`, the pre-v2.1 marker for
  "never morphs"), so the forced type holds for the whole life. `cirrus` and `altocumulus` stay fair.
* Each tick, before the cloud is advanced, its own drift speed is set so that the net motion
  (own drift + 0.6 x local wind, see clouds.update_cloud) is a slow, steady creep downwind sized to
  cross about a quarter of the stage in a nominal lifetime; it starts that far upwind of the centre,
  so it ends up the same distance downwind and never leaves the world.
* spawn / reseed / save are ignored (save answers `saved` with ok = false).

The lessons are plain-language real meteorology, checked against the Wikipedia pages for Cloud,
Cumulus cloud, Thunderstorm, Nimbostratus cloud and Stratus cloud and NOAA's JetStream cloud
classification (altitudes follow its temperate-latitude ranges). The supercell lesson and its lifetime
tour (v2.3) are checked against the Wikipedia pages for Supercell, Wall cloud, Tornado and Enhanced Fujita scale
(read 2026-10-09: structure and ingredients of a supercell, the wall cloud and rear flank downdraft, funnel cloud
versus tornado, the rope stage, the EF 3-second-gust ranges).

The supercell tab (v2.3) runs an ordered lifetime tour (`TOURS`, `frame.showcase.tour_step`) and every cycle
makes exactly one tornado: ShowcaseSimulation runs a programme around the unchanged engine (see
_govern_supercell / _tour_tick): the storm's life fraction follows a fixed schedule, the wall cloud is
held back until the tour reaches it, and the governor starts the one tornado itself (`auto_tornado` is off).
Each tour step starts when the cloud's own state reaches it (tower condensed, rotation >= 0.6 with the anvil already
spreading (>= 0.3) and the overshooting top rising, wall cloud
>= 0.25, a funnel exists, it touches down, it ropes, it is gone) and not before the previous step has been on
stage for TOUR_MIN_DWELL_S, so every step can be read.
"""
from __future__ import annotations

import math
import random

from clouds import FAIR_PARAMS, TYPE_NAMES
from simulation import Simulation, r2
from windfield import build_streamlines

DEFAULT_W, DEFAULT_H = 1920, 1080
PAUSE_S = 4.0                  # quiet stage between two clouds
INITIAL_PAUSE_S = 0.5
STAGE_SURFACE_TEMP_C = 12.0
STAGE_MONTH = 5.8              # the stage holds a fixed late-spring afternoon (v2.3): no day or season drift
STAGE_HOUR = 15.0
STAGE_WIND_U = 3.0
STAGE_GUST_SCALE = 0.3         # gusts are 30 % of the sandbox's: light and steady
DRIFT_SPAN_FRAC = 0.26         # fraction of the stage width crossed in a nominal lifetime
DRIFT_MAX = 6.0                # px/s

# -- the supercell programme (see _govern_supercell) ----------------------------------------------------
SC_LIFE0 = 110.0               # s: the supercell's lifetime while its life fraction runs 0 -> SC_LF_HOLD
SC_LF_HOLD = 0.48              # life fraction held while the tornado lives (rain starts at 0.36, the engine
                               # would end the storm at 0.82)
SC_DECAY_LF_RATE = 0.12        # life fraction per second once the tornado is gone: the storm decays
SC_ROT_PEAK = 0.92
SC_WALL_PEAK = 0.95
SC_MATURE_DWELL_S = 11.0       # the wall cloud may start to lower this long after the 'mature' step begins
SC_WALL_DWELL_S = 10.0         # the tornado starts when the wall cloud is down and the step has run this long
SC_TORNADO_EF = 3              # the stage's tornado is a clear EF3 (60.8-74.2 m/s)
SC_TORNADO_MATURE_S = 24.0
TOUR_MIN_DWELL_S = 8.5         # no tour step is left before it has been on stage this long
# Typical length (s) of each tour step, measured over many cycles: only used to tell the viewer how long the
# whole storm will last (see _stage_whole_life), never to drive the storm.
SC_STEP_NOMINAL_S = (20.0, 12.0, 13.0, 10.0, 15.0, 28.0, 15.5, 35.0)

# -- the rain programme -------------------------------------------------------------------
# In the sandbox a cloud's rain is whatever its reservoirs do: they drain at ~27 %/s from the moment it
# starts precipitating and it stops when they are 12 % full (or at 80 % of its lifetime), so every
# cloud rains for ~10 s (or, for the small light types, until 80 % of its life). On the showcase stage
# the rain is the point of the lesson, so ShowcaseSimulation runs a per-type programme around the
# unchanged cloud engine (clouds.update_cloud is not touched, sandbox clouds never see any of this):
#   * growing / mature: the reservoirs are capped at MATURE_FILL, so the rain builds up from a light
#     start (and a mature cloud is not already at its heaviest);
#   * precipitating: the reservoir fill follows a smooth profile (steady plateau, or a burst with a
#     clear peak) for a planned length, so precip_rate = 27 % x fill x capacity follows it too;
#   * the cloud's life_frac is held at HOLD_LF until the programme ends (the engine ends the rain at
#     0.8), and the profile finishes below the engine's 12 % exit level so the stage ends by itself.
# life = the spawned lifetime is clamped into this range (s); rain_share = share of the cloud's
# whole life (spawn to gone) spent precipitating; min_rain = floor for the rain length in seconds.
MATURE_FILL = 0.20
END_FILL = 0.08                # reservoir fill the profile ends on (< the engine's 0.12 exit level)
HOLD_LF = 0.77                 # life_frac the cloud is held at while the programme runs (> 0.75: no refill)
RAIN_ENTRY_LF = 0.5            # the engine starts precipitating at this life_frac
MIN_RAIN_S = 32.0
RAIN_BLEND = 0.10              # fraction of the programme over which the entry fill blends into the profile
DISSIPATE_TAIL = 6.6 / 4.7     # form spring: seconds of fade per unit of max(8, 0.18 x lifetime)
FADE_SEEN = 5.1 / 4.7          # the same fade as it is measured on stage (the cloud is gone a little sooner than
                               # DISSIPATE_TAIL plans): only used for the whole life the frame reports

RAIN_PLAN = {
    "cumulus":       dict(life=(60.0, 75.0),  rain_share=0.37, shape="burst",   level=0.95, at=0.35),
    "cumulonimbus":  dict(life=(70.0, 90.0),  rain_share=0.38, shape="burst",   level=1.00, at=0.30),
    "nimbostratus":  dict(life=(80.0, 100.0), rain_share=0.45, shape="plateau", level=0.70, wave=0.02),
    "stratocumulus": dict(life=(60.0, 75.0),  rain_share=0.37, shape="plateau", level=0.75, wave=0.04),
    "altostratus":   dict(life=(65.0, 80.0),  rain_share=0.38, shape="plateau", level=0.62, wave=0.10),
    "stratus":       dict(life=(60.0, 75.0),  rain_share=0.37, shape="plateau", level=0.78, wave=0.03),
}


def _ease(x: float) -> float:
    x = 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)
    return x * x * x * (x * (6.0 * x - 15.0) + 10.0)       # smootherstep: zero slope at both ends


def rain_fill(plan: dict, tau: float) -> float:
    """Target reservoir fill (0..1) at fraction `tau` of the rain programme; starts at MATURE_FILL,
    ends at END_FILL and is smooth (continuous slope) in between."""
    if plan["shape"] == "burst":
        at = plan["at"]
        if tau < at:
            return MATURE_FILL + (plan["level"] - MATURE_FILL) * _ease(tau / at)
        u = (tau - at) / (1.0 - at)
        return END_FILL + (plan["level"] - END_FILL) * (0.5 + 0.5 * math.cos(math.pi * min(1.0, u)))
    up = _ease(tau / 0.18)
    down = _ease((tau - 0.78) / 0.22)
    f = MATURE_FILL + (plan["level"] - MATURE_FILL) * up
    f += plan["wave"] * math.sin(2.0 * math.pi * 2.0 * tau) * up * (1.0 - down)
    return f * (1.0 - down) + END_FILL * down


def plan_rain_length(plan: dict, start_age: float, life0: float) -> tuple[float, float]:
    """(rain length, expected whole life) in seconds for rain that starts at cloud age `start_age`
    when the spawned lifetime is `life0`: the length at which the rain is plan['rain_share'] of the
    whole life, counting the fade-out after it (which depends on the lifetime the cloud is held at)."""
    share = plan["rain_share"]

    def whole(rain: float) -> float:
        end = start_age + rain
        held_life = max(life0, end / HOLD_LF)
        return end + DISSIPATE_TAIL * max(8.0, 0.18 * held_life)

    rain = MIN_RAIN_S
    for _ in range(40):
        rain = max(MIN_RAIN_S, share * whole(rain))
    return rain, whole(rain)


def reported_whole(rain_end: float, planned_whole: float) -> float:
    """The whole life (s) the frame reports for a cloud whose rain ends at age `rain_end` and whose
    programme plans `planned_whole`: the same fade-out, at the length it is measured to take."""
    return rain_end + (planned_whole - rain_end) * (FADE_SEEN / DISSIPATE_TAIL)


LESSONS = {
    "cumulus": {
        "title": "Cumulus",
        "altitude": "Base at about 500-1,500 m in temperate regions, sometimes up to 2,400 m",
        "formation": "Sunlit ground warms the air above it, so a bubble of air (a thermal) rises and cools until its "
                     "relative humidity reaches 100 %, and its water vapour condenses into a cloud with a flat base.",
        "life": "Thermal after thermal feeds new cauliflower turrets on top, while older ones are mixed with the drier "
                "air around them. A single cumulus is short-lived, usually tens of minutes.",
        "precipitation": "Small fair-weather cumulus give little or no rain. If the air is moist and unstable the cloud "
                         "deepens (cumulus congestus) and can drop a shower, as this one does.",
        "wind": "Wind shear leans the turrets downwind, and the whole cloud drifts with the wind at its level.",
        "ending": "When the rising air that fed it stops, or the downdraft from its own rain cuts it off, the cloud "
                  "evaporates into the surrounding dry air.",
    },
    "cumulonimbus": {
        "title": "Cumulonimbus",
        "altitude": "Low base, often below 2,000 m, with a top that reaches the tropopause, commonly 10 km or more",
        "formation": "In very unstable air a cumulus keeps growing because the heat released by condensation "
                     "keeps the updraft warmer than its surroundings. Once the top freezes into ice crystals "
                     "the cloud is a cumulonimbus.",
        "life": "A thunderstorm cell passes through a developing, a mature and a dissipating stage, each lasting "
                "about 30 minutes on average. The mature stage has an updraft and a downdraft side by side.",
        "precipitation": "Heavy rain, hail or graupel and lightning, formed as droplets coalesce and freeze in the "
                         "strong updraft.",
        "wind": "At the tropopause the rising air spreads sideways into an anvil that the winds aloft blow downwind, "
                "and the cold downdraft fans out along the ground as a gust of wind.",
        "ending": "The downdraft grows until it cuts off the warm air feeding the updraft, and the storm dissipates "
                  "while its anvil thins out slowly.",
    },
    "nimbostratus": {
        "title": "Nimbostratus",
        "altitude": "Low to mid-level base, often under 2,000 m, with a thick body that reaches well above it",
        "formation": "Ahead of a warm or occluded front, warm moist air slides gently up over cooler air, and a thickening "
                     "altostratus sheet lowers into nimbostratus.",
        "life": "It is a deep, dark grey layer with a diffuse, ragged base that stays in place as long as "
                "the front keeps lifting air.",
        "precipitation": "Steady, continuous rain or snow over a wide area, lasting for hours or even days depending on "
                         "how quickly the front moves.",
        "wind": "The air rises slowly along the gently sloping front, so there are no strong updrafts and the "
                "precipitation falls almost straight down.",
        "ending": "After the front has passed, nimbostratus is usually replaced by stratus or stratocumulus "
                  "and then breaks up.",
    },
    "stratocumulus": {
        "title": "Stratocumulus",
        "altitude": "Low level, below about 2,000 m",
        "formation": "Limited convection under a stable layer such as an inversion lifts the air only a little, "
                     "so it spreads sideways into lumps or rolls instead of towering.",
        "life": "The cells rise, spread and sink back, so the sheet keeps its patchwork look and can last for "
                "hours, over cool oceans even for days.",
        "precipitation": "Usually none; at most a little light drizzle.",
        "wind": "The wind lines the lumps up into rolls or rows roughly along its direction, and the whole sheet "
                "drifts with the low-level wind.",
        "ending": "Warming that breaks the inversion, or drier air mixing in, thins the sheet into separate patches "
                  "that evaporate.",
    },
    "altostratus": {
        "title": "Altostratus",
        "altitude": "Mid level, about 2,000-7,000 m",
        "formation": "A broad layer of moist air rising slowly, often ahead of a warm front or around a low-pressure "
                     "area, cools until it condenses into a uniform sheet of mostly water droplets, with ice crystals "
                     "near the top.",
        "life": "The grey or bluish sheet thickens as the front approaches until the sun shows only as a dim disc, "
                "as if seen through frosted glass.",
        "precipitation": "Light and often intermittent rain or snow, depending on the temperature below it; much of it "
                         "can evaporate before it reaches the ground.",
        "wind": "The winds at mid-level carry the sheet along, usually from the side of the approaching low-pressure area.",
        "ending": "It either thickens and lowers into nimbostratus as the front arrives, or thins and breaks up "
                  "as the front moves away.",
    },
    "altocumulus": {
        "title": "Altocumulus",
        "altitude": "Mid level, about 2,000-7,000 m",
        "formation": "Gentle, limited convection or wave motion inside a moist mid-level layer breaks it into rows "
                     "or patches of cloudlets made of water droplets and some ice.",
        "life": "Small cells keep rising through the layer, so the pattern of lumps changes slowly while it lasts.",
        "precipitation": "None reaches the ground; at most trails of falling drops or ice (virga) evaporate "
                         "below the cloud.",
        "wind": "Wind shear at that level lines the cloudlets into bands, and strong wind over mountains "
                "can shape lens-like lenticular altocumulus.",
        "ending": "As the layer dries out or its air sinks the lumps shrink and evaporate; if they thicken and "
                  "merge they can be an early sign of an approaching front.",
    },
    "stratus": {
        "title": "Stratus",
        "altitude": "Low level, from the surface to about 2,000 m",
        "formation": "In stable air a layer of moist air near the ground cools, or a fog lifts off the ground, "
                     "until a flat, featureless grey sheet forms.",
        "life": "The uniform sheet with an even base can persist for days when the weather is calm "
                "under high pressure.",
        "precipitation": "Only very weak: drizzle, freezing drizzle, or snow grains.",
        "wind": "A light breeze lifts the moist air or slides it over the ground; a stronger wind stirs the "
                "layer and tends to break it up into stratocumulus.",
        "ending": "Warming and drying air burns it off from below, or it sinks to the surface and becomes fog.",
    },
    "cirrus": {
        "title": "Cirrus",
        "altitude": "High level, about 5,000-12,000 m in temperate latitudes",
        "formation": "In the cold upper troposphere water vapour turns directly into ice crystals, often along "
                     "the jet stream or at the leading edge of a front.",
        "life": "Ice crystals slowly settle out in wispy trails, and the changing wind speed with height shears "
                "the trails into hooks and streaks.",
        "precipitation": "None reaches the ground; the crystals sublimate in the dry air below.",
        "wind": "The jet stream at this level is the fastest wind in the troposphere, and it stretches the cloud "
                "into streaks along its direction.",
        "ending": "The crystals sublimate when the air dries or warms; where the ice cloud thickens "
                  "it can become the leading edge of an approaching warm front instead.",
    },
}


LESSONS["supercell"] = {
    "title": "Supercell",
    "altitude": "A low, rain-free base, usually under 2,000 m, under an updraft that can reach the tropopause "
                "(10 km or more) and overshoot it in a dome",
    "formation": "Warm, moist air under cooler air is unstable, and a capping layer lets that energy build. Strong wind "
                 "shear, with the wind changing speed and direction with height, sets the air spinning sideways, and "
                 "the updraft tilts that spin upright into a rotating column called a mesocyclone.",
    "life": "An ordinary thunderstorm cell is cut off by its own rain within an hour, but a supercell keeps its updraft "
            "and downdraft side by side and typically lasts 2-4 hours, sometimes over 7. A flanking line of smaller "
            "cumulus on its rear flank keeps feeding it warm air. Here a lifetime is compressed into minutes.",
    "precipitation": "Heavy rain and large hail, whose stones can reach about 5 cm, fall from the forward flank "
                     "downdraft ahead of the updraft. The base under the updraft itself stays rain-free.",
    "wind": "Cool air sinking behind the updraft, the rear flank downdraft, wraps around it and helps lower a wall "
            "cloud. Supercells are among the few clouds that spawn tornadoes, but 30 % or fewer of them do.",
    "ending": "The cold outflow of the storm spreads out and the downdrafts cut the updraft off from its warm, moist "
              "air. The rotation weakens, the updraft collapses and the anvil thins out and drifts away.",
}

# The ordered lifetime tour of the supercell tab: id, title, text (2-4 plain sentences each).
TOURS = {
    "supercell": [
        {"id": "towering", "title": "Towering",
         "text": "Warm, humid air rises through a capping layer in a strong, persistent updraft, and the cloud builds "
                 "into a tall tower on a flat base. Wind shear leans the tower downwind. At this stage it still "
                 "looks like any large thunderstorm."},
        {"id": "mesocyclone", "title": "Mesocyclone",
         "text": "Wind shear has rolled the air near the ground into horizontal tubes, and the updraft tilts them "
                 "upright, so the whole updraft begins to rotate. This deep, persistently rotating updraft, the "
                 "mesocyclone, is what makes a storm a supercell. A line of smaller cumulus on the rear flank "
                 "feeds it more warm air."},
        {"id": "mature", "title": "Mature supercell",
         "text": "The updraft overshoots the top of the troposphere in a dome above a long anvil that the high "
                 "winds blow downwind. Rain and hail fall from the forward flank, ahead of the updraft, while the "
                 "base under the updraft stays rain-free. Lightning is frequent."},
        {"id": "wall_cloud", "title": "Wall cloud",
         "text": "Cool air sinking behind the updraft, the rear flank downdraft, wraps around it. Under the rain-free "
                 "base a wall cloud lowers beneath the strongest part of the updraft, and a rotating wall cloud "
                 "marks a mesocyclone. A wall cloud typically comes 10 to 20 minutes before a tornado, if one forms."},
        {"id": "funnel", "title": "Funnel cloud",
         "text": "A funnel cloud, a rotating cone of condensation, descends from the wall cloud. While it hangs in "
                 "the air it is not yet a tornado on the ground, although damaging winds can already blow at the "
                 "surface under it. The rear flank downdraft dries the cloud beside it into a clear slot."},
        {"id": "tornado", "title": "Tornado",
         "text": "The funnel reaches the ground: a column of violently rotating air touching both ground and cloud. "
                 "Dust and debris thrown up around its base form a debris cloud. Damage is rated from EF0 to EF5 "
                 "from the 3-second gust (65-85 mph for EF0, over 200 mph for EF5); most tornadoes are tens to "
                 "hundreds of metres wide and travel a few kilometres."},
        {"id": "rope_out", "title": "Rope stage",
         "text": "The rear flank downdraft wraps around the tornado and cuts off its warm, moist inflow. The vortex "
                 "narrows into a thin, tilted rope and then lifts off the ground, and its winds can still be "
                 "damaging while it contracts."},
        {"id": "decay", "title": "Decay",
         "text": "With the tornado gone the rotation weakens, and the storm's cold outflow undercuts the updraft. "
                 "Cut off from warm air, the updraft collapses, the rain stops and the anvil thins out and drifts "
                 "away."},
    ],
}
TOUR_IDS = {k: [st["id"] for st in v] for k, v in TOURS.items()}


class ShowcaseSimulation(Simulation):
    mode = "showcase"
    merging = False               # one cloud at a time: nothing to merge with
    auto_tornado = False          # the supercell tab starts its one tornado itself (_tour_tick)

    def __init__(self, seed: int, w: int = DEFAULT_W, h: int = DEFAULT_H, fps: int = 30,
                 cloud_type: str = "cumulus"):
        if cloud_type not in TYPE_NAMES:
            raise ValueError(f"cloud type must be one of {', '.join(TYPE_NAMES)}")
        self.sc_type = cloud_type
        self.sc_cycle = 0
        self.sc_cloud_id = None
        self.sc_cloud = None
        self.sc_pause = INITIAL_PAUSE_S
        self.sc_drift = 0.0
        self.sc_rain = None          # the current cloud's rain programme state (see _govern_rain)
        self.sc_prog = None          # the supercell programme / tour state of the current cycle
        self.tour_log: list = []     # [(cycle, step id, t start, t end or None)] of the supercell tour so far
        super().__init__(seed, w, h, fps, "flat", "none")

    # -- the stage -------------------------------------------------------------------------
    def _initial_clouds(self, rng):
        pass          # the stage starts empty; the first cloud begins after INITIAL_PAUSE_S

    def _reset(self, seed: int, w: int, h: int):
        super()._reset(seed, w, h)
        self.world.hold_clock(STAGE_MONTH, STAGE_HOUR)
        self.world.surface_temp_c = STAGE_SURFACE_TEMP_C
        self._atm = None
        self.world.prevailing_u = STAGE_WIND_U
        self.world.prevailing_v = 0.0
        self.wind_model.gust_scale = STAGE_GUST_SCALE
        self.wind = self._build_wind()
        self.streamlines = build_streamlines(self.wind, self.world, self.terrain, self.world_rng,
                                             self.n_streamlines)
        self.objects.settle_sway(self.wind)

    # -- cycle manager ---------------------------------------------------------------------
    def _spawn_stage_cloud(self, events: list):
        self.sc_cycle += 1
        fair = False if self.sc_type in FAIR_PARAMS else None
        cloud = self._spawn_cloud_auto(x=self.world.w * 0.5, cloud_type=self.sc_type, fair=fair)
        cloud.from_type = ""          # never tower into another type
        life = cloud.lifetime         # the span the drift is sized for
        self.sc_rain = None
        self.sc_prog = None
        if self.sc_type == "supercell":
            cloud.lifetime = SC_LIFE0
            life = 190.0              # about the length of a whole tour
            self.sc_prog = {"idx": 0, "t0": self.world.t, "wall_t": None, "end_t": None, "tornado": False}
            self.tour_log.append([self.sc_cycle, TOUR_IDS["supercell"][0], round(self.world.t, 2), None])
        cfg = RAIN_PLAN.get(self.sc_type)
        if cfg is not None and not cloud.fair:
            lo, hi = cfg["life"]
            cloud.lifetime = min(hi, max(lo, cloud.lifetime))
            # the cloud's own rng is left alone; the small per-cloud variation comes from its seed
            jitter = random.Random(cloud.seed ^ 0x5CA1E).uniform(0.95, 1.08)
            plan = dict(cfg, rain_share=cfg["rain_share"] * jitter, life0=cloud.lifetime, t0=None)
            _rain, life = plan_rain_length(plan, RAIN_ENTRY_LF * cloud.lifetime, cloud.lifetime)
            plan["whole"] = reported_whole(RAIN_ENTRY_LF * cloud.lifetime + _rain, life)
            self.sc_rain = plan
        dirn = 1.0 if self.world.prevailing_u >= 0 else -1.0
        self.sc_drift = dirn * min(DRIFT_MAX, DRIFT_SPAN_FRAC * self.world.w / max(60.0, life))
        cloud.ax = self.world.w * 0.5 - self.sc_drift * life * 0.5
        cloud.refresh()
        self.sc_cloud = cloud
        self.sc_cloud_id = cloud.id
        events.append(f"t={self.world.t:.1f} showcase cycle {self.sc_cycle}: {cloud.name} starts forming")

    def _maybe_spawn_cloud(self, dt: float, events: list):
        if self.sc_cloud_id is not None:
            if not any(c.id == self.sc_cloud_id for c in self.clouds):
                events.append(f"t={self.world.t:.1f} showcase cycle {self.sc_cycle} finished")
                self.sc_cloud = None
                self.sc_cloud_id = None
                self.sc_rain = None
                self._close_tour(self.world.t)
                self.sc_prog = None
                self.sc_pause = PAUSE_S
            return
        self.sc_pause -= dt
        if self.sc_pause <= 0.0:
            self._spawn_stage_cloud(events)

    def _hold_steady_drift(self):
        """Make the cloud's net motion the planned slow creep, whatever the local gust is."""
        c = self.sc_cloud
        if c is None:
            return
        wu, wv = self.wind.sample(c.ax, c.ay + c.core[1])
        c.vx = self.sc_drift - wu * 0.6
        c.vy = -wv * 0.15

    def _govern_rain(self, dt: float):
        """Apply the rain programme (see RAIN_PLAN) to the stage cloud, just before its tick."""
        c, plan = self.sc_cloud, self.sc_rain
        if c is None or plan is None:
            return
        if c.stage == "growing" or c.stage == "mature":
            c.water = min(c.water, MATURE_FILL * c.water_capacity)
            c.ice = min(c.ice, MATURE_FILL * c.ice_capacity)
            return
        if c.stage != "precipitating":
            return
        if plan["t0"] is None:                       # the first tick of the rain: plan it from here
            plan["t0"] = c.age
            plan["w0"] = c.water / max(1e-9, c.water_capacity)
            plan["i0"] = c.ice / max(1e-9, c.ice_capacity)
            plan["length"], whole = plan_rain_length(plan, c.age, plan["life0"])
            plan["whole"] = reported_whole(c.age + plan["length"], whole)
        age = c.age + dt                              # the age update_cloud is about to reach
        tau = (age - plan["t0"]) / plan["length"]
        if tau < 1.0:
            c.lifetime = max(c.lifetime, age / HOLD_LF)      # the engine's end-of-rain (0.8) must not come first
        target = rain_fill(plan, tau) if tau < 1.0 else 0.0
        b = _ease(tau / RAIN_BLEND)                   # glide from the fill the rain started with
        c.water = ((1.0 - b) * plan["w0"] + b * target) * c.water_capacity
        c.ice = ((1.0 - b) * plan["i0"] + b * target) * c.ice_capacity

    # -- the supercell programme and its tour ----------------------------------------------------
    def _govern_supercell(self, dt: float):
        """Just before the supercell's tick: its peaks, its wall cloud (held back until the tour lets it
        lower) and its life fraction, which follows a schedule (lifetime = age / life fraction): it runs at
        age / SC_LIFE0 up to SC_LF_HOLD and is held there while the tornado lives, then rises quickly so that
        the engine lets the storm decay."""
        c, p = self.sc_cloud, self.sc_prog
        if c is None or p is None:
            return
        age = c.age + dt
        c.rot_peak = SC_ROT_PEAK
        c.wall_peak = SC_WALL_PEAK if (p["wall_t"] is not None and self.world.t >= p["wall_t"]) else 0.0
        if p["end_t"] is None:
            c.lifetime = max(SC_LIFE0, age / SC_LF_HOLD)
        else:
            lf = min(0.9, SC_LF_HOLD + (self.world.t + dt - p["end_t"]) * SC_DECAY_LF_RATE)
            c.lifetime = age / lf

    def _close_tour(self, t: float):
        if self.tour_log and self.tour_log[-1][3] is None:
            self.tour_log[-1][3] = round(t, 2)

    def _tour_step_id(self):
        p = self.sc_prog
        if self.sc_type != "supercell" or p is None or self.sc_cloud is None:
            return None
        return TOUR_IDS["supercell"][p["idx"]]

    def _update_tornadoes(self, dt: float, events: list):
        """The supercell tab: start the cycle's one tornado when the wall cloud is down, then walk the tour."""
        c, p = self.sc_cloud, self.sc_prog
        if self.sc_type == "supercell" and c is not None and p is not None:
            if (not p["tornado"] and p["idx"] == 3 and c.wall >= 0.85 and c.stage in ("mature", "precipitating")
                    and self.world.t - p["t0"] >= SC_WALL_DWELL_S):
                p["tornado"] = True
                self._start_tornado(c, events, forced=True, peak_ef=SC_TORNADO_EF, mature_s=SC_TORNADO_MATURE_S)
        super()._update_tornadoes(dt, events)
        if self.sc_type == "supercell" and c is not None and p is not None:
            self._tour_tick(c, p, events)

    def _tour_tick(self, c, p, events):
        """Advance the tour by at most one step per tick, never before the step has had TOUR_MIN_DWELL_S."""
        t = self.world.t
        idx = p["idx"]
        if idx >= len(TOUR_IDS["supercell"]) - 1 or t - p["t0"] < TOUR_MIN_DWELL_S:
            return
        tors = self.tornadoes
        if idx == 0:
            go = c.form >= 0.9
        elif idx == 1:
            go = c.rotation >= 0.6 and c.anvil >= 0.3 and c.overshoot >= 0.25     # the picture matches the text: anvil and dome are spreading
        elif idx == 2:
            go = c.wall >= 0.25
        elif idx == 3:
            go = bool(tors)
        elif idx == 4:
            go = any(x.stage in ("touchdown", "mature", "roping") for x in tors)
        elif idx == 5:
            go = any(x.stage == "roping" for x in tors)
        else:
            go = not tors
        if not go:
            return
        self._close_tour(t)
        p["idx"] = idx + 1
        p["t0"] = t
        name = TOUR_IDS["supercell"][idx + 1]
        self.tour_log.append([self.sc_cycle, name, round(t, 2), None])
        events.append(f"t={t:.1f} tour step: {name}")
        if name == "mature":
            p["wall_t"] = t + SC_MATURE_DWELL_S
        elif name == "decay":
            p["end_t"] = t

    def _stage_whole_life(self):
        """The whole life (s, spawn to gone) of the cloud on stage as its programme plans it, or None when the
        cloud runs on the engine's own clock (cirrus, altocumulus).

        The programmes hold the engine's life fraction still (HOLD_LF while it rains, SC_LF_HOLD while the
        tornado lives) by stretching `cloud.lifetime`, so the engine's own `lifetime` / `life_frac` stand
        still for up to a minute and then jump: useless as "how far through its life is this cloud". The
        frame therefore reports the planned whole life and age / that (see _build_frame); the engine's
        numbers are untouched and keep driving the stages."""
        c = self.sc_cloud
        if c is None:
            return None
        if self.sc_rain is not None:
            return max(self.sc_rain["whole"], c.age)
        p = self.sc_prog
        if p is not None:
            idx = p["idx"]
            # the steps so far as they really were, this step (at least its typical length), the rest as typical
            return ((p["t0"] - c.born_t) + max(SC_STEP_NOMINAL_S[idx], self.world.t - p["t0"])
                    + sum(SC_STEP_NOMINAL_S[idx + 1:]))
        return None

    def step(self, dt_real: float, extra_events=None) -> dict:
        if not self.paused:
            self._hold_steady_drift()
            self._govern_rain(dt_real * self.speed)
            self._govern_supercell(dt_real * self.speed)
        return super().step(dt_real, extra_events)

    # -- protocol --------------------------------------------------------------------------
    def hello_message(self) -> dict:
        msg = super().hello_message()
        msg["showcase"] = {"type": self.sc_type, "types": list(TYPE_NAMES),
                           "lessons": {k: dict(v) for k, v in LESSONS.items()},
                           "tours": {k: [dict(st) for st in v] for k, v in TOURS.items()}}
        return msg

    def _build_frame(self, events, deaths, ice_alive) -> dict:
        frame = super()._build_frame(events, deaths, ice_alive)
        frame["showcase"] = {"type": self.sc_type, "cycle": self.sc_cycle, "cloud_id": self.sc_cloud_id}
        whole = self._stage_whole_life()
        if whole:
            for d in frame["clouds"]:
                if d["id"] == self.sc_cloud_id:
                    d["lifetime"] = r2(whole)
                    d["life_frac"] = r2(min(1.0, self.sc_cloud.age / whole))
        if self.sc_type in TOURS:
            frame["showcase"]["tour_step"] = self._tour_step_id()
        return frame

    def handle_command(self, cmd: dict, events: list):
        c = cmd.get("cmd")
        if c == "showcase":
            self._cmd_showcase(cmd, events)
        elif c in ("spawn", "reseed", "tornado"):
            return          # the stage runs one cloud at a time, and the supercell tab its own one tornado
        else:
            super().handle_command(cmd, events)

    def _cmd_save(self, cmd: dict):
        path = cmd.get("path")
        self.outbox.append({"type": "saved", "ok": False, "path": path if isinstance(path, str) else "",
                            "t": r2(self.world.t), "frame": self.frame,
                            "error": "saving is not available in showcase mode"})

    def _cmd_showcase(self, cmd: dict, events: list):
        new_type = self.sc_type
        if "type" in cmd:
            if cmd["type"] not in TYPE_NAMES:
                return
            new_type = cmd["type"]
        elif not cmd.get("restart"):
            return
        # drop the current cloud at once: no `deaths` entry, no dissipation
        self.clouds = []
        self.sc_cloud = None
        self.sc_cloud_id = None
        self.sc_rain = None
        self._close_tour(self.world.t)
        self.sc_prog = None
        self.tornadoes = []
        self.tor_cool = {}
        self.objects.storm_sway = {}
        self.particles.items = []
        self.particles.ttl = []
        self.particles.debris = []
        self.particles.debris_ttl = []
        self._lightning = []
        self.sc_type = new_type
        self.sc_cycle = 0
        self.sc_pause = INITIAL_PAUSE_S
        events.append(f"t={self.world.t:.1f} showcase: {new_type}")

