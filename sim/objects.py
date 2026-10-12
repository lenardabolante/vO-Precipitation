"""World objects (scenery + buildings) and their dynamic state, plus ground cover.

Protocol shape (PROTOCOL.md v2): the complete list goes out once in `hello` with
stable integer ids; frames carry only changed rows `[id, sway, snow, wet, w, h]`.
Landing particles are tallied per 32 px ground sample by the particle system; this
module turns those tallies into wetness / snow / puddle growth through x buckets,
so the cost is O(buckets hit + objects in them), not O(hits x objects).
"""
from __future__ import annotations

import bisect
import math

from settlements import (HOUSE_VARIANTS, plan_lone_buildings, plan_ponds,  # noqa: F401
                         plan_settlements)
from terrain import KINDS, Terrain

BUILDING_KINDS = ("house", "barn", "church", "windmill", "shop", "apartment", "office",
                  "skyscraper", "factory", "water_tower", "streetlamp")
SCENERY_KINDS = ("mountain", "hill", "tree", "pond", "puddle", "snowpack")
# Kinds that earlier versions put in a world and that are gone from the game. A save from then still lists them:
# ObjectField.from_state drops the ones at the end of the list (all of them, in a save the game wrote), see there.
RETIRED_KINDS = frozenset(("windsock",))      # removed 2026-10-10; scrap/windsock/SIM.md has it and how to bring it back
TREE_VARIANTS = ("pine", "oak", "birch", "bush", "palm", "cactus", "acacia", "shrub", "dead")
COLLECTORS = frozenset(BUILDING_KINDS) | {"tree"}
SWAYERS = frozenset(("tree",))
# Tornado damage (v2.3): which objects a tornado can damage and the wind (m/s, 3-second gust at the
# object) below which it does nothing; above it `damage` rises by DAMAGE_K per second for every 25 m/s of
# excess wind. The thresholds follow the Enhanced Fujita damage indicators: a small house starts to lose
# its roof in EF0-EF1 winds and is destroyed in EF2-EF3, a church or a shop takes more, a steel or
# concrete high-rise only EF4-EF5 (apartment 60, office 68, skyscraper 82 m/s; below that they only get facade damage).
DAMAGE_VT = {"tree": 34.0, "house": 33.0, "barn": 30.0, "church": 42.0, "windmill": 36.0,
             "shop": 40.0, "apartment": 60.0, "office": 68.0, "skyscraper": 82.0, "factory": 48.0,
             "water_tower": 44.0, "streetlamp": 32.0}
DAMAGE_K = 0.20
DAMAGE_BIG = frozenset(("apartment", "office", "skyscraper", "factory"))   # these fail slowly: (excess / 60), not (excess / 25)
# Facade damage: a big building does not collapse in winds below DAMAGE_VT, but windows break and cladding
# peels long before: above `vf` m/s its damage rises towards cap * ((v - vf) / (vt - vf)) ** 0.6 (never past
# `cap`, never down), so an EF2-EF3 passing over a tower block leaves it visibly scarred and not flattened.
# kind: (vf, cap). Above DAMAGE_VT the structural rule takes over and damage may reach 1.
DAMAGE_FACADE = {"apartment": (38.0, 0.40), "office": (36.0, 0.40), "skyscraper": (38.0, 0.30),
                 "factory": (40.0, 0.30)}

GROUND_STEP = 32
INV_G = 1.0 / GROUND_STEP
# (w, h) a puddle / a snowpack is generated with, and shrinks back to when it has dried / melted
PUDDLE_BASE = (20.0, 3.0)
SNOWPACK_BASE = (20.0, 2.0)


# -- where the ground-level spots (puddle, snowpack) may stand -----------------------------------------------
SPOT_MARGIN = {"puddle": 4.0, "snowpack": 4.0}   # px kept clear beyond the object's half width
FLAT_SLOPE = 0.05             # |dy/dx| a fallback spot may have (99 % of the old picks were flatter than 0.03)


def _merge(spans):
    """Sorted, merged [(a, b)] of overlapping or touching spans."""
    out = []
    for a, b in sorted(spans):
        if out and a <= out[-1][1]:
            if b > out[-1][1]:
                out[-1] = (out[-1][0], b)
        else:
            out.append((a, b))
    return out


def _clear(span_sets, x, half):
    """True when [x - half, x + half] touches none of the merged spans in any of `span_sets`."""
    lo, hi = x - half, x + half
    for spans in span_sets:
        j = bisect.bisect_left(spans, (lo, lo))
        if j > 0 and spans[j - 1][1] > lo:
            return False
        if j < len(spans) and spans[j][0] < hi:
            return False
    return True


# -- puddles rest on the ground (2026-10-10) -----------------------------------------------------------------------
# A puddle is a flat lens of water `w` wide at (x, y). Where the ground under it falls away it floats: 81 of 1239
# measured puddles floated by more than 1 px at their 20 px start size (up to 7 px), and 561 at the width rain grows
# them to (up to 194 px). So a puddle now (1) comes to rest at the lowest point of its own footprint when it is placed,
# and (2) only grows as wide as the hollow it sits in holds. Both are pure functions of the terrain and the already
# chosen x: nothing is drawn from the seeded stream.
# px the ground may fall away under a puddle's footprint before it counts as floating. The viewer draws the lens
# centred 1 px below its y (Renderer.drawPuddle), so its two tips already touch ground that is up to 1 px lower.
PUDDLE_TOL = 1.0
PUDDLE_BANK = 3.0             # px the ground may climb above its water at either end of a grown puddle (a bank it laps against)
PUDDLE_FORCE_MAX = 3.0        # px a blocked puddle may be forced down below the ground at its centre (the lens is drawn over it)
PUDDLE_MAX_W = 140.0          # the widest rain makes a puddle (ObjectField.update)
_SETTLE_ITERS = 2000          # a puddle moves at most half its width per round, so this is far more than any slope needs


