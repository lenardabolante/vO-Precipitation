"""Hurricane formation model (protocol v2.2, ``--mode hurricane``).

Python owns the model and the facts; the Java viewer only draws.  ``HurricaneSim`` has the same
driver interface as ``simulation.Simulation`` (``hello_message`` / ``step`` / ``handle_command`` /
``drain_outbox`` plus ``running``, ``frame``, ``seed``, ``fps``) and emits one ``hurricane`` frame
per tick.  PROTOCOL.md "v2.2 additions -> Hurricane mode" is the wire contract.

Model in one paragraph
----------------------
A *playback position* (stage index + fraction through the stage) says where the storm is in its
life.  It advances by itself in ``auto`` mode (the whole life takes ~3.5 min at speed 1, then loops)
or sits at the start of a stage chosen with the ``stage`` command.  The position defines *targets*
(maximum wind, central pressure, radius of maximum wind, eye size, organisation, ...); the actual
state follows the targets through two cascaded first-order lags per quantity, so nothing ever
snaps: a jump to another stage glides over about five seconds.  From the smoothed state everything
else is derived:

* wind: Holland (1980) gradient-wind shape outside the radius of maximum wind (RMW) and a
  power-law (solid-body when there is no eye) rise inside it, so the eye is calm;
* pressure: Holland's ``p(r) = pc + dp * exp(-(Rm/r)^B)`` with ``B`` chosen so that the
  cyclostrophic peak ``vmax^2 = B*dp/(rho*e)`` matches the current wind and pressure drop;
* rain: eyewall ring + spiral rainbands (azimuthal-mean coverage of the very log spirals sent in
  ``bands``) + a weak background; the eye is dry when the eye is clear;
* section: an analytic secondary circulation (surface inflow, sloping eyewall updraft, outflow
  aloft, sinking warm eye, rainband convection, eyewall/anvil/band cloud) scaled by the state.

Stage figures are the NHC Saffir-Simpson wind ranges (1-minute sustained, knots) and the original
1970s pressure / surge ranges; see ``SOURCES``.  Pure standard library, deterministic per seed.
"""
from __future__ import annotations

import math
import random

# ---------------------------------------------------------------------------------------------
# geometry / constants
# ---------------------------------------------------------------------------------------------
DOMAIN_RADIUS_KM = 600.0
TOP_KM = 18.0
COLS, ROWS = 48, 16
DR_KM = DOMAIN_RADIUS_KM / COLS          # 12.5
DZ_KM = TOP_KM / ROWS                    # 1.125
PROFILE_STEP_KM = 10.0
PROFILE_R = [i * PROFILE_STEP_KM for i in range(int(DOMAIN_RADIUS_KM / PROFILE_STEP_KM) + 1)]

_RCEN = [(i + 0.5) * DR_KM for i in range(COLS)]
_E250 = [1.0 - math.exp(-r / 250.0) for r in _RCEN]
_E300 = [math.exp(-r / 300.0) for r in _RCEN]
_E450 = [math.exp(-r / 450.0) for r in _RCEN]

KT_TO_MS = 0.514444
RHO_AIR = 1.15            # kg/m^3, near-surface tropical air
P_ENV_HPA = 1012.0        # environmental sea-level pressure the vortex fills back to
GALE_MS = 34 * KT_TO_MS   # 17.5 m/s, the 34-kt wind radius (r34)
TAU_S = 1.05              # time constant of each of the two cascaded lags (~5 s to settle)
N_BANDS = 6
SECTION_MAX_HZ = 6.0

STAGE_IDS = ["disturbance", "depression", "storm", "cat1", "cat2", "cat3", "cat4", "cat5",
             "landfall", "dissipation"]
LESSON_VIEWS = ("outside", "inside", "both")
LESSON_FOCUS = ("sst", "coriolis", "convection", "inflow", "eye", "eyewall", "rainbands",
                "outflow", "warm_core", "surge", "decay", "none")

SOURCES = [
    "https://www.nhc.noaa.gov/aboutsshws.php",
    "https://www.nhc.noaa.gov/aboutgloss.shtml",
    "https://www.nhc.noaa.gov/surge/",
    "https://www.aoml.noaa.gov/hrd-faq/",
    "https://www.noaa.gov/jetstream/tropical/tropical-cyclone-introduction",
    "https://en.wikipedia.org/wiki/Saffir%E2%80%93Simpson_scale",
    "https://en.wikipedia.org/wiki/Tropical_cyclone",
    "https://en.wikipedia.org/wiki/Eye_(cyclone)",
    "https://www.en.meteo.physik.uni-muenchen.de/~roger/Tropics/TCs_CharacteristicsStructure.html",
    "https://faculty.fiu.edu/~willough/PUBS/HEW&RAHN_04.pdf",
]

