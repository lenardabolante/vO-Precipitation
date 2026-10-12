"""Shared validators for PROTOCOL.md v2 messages (used by several tests)."""
import json
import math

TERRAIN_KINDS = {"flat", "rolling", "hilly", "mountainous", "preset"}
MODES = {"sandbox", "showcase", "hurricane", "world"}
# every id in sim/presets.py (the six v2.2 regions plus the v2.3 world locations)
import _pathfix  # noqa: F401,E402
from presets import PRESET_IDS as _ALL_PRESET_IDS  # noqa: E402
PRESET_IDS = set(_ALL_PRESET_IDS)
LESSON_KEYS = ("title", "altitude", "formation", "life", "precipitation", "wind", "ending")
SETTLEMENT_KINDS = {"hamlet", "village", "town", "city"}
SCENERY_KINDS = {"mountain", "hill", "tree", "pond", "puddle", "snowpack"}
BUILDING_KINDS = {"house", "barn", "church", "windmill", "shop", "apartment", "office",
                  "skyscraper", "factory", "water_tower", "streetlamp"}
OBJECT_KINDS = SCENERY_KINDS | BUILDING_KINDS
HOUSE_VARIANTS = {"cottage", "cabin", "farmhouse", "townhouse", "bungalow", "manor",
                  "adobe", "hut", "station"}                       # v2.3 biomes add the last three
TREE_VARIANTS = {"pine", "oak", "birch", "bush", "palm", "cactus", "acacia", "shrub", "dead"}   # v2.3 adds five
PLAIN_HOUSE_VARIANTS = HOUSE_VARIANTS - {"adobe", "hut", "station"}      # what a world without a preset builds
PLAIN_TREE_VARIANTS = TREE_VARIANTS - {"palm", "cactus", "acacia", "shrub", "dead"}
BIOME_IDS = {"temperate", "mediterranean", "tropical", "savanna", "desert", "alpine", "tundra", "polar"}
BASE_CLOUD_TYPES = {"cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus",
                    "altocumulus", "stratus", "cirrus"}
CLOUD_TYPES = BASE_CLOUD_TYPES | {"supercell"}           # v2.3: nine types
SUPERCELL_KEYS = ("rotation", "wall", "overshoot", "meso_x", "hook")
TOUR_IDS = ["towering", "mesocyclone", "mature", "wall_cloud", "funnel", "tornado", "rope_out", "decay"]
TORNADO_STAGES = ("funnel", "touchdown", "mature", "roping")
CAR_VARIANTS = {"sedan", "hatchback", "pickup", "van", "truck", "bus"}
CAR_STATES = {"driving", "stopped", "tossed", "wrecked"}
MAX_PUFFS_SUPERCELL = 56
STAGES = ["forming", "growing", "mature", "precipitating", "dissipating"]
PRECIP_TYPES = {"none", "rain", "snow", "mixed", "graupel"}
ARROW_KINDS = {"updraft", "downdraft", "wind", "fall", "growth"}
SEASONS = {"winter", "spring", "summer", "autumn"}
TYPES9 = {"cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus", "altocumulus",
          "stratus", "cirrus", "supercell"}
MAX_PUFFS_V23 = 72
DRAIN_MODES = {"small", "normal", "large", "random", "custom"}      # v2.4
DRAIN_CLASSES = {"small", "normal", "large"}
FLOOD_STATES = ("dry", "wet", "ponding", "flooded", "overflowing")
WATER_KINDS = {"pool", "street"}
OVERFLOW_AT = 9.95                       # `overflowing` from this depth: 10 - 0.05 on a v2.4 stream; validate_hello sets 8.0 on v2.4.1


def is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def ok(cond, msg):
    if not cond:
        raise AssertionError(msg)