def _footprint_low(terrain, x, half):
    """(y, at): the lowest ground (largest y) under [x - half, x + half] (clamped to the world) and the x of it, the one
    nearest `x` when several are equally low (a level floor does not move the puddle)."""
    a = max(0.0, x - half)
    b = min(float(terrain.w), x + half)
    cand = [a, b]
    if terrain.n > 1:
        cand += [i * terrain.step for i in range(int(math.ceil(a * terrain.inv)), min(int(b * terrain.inv), terrain.n - 1) + 1)
                 if a < i * terrain.step < b]
    best = None
    for c in cand:
        g = terrain.height_at(c)
        if best is None or g > best[0] + 1e-9 or (abs(g - best[0]) <= 1e-9 and abs(c - x) < abs(best[1] - x)):
            best = (g, c)
    return best


def puddle_rests(terrain, x, half=None):
    """True when a puddle at x, y = the ground at x, does not float: the ground under its footprint (half = half its
    width, 10 px for a new one) falls no more than PUDDLE_TOL below it."""
    return _footprint_low(terrain, x, PUDDLE_BASE[0] / 2.0 if half is None else half)[0] - terrain.height_at(x) <= PUDDLE_TOL


def settle_puddle(terrain, x, spans=(), taken=()):
    """(x, y) a puddle chosen at `x` comes to rest at.
    * Level ground (nothing under its 20 px footprint falls more than PUDDLE_TOL below its centre): nothing happens,
      the same x and y as `terrain.height_at`.
    * Otherwise water runs downhill: each round the puddle moves to the lowest ground under its footprint, so on a slope
      it travels to the foot of it, and at an edge it drops over it onto the lower ground. It stops where its own centre
      is the lowest point it covers; there the ground rises on both sides, or is level.
    * It never goes where it may not stand: outside 20 .. w - 20, within `half` of one of the merged `spans`
      (buildings, ponds: `spans` is a tuple of merged span lists, the same rule as `flat_spot`) or of `taken` (a merged
      span list of the puddles already there). A walk that is blocked ends at the last allowed x short of it; if the
      ground there still falls away, the puddle is forced down, y = the lowest ground under its footprint, so that it
      rests on it anyway, when that is no more than PUDDLE_FORCE_MAX (the lens is drawn over the ground, a deeper one
      would be a patch of water inside it); past that, or if it was picked on top of another puddle, it goes to the
      nearest allowed spot of level ground to where it was picked (a scan, as `flat_spot` does; a world with none
      forces it down)."""
    half = PUDDLE_BASE[0] / 2.0 + SPOT_MARGIN["puddle"]
    hw = PUDDLE_BASE[0] / 2.0
    lo, hi = 20.0, terrain.w - 20.0
    x0 = x

    def ok(c):
        return lo <= c <= hi and _clear(spans, c, half) and _clear((taken,), c, half)

    for _ in range(_SETTLE_ITERS):
        g = terrain.height_at(x)
        low, at = _footprint_low(terrain, x, hw)
        if low - g <= PUDDLE_TOL:
            break
        nx = x
        step = 1.0 if at > x else -1.0
        d = abs(at - x)
        k = 1.0
        while k < d + 1e-9 and ok(x + step * k):          # as far towards the low point as is allowed
            nx = x + step * k
            k += 1.0
        if ok(at):
            nx = at
        if nx == x or terrain.height_at(nx) <= g + 1e-9:
            break
        x = nx
    y = terrain.height_at(x)
    low = _footprint_low(terrain, x, hw)[0]
    if low - y > PUDDLE_FORCE_MAX or not ok(x):              # blocked on a slope or an edge, or picked on another puddle
        for j in range(int((terrain.w - 40.0) / 4.0) + 1):   # the nearest allowed spot that is level
            for sgn in (1.0, -1.0):
                c = x0 + sgn * 4.0 * j
                if ok(c) and puddle_rests(terrain, c):
                    return c, terrain.height_at(c)
    if low - y > PUDDLE_TOL:
        y = low                                              # blocked on a slope or an edge: forced down onto the ground
    return x, y


def _reach(terrain, x, y, direction, limit):
    """How far from x, going `direction` (+1 / -1), the ground stays within [y - PUDDLE_BANK, y + PUDDLE_TOL] (at most `limit`)."""
    thr = y + PUDDLE_TOL
    top = y - PUDDLE_BANK
    px, pg = x, terrain.height_at(x)
    if pg > thr or pg < top:
        return 0.0
    i = int(math.floor(x * terrain.inv)) + 1 if direction > 0 else int(math.ceil(x * terrain.inv)) - 1
    while 0 <= i < terrain.n:
        nx = i * terrain.step
        ng = terrain.y[i]
        if ng > thr or ng < top:
            edge = thr if ng > thr else top
            return min(limit, abs(px + (nx - px) * (edge - pg) / (ng - pg) - x))
        if abs(nx - x) >= limit:
            break
        px, pg = nx, ng
        i += direction
    return limit