# ---------------------------------------------------------------------------------------------
# stage definitions
# ---------------------------------------------------------------------------------------------
# Each stage: id, name, wind_kt range quoted on the wire, typical central pressure, category,
# summary, duration (seconds of auto play at speed 1).
_STAGE_META = [
    ("disturbance", "Tropical disturbance", [0, 25], 1010, -2, 22.0,
     "A cluster of showers and thunderstorms over warm tropical ocean, generally 100 to 300 "
     "nautical miles (about 185 to 555 km) across. NHC calls it a tropical disturbance while its "
     "winds are below tropical-depression strength. Air is rising in scattered towers, but nothing "
     "is organised yet: no closed circulation to speak of, no eye, no spiral bands. Many "
     "disturbances fade away; the ones that keep warm water, moist air and low wind shear can "
     "start to organise."),
    ("depression", "Tropical depression", [26, 33], 1005, -1, 16.0,
     "When the thunderstorms organise around a low-pressure centre with a closed circulation and "
     "sustained winds of 33 kt (38 mph) or less, NHC calls it a tropical depression. Heat released "
     "as vapour condenses warms the air column, so the surface pressure starts to fall. In the "
     "northern hemisphere the surface winds turn counter-clockwise around the centre, but there is "
     "still no eye."),
    ("storm", "Tropical storm", [34, 63], 997, 0, 20.0,
     "At 34 kt (39 mph) sustained wind the system becomes a tropical storm and is given a name; the "
     "range runs up to 63 kt (73 mph). Rainbands curl into spirals around the centre and the cloud "
     "pattern becomes rounder. Near the top of the range a small, ragged eye may begin to appear. "
     "Pressure keeps falling as the heat engine strengthens."),
    ("cat1", "Category 1 hurricane", [64, 82], 985, 1, 18.0,
     "Sustained winds of 64 to 82 kt (74 to 95 mph, 119 to 153 km/h); NHC: 'Very dangerous winds "
     "will produce some damage.' A ring of tall thunderstorms, the eyewall, forms around a clearing "
     "eye. On the original 1970s scale this category went with central pressure of about 980 hPa "
     "or higher and a storm surge of roughly 4 to 5 ft (1.2 to 1.5 m); NHC dropped the pressure "
     "and surge columns in 2009 so the scale rates wind only."),
    ("cat2", "Category 2 hurricane", [83, 95], 972, 2, 14.0,
     "Sustained winds of 83 to 95 kt (96 to 110 mph, 154 to 177 km/h); NHC: 'Extremely dangerous "
     "winds will cause extensive damage.' The eye is clearer and the eyewall tighter. The original "
     "scale listed central pressure of 965 to 979 hPa and surge of about 6 to 8 ft (1.8 to 2.4 m)."),
    ("cat3", "Category 3 hurricane (major)", [96, 112], 955, 3, 16.0,
     "Sustained winds of 96 to 112 kt (111 to 129 mph, 178 to 208 km/h); NHC: 'Devastating damage "
     "will occur.' Category 3 and above are called major hurricanes. The eye is clear and calm, "
     "surrounded by a sharp eyewall with spiral rainbands outside it. The original scale listed "
     "central pressure of 945 to 964 hPa and surge of about 9 to 12 ft (2.7 to 3.7 m)."),
    ("cat4", "Category 4 hurricane (major)", [113, 136], 932, 4, 18.0,
     "Sustained winds of 113 to 136 kt (130 to 156 mph, 209 to 251 km/h); NHC: 'Catastrophic "
     "damage will occur.' The eye is small and sharply defined and the warm core is strong. The "
     "original scale listed central pressure of 920 to 944 hPa and surge of about 13 to 18 ft "
     "(4.0 to 5.5 m). Strong storms can go through eyewall replacement cycles, where an outer ring "
     "of thunderstorms slowly moves in and takes over from the old eyewall."),
    ("cat5", "Category 5 hurricane (major)", [137, None], 910, 5, 20.0,
     "Sustained winds of 137 kt (157 mph, 252 km/h) or more; NHC: 'Catastrophic damage will "
     "occur,' with total roof failure and wall collapse. The eyewall is compact and extremely "
     "intense. The original scale listed central pressure below 920 hPa and surge above 18 ft "
     "(5.5 m). Intensifying this far usually needs very warm water (near 30 degrees C) that is "
     "deep enough that the storm does not stir up cooler water from below."),
    ("landfall", "Landfall", [60, 130], 960, 4, 30.0,
     "When the centre crosses the coast the storm is cut off from the warm, moist ocean air that "
     "fuels it, and friction with the land slows the wind. Storm surge, water pushed onshore by the "
     "wind above the normal tide, is NHC's leading cause of hurricane deaths; its height depends "
     "on the storm's intensity, speed, size, approach angle and the shape of the sea floor and "
     "coast. The strongest wind and surge are found near landfall, then the storm starts to fill."),
    ("dissipation", "Dissipation", [0, 60], 998, -2, 36.0,
     "Over land the storm keeps losing its moisture supply and begins to draw in dry continental "
     "air, so the eyewall thunderstorms die out, the eye fills with cloud, the central pressure "
     "rises and the winds fall. Over mountains this happens quickly; over flat land a system can "
     "last two to three days. The leftover circulation can still drop heavy rain. A storm also "
     "dies over water much cooler than about 26.5 degrees C, or when strong wind shear tears it "
     "apart."),
]

# Storm quantities at the START of each stage (the 11th row is where the last stage ends, close to
# where the loop begins again).  rmw/eye in km, org/eyec 0..1, warm in deg C, top in km, rain mm/h
# (peak azimuthal-mean), sst in deg C.  Values are interpolated linearly across a stage.
#               rmw   eye   org   eyec  warm   top   rain   sst
_KNOTS = [
    (90.0,  0.0, 0.12, 0.00,  0.6, 10.0,  6.0, 27.4),   # disturbance
    (70.0,  0.0, 0.30, 0.00,  1.8, 11.0,  9.0, 27.8),   # depression
    (55.0, 14.0, 0.50, 0.10,  3.5, 12.5, 14.0, 28.2),   # storm
    (45.0, 22.0, 0.65, 0.35,  5.5, 14.0, 22.0, 28.6),   # cat1
    (38.0, 22.0, 0.74, 0.55,  7.0, 14.8, 28.0, 29.0),   # cat2
    (32.0, 20.0, 0.84, 0.72,  9.0, 15.5, 36.0, 29.3),   # cat3
    (25.0, 17.0, 0.93, 0.88, 11.0, 16.2, 44.0, 29.6),   # cat4
    (19.0, 13.0, 0.97, 0.97, 13.0, 17.0, 50.0, 29.9),   # cat5
    (22.0, 15.0, 0.82, 0.75, 11.0, 16.0, 46.0, 29.0),   # landfall
    (38.0, 24.0, 0.50, 0.25,  6.0, 12.0, 24.0, 28.0),   # dissipation
    (70.0,  0.0, 0.08, 0.00,  0.8,  6.0,  3.0, 27.4),   # end of dissipation
]
# Maximum wind (kt, 1-min sustained) across each stage: inside the Saffir-Simpson / NHC range of the
# stage, so the derived category always matches the stage.
_WIND_KT = [(12, 24), (27, 32), (40, 60), (66, 80), (85, 93), (99, 110), (117, 133), (141, 152),
            (130, 62), (60, 16)]
# Central pressure (hPa) across each stage; inside the original SSHS pressure range where one exists.
_PRES_HPA = [(1010, 1008), (1007, 1003), (1002, 993), (989, 981), (978, 966), (963, 947),
             (943, 922), (918, 902), (932, 990), (990, 1008)]
# Over-land fraction across each stage (0 = open ocean, 1 = fully over land).
_LAND = [(0, 0), (0, 0), (0, 0), (0, 0), (0, 0), (0, 0), (0, 0), (0, 0), (0.6, 1.0), (1.0, 1.0)]