def validate_preset(p, w):
    """hello.preset (PROTOCOL.md v2.2)."""
    for k in ("id", "name", "summary", "transect", "span_km", "max_elev_m", "m_per_px", "climate",
              "landmarks", "sources"):
        ok(k in p, f"preset.{k}")
    ok(p["id"] in PRESET_IDS, "preset id")
    ok(all(isinstance(p[k], str) and p[k] for k in ("name", "summary", "transect")), "preset texts")
    ok(is_num(p["span_km"]) and p["span_km"] > 0 and is_num(p["max_elev_m"]) and p["max_elev_m"] > 0, "preset numbers")
    ok(is_num(p["m_per_px"]) and p["m_per_px"] > 0, "m_per_px")
    c = p["climate"]
    for k in ("station", "temp_c", "annual_precip_mm", "wind", "note"):
        ok(k in c, f"climate.{k}")
    ok(isinstance(c["station"], str) and isinstance(c["wind"], str) and isinstance(c["note"], str), "climate texts")
    ok(is_num(c["temp_c"]) and is_num(c["annual_precip_mm"]) and c["annual_precip_mm"] > 0, "climate numbers")
    if "biome" in p:                # v2.3 "Preset biome"
        b = p["biome"]
        ok(set(b) == {"id", "ground", "soil", "grass", "water", "trees", "snow_start", "frozen_water"}, "biome keys")
        ok(b["id"] in BIOME_IDS, "biome id")
        for k in ("ground", "soil", "water"):
            ok(len(b[k]) == 3 and all(isinstance(v, int) and 0 <= v <= 255 for v in b[k]), f"biome {k} rgb")
        ok(b["grass"] is None or (len(b["grass"]) == 3 and all(isinstance(v, int) and 0 <= v <= 255 for v in b["grass"])),
           "biome grass rgb or null")
        ok(is_num(b["trees"]) and 0.0 <= b["trees"] <= 1.0 and is_num(b["snow_start"]) and 0.0 <= b["snow_start"] <= 1.0,
           "biome trees / snow_start")
        ok(isinstance(b["frozen_water"], bool), "biome frozen_water")
    if "monthly_temp_c" in c:       # v2.3
        ok(len(c["monthly_temp_c"]) == 12 and all(is_num(v) for v in c["monthly_temp_c"]), "climate.monthly_temp_c")
        ok(len(c["monthly_precip_mm"]) == 12 and all(is_num(v) and v >= 0 for v in c["monthly_precip_mm"]),
           "climate.monthly_precip_mm")
        ok(is_num(c["diurnal_range_c"]) and c["diurnal_range_c"] > 0 and is_num(c["lat"]), "climate.diurnal_range_c / lat")
        ok(is_num(p["lat"]) and -90 <= p["lat"] <= 90 and is_num(p["lon"]) and -180 <= p["lon"] <= 180, "preset lat/lon")
        ok(isinstance(p["region"], str) and isinstance(p["country"], str) and p["region"] and p["country"], "preset region")
    lms = p["landmarks"]
    ok(len(lms) >= 3, "landmarks")
    last = -1.0
    for lm in lms:
        ok(isinstance(lm["name"], str) and lm["name"] and is_num(lm["x"]) and is_num(lm["elev_m"]), "landmark")
        ok(0.0 <= lm["x"] <= w, "landmark x inside the world")
        ok(lm["x"] > last, "landmarks in transect order")
        last = lm["x"]
    ok(max(lm["elev_m"] for lm in lms) == p["max_elev_m"], "max_elev_m is the highest landmark")
    ok(p["sources"] and all(isinstance(u, str) and u.startswith("https://") for u in p["sources"]), "sources urls")


def validate_showcase_hello(sc):
    ok(sc["type"] in CLOUD_TYPES, "showcase.type")
    ok(list(sc["types"]) == ["cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus",
                             "altocumulus", "stratus", "cirrus", "supercell"], "showcase.types order")
    ok(set(sc["lessons"]) == CLOUD_TYPES, "lessons for every type")
    for ty, ls in sc["lessons"].items():
        ok(set(ls) == set(LESSON_KEYS), f"lesson keys of {ty}")
        for k in LESSON_KEYS:
            ok(isinstance(ls[k], str) and ls[k].strip(), f"lesson {ty}.{k}")


def count_sentences(text):
    return len([x for x in text.replace("? ", ". ").split(". ") if x.strip()])


def validate_tours(tours):
    """hello.showcase.tours (PROTOCOL.md v2.3): an ordered lifetime tour per type that has one."""
    ok(set(tours) == {"supercell"}, "tours has the supercell tour")
    steps = tours["supercell"]
    ok([st["id"] for st in steps] == TOUR_IDS, "supercell tour ids in order")
    for st in steps:
        ok(isinstance(st["title"], str) and st["title"].strip(), "tour title")
        ok(isinstance(st["text"], str) and st["text"].strip(), "tour text")
        n = count_sentences(st["text"])
        ok(2 <= n <= 4, f"tour step {st['id']}: 2-4 sentences, got {n}")


def validate_roads(roads, w):
    """hello.roads (PROTOCOL.md v2.3)."""
    hw = 0
    for r in roads:
        ok(is_num(r["x0"]) and is_num(r["x1"]) and 0.0 <= r["x0"] < r["x1"] <= w, "road span inside the world")
        ok(r["kind"] in ("street", "highway"), "road kind")
        hw += r["kind"] == "highway"
    ok(hw <= 1, "at most one highway")


def validate_tornado(t):
    for k in ("id", "cloud", "stage", "x", "base_y", "ground_y", "reach", "width", "top_width", "lean", "strength",
              "ef", "wind_ms", "age", "phase", "debris"):
        ok(k in t, f"tornado.{k}")
    ok(isinstance(t["id"], int) and isinstance(t["cloud"], int), "tornado ids")
    ok(t["stage"] in TORNADO_STAGES, "tornado stage")
    ok(0.0 <= t["reach"] <= 1.0 and 0.0 <= t["strength"] <= 1.0 and 0.0 <= t["debris"] <= 1.0, "tornado ranges")
    ok(isinstance(t["ef"], int) and 0 <= t["ef"] <= 5, "ef 0-5")
    ok(t["width"] >= 0 and t["top_width"] >= 0 and t["wind_ms"] >= 0 and t["age"] >= 0, "tornado sizes")
    ok(t["base_y"] < t["ground_y"], "the cloud base is above the ground")
    ok(all(is_num(t[k]) for k in ("x", "lean", "phase")), "tornado numbers")
    if "path_x0" in t:
        ok(is_num(t["path_x0"]), "path_x0")
    if t["stage"] in ("touchdown", "mature"):
        ok("path_x0" in t, "path_x0 once it has touched down")


