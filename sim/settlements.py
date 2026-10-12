"""Settlements and buildings: hamlets, villages, towns and cities.

Placement is two-phase so buildings never stand on a slope: layouts are generated
in their own x-space first, then each is dropped onto the flattest free span of
the terrain, the terrain under it is levelled (`Terrain.flatten`), and only after
the heightmap is finished (`Terrain.normalize`) do the callers read surface y.

A "row" is one (settlement, layer) group; buildings in a row never overlap.
Building dicts here carry static data only (kind, variant, w, h, floors, wall,
roof, seed, rx = centre relative to the layout origin, layer). objects.py turns
them into protocol objects with ids, x, y and the dynamic sway/snow/wet state.
"""
from __future__ import annotations

from naming import make_settlement_name

HOUSE_VARIANTS = ("cottage", "cabin", "farmhouse", "townhouse", "bungalow", "manor", "adobe", "hut", "station")
SETTLEMENT_GAP = 150.0      # free space kept between two settlements (flatten shoulders fit inside)
SHOULDER = 70.0
MODE_DENSITY = {"sparse": 0.5, "normal": 1.0, "dense": 1.6}
MODES = ("none", "sparse", "normal", "dense")
GUARANTEE_MIN_W = 2560      # normal/dense worlds at least this wide always get a village and a city
ROAD_STREET = "street"
ROAD_HIGHWAY = "highway"


def plan_roads(settlements: list, w: int, highway: bool = True) -> list:
    """Roads (protocol v2.3 `hello.roads`): one `street` over every settlement's whole span and, when there
    is at least one settlement, one `highway` across the whole world joining them and leaving at both
    edges. No settlements (`--settlements none`) -> no roads. `highway=False` (a polar preset: nothing
    crosses the ice) leaves the streets only."""
    if not settlements:
        return []
    roads = [{"x0": float(s["x0"]), "x1": float(s["x1"]), "kind": ROAD_STREET}
             for s in sorted(settlements, key=lambda s: s["x0"])]
    if highway:
        roads.append({"x0": 0.0, "x1": float(w), "kind": ROAD_HIGHWAY})
    return roads


def _clamp255(v):
    return max(0, min(255, int(v)))


def _col(rng, base, jitter=12):
    return [_clamp255(c + rng.uniform(-jitter, jitter)) for c in base]


def _choice_w(rng, pairs):
    total = sum(w for _, w in pairs)
    r = rng.uniform(0.0, total)
    for item, w in pairs:
        r -= w
        if r <= 0:
            return item
    return pairs[-1][0]


_WALL_WARM = [(222, 204, 172), (232, 220, 190), (210, 186, 150), (196, 170, 140), (236, 232, 222),
              (200, 120, 96), (176, 150, 120), (228, 200, 150)]
_ROOF_WARM = [(150, 62, 48), (128, 52, 40), (110, 76, 56), (92, 84, 80), (168, 84, 52), (70, 74, 86)]
_GLASS = [(96, 130, 168), (70, 100, 130), (130, 150, 170), (110, 120, 140), (150, 170, 190), (84, 112, 140)]
_CONCRETE = [(170, 168, 160), (150, 148, 144), (186, 178, 166), (160, 150, 140), (140, 140, 146)]


# The building style of the settlement being planned (v2.3 preset biomes): None = today's mix. While a biome
# world is planned `_STYLE` is "adobe" (desert towns), "hut" (tropical and savanna villages) or "station" (a
# research station), and make_building() swaps the temperate kinds for the style's houses. It is only ever
# set by plan_settlements / plan_lone_buildings with a plan, so a plain world never sees it.
_STYLE = None
# The temperate-only building kinds a biome does without (v2.3; presets.py `_BIOMES` "avoid", tuning): while a biome
# world is planned `_AVOID` holds them and make_building() puts something else in their slot: a house (of the
# style's variant) for a barn or a church, a water tower for a windmill.
_AVOID = frozenset()
_AVOID_MAP = {"barn": ("house", None), "church": ("house", None), "windmill": ("water_tower", None)}


def _restyle(kind, variant):
    st = _STYLE
    if st == "adobe":
        if kind in ("house", "barn", "windmill", "church", "apartment", "office", "skyscraper", "factory"):
            return "house", "adobe"
    elif st == "hut":
        if kind in ("barn", "windmill"):
            return "house", "hut"
        if kind == "house" and variant in (None, "cottage", "cabin", "farmhouse", "bungalow"):
            return "house", "hut"
    elif st == "station":
        if kind in ("house", "barn", "windmill", "church", "shop", "apartment", "office", "skyscraper", "factory"):
            return "house", "station"
    return kind, variant