def puddle_cap(terrain, x, y):
    """The widest a puddle at (x, y) may grow: the width of the hollow it sits in, i.e. up to where the ground falls
    away more than PUDDLE_TOL below its water, or climbs more than PUDDLE_BANK above it, on either side (it is
    centred), inside the world, at most PUDDLE_MAX_W, and never below the 20 px it starts as."""
    limit = min(PUDDLE_MAX_W / 2.0, x, terrain.w - x)
    half = min(_reach(terrain, x, y, 1, limit), _reach(terrain, x, y, -1, limit))
    return max(PUDDLE_BASE[0], min(PUDDLE_MAX_W, math.floor(2.0 * half * 10.0) / 10.0))


def _obj(kind, x, y, w, h, seed, variant=None, wall=None, roof=None, floors=None,
         settlement=None, layer=None, wet=0.0):
    o = {"kind": kind}
    if variant is not None:
        o["variant"] = variant
    o.update({"x": round(x, 1), "y": round(y, 1), "w": round(w, 1), "h": round(h, 1), "seed": seed})
    if wall is not None:
        o["wall"] = list(wall)
    if roof is not None:
        o["roof"] = list(roof)
    if floors is not None:
        o["floors"] = floors
    if settlement is not None:
        o["settlement"] = settlement
    if layer is not None:
        o["layer"] = layer
    o["sway"] = 0.0
    o["snow"] = 0.0
    o["wet"] = wet
    return o