def validate_car(c):
    for k in ("id", "x", "y", "angle", "dir", "speed", "variant", "color", "lights", "wipers", "state", "lift", "spin"):
        ok(k in c, f"car.{k}")
    ok(isinstance(c["id"], int) and c["dir"] in (1, -1), "car id / dir")
    ok(c["variant"] in CAR_VARIANTS and c["state"] in CAR_STATES, "car variant / state")
    ok(len(c["color"]) == 3 and all(isinstance(v, int) and 0 <= v <= 255 for v in c["color"]), "car colour")
    ok(isinstance(c["lights"], bool) and isinstance(c["wipers"], bool), "car lights / wipers bool")
    ok(c["speed"] >= 0.0 and c["lift"] >= 0.0, "car speed / lift >= 0")
    ok(all(is_num(c[k]) for k in ("x", "y", "angle", "spin")), "car numbers")
    for k in ("wet", "snow"):
        if k in c:
            ok(0.0 <= c[k] <= 1.0, f"car {k} 0..1")
    if "depth" in c:                          # v2.4: omitted when the street is dry
        ok(is_num(c["depth"]) and c["depth"] > 0.0, "car depth > 0 when present")


def validate_drain(d):
    """hello.settlements[].drain / frame.drains.settlements[] (PROTOCOL.md v2.4); `id` is only in the frame's."""
    for k in ("size", "class", "inlets", "cap"):
        ok(k in d, f"drain.{k}")
    ok(is_num(d["size"]) and 1.5 <= d["size"] <= 12.0, "drain size within the clamp")
    ok(d["class"] in DRAIN_CLASSES, "drain class")
    ok(d["class"] == ("small" if d["size"] < 3.76 else ("large" if d["size"] >= 6.2 else "normal")), "class follows the size")
    ok(isinstance(d["inlets"], int) and not isinstance(d["inlets"], bool) and d["inlets"] >= 1, "inlets int >= 1")
    ok(is_num(d["cap"]) and d["cap"] > 0, "drain cap > 0")


def validate_water_hello(w, dr, flow=None):
    """hello.water and hello.drains (v2.4): both present or both absent. With `hello.flow` (v2.4.1) the curb is 0.0 (there is
    none) and `overflow_depth` (default 8.0) sets the `overflowing` band; without it the v2.4 curb is a height above the
    flood depth."""
    global OVERFLOW_AT
    ok(w["model"] == 1 and is_num(w["basin_min_depth"]) and w["basin_min_depth"] > 0, "hello.water")
    for k in ("mode", "inlet_spacing", "curb", "flood_depth", "size_min", "size_max", "classes"):
        ok(k in dr, f"hello.drains.{k}")
    ok(dr["mode"] in DRAIN_MODES, "hello.drains.mode")
    ok(all(is_num(dr[k]) and dr[k] > 0 for k in ("inlet_spacing", "flood_depth", "size_min", "size_max")),
       "hello.drains numbers")
    ok(set(dr["classes"]) == DRAIN_CLASSES and all(is_num(v) for v in dr["classes"].values()), "hello.drains.classes")
    ok(dr["size_min"] < dr["size_max"], "hello.drains ranges")
    if flow is None:
        ok(is_num(dr["curb"]) and dr["curb"] > dr["flood_depth"], "hello.drains.curb (v2.4) above the flood depth")
        ok("overflow_depth" not in dr, "no overflow_depth on a v2.4 stream")
        OVERFLOW_AT = dr["curb"] - 0.05
    else:
        ok(is_num(dr["curb"]) and dr["curb"] == 0.0, "hello.drains.curb is 0.0 with hello.flow")
        od = dr.get("overflow_depth", 8.0)
        ok(is_num(od) and od > dr["flood_depth"], "hello.drains.overflow_depth above the flood depth")
        OVERFLOW_AT = od


def validate_flow_hello(fl):
    """hello.flow (v2.4.1)."""
    ok(isinstance(fl, dict) and set(fl) == {"model", "cell", "min_h"}, "hello.flow keys")
    ok(fl["model"] == 1 and isinstance(fl["cell"], int) and fl["cell"] == 32 and is_num(fl["min_h"]) and fl["min_h"] > 0,
       "hello.flow values")


