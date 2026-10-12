"""Region presets (PROTOCOL.md v2.2 / v2.3): a real elevation cross-section plus the place's real climate.

Every figure that reaches the wire (`hello.preset`, `assets/world/presets.json`) comes from this file and
traces to a URL in the preset's `sources` list (each landmark and the climate block carry the URL they came
from in `src`; tests/test_presets.py checks that every `src` is listed and that every figure is present).
The v2.2 data was read from the cited pages on 2026-10-08 and the v2.3 additions (monthly normals, the
fifteen new places) on 2026-10-09. Wikipedia is used for the peak/place heights and for the station normals
it republishes from the national weather services (NOAA, Met Office, DWD, Roshydromet, PAGASA, CMA, JMA,
BoM, NIWA, INMET/DGAC, ...). Normals period: 1991-2020 unless `climate.period` says otherwise.

What is data and what is game tuning
  data    landmarks (lat, lon, elevation), station normals (twelve monthly mean temperatures, twelve monthly
          precipitation totals, annual mean daily maximum and minimum), the elevation the cited page gives
          for the place, the direction word of the prevailing wind
  tuning  `wind_u` (m/s, the sim's prevailing wind strength; only its SIGN is the real direction along the
          transect, it is negative where the real wind blows towards the start of the line), `cloud_mix`
          (relative spawn weights chosen to fit the described climate), `start_*` and `start_month` (where
          a sandbox opens), `humidity` and `aerosol` (0..1, see below). None of the tuning numbers is sent
          as a fact. `wind_from_deg` is the bearing the cited wind word means (south = 180, north-east = 45);
          test_presets checks the sign of `wind_u` against it.

Elevation profile  The control points are the cited landmarks only (peaks, stations, and sea level
          for bays/estuaries/seas: 0 m). Between them the profile is a monotone cubic curve, so the shape
          is smooth but the only heights in it are real ones. The points are projected onto the
          straight line from the first to the last landmark (local equirectangular projection), so a
          peak slightly off the line (Mount Olympus, Mount Rainier, Denali) is placed by where it falls
          along the line. The whole span is stretched over the world width. The lowest landmark is the
          lowest ground of the world (Badwater Basin, -86 m, in the California line).

Place elevation  `climate.elev_m` is the elevation the cited page gives for the place (the infobox, or the
          station height where the climate table states it: Denver DIA, Nairobi JKIA, In Salah). Where the
          page gives none (`None`: Tokyo, Sydney, Hokitika) no lapse correction is applied: the station is
          taken to stand at the lowest ground, which for a coastal station is within a few tenths of a degree.

Starting temperature  `world.surface_temp_c` is the temperature at the LOWEST ground of the profile.
          basis "annual": the station's annual mean carried down to the lowest ground with the
          ICAO standard lapse rate 6.5 C/km. basis "coldest_month": the same with the station's
          coldest-month mean (used for Russia and Tibet so that they start in their snowy season; the
          annual means are above the sim's snow line for the compressed profile). The `climate.note`
          on the wire says so. v2.3: `climate_block()` hands the twelve monthly means (same lapse
          correction) to the season model, which then replaces this as the start temperature.

humidity / aerosol  (tuning, 0..1, derived from the climate so that a dry place starts dry)
          humidity = 0.15 + 0.80 * sqrt(min(1, annual_precip_mm / 2500))
          aerosol  = 0.10 + 0.45 * (1 - humidity) ** 2      (dust and haze rise as the air dries out)

Biome (v2.3)  `biome` (ground / soil / grass / water colours, tree density and mix, pond rarity, the snow cover
          a frozen world opens with, the settlement plan, the named places) is TUNING, not data: it is chosen
          from the cited climate to make each place look like itself and nothing in it is a measured figure.
          The named places are landmarks that are real towns, cities or stations; a settlement generated on
          or nearest to one takes its name (see the `_BIOMES` block and settlements._plan_biome).
          The climate station's town (`station=True` in `places`) is always placed: if its landmark has no room it
          takes the nearest free span (Sydney: the Blue Mountains summit span fills the coastal end, so the city
          stands just west of it; Dubbo gave way). Not placed, for lack of room beside the summit spans of the
          compressed profile: Dubbo, Guwahati, Paranal Observatory, Omsk, Burlington, Snoqualmie Pass, Miami,
          Tulsa, Seward, Tamanrasset, San Pedro de Atacama and Mawsynram. Building kinds (`avoid`) and the road
          plan (`roads`) are tuning too: barn, windmill and church only in temperate and mediterranean worlds;
          no highway on the ice, light traffic in the desert.

Dropped for lack of a verifiable figure: Iceland (no cited prevailing wind direction), Moshi and Arusha
(Tanzania; no daily-mean row / no normals period), Kanazawa, Toyama, Matsumoto, Shillong Peak, Isiolo,
Nanyuki, Amboseli, Machakos, Greymouth, Hofn, Vik (no elevation on the cited page).
"""
from __future__ import annotations

import math

from terrain import Terrain, profile_m_per_px, profile_x

LAPSE_C_PER_KM = 6.5

WIKI = "https://en.wikipedia.org/wiki/"
_LAPSE_SRC = WIKI + "Lapse_rate"


def _lm(name, lat, lon, elev_m, src):
    return {"name": name, "lat": lat, "lon": lon, "elev_m": elev_m, "src": WIKI + src}