def generate_world(rng, w: int, h: int, terrain_kind: str, settlements_mode: str, terrain=None,
                   biome=None, plan=None):
    """Terrain, settlements and every static object for a new world.

    Returns (terrain, settlements, objects). Objects have ids 1..N in list order.
    Deterministic in `rng`. `terrain` is a ready-made heightmap (region presets, PROTOCOL.md v2.2);
    without it one is generated from `terrain_kind`. `biome` / `plan` (presets.py `_BIOMES` /
    `settlement_plan()`, PROTOCOL.md "Preset biome (v2.3)") dress a region preset: tree density and
    variants, pond rarity, the settlement plan with real names. Without them (every non-preset world) the
    generation is exactly what it was before v2.3: the same draws, the same objects for the same seed."""
    if terrain is None:
        terrain = Terrain.generate(rng, w, h, terrain_kind)
    settlements, village_buildings, occ, pond_slots = plan_settlements(rng, terrain, settlements_mode, plan)
    if plan is not None:
        guard = terrain.summits          # (a town may stand on the lowest ground of a preset)
    else:
        guard = terrain.protected
    if guard:
        # a preset's summits: if a mandatory settlement still landed on one, plan again (a few times,
        # from the untouched heightmap) with the next draws of the same seeded stream
        y0 = list(terrain.y)
        for _attempt in range(8):
            if not any(s["x0"] < b and s["x1"] > a for s in settlements for a, b in guard):
                break
            terrain.y = list(y0)
            terrain._refresh()
            settlements, village_buildings, occ, pond_slots = plan_settlements(rng, terrain, settlements_mode, plan)
    lone = plan_lone_buildings(rng, terrain, occ, settlements_mode, plan)
    ponds = plan_ponds(rng, terrain, occ, pond_slots, keep_lowest=plan is not None)
    if plan is None and terrain.kind in KINDS:
        terrain.cap_relief()                  # (a pond pad is dug up to 5 px below the lowest ground)
    terrain.normalize()                       # heightmap is final from here on

    u = w / 1280.0
    objects = []

    # -- background scenery standing on the terrain ---------------------------------
    def low_spot(tries=6):
        best = None
        for _ in range(tries):
            x = rng.uniform(w * 0.03, w * 0.97)
            y = terrain.height_at(x)
            if best is None or y > best[1]:
                best = (x, y)
        return best[0]

    for _ in range(max(1, int(round(u * 1.2)))):
        x = low_spot()
        objects.append(_obj("mountain", x, terrain.height_at(x), rng.uniform(290, 520),
                            rng.uniform(210, 360), rng.getrandbits(31)))
    for _ in range(max(1, int(round(u * 1.5)))):
        x = low_spot(3)
        objects.append(_obj("hill", x, terrain.height_at(x), rng.uniform(160, 320),
                            rng.uniform(60, 135), rng.getrandbits(31)))
    for p in ponds:
        objects.append(_obj("pond", p["x"], terrain.height_at(p["x"]), p["w"], p["h"], p["seed"],
                            wet=1.0))

    # -- trees (thin out inside towns and above the tree line) ----------------------
    spans = [(s["x0"] - 10.0, s["x1"] + 10.0) for s in settlements]
    blocked = [(b["x"] - b["w"] / 2 - 6, b["x"] + b["w"] / 2 + 6) for b in lone]
    blocked += [(p["x"] - p["w"] / 2 - 6, p["x"] + p["w"] / 2 + 6) for p in ponds]
    tree_line = (0.27 if biome is None else biome["tree_line"]) * h
    n_trees = int(22 * u) if biome is None else int(round(22 * u * biome["trees"]))
    placed = 0
    for _ in range(n_trees * 8):
        if placed >= n_trees:
            break
        x = rng.uniform(10.0, w - 10.0)
        if any(a <= x <= b for a, b in blocked):
            continue
        in_town = any(a <= x <= b for a, b in spans)
        if in_town and rng.random() > 0.10:
            continue
        y = terrain.height_at(x)
        alt = terrain.ground_y - y
        if alt > tree_line or abs(terrain.slope_at(x)) > 0.9:
            continue
        if biome is not None:
            if alt > 0.15 * h:
                variant = biome["high_tree"] if rng.random() < 0.8 else "bush"
            else:
                variant = _pick_biome_tree(rng, biome["tree_mix"], in_town)
        elif alt > 0.15 * h:
            variant = "pine" if rng.random() < 0.8 else "bush"
        else:
            variant = _pick_tree_variant(rng, in_town)
        tw, th = _tree_size(rng, variant)
        objects.append(_obj("tree", x, y, tw, th, rng.getrandbits(31), variant=variant))
        placed += 1

    # -- buildings ---------------------------------------------------------------
    blds = village_buildings + lone
    blds.sort(key=lambda b: (b.get("settlement", 0), b["layer"], b["x"]))
    for b in blds:
        x = round(b["x"], 1)
        objects.append(_obj(b["kind"], x, terrain.height_at(x), b["w"], b["h"], b["seed"],
                            variant=b.get("variant"), wall=b["wall"], roof=b["roof"],
                            floors=b["floors"], settlement=b.get("settlement"), layer=b["layer"]))

    # -- dynamic spots ---------------------------------------------------------------
    # Where a puddle or a snowpack may stand. The flattest ground of a world is the levelled pad
    # under a settlement, so picking "the flattest of 5 draws" alone put them inside buildings and in the
    # street; a spot now has to be clear of the things in `avoid` (the same draws are made as before, so the
    # rest of the seeded stream, and everything generated after it, is unchanged; nothing here draws more).
    foot = _merge([(o["x"] - o["w"] / 2.0, o["x"] + o["w"] / 2.0) for o in objects
                   if o["kind"] in BUILDING_KINDS])
    pond_spans = _merge([(o["x"] - o["w"] / 2.0, o["x"] + o["w"] / 2.0) for o in objects if o["kind"] == "pond"])
    street_spans = _merge([(s["x0"], s["x1"]) for s in settlements])
    avoid_sets = {"puddle": (foot, pond_spans), "snowpack": (foot, pond_spans, street_spans)}

    def flat_spot(kind, tries=5):
        """x of a flat spot for `kind`, or None when the world has no clear room for it. The flattest of `tries`
        draws that is clear of `avoid_sets[kind]` (and no steeper than FLAT_SLOPE, unless every draw is); if
        there is none, the nearest clear x with level ground to the flattest draw, found by a scan, not by
        drawing again; failing that the flattest clear x there is."""
        spans = avoid_sets[kind]
        half = 0.5 * 20.0 + SPOT_MARGIN[kind]
        draws = []
        for _ in range(tries):
            x = rng.uniform(20.0, w - 20.0)
            draws.append((abs(terrain.slope_at(x)), x))
        sl0, x0 = min(draws, key=lambda d: d[0])        # (the old pick: the flattest draw, the first of equals)
        ok = max(FLAT_SLOPE, sl0)
        best = None
        for sl, x in draws:
            if sl <= ok and (best is None or sl < best[0]) and _clear(spans, x, half):
                best = (sl, x)
        if best is not None:
            return best[1]
        xs = [x0 + sgn * 4.0 * j for j in range(int((w - 40.0) / 4.0) + 1) for sgn in (1.0, -1.0)]
        xs = [x for x in xs if 20.0 <= x <= w - 20.0 and _clear(spans, x, half)]
        pool = ([x for x in xs if abs(terrain.slope_at(x)) <= 0.01] or      # dead level first, then merely flat
                [x for x in xs if abs(terrain.slope_at(x)) <= FLAT_SLOPE] or
                sorted(xs, key=lambda x: abs(terrain.slope_at(x)))[:1])
        return pool[0] if pool else None        # (xs is ordered by distance from x0; the first flat one is the nearest)

    picks = []
    for _ in range(max(3, int(round(3 * u)))):
        x = flat_spot("puddle")
        seed_ = rng.getrandbits(31)
        if x is not None:
            picks.append((x, seed_))
    # a puddle on level ground stays exactly where it was picked (and keeps that spot against the others); the rest
    # run downhill to the lowest point (settle_puddle: no draws), in the order they were picked, one hollow each
    taken = _merge([(x - 10.0, x + 10.0) for x, _s in picks if puddle_rests(terrain, x)])
    for x, seed_ in picks:
        if puddle_rests(terrain, x):
            y = terrain.height_at(x)
        else:
            x, y = settle_puddle(terrain, x, avoid_sets["puddle"], taken)
            taken = _merge(taken + [(x - 10.0, x + 10.0)])
        objects.append(_obj("puddle", x, y, PUDDLE_BASE[0], PUDDLE_BASE[1], seed_))
    for _ in range(max(3, int(round(3 * u)))):
        x = flat_spot("snowpack")
        seed_ = rng.getrandbits(31)
        if x is not None:
            objects.append(_obj("snowpack", x, terrain.height_at(x), 20.0, 2.0, seed_))
    # The windsock that was placed here is gone from the game (2026-10-10; scrap/windsock/SIM.md has it), but the draws
    # it made are still made, and nothing is created from them. `rng` is the world's seeded stream and the caller
    # keeps using it after this function returns (World(), the first cloud spawn delay, the streamlines, the opening
    # clouds), so leaving the draws out would change every world. Per windsock that is flat_spot's 5 tries (each
    # a uniform(20, w - 20), whatever the terrain: the search after them draws nothing) and one getrandbits(31).
    for _ in range(max(1, int(round(0.7 * u)))):
        for _try in range(5):
            rng.uniform(20.0, w - 20.0)
        rng.getrandbits(31)

    for i, o in enumerate(objects):
        o["id"] = i + 1
    # id first in the dict for readability on the wire
    objects = [{"id": o["id"], **{k: v for k, v in o.items() if k != "id"}} for o in objects]
    return terrain, settlements, objects