def validate_flow_list(fl, world_w):
    """frame.flow (v2.4.1): the complete list of runs of film. A cell is in at most one run, a run has 1..40 cells, `h` is in
    steps of 0.05 px and at least `min_h`, `q` is non-negative, `dir` is -1 or 1, `src` >= 0 and `dst` >= -2."""
    ok(isinstance(fl, list), "frame.flow is a list")
    nb = int(world_w // 32) + 2
    used = set()
    for r in fl:
        for k in ("k0", "dir", "src", "dst", "h", "q"):
            ok(k in r, f"flow.{k}")
        ok(set(r) <= {"k0", "dir", "src", "dst", "h", "q"}, "flow run keys")
        ok(all(isinstance(r[k], int) and not isinstance(r[k], bool) for k in ("k0", "dir", "src", "dst")), "flow ints")
        ok(r["dir"] in (-1, 1) and r["src"] >= 0 and r["dst"] >= -2, "flow dir / src / dst")
        ok(isinstance(r["h"], list) and isinstance(r["q"], list) and len(r["h"]) == len(r["q"]) and 1 <= len(r["h"]) <= 40,
           "flow h / q lists of equal length 1..40")
        ok(all(is_num(v) and v >= 0.05 - 1e-9 and abs(v / 0.05 - round(v / 0.05)) < 1e-6 for v in r["h"]), "flow h in 0.05 steps")
        ok(all(is_num(v) and v >= 0.0 for v in r["q"]), "flow q >= 0")
        for i in range(len(r["h"])):
            k = r["k0"] + r["dir"] * i
            ok(0 <= k < nb, "flow cell inside the world")
            ok(k not in used, "a cell is in at most one run")
            used.add(k)


def validate_water_list(wl, settlement_ids, world_w, terrain_y=None, step=8):
    """frame.water (v2.4, v2.4.1): the complete list of bodies. `spill` may also be true on a pool (it runs over its real rim)."""
    ok(isinstance(wl, list), "frame.water is a list")
    seen = set()
    for b in wl:
        for k in ("id", "kind", "x", "x0", "x1", "y", "depth", "vol"):
            ok(k in b, f"water.{k}")
        ok(isinstance(b["id"], int) and not isinstance(b["id"], bool) and b["id"] not in seen, "water id unique int")
        seen.add(b["id"])
        ok(b["kind"] in WATER_KINDS, "water kind")
        ok(all(is_num(b[k]) for k in ("x", "x0", "x1", "y", "depth", "vol")), "water numbers")
        ok(0.0 <= b["x0"] <= b["x"] <= b["x1"] <= world_w, "water x0 <= x <= x1 inside the world")
        ok(b["depth"] > 0.0 and b["vol"] > 0.0, "a listed body holds water")
        if b["kind"] == "street":
            ok(b.get("settlement") in settlement_ids, "street body names a known settlement")
            ok(b["id"] == b["settlement"], "a street body's id is its settlement's id")
            ok(isinstance(b.get("spill", False), bool), "spill bool")
        else:
            ok("settlement" not in b and b.get("spill", True) is True, "a pool has no settlement, and `spill` only when true")
            ok(b["id"] > max(settlement_ids, default=0) or b["id"] in settlement_ids,
               "pool ids count on from the largest settlement id (a merged street body keeps its settlement's id)")
        if terrain_y is not None:
            i = int(round(b["x"] / step))
            ok(abs(terrain_y[min(i, len(terrain_y) - 1)] - b["y"] - b["depth"]) < step * 0.5 * 4 + 1.0,
               "depth is the terrain at x minus y (to the sample spacing)")


def validate_flood_list(fl, settlement_ids):
    """frame.flood (v2.4): one entry per settlement."""
    ok(isinstance(fl, list) and len(fl) == len(settlement_ids), "one flood entry per settlement")
    ok({e["id"] for e in fl} == set(settlement_ids), "flood ids are the settlements'")
    for e in fl:
        ok(e["state"] in FLOOD_STATES, "flood state")
        ok(all(is_num(e[k]) and e[k] >= 0 for k in ("depth", "inflow", "cap")), "flood numbers >= 0")
        d = e["depth"]
        want = ("overflowing" if d >= OVERFLOW_AT else "flooded" if d >= 3.0 else "ponding" if d >= 0.05 else None)
        if want == "overflowing":
            ok(e["state"] == "overflowing", f"state {e['state']} agrees with depth {d}")
        if want in ("flooded", "ponding"):
            ok(e["state"] == want or e["state"] == "overflowing", f"state {e['state']} agrees with depth {d}")
        ok(set(e) <= {"id", "state", "depth", "inflow", "cap", "backed"}, "flood entry keys")
        ok(isinstance(e.get("backed", True), bool) and e.get("backed", True) is True, "backed only when true")
        if e["state"] in ("ponding", "flooded"):
            ok(d >= 0.05, "ponding or flooded needs depth")
        if e["state"] in ("dry", "wet"):
            ok(d < 0.05, "dry / wet means no water")
        if e["state"] == "dry":
            ok(e["inflow"] < 0.05, "dry means (almost) no inflow")


def validate_climate_hello(c):
    """hello.climate (PROTOCOL.md v2.3)."""
    for k in ("year_days", "day_length_s", "hemisphere", "lat", "monthly_temp_c", "diurnal_range_c",
              "base_c", "start_month", "source"):
        ok(k in c, f"climate.{k}")
    ok(isinstance(c["year_days"], int) and 2 <= c["year_days"] <= 365, "climate.year_days")
    ok(c["day_length_s"] == 300, "climate.day_length_s")
    ok(c["hemisphere"] in ("N", "S"), "climate.hemisphere")
    ok(is_num(c["lat"]) and -90 <= c["lat"] <= 90, "climate.lat")
    ok(isinstance(c["monthly_temp_c"], list) and len(c["monthly_temp_c"]) == 12
       and all(is_num(v) for v in c["monthly_temp_c"]), "climate.monthly_temp_c")
    ok(is_num(c["diurnal_range_c"]) and c["diurnal_range_c"] > 0, "climate.diurnal_range_c")
    ok(is_num(c["base_c"]) and is_num(c["start_month"]) and 1.0 <= c["start_month"] < 13.0, "climate numbers")
    ok(c["source"] in ("preset", "generated"), "climate.source")


def validate_world_v23(w):
    """The optional v2.3 keys of frame.world: all present or all absent; wrong types are rejected."""
    keys = ("year_frac", "month", "day", "season", "hemisphere", "temp")
    present = [k in w for k in keys]
    ok(all(present) or not any(present), "the v2.3 world keys come together")
    if not any(present):
        return
    ok(is_num(w["year_frac"]) and 0.0 <= w["year_frac"] < 1.0, "year_frac 0..1")
    ok(is_num(w["month"]) and 1.0 <= w["month"] < 13.0 + 1e-9, "month 1..13")
    ok(isinstance(w["day"], int) and not isinstance(w["day"], bool) and w["day"] >= 0, "day int")
    ok(w["season"] in SEASONS, "season")
    ok(w["hemisphere"] in ("N", "S"), "hemisphere")
    t = w["temp"]
    for k in ("base_c", "season_c", "diurnal_c", "gas_c", "user_c", "day_min_c", "day_max_c"):
        ok(k in t and is_num(t[k]), f"temp.{k}")
    ok(t["day_min_c"] <= t["day_max_c"], "day_min_c <= day_max_c")
    total = t["base_c"] + t["season_c"] + t["diurnal_c"] + t["gas_c"] + t["user_c"]
    ok(abs(w["surface_temp_c"] - total) < 0.02, "surface_temp_c is the sum of its parts")


def validate_atmosphere(a):
    for k in ("humidity", "co2_ppm", "aerosol", "instability", "shear", "spawn_per_min", "cloud_target",
              "type_weights"):
        ok(k in a, f"atmosphere.{k}")
    for k in ("humidity", "aerosol", "instability", "shear"):
        ok(is_num(a[k]) and 0.0 <= a[k] <= 1.0, f"atmosphere.{k} 0..1")
    ok(is_num(a["co2_ppm"]) and 180 <= a["co2_ppm"] <= 2000, "co2_ppm 180..2000")
    ok(is_num(a["spawn_per_min"]) and a["spawn_per_min"] >= 0, "spawn_per_min")
    ok(isinstance(a["cloud_target"], int) and a["cloud_target"] >= 0, "cloud_target int")
    tw = a["type_weights"]
    ok(set(tw) == TYPES9, "type_weights has all nine types")
    ok(all(is_num(v) and v >= 0 for v in tw.values()), "type_weights >= 0")
    ok(abs(sum(tw.values()) - 1.0) < 0.01, "type_weights sum to 1")


def validate_merge_keys(c):
    """parts / merge / puff_part / merging_into of a cloud (all optional)."""
    has = [k in c for k in ("parts", "merge", "puff_part")]
    ok(all(has) or not any(has), "parts, merge and puff_part come together")
    if "parts" in c:
        ok(isinstance(c["parts"], list) and 1 <= len(c["parts"]) <= 3, "1..3 parts")
        for p in c["parts"]:
            ok(isinstance(p["id"], int) and p["type"] in TYPES9 and isinstance(p["name"], str), "part entry")
        ok(is_num(c["merge"]) and 0.0 <= c["merge"] <= 1.0, "merge 0..1")
        ok(isinstance(c["puff_part"], list) and len(c["puff_part"]) == len(c["puffs"]), "puff_part per puff")
        ok(all(isinstance(v, int) and 0 <= v <= len(c["parts"]) for v in c["puff_part"]), "puff_part values")
    if "merging_into" in c:
        ok(isinstance(c["merging_into"], int) and not isinstance(c["merging_into"], bool), "merging_into id")
        ok(c["stage"] == "dissipating" and c["precip_type"] in PRECIP_TYPES and c["precip_rate"] == 0.0,
           "an absorbed cloud is dissipating and dry")


def validate_hello(h):
    ok(h["type"] == "hello", "type")
    if "climate" in h:
        validate_climate_hello(h["climate"])
    ok(h["version"] == 2, "version must be 2")
    ok(h.get("mode", "sandbox") in MODES, "mode")
    if "preset" in h:
        ok(h["world"]["terrain_kind"] == "preset", "a preset world has terrain_kind 'preset'")
        validate_preset(h["preset"], h["world"]["w"])
    if h.get("mode") == "showcase":
        validate_showcase_hello(h["showcase"])
        if "tours" in h["showcase"]:
            validate_tours(h["showcase"]["tours"])
    if "roads" in h:
        validate_roads(h["roads"], h["world"]["w"])
    ok(isinstance(h["seed"], int), "seed int")
    ok(isinstance(h["loaded"], bool), "loaded bool")
    ok(is_num(h["t"]), "t number")
    w = h["world"]
    ok(isinstance(w["w"], int) and isinstance(w["h"], int), "world w/h int")
    ok(isinstance(w["ground_y"], int), "ground_y int")
    ok(w["terrain_kind"] in TERRAIN_KINDS, "terrain_kind")
    ter = w["terrain"]
    ok(isinstance(ter["step"], int) and ter["step"] > 0, "terrain step")
    ok(len(ter["y"]) == math.ceil(w["w"] / ter["step"]) + 1, "terrain length = ceil(w/step)+1")
    ok(all(is_num(v) for v in ter["y"]), "terrain y numbers")
    ok(abs(max(ter["y"]) - w["ground_y"]) < 1e-9, "ground_y is the lowest (largest y) surface point")
    ok(min(ter["y"]) > 0 and max(ter["y"]) < w["h"], "terrain inside the world")
    sids = set()
    for s in h["settlements"]:
        ok(isinstance(s["id"], int) and s["id"] not in sids, "settlement id unique int")
        sids.add(s["id"])
        ok(s["kind"] in SETTLEMENT_KINDS, "settlement kind")
        ok(isinstance(s["name"], str) and s["name"], "settlement name")
        ok(0 <= s["x0"] < s["x1"] <= w["w"], "settlement span inside world")
        ok(isinstance(s["population"], int) and s["population"] > 0, "population")
        if "drain" in s:                      # v2.4
            validate_drain(s["drain"])
    ok(("water" in h) == ("drains" in h), "hello.water and hello.drains come together")
    ok("flow" not in h or "water" in h, "hello.flow needs hello.water")
    if "flow" in h:                           # v2.4.1
        validate_flow_hello(h["flow"])
    if "water" in h:                          # v2.4, sandbox only
        validate_water_hello(h["water"], h["drains"], h.get("flow"))
        ok(all("drain" in s for s in h["settlements"]), "every settlement has a drain when hello.water is present")
        ok(h.get("mode", "sandbox") == "sandbox", "the water model is sandbox only")
    ids = set()
    for o in h["objects"]:
        ok(isinstance(o["id"], int) and o["id"] not in ids, "object id unique int")
        ids.add(o["id"])
        ok(o["kind"] in OBJECT_KINDS, f"object kind {o['kind']}")
        for k in ("x", "y", "w", "h", "sway", "snow", "wet"):
            ok(is_num(o[k]), f"object {k} number")
        ok(o["w"] > 0 and o["h"] > 0, "object has size")
        ok(0 <= o["x"] <= w["w"], "object x inside world")
        ok(-1.0 <= o["sway"] <= 1.0 and 0.0 <= o["snow"] <= 1.0 and 0.0 <= o["wet"] <= 1.0, "dynamic ranges")
        if "variant" in o:
            ok(isinstance(o["variant"], str), "variant str")
            if o["kind"] == "house":
                ok(o["variant"] in HOUSE_VARIANTS, "house variant")
            if o["kind"] == "tree":
                ok(o["variant"] in TREE_VARIANTS, "tree variant")
        if "seed" in o:
            ok(isinstance(o["seed"], int), "object seed int")
        for k in ("wall", "roof"):
            if k in o:
                ok(len(o[k]) == 3 and all(isinstance(c, int) and 0 <= c <= 255 for c in o[k]), f"{k} rgb")
        if "floors" in o:
            ok(isinstance(o["floors"], int) and o["floors"] >= 1, "floors int")
        if "settlement" in o:
            ok(o["settlement"] in sids, "object references a known settlement")
        if "layer" in o:
            ok(o["layer"] in (0, 1), "layer 0/1")
        if "damage" in o:
            ok(is_num(o["damage"]) and 0.0 < o["damage"] <= 1.0, "object damage in (0, 1] when present")
    json.loads(json.dumps(h))
    return ids


def validate_cloud(c):
    for k in ("id", "seed", "name", "type", "x", "y", "w", "h", "stage", "age", "lifetime", "life_frac",
              "form", "shear", "anvil", "water", "ice", "updraft", "precip_type", "precip_rate",
              "total_precip_mm", "puffs", "tint", "opacity", "darkness", "stage_times"):
        ok(k in c, f"cloud missing {k}")
    ok(isinstance(c["seed"], int), "cloud seed int")
    ok(c["type"] in CLOUD_TYPES, "cloud type")
    ok(c["stage"] in STAGES, "cloud stage")
    ok(c["precip_type"] in PRECIP_TYPES, "precip_type")
    ok(0.0 <= c["form"] <= 1.0, f"form range {c['form']}")
    ok(0.0 <= c["anvil"] <= 1.0, "anvil range")
    ok(is_num(c["shear"]), "shear number")
    ok(0.0 <= c["opacity"] <= 1.0 and 0.0 <= c["darkness"] <= 1.0, "opacity/darkness range")
    ok(0.0 <= c["life_frac"] <= 1.0, "life_frac")
    ok(len(c["tint"]) == 3 and all(0 <= t <= 255 for t in c["tint"]), "tint")
    cap = MAX_PUFFS_V23 if "parts" in c else (MAX_PUFFS_SUPERCELL if c["type"] == "supercell" else 40)
    ok(12 <= len(c["puffs"]) <= cap, f"puff count {len(c['puffs'])}")
    has_sc = [k in c for k in SUPERCELL_KEYS]
    ok(all(has_sc) == (c["type"] == "supercell") and (all(has_sc) or not any(has_sc)),
       "a supercell carries rotation / wall / overshoot / meso_x / hook, nothing else does")
    if c["type"] == "supercell":
        for k in ("rotation", "wall", "overshoot", "hook"):
            ok(is_num(c[k]) and 0.0 <= c[k] <= 1.0, f"supercell {k} 0..1")
        ok(is_num(c["meso_x"]), "meso_x number")
        ok(not c.get("fair", False), "a supercell is never fair")
    validate_merge_keys(c)
    for p in c["puffs"]:
        ok(len(p) == 4 and all(is_num(v) for v in p), "puff = [dx, dy, r, shade]")
        ok(p[2] >= 0.0 and 0.0 <= p[3] <= 1.0, "puff r >= 0, shade 0..1")
    for stg, t in c["stage_times"].items():
        ok(stg in STAGES and is_num(t), "stage_times")
    ok(isinstance(c.get("fair", False), bool), "fair is a bool")
    ok(("from_type" in c) == ("morph" in c), "from_type and morph come together")
    if "from_type" in c:
        ok(c["from_type"] == "cumulus" and c["type"] == "cumulonimbus", "only cumulus tower into cumulonimbus")
        ok(0.0 <= c["morph"] <= 1.0 and not c.get("fair", False), "morph range, a cumulonimbus is not fair")
    if c["type"] in ("cumulonimbus", "nimbostratus"):
        ok(not c.get("fair", False), "cumulonimbus / nimbostratus are never fair")
    if c.get("fair", False):
        ok(c["stage"] != "precipitating" and c["precip_type"] == "none" and c["total_precip_mm"] == 0.0,
           "a fair cloud does not rain")
    if c["type"] not in ("cumulonimbus", "supercell"):
        ok(c["anvil"] == 0.0, "only cumulonimbus and supercell have an anvil")
    if c["type"] in ("cirrus", "altocumulus"):
        ok(c["precip_type"] == "none", "cirrus/altocumulus never precipitate to the ground")


def validate_frame(f, world_w, world_h, known_ids, settlement_ids=None, terrain_y=None):
    """`settlement_ids` (v2.4): the hello's settlement ids; given, frame.water / flood / drains are checked against them."""
    ok(f["type"] == "frame", "type")
    ok(isinstance(f["frame"], int) and is_num(f["t"]) and is_num(f["speed"]), "frame/t/speed")
    ok(isinstance(f["paused"], bool), "paused bool")
    w = f["world"]
    for k in ("w", "h", "ground_y", "freezing_y", "surface_temp_c", "time_of_day"):
        ok(k in w, f"frame world.{k}")
    ok(w["w"] == world_w and w["h"] == world_h, "frame world size")
    ok(0.0 <= w["time_of_day"] < 1.0, "time_of_day")
    validate_world_v23(w)
    if "atmosphere" in f:
        validate_atmosphere(f["atmosphere"])
    wind = f["wind"]
    ok(len(wind["u"]) == wind["cols"] * wind["rows"] == len(wind["v"]), "wind grid length")
    ok(wind["cols"] * wind["cell"] >= world_w and wind["rows"] * wind["cell"] >= world_h, "wind grid covers the world")
    for sl in f["streamlines"]:
        ok(len(sl["pts"]) >= 2 and "speed" in sl and "phase" in sl, "streamline")
    for c in f["clouds"]:
        validate_cloud(c)
    for p in f["particles"]:
        ok(len(p) == 7 and p[0] in (0, 1, 2, 3, 4, 5, 6, 7), "particle row")
        ok(all(is_num(v) and round(v, 1) == v for v in p[1:]), f"particle numbers rounded to 1 decimal {p}")
    for a in f["arrows"]:
        for k in ("x1", "y1", "x2", "y2", "kind", "mag", "label"):
            ok(k in a, f"arrow {k}")
        ok(a["kind"] in ARROW_KINDS, "arrow kind")
    for row in f["objects"]:
        ok(len(row) == 6 and all(is_num(v) for v in row), "object delta row [id,sway,snow,wet,w,h]")
        ok(row[0] in known_ids, f"object row references unknown id {row[0]}")
        ok(-1.0 <= row[1] <= 1.0 and 0.0 <= row[2] <= 1.0 and 0.0 <= row[3] <= 1.0, "object row ranges")
    ids = [row[0] for row in f["objects"]]
    ok(len(ids) == len(set(ids)), "an object id appears once per frame")
    if "ground" in f:
        g = f["ground"]
        ok(g["step"] == 32, "ground step 32")
        n = math.ceil(world_w / 32) + 1
        ok(len(g["snow"]) == n and len(g["wet"]) == n, "ground strip length")
        ok(all(0.0 <= v <= 1.0 for v in g["snow"] + g["wet"]), "ground values 0..1")
    for lt in f["lightning"]:
        ok(len(lt["pts"]) >= 2 and 0.0 <= lt["alpha"] <= 1.0, "lightning")
    st = f["stats"]
    for k in ("clouds_alive", "clouds_born", "clouds_dead", "rain_mm", "snow_mm", "particles",
              "ice_crystals", "wind_mean"):
        ok(k in st, f"stats.{k}")
    ok(st["clouds_alive"] == len(f["clouds"]), "clouds_alive matches the list")
    for k in ("tornadoes", "tornadoes_total", "cars"):
        if k in st:
            ok(isinstance(st[k], int) and st[k] >= 0, f"stats.{k} int")
    ok(st.get("tornadoes", 0) == len(f.get("tornadoes", [])), "stats.tornadoes matches the list")
    ok(st.get("cars", 0) == len(f.get("cars", [])), "stats.cars matches the list")
    ok(st.get("tornadoes_total", 0) >= st.get("tornadoes", 0), "tornadoes_total >= tornadoes")
    for t in f.get("tornadoes", []):
        validate_tornado(t)
    cids = [t["cloud"] for t in f.get("tornadoes", [])]
    ok(len(cids) == len(set(cids)), "at most one tornado per supercell at a time")
    car_ids = [c["id"] for c in f.get("cars", [])]
    ok(len(car_ids) == len(set(car_ids)), "car ids unique")
    for c in f.get("cars", []):
        validate_car(c)
    seen_d = set()
    for row in f.get("damage", []):
        ok(len(row) == 2 and row[0] in known_ids and is_num(row[1]) and 0.0 < row[1] <= 1.0, "damage row [id, dmg]")
        ok(row[0] not in seen_d, "a damage id appears once per frame")
        seen_d.add(row[0])
    if settlement_ids is not None:            # v2.4
        if "water" in f:
            validate_water_list(f["water"], settlement_ids, world_w, terrain_y)
        if "flood" in f:
            validate_flood_list(f["flood"], settlement_ids)
        if "flow" in f:                       # v2.4.1
            validate_flow_list(f["flow"], world_w)
        if "drains" in f:
            d = f["drains"]
            ok(d["mode"] in DRAIN_MODES, "frame.drains.mode")
            ok([e["id"] for e in d["settlements"]] == list(settlement_ids), "frame.drains lists every settlement")
            for e in d["settlements"]:
                validate_drain(e)
    ok(isinstance(f["events"], list) and isinstance(f["deaths"], list), "events/deaths lists")
    if "showcase" in f:
        sc = f["showcase"]
        ok(sc["type"] in CLOUD_TYPES and isinstance(sc["cycle"], int) and sc["cycle"] >= 0, "frame.showcase")
        if "tour_step" in sc:
            ok(sc["tour_step"] is None or sc["tour_step"] in TOUR_IDS, "tour_step is null or a tour id")
            ok(sc["tour_step"] is None or sc["cloud_id"] is not None, "tour_step is null in the pause")
        ok(sc["cloud_id"] is None or sc["cloud_id"] in {c["id"] for c in f["clouds"]},
           "showcase.cloud_id is null or the cloud on stage")
        ok(len(f["clouds"]) <= 1, "showcase has at most one cloud")
    for d in f["deaths"]:
        for k in ("id", "seed", "name", "type", "born", "died", "lifetime", "peak_water",
                  "total_precip_mm", "precip_type", "stage_times", "puffs", "tint"):
            ok(k in d, f"death.{k}")
        if "merged_into" in d:
            ok(isinstance(d["merged_into"], int) and not isinstance(d["merged_into"], bool), "merged_into id")
    json.loads(json.dumps(f))
