"""Standing water, overland flow, storm drains and flooding (protocol v2.4 and v2.4.1; PLAN.md Phases L and N).

The world is 2-D, so water is *area* (px^2) in the cross-section. Rain is already tallied on the ground in 32 px buckets
(`rain_hits`: landed particles per bucket per tick). This module turns that tally into water that runs over the ground and
lies in the hollows of the heightmap. The rule it keeps (the user's, 2026-10-11): **water never stands above ground that
has lower ground next to it.** There is no virtual curb and no wall.

* **Basin map** (pure, derived from the terrain, the settlement spans and the pond objects; nothing of it is saved).
  The ground is cut at its *divides* (the highest ground between two neighbouring hollows) into segments; each segment
  holds one element: a natural hollow (`basin`), a settlement street that is itself a hollow (`street`), a `pond` object
  (absorbs the water that runs into it), or the world edge (`edge`, an outflow). A hollow whose lower rim is less than
  `BASIN_MIN_DEPTH` above its bed is not a hollow: its segment joins the neighbour across the lower divide (water crosses
  it as if it were level ground). **A street is ordinary ground**: it is an element of its own only when the terrain makes
  it a hollow at least that deep; otherwise it dissolves into the neighbour across its lower divide, and the cells of its
  span only carry a settlement mark (for the drains and the flood report).
* **The film.** Water on the ground is a *film*: one volume `S` (px^2) per 32 px cell (cell `k` is centred at `x = 32 k`,
  the rain bucket `k`; depth `h = S / 32`). Rain that lands on a cell not under a body goes into its own `S` (a street span
  included). Between two neighbouring cells the film runs toward the lower WATER SURFACE (ground + film), in either direction:
  `q = KU sqrt(surface slope) h^(5/3)` px^2/s (a diffusive wave: on a uniform slope with a steady flux the surface slope is the
  ground slope and the film is `h = (q / (KU sqrt(slope)))^0.6` thick, so a stream is thicker where more water comes down; a
  thick film next to a thin one levels out by itself, and water on a flat runs off toward the lower surface ahead). A step
  never moves more than would level the two surfaces. It ends in a sink: a cell under a body (the body's level is the
  surface there), within `BASIN_MIN_DEPTH` of a hollow's bed, a pond (absorbs it) or the world edge (loses it). Only the
  active cells are visited.
* **Bodies.** A body is the water in one hollow, or in several merged ones: a volume `V` and a *level surface*
  (`Profile.level`, exact: the volume is a piecewise quadratic in the level). It fills from the bed and spreads over
  whatever land its level reaches (a town's street included); when the level reaches the lower **real** rim the excess runs
  on as film from the first cell beyond the rim to the next lower ground. Two bodies that both stand at the rim between them
  become one (the larger keeps its id); a merged body whose level falls `SPLIT_HYST` below an inner rim splits again.
  Water leaves a body only by soaking away (`SOAK` x wetted width) and through the drain inlets that stand under it.
  There is no width cap anywhere in this file.
* **Drains.** Every settlement has `inlets x INLET_K x size^2` px^2/s of capacity. It is taken from the film on the cells of
  the settlement's span (an equal share per cell) and, where a body covers the street, from the body through each inlet
  that stands under it. `flood_list()` reports dry / wet / ponding / flooded / overflowing from the real water depth on the
  span. A drain's size comes from the world seed, the settlement id and its kind (crc32, never the world's random
  stream), so one seed always gets the same sizes.

Every tunable constant is in the block below and nowhere else; the viewer never reads one (the wire carries what it needs).
"""
from __future__ import annotations

import math
import zlib
from bisect import bisect_left, bisect_right

# ---------------------------------------------------------------- constants (the one place; LS6 and NS6 tune these)
GROUND_STEP = 32                 # px per rain bucket (objects.GROUND_STEP); also the film cell
HIT_AREA = 1.15                  # px^2 of water one landed rain particle adds (LS6: tuned on the measured rain, see PLAN.md Phase L)
BASIN_MIN_DEPTH = 1.0            # px: a hollow whose lower rim is less above its bed is not a hollow
SOAK = 0.015                     # px/s x wetted width: soak and evaporation of every body
MIN_VOL = 0.3                    # px^2: a body below this is removed
CURB = 0.0                       # px: there is no virtual curb any more (v2.4.1); kept at 0 for the wire (`hello.drains.curb`)
INLET_SPACING = 60.0             # px between inlets along a street
FLOOD_DEPTH = 3.0                # px: `flooded` from this depth
OVERFLOW_DEPTH = 8.0             # px: `overflowing` from this depth (or when the body that covers the street runs over its rim)
WET_DEPTH = 0.05                 # px: below this a street counts as having no water (state) and is not listed
INLET_K = 0.2667                 # px^2/s per size^2 of one inlet: cap = inlets x INLET_K x size^2
SIZE_MIN, SIZE_MAX = 1.5, 12.0   # px, the clamp of every pipe size
CLASSES = {"small": 3.0, "normal": 4.7, "large": 8.2}
SMALL_BELOW, LARGE_FROM = 3.76, 6.2      # a size's class: nearest class by ratio
KIND_RANGE = {"hamlet": (2.0, 4.0), "village": (3.0, 5.5), "town": (3.5, 6.5), "city": (4.0, 8.0)}
RANDOM_RANGE = (2.5, 10.0)
MODES = ("small", "normal", "large", "random")       # what --drains and the `drains` command take
SUBSTEP = 0.25                   # s: longest sub-step of the water step (so speed x8 cannot overshoot)
PROCESS_EVERY = 3                # ticks per water step (the rain tally of three ticks is processed together)
SPLIT_HYST = 0.5                 # px: a merged body splits when its level is this far below an inner rim
INFLOW_WINDOW = 2.0              # s: the averaging window of a street's `inflow`
WATER_EVERY = 3                  # frames: `frame.water` is sent at most this often
FLOOD_EVERY = 6                  # frames: `frame.flood` likewise
FLOW_EVERY = 3                   # frames: `frame.flow` likewise
WATER_DY, WATER_DX = 0.05, 0.5   # px: how far a body's y / x0 / x1 must move before `frame.water` is sent again
FLOW_DH, FLOW_DQ = 0.1, 0.1      # a cell's h (px) or q (fraction) must move this far before `frame.flow` is sent again
MODEL = 1
FLOW_MODEL = 1
# the film (v2.4.1)
KU = 35.0                        # u = KU sqrt(surface slope) h^(2/3): the speed of the film on open ground (px/s, px); NS6 fit (the plan started at 23)
KU_STREET = 40.0                 # the same law on the cells of a settlement's span (a smoother, paved surface: NS6 fit, see PLAN.md Phase N)
SLOPE_MIN = 0.05                 # the least surface slope the film runs on toward a lower surface (so a level pad still drains: NS6 fit)
FLOW_PASSES = 8                  # film sub-steps in every water sub-step
SURFACE_STEP_RATIO = 1.0         # a film sub-step moves at most this fraction of what would level the two surfaces
FILM_SOAK = 8.0                  # px^2/s per wet cell of open ground that is no longer supplied (a street span is paved: STREET_SOAK)
STREET_SOAK = 0.0                # px^2/s per wet cell of a settlement's span
FILM_EPS = 0.02                  # px^2: a cell holding less than this is dry (the water is booked as soaked)
MIN_H = 0.05                     # px: cells thinner than this are not sent in `frame.flow`
H_STEP = 0.05                    # px: `h` goes out in steps of this
MAX_RUN = 40                     # cells in one run of `frame.flow`
SINK_REACH = GROUND_STEP / 2.0   # px: the cell nearest a hollow's bed (within this of it) is always a sink
_EPS = 1e-9


# ---------------------------------------------------------------- drain sizes (seed, id, kind: no random stream)
def _u(seed, sid, tag):
    return (zlib.crc32(f"{seed}:{sid}:{tag}".encode()) & 0xFFFFFF) / 0xFFFFFF


def clamp_size(v):
    return round(max(SIZE_MIN, min(SIZE_MAX, float(v))), 2)


def size_for(mode, seed, sid, kind):
    """The pipe size (px) a settlement gets in `mode` (small / normal / large / random)."""
    if mode in ("small", "large"):
        return CLASSES[mode]
    if mode == "random":
        lo, hi = RANDOM_RANGE
        return clamp_size(lo + _u(seed, sid, "drain-random") * (hi - lo))
    lo, hi = KIND_RANGE.get(kind, KIND_RANGE["town"])
    return clamp_size(lo + _u(seed, sid, "drain") * (hi - lo))