_QUANTITIES = ("vmax", "pc", "rmw", "eye", "org", "eyec", "warm", "top", "rain", "sst", "land")

# Storm-surge height (m) against maximum wind (kt): the original SSHS category surge ranges
# (4-5, 6-8, 9-12, 13-18, >18 ft) interpolated between the category wind bounds.
_SURGE_PTS = [(0, 0.0), (25, 0.1), (34, 0.5), (64, 1.2), (83, 1.8), (96, 2.7), (113, 4.0),
              (137, 5.5), (160, 7.0)]

# ---------------------------------------------------------------------------------------------
# lesson (the guided tour)
# ---------------------------------------------------------------------------------------------
_LESSON = [
    ("ingredients", "What a hurricane needs", "outside", "sst", "disturbance",
     "A tropical cyclone needs a handful of ingredients at the same time. The ocean has to be warm, "
     "at least about 26.5 to 27 degrees C (80 F), through a depth of roughly 50 m (150 ft), so that "
     "churning water does not bring cooler water up. The air above must be unstable and moist up "
     "to the middle of the troposphere, and the vertical wind shear must be low, below about "
     "37 km/h (23 mph), or it tears the storm apart. It also needs a pre-existing disturbance to "
     "start from and a position at least about 5 degrees of latitude (roughly 555 km or 345 miles) "
     "from the equator."),
    ("disturbance", "A cluster of thunderstorms", "outside", "convection", "disturbance",
     "Most hurricanes start as a tropical disturbance: a cluster of showers and thunderstorms, "
     "generally 100 to 300 nautical miles across, such as the ones that travel along in an easterly "
     "wave over the tropical Atlantic. Each thunderstorm tower lifts warm, humid air and releases "
     "heat as the vapour condenses. At this point the towers are scattered, there is no eye and no "
     "organised spiral, and the surface pressure is only slightly lower than its surroundings."),
    ("heat_engine", "The ocean-powered heat engine", "both", "inflow", "depression",
     "A hurricane works like a heat engine. Warm ocean water evaporates into the air above it; when "
     "that humid air rises and the vapour condenses into cloud, it releases heat that warms the "
     "surrounding air and makes it more buoyant, so it keeps rising. Air near the surface rushes in "
     "to replace what rose, bringing more moisture from the ocean with it. The more the central "
     "air warms, the lower the surface pressure falls and the faster the wind blows inward."),
    ("coriolis", "Why it spins", "outside", "coriolis", "depression",
     "Air does not flow straight into the low-pressure centre. The Earth's rotation (the Coriolis "
     "effect) deflects moving air to the right in the northern hemisphere and to the left in the "
     "southern hemisphere, so the inflowing air curves around the centre. In the northern "
     "hemisphere the whole system turns counter-clockwise when seen from above. Near the equator "
     "the Coriolis effect is too weak to do this, which is why tropical cyclones rarely form within "
     "about 5 degrees of latitude of it."),
    ("spin_up", "Getting organised", "outside", "rainbands", "storm",
     "As long as the ocean keeps feeding it, the system organises: thunderstorms line up into "
     "curved bands, the winds near the centre pick up, and the central pressure keeps falling. "
     "With sustained winds of 34 kt (39 mph) it is a tropical storm and receives a name. A "
     "circulation that stays weak and ragged can still be torn apart by shear or dry air, so "
     "organisation is not guaranteed."),
    ("outside_eye", "From outside: the eye", "outside", "eye", "cat3",
     "Seen from above, a mature hurricane has a roughly circular hole in the middle: the eye. It is "
     "typically 30 to 65 km across (smaller and larger ones exist) and is the part of the storm "
     "with comparatively light winds and often clear skies. Air sinks inside it, which dries and "
     "warms the air and clears the cloud away, so the eye is a calm island surrounded by the most "
     "violent weather."),
    ("outside_eyewall", "From outside: the eyewall", "outside", "eyewall", "cat3",
     "The eyewall is the ring of tall thunderstorms that surrounds the eye. It holds the greatest "
     "wind speeds, the fastest rising air, the highest cloud tops and the heaviest rain in the whole "
     "storm. The radius of maximum wind sits right at its inner edge. Its thunderstorm tops can "
     "reach 15 to 18 km, up to the base of the stratosphere."),
    ("outside_bands", "From outside: spiral rainbands", "outside", "rainbands", "cat3",
     "Outside the eyewall, long narrow bands of rain and thunderstorms spiral in toward the centre. "
     "These rainbands rotate with the storm, can be embedded with heavy showers and squalls, and "
     "feed moist surface air toward the eyewall. In a strong, well-organised hurricane they are "
     "numerous and tightly wound; in a weak disorganised system they are scattered or missing."),
    ("outside_outflow", "From outside: the cirrus shield", "outside", "outflow", "cat3",
     "At the top of the troposphere the rising air has nowhere further to go and spreads outward. "
     "This outflow forms a broad shield of high cirrus cloud, typically 600 to 800 km across, that "
     "hides much of the storm from satellites' view of the surface. The outflow also carries air "
     "away from the storm, which helps keep the pressure at the centre low."),
    ("inside_inflow", "Inside: air spirals in", "inside", "inflow", "cat3",
     "Cut the storm in half and look at the side view. In the lowest kilometre or two, air flows "
     "inward toward the eye, picking up heat and moisture from the sea as it goes. This low-level "
     "inflow is strongest near the surface and outside the eyewall. It is also curving around the "
     "centre, so the closer it gets, the faster it spins."),
    ("inside_eyewall", "Inside: the rising eyewall", "inside", "eyewall", "cat3",
     "The inflowing air meets the eyewall and is forced to rise, fast. The eyewall is not "
     "vertical: it slopes outward with height, like the seats of a stadium (the 'stadium effect'). "
     "As the air rises, its vapour condenses into towering cloud and heavy rain, releasing the heat "
     "that powers the whole storm. A weaker, scattered kind of ascent happens under the "
     "rainbands."),
    ("inside_outflow", "Inside: outflow aloft", "inside", "outflow", "cat3",
     "High in the troposphere, near the tropopause, the air that rose through the eyewall turns "
     "and flows outward. This outflow layer carries it away from the centre and, together with the "
     "cirrus it forms, is the 'exhaust' of the heat engine. A storm with good outflow can keep "
     "pulling air up through the eyewall, while a storm whose outflow is blocked tends to weaken."),
    ("inside_eye", "Inside: the sinking, warm, clear eye", "inside", "eye", "cat4",
     "In the middle, air sinks. Sinking air is compressed, warms and dries, so the cloud in the eye "
     "evaporates and the eye is clear and calm, with only low cloud near the sea. That warming "
     "makes the eye and the air above it warmer than their surroundings, and the lowest surface "
     "pressure of the whole storm is found here. A more intense storm usually has a smaller and "
     "more sharply defined eye."),
    ("warm_core", "The warm core", "inside", "warm_core", "cat4",
     "Tropical cyclones are 'warm-core' systems: the column of air near the centre is warmer than "
     "the air around it, mostly in the upper troposphere, where the difference can be around "
     "7 to 13 degrees C. The warmth comes from the latent heat released in the eyewall and from "
     "sinking air in the eye. Warm air is lighter, so the weight of the air column above the sea is "
     "reduced, and that is what makes the pressure at the surface so low."),
    ("stages", "The stages of a hurricane", "both", "none", "storm",
     "NHC classifies a tropical cyclone by its maximum 1-minute sustained wind. A tropical "
     "disturbance has organised convection but winds below depression strength; a tropical "
     "depression has winds of 33 kt (38 mph) or less; a tropical storm 34 to 63 kt (39 to 73 mph); "
     "and a hurricane 64 kt (74 mph) or more. Hurricanes are then rated Category 1 to 5 on the "
     "Saffir-Simpson Hurricane Wind Scale, which rates wind only; Category 3 and above are major "
     "hurricanes."),
    ("intensity", "Categories 1 to 5", "outside", "eyewall", "cat5",
     "Category 1 starts at 64 kt (74 mph) and Category 5 begins at 137 kt (157 mph). As the winds "
     "increase, the central pressure falls, the eye shrinks and clears, and the eyewall becomes "
     "taller and more compact. Strong storms often get a boost from very warm, deep water, and a "
     "rise of 30 kt or more in 24 hours is called rapid intensification. Their strength can then "
     "fluctuate when an outer eyewall replaces the inner one."),
    ("surge", "Storm surge", "outside", "surge", "landfall",
     "Storm surge is the abnormal rise in sea level that a storm's winds push onto the shore, over "
     "and above the normal astronomical tide. It is the leading cause of deaths from hurricanes. "
     "How high it gets depends on the storm's intensity, its forward speed, its size, the angle at "
     "which it hits the coast, and the shape of the sea floor and the coastline: a wide shallow "
     "shelf and a concave coast pile the water up higher."),
    ("landfall", "Landfall: losing the ocean", "both", "decay", "landfall",
     "When the eye moves over land, the storm is cut off from the warm, moist air over the ocean "
     "that it uses as fuel, and the rough ground adds friction that slows the wind. The inflow of "
     "moisture shrinks, the eyewall thunderstorms weaken and the central pressure starts to rise, "
     "or 'fill'. The strongest wind and the storm surge are still found close to where the centre "
     "crosses the coast."),
    ("dissipation", "How a hurricane dies", "both", "decay", "dissipation",
     "Without the ocean heat engine the storm decays: it begins to draw in dry continental air, "
     "the eye fills with cloud, the rainbands break up and the winds fall below tropical-storm "
     "strength. Over mountains it can weaken quickly; over flat land it may survive for two to "
     "three days. A hurricane can also die over the open sea if it moves over water much cooler "
     "than about 26.5 degrees C or into strong wind shear."),
    ("explore", "Now explore", "both", "none", "cat3",
     "That is the whole life cycle. A disturbance over warm water becomes a heat engine that spins "
     "up with the help of the Earth's rotation. From the outside you see the eye, the eyewall, the "
     "spiral bands and the cirrus shield; from the inside, the low-level inflow, the sloping "
     "eyewall, the outflow aloft and the warm, sinking eye. Finally comes landfall and decay. "
     "Pick any stage to see how the picture changes, or let the storm run through its whole life "
     "on its own."),
]