def make_building(rng, kind, variant=None, target_h=None):
    """One building's static description (no position)."""
    if _AVOID and kind in _AVOID:
        kind, variant = _AVOID_MAP.get(kind, ("house", None))
    if _STYLE is not None:
        kind, variant = _restyle(kind, variant)
    seed = rng.getrandbits(31)
    b = {"kind": kind, "seed": seed, "layer": 0}
    if kind == "house":
        v = variant or _choice_w(rng, [("cottage", 3), ("cabin", 2), ("farmhouse", 2.5),
                                        ("bungalow", 1.5), ("townhouse", 0.5), ("manor", 0.3)])
        b["variant"] = v
        if v == "cottage":
            b.update(w=rng.uniform(52, 66), h=rng.uniform(42, 52), floors=1,
                     wall=_col(rng, rng.choice(_WALL_WARM)), roof=_col(rng, rng.choice(_ROOF_WARM)))
        elif v == "cabin":
            b.update(w=rng.uniform(46, 60), h=rng.uniform(36, 46), floors=1,
                     wall=_col(rng, (134, 96, 62), 14), roof=_col(rng, (74, 62, 56), 10))
        elif v == "farmhouse":
            b.update(w=rng.uniform(66, 86), h=rng.uniform(50, 64), floors=rng.randint(1, 2),
                     wall=_col(rng, rng.choice(_WALL_WARM)), roof=_col(rng, rng.choice(_ROOF_WARM)))
        elif v == "townhouse":
            fl = rng.randint(2, 3)
            b.update(w=rng.uniform(38, 52), h=rng.uniform(70, 96) * (0.85 + 0.15 * fl / 3), floors=fl,
                     wall=_col(rng, rng.choice([(176, 112, 90), (168, 150, 130), (140, 136, 134),
                                                 (200, 184, 160), (120, 96, 88)])),
                     roof=_col(rng, (82, 86, 98), 12))
        elif v == "bungalow":
            b.update(w=rng.uniform(70, 92), h=rng.uniform(36, 46), floors=1,
                     wall=_col(rng, rng.choice(_WALL_WARM)), roof=_col(rng, (176, 98, 66), 14))
        elif v == "adobe":      # sun-dried brick, flat roof (desert towns)
            b.update(w=rng.uniform(54, 74), h=rng.uniform(36, 50), floors=rng.randint(1, 2),
                     wall=_col(rng, rng.choice([(212, 184, 140), (200, 166, 120), (222, 200, 160)]), 8),
                     roof=_col(rng, (188, 160, 118), 8))
        elif v == "hut":        # timber and thatch (tropical and savanna villages)
            b.update(w=rng.uniform(38, 52), h=rng.uniform(32, 42), floors=1,
                     wall=_col(rng, (150, 112, 74), 12), roof=_col(rng, (176, 150, 84), 10))
        elif v == "station":    # prefabricated polar-station block
            b.update(w=rng.uniform(60, 92), h=rng.uniform(38, 48), floors=1,
                     wall=_col(rng, rng.choice([(204, 78, 46), (196, 92, 52), (214, 140, 60)]), 8),
                     roof=_col(rng, (62, 66, 76), 6))
        else:  # manor
            b.update(w=rng.uniform(100, 132), h=rng.uniform(70, 92), floors=rng.randint(2, 3),
                     wall=_col(rng, rng.choice([(206, 196, 176), (190, 178, 160), (224, 214, 196)])),
                     roof=_col(rng, rng.choice([(64, 66, 78), (120, 52, 44)]), 8))
    elif kind == "barn":
        b["variant"] = rng.choice(["red", "weathered"])
        wall = (150, 52, 44) if b["variant"] == "red" else (120, 96, 80)
        b.update(w=rng.uniform(80, 110), h=rng.uniform(56, 76), floors=1,
                 wall=_col(rng, wall, 10), roof=_col(rng, (78, 70, 70), 10))
    elif kind == "church":
        b["variant"] = rng.choice(["steeple", "tower"])
        b.update(w=rng.uniform(50, 70), h=rng.uniform(110, 150), floors=1,
                 wall=_col(rng, (204, 198, 186), 10), roof=_col(rng, (84, 88, 100), 8))
    elif kind == "windmill":
        b["variant"] = "tower"
        b.update(w=rng.uniform(40, 56), h=rng.uniform(90, 130), floors=1,
                 wall=_col(rng, (214, 200, 176), 10), roof=_col(rng, (110, 70, 50), 10))
    elif kind == "shop":
        b["variant"] = rng.choice(["awning", "corner"])
        b.update(w=rng.uniform(44, 60), h=rng.uniform(50, 68), floors=rng.randint(1, 2),
                 wall=_col(rng, rng.choice(_WALL_WARM + [(120, 150, 160), (150, 120, 150)])),
                 roof=_col(rng, rng.choice(_ROOF_WARM)))
    elif kind == "apartment":
        h = target_h if target_h else rng.uniform(95, 170)
        h = max(90.0, min(190.0, h))
        b["variant"] = rng.choice(["block", "terraced"])
        b.update(w=rng.uniform(60, 86), h=h, floors=max(3, int(round(h / 21.0))),
                 wall=_col(rng, rng.choice(_CONCRETE + [(176, 130, 104)])), roof=_col(rng, (110, 110, 116), 8))
    elif kind == "office":
        h = target_h if target_h else rng.uniform(130, 240)
        h = max(120.0, min(260.0, h))
        b["variant"] = rng.choice(["glass", "slab"])
        b.update(w=rng.uniform(54, 80), h=h, floors=max(6, int(round(h / 16.0))),
                 wall=_col(rng, rng.choice(_GLASS), 10), roof=_col(rng, (120, 122, 128), 8))
    elif kind == "skyscraper":
        h = target_h if target_h else rng.uniform(230, 390)
        h = max(200.0, min(400.0, h))
        b["variant"] = rng.choice(["glass", "stepped", "spire"])
        b.update(w=rng.uniform(46, 70), h=h, floors=max(14, int(round(h / 11.0))),
                 wall=_col(rng, rng.choice(_GLASS), 10), roof=_col(rng, (96, 100, 108), 8))
    elif kind == "factory":
        b["variant"] = rng.choice(["chimney", "sheds"])
        b.update(w=rng.uniform(120, 170), h=rng.uniform(70, 100), floors=1,
                 wall=_col(rng, (150, 92, 72), 12), roof=_col(rng, (110, 112, 116), 8))
    elif kind == "water_tower":
        b["variant"] = "tank"
        b.update(w=rng.uniform(30, 44), h=rng.uniform(80, 110), floors=1,
                 wall=_col(rng, (150, 155, 160), 8), roof=_col(rng, (120, 120, 126), 8))
    elif kind == "streetlamp":
        b["variant"] = "lamp"
        b.update(w=6.0, h=rng.uniform(28, 34), floors=1, wall=[60, 60, 66], roof=[240, 220, 150])
    else:
        raise ValueError(kind)
    b["w"] = round(b["w"], 1)
    b["h"] = round(b["h"], 1)
    return b