def class_of(size):
    return "small" if size < SMALL_BELOW else ("large" if size >= LARGE_FROM else "normal")


def inlets_for(x0, x1):
    """max(1, round((x1 - x0) / INLET_SPACING)); halves round up (Java's Math.round), not to even."""
    return max(1, int(math.floor((x1 - x0) / INLET_SPACING + 0.5)))


def capacity(inlets, size):
    return inlets * INLET_K * size * size


# ---------------------------------------------------------------- level / volume of a stretch of ground
class Profile:
    """The ground over a stretch as a polyline (xs, es) of *elevations* e = -y (up is positive). The volume of water
    that lies in it at a level L is V(L) = integral of max(0, L - e(x)) dx, a piecewise quadratic in L, so the
    level for a given volume is exact (no iteration): the breakpoints are the sample elevations, between two of them
    the wetted width is linear in L."""
    __slots__ = ("xs", "es", "bk", "wa", "sl", "cv", "emin", "_bed")

    def __init__(self, xs, es):
        self.xs = xs
        self.es = es
        ev = {}
        for i in range(len(xs) - 1):
            dx = xs[i + 1] - xs[i]
            a, b = es[i], es[i + 1]
            lo, hi = (a, b) if a <= b else (b, a)
            if hi - lo < 1e-12:                       # a level run: wet as soon as L passes it
                ev.setdefault(lo, [0.0, 0.0])[1] += dx
            else:
                k = dx / (hi - lo)
                ev.setdefault(lo, [0.0, 0.0])[0] += k
                ev.setdefault(hi, [0.0, 0.0])[0] -= k
        bk = sorted(ev)
        wa, sl, cv = [], [], []
        w = s = v = 0.0
        for n, b in enumerate(bk):
            s += ev[b][0]
            w += ev[b][1]                              # width just above b (the width is continuous but at a run)
            wa.append(w)
            sl.append(s)
            cv.append(v)
            if n + 1 < len(bk):
                d = bk[n + 1] - b
                v += w * d + 0.5 * s * d * d
                w += s * d
        if sl:
            sl[-1] = 0.0                               # above the highest sample the width no longer grows
        self.bk, self.wa, self.sl, self.cv = bk, wa, sl, cv
        self.emin = bk[0] if bk else 0.0
        self._bed = None

    def vol(self, level):
        k = bisect_right(self.bk, level) - 1
        if k < 0:
            return 0.0
        d = level - self.bk[k]
        return self.cv[k] + self.wa[k] * d + 0.5 * self.sl[k] * d * d

    def width(self, level):
        k = bisect_right(self.bk, level) - 1
        if k < 0:
            return 0.0
        return self.wa[k] + self.sl[k] * (level - self.bk[k])

    def level(self, volume):
        if volume <= 0.0:
            return self.emin
        k = bisect_right(self.cv, volume) - 1
        c = volume - self.cv[k]
        w, s = self.wa[k], self.sl[k]
        if c <= 0.0:
            return self.bk[k]
        den = w + math.sqrt(w * w + 2.0 * s * c)
        return self.bk[k] + (2.0 * c / den if den > 0.0 else 0.0)

    def shore(self, level):
        """(xa, xb): the leftmost and the rightmost place the level meets the ground, or None when nothing is wet."""
        xs, es = self.xs, self.es
        n = len(xs)
        i = 0
        while i < n and es[i] >= level:
            i += 1
        if i >= n:
            return None
        if i == 0:
            xa = xs[0]
        else:
            t = (es[i - 1] - level) / (es[i - 1] - es[i])
            xa = xs[i - 1] + t * (xs[i] - xs[i - 1])
        j = n - 1
        while j > i and es[j] >= level:
            j -= 1
        if j == n - 1:
            xb = xs[j]
        else:
            t = (es[j + 1] - level) / (es[j + 1] - es[j])
            xb = xs[j + 1] - t * (xs[j + 1] - xs[j])
        return xa, xb

    def bed(self):
        """(x, e) of the lowest ground (the sample nearest the middle when several are as low)."""
        if self._bed is None:                              # (static: a profile never changes)
            mid = 0.5 * (self.xs[0] + self.xs[-1])
            best = min(range(len(self.xs)), key=lambda i: (self.es[i], abs(self.xs[i] - mid)))
            self._bed = (self.xs[best], self.es[best])
        return self._bed


# ---------------------------------------------------------------- the basin map
class El:
    __slots__ = ("kind", "lo", "hi", "bed_i", "bed_e", "sid", "x0", "x1", "depth", "rep")

    def __init__(self, kind, lo, hi, bed_i=-1, bed_e=0.0, sid=0, x0=0.0, x1=0.0):
        self.kind, self.lo, self.hi = kind, lo, hi
        self.bed_i, self.bed_e = bed_i, bed_e
        self.sid, self.x0, self.x1 = sid, x0, x1
        self.depth = 0.0
        self.rep = bed_i