def _smoothstep(x: float) -> float:
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    return x * x * (3.0 - 2.0 * x)


def _interp(pts, x: float) -> float:
    if x <= pts[0][0]:
        return pts[0][1]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return pts[-1][1]


def category_for_kt(kt: float) -> int:
    """-2 disturbance, -1 depression, 0 tropical storm, 1..5 Saffir-Simpson (kt, 1-minute)."""
    if kt >= 137:
        return 5
    if kt >= 113:
        return 4
    if kt >= 96:
        return 3
    if kt >= 83:
        return 2
    if kt >= 64:
        return 1
    if kt >= 34:
        return 0
    if kt >= 26:
        return -1
    return -2


def surge_for_kt(kt: float) -> float:
    return _interp(_SURGE_PTS, kt)


def _category_label(cat: int) -> str:
    return {-2: "tropical disturbance", -1: "tropical depression", 0: "tropical storm"}.get(
        cat, f"Category {cat} hurricane")


def _r1(x: float) -> float:
    return round(float(x) + 0.0, 1)


def _r2(x: float) -> float:
    return round(float(x) + 0.0, 2)


def _r3(x: float) -> float:
    return round(float(x) + 0.0, 3)


# ---------------------------------------------------------------------------------------------
# the simulation
# ---------------------------------------------------------------------------------------------
class HurricaneSim:
    def __init__(self, seed: int, fps: int):
        self.seed = int(seed)
        self.fps = max(1, int(fps))
        rng = random.Random(self.seed)

        # small per-seed variation (sizes, rain, sea temperature, band layout)
        self.size_scale = 1.0 + rng.uniform(-0.12, 0.12)
        self.rain_scale = 1.0 + rng.uniform(-0.08, 0.08)
        self.sst_offset = rng.uniform(-0.3, 0.3)
        self.rotation = rng.uniform(0.0, 2.0 * math.pi)
        self._band_cfg = []
        for i in range(N_BANDS):
            self._band_cfg.append({
                "theta": 2.0 * math.pi * i / N_BANDS + rng.uniform(-0.35, 0.35),
                "start": 0.10 + 0.12 * i + rng.uniform(-0.02, 0.02),   # organisation where it appears
                "base": rng.uniform(0.55, 1.0),
                "pitch": rng.uniform(0.24, 0.32),
                "r0": 1.9 + 0.35 * i + rng.uniform(-0.1, 0.1),         # in units of the RMW
                "ang": rng.uniform(4.8, 7.0),
                "wid": rng.uniform(0.8, 1.2),
            })

        # static, smoothed per-seed noise: a disorganised system is patchy, an organised one is not
        raw = [[rng.uniform(-1.0, 1.0) for _ in range(COLS)] for _ in range(ROWS)]
        self._noise = []
        for rw in range(ROWS):
            for cl in range(COLS):
                acc = 0.0
                for dr_, dc_, wgt in ((0, 0, 2.0), (1, 0, 1.0), (-1, 0, 1.0), (0, 1, 1.0), (0, -1, 1.0)):
                    acc += wgt * raw[min(max(rw + dr_, 0), ROWS - 1)][min(max(cl + dc_, 0), COLS - 1)]
                self._noise.append(acc / 6.0)

        self.running = True
        self.paused = False
        self.speed = 1.0
        self.auto = False
        self.frame = 0
        self.t = 0.0
        self.cycle = 0
        self.outbox: list = []
        self.stage_idx = 0
        self.stage_f = 0.0

        tgt = self._targets()
        self._s1 = dict(tgt)
        self._s2 = dict(tgt)
        # the 34-kt wind radius is very sensitive to vmax near the gale threshold (the Holland tail
        # is flat there), so it gets its own pair of lags on top of the profile it is read from
        r34 = self._gale_radius(self._core())
        self._r34 = [r34, r34]

        self._last_section_sig = None
        self._last_section_frame = -10 ** 9
        self._section_min_frames = max(1, int(math.ceil(self.fps / SECTION_MAX_HZ)))
        self._prev_cat = category_for_kt(self._s2["vmax"])
        self._prev_eye_clear = self._s2["eyec"] > 0.5
        self._prev_land = self._s2["land"] > 0.5

    # -- targets / smoothing -----------------------------------------------------------------
    def _targets(self) -> dict:
        i, f = self.stage_idx, self.stage_f
        k0, k1 = _KNOTS[i], _KNOTS[i + 1]

        def lerp(a, b):
            return a + (b - a) * f

        wlo, whi = _WIND_KT[i]
        plo, phi = _PRES_HPA[i]
        llo, lhi = _LAND[i]
        return {
            "vmax": lerp(wlo, whi),
            "pc": lerp(plo, phi),
            "rmw": lerp(k0[0], k1[0]) * self.size_scale,
            "eye": lerp(k0[1], k1[1]) * self.size_scale,
            "org": lerp(k0[2], k1[2]),
            "eyec": lerp(k0[3], k1[3]),
            "warm": lerp(k0[4], k1[4]),
            "top": lerp(k0[5], k1[5]),
            "rain": lerp(k0[6], k1[6]) * self.rain_scale,
            "sst": lerp(k0[7], k1[7]) + self.sst_offset,
            "land": lerp(llo, lhi),
        }

    def _advance_filters(self, dt: float):
        a = 1.0 - math.exp(-dt / TAU_S)
        tgt = self._targets()
        s1, s2 = self._s1, self._s2
        for q in _QUANTITIES:
            s1[q] += (tgt[q] - s1[q]) * a
            s2[q] += (s1[q] - s2[q]) * a
        raw = self._gale_radius(self._core())
        self._r34[0] += (raw - self._r34[0]) * a
        self._r34[1] += (self._r34[0] - self._r34[1]) * a

    # -- commands ----------------------------------------------------------------------------
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
            self.speed = max(0.25, min(8.0, v))
        elif c == "stage":
            sid = cmd.get("id")
            if sid in STAGE_IDS:
                self.stage_idx = STAGE_IDS.index(sid)
                self.stage_f = 0.0
                self.auto = False
                events.append(f"t={self.t:.1f} heading to the {_STAGE_META[self.stage_idx][1]} stage")
        elif c == "auto":
            val = cmd.get("value", True)
            if isinstance(val, str):
                val = val.strip().lower() in ("1", "true", "yes", "on")
            self.auto = bool(val)
        elif c == "save":
            self.outbox.append({"type": "saved", "ok": False, "path": str(cmd.get("path") or ""),
                                "t": _r2(self.t), "frame": self.frame,
                                "error": "saving is not available in hurricane mode"})
        elif c == "quit":
            self.running = False
        # everything else is ignored per PROTOCOL.md

    def drain_outbox(self) -> list:
        msgs = self.outbox
        self.outbox = []
        return msgs

    # -- hello -------------------------------------------------------------------------------
    def hello_message(self) -> dict:
        step = 8
        w, h = 1920, 1080
        stages = []
        for sid, name, wind, pres, cat, _dur, summary in _STAGE_META:
            stages.append({"id": sid, "name": name, "wind_kt": list(wind), "pressure_hpa": pres,
                           "category": cat, "summary": summary})
        lesson = [{"id": lid, "title": title, "text": text, "view": view, "focus": focus,
                   "stage": stage} for lid, title, view, focus, stage, text in _LESSON]
        return {
            "type": "hello", "version": 2, "seed": self.seed, "loaded": False, "t": _r2(self.t),
            "world": {"w": w, "h": h, "ground_y": 1000, "terrain_kind": "flat",
                      "terrain": {"step": step, "y": [1000] * (int(math.ceil(w / step)) + 1)}},
            "settlements": [], "objects": [],
            "mode": "hurricane",
            "hurricane": {
                "domain": {"radius_km": int(DOMAIN_RADIUS_KM), "top_km": int(TOP_KM)},
                "stages": stages, "lesson": lesson, "sources": list(SOURCES),
            },
        }

    # -- stepping ----------------------------------------------------------------------------
    def step(self, dt_real: float, extra_events=None) -> dict:
        events: list = list(extra_events) if extra_events else []
        if not self.paused:
            dt = dt_real * self.speed
            self.t += dt
            if self.auto:
                self._advance_playback(dt, events)
            self._advance_filters(dt)
            vmax_kt = self._s2["vmax"]
            self.rotation += (0.03 + 0.0042 * vmax_kt) * dt
            self._detect_events(events)
        self.frame += 1
        return self._build_frame(events)

    def _advance_playback(self, dt: float, events: list):
        self.stage_f += dt / _STAGE_META[self.stage_idx][5]
        while self.stage_f >= 1.0:
            self.stage_f -= 1.0
            self.stage_idx += 1
            if self.stage_idx >= len(STAGE_IDS):
                self.stage_idx = 0
                self.cycle += 1
                events.append(f"t={self.t:.1f} a new tropical disturbance forms (cycle {self.cycle + 1})")
            else:
                events.append(f"t={self.t:.1f} heading to the {_STAGE_META[self.stage_idx][1]} stage")

    def _detect_events(self, events: list):
        s = self._s2
        cat = category_for_kt(round(s["vmax"]))
        if cat != self._prev_cat:
            if cat > self._prev_cat:
                verb = "strengthened to" if self._prev_cat > -2 else "organised into"
                events.append(f"t={self.t:.1f} {verb} a {_category_label(cat)}")
            else:
                events.append(f"t={self.t:.1f} weakened to a {_category_label(cat)}")
            self._prev_cat = cat
        clear = s["eyec"] > 0.5
        if clear != self._prev_eye_clear:
            events.append(f"t={self.t:.1f} " + ("the eye cleared out" if clear else "the eye clouded over"))
            self._prev_eye_clear = clear
        land = s["land"] > 0.5
        if land != self._prev_land:
            events.append(f"t={self.t:.1f} " + (
                "landfall: the storm is cut off from the warm ocean" if land
                else "the new system is back over open water"))
            self._prev_land = land

    # -- derived structure -------------------------------------------------------------------
    def _core(self) -> dict:
        s = self._s2
        vmax_kt = max(s["vmax"], 0.0)
        vmax = vmax_kt * KT_TO_MS
        pc = s["pc"]
        dp = max(P_ENV_HPA - pc, 1.0)
        b = vmax * vmax * RHO_AIR * math.e / (dp * 100.0)
        b = max(0.9, min(2.5, b))
        rm = max(s["rmw"], 5.0)
        eyec = max(0.0, min(1.0, s["eyec"]))
        return {
            "vmax_kt": vmax_kt, "vmax": vmax, "pc": pc, "dp": dp, "B": b, "rm": rm,
            "eye": max(s["eye"], 0.0), "eyec": eyec, "n": 1.0 + 3.0 * eyec,
            "org": max(0.0, min(1.0, s["org"])), "warm": s["warm"], "top": s["top"],
            "rain": s["rain"], "sst": s["sst"], "land": max(0.0, min(1.0, s["land"])),
        }

    @staticmethod
    def _wind_at(c: dict, r: float) -> float:
        """Tangential wind (m/s) at radius r (km): power-law inside the RMW, Holland outside."""
        rm = c["rm"]
        vm = c["vmax"]
        if r <= 0.0:
            base = 0.0
        elif r < rm:
            base = vm * (r / rm) ** c["n"]
        else:
            x = (rm / r) ** c["B"]
            base = vm * math.sqrt(x * math.exp(1.0 - x))
        return base * math.exp(-(r / 520.0) ** 3)

    @staticmethod
    def _gale_radius(c: dict) -> float:
        """Radius (km) of the 34-kt wind, from the same wind profile; grows from 0 as vmax passes 34 kt."""
        vm = c["vmax"]
        if vm <= GALE_MS:
            return 0.0
        lo, hi = c["rm"], 900.0
        if HurricaneSim._wind_at(c, hi) >= GALE_MS:
            r = hi
        else:
            for _ in range(32):
                mid = 0.5 * (lo + hi)
                if HurricaneSim._wind_at(c, mid) >= GALE_MS:
                    lo = mid
                else:
                    hi = mid
            r = 0.5 * (lo + hi)
        return r * _smoothstep((vm - GALE_MS) / 1.5)

    def _bands(self, c: dict) -> list:
        org, rm, land = c["org"], c["rm"], c["land"]
        out = []
        for i, cfg in enumerate(self._band_cfg):
            inten = cfg["base"] * _smoothstep((org - cfg["start"]) / 0.3) * (1.0 - 0.4 * land)
            pitch = cfg["pitch"] * (1.25 - 0.35 * org)
            r0 = max(40.0 + 16.0 * i, cfg["r0"] * rm)
            ang = cfg["ang"] * (0.55 + 0.45 * org)
            r1 = min(DOMAIN_RADIUS_KM * 0.97, r0 * math.exp(pitch * ang))
            r1 = max(r1, r0 + 20.0)
            out.append({"theta": cfg["theta"], "r0": r0, "r1": r1, "pitch": pitch,
                        "width": (22.0 + 22.0 * org) * cfg["wid"], "intensity": inten})
        return out

    @staticmethod
    def _band_cover(bands: list, r: float) -> float:
        """Azimuthal-mean fraction of the circle at radius r covered by the (intensity weighted) bands."""
        tot = 0.0
        for b in bands:
            if b["intensity"] <= 0.0005 or r < b["r0"] or r > b["r1"]:
                continue
            p = b["pitch"]
            edge = min(1.0, (r - b["r0"]) / 20.0, (b["r1"] - r) / 50.0)
            arc = b["width"] * math.sqrt(1.0 + p * p) / p     # arc length of the circle inside the arm
            tot += b["intensity"] * edge * min(1.0, arc / (2.0 * math.pi * r))
        return min(1.2, tot)

    def _rain_at(self, c: dict, bands: list, r: float) -> float:
        """Azimuthal-mean rain rate (mm/h): eyewall ring (dry eye when it is clear), rainbands, background."""
        rm, eyec, org, rain_peak = c["rm"], c["eyec"], c["org"], c["rain"]
        e2 = (1.0 - eyec) ** 2
        sig = (0.35 * rm + 5.0) * (1.0 + 1.4 * (1.0 - org))
        x = r - 1.05 * rm
        if x < 0.0:
            ring = e2 + (1.0 - e2) * math.exp(-0.5 * (x / (0.5 * sig)) ** 2)
        else:
            ring = math.exp(-0.5 * (x / sig) ** 2)
        return (rain_peak * ring + 0.55 * rain_peak * self._band_cover(bands, r)
                + 0.06 * rain_peak * org * math.exp(-r / 250.0))

    def _profile(self, c: dict, bands: list) -> dict:
        rm, vm = c["rm"], c["vmax"]
        eye, eyec, org = c["eye"], c["eyec"], c["org"]
        e2 = (1.0 - eyec) ** 2
        top = c["top"]
        hole = max(eye, 8.0)
        wind, pres, rain, ctop = [], [], [], []
        for r in PROFILE_R:
            wind.append(_r2(self._wind_at(c, r) + 1.5 * math.exp(-(r / hole) ** 2)))
            if r <= 0.0:
                p = c["pc"]
            else:
                p = c["pc"] + c["dp"] * math.exp(-((rm / r) ** c["B"]))
            pres.append(_r1(p))
            rain.append(_r1(self._rain_at(c, bands, r)))
            outer = 4.5 + (top - 4.5) * math.exp(-max(0.0, r - 1.1 * rm) / (180.0 + 120.0 * org))
            inner = 2.0 + (top - 2.0) * e2
            s = _smoothstep((r - 0.5 * eye) / max(0.5 * rm, 1.0)) if eye > 0 else 1.0
            ctop.append(_r1(inner + (outer - inner) * s))
        return {"r_km": [int(r) for r in PROFILE_R], "wind_ms": wind, "pressure_hpa": pres,
                "rain_mmh": rain, "cloud_top_km": ctop}

    # -- vertical cross-section --------------------------------------------------------------
    def _build_section(self, c: dict, bands: list) -> dict:
        org, eyec, rm, top, land = c["org"], c["eyec"], c["rm"], max(c["top"], 4.0), c["land"]
        vmax = c["vmax"]
        e2 = (1.0 - eyec) ** 2
        slope = 0.06 * rm + 0.6                       # km outward per km of height (eyewall tilt)
        wm = 0.07 * vmax * (0.35 + 0.65 * org) * (1.0 - 0.4 * land)      # peak eyewall ascent, m/s
        ui = 0.33 * vmax * (0.4 + 0.6 * org) * (1.0 - 0.4 * land)        # surface inflow, m/s
        uo = 0.25 * vmax * (0.3 + 0.7 * org) * (1.0 - 0.5 * land)        # outflow aloft, m/s
        blur = 1.0 + 1.2 * (1.0 - org)
        dis = (1.0 - org) ** 1.5
        zo = 0.88 * top                               # height of the outflow layer
        rpk = 1.8 * rm                                # radius of strongest low-level inflow
        warm = c["warm"]
        ampc = 0.25 + 0.75 * org ** 1.2
        moist = 1.0 - 0.35 * land
        bt = 0.6 * top + 1.5                          # top of rainband convection
        rcen = _RCEN
        # In the axisymmetric section each spiral band shows up as one convective column where
        # the band crosses a fixed ray of the unrotated pattern (so the picture does not change
        # as the storm rotates); a band that has not wound out that far yet is not there.
        cover = [0.0] * COLS
        for b in bands:
            if b["intensity"] <= 0.0005:
                continue
            off = (-b["theta"]) % (2.0 * math.pi)
            rc = b["r0"] * math.exp(b["pitch"] * off)
            reach = _smoothstep((b["r1"] + 20.0 - rc) / 40.0)
            if reach <= 0.0:
                continue
            wd = 0.25 * b["width"]
            for i, r in enumerate(rcen):
                cover[i] += reach * b["intensity"] * math.exp(-0.5 * ((r - rc) / wd) ** 2)
        cover = [min(1.0, v) for v in cover]
        u_g, w_g, v_g, cl_g, t_g = [], [], [], [], []
        exp, sqrt, pi, sin = math.exp, math.sqrt, math.pi, math.sin
        noise = self._noise
        vm, bpar, npow = c["vmax"], c["B"], c["n"]
        infl_c = []
        for r in rcen:
            xr = r / rpk
            infl_c.append(2.0 * xr / (1.0 + xr * xr))
        ca = 0.25 + 0.75 * org
        cdecay = 150.0 + 200.0 * org
        for row in range(ROWS):
            z = (row + 0.5) * DZ_KM
            rz = rm + slope * z
            sig = (max(0.4 * rm, 9.0) + 0.15 * z) * blur
            x = z / top
            vert = sin(pi * x) if x < 1.0 else 0.0
            sub = (x * (1.0 - x) ** 0.6) / 0.35 if 0.0 < x < 1.0 else 0.0
            cz = _smoothstep((top - z) / 2.5)
            dec = 1.0 - 0.056 * z
            low = exp(-(z / 1.3) ** 2)
            outf = exp(-0.5 * ((z - zo) / 1.7) ** 2)
            cirf = exp(-0.5 * ((z - zo) / 1.2) ** 2)
            warmz = exp(-0.5 * ((z - 10.5) / 3.3) ** 2)
            lowc = _smoothstep((2.8 - z) / 1.0)
            bcz = _smoothstep((bt - z) / 2.0)
            bvert = sin(pi * z / bt) if z < bt else 0.0
            rscale = rm / rz
            wmv = wm * vert
            uslope = min(slope * wm * 0.9, 0.4 * vmax / max(vert, 1e-6)) * vert
            ihsig = 1.0 / (0.8 * rz + 10.0)
            ihrz = 0.6 * rz
            warm_row = warm * warmz
            wsig = 1.0 / (0.9 * rz + 8.0)
            inv_eye = 1.0 / (0.55 * rz)
            inv_eye2 = 1.0 / (0.9 * rz)
            base = row * COLS
            for col in range(COLS):
                r = rcen[col]
                d = (r - rz) / sig
                gew = exp(-0.5 * d * d) if -6.0 < d < 6.0 else 0.0
                gfill = gew if r >= rz else e2 + (1.0 - e2) * gew
                nz = noise[base + col]
                gfill_w = gfill * (1.0 + 0.8 * dis * nz)
                gfill_c = gfill * (1.0 + 1.2 * dis * nz)
                cvr = cover[col]
                # vertical motion: eyewall ascent, gentle sinking in the eye, band convection
                w = wmv * gfill_w + 0.35 * wm * cvr * bvert - 0.03 * wmv * _E250[col]
                if eyec > 0.0 and r < 2.5 * rz:
                    q = r * inv_eye
                    w -= 0.3 * wm * eyec * exp(-0.5 * q * q) * sub
                # radial wind: inflow near the surface, outward along the sloping eyewall, outflow aloft
                u = -ui * low * infl_c[col] + uslope * gew
                if outf > 0.002:
                    h = (r - ihrz) * ihsig
                    if h > 0.0:
                        u += uo * outf * _smoothstep(h) * _E450[col]
                # tangential wind: profile stretched outward with height, decaying aloft
                rr = r * rscale
                if rr < rm:
                    vv = vm * (rr / rm) ** npow
                else:
                    xx = (rm / rr) ** bpar
                    vv = vm * sqrt(xx * exp(1.0 - xx))
                v = vv * exp(-(rr / 520.0) ** 3) * dec
                # cloud / rain density
                keep = 1.0 - gfill_c * cz * ampc * moist
                if keep < 0.0:
                    keep = 0.0
                if lowc > 0.0:
                    if eyec > 0.0 and r < 3.0 * rz:
                        q = r * inv_eye2
                        keep *= 1.0 - 0.08 * eyec * exp(-q * q) * lowc
                    if r > 0.5 * rz:
                        keep *= 1.0 - 0.2 * org * _E300[col] * lowc
                if cirf > 0.002 and r > 0.5 * rz:
                    cir = 0.55 * ca * cirf * _smoothstep((r - 0.5 * rz) / rz) *                         exp(-max(0.0, r - 1.3 * rz) / cdecay)
                    keep *= 1.0 - (cir if cir < 1.0 else 1.0)
                if cvr > 0.0 and bcz > 0.0:
                    keep *= 1.0 - 0.85 * cvr * bcz * moist
                ta = 0.0
                if warm_row > 0.002:
                    q = r * wsig
                    if q < 5.0:
                        ta = warm_row * exp(-0.5 * q * q)
                u_g.append(round(u, 2) + 0.0)
                w_g.append(round(w, 2) + 0.0)
                v_g.append(round(v, 2) + 0.0)
                cl_g.append(round(1.0 - keep, 3) + 0.0)
                t_g.append(round(ta, 2) + 0.0)
        return {"cols": COLS, "rows": ROWS, "dr_km": DR_KM, "dz_km": DZ_KM,
                "u": u_g, "w": w_g, "v": v_g, "cloud": cl_g, "temp_anom": t_g}

    def _section_due(self, c: dict) -> bool:
        sig = (c["vmax"], c["rm"], c["eye"], c["org"] * 30.0, c["eyec"] * 30.0, c["warm"],
               c["top"], c["land"] * 30.0, c["rain"])
        last = self._last_section_sig
        if last is None:
            self._last_section_sig = sig
            self._last_section_frame = self.frame
            return True
        if self.frame - self._last_section_frame < self._section_min_frames:
            return False
        diff = 0.0
        anydiff = 0.0
        for a, b in zip(sig, last):
            scale = max(abs(a), abs(b), 5.0)
            d = abs(a - b) / scale
            diff = max(diff, d)
            anydiff = max(anydiff, d)
        stale = self.frame - self._last_section_frame >= 2 * self.fps
        if diff > 0.004 or (stale and anydiff > 1e-5):
            self._last_section_sig = sig
            self._last_section_frame = self.frame
            return True
        return False

    # -- frame -------------------------------------------------------------------------------
    def _build_frame(self, events: list) -> dict:
        c = self._core()
        bands = self._bands(c)
        profile = self._profile(c, bands)
        kt = int(round(c["vmax_kt"]))
        cat = category_for_kt(kt)
        r34 = self._r34[1]
        state = {
            "vmax_ms": _r1(c["vmax"]), "vmax_kt": kt, "pressure_hpa": _r1(c["pc"]),
            "category": cat, "rmw_km": _r1(c["rm"]), "eye_km": _r1(c["eye"]),
            "r34_km": _r1(r34), "sst_c": _r2(c["sst"]), "warm_core_c": _r1(c["warm"]),
            "cloud_top_km": _r1(c["top"]), "rain_mmh": _r1(self._rain_at(c, bands, 1.05 * c["rm"])),
            "surge_m": _r2(surge_for_kt(c["vmax_kt"])), "organization": _r3(c["org"]),
            "eye_clarity": _r3(c["eyec"]), "over_land": c["land"] > 0.5,
            "rotation": _r3(self.rotation), "hemisphere": "N",
        }
        frame = {
            "type": "hurricane", "frame": self.frame, "t": _r2(self.t), "speed": _r2(self.speed),
            "paused": self.paused, "stage": STAGE_IDS[self.stage_idx],
            "stage_index": self.stage_idx, "stage_frac": _r3(self.stage_f), "auto": self.auto,
            "state": state, "profile": profile,
            "bands": [{"theta": _r3(b["theta"]), "r0_km": _r1(b["r0"]), "r1_km": _r1(b["r1"]),
                       "pitch": _r3(b["pitch"]), "width_km": _r1(b["width"]),
                       "intensity": _r3(b["intensity"])} for b in bands],
            "events": events,
        }
        if self._section_due(c):
            frame["section"] = self._build_section(c, bands)
        return frame