def _pick_tree_variant(rng, in_town):
    r = rng.random()
    if in_town:
        return "bush" if r < 0.6 else ("oak" if r < 0.8 else "birch")
    if r < 0.32:
        return "oak"
    if r < 0.55:
        return "birch"
    if r < 0.82:
        return "pine"
    return "bush"


def _pick_biome_tree(rng, mix, in_town):
    """A tree variant from the biome's weights (a town keeps its smaller trees: bush, palm, shrub)."""
    pairs = [(k, v) for k, v in mix.items() if v > 0.0]
    if in_town:
        small = [(k, v) for k, v in pairs if k in ("bush", "palm", "shrub", "oak")]
        pairs = small or pairs
    total = sum(v for _k, v in pairs)
    r = rng.uniform(0.0, total)
    for k, v in pairs:
        r -= v
        if r <= 0.0:
            return k
    return pairs[-1][0]


def _tree_size(rng, variant):
    if variant == "palm":
        return rng.uniform(26, 40), rng.uniform(88, 130)
    if variant == "cactus":
        return rng.uniform(14, 24), rng.uniform(40, 80)
    if variant == "acacia":
        return rng.uniform(50, 80), rng.uniform(48, 70)
    if variant == "shrub":
        return rng.uniform(20, 34), rng.uniform(12, 22)
    if variant == "dead":
        return rng.uniform(14, 22), rng.uniform(40, 70)
    if variant == "pine":
        return rng.uniform(20, 32), rng.uniform(55, 95)
    if variant == "oak":
        return rng.uniform(28, 46), rng.uniform(48, 72)
    if variant == "birch":
        return rng.uniform(16, 26), rng.uniform(52, 78)
    return rng.uniform(20, 34), rng.uniform(14, 24)


def apply_snow_start(objs: list, ground, freezing_y: float, snow_start: float) -> int:
    """A world that opens at or below freezing starts white (preset biomes, v2.3): every ground sample whose
    surface lies at or above the freezing level (`y <= freezing_y`: higher up the screen = colder) gets
    `snow_start` cover, and the trees, buildings and snowpacks standing there carry some of it too. Returns the
    number of ground samples covered. Call before the ObjectField is built; the ground's first message is forced."""
    if snow_start <= 0.0:
        return 0
    n = 0
    for i, y in enumerate(ground.surf):
        if y <= freezing_y:
            ground.snow[i] = snow_start
            n += 1
    if n:
        ground.force = True
        ground.dirty = True
    for o in objs:
        if o["y"] > freezing_y:
            continue
        if o["kind"] in COLLECTORS:
            o["snow"] = round(0.8 * snow_start, 2)
        elif o["kind"] == "snowpack":
            o["snow"] = round(snow_start, 2)
    return n