def _pack(rng, items, gap_lo, gap_hi):
    """Lay items left to right with random gaps; sets item['rx'] (centre). Returns length."""
    x = 0.0
    for b in items:
        b["rx"] = x + b["w"] / 2.0
        x += b["w"]
        if b is not items[-1]:
            x += rng.uniform(gap_lo, gap_hi)
    return x


def _with_lamps(rng, items, prob):
    out = []
    for i, b in enumerate(items):
        out.append(b)
        if i < len(items) - 1 and rng.random() < prob:
            out.append(make_building(rng, "streetlamp"))
    return out


def _back_row(rng, length, n, pool, scale=1.0):
    """A second row behind the main one (layer 1): n buildings spread over the span, no overlaps."""
    items = []
    for _ in range(n):
        kind, variant = pool(rng)
        b = make_building(rng, kind, variant)
        if scale != 1.0 and kind in ("apartment", "office", "skyscraper"):
            b = make_building(rng, kind, variant, target_h=b["h"] * scale)
        b["layer"] = 1
        items.append(b)
    total_w = sum(b["w"] for b in items)
    slack = length - total_w
    if slack < 0:
        items = items[:max(1, len(items) - 2)]
        total_w = sum(b["w"] for b in items)
        slack = max(0.0, length - total_w)
    x = rng.uniform(0.0, max(0.0, slack) * 0.3)
    gap = max(8.0, (slack - x) / max(1, len(items)))
    for b in items:
        x += rng.uniform(gap * 0.4, gap)
        b["rx"] = x + b["w"] / 2.0
        x += b["w"]
    return [b for b in items if b["rx"] + b["w"] / 2.0 <= length + 1.0]


def _village_layout(rng):
    nh = rng.randint(3, 5)
    left_n = nh // 2
    right_n = nh - left_n

    def house():
        return make_building(rng, "house")

    seq = []
    seq.append(make_building(rng, "barn" if rng.random() < 0.6 else "windmill"))
    seq.extend(house() for _ in range(left_n))
    seq.append(make_building(rng, "church"))
    if rng.random() < 0.5:
        seq.append(make_building(rng, "shop"))
    seq.extend(house() for _ in range(right_n))
    r = rng.random()
    seq.append(make_building(rng, "windmill" if r < 0.4 else ("barn" if r < 0.8 else "water_tower")))
    seq = _with_lamps(rng, seq, 0.28)
    length = _pack(rng, seq, 8.0, 24.0)
    back = _back_row(rng, length, rng.randint(2, 4),
                     lambda r: ("house", _choice_w(r, [("cottage", 3), ("cabin", 2), ("farmhouse", 2)])))
    return seq + back, length


def _hamlet_layout(rng):
    n = rng.randint(2, 4)
    seq = [make_building(rng, "house", _choice_w(rng, [("cabin", 3), ("cottage", 3), ("farmhouse", 2)]))
           for _ in range(n)]
    if rng.random() < 0.6:
        seq.insert(rng.randint(0, len(seq)), make_building(rng, "barn"))
    seq = _with_lamps(rng, seq, 0.15)
    length = _pack(rng, seq, 10.0, 30.0)
    return seq, length