# ---------------------------------------------------------------------------------------------
# helpers for tests / manual inspection
# ---------------------------------------------------------------------------------------------
def stage_table(seed: int = 1, settle_s: float = 16.0, fps: int = 30) -> list:
    """Settle the model at the start of every stage and return one dict per stage (state values)."""
    sim = HurricaneSim(seed, fps)
    rows = []
    for sid in STAGE_IDS:
        sim.handle_command({"cmd": "stage", "id": sid}, [])
        msg = None
        for _ in range(int(settle_s * fps)):
            msg = sim.step(1.0 / fps)
        row = dict(msg["state"])
        row["stage"] = sid
        row["B"] = round(sim._core()["B"], 2)
        rows.append(row)
    return rows


def format_stage_table(rows: list) -> str:
    cols = [("stage", "stage", 12), ("cat", "category", 4), ("vmax_kt", "vmax_kt", 8),
            ("p_hPa", "pressure_hpa", 8), ("rmw", "rmw_km", 6), ("eye", "eye_km", 6),
            ("r34", "r34_km", 7), ("top", "cloud_top_km", 6), ("warm", "warm_core_c", 6),
            ("rain", "rain_mmh", 6), ("surge_m", "surge_m", 8), ("org", "organization", 6),
            ("eyec", "eye_clarity", 6), ("sst", "sst_c", 6), ("B", "B", 5)]
    lines = ["".join(h.rjust(w) if i else h.ljust(w) for i, (h, _k, w) in enumerate(cols))]
    for row in rows:
        lines.append("".join(str(row[k]).rjust(w) if i else str(row[k]).ljust(w)
                             for i, (_h, k, w) in enumerate(cols)))
    return "\n".join(lines)


if __name__ == "__main__":
    print(format_stage_table(stage_table()))