class ObjectField:
    """Dynamic state of the object list; produces the per-frame delta rows."""

    def __init__(self, objs: list, terrain=None):
        self.objs = objs
        n = len(objs)
        self.wet_f = [float(o["wet"]) for o in objs]
        self.snow_f = [float(o["snow"]) for o in objs]
        self.sway_f = [float(o["sway"]) for o in objs]
        self.sway_idx = [i for i, o in enumerate(objs) if o["kind"] in SWAYERS]
        self.buckets: dict = {}
        for i, o in enumerate(objs):
            if o["kind"] in COLLECTORS:
                a = o["x"] - o["w"] / 2.0 - 8.0
                b = o["x"] + o["w"] / 2.0 + 8.0
                for k in range(int(a * INV_G + 0.5), int(b * INV_G + 0.5) + 1):
                    self.buckets.setdefault(k, []).append(i)
        self.puddles = sorted((o["x"], i) for i, o in enumerate(objs) if o["kind"] == "puddle")
        self.snowpacks = sorted((o["x"], i) for i, o in enumerate(objs) if o["kind"] == "snowpack")
        self._puddle_x = [x for x, _ in self.puddles]
        self._snow_x = [x for x, _ in self.snowpacks]
        self.active: set = {i for i in range(n) if self.wet_f[i] > 0 and objs[i]["kind"] != "pond"
                            or self.snow_f[i] > 0}
        self.changed: set = set()
        self.sway_dt = 0.0
        self.decay_dt = 0.0
        self.tick = 0
        # v2.3 tornado damage: 0..1 per object (monotone), the damageable ones sorted by x
        self.dmg_f = [float(o.get("damage", 0.0)) for o in objs]
        self.dmg_sorted = sorted((o["x"], i) for i, o in enumerate(objs) if o["kind"] in DAMAGE_VT)
        self._dmg_x = [x for x, _ in self.dmg_sorted]
        self.dmg_changed: set = set()
        self.storm_sway: dict = {}
        self.size_f: dict = {}        # index -> [w, h] unrounded, of the puddles / snowpacks that are shrinking
        # index -> the widest a puddle may grow (what its hollow holds, puddle_cap). Derived from the terrain and the
        # puddle's x, y, so it is not part of the saved state; without a terrain (a bare field in a test) rain grows
        # a puddle up to PUDDLE_MAX_W anywhere, as it always did.
        self.puddle_cap: dict = {}
        if terrain is not None:
            for _x, i in self.puddles:
                self.puddle_cap[i] = puddle_cap(terrain, objs[i]["x"], objs[i]["y"])

    # -- helpers --------------------------------------------------------------
    def _sync(self, i):
        o = self.objs[i]
        wet = round(self.wet_f[i], 2)
        snow = round(self.snow_f[i], 2)
        if wet != o["wet"] or snow != o["snow"]:
            o["wet"] = wet
            o["snow"] = snow
            self.changed.add(i)

    def _shrink(self, i, o, base, before, after):
        """A puddle / snowpack shrinks back towards its starting size as it dries / melts: its size above `base`
        falls in step with the cover (`before` -> `after`), and is exactly `base` again when the cover is gone.
        (They used to only ever grow: one shower left a 140 px puddle, one snowfall a 160 px drift, for good.)"""
        bw, bh = base
        if o["w"] <= bw and o["h"] <= bh:
            self.size_f.pop(i, None)
            return
        # the unrounded size is kept between calls: on the wire w / h have one decimal, and a step smaller than
        # that would otherwise be rounded away every time (the height stood still, then jumped at the end)
        cur = self.size_f.get(i)
        if cur is None or round(cur[0], 1) != o["w"] or round(cur[1], 1) != o["h"]:
            cur = [float(o["w"]), float(o["h"])]          # first call, or it has grown since
        k = after / before if before > 0.0 and after > 0.0 else 0.0
        cur = [bw + max(0.0, cur[0] - bw) * k, bh + max(0.0, cur[1] - bh) * k]
        if k > 0.0:
            self.size_f[i] = cur
        else:
            self.size_f.pop(i, None)
        w = round(cur[0], 1)
        h = round(cur[1], 1)
        if w != o["w"] or h != o["h"]:
            o["w"] = w
            o["h"] = h
            self.changed.add(i)

    @staticmethod
    def _nearest(xs, items, x):
        j = bisect.bisect_left(xs, x)
        if j == 0:
            return items[0][1]
        if j >= len(xs):
            return items[-1][1]
        return items[j][1] if xs[j] - x < x - xs[j - 1] else items[j - 1][1]

    # -- per tick -------------------------------------------------------------
    def update(self, dt, wind, rain_hits, snow_hits, freezing_y):
        objs = self.objs
        wet_f = self.wet_f
        snow_f = self.snow_f
        active = self.active
        buckets = self.buckets
        sync = self._sync
        self.tick += 1

        for k, n in rain_hits.items():
            if self.puddles:
                i = self._nearest(self._puddle_x, self.puddles, k * GROUND_STEP)
                o = objs[i]
                w = round(min(self.puddle_cap.get(i, PUDDLE_MAX_W), o["w"] + 0.06 * n), 1)
                if w > o["w"]:                     # (a puddle already wider than its hollow, from an old save, does not snap back)
                    o["w"] = w
                o["h"] = round(min(18.0, o["h"] + 0.01 * n), 1)
                wet_f[i] = 1.0
                active.add(i)
                self.changed.add(i)
            lst = buckets.get(k)
            if lst:
                inc = 0.01 * n
                for i in lst:
                    v = wet_f[i] + inc
                    wet_f[i] = 1.0 if v > 1.0 else v
                    active.add(i)
                    sync(i)
        for k, n in snow_hits.items():
            if self.snowpacks:
                i = self._nearest(self._snow_x, self.snowpacks, k * GROUND_STEP)
                o = objs[i]
                o["w"] = round(min(160.0, o["w"] + 0.05 * n), 1)
                o["h"] = round(min(14.0, o["h"] + 0.012 * n), 1)
                snow_f[i] = 1.0
                active.add(i)
                self.changed.add(i)
            lst = buckets.get(k)
            if lst:
                inc = 0.01 * n
                for i in lst:
                    v = snow_f[i] + inc
                    snow_f[i] = 1.0 if v > 1.0 else v
                    active.add(i)
                    sync(i)

        # wind lean, every 3rd tick for the objects that sway
        self.sway_dt += dt
        if self.tick % 3 == 0:
            d = self.sway_dt
            self.sway_dt = 0.0
            ease = min(1.0, d * 3.0)
            sway_f = self.sway_f
            sample = wind.sample
            storm = self.storm_sway
            for i in self.sway_idx:
                o = objs[i]
                su = sample(o["x"], o["y"] - o["h"] * 0.5)[0]
                target = su * 0.125
                target = -1.0 if target < -1.0 else (1.0 if target > 1.0 else target)
                if storm:
                    ts = storm.get(i)
                    if ts is not None and abs(ts) > abs(target):
                        target = ts          # a tornado's pull beats the ambient wind
                s = sway_f[i] + (target - sway_f[i]) * ease
                sway_f[i] = s
                q = round(s * 20.0) / 20.0 + 0.0
                if q != o["sway"]:
                    o["sway"] = q
                    self.changed.add(i)

        # slow drying / snow melt, every 4th tick
        self.decay_dt += dt
        if self.tick % 4 == 0 and active:
            d = self.decay_dt
            self.decay_dt = 0.0
            for i in list(active):
                o = objs[i]
                kind = o["kind"]
                if kind != "pond":
                    w0 = wet_f[i]
                    w = w0 - 0.01 * d
                    wet_f[i] = w if w > 0.0 else 0.0
                    if kind == "puddle" and w0 < 1.0:                     # (1.0 = rain is still topping it up)
                        self._shrink(i, o, PUDDLE_BASE, w0, wet_f[i])     # a puddle dries up again
                s = snow_f[i]
                if s > 0.0:
                    s0 = s
                    s -= (0.0008 if o["y"] < freezing_y else 0.005) * d
                    snow_f[i] = s if s > 0.0 else 0.0
                    if kind == "snowpack" and s0 < 1.0:                   # (1.0 = snow is still landing on it)
                        self._shrink(i, o, SNOWPACK_BASE, s0, snow_f[i])  # a snowpack melts away again
                sync(i)
                if snow_f[i] <= 0.0 and (o["kind"] == "pond" or wet_f[i] <= 0.0):
                    active.discard(i)

    # -- tornado damage (v2.3) --------------------------------------------------------------------
    def apply_tornadoes(self, dt, tornadoes) -> list:
        """Damage and sway for everything near a grounded tornado. `damage` only ever rises. Returns
        [(x, y_mid, delta, w, h)] for the objects damaged this tick (the caller throws debris from them)."""
        self.storm_sway = storm = {}
        hit = []
        if not tornadoes:
            return hit
        objs = self.objs
        dmg_f = self.dmg_f
        xs = self._dmg_x
        items = self.dmg_sorted
        tanh = math.tanh
        for t in tornadoes:
            gf = t.ground_factor
            if gf <= 0.0 or t.wind_ms < 20.0:
                continue
            rc = t.core_radius
            reach = rc * 5.0 + 90.0
            axis = t.x
            j0 = bisect.bisect_left(xs, axis - reach)
            j1 = bisect.bisect_right(xs, axis + reach)
            for j in range(j0, j1):
                i = items[j][1]
                o = objs[i]
                ox = o["x"]
                d = abs(ox - axis) - o["w"] * 0.5
                if d < 0.0:
                    d = 0.0
                v = t.local_wind(d)
                if o["kind"] in SWAYERS:
                    ts = tanh((axis - ox) / (0.6 * rc + 4.0)) * min(1.0, v / 22.0)
                    if abs(ts) > abs(storm.get(i, 0.0)):
                        storm[i] = ts
                cur = dmg_f[i]
                if cur >= 1.0:
                    continue
                vt = DAMAGE_VT[o["kind"]]
                exc = v - vt
                new = cur
                fac = DAMAGE_FACADE.get(o["kind"])
                if fac is not None and v > fac[0]:
                    target = fac[1] * (min(1.0, (v - fac[0]) / (vt - fac[0]))) ** 0.6
                    if cur < target:
                        new = min(target, cur + DAMAGE_K * ((v - fac[0]) / 25.0) * dt)
                if exc > 0.0:
                    new += DAMAGE_K * (exc / (60.0 if o["kind"] in DAMAGE_BIG else 25.0)) * dt
                if new <= cur:
                    continue
                if new > 1.0:
                    new = 1.0
                dmg_f[i] = new
                q = round(new, 2)
                if q != o.get("damage", 0.0):
                    o["damage"] = q
                    self.dmg_changed.add(i)
                hit.append((ox, o["y"] - o["h"] * 0.5, new - cur, o["w"], o["h"]))
        return hit

    def pop_damage(self) -> list:
        """[[id, dmg], ...] for the objects whose damage changed since the last call."""
        if not self.dmg_changed:
            return []
        objs = self.objs
        rows = [[objs[i]["id"], objs[i]["damage"]] for i in sorted(self.dmg_changed)]
        self.dmg_changed.clear()
        return rows

    def pop_rows(self) -> list:
        """Rows [id, sway, snow, wet, w, h] for everything that changed since the last call."""
        if not self.changed:
            return []
        objs = self.objs
        rows = []
        for i in sorted(self.changed):
            o = objs[i]
            rows.append([o["id"], o["sway"], o["snow"], o["wet"], o["w"], o["h"]])
        self.changed.clear()
        return rows

    # -- state ----------------------------------------------------------------
    def to_state(self) -> dict:
        return {"objs": [dict(o) for o in self.objs],
                "wet_f": list(self.wet_f), "snow_f": list(self.snow_f), "sway_f": list(self.sway_f),
                "active": sorted(self.active), "changed": sorted(self.changed),
                "sway_dt": self.sway_dt, "decay_dt": self.decay_dt, "tick": self.tick,
                "dmg_f": list(self.dmg_f), "dmg_changed": sorted(self.dmg_changed),
                "storm_sway": {str(k): v for k, v in self.storm_sway.items()},
                "size_f": {str(k): list(v) for k, v in self.size_f.items()}}

    @staticmethod
    def _without_retired(s: dict) -> dict:
        """`s` without the retired kinds (RETIRED_KINDS: the windsocks of a save made before they were removed).
        generate_world put them last, after every other object, so cutting the list there renumbers nothing: the
        ids of the rest stay what they were and so do the list indexes that wet_f, snow_f, sway_f, dmg_f, active,
        changed, dmg_changed, storm_sway and size_f are keyed by (those are cut to the same length). A retired
        object that is not in that final run (no save the game wrote has one) is left where it is: nothing moves,
        draws or damages it, it is an inert row. `s` itself is not changed."""
        objs = s["objs"]
        n = len(objs)
        while n and objs[n - 1]["kind"] in RETIRED_KINDS:
            n -= 1
        if n == len(objs):
            return s
        t = dict(s)
        t["objs"] = objs[:n]
        for k in ("wet_f", "snow_f", "sway_f", "dmg_f"):
            if k in t:
                t[k] = t[k][:n]
        for k in ("active", "changed", "dmg_changed"):
            if k in t:
                t[k] = [i for i in t[k] if int(i) < n]
        for k in ("storm_sway", "size_f"):
            if k in t:
                t[k] = {i: v for i, v in t[k].items() if int(i) < n}
        return t

    @classmethod
    def from_state(cls, s: dict, terrain=None) -> "ObjectField":
        s = cls._without_retired(s)
        objs = s["objs"]
        f = cls(objs, terrain)
        f.wet_f = [float(v) for v in s["wet_f"]]
        f.snow_f = [float(v) for v in s["snow_f"]]
        f.sway_f = [float(v) for v in s["sway_f"]]
        f.active = set(int(i) for i in s["active"])
        f.changed = set(int(i) for i in s["changed"])
        f.sway_dt = float(s["sway_dt"])
        f.decay_dt = float(s["decay_dt"])
        f.tick = int(s["tick"])
        # v2.3 (a v2.2 file has no damage: every object is whole)
        if "dmg_f" in s:
            f.dmg_f = [float(v) for v in s["dmg_f"]]
            f.dmg_changed = set(int(i) for i in s["dmg_changed"])
            f.storm_sway = {int(k): float(v) for k, v in s["storm_sway"].items()}
        # (absent in files saved before puddles and snowpacks shrank again: they continue from the rounded size)
        f.size_f = {int(k): [float(v[0]), float(v[1])] for k, v in s.get("size_f", {}).items()}
        return f

    def settle_sway(self, wind):
        """Start trees already leaning with the wind so a quiet world lists no object rows."""
        for i in self.sway_idx:
            o = self.objs[i]
            su = wind.sample(o["x"], o["y"] - o["h"] * 0.5)[0]
            t = max(-1.0, min(1.0, su * 0.125))
            self.sway_f[i] = t
            o["sway"] = round(t * 20.0) / 20.0 + 0.0