PRESETS: dict = {
    # ------------------------------------------------------------------------------------------
    "washington": {
        "name": "Washington",
        "summary": ("Pacific storms run into the Olympic Mountains and the Cascades: the windward slopes get "
                    "metres of rain and over 400 inches of snow a year, and the plateau behind them is semi-arid."),
        "transect_text": "47-48°N, Pacific coast → Columbia Plateau (west → east)",
        "lat": 47.4489, "lon": -122.3094, "region": "US-WA", "country": "US", "kind": "state",
        "start_month": 11.0,
        "highlights": ["rain shadow", "Cascades snow", "Pacific storms"],
        "landmarks": [
            _lm("Ocean Shores (Pacific coast)", 46.9717, -124.1467, 3, "Ocean_Shores,_Washington"),
            _lm("Mount Olympus", 47.8013, -123.7108, 2430, "Mount_Olympus_(Washington)"),
            _lm("Hood Canal", 47.8, -122.7, 0, "Hood_Canal"),
            _lm("Seattle-Tacoma Airport", 47.4489, -122.3094, 132, "Seattle%E2%80%93Tacoma_International_Airport"),
            _lm("Mount Rainier", 46.8517, -121.7603, 4391, "Mount_Rainier"),
            _lm("Snoqualmie Pass", 47.4233, -121.4108, 919, "Snoqualmie_Pass"),
            _lm("Ellensburg", 47.0003, -120.5467, 477, "Ellensburg,_Washington"),
            _lm("Moses Lake", 47.1447, -119.2758, 323, "Moses_Lake,_Washington"),
        ],
        "climate": {
            "station": "Seattle-Tacoma Airport", "elev_m": 132, "temp_c": 12.1, "annual_precip_mm": 999,
            "monthly_temp_c": [6.0, 6.7, 8.4, 10.7, 14.2, 16.7, 19.5, 19.7, 17.0, 12.1, 8.1, 5.6],
            "monthly_precip_mm": [147, 96, 106, 81, 48, 37, 15, 25, 41, 99, 160, 145],
            "annual_max_c": 16.3, "annual_min_c": 7.8, "period": "1991-2020",
            "wind": "westerly",
            "note": ("wet windward slopes (Olympic Peninsula west side up to 4,100 mm a year, Mount Olympus "
                     "main peak 5,899 mm), dry rain shadow east of the Cascades (Ellensburg 229 mm, Moses "
                     "Lake 198 mm); winter winds from the south-west, summer winds from the north-west"),
            "src": [WIKI + "Climate_of_Seattle", WIKI + "Geography_of_Washington_(state)",
                    WIKI + "Mount_Olympus_(Washington)", WIKI + "Ellensburg,_Washington",
                    WIKI + "Moses_Lake,_Washington"],
        },
        "start": {"basis": "annual", "ref_temp_c": 12.1},
        "wind_u": 5.0, "wind_from_deg": 270,
        "cloud_mix": {"nimbostratus": 4.0, "stratocumulus": 3.0, "stratus": 3.0, "altostratus": 3.0,
                      "cumulus": 2.0, "cirrus": 2.0, "altocumulus": 1.0, "cumulonimbus": 0.4},
    },
    # ------------------------------------------------------------------------------------------
    "philippines": {
        "name": "Philippines",
        "summary": ("Northern Luzon: the Habagat monsoon rains and passing typhoons hit the Cordillera Central and "
                    "the Sierra Madre, while the Cagayan Valley between them is the hottest and driest ground."),
        "transect_text": "16-17.5°N, Lingayen Gulf → Cordillera Central → Cagayan Valley → Sierra Madre (WSW → ENE)",
        "lat": 17.6133, "lon": 121.7303, "region": "PH", "country": "PH", "kind": "country",
        "start_month": 8.0,
        "highlights": ["Habagat monsoon", "typhoons", "mountain rain"],
        "landmarks": [
            _lm("Lingayen Gulf (Dagupan)", 16.0430, 120.3340, 0, "Dagupan"),
            _lm("Baguio", 16.4119, 120.5933, 1465, "Baguio"),
            _lm("Mount Pulag", 16.5836, 120.8836, 2928, "Mount_Pulag"),
            _lm("Bayombong", 16.48, 121.15, 342, "Bayombong"),
            _lm("Tuguegarao (Cagayan Valley)", 17.6133, 121.7303, 120, "Tuguegarao"),
            _lm("Mount Dos Cuernos", 17.5338, 122.0030, 1736, "Northern_Sierra_Madre_Natural_Park"),
            _lm("Palanan (Pacific coast)", 17.0589, 122.4300, 52, "Palanan"),
        ],
        "climate": {
            "station": "Tuguegarao", "elev_m": 120, "temp_c": 26.9, "annual_precip_mm": 1769,
            "monthly_temp_c": [23.6, 24.5, 26.6, 28.9, 29.5, 29.4, 28.5, 28.2, 27.8, 26.6, 25.4, 23.8],
            "monthly_precip_mm": [33.7, 32.2, 31.5, 46.5, 141.4, 135.7, 205.5, 216.8, 226.0, 282.9, 258.4, 158.3],
            "annual_max_c": 32.1, "annual_min_c": 21.7, "period": "1991-2020",
            "wind": "south-west monsoon (Habagat), north-east monsoon (Amihan) in winter",
            "note": ("Habagat rains June-October, about 28 storms or typhoons a year enter the Philippine "
                     "area; Baguio on the Cordillera slope gets 3,824 mm, Mount Pulag 2,764 mm and Palanan "
                     "on the Pacific side 1,875 mm; snow never falls even on the summit of Luzon"),
            "src": [WIKI + "Tuguegarao", WIKI + "Climate_of_the_Philippines", WIKI + "Baguio",
                    WIKI + "Mount_Pulag", WIKI + "Palanan"],
        },
        "start": {"basis": "annual", "ref_temp_c": 26.9},
        "wind_u": 3.5, "wind_from_deg": 225,
        "cloud_mix": {"cumulus": 5.0, "cumulonimbus": 4.0, "nimbostratus": 3.0, "cirrus": 2.0,
                      "stratocumulus": 1.0, "altostratus": 1.0, "altocumulus": 1.0, "stratus": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "tibet": {
        "name": "Tibet",
        "summary": ("From the Khumbu side of the Himalaya over Everest and the Yarlung Tsangpo valley to the "
                    "high, cold, dry plateau around Nagqu; the monsoon runs out of moisture long before the "
                    "north of the plateau."),
        "transect_text": "27.7°N 86.7°E → 31.5°N 92.1°E, Himalaya → Tibetan Plateau (SW → NE)",
        "lat": 31.476, "lon": 92.051, "region": "CN", "country": "CN", "kind": "place",
        "start_month": 1.0,
        "highlights": ["Everest", "high plateau", "monsoon limit"],
        "landmarks": [
            _lm("Lukla", 27.6889, 86.7306, 2860, "Lukla"),
            _lm("Mount Everest", 27.9883, 86.9253, 8849, "Mount_Everest"),
            _lm("Shigatse", 29.2670, 88.8810, 3836, "Shigatse"),
            _lm("Nyenchen Tanglha", 30.3708, 90.5844, 7162, "Nyenchen_Tanglha_Mountains"),
            _lm("Nam Co (lake surface)", 30.7000, 90.5500, 4718, "Nam_Co"),
            _lm("Nagqu", 31.4760, 92.0510, 4507, "Nagqu"),
        ],
        "climate": {
            "station": "Nagqu", "elev_m": 4507, "temp_c": -0.1, "annual_precip_mm": 470,
            "monthly_temp_c": [-11.3, -8.5, -4.5, -0.2, 4.2, 8.4, 10.0, 9.5, 6.7, 0.8, -6.0, -9.9],
            "monthly_precip_mm": [5.2, 3.2, 7.5, 11.9, 40.9, 90.2, 111.0, 100.9, 71.7, 22.0, 3.5, 2.0],
            "annual_max_c": 7.7, "annual_min_c": -6.5, "period": "1991-2020",
            "wind": "southerly in the summer monsoon, windy in the dry winter",
            "note": ("starts in midwinter (January mean -11.3 °C); the plateau averages over 4,500 m, its "
                     "colder, drier north gets only 100-300 mm a year, and the Indian monsoon brings its "
                     "moisture from the south in summer"),
            "src": [WIKI + "Nagqu", WIKI + "Tibetan_Plateau"],
        },
        "start": {"basis": "coldest_month", "ref_temp_c": -11.3},
        "wind_u": 6.0, "wind_from_deg": 180,
        "cloud_mix": {"cirrus": 4.0, "altocumulus": 4.0, "altostratus": 2.0, "stratocumulus": 2.0,
                      "cumulus": 2.0, "nimbostratus": 0.8, "cumulonimbus": 0.6, "stratus": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "russia": {
        "name": "Russia",
        "summary": ("Moscow across the East European Plain, over the low, old Southern Urals and out onto the "
                    "flat West Siberian Plain: long cold winters, snow cover for months, Atlantic air that "
                    "dries out as it goes east."),
        "transect_text": "55-56°N, Moscow → Southern Urals → Omsk (west → east)",
        "lat": 55.8314, "lon": 37.6222, "region": "RU", "country": "RU", "kind": "country",
        "start_month": 1.0,
        "highlights": ["long winters", "snow cover", "Ural barrier"],
        "landmarks": [
            _lm("Moscow", 55.8314, 37.6222, 156, "Moscow"),
            _lm("Ufa", 54.7261, 55.9475, 150, "Ufa"),
            _lm("Taganay (Southern Urals)", 55.2597, 59.7925, 1178, "Taganay"),
            _lm("Chelyabinsk", 55.1547, 61.3758, 220, "Chelyabinsk"),
            _lm("Omsk (West Siberian Plain)", 54.9830, 73.3670, 87, "Omsk"),
        ],
        "climate": {
            "station": "Moscow", "elev_m": 147, "temp_c": 6.3, "annual_precip_mm": 713,
            "monthly_temp_c": [-6.2, -5.9, -0.7, 6.9, 13.6, 17.3, 19.7, 17.6, 11.9, 5.8, -0.5, -4.4],
            "monthly_precip_mm": [53, 44, 39, 37, 61, 78, 84, 78, 66, 70, 52, 51],
            "annual_max_c": 10.1, "annual_min_c": 2.6, "period": "1991-2020",
            "wind": "westerly",
            "note": ("starts in midwinter (January mean -6.2 °C at Moscow, -16.9 °C at Omsk); westerly air "
                     "masses are blocked by the Urals and Siberian cold penetrates south, Omsk gets only "
                     "418 mm a year"),
            "src": [WIKI + "Moscow", WIKI + "Omsk", WIKI + "Yekaterinburg", WIKI + "Ural_Mountains",
                    WIKI + "West_Siberian_Plain"],
        },
        "start": {"basis": "coldest_month", "ref_temp_c": -6.2},
        "wind_u": 5.0, "wind_from_deg": 270,
        "cloud_mix": {"stratus": 3.0, "altostratus": 3.0, "nimbostratus": 3.0, "stratocumulus": 3.0,
                      "altocumulus": 2.0, "cirrus": 2.0, "cumulus": 1.5, "cumulonimbus": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "united_kingdom": {
        "name": "United Kingdom",
        "summary": ("Atlantic depressions arrive from the south-west: Snowdonia and the Pennines wring out the "
                    "rain, layered cloud and drizzle are the norm, and the east of England sits in the rain shadow."),
        "transect_text": "53°N, Caernarfon Bay → Snowdonia → Peak District → The Wash (west → east)",
        "lat": 53.3814, "lon": -1.4914, "region": "GB", "country": "GB", "kind": "country",
        "start_month": 10.0,
        "highlights": ["Atlantic depressions", "drizzle", "rain shadow"],
        "landmarks": [
            _lm("Caernarfon Bay (Irish Sea)", 53.1, -4.55, 0, "Caernarfon_Bay"),
            _lm("Snowdon", 53.0685, -4.0762, 1085, "Snowdon"),
            _lm("Dee Estuary", 53.309, -3.157, 0, "Dee_Estuary"),
            _lm("Kinder Scout", 53.3847, -1.8739, 636, "Kinder_Scout"),
            _lm("Sheffield", 53.3814, -1.4914, 131, "Sheffield"),
            _lm("The Wash (North Sea)", 52.917, 0.25, 0, "The_Wash"),
        ],
        "climate": {
            "station": "Sheffield (Weston Park)", "elev_m": 131, "temp_c": 10.3, "annual_precip_mm": 832,
            "monthly_temp_c": [4.6, 4.9, 6.7, 9.2, 12.1, 15.0, 17.1, 16.7, 14.2, 10.7, 7.3, 5.0],
            "monthly_precip_mm": [75.7, 67.0, 59.5, 58.8, 54.5, 75.1, 62.2, 65.1, 63.5, 78.7, 84.7, 86.9],
            "annual_max_c": 13.7, "annual_min_c": 6.9, "period": "1991-2020",
            "wind": "south-westerly",
            "note": ("Atlantic depressions from the west or south-west; Snowdon's slopes get more than "
                     "5,100 mm a year, London about 650 mm and parts of Essex and Suffolk about 600 mm"),
            "src": [WIKI + "Sheffield", WIKI + "Climate_of_the_United_Kingdom", WIKI + "Snowdon"],
        },
        "start": {"basis": "annual", "ref_temp_c": 10.3},
        "wind_u": 6.0, "wind_from_deg": 225,
        "cloud_mix": {"stratocumulus": 4.0, "nimbostratus": 4.0, "stratus": 3.0, "altostratus": 3.0,
                      "cumulus": 3.0, "cirrus": 2.0, "altocumulus": 1.5, "cumulonimbus": 0.3},
    },
    # ------------------------------------------------------------------------------------------
    "central_europe": {
        "name": "Central Europe",
        "summary": ("From the North German Plain over the Harz to the Alpine foreland at Munich and on into the "
                    "Alps: westerlies, summer thunderstorms on the foreland, föhn on the north side of the "
                    "range, and snow on the high ground all year."),
        "transect_text": "53.5°N → 46.5°N, Hamburg → Harz → Munich → Alps → Bolzano (north → south)",
        "lat": 48.1375, "lon": 11.575, "region": "DE", "country": "DE", "kind": "place",
        "start_month": 7.0,
        "highlights": ["Alps", "föhn", "summer thunderstorms"],
        "landmarks": [
            _lm("Hamburg", 53.55, 10.0, 10.7, "Hamburg"),
            _lm("Brocken (Harz)", 51.8006, 10.6172, 1141, "Brocken"),
            _lm("Munich", 48.1375, 11.575, 520, "Munich"),
            _lm("Zugspitze", 47.4211, 10.9853, 2962, "Zugspitze"),
            _lm("Innsbruck", 47.2683, 11.3933, 574, "Innsbruck"),
            _lm("Wildspitze", 46.8853, 10.8672, 3770, "Wildspitze"),
            _lm("Bolzano", 46.5, 11.35, 262, "Bolzano"),
        ],
        "climate": {
            "station": "Munich", "elev_m": 520, "temp_c": 10.1, "annual_precip_mm": 940,
            "monthly_temp_c": [0.9, 1.9, 5.7, 10.2, 14.3, 17.8, 19.6, 19.4, 14.7, 10.1, 4.9, 1.8],
            "monthly_precip_mm": [51.9, 45.5, 61.2, 56.0, 107.0, 120.9, 118.9, 116.5, 78.1, 66.9, 58.4, 58.5],
            "annual_max_c": 14.5, "annual_min_c": 6.1, "period": "1991-2020",
            "wind": "westerly (across this line)",
            "note": ("the Alps are the first high barrier to the westerlies (Zugspitze 2,003 mm a year); "
                     "föhn warms Munich and Innsbruck, thunderstorms follow the foothills, Brocken in the "
                     "Harz has fog about 300 days a year"),
            "src": [WIKI + "Munich", WIKI + "Zugspitze", WIKI + "Innsbruck", WIKI + "Brocken",
                    WIKI + "Hamburg"],
        },
        "start": {"basis": "annual", "ref_temp_c": 10.1},
        "wind_u": 2.5, "wind_from_deg": 270,
        "cloud_mix": {"cumulus": 4.0, "stratocumulus": 3.0, "nimbostratus": 2.5, "stratus": 2.0,
                      "altostratus": 2.0, "altocumulus": 2.0, "cirrus": 2.0, "cumulonimbus": 1.5},
    },
    # ==========================================================================================
    # v2.3 world locations (read from the cited pages on 2026-10-09)
    # ==========================================================================================
    "oklahoma": {
        "name": "Oklahoma",
        "summary": ("Tornado Alley: Gulf moisture from the south meets dry air from Mexico and cold air from "
                    "Canada over the Great Plains, and the storms they breed peak from April to June."),
        "transect_text": "35-37°N, Black Mesa → Wichita Mountains → Oklahoma City → Tulsa → Cavanal Hill (west → east)",
        "lat": 35.46861, "lon": -97.52139, "region": "US-OK", "country": "US", "kind": "state",
        "start_month": 5.5,
        "highlights": ["Tornado Alley", "62 tornadoes a year", "Gulf moisture"],
        "landmarks": [
            _lm("Black Mesa (highest point)", 36.93188, -102.99785, 1516, "Black_Mesa_(Oklahoma)"),
            _lm("Mount Scott (Wichita Mountains)", 34.744562, -98.531909, 751, "Mount_Scott_(Oklahoma)"),
            _lm("Oklahoma City", 35.46861, -97.52139, 365, "Oklahoma_City"),
            _lm("Tulsa", 36.13139, -95.93722, 194, "Tulsa,_Oklahoma"),
            _lm("Cavanal Hill", 35.070931, -94.679393, 727, "Cavanal_Hill"),
        ],
        "climate": {
            "station": "Oklahoma City (Will Rogers World Airport)", "elev_m": 365, "temp_c": 15.6,
            "annual_precip_mm": 924,
            "monthly_temp_c": [3.4, 5.7, 10.7, 15.2, 20.1, 24.9, 27.6, 27.1, 22.6, 16.2, 9.6, 4.4],
            "monthly_precip_mm": [34, 36, 65, 91, 135, 114, 91, 91, 94, 84, 43, 45],
            "annual_max_c": 21.9, "annual_min_c": 9.3, "period": "1991-2020",
            "wind": "southerly",
            "note": ("warm humid air from the Gulf of Mexico, warm dry air from Mexico and the south-west and "
                     "cold dry air from Canada meet here; the highest-risk months are April to June and an "
                     "average of 62 tornadoes strike the state a year; rainfall is over 40 inches (up to "
                     "1,420 mm) in the south-east and under 17 inches (430 mm) in the Panhandle"),
            "src": [WIKI + "Oklahoma_City", WIKI + "Climate_of_Oklahoma", WIKI + "Tornado_Alley"],
        },
        "start": {"basis": "annual", "ref_temp_c": 15.6},
        "wind_u": -4.0, "wind_from_deg": 180,
        "cloud_mix": {"cumulus": 4.0, "cumulonimbus": 3.5, "altocumulus": 2.0, "stratocumulus": 2.0,
                      "cirrus": 2.0, "altostratus": 1.5, "nimbostratus": 1.2, "stratus": 1.0,
                      "supercell": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "florida": {
        "name": "Florida",
        "summary": ("A flat peninsula between two warm seas: a May-October rainy season of daily thunderstorms "
                    "(more than any other US state), sea breezes, and hurricanes in late summer."),
        "transect_text": "31°N → 26°N, Britton Hill → Tallahassee → Orlando → Miami (north-west → south-east)",
        "lat": 28.54222, "lon": -81.37917, "region": "US-FL", "country": "US", "kind": "state",
        "start_month": 8.5,
        "highlights": ["thunderstorm capital", "hurricane coast", "May-October rainy season"],
        "landmarks": [
            _lm("Britton Hill (highest point)", 30.9885139, -86.2818893, 105, "Britton_Hill"),
            _lm("Tallahassee", 30.44278, -84.21472, 38, "Tallahassee,_Florida"),
            _lm("Sugarloaf Mountain", 28.6494413, -81.7331317, 95, "Sugarloaf_Mountain_(Florida)"),
            _lm("Orlando", 28.54222, -81.37917, 27, "Orlando,_Florida"),
            _lm("Miami", 25.77417, -80.19361, 2.1, "Miami"),
        ],
        "climate": {
            "station": "Orlando International Airport", "elev_m": 27, "temp_c": 22.7, "annual_precip_mm": 1307,
            "monthly_temp_c": [15.9, 17.6, 19.6, 22.3, 25.2, 27.3, 28.1, 28.1, 27.2, 24.2, 20.1, 17.4],
            "monthly_precip_mm": [63, 52, 77, 66, 102, 204, 189, 195, 162, 88, 45, 63],
            "annual_max_c": 28.4, "annual_min_c": 17.1, "period": "1991-2020",
            "wind": "east and south-east in summer, north in winter",
            "note": ("a defined rainy season from May through October; some places report more than 90 "
                     "thunderstorm days a year and Florida has the highest lightning density in the US; "
                     "nearly three-fourths of the tropical cyclones that affect the state come between "
                     "August and October; snowfall is rare"),
            "src": [WIKI + "Orlando,_Florida", WIKI + "Climate_of_Florida", WIKI + "Miami"],
        },
        "start": {"basis": "annual", "ref_temp_c": 22.7},
        "wind_u": -3.5, "wind_from_deg": 120,
        "cloud_mix": {"cumulus": 5.0, "cumulonimbus": 4.0, "cirrus": 2.0, "altocumulus": 1.5,
                      "stratocumulus": 1.5, "nimbostratus": 1.5, "altostratus": 1.0, "stratus": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "colorado": {
        "name": "Colorado",
        "summary": ("The Rockies take the Pacific storms' moisture as snow, and the Front Range and the High "
                    "Plains beyond them sit in the rain shadow with 380-640 mm a year."),
        "transect_text": "39°N, Grand Junction → Mount Elbert → Denver → Limon → Burlington (west → east)",
        "lat": 39.74, "lon": -104.99, "region": "US-CO", "country": "US", "kind": "state",
        "start_month": 7.0,
        "highlights": ["Rocky Mountains", "rain shadow plains", "alpine snow"],
        "landmarks": [
            _lm("Grand Junction", 39.08778, -108.56806, 1416, "Grand_Junction,_Colorado"),
            _lm("Mount Elbert", 39.11778, -106.44528, 4400, "Mount_Elbert"),
            _lm("Denver", 39.74, -104.99, 1610, "Denver"),
            _lm("Limon", 39.26389, -103.69222, 1639, "Limon,_Colorado"),
            _lm("Burlington", 39.30417, -102.25778, 1270, "Burlington,_Colorado"),
        ],
        "climate": {
            "station": "Denver (Denver International Airport)", "elev_m": 1650, "temp_c": 10.6,
            "annual_precip_mm": 368,
            "monthly_temp_c": [-0.2, 0.4, 5.3, 8.8, 14.1, 20.1, 23.9, 22.7, 18.2, 10.6, 4.1, -0.4],
            "monthly_precip_mm": [9.7, 10, 22, 43, 55, 49, 54, 40, 34, 25, 16, 8.9],
            "annual_max_c": 18.4, "annual_min_c": 2.9, "period": "1991-2020",
            "wind": "westerly (Pacific storms, shielded by the Rockies)",
            "note": ("the high Rockies partly protect the Front Range from the prevailing storms that blow "
                     "in from the Pacific; precipitation on the plains averages 15 to 25 inches (380 to "
                     "640 mm) a year and much of the alpine snow melts by mid-August; station elevation "
                     "5,414 ft (Denver International Airport)"),
            "src": [WIKI + "Denver", WIKI + "Climate_of_Colorado"],
        },
        "start": {"basis": "annual", "ref_temp_c": 10.6},
        "wind_u": 5.0, "wind_from_deg": 270,
        "cloud_mix": {"cumulus": 4.0, "cirrus": 3.0, "altocumulus": 3.0, "cumulonimbus": 2.0,
                      "stratocumulus": 1.5, "altostratus": 1.5, "nimbostratus": 0.8, "stratus": 0.6},
    },
    # ------------------------------------------------------------------------------------------
    "california": {
        "name": "California",
        "summary": ("From Monterey Bay across the Central Valley and over the Sierra Nevada to Death Valley: "
                    "ocean winters in the west, deep snow on the crest, and a desert 86 m below sea level behind it."),
        "transect_text": "36-37°N, Monterey Bay → Fresno → Mount Whitney → Death Valley (west → east)",
        "lat": 36.75, "lon": -119.767, "region": "US-CA", "country": "US", "kind": "state",
        "start_month": 1.5,
        "highlights": ["Sierra Nevada", "Death Valley -86 m", "wet winters"],
        "landmarks": [
            _lm("Monterey Bay", 36.8, -121.9, 0, "Monterey_Bay"),
            _lm("Fresno (Central Valley)", 36.75, -119.767, 94, "Fresno,_California"),
            _lm("Mount Whitney", 36.578580925, -118.29199495, 4421, "Mount_Whitney"),
            _lm("Lone Pine (Owens Valley)", 36.60611, -118.07944, 1281, "Lone_Pine,_California"),
            _lm("Telescope Peak (Panamint Range)", 36.169815947, -117.089198336, 3366, "Telescope_Peak"),
            _lm("Badwater Basin", 36.25028, -116.82583, -86, "Badwater_Basin"),
        ],
        "climate": {
            "station": "Fresno Airport", "elev_m": 94, "temp_c": 18.3, "annual_precip_mm": 279,
            "monthly_temp_c": [8.9, 11.3, 14.1, 16.8, 21.2, 25.3, 28.6, 27.9, 25.1, 19.3, 12.8, 8.6],
            "monthly_precip_mm": [55, 49, 48, 26, 11, 6.1, 0.76, 0.0, 1.3, 14, 22, 45],
            "annual_max_c": 24.6, "annual_min_c": 12.1, "period": "1991-2020",
            "wind": "westerly",
            "note": ("the prevailing westerlies bring ocean moisture and the coast and valleys are cooler "
                     "and wetter in winter; Lake Tahoe and Mammoth Lakes ski resorts routinely receive over "
                     "10 feet (3.0 m) of snow in a season; Death Valley summer temperatures often pass 120 °F "
                     "(49 °C)"),
            "src": [WIKI + "Fresno,_California", WIKI + "Climate_of_California"],
        },
        "start": {"basis": "annual", "ref_temp_c": 18.3},
        "wind_u": 5.0, "wind_from_deg": 270,
        "cloud_mix": {"cirrus": 3.0, "stratocumulus": 3.0, "cumulus": 2.5, "altostratus": 2.5,
                      "altocumulus": 2.0, "stratus": 2.0, "nimbostratus": 1.5, "cumulonimbus": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "alaska": {
        "name": "Alaska",
        "summary": ("From the Gulf of Alaska coast at Seward up past Anchorage and Denali to Fairbanks: the coast "
                    "gets metres of snow and rain, the interior behind the Alaska Range is cold and dry."),
        "transect_text": "60°N → 65°N, Seward → Anchorage → Talkeetna → Denali → Fairbanks (south → north)",
        "lat": 61.21667, "lon": -149.89361, "region": "US-AK", "country": "US", "kind": "state",
        "start_month": 2.0,
        "highlights": ["Denali 6,190 m", "coast vs interior", "Knik wind"],
        "landmarks": [
            _lm("Seward (Gulf of Alaska)", 60.12444, -149.43333, 0, "Seward,_Alaska"),
            _lm("Anchorage", 61.21667, -149.89361, 31, "Anchorage,_Alaska"),
            _lm("Talkeetna", 62.32389, -150.10944, 106, "Talkeetna,_Alaska"),
            _lm("Denali", 63.06917, -151.00639, 6190, "Denali"),
            _lm("Fairbanks", 64.84361, -147.72306, 136, "Fairbanks,_Alaska"),
        ],
        "climate": {
            "station": "Anchorage (Ted Stevens Airport)", "elev_m": 31, "temp_c": 3.1, "annual_precip_mm": 417,
            "monthly_temp_c": [-8.4, -5.9, -3.4, 3.1, 8.9, 13.3, 15.3, 14.2, 9.6, 2.4, -4.7, -7.0],
            "monthly_precip_mm": [19, 22, 18, 11, 17, 26, 46, 74, 79, 46, 30, 29],
            "annual_max_c": 6.7, "annual_min_c": -0.6, "period": "1991-2020",
            "wind": "south-easterly (the Knik wind, mostly in winter)",
            "note": ("the northern Gulf of Alaska coast gets up to 150 inches (3,810 mm) of precipitation a "
                     "year, the interior often under 10 inches (254 mm); Anchorage gets around 75 inches of "
                     "snow; interior winter temperatures can fall below -50 °F (-45.6 °C)"),
            "src": [WIKI + "Anchorage,_Alaska", WIKI + "Climate_of_Alaska"],
        },
        "start": {"basis": "annual", "ref_temp_c": 3.1},
        "wind_u": 3.5, "wind_from_deg": 135,
        "cloud_mix": {"stratus": 3.0, "nimbostratus": 3.0, "altostratus": 3.0, "stratocumulus": 3.0,
                      "cirrus": 2.0, "cumulus": 1.5, "altocumulus": 1.5, "cumulonimbus": 0.2},
    },
    # ------------------------------------------------------------------------------------------
    "hawaii": {
        "name": "Hawaii",
        "summary": ("The Big Island under the north-east trade winds: Hilo on the windward side is drenched, "
                    "the summit of Mauna Kea is dry and snow-capped in winter, and Kona on the lee coast is "
                    "sheltered."),
        "transect_text": "19.4-20°N, Hilo → Kīlauea → Mauna Kea → Waimea → Kailua-Kona (east → west)",
        "lat": 19.70556, "lon": -155.08583, "region": "US-HI", "country": "US", "kind": "state",
        "start_month": 1.5,
        "highlights": ["trade winds", "windward rain", "summit snow"],
        "landmarks": [
            _lm("Hilo", 19.70556, -155.08583, 6.1, "Hilo,_Hawaii"),
            _lm("Kīlauea summit", 19.421097472, -155.286762433, 1247, "K%C4%ABlauea"),
            _lm("Mauna Kea", 19.82056, -155.46806, 4207.3, "Mauna_Kea"),
            _lm("Waimea", 20.02028, -155.66778, 810, "Waimea,_Hawaii_County,_Hawaii"),
            _lm("Kailua-Kona", 19.65, -155.99417, 2.1, "Kailua-Kona,_Hawaii"),
        ],
        "climate": {
            "station": "Hilo International Airport", "elev_m": 6.1, "temp_c": 23.3, "annual_precip_mm": 3058,
            "monthly_temp_c": [21.9, 21.8, 22.2, 22.5, 23.3, 24.0, 24.6, 24.8, 24.7, 24.3, 23.3, 22.3],
            "monthly_precip_mm": [200, 260, 322, 239, 178, 185, 235, 287, 221, 260, 366, 307],
            "annual_max_c": 27.0, "annual_min_c": 19.6, "period": "1991-2020",
            "wind": "north-east trade winds",
            "note": ("trade winds blow from the north-east 80 to 95 percent of the time from May through "
                     "September; windward slopes get far more rain than the leeward lowlands and the tall "
                     "mountains (Mauna Kea's summit averages from 7.4 inches, 188 mm, a year); the heaviest "
                     "rain falls from October to April and snow is common on the summits of Mauna Kea and "
                     "Mauna Loa in winter"),
            "src": [WIKI + "Hilo,_Hawaii", WIKI + "Climate_of_Hawaii"],
        },
        "start": {"basis": "annual", "ref_temp_c": 23.3},
        "wind_u": 4.5, "wind_from_deg": 45,
        "cloud_mix": {"cumulus": 5.0, "stratocumulus": 3.0, "nimbostratus": 2.0, "cirrus": 2.0,
                      "altocumulus": 2.0, "cumulonimbus": 1.0, "stratus": 1.0, "altostratus": 1.0},
    },
    # ------------------------------------------------------------------------------------------
    "japan": {
        "name": "Japan",
        "summary": ("Honshu from the Sea of Japan over the Japanese Alps and Mount Fuji to Tokyo: the winter "
                    "north-west monsoon dumps snow on the mountains facing the Sea of Japan and leaves the "
                    "Pacific side clear, while summer brings the rainy season and typhoons."),
        "transect_text": "36.8°N → 35.5°N, Toyama Bay → Mount Yari → Lake Suwa → Mount Fuji → Tokyo Bay (north-west → south-east)",
        "lat": 35.68972, "lon": 139.69222, "region": "JP", "country": "JP", "kind": "country",
        "start_month": 2.0,
        "highlights": ["winter monsoon snow", "rainy season", "typhoons"],
        "landmarks": [
            _lm("Toyama Bay (Sea of Japan)", 36.833, 137.167, 0, "Toyama_Bay"),
            _lm("Mount Yari", 36.34194, 137.6475, 3180, "Mount_Yari"),
            _lm("Lake Suwa", 36.04833, 138.08417, 759, "Lake_Suwa"),
            _lm("Mount Fuji", 35.36083, 138.7275, 3776, "Mount_Fuji"),
            _lm("Tokyo Bay", 35.5225, 139.91, 0, "Tokyo_Bay"),
        ],
        "climate": {
            "station": "Tokyo (Kitanomaru Park)", "elev_m": None, "temp_c": 15.8, "annual_precip_mm": 1598,
            "monthly_temp_c": [5.4, 6.1, 9.4, 14.3, 18.8, 21.9, 25.7, 26.9, 23.3, 18.0, 12.5, 7.7],
            "monthly_precip_mm": [59.7, 56.5, 116.0, 133.7, 139.7, 167.8, 156.2, 154.7, 224.9, 234.8, 96.3, 57.9],
            "annual_max_c": 20.3, "annual_min_c": 12.1, "period": "1991-2020",
            "wind": "north-west in the winter monsoon",
            "note": ("the north-west seasonal wind in winter brings heavy snowfall to the mountains facing the "
                     "Sea of Japan while the Pacific side has clear skies; the rainy season is mid-June to "
                     "mid-July and five or six typhoons pass over or near Japan every year from early "
                     "August to early October; the cited page gives no elevation for the Tokyo station"),
            "src": [WIKI + "Tokyo", WIKI + "Climate_of_Japan"],
        },
        "start": {"basis": "annual", "ref_temp_c": 15.8},
        "wind_u": 5.0, "wind_from_deg": 315,
        "cloud_mix": {"stratocumulus": 3.0, "nimbostratus": 3.0, "cumulus": 2.5, "altostratus": 2.5,
                      "stratus": 2.0, "cirrus": 2.0, "altocumulus": 2.0, "cumulonimbus": 1.2},
    },
    # ------------------------------------------------------------------------------------------
    "meghalaya": {
        "name": "Meghalaya (India)",
        "summary": ("The Khasi Hills lie east-west across the monsoon airflow from the Bay of Bengal: Cherrapunji "
                    "and Mawsynram, on the south face, are among the wettest places on Earth, with a short dry season."),
        "transect_text": "25-26°N, Sylhet plain → Cherrapunji → Mawsynram → Shillong → Guwahati (south → north)",
        "lat": 25.284, "lon": 91.721, "region": "IN", "country": "IN", "kind": "place",
        "start_month": 7.0,
        "highlights": ["wettest place", "Bay of Bengal monsoon", "11,000 mm a year"],
        "landmarks": [
            _lm("Sylhet", 24.9, 91.867, 35, "Sylhet"),
            _lm("Cherrapunji", 25.284, 91.721, 1430, "Cherrapunji"),
            _lm("Mawsynram", 25.29889, 91.58139, 1400, "Mawsynram"),
            _lm("Shillong (low end of the city's 1,495-1,965 m)", 25.58222, 91.89444, 1495, "Shillong"),
            _lm("Guwahati (low end of the city's 50-680 m)", 26.17222, 91.74583, 50, "Guwahati"),
        ],
        "climate": {
            "station": "Cherrapunji", "elev_m": 1430, "temp_c": 18.0, "annual_precip_mm": 11176,
            "monthly_temp_c": [12.3, 14.1, 17.0, 18.7, 19.9, 20.6, 20.6, 21.1, 21.0, 20.0, 16.9, 13.8],
            "monthly_precip_mm": [15.5, 53.7, 364.1, 792.4, 1297.1, 2608.1, 2665.4, 1768.7, 1089.7, 461.4,
                                  49.3, 10.2],
            "annual_max_c": 21.8, "annual_min_c": 13.7, "period": "1991-2020",
            "wind": "southerly: moist monsoon air moving north from the Bay of Bengal",
            "note": ("the east-west Khasi Hills stand directly in the path of the warm moist air from the Bay "
                     "of Bengal; Mawsynram's average is 11,872 mm a year and the 1991-2020 table gives "
                     "Cherrapunji 11,176 mm (its page text says 11,777 mm); December to February are dry, "
                     "under 30 mm a month"),
            "src": [WIKI + "Cherrapunji", WIKI + "Mawsynram"],
        },
        "start": {"basis": "annual", "ref_temp_c": 18.0},
        "wind_u": 5.0, "wind_from_deg": 180,
        "cloud_mix": {"nimbostratus": 4.0, "cumulonimbus": 4.0, "cumulus": 3.0, "stratocumulus": 2.0,
                      "stratus": 2.0, "altostratus": 2.0, "cirrus": 1.0, "altocumulus": 1.0},
    },
    # ------------------------------------------------------------------------------------------
    "amazon": {
        "name": "Amazon basin (Brazil)",
        "summary": ("Along the Amazon from the Pico da Neblina past Manaus to Belém: a rainforest that makes half "
                    "of its own rain, with a wet season and a short drier spell, hot all year."),
        "transect_text": "0-3°S, São Gabriel da Cachoeira → Pico da Neblina → Manaus → Santarém → Belém (west → east)",
        "lat": -3.1189, "lon": -60.0217, "region": "BR", "country": "BR", "kind": "place",
        "start_month": 2.0,
        "highlights": ["equatorial rainforest", "forest-made rain", "9 feet of rain a year"],
        "landmarks": [
            _lm("São Gabriel da Cachoeira", -0.11917, -67.08278, 92, "S%C3%A3o_Gabriel_da_Cachoeira"),
            _lm("Pico da Neblina", 0.80472, -66.00667, 2995.3, "Pico_da_Neblina"),
            _lm("Manaus", -3.1189, -60.0217, 92, "Manaus"),
            _lm("Parintins", -2.62778, -56.73583, 27, "Parintins"),
            _lm("Santarém", -2.43, -54.72, 51, "Santar%C3%A9m,_Par%C3%A1"),
            _lm("Belém", -1.45583, -48.50389, 10, "Bel%C3%A9m"),
        ],
        "climate": {
            "station": "Manaus", "elev_m": 92, "temp_c": 27.4, "annual_precip_mm": 2362,
            "monthly_temp_c": [26.6, 26.6, 26.6, 26.7, 27.0, 27.3, 27.5, 28.2, 28.6, 28.5, 28.0, 27.2],
            "monthly_precip_mm": [305.6, 296.8, 320.9, 331.0, 233.3, 117.2, 67.1, 56.1, 79.0, 113.9, 188.0,
                                  253.5],
            "annual_max_c": 32.3, "annual_min_c": 24.0, "period": "1991-2020",
            "wind": "south-east trade winds (the Southern Hemisphere rule)",
            "note": ("the Amazon receives about 9 feet of rain a year and half of the rainfall in the area is "
                     "produced by the forests; at Manaus April is the wettest month (331 mm) and August the "
                     "driest (56 mm); the trade winds blow from the south-east in the Southern Hemisphere"),
            "src": [WIKI + "Manaus", WIKI + "Amazon_rainforest", WIKI + "Trade_winds"],
        },
        "start": {"basis": "annual", "ref_temp_c": 27.4},
        "wind_u": -3.0, "wind_from_deg": 135,
        "cloud_mix": {"cumulus": 5.0, "cumulonimbus": 4.5, "nimbostratus": 3.0, "cirrus": 2.0,
                      "stratocumulus": 1.5, "altostratus": 1.5, "altocumulus": 1.0, "stratus": 1.0},
    },
    # ------------------------------------------------------------------------------------------
    "atacama": {
        "name": "Atacama (Chile)",
        "summary": ("From the foggy Pacific coast at Antofagasta over the driest desert on Earth to the Andean "
                    "volcano Licancabur: a two-sided rain shadow where some stations have never recorded rain."),
        "transect_text": "22.8-24.6°S, Paranal → Antofagasta → Calama → San Pedro de Atacama → Licancabur (south-west → north-east)",
        "lat": -22.467, "lon": -68.933, "region": "CL", "country": "CL", "kind": "place",
        "start_month": 7.0,
        "highlights": ["driest desert", "camanchaca fog", "two-sided rain shadow"],
        "landmarks": [
            _lm("Paranal Observatory", -24.627222, -70.404167, 2635, "Paranal_Observatory"),
            _lm("Antofagasta (Pacific coast)", -23.65, -70.4, 40, "Antofagasta"),
            _lm("Calama", -22.467, -68.933, 2260, "Calama,_Chile"),
            _lm("San Pedro de Atacama", -22.917, -68.2, 2407, "San_Pedro_de_Atacama"),
            _lm("Licancabur", -22.83389, -67.88361, 5916, "Licancabur"),
        ],
        "climate": {
            "station": "Calama", "elev_m": 2260, "temp_c": 13.3, "annual_precip_mm": 7,
            "monthly_temp_c": [15.8, 15.9, 15.1, 13.6, 11.9, 10.9, 10.6, 11.4, 12.3, 13.3, 14.0, 15.0],
            "monthly_precip_mm": [0.4, 1.4, 0.8, 0.2, 1.3, 0.6, 1.0, 0.8, 0.1, 0.0, 0.0, 0.0],
            "annual_max_c": 23.5, "annual_min_c": 3.1, "period": "1991-2020",
            "wind": "south-east trade winds (the Southern Hemisphere rule, almost across this line)",
            "note": ("commonly known as the driest place in the world: about 15 mm a year on average, 1 to 3 "
                     "mm in some places, and the Calama table gives 6.6 mm; the cool Humboldt Current and the "
                     "South Pacific anticyclone add to the Andes and the Chilean Coast Range as a two-sided "
                     "rain shadow; the coast gets the marine fog called camanchaca (Antofagasta 4.4 mm); the "
                     "wind word is the general trade-wind rule, no local measurement was cited"),
            "src": [WIKI + "Calama,_Chile", WIKI + "Atacama_Desert", WIKI + "Antofagasta",
                    WIKI + "San_Pedro_de_Atacama", WIKI + "Trade_winds"],
        },
        "start": {"basis": "annual", "ref_temp_c": 13.3},
        "wind_u": -1.5, "wind_from_deg": 135,
        "cloud_mix": {"cirrus": 4.0, "altocumulus": 3.0, "stratus": 3.0, "stratocumulus": 3.0,
                      "cumulus": 1.5, "altostratus": 1.0, "nimbostratus": 0.2, "cumulonimbus": 0.2},
    },
    # ------------------------------------------------------------------------------------------
    "sahara": {
        "name": "Sahara (Algeria)",
        "summary": ("From the Mediterranean coast at Algiers across the Saharan Atlas and the desert at In Salah "
                    "to the Hoggar mountains: under 20 mm of rain a year over most of it and the hottest "
                    "summers on Earth."),
        "transect_text": "37°N → 22.8°N, Algiers → Ghardaïa → In Salah → Mount Tahat → Tamanrasset (north → south)",
        "lat": 27.195, "lon": 2.48333, "region": "DZ", "country": "DZ", "kind": "place",
        "start_month": 7.0,
        "highlights": ["hyperarid", "Harmattan", "Hoggar mountains"],
        "landmarks": [
            _lm("Algiers (Mediterranean coast)", 36.7325, 3.08722, 2, "Algiers"),
            _lm("Ghardaïa", 32.483, 3.667, 572, "Gharda%C3%AFa"),
            _lm("In Salah", 27.195, 2.48333, 293, "In_Salah"),
            _lm("Mount Tahat (Hoggar)", 23.28889, 5.53361, 3003, "Mount_Tahat"),
            _lm("Tamanrasset", 22.78889, 5.52556, 1320, "Tamanrasset"),
        ],
        "climate": {
            "station": "In Salah", "elev_m": 293, "temp_c": 26.7, "annual_precip_mm": 14,
            "monthly_temp_c": [14.4, 17.1, 21.6, 26.2, 31.2, 35.7, 38.2, 37.5, 34.3, 28.1, 20.4, 15.8],
            "monthly_precip_mm": [2.9, 1.0, 1.4, 1.3, 0.8, 1.2, 0.1, 0.6, 1.4, 1.4, 1.4, 0.5],
            "annual_max_c": 34.5, "annual_min_c": 18.9, "period": "1991-2020",
            "wind": "north-easterly Harmattan trade wind (end of November to mid-March)",
            "note": ("most of the Sahara receives under 20 mm a year (the hyperarid core under 1 mm) and its "
                     "Mediterranean fringe 100 to 250 mm; In Salah's mean daily maximum is 46.1 °C in July; "
                     "Tamanrasset, high in the Hoggar at 1,320 m, gets 58.4 mm a year"),
            "src": [WIKI + "In_Salah", WIKI + "Sahara", WIKI + "Harmattan", WIKI + "Tamanrasset"],
        },
        "start": {"basis": "annual", "ref_temp_c": 26.7},
        "wind_u": 4.0, "wind_from_deg": 45,
        "cloud_mix": {"cirrus": 5.0, "altocumulus": 3.0, "cumulus": 1.5, "altostratus": 1.5,
                      "stratocumulus": 0.8, "cumulonimbus": 0.3, "stratus": 0.2, "nimbostratus": 0.1},
    },
    # ------------------------------------------------------------------------------------------
    "kenya": {
        "name": "Kenya and Kilimanjaro",
        "summary": ("Across the equator from the Tsavo plains past Kilimanjaro and Nairobi to Mount Kenya: two "
                    "rainy seasons, a cool highland climate, and glaciers on the equator above 4,650 m."),
        "transect_text": "3.4°S → 0°N, Voi → Mount Kilimanjaro → Nairobi → Mount Kenya → Meru (south → north)",
        "lat": -1.28639, "lon": 36.81722, "region": "KE", "country": "KE", "kind": "country",
        "start_month": 4.5,
        "highlights": ["equatorial glaciers", "two rainy seasons", "cool highlands"],
        "landmarks": [
            _lm("Voi", -3.39056, 38.57694, 580, "Voi,_Kenya"),
            _lm("Mount Kilimanjaro", -3.07583, 37.35333, 5895, "Mount_Kilimanjaro"),
            _lm("Nairobi", -1.28639, 36.81722, 1795, "Nairobi"),
            _lm("Mount Kenya", -0.15083, 37.3075, 5199, "Mount_Kenya"),
            _lm("Meru", 0.05, 37.65, 1600, "Meru,_Kenya"),
        ],
        "climate": {
            "station": "Nairobi (Jomo Kenyatta International Airport)", "elev_m": 1615, "temp_c": 19.8,
            "annual_precip_mm": 749,
            "monthly_temp_c": [20.5, 20.8, 21.6, 20.8, 19.8, 18.5, 17.7, 18.2, 19.4, 20.6, 19.9, 19.7],
            "monthly_precip_mm": [57.8, 36.2, 74.7, 130.2, 108.9, 28.8, 10.9, 18.5, 16.9, 50.0, 130.6, 85.4],
            "annual_max_c": 25.8, "annual_min_c": 14.0, "period": "1991-2020",
            "wind": "south-easterly most of the year, north-easterly in January and February",
            "note": ("on Mount Kenya the long rains (mid-March to June) bring about half of the annual rainfall "
                     "and the short rains (October to December) about a third; the glaciers reach no lower "
                     "than 4,650 m; the highlands are about 11 °C cooler than the lowlands; Meru's elevation "
                     "is 'approximately 1,600 m' on its page"),
            "src": [WIKI + "Nairobi", WIKI + "Mount_Kenya", WIKI + "Climate_of_Kenya"],
        },
        "start": {"basis": "annual", "ref_temp_c": 19.8},
        "wind_u": 3.0, "wind_from_deg": 135,
        "cloud_mix": {"cumulus": 4.0, "cumulonimbus": 2.5, "stratocumulus": 2.5, "altocumulus": 2.0,
                      "cirrus": 2.0, "nimbostratus": 1.5, "altostratus": 1.5, "stratus": 1.0},
    },
    # ------------------------------------------------------------------------------------------
    "australia": {
        "name": "Australia",
        "summary": ("From the Broken Hill outback over the western plains and the Great Dividing Range to Sydney "
                    "on the Tasman coast: westerly fronts, a mountain barrier that keeps rain off the interior, "
                    "and an arid heart."),
        "transect_text": "32-34°S, Broken Hill → Cobar → Dubbo → Blue Mountains → Botany Bay (west → east)",
        "lat": -33.86778, "lon": 151.21, "region": "AU", "country": "AU", "kind": "country",
        "start_month": 2.0,
        "highlights": ["Great Dividing Range", "rain shadow", "arid interior"],
        "landmarks": [
            _lm("Broken Hill", -31.95, 141.467, 315, "Broken_Hill"),
            _lm("Cobar", -31.49972, 145.83194, 260, "Cobar"),
            _lm("Dubbo", -32.25694, 148.60111, 275, "Dubbo"),
            _lm("Katoomba (Blue Mountains)", -33.71, 150.31, 1017, "Katoomba,_New_South_Wales"),
            _lm("Botany Bay", -33.99972, 151.23306, 0, "Botany_Bay"),
        ],
        "climate": {
            "station": "Sydney (Observatory Hill)", "elev_m": None, "temp_c": 18.8, "annual_precip_mm": 1150,
            "monthly_temp_c": [23.5, 23.4, 22.1, 19.5, 16.6, 14.2, 13.4, 14.5, 17.0, 18.9, 20.4, 22.1],
            "monthly_precip_mm": [91.1, 131.5, 117.5, 114.1, 100.8, 142.0, 80.3, 75.1, 63.4, 67.7, 90.6, 73.0],
            "annual_max_c": 22.8, "annual_min_c": 14.7, "period": "1991-2020",
            "wind": "westerly (the usual westerly winds and cold fronts of southern Australia)",
            "note": ("the Great Dividing Range limits rain moving into inland Australia: 600 to 1,200 mm a "
                     "year in the east of New South Wales, and 80% of the country gets under 600 mm and 50% "
                     "under 300 mm; the cited page gives no elevation for the Sydney station"),
            "src": [WIKI + "Sydney", WIKI + "Climate_of_Australia"],
        },
        "start": {"basis": "annual", "ref_temp_c": 18.8},
        "wind_u": 5.0, "wind_from_deg": 270,
        "cloud_mix": {"cumulus": 3.5, "cirrus": 3.0, "stratocumulus": 2.5, "altocumulus": 2.5,
                      "altostratus": 2.0, "nimbostratus": 1.5, "stratus": 1.0, "cumulonimbus": 1.0},
    },
    # ------------------------------------------------------------------------------------------
    "new_zealand": {
        "name": "New Zealand",
        "summary": ("The South Island's Southern Alps on the TranzAlpine line: westerly air drops metres of rain "
                    "on the west coast and crosses Arthur's Pass to a dry Canterbury, where the föhn warms it."),
        "transect_text": "42.6-43.5°S, Lake Brunner → Mount Rolleston → Arthur's Pass → Springfield → Christchurch (north-west → south-east)",
        "lat": -42.71556, "lon": 170.96806, "region": "NZ", "country": "NZ", "kind": "country",
        "start_month": 7.5,
        "highlights": ["Southern Alps", "west coast rain", "nor'wester föhn"],
        "landmarks": [
            _lm("Lake Brunner", -42.617, 171.45, 76, "Lake_Brunner"),
            _lm("Mount Rolleston", -42.9143194, 171.5174389, 2271, "Mount_Rolleston"),
            _lm("Arthur's Pass", -42.945389, 171.565667, 739, "Arthur%27s_Pass"),
            _lm("Springfield (railway station)", -43.33528, 171.9275, 117, "Springfield,_New_Zealand"),
            _lm("Christchurch", -43.5311, 172.6361, 6, "Christchurch"),
        ],
        "climate": {
            "station": "Hokitika Airport", "elev_m": None, "temp_c": 11.9, "annual_precip_mm": 2921,
            "monthly_temp_c": [15.8, 16.0, 14.7, 12.6, 10.6, 8.4, 7.6, 8.4, 9.9, 11.2, 12.6, 14.5],
            "monthly_precip_mm": [257.4, 191.7, 213.4, 244.9, 252.8, 261.2, 228.0, 246.9, 244.7, 284.8,
                                  222.9, 272.1],
            "annual_max_c": 15.8, "annual_min_c": 7.9, "period": "1991-2020",
            "wind": "westerly (air pushed from the west)",
            "note": ("Milford Sound receives over 6,700 mm a year while Alexandra in Central Otago gets only "
                     "about 300 mm, barely 100 km away; Christchurch gets 618 mm; the drier föhn wind "
                     "descending the eastern slopes of the Southern Alps can push eastern temperatures into "
                     "the high 30s °C; the cited page gives no elevation for the Hokitika station"),
            "src": [WIKI + "Hokitika", WIKI + "Climate_of_New_Zealand"],
        },
        "start": {"basis": "annual", "ref_temp_c": 11.9},
        "wind_u": 6.0, "wind_from_deg": 270,
        "cloud_mix": {"nimbostratus": 3.5, "stratocumulus": 3.0, "altostratus": 3.0, "cumulus": 2.5,
                      "cirrus": 2.5, "stratus": 2.0, "altocumulus": 2.0, "cumulonimbus": 0.5},
    },
    # ------------------------------------------------------------------------------------------
    "antarctica": {
        "name": "Antarctica (McMurdo Sound)",
        "summary": ("Ross Island and the Transantarctic Mountains around McMurdo Sound: the coast is the warmest "
                    "part of the continent at about -10 °C, the interior is a cold desert, and katabatic "
                    "winds pour off the ice."),
        "transect_text": "78°S → 77.5°S, Mount Lister → Mount Discovery → McMurdo Sound → McMurdo Station → Mount Erebus (west → east)",
        "lat": -77.846323, "lon": 166.668235, "region": "AQ", "country": "AQ", "kind": "place",
        "start_month": 12.0,
        "highlights": ["katabatic winds", "polar desert", "Mount Erebus"],
        "landmarks": [
            _lm("Mount Lister", -78.067, 162.683, 4025, "Mount_Lister"),
            _lm("Mount Discovery", -78.367, 165.017, 2681, "Mount_Discovery"),
            _lm("McMurdo Sound (sea level)", -77.5, 165.0, 0, "McMurdo_Sound"),
            _lm("McMurdo Station", -77.846323, 166.668235, 10, "McMurdo_Station"),
            _lm("Mount Erebus", -77.52972, 167.15333, 3792, "Mount_Erebus"),
        ],
        "climate": {
            "station": "McMurdo Station", "elev_m": 10, "temp_c": -17.3, "annual_precip_mm": 213,
            "monthly_temp_c": [-2.8, -8.8, -17.3, -20.9, -23.3, -22.9, -25.8, -27.4, -25.7, -19.4, -9.7, -3.5],
            "monthly_precip_mm": [16, 29, 15, 18, 21, 28, 17, 13, 10, 20, 12, 14],
            "annual_max_c": -14.2, "annual_min_c": -19.7, "period": "not stated (page title: extremes 1956-present)",
            "wind": "katabatic, off the high interior (the interior lies south of McMurdo)",
            "note": ("the coast averages around -10 °C in the warmest parts of Antarctica and about -55 °C "
                     "inland at Vostok; Antarctica averages 166 mm of precipitation a year, as little as "
                     "50 mm in the high interior; weather fronts rarely penetrate far inland because of the "
                     "katabatic winds; the wind bearing is inferred from where the interior lies; the cited "
                     "McMurdo table states no normals period"),
            "src": [WIKI + "McMurdo_Station", WIKI + "Climate_of_Antarctica", WIKI + "Katabatic_wind"],
        },
        "start": {"basis": "annual", "ref_temp_c": -17.3},
        "wind_u": 4.0, "wind_from_deg": 225,
        "cloud_mix": {"stratus": 3.0, "altostratus": 3.0, "stratocumulus": 2.5, "cirrus": 3.0,
                      "altocumulus": 2.0, "nimbostratus": 1.0, "cumulus": 0.5, "cumulonimbus": 0.05},
    },
}


# ----------------------------------------------------------------------------------------------------------
# Biomes (PROTOCOL.md "Preset biome (v2.3)"): how a region LOOKS and what the world generator builds there.
#
# Everything in this block is TUNING, not data: the colours, the tree densities and mixes, the pond rarity,
# the snow cover a frozen world opens with and the settlement plans are chosen to make each place look like
# itself with the game's existing art; none of it is a measured figure and none is sent as a fact. What the
# choices rest on is the cited climate in each preset (temperature, precipitation, humidity, the notes and
# the landmark list): `desert` where the cited rainfall is under about 400 mm and the humidity below 0.3,
# `polar` where the annual mean is far below freezing, `tundra` for the cold subarctic (Alaska: annual mean
# 3.1 C, interior under 254 mm), `alpine` for the high plateau and the Rockies, `tropical` for the warm wet
# places, `savanna` for the warm seasonally dry grasslands (Kenya, the Australian west), `mediterranean`
# for the dry-summer coast (California), `temperate` for the rest. The named places below are landmarks
# that are real towns, cities or stations (peaks, bays and lakes stay landmarks): the settlement that is
# generated on or nearest to such a landmark takes its name, and a place gets the largest settlement kind
# up to the one listed that still fits between its neighbours and the summits (the world is 3840 px for a
# few hundred km, so a "city" is a symbol: McMurdo is one small station, In Salah a small adobe town).
#
#   look      ground / soil / grass / water colours (grass None = bare ground: sand, rock, ice)
#   trees     0..1 density against a temperate world (22 trees per 1280 px)
#   tree_mix  relative weights of the tree variants; high_tree is the variant above the tree line start
#   snow      ground snow cover the world opens with wherever the surface is at or above the freezing level
#   ponds     rarity (1 = today's count, 0 = none); lone = scattered farmsteads (1 = today's)
#   plan      max settlement kind, density of the extra (unnamed) settlements and the building style
# ----------------------------------------------------------------------------------------------------------
BIOME_IDS = ("temperate", "mediterranean", "tropical", "savanna", "desert", "alpine", "tundra", "polar")
SETTLEMENT_RANK = {"hamlet": 0, "village": 1, "town": 2, "city": 3}

_LOOK = {
    "temperate": {"ground": [110, 146, 84], "soil": [104, 78, 54], "grass": [104, 160, 82], "water": [70, 120, 170]},
    "mediterranean": {"ground": [166, 154, 90], "soil": [124, 92, 62], "grass": [150, 152, 84], "water": [70, 130, 170]},
    "tropical": {"ground": [70, 140, 64], "soil": [96, 70, 48], "grass": [60, 150, 60], "water": [60, 130, 150]},
    "savanna": {"ground": [186, 164, 92], "soil": [130, 96, 60], "grass": [176, 168, 88], "water": [84, 120, 130]},
    "desert": {"ground": [194, 160, 104], "soil": [150, 112, 70], "grass": None, "water": [70, 120, 150]},
    "alpine": {"ground": [128, 132, 112], "soil": [96, 88, 76], "grass": [120, 140, 100], "water": [80, 130, 170]},
    "tundra": {"ground": [140, 138, 116], "soil": [96, 84, 72], "grass": [130, 140, 110], "water": [82, 118, 140]},
    "polar": {"ground": [236, 242, 248], "soil": [178, 196, 214], "grass": None, "water": [140, 180, 210]},
}


def _place(landmark, kind, name=None, style="inherit", min_kind=None, at=None, x_frac=None, station=False):
    d = {"landmark": landmark, "kind": kind}
    if station:
        d["station"] = True            # the climate station's town: always placed (see settlements._plan_biome)
    if min_kind:
        d["min"] = min_kind
    if name:
        d["name"] = name
    if style != "inherit":
        d["style"] = style
    if at:
        d["at"] = at
    if x_frac is not None:
        d["x_frac"] = x_frac
    return d


# Tuning (v2.3 polish): the viewer draws barn, windmill and church in one temperate look, so only the temperate and
# mediterranean biomes keep them; elsewhere their slots become houses of the biome's variant (or a water tower).
_AVOID_KINDS = {"temperate": (), "mediterranean": ()}
_ALL_AVOID = ("barn", "windmill", "church")
# Road plan per biome (tuning): nothing crosses the ice of a polar world (a short street at the station, a
# pickup or a van or two); a desert keeps its highway between the real towns but with light traffic.
_ROADS = {"polar": {"highway": False, "traffic": 0.3, "mix": ("pickup", "van")},
          "desert": {"highway": True, "traffic": 0.35, "mix": None}}


def _bio(bid, trees, mix, snow, ponds=1.0, lone=1.0, density=0.7, max_kind="city", style=None, places=(),
         high_tree="pine", frozen=False, look=None, tree_line=0.27):
    if bid in ("tropical", "savanna") and high_tree == "pine":
        high_tree = "shrub"            # tuning: no pine on tropical or savanna high ground
    lk = dict(_LOOK[bid])
    if look:
        lk.update(look)
    return {"id": bid, "ground": lk["ground"], "soil": lk["soil"], "grass": lk["grass"], "water": lk["water"],
            "trees": trees, "snow_start": snow, "frozen_water": frozen,
            "tree_mix": mix, "high_tree": high_tree, "tree_line": tree_line, "ponds": ponds, "lone": lone,
            "settlements": {"max": max_kind, "density": density, "style": style}, "places": list(places),
            "avoid": _AVOID_KINDS.get(bid, _ALL_AVOID),
            "roads": _ROADS.get(bid, {"highway": True, "traffic": 1.0, "mix": None})}


_BIOMES = {
    "washington": _bio("temperate", 1.0, {"pine": 0.5, "oak": 0.1, "birch": 0.15, "bush": 0.25}, 0.6, density=0.6,
                       places=[_place("Seattle-Tacoma Airport", "city", "Seattle", station=True),
                               _place("Ellensburg", "town"), _place("Moses Lake", "village")]),
    "philippines": _bio("tropical", 0.9, {"palm": 0.4, "oak": 0.25, "bush": 0.2, "birch": 0.05}, 0.5,
                        high_tree="pine", density=0.7, style="hut",
                        places=[_place("Baguio", "town", style=None), _place("Bayombong", "village"),
                                _place("Tuguegarao (Cagayan Valley)", "city", style=None, station=True)]),
    "tibet": _bio("alpine", 0.12, {"pine": 0.25, "shrub": 0.6, "bush": 0.15}, 0.9, ponds=0.5, lone=0.4,
                  density=0.3, max_kind="town", high_tree="shrub",
                  look={"ground": [150, 140, 118], "soil": [110, 98, 84], "grass": [156, 150, 110]},
                  places=[_place("Shigatse", "town"), _place("Nagqu", "town", min_kind="hamlet", station=True)]),
    "russia": _bio("temperate", 1.0, {"pine": 0.4, "birch": 0.4, "oak": 0.05, "bush": 0.15}, 0.9, density=0.6,
                   places=[_place("Moscow", "city", station=True), _place("Ufa", "town"), _place("Chelyabinsk", "town")]),
    "united_kingdom": _bio("temperate", 0.8, {"oak": 0.35, "birch": 0.2, "pine": 0.1, "bush": 0.35}, 0.6,
                           density=0.7, places=[_place("Sheffield", "city", station=True)],
                           look={"ground": [96, 146, 80], "grass": [92, 158, 76]}),
    "central_europe": _bio("temperate", 1.0, {"pine": 0.35, "oak": 0.25, "birch": 0.15, "bush": 0.25}, 0.7,
                           density=0.7, places=[_place("Hamburg", "city", min_kind="village"), _place("Munich", "city", station=True)]),
    "oklahoma": _bio("temperate", 0.35, {"oak": 0.4, "bush": 0.45, "dead": 0.15}, 0.5, ponds=0.6, density=0.5,
                     look={"ground": [150, 150, 84], "grass": [150, 156, 80]},
                     places=[_place("Oklahoma City", "city", station=True)]),
    "florida": _bio("tropical", 0.8, {"palm": 0.45, "oak": 0.3, "bush": 0.25}, 0.3, ponds=1.5, density=0.6,
                    places=[_place("Tallahassee", "town"), _place("Orlando", "city", station=True)]),
    "colorado": _bio("alpine", 0.3, {"pine": 0.5, "shrub": 0.35, "bush": 0.15}, 0.8, ponds=0.5, density=0.5,
                     places=[_place("Grand Junction", "town"), _place("Denver", "city", station=True),
                             _place("Limon", "village")]),
    "california": _bio("mediterranean", 0.45, {"oak": 0.4, "bush": 0.2, "shrub": 0.3, "palm": 0.05, "pine": 0.05},
                       0.5, ponds=0.5, density=0.5,
                       places=[_place("Fresno (Central Valley)", "city", station=True),
                               _place("Lone Pine (Owens Valley)", "village")]),
    "alaska": _bio("tundra", 0.3, {"pine": 0.5, "birch": 0.2, "shrub": 0.3}, 0.9, ponds=0.8, density=0.3,
                   max_kind="city", high_tree="shrub",
                   places=[_place("Anchorage", "city", station=True), _place("Talkeetna", "hamlet"), _place("Fairbanks", "town")]),
    "hawaii": _bio("tropical", 0.6, {"palm": 0.6, "oak": 0.15, "bush": 0.25}, 0.7, density=0.4, max_kind="town",
                   high_tree="shrub", style="hut",
                   places=[_place("Hilo", "town", style=None, station=True), _place("Waimea", "village"),
                           _place("Kailua-Kona", "town", style=None)]),
    "japan": _bio("temperate", 0.9, {"pine": 0.35, "oak": 0.15, "birch": 0.1, "bush": 0.4}, 0.7, density=0.6,
                  places=[_place("Tokyo Bay", "city", "Tokyo", station=True)]),
    "meghalaya": _bio("tropical", 1.0, {"palm": 0.2, "oak": 0.55, "bush": 0.25}, 0.3, ponds=1.2,
                      density=0.5, max_kind="town", style="hut",
                      places=[_place("Sylhet", "town"), _place("Cherrapunji", "town", station=True),
                              _place("Shillong (low end of the city's 1,495-1,965 m)", "town", style=None)]),
    "amazon": _bio("tropical", 1.0, {"palm": 0.25, "oak": 0.55, "bush": 0.2}, 0.3, ponds=1.5, density=0.4,
                   max_kind="city", style="hut",
                   places=[_place("Manaus", "city", style=None, station=True), _place("Santarém", "town", style=None),
                           _place("Belém", "town", style=None)]),
    "atacama": _bio("desert", 0.02, {"cactus": 0.6, "shrub": 0.3, "dead": 0.1}, 0.5, ponds=0.0, lone=0.0,
                    density=0.0, max_kind="town", style="adobe", high_tree="dead",
                    look={"ground": [188, 150, 112], "soil": [140, 100, 72]},
                    places=[_place("Antofagasta (Pacific coast)", "town"),
                            _place("Calama", "town", station=True)]),
    "sahara": _bio("desert", 0.05, {"palm": 0.35, "acacia": 0.2, "shrub": 0.3, "dead": 0.15}, 0.3, ponds=0.0,
                   lone=0.2, density=0.0, max_kind="town", style="adobe", high_tree="dead",
                   places=[_place("Algiers (Mediterranean coast)", "town"),
                           _place("Ghardaïa", "town"), _place("In Salah", "town", station=True)]),
    "kenya": _bio("savanna", 0.35, {"acacia": 0.7, "shrub": 0.2, "bush": 0.1}, 0.6, ponds=0.7, density=0.4,
                  max_kind="city", style="hut", high_tree="pine",
                  places=[_place("Voi", "village"), _place("Nairobi", "city", style=None, station=True)]),
    "australia": _bio("savanna", 0.4, {"acacia": 0.4, "oak": 0.2, "shrub": 0.3, "dead": 0.1}, 0.4, ponds=0.5,
                      lone=0.8, density=0.3, max_kind="city",
                      look={"ground": [190, 150, 96], "soil": [134, 96, 62], "grass": [172, 160, 86]},
                      places=[_place("Broken Hill", "town"), _place("Cobar", "village"),
                              _place("Botany Bay", "city", "Sydney", station=True)]),
    "new_zealand": _bio("temperate", 0.9, {"pine": 0.3, "oak": 0.15, "birch": 0.1, "bush": 0.45}, 0.7,
                        density=0.5, max_kind="city",
                        places=[_place("Lake Brunner", "village", "Hokitika", x_frac=0.03, station=True),
                                _place("Arthur's Pass", "hamlet"), _place("Christchurch", "city")]),
    "antarctica": _bio("polar", 0.0, {"dead": 1.0}, 1.0, ponds=0.3, lone=0.0, density=0.0, max_kind="hamlet",
                       style="station", frozen=True, high_tree="dead",
                       places=[_place("McMurdo Station", "hamlet", station=True)]),
}


def _town_name(landmark: str) -> str:
    """A landmark's town name: the landmark name without its parenthesis ("Fresno (Central Valley)")."""
    return landmark.split(" (")[0].strip()


PRESET_IDS = tuple(PRESETS)

# Direction of the prevailing wind ALONG the transect (+1 = towards increasing distance = to the right
# on screen). It follows from the direction word (`wind_from_deg`) and the bearing of the line first ->
# last landmark; `wind_u` carries the sign (negative = the real wind blows towards the start of the line:
# oklahoma, florida, amazon, atacama). `wind_along` (cosine of the angle between the two) is computed
# below; test_presets checks sign(wind_u) == sign(wind_along) and lists the nearly crosswise lines.

# standard lapse sources cited for every preset
_COMMON_SRC = [_LAPSE_SRC]


def _project(landmarks: list) -> list:
    """Distance (km) of each landmark along the straight line first -> last (equirectangular)."""
    lat0 = math.radians((landmarks[0]["lat"] + landmarks[-1]["lat"]) / 2.0)
    kx = 111.32 * math.cos(lat0)
    ky = 110.57

    def xy(lm):
        return ((lm["lon"] - landmarks[0]["lon"]) * kx, (lm["lat"] - landmarks[0]["lat"]) * ky)

    ex, ey = xy(landmarks[-1])
    length = math.hypot(ex, ey)
    ux, uy = ex / length, ey / length
    out = []
    for lm in landmarks:
        px, py = xy(lm)
        out.append(round(px * ux + py * uy, 1))
    return out


def _bearing_deg(landmarks: list) -> float:
    """Compass bearing (degrees clockwise from north) of the line first -> last landmark."""
    a, b = landmarks[0], landmarks[-1]
    lat0 = math.radians((a["lat"] + b["lat"]) / 2.0)
    ex = (b["lon"] - a["lon"]) * 111.32 * math.cos(lat0)
    ey = (b["lat"] - a["lat"]) * 110.57
    return math.degrees(math.atan2(ex, ey)) % 360.0


def humidity_for(annual_precip_mm: float) -> float:
    """Game tuning (0..1): water vapour that a climate with this much rain starts with."""
    return round(0.15 + 0.80 * math.sqrt(min(1.0, annual_precip_mm / 2500.0)), 2)


def aerosol_for(humidity: float) -> float:
    """Game tuning (0..1): condensation nuclei (dust, haze) rise as the air dries out."""
    return round(0.10 + 0.45 * (1.0 - humidity) ** 2, 2)


def _finish(pid: str, p: dict) -> dict:
    p["id"] = pid
    lms = p["landmarks"]
    for lm, km in zip(lms, _project(lms)):
        lm["km"] = km
    p["span_km"] = lms[-1]["km"]
    lo = min(lm["elev_m"] for lm in lms)
    hi = max(lm["elev_m"] for lm in lms)
    p["min_elev_m"] = lo
    p["max_elev_m"] = hi
    c = p["climate"]
    st = p["start"]
    elev = c["elev_m"]
    drop_km = ((elev - lo) / 1000.0) if elev is not None else 0.0
    c["lapse_c"] = round(LAPSE_C_PER_KM * drop_km, 2)
    st["surface_temp_c"] = round(st["ref_temp_c"] + LAPSE_C_PER_KM * drop_km, 1)
    c["diurnal_range_c"] = round(c["annual_max_c"] - c["annual_min_c"], 1)
    p["humidity"] = humidity_for(c["annual_precip_mm"])
    p["aerosol"] = aerosol_for(p["humidity"])
    bearing = _bearing_deg(lms)
    p["bearing_deg"] = round(bearing, 1)
    toward = math.radians((p["wind_from_deg"] + 180.0) % 360.0)
    p["wind_along"] = round(math.cos(toward - math.radians(bearing)), 2)
    srcs: list = []
    for lm in lms:
        if lm["src"] not in srcs:
            srcs.append(lm["src"])
    for u in c["src"] + _COMMON_SRC:
        if u not in srcs:
            srcs.append(u)
    p["sources"] = srcs
    p["biome"] = _BIOMES[pid]
    return p


for _pid, _p in PRESETS.items():
    _finish(_pid, _p)


def get_preset(pid: str) -> dict:
    try:
        return PRESETS[pid]
    except KeyError:
        raise ValueError(f"unknown preset {pid!r}; choose one of {', '.join(PRESET_IDS)}") from None


# -- building ------------------------------------------------------------------------------------
def build_terrain(preset: dict, rng, w: int, h: int):
    """Terrain for `preset` at world size w x h. Returns (terrain, m_per_px, landmark x list)."""
    lms = preset["landmarks"]
    return Terrain.from_profile(rng, w, h, [lm["km"] for lm in lms], [lm["elev_m"] for lm in lms],
                                preset["span_km"])


def apply_climate(preset: dict, world) -> None:
    """Start the world from the preset's climate (temperature at the lowest ground, prevailing wind)."""
    world.surface_temp_c = float(preset["start"]["surface_temp_c"])
    world.prevailing_u = float(preset["wind_u"])
    world.prevailing_v = 0.0


def climate_block(preset) -> dict:
    """What the season model needs from a preset (Sim Core 1 calls this from `Simulation._reset`).

    `monthly_temp_c` are the station's twelve monthly means carried down to the LOWEST ground of the profile
    with the lapse rate (the same correction `start.surface_temp_c` gets), `diurnal_range_c` is the mean
    daily maximum minus the mean daily minimum, `lat` the station's latitude (hemisphere), `start_month` where
    a sandbox opens, `humidity` / `aerosol` the 0..1 tuning derived from the annual precipitation.
    `preset` is a preset dict or an id."""
    p = get_preset(preset) if isinstance(preset, str) else preset
    c = p["climate"]
    lapse = float(c["lapse_c"])
    return {
        "monthly_temp_c": [round(t + lapse, 1) for t in c["monthly_temp_c"]],
        "diurnal_range_c": c["diurnal_range_c"],
        "lat": p["lat"],
        "start_month": float(p["start_month"]),
        "humidity": p["humidity"],
        "aerosol": p["aerosol"],
    }


def biome_block(preset: dict, frozen: bool | None = None) -> dict:
    """`hello.preset.biome` (PROTOCOL.md "Preset biome (v2.3)"): the wire keys only. `frozen` overrides
    `frozen_water` (the simulation passes True when the world opens at or below freezing)."""
    b = preset["biome"]
    return {"id": b["id"], "ground": list(b["ground"]), "soil": list(b["soil"]),
            "grass": None if b["grass"] is None else list(b["grass"]), "water": list(b["water"]),
            "trees": b["trees"], "snow_start": b["snow_start"],
            "frozen_water": bool(b["frozen_water"] or frozen)}


def settlement_plan(preset: dict, w: int) -> dict:
    """What the world generator needs to build the places of a preset at world width `w`: the named
    settlements (x of their landmark, kind, name, style) and the plan for everything else."""
    b = preset["biome"]
    lms = preset["landmarks"]
    xs = profile_x([lm["km"] for lm in lms], preset["span_km"], w)
    by_name = {lm["name"]: x for lm, x in zip(lms, xs)}
    named = []
    for pl in b["places"]:
        x = pl["x_frac"] * w if "x_frac" in pl else by_name[pl.get("at", pl["landmark"])]
        lm = next(m for m in lms if m["name"] == pl.get("at", pl["landmark"]))
        rank = SETTLEMENT_RANK[pl["kind"]]
        floor = pl.get("min") or next(k for k, r in SETTLEMENT_RANK.items() if r == max(0, rank - 1))
        named.append({"x": float(x), "kind": pl["kind"], "min": floor, "landmark": pl["landmark"],
                      "top": lm["elev_m"] == preset["max_elev_m"], "station": bool(pl.get("station")),
                      "name": pl.get("name") or _town_name(pl["landmark"]),
                      "style": pl["style"] if "style" in pl else b["settlements"]["style"]})
    st = b["settlements"]
    return {"named": named, "max": st["max"], "density": st["density"], "style": st["style"],
            "ponds": b["ponds"], "lone": b["lone"], "avoid": tuple(b["avoid"])}


def hello_block(preset: dict, w: int, h: int, frozen: bool | None = None) -> dict:
    """The `hello.preset` object for a world of width w and height h."""
    lms = preset["landmarks"]
    span = preset["span_km"]
    c = preset["climate"]
    return {
        "id": preset["id"], "name": preset["name"], "summary": preset["summary"],
        "transect": preset["transect_text"], "span_km": int(round(span)),
        "max_elev_m": preset["max_elev_m"],
        "m_per_px": round(profile_m_per_px(preset["max_elev_m"] - preset["min_elev_m"], h), 1),
        "lat": preset["lat"], "lon": preset["lon"], "region": preset["region"], "country": preset["country"],
        "climate": {"station": c["station"], "temp_c": c["temp_c"],
                    "annual_precip_mm": c["annual_precip_mm"], "wind": c["wind"], "note": c["note"],
                    "monthly_temp_c": list(c["monthly_temp_c"]), "monthly_precip_mm": list(c["monthly_precip_mm"]),
                    "diurnal_range_c": c["diurnal_range_c"], "lat": preset["lat"]},
        "landmarks": [{"x": x, "name": lm["name"], "elev_m": lm["elev_m"]}
                      for lm, x in zip(lms, profile_x([lm["km"] for lm in lms], span, w))],
        "sources": list(preset["sources"]),
        "biome": biome_block(preset, frozen),
    }


def world_entry(preset: dict) -> dict:
    """One map dot for `hello.world_map.locations` and `assets/world/presets.json`."""
    c = preset["climate"]
    return {
        "id": preset["id"], "name": preset["name"], "lat": preset["lat"], "lon": preset["lon"],
        "region": preset["region"], "country": preset["country"], "kind": preset["kind"],
        "summary": preset["summary"], "station": c["station"],
        "annual_temp_c": c["temp_c"], "annual_precip_mm": c["annual_precip_mm"],
        "monthly_temp_c": list(c["monthly_temp_c"]), "highlights": list(preset["highlights"]),
        "biome": preset["biome"]["id"],
    }


def world_entries() -> list:
    return [world_entry(PRESETS[pid]) for pid in PRESET_IDS]


def region_map() -> dict:
    """Region code -> preset id (a click on that region opens that preset)."""
    return {PRESETS[pid]["region"]: pid for pid in PRESET_IDS}