class TerrainMap:
    """Static: elements (left to right, an `edge` at each end), the divide sample between each neighbouring pair, and the
    destination element of every rain bucket. A street is an element only when the terrain makes it a hollow of at least
    `min_depth` (its lower divide that much above its lowest ground); otherwise it dissolves, like any shallow hollow, into
    the neighbour across its lower divide and only `spans[sid]` remembers where it is."""

    def __init__(self, y, step, streets, ponds, min_depth=BASIN_MIN_DEPTH, width=None):
        self.step = step
        self.n = n = len(y)
        self.e = e = [-v for v in y]
        taken = [False] * n
        zones = []
        for sid, x0, x1 in streets:
            zones.append(("street", sid, x0, x1))
        for x0, x1 in ponds:
            zones.append(("pond", 0, x0, x1))
        zones.sort(key=lambda z: (z[0] != "street", z[2], z[3]))     # streets first: a pond that overlaps one is the one dropped
        zel = []
        for kind, sid, x0, x1 in zones:
            i0 = max(0, int(math.ceil(x0 / step - 1e-9)))
            i1 = min(n - 1, int(math.floor(x1 / step + 1e-9)))
            if i1 < i0:
                i0 = i1 = min(n - 1, max(0, int(round(0.5 * (x0 + x1) / step))))
            if any(taken[i0:i1 + 1]):
                continue                                  # a pond that overlaps a street (or another pond) is dropped
            for i in range(i0, i1 + 1):
                taken[i] = True
            zel.append(El(kind, i0, i1, bed_i=i0, bed_e=min(e[i0:i1 + 1]), sid=sid, x0=x0, x1=x1))
        zel.sort(key=lambda el: el.lo)
        cands = []
        i = 0
        while i < n:
            if taken[i]:
                i += 1
                continue
            j = i
            while j + 1 < n and not taken[j + 1] and e[j + 1] == e[i]:
                j += 1
            if i > 0 and j < n - 1 and e[i - 1] > e[i] and e[j + 1] > e[i]:
                cands.append(El("basin", i, j, bed_i=(i + j) // 2, bed_e=e[i]))
            i = j + 1
        elems = sorted(cands + zel, key=lambda el: el.lo)
        elems = [El("edge", 0, 0, bed_i=-1, bed_e=-1e18)] + elems + [El("edge", n - 1, n - 1, bed_i=-2, bed_e=-1e18)]
        elems[0].rep, elems[-1].rep = -1, -2
        dv = []
        for a, b in zip(elems, elems[1:]):
            ia = 0 if a.kind == "edge" and a.rep == -1 else a.hi
            ib = n - 1 if b.kind == "edge" and b.rep == -2 else b.lo
            if ib < ia:
                ia = ib = max(ib, 0)
            best = ia
            for k in range(ia + 1, ib + 1):
                if e[k] > e[best]:
                    best = k
            dv.append(best)
        # prune the hollows that are too shallow (water crosses them as if the ground were level)
        def refresh(k):
            el = elems[k]
            if el.kind in ("basin", "street"):
                el.depth = min(e[dv[k - 1]], e[dv[k]]) - el.bed_e
        for k in range(len(elems)):
            refresh(k)
        while True:
            best = None
            for k, el in enumerate(elems):
                if el.kind in ("basin", "street") and el.depth < min_depth - _EPS                         and (best is None or el.depth < elems[best].depth):
                    best = k
            if best is None:
                break
            k = best
            el = elems[k]
            dl, dr = e[dv[k - 1]], e[dv[k]]
            if dl == dr and el.kind == "street":              # a level pad runs toward the neighbour with the lower bed, then left
                go_left = elems[k - 1].bed_e <= elems[k + 1].bed_e
            else:
                go_left = dl <= dr
            if go_left:
                nb = k - 1
                del dv[k - 1]
            else:
                nb = k + 1
                del dv[k]
            tgt = elems[nb]
            if tgt.kind == "basin" and el.bed_e < tgt.bed_e:
                tgt.bed_e, tgt.bed_i, tgt.rep = el.bed_e, el.bed_i, el.bed_i
            if tgt.kind == "basin":
                tgt.lo, tgt.hi = min(tgt.lo, el.lo), max(tgt.hi, el.hi)
            del elems[k]
            nk = nb if nb < k else nb - 1
            for q in (nk - 1, nk, nk + 1):
                if 0 <= q < len(elems):
                    refresh(q)
        self.elems = elems
        self.dv = dv
        self.kind = [el.kind for el in elems]
        self.rep_index = {el.rep: j for j, el in enumerate(elems)}
        self.street_el = {el.sid: j for j, el in enumerate(elems) if el.kind == "street"}     # the streets that are hollows
        self.spans = {sid: (x0, x1) for sid, x0, x1 in streets}                              # every street, hollow or not
        self._profiles = {}
        self.width = width if width is not None else (n - 1) * step
        self.buckets = self._catchment()

    # -- helpers
    def rims(self, a, b):
        """Sample indices of the left and right rim of the elements a..b (contiguous basins)."""
        return self.dv[a - 1], self.dv[b]

    def pool_profile(self, a, b):
        rl, rr = self.rims(a, b)
        p = self._profiles.get((rl, rr))
        if p is None:
            step = self.step
            p = self._profiles[(rl, rr)] = Profile([i * step for i in range(rl, rr + 1)], self.e[rl:rr + 1])
        return p

    def e_at(self, x):
        f = x / self.step
        if f <= 0.0:
            return self.e[0]
        i = int(f)
        if i >= self.n - 1:
            return self.e[-1]
        return self.e[i] + (self.e[i + 1] - self.e[i]) * (f - i)

    def _catchment(self):
        step = self.step
        xs_div = [d * step for d in self.dv]
        nb = int(math.ceil(self.width / GROUND_STEP)) + 1
        out = []
        streets = [(el.x0, el.x1, j) for j, el in enumerate(self.elems) if el.kind == "street"]
        for kb in range(nb):
            x = kb * GROUND_STEP
            hit = next((j for x0, x1, j in streets if x0 <= x <= x1), None)       # a bucket centred in a hollow street's span is its own
            out.append(hit if hit is not None else bisect_left(xs_div, x))
        return out


def find_basins(terrain, street_spans=(), pond_spans=()):
    """The basin map of a terrain (anything with .y and .step): streets are (id, x0, x1), ponds (x0, x1)."""
    return TerrainMap(terrain.y, terrain.step, list(street_spans), list(pond_spans))


def catchment(tmap):
    """bucket k (centre x = 32 k) -> index of the element its rain goes to."""
    return tmap.buckets


def volume_at(terrain, span, level_y):
    """Water volume (px^2) in the samples span = (i0, i1) of a terrain when its surface is at world y `level_y`."""
    i0, i1 = span
    return Profile([i * terrain.step for i in range(i0, i1 + 1)], [-v for v in terrain.y[i0:i1 + 1]]).vol(-level_y)


def level_for_volume(terrain, span, volume):
    """World y of the surface when `volume` lies in the samples span = (i0, i1)."""
    i0, i1 = span
    return -Profile([i * terrain.step for i in range(i0, i1 + 1)], [-v for v in terrain.y[i0:i1 + 1]]).level(volume)


def span_at(terrain, span, level_y):
    """(x0, x1) where the surface at world y `level_y` meets the ground within the samples span, or None."""
    i0, i1 = span
    return Profile([i * terrain.step for i in range(i0, i1 + 1)], [-v for v in terrain.y[i0:i1 + 1]]).shore(-level_y)


# ---------------------------------------------------------------- bodies
class Body:
    __slots__ = ("id", "kind", "a", "b", "V", "prof", "e_s", "vcap", "side", "sid", "lev", "x0", "x1", "xb", "depth",
                 "spilling", "spilled", "spill_k", "fed")

    def __init__(self, bid, kind, a, b, V, prof, e_s, side, sid=0):
        self.id, self.kind, self.a, self.b, self.V = bid, kind, a, b, V
        self.sid = sid
        self.set_profile(prof, e_s, side)
        self.lev = prof.emin
        self.x0 = self.x1 = self.xb = 0.0
        self.depth = 0.0
        self.spilling = False
        self.spilled = 0.0
        self.spill_k = -1
        self.fed = True                       # got water in the current water step (a body that is fed is not removed as dry)

    def set_profile(self, prof, e_s, side):
        self.prof, self.e_s, self.side = prof, e_s, side
        self.vcap = prof.vol(e_s)


# ---------------------------------------------------------------- the film: the static shape of the ground it runs over
class Film:
    """Cells of 32 px along the world (cell `k` is centred at x = 32 k, the rain bucket `k`): per cell the element it lies
    in, the way it runs (`dirn`: -1 left, +1 right, 0 = a sink that is always a sink), its slope, the speed constant and the
    settlement whose span it is part of. Derived from the `TerrainMap` and the settlement spans; nothing of it is saved.
    The water itself (`S`, px^2 per cell) and the set of cells that hold some (`act`) are the only live state."""

    def __init__(self, tmap, spans):
        self.nb = nb = len(tmap.buckets)
        step = tmap.step
        e = tmap.e
        self.el_of = el_of = tmap.buckets
        self.ecell = ecell = [tmap.e_at(k * GROUND_STEP) for k in range(nb)]
        self.dirn = dirn = [0] * nb
        self.sink = sink = [False] * nb                    # static sinks (the bed of a hollow, a pond, the world edge)
        elems = tmap.elems
        per_el = {}
        for k in range(nb):
            per_el.setdefault(el_of[k], []).append(k)
            x = k * GROUND_STEP
            el = elems[el_of[k]]
            kind = el.kind
            if kind == "edge":
                dirn[k] = -1 if el.rep == -1 else 1
                sink[k] = (k == 0 and el.rep == -1) or (k == nb - 1 and el.rep == -2)
                continue
            if kind == "basin":
                xb = el.bed_i * step
                sink[k] = abs(x - xb) <= SINK_REACH or ecell[k] <= el.bed_e + BASIN_MIN_DEPTH
            else:                                          # a street that is a hollow, or a pond: its zone is the sink
                xb = 0.5 * (el.x0 + el.x1)
                sink[k] = el.x0 <= x <= el.x1
            if not sink[k]:
                dirn[k] = 1 if x < xb else -1
        for j, ks in per_el.items():                       # every hollow has a sink cell, the one nearest its bed
            el = elems[j]
            if el.kind in ("basin", "street", "pond") and not any(sink[k] for k in ks):
                xb = el.bed_i * step if el.kind == "basin" else 0.5 * (el.x0 + el.x1)
                kk = min(ks, key=lambda q: (abs(q * GROUND_STEP - xb), q))
                sink[kk] = True
                dirn[kk] = 0
        for k in range(nb):
            if sink[k]:
                dirn[k] = 0
        self.sid_of = sid_of = [0] * nb
        self.span_cells = {}
        for sid, (x0, x1) in sorted(spans.items()):
            ks = [k for k in range(nb) if x0 <= k * GROUND_STEP <= x1]
            if not ks:
                ks = [min(nb - 1, max(0, int(round(0.5 * (x0 + x1) / GROUND_STEP))))]
            self.span_cells[sid] = ks
            for k in ks:
                if not sid_of[k]:
                    sid_of[k] = sid
        self.ku = [KU_STREET if sid_of[k] else KU for k in range(nb)]
        self.soak = [STREET_SOAK if sid_of[k] else FILM_SOAK for k in range(nb)]
        self.term = term = [-1] * nb                        # where a cell's water ends up: the sink cell it runs to
        for k in range(nb):
            c, guard = k, 0
            while 0 <= c < nb and dirn[c] != 0 and guard < nb + 2:
                c += dirn[c]
                guard += 1
            term[k] = c if 0 <= c < nb else (0 if c < 0 else nb - 1)
        self.S = [0.0] * nb
        self.act = set()
        self.field = None                                   # the WaterField that owns this film (it knows the bodies' levels)

    def q_of(self, k):
        """The flux (px^2/s) the film in cell `k` passes on to the lower surface beside it (0 when it has none)."""
        return self.field.flux_of(k)[1]

    def depth_at(self, x):
        """The film depth (px) at world x, interpolated between the cell centres."""
        f = x / GROUND_STEP
        if f <= 0.0:
            return self.S[0] / GROUND_STEP
        k = int(f)
        if k >= self.nb - 1:
            return self.S[self.nb - 1] / GROUND_STEP
        t = f - k
        return (self.S[k] * (1.0 - t) + self.S[k + 1] * t) / GROUND_STEP

    def total(self):
        return sum(self.S[k] for k in self.act)

    def runs(self):
        """The wet cells as runs, chains (k0, dir, [cells]) of at most MAX_RUN along the way the film runs, upstream cell first,
        sorted by (k0, dir). The way is the dynamic one (toward the lower water surface beside the cell)."""
        S = self.S
        way = {}
        for k in self.act:
            if S[k] / GROUND_STEP >= MIN_H - _EPS:
                way[k] = self.field.flux_of(k)[0]
        used = set()
        out = []
        for d, order in ((1, sorted(way)), (-1, sorted(way, reverse=True))):
            for k in order:
                if way[k] != d or k in used:
                    continue
                chain = [k]
                used.add(k)
                c = k
                while len(chain) < MAX_RUN:
                    n = c + d
                    if way.get(n) == d and n not in used:
                        chain.append(n)
                        used.add(n)
                        c = n
                    else:
                        break
                out.append((chain[0], d, chain))
        out.sort(key=lambda r: (r[0], r[1]))
        return out


class WaterField:
    """The water of a sandbox world. `update(dt, rain_hits)` once per tick; everything else is read-only wire output,
    save / load, or the `drains` command."""

    def __init__(self, terrain, settlements, objs, seed, mode="normal"):
        self.seed = int(seed)
        self.base_mode = mode if mode in MODES else "normal"
        self.mode = self.base_mode
        self.streets = {s["id"]: s for s in settlements}            # id -> the settlement (read only, never copied back)
        self.sorder = [s["id"] for s in settlements]
        self.y = terrain.y
        self.step = terrain.step
        self.w = terrain.w
        pond_spans = [(o["x"] - o["w"] / 2.0, o["x"] + o["w"] / 2.0) for o in objs if o["kind"] == "pond"]
        self.tmap = TerrainMap(terrain.y, terrain.step, [(s["id"], s["x0"], s["x1"]) for s in settlements], pond_spans,
                               width=terrain.w)
        self.e = self.tmap.e
        self.film = Film(self.tmap, self.tmap.spans)
        self.film.field = self
        self.sizes = {s["id"]: size_for(self.mode, self.seed, s["id"], s["kind"]) for s in settlements}
        self.inlets = {s["id"]: inlets_for(s["x0"], s["x1"]) for s in settlements}
        self.next_id = (max(self.sorder) if self.sorder else 0) + 1
        self.acc = {}                                            # cell -> landed particles not yet processed
        self.tick = 0
        self.dt_acc = 0.0
        self.elem_body = [None] * len(self.tmap.elems)
        self.pools = {}                                          # id -> Body (natural bodies, and every merged one)
        self.sbody = {}                                          # settlement id -> street Body (a street that is a hollow)
        for sid, j in sorted(self.tmap.street_el.items()):
            self._new_street(sid, j)
        self.win = {sid: [] for sid in self.sorder}              # the inflow window of each settlement: [dt, px^2 entering]
        self.entering = {}                                       # px^2 that entered each settlement's span in this water step
        self._fed = set()                                        # film cells that got water since the last film step
        self.ledger = {"rain": 0.0, "soaked": 0.0, "drained": 0.0, "lost": 0.0, "absorbed": 0.0}
        self.sent_water = None                                   # {id: (kind, x0, x1, y, spill)} last sent, None = never
        self.sent_water_frame = -10 ** 9
        self.sent_flood = None
        self.sent_flood_frame = -10 ** 9
        self.sent_flow = None                                    # the runs last sent (see _flow_key), None = never
        self.sent_flow_frame = -10 ** 9
        self.drains_pending = False
        self.resend = False                                      # set by from_state: the first frame repeats the lists
        self._spans = ([], [])
        self._inlet_x = {}
        for sid in self.sorder:
            s = self.streets[sid]
            n = self.inlets[sid]
            self._inlet_x[sid] = [s["x0"] + (i + 0.5) * (s["x1"] - s["x0"]) / n for i in range(n)]

    # ---- construction helpers
    def _rim_of(self, a, b):
        tm = self.tmap
        rl, rr = tm.rims(a, b)
        return rl, rr, min(self.e[rl], self.e[rr]), (-1 if self.e[rl] <= self.e[rr] else 1)

    def _new_street(self, sid, j):
        prof = self.tmap.pool_profile(j, j)
        _rl, _rr, e_s, side = self._rim_of(j, j)
        b = Body(sid, "street", j, j, 0.0, prof, e_s, side, sid)
        self.sbody[sid] = b
        self.elem_body[j] = b
        return b

    def _new_pool(self, a, b, V, bid=None):
        tm = self.tmap
        prof = tm.pool_profile(a, b)
        _rl, _rr, e_s, side = self._rim_of(a, b)
        if bid is None:
            bid = self.next_id
            self.next_id += 1
        body = Body(bid, "pool", a, b, V, prof, e_s, side)
        self.pools[body.id] = body
        for j in range(a, b + 1):
            self.elem_body[j] = body
        return body

    def _body_for(self, j):
        """The body of element j, made when there is none yet (a natural hollow's pool, a street hollow's street body)."""
        b = self.elem_body[j]
        if b is None:
            el = self.tmap.elems[j]
            if el.kind == "street" and el.sid not in self.pools:
                b = self._new_street(el.sid, j)
            else:
                b = self._new_pool(j, j, 0.0)
        return b

    def cap_of(self, sid):
        return capacity(self.inlets[sid], self.sizes[sid])

    # ---- the per-tick entry point
    def update(self, dt, rain_hits):
        acc = self.acc
        nb = self.film.nb
        for k, n in rain_hits.items():
            if k >= nb:
                k = nb - 1
            elif k < 0:
                k = 0
            acc[k] = acc.get(k, 0.0) + n
        self.tick += 1
        self.dt_acc += dt
        if self.tick % PROCESS_EVERY == 0:
            dta = self.dt_acc
            self.dt_acc = 0.0
            self._process(dta)

    # ---- the water step
    def _busy(self):
        return (bool(self.acc) or bool(self.film.act) or bool(self.pools) or any(b.V > 0.0 for b in self.sbody.values())
                or any(self.win.values()))

    def _process(self, dt):
        if not self._busy():
            return
        rain = sorted((k, v * HIT_AREA) for k, v in self.acc.items() if v)
        self.acc = {}
        self.entering = {}
        n = max(1, int(math.ceil(dt / SUBSTEP - 1e-9)))
        h = dt / n
        for b in self._bodies():
            b.spilled = 0.0
            b.fed = False
        for _ in range(n):
            led = self.ledger
            for k, v in rain:
                v = v / n
                led["rain"] += v
                sid = self.film.sid_of[k]
                if sid:
                    self.entering[sid] = self.entering.get(sid, 0.0) + v
                self._put(k, v)
            self._film_step(h)
            self._settle()
            self._soak(h)
        for sid in self.sorder:                                 # the inflow window of every settlement
            w = self.win[sid]
            w.append([dt, self.entering.get(sid, 0.0)])
            tot = sum(p[0] for p in w)
            while len(w) > 1 and tot - w[0][0] >= INFLOW_WINDOW - _EPS:
                tot -= w.pop(0)[0]
            if not any(p[1] for p in w):
                self.win[sid] = []
        self._finish()

    # -- where a bit of water goes
    def _put(self, k, v):
        """Water `v` (px^2) arrives on cell k: into the body or pond or world edge there, else onto the film."""
        if not self._sink_take(k, v):
            f = self.film
            f.S[k] += v
            f.act.add(k)
            self._fed.add(k)

    def _sink_take(self, k, v):
        f = self.film
        j = f.el_of[k]
        kind = self.tmap.kind[j]
        if kind == "edge":
            if f.sink[k]:
                self.ledger["lost"] += v
                return True
            return False
        if kind == "pond":
            if f.sink[k]:
                self.ledger["absorbed"] += v
                return True
            return False
        b = self.elem_body[j]
        if f.sink[k] or (b is not None and b.V > 0.0 and f.ecell[k] < b.lev - _EPS):
            if b is None:
                b = self._body_for(j)
            b.V += v
            b.fed = True
            return True
        return False

    def _hand_over(self, j, v):
        """Water `v` goes straight into element j (the first cell beyond a rim lies in another element: a narrow one)."""
        kind = self.tmap.kind[j]
        if kind == "edge":
            self.ledger["lost"] += v
        elif kind == "pond":
            self.ledger["absorbed"] += v
        else:
            b = self._body_for(j)
            b.V += v
            b.fed = True

    # -- the film
    def _surf(self, c):
        """(water surface elevation, is a sink) of cell c: ground + film on an open cell; the body's level (or the bed) on a sink."""
        f = self.film
        if f.sink[c] or self._under(c):
            b = self.elem_body[f.el_of[c]]
            e = f.ecell[c]
            if b is not None and b.V > 0.0 and b.lev > e:
                return b.lev, True
            return e, True
        return f.ecell[c] + f.S[c] / GROUND_STEP, False

    def _face(self, a, b, sa, sb):
        """The flux (px^2/s) and the face depth between neighbours a and b (surfaces sa, sb): from the higher surface to the lower."""
        f = self.film
        d = sa - sb if sa > sb else sb - sa
        hf = (sa if sa > sb else sb) - max(f.ecell[a], f.ecell[b])
        if hf <= 0.0 or d <= 0.0:
            return 0.0, 0.0, 0.0
        donor = a if sa > sb else b
        slope = d / GROUND_STEP
        return f.ku[donor] * math.sqrt(slope if slope > SLOPE_MIN else SLOPE_MIN) * hf ** (5.0 / 3.0), d, hf

    def flux_of(self, k):
        """(way, q) of the film in cell k: the way (-1 / +1) toward the lowest surface beside it and the flux that way, px^2/s.
        With no lower surface beside it the way is the ground's (a sink's, 1 for a level cell) and q is 0."""
        f = self.film
        if f.S[k] <= 0.0:
            return (f.dirn[k] or 1), 0.0
        s0 = f.ecell[k] + f.S[k] / GROUND_STEP
        best = (0.0, 0, 0.0)
        for n in (k - 1, k + 1):
            if 0 <= n < f.nb:
                sn = self._surf(n)[0]
                q, d, _hf = self._face(k, n, s0, sn)
                if s0 > sn and d > best[0]:
                    best = (d, n - k, q)
            elif s0 > f.ecell[k]:                               # off the world: the edge takes it
                q, d, _hf = self._face(k, k, s0, f.ecell[k])
                if d > best[0]:
                    best = (d, n - k, q)
        if best[1] == 0:
            return (f.dirn[k] or 1), 0.0
        return best[1], best[2]

    def _film_step(self, h):
        f = self.film
        if not f.act:
            self._fed = set()
            return
        fed, self._fed = self._fed, set()
        S = f.S
        led = self.ledger
        sid_of = f.sid_of
        caps = {}
        for sid in self.sorder:
            if self.sizes[sid] > 0.0:
                caps[sid] = self.cap_of(sid) / len(f.span_cells[sid])
        for k in sorted(f.act):
            s = S[k]
            if f.dirn[k] == 0 or self._under(k):                # a sink cell holds nothing: its water goes to the body there
                S[k] = 0.0
                if not self._sink_take(k, s):
                    self._hand_over(f.el_of[k], s)
                continue
            sid = sid_of[k]
            if sid:                                              # the drains take their share from the film on the span
                d = caps[sid] * h
                if d > s:
                    d = s
                s -= d
                led["drained"] += d
            if f.soak[k] > 0.0 and k not in fed:             # water left behind (no supply) soaks into the ground
                d = f.soak[k] * h
                if d > s:
                    d = s
                s -= d
                led["soaked"] += d
            S[k] = s
        for _ in range(FLOW_PASSES):                            # (a near-level sheet needs small steps: the flux law has 1 / sqrt(slope) conductance)
            self._flow(h / FLOW_PASSES, 0)
        for k in sorted(f.act):
            if S[k] < FILM_EPS:
                led["soaked"] += S[k]
                S[k] = 0.0
                f.act.discard(k)

    def _flow(self, h, depth):
        """One sub-step of the diffusive wave: fluxes between neighbours from the old state (two passes: all the moves are worked
        out first, then made), each at most SURFACE_STEP_RATIO of what would level the two surfaces (so the explicit step is stable
        however level the surfaces are: a flux that grows as 1 / sqrt(slope) is capped, and the film relaxes toward level)."""
        f = self.film
        S, nb, ecell, ku, sid_of = f.S, f.nb, f.ecell, f.ku, f.sid_of
        sink = f.sink
        ratio = SURFACE_STEP_RATIO
        smin = SLOPE_MIN
        cells = set()
        for k in f.act:
            cells.add(k - 1)
            cells.add(k)
        surf = {}                                              # cell -> (surface, is a sink); S does not change until the moves are made

        def get(c):
            v = surf.get(c)
            if v is None:
                v = surf[c] = self._surf(c)
            return v
        moves = []                                             # (donor, receiver or -1/nb off the world, volume, receiver is a sink)
        inv = 1.0 / GROUND_STEP
        for a in sorted(cells):
            if a < -1 or a >= nb:
                continue
            if a == -1:                                        # off the left end
                if 0 not in f.act:
                    continue
                y = -1
                x = 0
            elif a == nb - 1:
                if a not in f.act:
                    continue
                x, y = a, nb
            else:
                x, y = a, a + 1
            if y == -1 or y == nb:                              # the world's end: the film runs off when it stands above its ground
                if S[x] <= 0.0 or sink[x]:
                    continue
                hf = S[x] * inv
                q = ku[x] * math.sqrt(max(hf * inv, smin)) * hf ** (5.0 / 3.0)
                moves.append((x, y, min(q * h, S[x]), True))
                continue
            sa, sinka = get(x)
            sb, sinkb = get(y)
            if sa == sb:
                continue
            if sa > sb:
                donor, recv, dsink, rsink, hi, d = x, y, sinka, sinkb, sa, sa - sb
            else:
                donor, recv, dsink, rsink, hi, d = y, x, sinkb, sinka, sb, sb - sa
            if dsink:
                continue                                        # a body gives nothing to the ground
            ex, ey = ecell[x], ecell[y]
            hf = hi - (ex if ex > ey else ey)
            if hf <= 0.0:
                continue
            slope = d * inv
            v = ku[donor] * math.sqrt(slope if slope > smin else smin) * hf ** (5.0 / 3.0) * h
            cap = ratio * min((32.0 if rsink else 16.0) * d, GROUND_STEP * hf)
            moves.append((donor, recv, v if v < cap else cap, rsink))
        out = {}
        for donor, _r, v, _s in moves:
            out[donor] = out.get(donor, 0.0) + v
        scale = {c: S[c] / t for c, t in out.items() if t > S[c] > 0.0}
        led = self.ledger
        fed = self._fed
        act = f.act
        for donor, recv, v, rsink in moves:
            sc = scale.get(donor)
            if sc is not None:
                v *= sc
            if v <= 0.0:
                continue
            S[donor] = max(0.0, S[donor] - v)
            if recv < 0 or recv >= nb:
                led["lost"] += v
                continue
            sid_to = sid_of[recv]
            if sid_to and sid_to != sid_of[donor]:
                self.entering[sid_to] = self.entering.get(sid_to, 0.0) + v
            if rsink:
                self._put(recv, v)
            else:                                               # (a receiver that was not a sink when the surfaces were read stays one)
                S[recv] += v
                act.add(recv)
                fed.add(recv)

    def _absorb(self):
        """Film on a cell the water now stands over goes into the body (water never lies on water). True when any moved."""
        f = self.film
        moved = False
        for k in sorted(f.act):
            if self._under(k):
                b = self.elem_body[f.el_of[k]]
                b.V += f.S[k]
                b.fed = True
                f.S[k] = 0.0
                f.act.discard(k)
                moved = True
        return moved

    def _under(self, k):
        b = self.elem_body[self.film.el_of[k]]
        return b is not None and b.V > 0.0 and self.film.ecell[k] < b.lev - _EPS

    # -- bodies
    def _bodies(self):
        out = list(self.sbody.values()) + list(self.pools.values())
        out.sort(key=lambda b: (b.a, b.id))
        return out

    def _across(self, b):
        """The element across b's lower real rim."""
        return b.a - 1 if b.side < 0 else b.b + 1

    def _rim_cell(self, b):
        """The first film cell beyond b's lower rim (its overflow enters there), or None when that cell is not in the
        element across the rim (a narrow neighbour: the water is handed to it directly)."""
        f = self.film
        rl, rr = self.tmap.rims(b.a, b.b)
        xr = (rl if b.side < 0 else rr) * self.step
        k = int(math.floor(xr / GROUND_STEP + 1e-9))
        if b.side > 0:
            k += 1
        k = max(0, min(f.nb - 1, k))
        return k if f.el_of[k] == self._across(b) else None

    def _settle(self):
        """Every body above its real rim passes the excess on: as film from the first cell beyond the rim, or, when the
        body across the rim stands at that same rim too, the two become one."""
        led = self.ledger
        kinds = self.tmap.kind
        work = [b for b in self._bodies() if b.V > b.vcap + _EPS]
        guard = 0
        limit = 64 + 8 * (len(self.pools) + len(self.sbody))
        while work and guard < limit:
            guard += 1
            b = work.pop(0)
            if b.V <= b.vcap + _EPS or not self._alive(b):
                continue
            ex = b.V - b.vcap
            j = self._across(b)
            t = self.elem_body[j] if kinds[j] in ("basin", "street") else None
            if t is not None and t is not b and t.V >= t.vcap - 1e-7 and self.elem_body[self._across(t)] is b:
                work.append(self._merge(b, t))                    # both stand at the rim between them
                continue
            b.V = b.vcap
            b.spilled += ex
            k = self._rim_cell(b)
            b.spill_k = k if k is not None else -1
            if k is None:
                self._hand_over(j, ex)
            else:
                sid = self.film.sid_of[k]
                if sid:
                    self.entering[sid] = self.entering.get(sid, 0.0) + ex
                self._put(k, ex)
            tt = self.elem_body[j] if kinds[j] in ("basin", "street") else None
            if tt is not None and tt is not b and tt.V > tt.vcap + _EPS:
                work.append(tt)
        for b in work:                                                 # only reachable by the guard: nothing is lost silently
            if self._alive(b) and b.V > b.vcap + _EPS:
                led["lost"] += b.V - b.vcap
                b.V = b.vcap

    def _alive(self, b):
        return (self.pools.get(b.id) is b) or (self.sbody.get(b.sid) is b if b.kind == "street" else False)

    def _merge(self, b, t):
        """Two bodies that both stand at the rim between them become one pool; the larger keeps its id (a tie: the lower)."""
        vb, vt = min(b.V, b.vcap), min(t.V, t.vcap)
        if abs(vb - vt) <= 1e-9 * max(vb, vt, 1.0):               # equal (to rounding): the lower id
            keep, gone = (b, t) if b.id < t.id else (t, b)
        else:
            keep, gone = (b, t) if vb > vt else (t, b)
        a0, b1 = min(b.a, t.a), max(b.b, t.b)
        V = b.V + t.V
        for body in (gone, keep):
            if body.kind == "street":
                self.sbody.pop(body.sid, None)
            else:
                self.pools.pop(body.id, None)
        keep.kind, keep.sid = "pool", 0
        prof = self.tmap.pool_profile(a0, b1)
        _rl, _rr, e_s, side = self._rim_of(a0, b1)
        keep.a, keep.b, keep.V = a0, b1, V
        keep.set_profile(prof, e_s, side)
        keep.spilled = max(keep.spilled, gone.spilled)
        self.pools[keep.id] = keep
        for j in range(a0, b1 + 1):
            self.elem_body[j] = keep
        return keep

    def _soak(self, h):
        led = self.ledger
        for b in self._bodies():
            if b.V <= 0.0:
                continue
            prof = b.prof
            lev = prof.level(b.V)
            loss = SOAK * prof.width(lev) * h
            if loss > b.V:
                loss = b.V
            b.V -= loss
            led["soaked"] += loss
            if b.V > 0.0 and self.sorder:                              # the inlets that stand under the water drain it
                rate = self._body_drain_rate(b, lev)
                if rate > 0.0:
                    d = min(rate * h, b.V)
                    b.V -= d
                    led["drained"] += d

    def _body_drain_rate(self, b, lev):
        sh = b.prof.shore(lev)
        if sh is None:
            return 0.0
        xa, xb = sh
        rate = 0.0
        tm = self.tmap
        for sid in self.sorder:
            s = self.streets[sid]
            if s["x1"] < xa or s["x0"] > xb:
                continue
            per = INLET_K * self.sizes[sid] ** 2
            n = sum(1 for x in self._inlet_x[sid] if xa <= x <= xb and tm.e_at(x) < lev)
            rate += n * per
        return rate

    def _finish(self):
        """Remove the dry, split the merged whose level fell, and refresh each body's level surface and shore."""
        led = self.ledger
        for b in list(self.pools.values()):
            if b.V < MIN_VOL and not b.fed:
                led["soaked"] += b.V
                self._drop(b)
        for b in list(self.pools.values()):
            if b.b > b.a:
                self._maybe_split(b)
        for b in self._bodies():
            if b.kind == "street" and 0.0 < b.V < MIN_VOL:
                led["soaked"] += b.V
                b.V = 0.0
            self._surface(b)
        for _ in range(3):                                       # a rising surface takes the film it has just covered
            if not self._absorb():
                break
            self._settle()
            for b in self._bodies():
                self._surface(b)
        spans = sorted(((b.x0, b.x1, b) for b in self._bodies() if b.V > 0.0 and b.x1 > b.x0), key=lambda t: t[0])
        self._spans = ([t[0] for t in spans], spans)

    def _drop(self, b):
        if b.kind == "street":
            self.sbody.pop(b.sid, None)
        else:
            self.pools.pop(b.id, None)
        for j in range(b.a, b.b + 1):
            if self.elem_body[j] is b:
                self.elem_body[j] = None

    def _maybe_split(self, b):
        tm = self.tmap
        lev = b.prof.level(b.V)
        cuts = [j for j in range(b.a, b.b) if self.e[tm.dv[j]] > lev + SPLIT_HYST]       # inner rims that stand dry
        if not cuts:
            return
        groups, start = [], b.a
        for j in cuts:
            groups.append((start, j))
            start = j + 1
        groups.append((start, b.b))
        parts = []
        for a, c in groups:
            prof = tm.pool_profile(a, c)
            parts.append((a, c, prof.vol(lev)))
        big = max(range(len(parts)), key=lambda i: (parts[i][2], -i))
        self._drop(b)
        for i, (a, c, v) in enumerate(parts):
            if v < MIN_VOL:
                self.ledger["soaked"] += v
                continue
            if a == c and tm.kind[a] == "street" and tm.elems[a].sid not in self.sbody and tm.elems[a].sid not in self.pools:
                nb = self._new_street(tm.elems[a].sid, a)             # a street hollow is its settlement's again
                nb.V = v
                continue
            # the largest part is the same lake and keeps its id (unless that id is a settlement's); the others are new bodies
            keep = b.id if (i == big and b.id not in self.streets) else None
            self._new_pool(a, c, v, keep)

    def _surface(self, b):
        prof = b.prof
        if b.V <= 0.0:
            b.lev = prof.emin
            b.x0 = b.x1 = b.xb = 0.0
            b.depth = 0.0
            b.spilling = False
            return
        lev = prof.level(b.V)
        b.lev = lev
        sh = prof.shore(lev)
        if sh is None:
            b.x0 = b.x1 = b.xb = 0.0
            b.depth = 0.0
        else:
            b.x0, b.x1 = sh
            xb, eb = prof.bed()
            b.xb = xb
            b.depth = max(0.0, lev - eb)
        b.spilling = b.spilled > 0.0

    # ---- queries
    def depth_at(self, x):
        """Depth (px) of water at world x: the body's depth where one covers it, else the film's depth there (interpolated
        between the cell centres). 0 when thinner than WET_DEPTH."""
        xs, spans = self._spans
        d = 0.0
        if xs:
            i = bisect_right(xs, x) - 1
            if i >= 0:
                x0, x1, b = spans[i]
                if x <= x1:
                    d = b.lev - self.tmap.e_at(x)
        if d < WET_DEPTH:
            d = self.film.depth_at(x) if self.film.act else 0.0
        return d if d >= WET_DEPTH else 0.0              # a film thinner than the wet threshold is not standing water

    def _covering(self, sid):
        """The bodies with water on the span of settlement `sid`, each with the greatest depth it has there."""
        x0, x1 = self.tmap.spans[sid]
        out = []
        for b in self._bodies():
            if b.V <= 0.0 or b.x1 <= b.x0 or b.x1 < x0 or b.x0 > x1:
                continue
            lo, hi = max(b.x0, x0), min(b.x1, x1)
            tm = self.tmap
            lowest = min([tm.e_at(lo), tm.e_at(hi)] + [self.e[i] for i in range(int(math.ceil(lo / self.step)),
                                                                                int(math.floor(hi / self.step)) + 1)])
            out.append((b, max(0.0, b.lev - lowest)))
        return out

    def span_depth(self, sid):
        """The greatest water depth on the settlement's span: the thickest film cell, or a body's depth at its lowest point."""
        f = self.film
        d = max((f.S[k] for k in f.span_cells[sid]), default=0.0) / GROUND_STEP
        for _b, bd in self._covering(sid):
            d = max(d, bd)
        return d

    # ---- wire output
    def drain_info(self, sid):
        size = self.sizes[sid]
        n = self.inlets[sid]
        return {"size": size, "class": class_of(size), "inlets": n, "cap": round(capacity(n, size), 1)}

    def hello_water(self):
        return {"model": MODEL, "basin_min_depth": BASIN_MIN_DEPTH}

    def hello_flow(self):
        return {"model": FLOW_MODEL, "cell": GROUND_STEP, "min_h": MIN_H}

    def hello_drains(self):
        return {"mode": self.mode, "inlet_spacing": INLET_SPACING, "curb": CURB, "flood_depth": FLOOD_DEPTH,
                "overflow_depth": OVERFLOW_DEPTH, "size_min": SIZE_MIN, "size_max": SIZE_MAX, "classes": dict(CLASSES)}

    def decorate(self, settlements):
        """Copies of the hello `settlements` entries with `drain` added (the originals are not touched)."""
        out = []
        for s in settlements:
            d = dict(s)
            if s["id"] in self.sizes:
                d["drain"] = self.drain_info(s["id"])
            out.append(d)
        return out

    def drains_block(self):
        return {"mode": self.mode,
                "settlements": [dict(id=sid, **self.drain_info(sid)) for sid in self.sorder]}

    def _listed(self, b):
        if b.kind == "street":
            return b.V > 0.0 and round(b.depth, 2) >= WET_DEPTH
        return b.V >= MIN_VOL

    def water_list(self):
        out = []
        for b in sorted((b for b in self._bodies() if self._listed(b)), key=lambda b: b.id):
            d = {"id": b.id, "kind": b.kind, "x": round(b.xb, 1), "x0": round(b.x0, 1), "x1": round(b.x1, 1),
                 "y": round(-b.lev, 2), "depth": round(b.depth, 2), "vol": round(b.V, 1)}
            if b.kind == "street":
                d["settlement"] = b.sid
                d["spill"] = b.spilling
            elif b.spilling:
                d["spill"] = True
            out.append(d)
        return out

    def inflow(self, sid):
        w = self.win.get(sid, [])
        tot = sum(p[0] for p in w)
        return sum(p[1] for p in w) / tot if tot > 0.0 else 0.0

    def flood_entry(self, sid):
        """(state, depth, backed) of one settlement, from the real water on its span."""
        raw = self.span_depth(sid)
        depth = round(raw, 2) if raw >= WET_DEPTH else 0.0      # a film thinner than the wet threshold is not water on the street
        cov = [b for b, bd in self._covering(sid) if bd >= WET_DEPTH]          # (a body 0.04 px over the pad is not water on it)
        backed = any(b.kind == "pool" for b in cov)
        if depth >= OVERFLOW_DEPTH - 1e-9 or any(b.spilling for b in cov):
            return "overflowing", depth, backed
        if depth >= FLOOD_DEPTH:
            return "flooded", depth, backed
        if depth >= WET_DEPTH:
            return "ponding", depth, backed
        return ("wet" if self.inflow(sid) >= 0.01 else "dry"), depth, backed

    def state_of(self, sid):
        return self.flood_entry(sid)[0]

    def flood_list(self):
        out = []
        for sid in self.sorder:
            st, depth, backed = self.flood_entry(sid)
            e = {"id": sid, "state": st, "depth": depth, "inflow": round(self.inflow(sid), 1),
                 "cap": round(self.cap_of(sid), 1)}
            if backed:
                e["backed"] = True
            out.append(e)
        return out

    def dst_of(self, cell):
        """The `dst` of a run whose last cell is `cell`: the body its water ends up in, -1 off a world edge, -2 into a pond."""
        f = self.film
        t = f.term[cell]
        j = f.el_of[t]
        kind = self.tmap.kind[j]
        if kind == "edge":
            return -1
        if kind == "pond":
            return -2
        b = self.elem_body[j]
        return b.id if b is not None else 0

    def flow_list(self):
        """frame.flow: the runs of film (downhill chains of wet cells), `h` in H_STEP steps, `q` to one decimal."""
        f = self.film
        spill_src = {b.spill_k: b.id for b in self._bodies() if b.spilling and b.spill_k >= 0}
        out = []
        for k0, d, chain in f.runs():
            hs = [round(round(f.S[k] / GROUND_STEP / H_STEP) * H_STEP, 2) for k in chain]
            src = spill_src.get(k0) or f.sid_of[k0] or 0
            out.append({"k0": k0, "dir": d, "src": src, "dst": self.dst_of(chain[-1]), "h": hs,
                        "q": [round(f.q_of(k), 1) for k in chain]})
        return out

    @staticmethod
    def _water_key(wl):
        return {d["id"]: (d["kind"], d["x0"], d["x1"], d["y"], d.get("spill", False)) for d in wl}

    def _water_changed(self, wl):
        old = self.sent_water
        new = self._water_key(wl)
        if old is None or set(old) != set(new):
            return True
        for i, (k, x0, x1, y, sp) in new.items():
            ok, ox0, ox1, oy, osp = old[i]
            if k != ok or sp != osp or abs(y - oy) >= WATER_DY - 1e-9 or abs(x0 - ox0) >= WATER_DX - 1e-9 \
                    or abs(x1 - ox1) >= WATER_DX - 1e-9:
                return True
        return False

    @staticmethod
    def _flow_changed(old, new):
        """Has a run appeared or vanished, or a cell's h moved by FLOW_DH, or its q by FLOW_DQ (relative)?"""
        if old is None or len(old) != len(new):
            return True
        for a, b in zip(old, new):
            if (a["k0"], a["dir"], a["src"], a["dst"], len(a["h"])) != (b["k0"], b["dir"], b["src"], b["dst"], len(b["h"])):
                return True
            for ha, hb in zip(a["h"], b["h"]):
                if abs(ha - hb) >= FLOW_DH - 1e-9:
                    return True
            for qa, qb in zip(a["q"], b["q"]):
                if abs(qa - qb) >= FLOW_DQ * max(qa, qb, 0.1) - 1e-9:
                    return True
        return False

    def emit(self, frame_no):
        """The v2.4 / v2.4.1 keys of the frame numbered `frame_no`: `water`, `flood`, `drains` and `flow` where their send
        rules say so."""
        out = {}
        wl = None
        send = False
        if self.sent_water is None:
            wl, send = self.water_list(), True
        elif frame_no - self.sent_water_frame >= WATER_EVERY:
            wl = self.water_list()
            send = self._water_changed(wl)
        if send:
            self.sent_water = self._water_key(wl)
            self.sent_water_frame = frame_no
            out["water"] = wl
        elif self.resend:                        # a loaded world repeats the lists once; the send cadence is untouched
            out["water"] = wl if wl is not None else self.water_list()
        if self.sorder:
            fl = None
            send = False
            if self.sent_flood is None:
                fl, send = self.flood_list(), True
            elif frame_no - self.sent_flood_frame >= FLOOD_EVERY:
                fl = self.flood_list()
                send = fl != self.sent_flood
            if send:
                self.sent_flood = fl
                self.sent_flood_frame = frame_no
                out["flood"] = fl
            elif self.resend:
                out["flood"] = fl if fl is not None else self.flood_list()
            if self.drains_pending:
                out["drains"] = self.drains_block()
                self.drains_pending = False
        fw = None
        send = False
        if self.sent_flow is None:
            fw, send = self.flow_list(), True
        elif frame_no - self.sent_flow_frame >= FLOW_EVERY and frame_no % FLOW_EVERY == 0:
            if self.film.act or self.sent_flow:
                fw = self.flow_list()
                send = self._flow_changed(self.sent_flow, fw)
        if send:
            self.sent_flow = fw
            self.sent_flow_frame = frame_no
            out["flow"] = fw
        elif self.resend:
            out["flow"] = fw if fw is not None else self.flow_list()
        self.resend = False
        return out

    # ---- the `drains` command
    def command(self, cmd):
        """Apply a `drains` command (PROTOCOL.md v2.4). Returns True when it was accepted; a bad one changes nothing."""
        if not self.sorder:
            return False
        if "mode" in cmd:
            m = cmd["mode"]
            if not isinstance(m, str) or m not in MODES:
                return False
            self.mode = self.base_mode = m
            for sid in self.sorder:
                self.sizes[sid] = size_for(m, self.seed, sid, self.streets[sid]["kind"])
            self.drains_pending = True
            return True
        if "settlement" in cmd:
            sid = cmd["settlement"]
            if isinstance(sid, bool) or not isinstance(sid, int) or sid not in self.sizes:
                return False
            if "size" in cmd:
                v = self._num(cmd["size"])
                if v is None:
                    return False
                self.sizes[sid] = clamp_size(v)
            elif "delta" in cmd:
                v = self._num(cmd["delta"])
                if v is None:
                    return False
                self.sizes[sid] = clamp_size(self.sizes[sid] + v)
            else:
                return False
            self.mode = "custom"
            self.drains_pending = True
            return True
        if "delta" in cmd:
            v = self._num(cmd["delta"])
            if v is None:
                return False
            for sid in self.sorder:
                self.sizes[sid] = clamp_size(self.sizes[sid] + v)
            self.mode = "custom"
            self.drains_pending = True
            return True
        return False

    @staticmethod
    def _num(v):
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            return None
        try:
            v = float(v)
        except OverflowError:
            return None
        return v if math.isfinite(v) else None

    # ---- the totals the conservation tests read
    def stored(self):
        """Everything the field holds: the bodies' volumes and the film."""
        return sum(b.V for b in self._bodies()) + self.film.total()

    # ---- save / load (the `water` block of the state file)
    def to_state(self):
        tm = self.tmap
        reps = [el.rep for el in tm.elems]
        return {
            "model": MODEL, "mode": self.mode, "base_mode": self.base_mode,
            "sizes": [[sid, self.sizes[sid]] for sid in self.sorder],
            "next_id": self.next_id, "tick": self.tick, "dt_acc": self.dt_acc,
            "cacc": [[k, v] for k, v in sorted(self.acc.items()) if v],
            "film": [[k, self.film.S[k]] for k in sorted(self.film.act)],
            "fed": sorted(self._fed),
            "pools": [{"id": b.id, "a": reps[b.a], "b": reps[b.b], "v": b.V, "spilled": b.spilled}
                      for b in sorted(self.pools.values(), key=lambda b: b.id)],
            "streets": [{"id": sid, "v": self.sbody[sid].V if sid in self.sbody else 0.0,
                         "win": [list(p) for p in self.win[sid]],
                         "spilled": self.sbody[sid].spilled if sid in self.sbody else 0.0} for sid in self.sorder],
            "ledger": dict(self.ledger),
            "sent_water": None if self.sent_water is None else [[i, *v] for i, v in sorted(self.sent_water.items())],
            "sent_water_frame": self.sent_water_frame,
            "sent_flood": self.sent_flood, "sent_flood_frame": self.sent_flood_frame,
            "sent_flow": self.sent_flow, "sent_flow_frame": self.sent_flow_frame,
            "drains_pending": self.drains_pending,
        }

    @classmethod
    def from_state(cls, terrain, settlements, objs, seed, s):
        """Rebuild from a saved block. A block without keys (or None) gives the new-world default: mode `normal`, sizes
        computed from the seed, no standing water. A v2.4 block (street bodies, no film) loads too: the water its street
        bodies held is put on the street as film."""
        f = cls(terrain, settlements, objs, seed, "normal")
        if not s:
            return f
        f.base_mode = s.get("base_mode", "normal")
        f.mode = s.get("mode", f.base_mode)
        for sid, size in s.get("sizes", []):
            if sid in f.sizes:
                f.sizes[sid] = float(size)
        f.next_id = int(s.get("next_id", f.next_id))
        f.tick = int(s.get("tick", 0))
        f.dt_acc = float(s.get("dt_acc", 0.0))
        rep = f.tmap.rep_index
        nb = f.film.nb
        for k, v in s.get("cacc", []):
            if 0 <= int(k) < nb:
                f.acc[int(k)] = f.acc.get(int(k), 0.0) + float(v)
        for r, v in s.get("acc", []):                                  # (v2.4: rain tallied per element: onto its bed cell)
            if r in rep:
                j = rep[r]
                kk = [q for q in range(nb) if f.film.el_of[q] == j]
                if kk:
                    k = min(kk, key=lambda q: (abs(q * GROUND_STEP - (f.tmap.elems[j].bed_i * f.step if f.tmap.elems[j].bed_i >= 0
                                                                      else q * GROUND_STEP)), q))
                    f.acc[k] = f.acc.get(k, 0.0) + float(v)
        for p in s.get("pools", []):
            if p["a"] in rep and p["b"] in rep:
                body = f._new_pool(rep[p["a"]], rep[p["b"]], float(p["v"]), int(p["id"]))
                body.spilled = float(p.get("spilled", 0.0))
            else:
                f.ledger["lost"] += float(p["v"])                      # (a hollow the new map no longer has: nothing vanishes unbooked)
        f.next_id = int(s.get("next_id", f.next_id))
        for sid, body in list(f.sbody.items()):                        # a street hollow the saved world had merged into a pool
            if f.elem_body[body.a] is not body:
                del f.sbody[sid]
        for st in s.get("streets", []):
            sid = st["id"]
            if sid not in f.win:
                continue
            f.win[sid] = [[float(a), float(c)] for a, c in st.get("win", [])]
            v = float(st["v"])
            if sid in f.sbody:
                f.sbody[sid].V = v
                f.sbody[sid].spilled = float(st.get("spilled", 0.0))
            elif v > 0.0:                                              # a v2.4 street body that is not a hollow now: film on the street
                ks = f.film.span_cells[sid]
                for k in ks:
                    f.film.S[k] += v / len(ks)
                    f.film.act.add(k)
        f._fed = {int(k) for k in s.get("fed", [])}
        for k, v in s.get("film", []):
            k = int(k)
            if 0 <= k < nb and float(v) > 0.0:
                f.film.S[k] = float(v)
                f.film.act.add(k)
        f.ledger.update({k: float(v) for k, v in s.get("ledger", {}).items()})
        sw = s.get("sent_water")
        f.sent_water = None if sw is None else {int(r[0]): (r[1], r[2], r[3], r[4], r[5]) for r in sw}
        f.sent_water_frame = int(s.get("sent_water_frame", -10 ** 9))
        sf = s.get("sent_flood")
        f.sent_flood = sf
        f.sent_flood_frame = int(s.get("sent_flood_frame", -10 ** 9))
        f.sent_flow = s.get("sent_flow")
        f.sent_flow_frame = int(s.get("sent_flow_frame", -10 ** 9))
        f.drains_pending = bool(s.get("drains_pending", False))
        f.resend = True
        f._finish()
        for b in f._bodies():                                          # the rim cell a spilling body feeds is a function of its rim
            if b.spilling:
                k = f._rim_cell(b)
                b.spill_k = k if k is not None else -1
        return f