class GroundCover:
    """Snow and wetness of the terrain surface, sampled every GROUND_STEP px."""

    def __init__(self, terrain, w: int, interval: int):
        self.n = int(math.ceil(w / GROUND_STEP)) + 1
        self.surf = [terrain.height_at(i * GROUND_STEP) for i in range(self.n)]
        self.snow = [0.0] * self.n
        self.wet = [0.0] * self.n
        self.interval = max(1, int(interval))
        self.sent_snow = [0.0] * self.n
        self.sent_wet = [0.0] * self.n
        self.dt_acc = 0.0
        self.tick = 0
        self.dirty = False
        self.force = False

    def update(self, dt, rain_hits, snow_hits, freezing_y):
        n1 = self.n - 1
        wet = self.wet
        snow = self.snow
        for k, c in rain_hits.items():
            k = 0 if k < 0 else (n1 if k > n1 else k)
            v = wet[k] + 0.02 * c
            wet[k] = 1.0 if v > 1.0 else v
            self.dirty = True
        for k, c in snow_hits.items():
            k = 0 if k < 0 else (n1 if k > n1 else k)
            v = snow[k] + 0.03 * c
            snow[k] = 1.0 if v > 1.0 else v
            self.dirty = True
        self.dt_acc += dt
        self.tick += 1
        if self.tick % 3 == 0:
            d = self.dt_acc
            self.dt_acc = 0.0
            if self.dirty:
                surf = self.surf
                any_left = False
                dw = 0.012 * d
                for i in range(self.n):
                    w = wet[i]
                    if w > 0.0:
                        w -= dw
                        wet[i] = w if w > 0.0 else 0.0
                        any_left = True
                    s = snow[i]
                    if s > 0.0:
                        # snow lingers on ground above the freezing level, melts below it
                        s -= (0.0004 if surf[i] < freezing_y else 0.02) * d
                        snow[i] = s if s > 0.0 else 0.0
                        any_left = True
                self.dirty = any_left

    def maybe_emit(self, frame: int):
        """The `ground` dict for this frame, or None when unchanged / not an emit frame."""
        if self.force:
            self.force = False
            return {"step": GROUND_STEP, "snow": [round(v, 2) for v in self.snow],
                    "wet": [round(v, 2) for v in self.wet]}
        if frame % self.interval != 0:
            return None
        snow = [round(v, 2) for v in self.snow]
        wet = [round(v, 2) for v in self.wet]
        if snow == self.sent_snow and wet == self.sent_wet:
            return None
        self.sent_snow = snow
        self.sent_wet = wet
        return {"step": GROUND_STEP, "snow": snow, "wet": wet}

    def to_state(self) -> dict:
        return {"snow": list(self.snow), "wet": list(self.wet),
                "sent_snow": list(self.sent_snow), "sent_wet": list(self.sent_wet),
                "dt_acc": self.dt_acc, "tick": self.tick, "dirty": self.dirty,
                "interval": self.interval}

    @classmethod
    def from_state(cls, s: dict, terrain, w: int) -> "GroundCover":
        g = cls(terrain, w, int(s["interval"]))
        g.snow = [float(v) for v in s["snow"]]
        g.wet = [float(v) for v in s["wet"]]
        g.sent_snow = [float(v) for v in s["sent_snow"]]
        g.sent_wet = [float(v) for v in s["sent_wet"]]
        g.dt_acc = float(s["dt_acc"])
        g.tick = int(s["tick"])
        g.dirty = bool(s["dirty"])
        return g