def _town_pick(rng, u, state):
    """(kind, variant) for the next town building at normalised distance u from the centre."""
    if u < 0.3:
        if not state["church"] and u < 0.2:
            state["church"] = True
            return ("church", None)
        if not state["office"] and rng.random() < 0.12:
            state["office"] = True
            return ("office", None)
        return _choice_w(rng, [(("shop", None), 3.5), (("apartment", None), 3),
                                (("house", "townhouse"), 2)])
    if u < 0.7:
        return _choice_w(rng, [(("house", "townhouse"), 3.5), (("house", None), 3),
                                (("shop", None), 2), (("apartment", None), 1.5)])
    return _choice_w(rng, [(("house", "bungalow"), 2), (("house", "farmhouse"), 2),
                            (("house", "cottage"), 2.5), (("house", "townhouse"), 2),
                            (("barn", None), 1)])


def _town_layout(rng):
    length = rng.uniform(480, 650)
    state = {"church": False, "office": False}
    seq = []
    x = 0.0
    while x < length:
        u = abs((x + 28.0) / length * 2.0 - 1.0)
        kind, variant = _town_pick(rng, u, state)
        b = make_building(rng, kind, variant)
        seq.append(b)
        x += b["w"] + 14.0
    if not state["church"] and len(seq) > 2:
        seq[len(seq) // 2] = make_building(rng, "church")
    r = rng.random()
    if r < 0.4:
        seq.append(make_building(rng, "water_tower"))
    elif r < 0.7:
        seq.append(make_building(rng, "factory"))
    seq = _with_lamps(rng, seq, 0.3)
    total = _pack(rng, seq, 6.0, 20.0)
    back = _back_row(rng, total, rng.randint(3, 5),
                     lambda r: _choice_w(r, [(("house", "townhouse"), 3), (("apartment", None), 2),
                                              (("house", "cottage"), 1.5)]))
    return seq + back, total


def _city_pick(rng, u):
    if u < 0.25:
        return _choice_w(rng, [(("skyscraper", None), 6), (("office", None), 4)])
    if u < 0.5:
        return _choice_w(rng, [(("office", None), 5), (("skyscraper", None), 1.5), (("apartment", None), 3.5)])
    if u < 0.75:
        return _choice_w(rng, [(("apartment", None), 5), (("shop", None), 3), (("office", None), 2)])
    return _choice_w(rng, [(("house", "townhouse"), 5), (("shop", None), 2.5), (("apartment", None), 1.5),
                            (("factory", None), 0.8), (("water_tower", None), 1.2)])


def _tower_height(rng, kind, u):
    core = 1.0 - 0.78 * (u ** 1.25)
    if kind == "skyscraper":
        return rng.uniform(0.82, 1.1) * 390.0 * core
    if kind == "office":
        return rng.uniform(0.8, 1.15) * 255.0 * core
    return None


def _city_try(rng):
    length = rng.uniform(720, 980)
    seq = []
    x = 0.0
    while x < length:
        u = abs((x + 35.0) / length * 2.0 - 1.0)
        kind, variant = _city_pick(rng, u)
        b = make_building(rng, kind, variant, target_h=_tower_height(rng, kind, u))
        seq.append(b)
        x += b["w"] + (6.0 if u < 0.5 else 14.0)
    seq = _with_lamps(rng, seq, 0.12)
    total = _pack(rng, seq, 3.0, 9.0)
    # tallest tower in the middle
    towers = [b for b in seq if b["kind"] in ("skyscraper", "office")]
    if len(towers) >= 2:
        mid = min(towers, key=lambda b: abs(b["rx"] - total / 2.0))
        tall = max(towers, key=lambda b: b["h"])
        if tall is not mid:
            mid["h"], tall["h"] = tall["h"], mid["h"]
            mid["floors"], tall["floors"] = tall["floors"], mid["floors"]
        mid["h"] = round(min(400.0, mid["h"] + 12.0), 1)
        mid["floors"] = max(mid["floors"], int(round(mid["h"] / 11.0)))
    back_n = rng.randint(10, 14)
    back = _back_row(rng, total, back_n,
                     lambda r: _choice_w(r, [(("office", None), 3), (("apartment", None), 3),
                                              (("skyscraper", None), 2)]), scale=0.72)
    # back-row towers must stay below the front-row core
    front_max = max((b["h"] for b in seq if b["kind"] in ("skyscraper", "office")), default=200.0)
    for b in back:
        if b["h"] > front_max * 0.85:
            b["h"] = round(front_max * 0.85, 1)
    return seq + back, total


def _city_layout(rng):
    """A city always has a dense core: reroll the layout until it has at least five towers."""
    best = None
    for _ in range(40):
        items, total = _city_try(rng)
        towers = sum(1 for b in items if b["layer"] == 0 and b["kind"] in ("office", "skyscraper"))
        if best is None or towers > best[0]:
            best = (towers, items, total)
        if towers >= 5:
            break
    return best[1], best[2]


def _station_layout(rng):
    """A research station: a few prefabricated blocks and a water tank, no church, no barn."""
    seq = [make_building(rng, "house", "station") for _ in range(rng.randint(3, 5))]
    seq.insert(rng.randint(0, len(seq)), make_building(rng, "water_tower"))
    seq = _with_lamps(rng, seq, 0.3)
    length = _pack(rng, seq, 10.0, 22.0)
    return seq, length


_LAYOUTS = {"hamlet": _hamlet_layout, "village": _village_layout,
            "town": _town_layout, "city": _city_layout}


def _population(rng, kind, buildings):
    per = {"cottage": 3, "cabin": 2, "farmhouse": 5, "townhouse": 6, "bungalow": 3, "manor": 9,
           "adobe": 4, "hut": 3, "station": 6}
    total = 0
    for b in buildings:
        if b["kind"] == "house":
            total += per.get(b.get("variant"), 3)
        elif b["kind"] == "apartment":
            total += b["floors"] * 8
        elif b["kind"] == "shop":
            total += 2
    factor = {"hamlet": 1.5, "village": 4.0, "town": 12.0, "city": 40.0}[kind]
    total = int(total * factor * rng.uniform(0.9, 1.25))
    return max(10, int(round(total, -1)))


class Occupancy:
    """Sorted-free list of occupied [a, b] x-intervals."""

    def __init__(self):
        self.spans = []

    def free(self, a, b):
        for s0, s1 in self.spans:
            if a < s1 and b > s0:
                return False
        return True

    def add(self, a, b):
        self.spans.append((a, b))

    def to_state(self):
        return [list(s) for s in self.spans]


def plan_counts(mode: str, w: int) -> dict:
    """How many settlements of each kind to attempt, in placement priority order."""
    if mode == "none":
        return {}
    d = MODE_DENSITY[mode]
    u = w / 1280.0
    counts = {
        "city": int(round(u / 3.0 * d)),
        "village": max(1, int(round(u / 1.6 * d))),
        "town": int(round(u / 3.5 * d)),
        "hamlet": int(round(u / 1.5 * d)),
    }
    if mode in ("normal", "dense") and w >= GUARANTEE_MIN_W:
        counts["city"] = max(1, counts["city"])
    if w < 2560 and mode != "dense":
        counts["city"] = 0
    return counts


EDGE_MARGIN = 140.0
FILL_FRACTION = {"sparse": 0.7, "normal": 0.84, "dense": 0.97}   # share of the free width settlements may use


def _place_all(rng, terrain, occ, layouts, must_keep=0, fill=0.84):
    """Slot-based placement: every layout that fits is guaranteed a free span.

    `layouts` is [(kind, payload, length)] in priority order (cities first, hamlets last); the
    lowest-priority ones are dropped until the rest fit (the first `must_keep` never are). The
    survivors are shuffled across the world with random slack between them, then each is slid to
    the flattest spot within its own slot and (settlements only) the ground under it is levelled.
    Returns [(kind, payload, length, x0)]."""
    avail = terrain.w - 2.0 * EDGE_MARGIN
    chosen = list(layouts)
    keep = {id(item) for item in layouts[:must_keep]}

    def used():
        return sum(L for _k, _i, L in chosen) + SETTLEMENT_GAP * max(0, len(chosen) - 1)

    while len(chosen) > must_keep and used() > avail * fill:
        chosen.pop()
    while chosen and used() > avail:      # tiny worlds: even the mandatory ones must physically fit
        chosen.pop()
    if not chosen:
        return []
    rng.shuffle(chosen)
    n = len(chosen)
    total = sum(L for _k, _i, L in chosen) + SETTLEMENT_GAP * (n - 1)
    slack = max(0.0, avail - total)
    cuts = sorted(rng.uniform(0.0, slack) for _ in range(n))
    nominal = []
    for i in range(n):
        nominal.append(EDGE_MARGIN + cuts[i] + sum(c[2] + SETTLEMENT_GAP for c in chosen[:i]))
    placed = []
    prev_end = EDGE_MARGIN - SETTLEMENT_GAP
    later = [sum(c[2] + SETTLEMENT_GAP for c in chosen[i + 1:]) for i in range(n)]   # room the rest needs
    for i, (kind, payload, L) in enumerate(chosen):
        lo = max(nominal[i] - 200.0, prev_end + SETTLEMENT_GAP, EDGE_MARGIN)
        nxt = nominal[i + 1] - SETTLEMENT_GAP if i + 1 < n else terrain.w - EDGE_MARGIN
        hi = max(min(nominal[i] + 200.0, nxt - L), lo)
        best = None
        for _t in range(14):
            x0 = rng.uniform(lo, hi)
            score = terrain.span_range(x0, x0 + L) + rng.uniform(0.0, 6.0)
            if terrain.protected and any(x0 < b and x0 + L > a for a, b in terrain.protected):
                score += 1.0e5          # a preset's summit: only if nothing else in the slot is free
            if best is None or score < best[0]:
                best = (score, x0)
        if best[0] >= 1.0e5:
            # the whole slot lies on a summit: slide to just beside it if there is room, else skip an
            # optional settlement (a mandatory one keeps its least-bad spot)
            alt = None
            for a, b in terrain.protected:
                for cand in (b + 10.0, a - L - 10.0):
                    if (cand >= max(prev_end + SETTLEMENT_GAP, EDGE_MARGIN)
                            and cand + L + later[i] <= terrain.w - EDGE_MARGIN
                            and not any(cand < b2 and cand + L > a2 for a2, b2 in terrain.protected)):
                        sc = terrain.span_range(cand, cand + L)
                        if alt is None or sc < alt[0]:
                            alt = (sc, cand)
            if alt is not None:
                best = alt
            elif id(chosen[i]) not in keep:
                continue
        x0 = best[1]
        placed.append((kind, payload, L, x0))
        prev_end = x0 + L
        if kind == "pond":
            occ.add(x0, x0 + L)
        else:
            occ.add(x0 - SETTLEMENT_GAP * 0.5, x0 + L + SETTLEMENT_GAP * 0.5)
            terrain.flatten(x0, x0 + L, terrain.mean_over(x0, x0 + L), SHOULDER)
    return placed


KINDS_DESC = ("city", "town", "village", "hamlet")
KIND_RANK = {"hamlet": 0, "village": 1, "town": 2, "city": 3}
NAMED_PAD = 75.0            # half of SETTLEMENT_GAP: the free margin kept on each side of a settlement


def _layout_for(rng, kind, style):
    if style == "station" and kind == "hamlet":
        return _station_layout(rng)
    return _LAYOUTS[kind](rng)


def _free(occ, a, b, ignore=()):
    """Occupancy.free(a, b) that does not count the spans in `ignore` (a named town's own summit)."""
    for s0, s1 in occ.spans:
        if (s0, s1) in ignore:
            continue
        if a < s1 and b > s0:
            return False
    return True


def _find_near(terrain, occ, cx, length, ignore=(), reach=None):
    """Left edge of the free span of `length` px nearest to the wish `cx` (centre), or None. `reach` limits how
    far from the wish the centre may be (default: half the length plus a margin)."""
    lo = EDGE_MARGIN
    hi = terrain.w - EDGE_MARGIN - length
    if hi < lo:
        return None
    base = min(max(cx - length / 2.0, lo), hi)
    if reach is None:
        reach = length / 2.0 + EDGE_MARGIN + 150.0     # a named place stays close to its landmark (or is not placed)
    for off in range(0, int(terrain.w), 20):
        for sgn in ((1.0,) if off == 0 else (1.0, -1.0)):
            x0 = base + sgn * off
            if abs(x0 + length / 2.0 - cx) > reach:
                continue
            if lo <= x0 <= hi and _free(occ, x0, x0 + length, ignore):
                return x0
    return None


def _place_free(rng, terrain, occ, layouts):
    """Generic placement for a biome world: every layout, in priority order, goes to the flattest of a few
    random free spans; one that finds none is skipped. Returns [(kind, payload, L, x0)]."""
    placed = []
    for kind, payload, length in layouts:
        hi = terrain.w - EDGE_MARGIN - length
        if hi < EDGE_MARGIN:
            continue
        best = None
        for _t in range(30):
            x0 = rng.uniform(EDGE_MARGIN, hi)
            if not occ.free(x0, x0 + length):
                continue
            score = terrain.span_range(x0, x0 + length) + rng.uniform(0.0, 6.0)
            if best is None or score < best[0]:
                best = (score, x0)
        if best is None:
            continue
        x0 = best[1]
        placed.append((kind, payload, length, x0))
        if kind == "pond":
            occ.add(x0, x0 + length)
        else:
            occ.add(x0 - NAMED_PAD, x0 + length + NAMED_PAD)
            terrain.flatten(x0, x0 + length, terrain.mean_over(x0, x0 + length), SHOULDER)
    return placed


def _plan_biome(rng, terrain, mode: str, plan: dict):
    """plan_settlements() for a preset world (protocol "Preset biome (v2.3)"). `plan` = presets.settlement_plan():
    the named settlements (a real town, city or station on a landmark takes the landmark's name and is placed
    first, at the largest kind up to the one listed that fits at that spot) and the generic remainder,
    capped in kind and scaled in number by the biome. Nothing is built on a summit; the lowest ground may be."""
    global _STYLE, _AVOID
    occ = Occupancy()
    summits = list(getattr(terrain, "summits", None) or terrain.protected)
    for a, b in summits:
        occ.add(a, b)
    placed = []                      # (kind, items, length, x0, name or None)
    used_names: set = set()
    ponds_slots = []
    prev_style, prev_avoid = _STYLE, _AVOID
    _AVOID = frozenset(plan.get("avoid", ()))
    try:
        if mode != "none":
            named = sorted(plan["named"], key=lambda n: (not n.get("station"), -KIND_RANK[n["kind"]], n["x"]))
            for spec in named:
                _STYLE = spec["style"]
                got = None
                # a town on a landmark that the relative scale makes a "summit" (Cherrapunji, Baguio, Calama: the
                # high ground IS the town) may stand there, unless the landmark is the preset's highest point
                own = ()
                if not spec.get("top"):
                    own = tuple(sp for sp in summits if sp[0] <= spec["x"] <= sp[1])
                # the station town is always placed: if no kind fits near its landmark (Sydney: the Blue Mountains
                # span fills the coastal end) a second pass takes the nearest free span anywhere
                for far in ((False, True) if spec.get("station") else (False,)):
                    for kind in KINDS_DESC:
                        if KIND_RANK[kind] > KIND_RANK[spec["kind"]] or KIND_RANK[kind] < KIND_RANK[spec["min"]]:
                            continue
                        items, length = _layout_for(rng, kind, spec["style"])
                        x0 = _find_near(terrain, occ, spec["x"], length, own, reach=float(terrain.w) if far else None)
                        if x0 is not None:
                            got = (kind, items, length, x0, spec["name"])
                            break
                    if got is not None:
                        break
                if got is None:
                    continue
                kind, items, length, x0, name = got
                occ.add(x0 - NAMED_PAD, x0 + length + NAMED_PAD)
                terrain.flatten(x0, x0 + length, terrain.mean_over(x0, x0 + length), SHOULDER)
                placed.append(got)
                used_names.add(name)
            # the generic remainder
            _STYLE = plan["style"]
            d = MODE_DENSITY[mode] * plan["density"]
            u = terrain.w / 1280.0
            counts = {"city": int(round(u / 3.0 * d)), "village": int(round(u / 1.6 * d)),
                      "town": int(round(u / 3.5 * d)), "hamlet": int(round(u / 1.5 * d))}
            for k in counts:
                if KIND_RANK[k] > KIND_RANK[plan["max"]]:
                    counts[k] = 0
            for g in placed:
                counts[g[0]] = max(0, counts[g[0]] - 1)
            gen = []
            for kind in KINDS_DESC:
                for _ in range(counts[kind]):
                    items, length = _layout_for(rng, kind, plan["style"])
                    gen.append((kind, items, length))
        else:
            gen = []
        # the ponds take their slots before the unnamed settlements do (the named ones are already down)
        n_ponds = int(round(terrain.w / 1280.0 * 0.8 * plan["ponds"]))
        pond_layouts = []
        for _ in range(n_ponds):
            pw = rng.uniform(70.0, 150.0)
            pond_layouts.append(("pond", pw, pw + 160.0))
        for kind, payload, length, x0 in _place_free(rng, terrain, occ, pond_layouts + gen):
            if kind == "pond":
                ponds_slots.append((x0, length, payload))
            else:
                placed.append((kind, payload, length, x0, None))
    finally:
        _STYLE, _AVOID = prev_style, prev_avoid
    settlements = []
    buildings = []
    for sid, (kind, items, length, x0, name) in enumerate(sorted(placed, key=lambda g: g[3]), start=1):
        for b in items:
            b["x"] = x0 + b["rx"]
            b["settlement"] = sid
        buildings.extend(items)
        settlements.append({"id": sid, "kind": kind,
                            "name": name if name else make_settlement_name(rng, kind, used_names),
                            "x0": round(x0, 1), "x1": round(x0 + length, 1),
                            "population": _population(rng, kind, items)})
    return settlements, buildings, occ, ponds_slots


def plan_settlements(rng, terrain, mode: str, plan: dict | None = None):
    """Place settlements (and reserve slots for ponds), level the ground under the settlements.

    Returns (settlements, buildings, occ, pond_slots). Building dicts get absolute 'x' (centre) and
    'settlement' id; y is filled in later by objects.py. pond_slots is [(x0, slot_length, pond_w)].
    With a `plan` (a preset's biome, v2.3) see _plan_biome; without one this is the v2.2 planner, untouched."""
    if plan is not None:
        return _plan_biome(rng, terrain, mode, plan)
    occ = Occupancy()
    for a, b in terrain.protected:      # preset landmarks: nothing is built or levelled on a summit
        occ.add(a, b)
    settlements = []
    buildings = []
    counts = plan_counts(mode, terrain.w)
    first = []          # mandatory: first city, first village, first pond
    rest = []
    n_ponds = max(1, int(round(terrain.w / 1280.0 * 0.8)))

    def make(kind):
        items, length = _LAYOUTS[kind](rng)
        return (kind, items, length)

    def pond():
        pw = rng.uniform(70.0, 150.0)
        return ("pond", pw, pw + 160.0)

    for i in range(counts.get("city", 0)):
        (first if i == 0 else rest).append(make("city"))
    for i in range(counts.get("village", 0)):
        (first if i == 0 else rest).append(make("village"))
    first.append(pond())
    for _ in range(n_ponds - 1):
        rest.append(pond())
    for kind in ("town", "hamlet"):
        for _ in range(counts.get(kind, 0)):
            rest.append(make(kind))
    placed = _place_all(rng, terrain, occ, first + rest, must_keep=len(first),
                        fill=FILL_FRACTION.get(mode, 0.84))
    used_names: set = set()
    ponds = [(x0, L, payload) for kind, payload, L, x0 in placed if kind == "pond"]
    towns = sorted((p for p in placed if p[0] != "pond"), key=lambda p: p[3])
    for sid, (kind, items, length, x0) in enumerate(towns, start=1):
        for b in items:
            b["x"] = x0 + b["rx"]
            b["settlement"] = sid
        buildings.extend(items)
        settlements.append({"id": sid, "kind": kind,
                            "name": make_settlement_name(rng, kind, used_names),
                            "x0": round(x0, 1), "x1": round(x0 + length, 1),
                            "population": _population(rng, kind, items)})
    return settlements, buildings, occ, ponds


def plan_lone_buildings(rng, terrain, occ, mode: str, plan: dict | None = None):
    """Scattered lone farmhouses/cabins (with the odd barn) between settlements. With a biome `plan` their
    number follows plan["lone"] and they take the plan's building style."""
    global _STYLE, _AVOID
    out = []
    if mode == "none":
        return out
    u = terrain.w / 1280.0
    scale = 1.0 if plan is None else plan["lone"]
    n = int(round(u * 2.2 * MODE_DENSITY[mode] * scale))
    prev_style, prev_avoid = _STYLE, _AVOID
    if plan is not None:
        _STYLE = plan["style"]
        _AVOID = frozenset(plan.get("avoid", ()))
    try:
        out = _lone_group(rng, terrain, occ, n)
    finally:
        _STYLE, _AVOID = prev_style, prev_avoid
    return out


def _lone_group(rng, terrain, occ, n):
    out = []
    for _ in range(n):
        r = rng.random()
        group = []
        if r < 0.45:
            group.append(make_building(rng, "house", "farmhouse"))
            if rng.random() < 0.5:
                group.append(make_building(rng, "barn"))
        elif r < 0.85:
            group.append(make_building(rng, "house", "cabin"))
        else:
            group.append(make_building(rng, "house", "cottage"))
        length = _pack(rng, group, 10.0, 24.0)
        best = None
        for _try in range(40):
            x0 = rng.uniform(80.0, terrain.w - 80.0 - length)
            if not occ.free(x0 - 30.0, x0 + length + 30.0):
                continue
            score = terrain.span_range(x0, x0 + length) + rng.uniform(0.0, 3.0)
            if best is None or score < best[0]:
                best = (score, x0)
        if best is None:
            continue
        x0 = best[1]
        occ.add(x0 - 20.0, x0 + length + 20.0)
        terrain.flatten(x0 - 4.0, x0 + length + 4.0, None, 30.0)
        for b in group:
            b["x"] = x0 + b["rx"]
        out.extend(group)
    return out


def plan_ponds(rng, terrain, occ, slots, keep_lowest=False):
    """Dig the ponds into their reserved slots. Each pond goes to the lowest, flattest spot of its
    slot (a local minimum of the heightmap: the pad is levelled a few px below everything within
    reach, so water collects there). `keep_lowest` (preset worlds, v2.3): never below the lowest ground, so a
    pond cannot shift a preset's real heights."""
    step = terrain.step
    n = terrain.n
    ys = terrain.y
    ponds = []
    for x0, L, pw in slots:
        lo = x0 + pw / 2.0 + 20.0
        hi = x0 + L - pw / 2.0 - 20.0
        reach = pw / 2.0 + 45.0
        best = None
        for _t in range(16):
            cx = rng.uniform(lo, max(lo, hi))
            i0 = max(0, int((cx - reach) / step))
            i1 = min(n - 1, int((cx + reach) / step) + 1)
            seg = ys[i0:i1 + 1]
            score = (max(seg) - min(seg)) + rng.uniform(0.0, 2.0)
            if best is None or score < best[0]:
                best = (score, cx, max(seg))
        _s, cx, ymax = best
        level = ymax + 5.0
        if keep_lowest:
            level = min(level, max(ys))
        terrain.flatten(cx - pw / 2, cx + pw / 2, level, 40.0)
        ponds.append({"x": cx, "w": pw, "h": rng.uniform(14.0, 26.0), "seed": rng.getrandbits(31)})
    return ponds
