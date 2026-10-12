"""Cloud lifecycle, uniqueness, skeletons and microphysics bookkeeping (protocol v2).

Stages: forming -> growing -> mature -> precipitating -> dissipating -> (removed /
appears once in `deaths`). `stage_times` records the sim time each stage *started*.

Every cloud owns its own random.Random, seeded from the world seed plus a counter
(never the shared/global random module). Type, size, puff skeleton, tint, lifetime,
reservoir capacity, drift and name are all derived from that one seed. A set of shape
signatures rerolls collisions so no two clouds in a run are the same.

Smoothness: a cloud is born as vapour (`form` ~ 0, w = h = 0) and condenses through a
critically damped spring (`form`, `anvil`, `shear` are springs, never stepped). Each
puff has its own growth window [a, a+d] on the `form` axis (anvil puffs: on the `anvil`
axis) and starts at its parent puff's position, so turrets bubble up one after another.
Puff radius and position are smooth functions of (form, anvil, shear, age): puffs never
pop in or out at size. At the end a cloud evaporates back to form ~ 0 before removal.

Fair weather and towering (protocol v2.1): most cumulus / stratocumulus / stratus / altostratus are
born `fair` (their reservoirs are held at FAIR_FILL of capacity, so they stay light and never rain).
While growing / mature a fair cloud may *develop* (a hazard roll on the cloud's own rng): `fair`
flips off, `fill_cap` rises to 1 over several seconds and the cloud goes on to precipitate. A cumulus
may also *tower* into a cumulonimbus: extra puffs (drive 2, grown by `morph`) and anvil puffs are
appended to its layout at r = 0, and every size / colour / capacity glides from the cumulus value
to the cumulonimbus value with `morph` (a critically damped spring), so nothing jumps.

Merging (protocol v2.3): two condensed, overlapping clouds merge; the larger survives and its layout
gains copies of the absorbed cloud's finished puffs (drive 3, 4, 5 = 1st, 2nd, 3rd merger) that swell
with a `merge` spring while the absorbed cloud fades. See the merging section further down
(begin_merge / advance_merge) and Simulation._detect_merges, which decides when. Aerosol and humidity
(atmosphere.py) shape lifetime, capacity, fair odds, develop odds and rain onset through `atmo`.

Supercell (protocol v2.3): a ninth type, a cumulonimbus-family cloud with a persistent rotating updraft.
Its skeleton adds two growth axes next to `form` and `anvil`: drive 6 = the wall cloud hanging below the
rain-free base (grown by the `wall` spring) and drive 7 = the overshooting dome above the anvil (grown by
`overshoot`); the long downwind anvil, its short back-sheared part and the stepped flanking line of
cumulus on the upwind (rear) flank ride on `anvil` and `form`. `rotation`, `wall`, `overshoot` and `hook`
are critically damped springs (see _update_supercell), so nothing in the cloud ever jumps. A supercell
always survives a merge and is never absorbed; a cumulonimbus never turns into one.

The day cycle of convection (Phase O). A cloud of the automatic spawner (`Cloud.diurnal`) in a sky that knows its hour (`atmo` is
given to update_cloud) follows the sun: a cumulus may only tower while the ground is hot (the tower hazard is scaled by
atmosphere.cb_day, lifted to the climate's night-storm floor), and a convective cloud ages faster once the sun's heat is gone
(`day_age_rate`: up to 10 x for a cumulonimbus, 3.5 x for a supercell, 2 x for a cumulus, in the dead of night) so its life cycle
ends it by about 22 h; nothing is removed at sunset. A cloud the player asked for (`spawn` command, the showcase stage) keeps
the plain clock at any hour, and so does a storm the `tornado` command is holding up.
"""
from __future__ import annotations

import math
import random

import atmosphere as atm
from naming import TYPE_PREFIX, make_name

STAGES = ["forming", "growing", "mature", "precipitating", "dissipating"]

# w, h in px for a 720 px tall world (multiplied by the world's cloud scale).
# band = (lo, hi) fraction of world height for the cloud *base*; opacity = max opacity.
TYPE_PARAMS = {
    "cumulus":       dict(w=(120, 180), h=(70, 120),  life=(40, 95),   cap=(0.45, 0.85), cb=False,
                          precip=("rain",), weight=5, band=(0.34, 0.46), tint=(250, 251, 252),
                          opacity=0.95, dark=1.0, updraft=4.5),
    "cumulonimbus":  dict(w=(300, 420), h=(260, 380), life=(95, 240),  cap=(0.95, 1.5),  cb=True,
                          precip=("rain", "mixed", "graupel"), weight=1, band=(0.42, 0.50),
                          tint=(150, 158, 168), opacity=0.98, dark=1.0, updraft=7.5),
    "nimbostratus":  dict(w=(240, 340), h=(110, 170), life=(120, 230), cap=(0.8, 1.25),  cb=False,
                          precip=("rain", "snow", "mixed"), weight=2, band=(0.36, 0.46),
                          tint=(170, 176, 184), opacity=0.95, dark=1.0, updraft=3.0),
    "stratocumulus": dict(w=(180, 270), h=(60, 100),  life=(60, 150),  cap=(0.35, 0.65), cb=False,
                          precip=("rain",), weight=3, band=(0.32, 0.42), tint=(215, 219, 224),
                          opacity=0.90, dark=0.9, updraft=3.0),
    "altostratus":   dict(w=(220, 340), h=(60, 110),  life=(80, 180),  cap=(0.45, 0.85), cb=False,
                          precip=("snow", "rain"), weight=2, band=(0.18, 0.28), tint=(205, 208, 216),
                          opacity=0.80, dark=0.7, updraft=3.0),
    "altocumulus":   dict(w=(200, 320), h=(50, 90),   life=(70, 160),  cap=(0.30, 0.55), cb=False,
                          precip=("none",), weight=3, band=(0.20, 0.30), tint=(228, 230, 236),
                          opacity=0.86, dark=0.5, updraft=3.0),
    "stratus":       dict(w=(280, 480), h=(40, 75),   life=(90, 200),  cap=(0.25, 0.50), cb=False,
                          precip=("rain",), weight=2, band=(0.40, 0.48), tint=(192, 196, 202),
                          opacity=0.88, dark=0.8, updraft=3.0),
    "cirrus":        dict(w=(260, 460), h=(30, 60),   life=(100, 220), cap=(0.20, 0.40), cb=False,
                          precip=("none",), weight=3, band=(0.10, 0.20), tint=(238, 242, 248),
                          opacity=0.50, dark=0.0, updraft=3.0),
    # supercell: bigger and longer-lived than a cumulonimbus; weight 0 so only the climate's
    # `type_weights` (atmosphere.supercell_raw) and the `spawn` command ever create one
    "supercell":     dict(w=(400, 520), h=(400, 520), life=(260, 360), cap=(1.4, 2.0),  cb=True,
                          precip=("rain", "mixed", "graupel"), weight=0, band=(0.44, 0.52),
                          tint=(128, 136, 148), opacity=0.99, dark=1.0, updraft=9.0),
}
TYPE_NAMES = tuple(TYPE_PARAMS)

MAX_SEED_ATTEMPTS = 8
MAX_PUFFS = 40
MIN_PUFFS = 12
SUPERCELL_MAX_PUFFS = 56    # tower, long anvil, overshoot, flanking line and wall cloud need more than 40

# Merging (protocol v2.3). A merged cloud may carry up to MERGED_MAX_PUFFS puffs; a cumulus that can
# still tower keeps TOWER_RESERVE of them free so the tower and anvil always fit.
MERGED_MAX_PUFFS = 72
TOWER_RESERVE = 12
MAX_PARTS = 3
MERGE_FORM_MIN = 0.85       # both clouds must be this condensed
MERGE_OVERLAP_X = 0.35      # horizontal overlap, as a share of the narrower cloud
MERGE_OVERLAP_Y = 0.30      # vertical overlap, as a share of the thinner cloud
MERGE_T = 5.0               # seconds from the start of a merge to ~95 % done (critically damped)
MERGE_DONE = 0.9999
MIN_COPIES = 3              # a merge needs room for at least this many absorbed puffs
MERGE_WINDOW_PER_PX = 0.0075   # growth window (on the merge axis) per px of an absorbed puff's radius, at least

# Fair weather (v2.1). (probability of being born fair, develop-hazard scale) per type; types whose
# precip is ("none",) (cirrus, altocumulus) are always fair and never develop; cumulonimbus and
# nimbostratus are never fair.
FAIR_PARAMS = {
    "cumulus": (0.80, 1.0), "stratocumulus": (0.80, 0.9), "stratus": (0.80, 0.8),
    "altostratus": (0.80, 0.9),
}
FAIR_FILL = 0.22            # fraction of capacity a fair cloud's reservoirs may hold
DEVELOP_HAZARD = 3.2        # integrated hazard over the develop window (x type scale), see _tri()
DEVELOP_WINDOW = (0.05, 0.22, 0.45)    # life_frac: window start, peak, end of the roll
TOWER_HAZARD = 2.0          # cumulus -> cumulonimbus
TOWER_WINDOW = (0.10, 0.27, 0.50)
MORPH_READY = 0.95          # a towering cloud may start precipitating once morph reaches this


def _clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


# -- skeleton generation -------------------------------------------------------------
# A puff is the tuple (drive, a, d, px, py, R, shade, par, hfrac, wA, wW, wP):
#   drive  0 = grows with `form`, 1 = grows with `anvil`
#   a, d   growth window on that axis (radius ease 0->1 over [a, a+d])
#   px,py  final centre relative to the cloud base centre (y up = negative)
#   R      final radius;  shade 0 = lit top .. 1 = shadowed base
#   par    index of the parent puff (start position) or -1 (starts at the core)
#   hfrac  0 at the base .. 1 at the top (how much wind shear leans it)
#   wA,wW,wP  slow "breathing" amplitude / angular speed / phase
class _Builder:
    def __init__(self, rng, H):
        self.rng = rng
        self.H = H
        self.p = []

    def add(self, px, py, R, a, d, par=-1, drive=0, shade=None):
        rng = self.rng
        d = _clamp(d, 0.26, 0.7)
        a = _clamp(a, 0.0, 1.0 - d)
        if shade is None:
            shade = _clamp(1.0 + 0.9 * py / self.H + rng.uniform(-0.06, 0.06), 0.0, 1.0)
        hfrac = _clamp(-py / self.H, 0.0, 1.0)
        self.p.append((drive, round(a, 3), round(d, 3), round(px, 1), round(py, 1), round(R, 1),
                       round(shade, 2), par, round(hfrac, 3), round(rng.uniform(0.008, 0.03), 4),
                       round(rng.uniform(0.25, 0.8), 3), round(rng.uniform(0.0, 6.283), 3)))
        return len(self.p) - 1

    def children(self, parent, n, scale=(0.35, 0.6), dist=(0.7, 0.95), ang=(-160.0, -20.0),
                 gap=(0.10, 0.22), d=(0.3, 0.5)):
        rng = self.rng
        dr, a0, d0, px, py, R = self.p[parent][0], self.p[parent][1], self.p[parent][2], \
            self.p[parent][3], self.p[parent][4], self.p[parent][5]
        out = []
        for _ in range(n):
            th = math.radians(rng.uniform(*ang))
            dd = R * rng.uniform(*dist)
            r = R * rng.uniform(*scale)
            out.append(self.add(px + math.cos(th) * dd, py + math.sin(th) * dd, r,
                                a0 + d0 * 0.4 + rng.uniform(*gap), rng.uniform(*d), parent, dr))
        return out


def _cumulus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    nb = rng.randint(4, 6)
    base = []
    for i in range(nb):
        u = (i + 0.5) / nb * 2.0 - 1.0
        R = H * rng.uniform(0.17, 0.25)
        base.append(b.add(u * W * 0.42 + rng.uniform(-0.02, 0.02) * W, -R * 0.82, R,
                          abs(u) * 0.14 + rng.uniform(0.0, 0.08), rng.uniform(0.3, 0.45)))
    k = rng.randint(3, 4)
    for i in range(k):
        u = (i + 0.5) / k * 2.0 - 1.0
        dome = max(0.0, 1.0 - u * u) ** 0.6
        R = H * rng.uniform(0.26, 0.36) * (0.62 + 0.38 * dome)
        px = u * W * 0.34 + rng.uniform(-0.03, 0.03) * W
        py = -R * 0.9 - dome * H * rng.uniform(0.22, 0.38)
        par = min(base, key=lambda j: abs(b.p[j][3] - px))
        lobe = b.add(px, py, R, b.p[par][1] + rng.uniform(0.10, 0.25), rng.uniform(0.3, 0.5), par)
        kids = b.children(lobe, rng.randint(2, 3))
        if rng.random() < 0.7 and kids:
            b.children(kids[rng.randrange(len(kids))], 1, scale=(0.4, 0.6))
    return b.p


def _cumulonimbus(rng, W, H, dirn):
    b = _Builder(rng, H)
    nb = rng.randint(7, 9)
    base = []
    for i in range(nb):
        u = (i + 0.5) / nb * 2.0 - 1.0
        R = H * rng.uniform(0.085, 0.12) * (1.0 - 0.25 * abs(u))
        base.append(b.add(u * W * 0.42, -R * 0.84, R, abs(u) * 0.14 + rng.uniform(0.0, 0.06),
                          rng.uniform(0.3, 0.45)))
    m = rng.randint(4, 5)
    cx = rng.uniform(-0.06, 0.06) * W
    prev = min(base, key=lambda j: abs(b.p[j][3] - cx))
    top = prev
    for i in range(m):
        R = H * rng.uniform(0.15, 0.19) * (1.0 - 0.09 * i)
        py = -H * 0.20 - i * (H * 0.62 / max(1, m - 1))
        px = cx + rng.uniform(-0.04, 0.04) * W
        a = 0.10 + i * 0.13 + rng.uniform(0.0, 0.04)
        t = b.add(px, py, R, a, rng.uniform(0.3, 0.42), prev)
        if i >= 1:
            for sgn in (-1.0, 1.0):
                b.add(px + sgn * R * rng.uniform(0.75, 1.05), py + R * rng.uniform(0.1, 0.4),
                      R * rng.uniform(0.62, 0.82), a + rng.uniform(0.04, 0.10),
                      rng.uniform(0.3, 0.42), t)
        prev = t
        top = t
    tp = b.p[top]
    b.children(top, 2, scale=(0.4, 0.55), ang=(-130.0, -50.0), gap=(0.08, 0.16))
    # anvil: a flat row spreading downwind from the top of the tower as `anvil` rises
    na = rng.randint(7, 9)
    for j in range(na):
        u = -0.25 + 1.25 * j / (na - 1)
        R = H * rng.uniform(0.07, 0.095) * (1.0 - 0.35 * abs(u))
        px = tp[3] + dirn * u * W * 0.55
        py = tp[4] + tp[5] * 0.30 + abs(u) * H * 0.010 + rng.uniform(-3.0, 3.0)
        b.add(px, py, R, abs(u) * 0.45 + rng.uniform(0.0, 0.08), rng.uniform(0.35, 0.5), top,
              drive=1, shade=_clamp(rng.uniform(0.05, 0.3) + abs(u) * 0.1, 0.0, 1.0))
    return b.p


def _supercell(rng, W, H, dirn):
    """Supercell skeleton (x grows downwind by `dirn`; y up is negative, 0 = the base level):
    a flat base row; an updraft tower on the upwind (rear) side; a long downwind anvil with a short
    back-sheared part (drive 1); an overshooting dome (drive 7) above the tower; a flanking line of
    stepped cumulus on the rear flank (drive 0, late windows); a wall cloud (drive 6) hanging below the
    rain-free base under the updraft."""
    b = _Builder(rng, H)
    tx = -dirn * 0.10 * W
    nb = rng.randint(8, 9)
    base = []
    for i in range(nb):
        u = (i + 0.5) / nb * 2.0 - 1.0
        R = H * rng.uniform(0.08, 0.105) * (1.0 - 0.25 * abs(u))
        base.append(b.add(u * W * 0.44, -R * 0.84, R, abs(u) * 0.14 + rng.uniform(0.0, 0.06),
                          rng.uniform(0.3, 0.45)))
    near = lambda x: min(base, key=lambda j: abs(b.p[j][3] - x))
    m = 6
    prev = near(tx)
    top = prev
    for i in range(m):
        R = H * rng.uniform(0.17, 0.20) * (1.0 - 0.10 * i)
        py = -H * 0.17 - i * (H * 0.47 / (m - 1))
        px = tx + dirn * (0.035 * W * i / (m - 1)) + rng.uniform(-0.02, 0.02) * W
        a = 0.10 + i * 0.11 + rng.uniform(0.0, 0.04)
        t = b.add(px, py, R, a, rng.uniform(0.3, 0.42), prev)
        if i >= 1:
            for sgn in (-1.0, 1.0):
                b.add(px + sgn * R * rng.uniform(0.85, 1.1), py + R * rng.uniform(0.1, 0.4),
                      R * rng.uniform(0.62, 0.82), a + rng.uniform(0.04, 0.10),
                      rng.uniform(0.3, 0.42), t)
        prev = t
        top = t
    tp = b.p[top]
    b.children(top, 2, scale=(0.4, 0.55), ang=(-130.0, -50.0), gap=(0.08, 0.16))
    # the long downwind anvil
    na = 11
    for j in range(na):
        u = -0.05 + 1.15 * j / (na - 1)
        R = H * rng.uniform(0.085, 0.105) * (1.0 - 0.2 * max(0.0, u))
        b.add(tp[3] + dirn * u * W * 0.85, tp[4] + tp[5] * 0.30 + max(0.0, u) * H * 0.012 + rng.uniform(-3.0, 3.0),
              R, abs(u) * 0.45 + rng.uniform(0.0, 0.08), rng.uniform(0.35, 0.5), top, drive=1,
              shade=_clamp(rng.uniform(0.05, 0.3) + abs(u) * 0.1, 0.0, 1.0))
    # ... and its short back-sheared part, upwind of the tower
    for k in range(1, 4):
        b.add(tp[3] - dirn * (0.06 + 0.075 * k) * W, tp[4] + tp[5] * 0.32 + rng.uniform(-2.0, 2.0),
              H * rng.uniform(0.07, 0.085) * (1.0 - 0.22 * k), 0.02 + k * 0.10 + rng.uniform(0.0, 0.04),
              rng.uniform(0.35, 0.5), top, drive=1, shade=_clamp(rng.uniform(0.08, 0.3), 0.0, 1.0))
    # the overshooting dome punched through the anvil above the updraft
    for dx, dy, rs, a in ((0.01 * W * dirn, -0.55, 0.70, 0.0), (-dirn * 0.55 * tp[5], -0.20, 0.55, 0.15),
                          (dirn * 0.60 * tp[5], -0.28, 0.50, 0.22)):
        b.add(tp[3] + dx, tp[4] + dy * tp[5], tp[5] * rs, a, rng.uniform(0.4, 0.55), top, drive=7,
              shade=_clamp(rng.uniform(0.0, 0.12), 0.0, 1.0))
    # the flanking line: stepped cumulus on the rear flank, each lower than the one nearer the updraft
    for k in range(1, 4):
        Hk = H * (0.36 - 0.09 * (k - 1))
        px = tx - dirn * (0.14 + 0.11 * k) * W
        R1 = Hk * 0.34
        R2 = Hk * 0.28
        a = 0.30 + 0.07 * k + rng.uniform(0.0, 0.03)
        lo = b.add(px, -R1 * 0.9, R1, a, rng.uniform(0.3, 0.4), near(px))
        b.add(px + rng.uniform(-0.2, 0.2) * R2, -Hk * 0.62, R2, a + rng.uniform(0.06, 0.12),
              rng.uniform(0.3, 0.4), lo)
    # the wall cloud, lowered under the base beneath the updraft (grown by `wall`)
    xw = tx - dirn * 0.05 * W
    for k, (ox, oy, rr) in enumerate(((-0.055, 0.035, 0.060), (-0.018, 0.075, 0.072),
                                      (0.020, 0.068, 0.066), (0.058, 0.030, 0.055))):
        px = xw + ox * W
        b.add(px, oy * H, rr * H, 0.04 * k + rng.uniform(0.0, 0.05), rng.uniform(0.4, 0.55), near(px),
              drive=6, shade=_clamp(rng.uniform(0.86, 0.97), 0.0, 1.0))
    return b.p


def _sheet(b, rng, W, H, layers, n_lo, n_hi, r_lo, r_hi, layer_shade, y_lo=0.18, y_hi=0.85,
           a_layer=0.15, wobble=0.04):
    for li in range(layers):
        frac = li / max(1, layers - 1) if layers > 1 else 0.5
        y_l = -H * (y_lo + (y_hi - y_lo) * frac)
        span = W * (1.0 - 0.12 * li)
        n = rng.randint(n_lo, n_hi)
        for j in range(n):
            u = (j + 0.5) / n * 2.0 - 1.0
            R = H * rng.uniform(r_lo, r_hi)
            b.add(u * span * 0.5 + rng.uniform(-0.01, 0.01) * W, y_l + rng.uniform(-wobble, wobble) * H,
                  R, li * a_layer + abs(u) * 0.35 + rng.uniform(0.0, 0.10), rng.uniform(0.4, 0.55),
                  shade=_clamp(layer_shade(li) + rng.uniform(-0.05, 0.05), 0.0, 1.0))


def _nimbostratus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    n = int(_clamp(W / (H * 0.24), 6, 10))
    _sheet(b, rng, W, H, 3, n, n + 1, 0.17, 0.25, lambda li: 0.90 - 0.22 * li)
    return b.p


def _altostratus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    n = int(_clamp(W / (H * 0.45), 6, 10))
    _sheet(b, rng, W, H, 2, n, n + 1, 0.20, 0.30, lambda li: 0.62 - 0.22 * li, y_lo=0.30, y_hi=0.70)
    return b.p


def _stratus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    n = int(_clamp(W / (H * 0.55), 10, 16))
    _sheet(b, rng, W, H, 1, n, n, 0.32, 0.5, lambda li: 0.70, y_lo=0.4, y_hi=0.4, wobble=0.06)
    return b.p


def _stratocumulus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    for row in range(2):
        y_r = -H * (0.30 + 0.38 * row)
        n = rng.randint(4, 6)
        for j in range(n):
            u = (j + 0.5) / n * 2.0 - 1.0 + (0.5 / n if row else 0.0)
            R = H * rng.uniform(0.26, 0.36)
            cell = b.add(u * W * 0.46 + rng.uniform(-0.02, 0.02) * W, y_r + rng.uniform(-0.05, 0.05) * H,
                         R, row * 0.12 + rng.uniform(0.0, 0.35), rng.uniform(0.35, 0.5),
                         shade=_clamp(0.85 - 0.35 * row + rng.uniform(-0.05, 0.05), 0.0, 1.0))
            b.children(cell, 1, scale=(0.4, 0.55), ang=(-130.0, -50.0), gap=(0.08, 0.18))
    return b.p


def _altocumulus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    for row in range(3):
        y_r = -H * (0.22 + 0.28 * row)
        n = rng.randint(5, 7)
        for j in range(n):
            u = (j + 0.5) / n * 2.0 - 1.0 + (0.5 / n if row % 2 else 0.0)
            R = H * rng.uniform(0.14, 0.2)
            b.add(u * W * 0.46 + rng.uniform(-0.015, 0.015) * W, y_r + rng.uniform(-0.04, 0.04) * H,
                  R, rng.uniform(0.0, 0.5), rng.uniform(0.3, 0.45),
                  shade=_clamp(0.55 - 0.1 * row + rng.uniform(-0.06, 0.06), 0.0, 1.0))
    return b.p


def _cirrus(rng, W, H, _dirn):
    b = _Builder(rng, H)
    for _ in range(rng.randint(3, 4)):
        sx = rng.uniform(-0.35, 0.35) * W
        sy = -H * rng.uniform(0.15, 0.85)
        length = W * rng.uniform(0.35, 0.6)
        th = math.radians(rng.uniform(-12.0, -3.0))
        curve = rng.uniform(-0.12, 0.12)
        m = rng.randint(6, 9)
        a0 = rng.uniform(0.0, 0.3)
        prev = -1
        for j in range(m):
            t = j / (m - 1)
            R = H * rng.uniform(0.2, 0.3) * (1.0 - 0.5 * t)
            prev = b.add(sx + math.cos(th) * length * t, sy + math.sin(th) * length * t + curve * length * t * t,
                         R, a0 + t * 0.4, rng.uniform(0.3, 0.45), prev,
                         shade=_clamp(rng.uniform(0.05, 0.25), 0.0, 1.0))
    return b.p


_BUILDERS = {
    "cumulus": _cumulus, "cumulonimbus": _cumulonimbus, "nimbostratus": _nimbostratus,
    "stratocumulus": _stratocumulus, "altostratus": _altostratus, "altocumulus": _altocumulus,
    "stratus": _stratus, "cirrus": _cirrus, "supercell": _supercell,
}


def build_layout(rng, cloud_type, W, H, dirn=1.0):
    layout = _BUILDERS[cloud_type](rng, W, H, dirn)
    cap = SUPERCELL_MAX_PUFFS if cloud_type == "supercell" else MAX_PUFFS
    if len(layout) > cap:
        layout = layout[:cap]
    # pad to the minimum with extra child bumps (never needed for current builders, kept as a guard)
    n_try = 0
    while len(layout) < MIN_PUFFS and n_try < 20:
        n_try += 1
        par = rng.randrange(len(layout))
        pd, pa, pdd, ppx, ppy, pR = layout[par][:6]
        th = math.radians(rng.uniform(-150.0, -30.0))
        layout.append((pd, round(min(1.0 - 0.3, pa + pdd * 0.4 + 0.1), 3), 0.3,
                       round(ppx + math.cos(th) * pR * 0.8, 1), round(ppy + math.sin(th) * pR * 0.8, 1),
                       round(pR * 0.45, 1), layout[par][6], par,
                       layout[par][8], 0.015, 0.5, 1.0))
    return [tuple(p) for p in layout]


# -- geometry --------------------------------------------------------------------------
def eval_layout(layout, form, anvil, shear, age, core, want_bbox=False, morph=0.0, merges=None, extra=None):
    """Evaluate the skeleton. Returns (raw puffs [(x, y, r, shade)], bbox or None).
    Positions are relative to the cloud's base centre (the anchor). The bbox (only computed on
    request, it is for spawn-time sizing) counts puffs that have started growing.
    Drive 2 puffs (a tower built onto a cumulus) grow on min(morph, form), so they also recede
    with the cloud when it evaporates. Drive 3, 4, 5 puffs are the copies of the 1st, 2nd, 3rd
    absorbed cloud (v2.3): they grow on min(merges[k], form); `merges` None = every merge finished.
    Drive 6 (a supercell's wall cloud) and 7 (its overshooting dome) grow on min(wall, form) and
    min(overshoot, form), `extra` = (wall, overshoot); None = (0, 1): the wall cloud is not part of the
    cloud's bounds, the dome is."""
    maxis = morph if morph < form else form
    if merges is None:
        m1 = m2 = m3 = form
    else:
        m1 = merges[0] if merges[0] < form else form
        m2 = merges[1] if merges[1] < form else form
        m3 = merges[2] if merges[2] < form else form
    if extra is None:
        wl = 0.0
        ov = form
    else:
        wl = extra[0] if extra[0] < form else form
        ov = extra[1] if extra[1] < form else form
    cx = []
    cy = []
    out = []
    minx = miny = 1e18
    maxx = maxy = -1e18
    any_born = False
    sin = math.sin
    for drive, a, d, px, py, R, shade, par, hfrac, wA, wW, wP in layout:
        if not drive:
            p = form
        elif drive == 1:
            p = anvil
        elif drive == 2:
            p = maxis
        elif drive < 6:
            p = m1 if drive == 3 else (m2 if drive == 4 else m3)
        elif drive == 6:
            p = wl
        else:
            p = ov
        u = (p - a) / d
        if par >= 0:
            sx = cx[par]
            sy = cy[par]
        else:
            sx, sy = core
        if u <= 0.0:
            cx.append(sx)
            cy.append(sy)
            out.append((sx, sy, 0.0, shade))
            continue
        e = 1.0 if u >= 1.0 else u * u * (3.0 - 2.0 * u)
        fx = px + shear * hfrac
        x = sx + (fx - sx) * e
        y = sy + (py - sy) * e
        cx.append(x)
        cy.append(y)
        r = R * e * (1.0 + wA * sin(wW * age + wP))
        out.append((x, y, r, shade))
        if want_bbox:
            any_born = True
            if x - r < minx:
                minx = x - r
            if x + r > maxx:
                maxx = x + r
            if y - r < miny:
                miny = y - r
            if y + r > maxy:
                maxy = y + r
    return out, ((minx, miny, maxx, maxy) if any_born else None)


def _smoothstep(f):
    return f * f * (3.0 - 2.0 * f)


class Cloud:
    __slots__ = (
        "id", "seed", "rng", "type", "name", "ax", "ay", "ay_pref", "vx", "vy", "base_w", "base_h",
        "is_cb", "lifetime", "water_capacity", "ice_capacity", "precip_types", "layout", "tint",
        "born_t", "age", "stage", "water", "ice", "updraft", "precip_type", "precip_rate",
        "total_precip_mm", "peak_water", "opacity", "darkness", "stage_times", "form", "form_v",
        "anvil", "anvil_v", "anvil_max", "shear", "core", "op_max", "dark_k", "body_w", "cx_full", "cy_full",
        "x", "y", "w", "h", "puffs", "top_y", "base_y",
        "fair", "fill_cap", "from_type", "morph", "morph_v", "morph_from", "morph_to", "diurnal",
        # v2.3 merging: survivor side (parts, merge, merge_v, mg = the merge in flight, puff_part) and
        # absorbed side (merging_into and the state it started from)
        "parts", "merge", "merge_v", "mg", "puff_part",
        "merging_into", "merge_f0", "merge_dx", "merge_dy", "merge_w0", "merge_i0",
        # v2.3 supercell: the four smooth springs (+ velocities), the wall cloud's x offset from the anchor and
        # how far it hangs below it, the per-cloud peaks, and `sc_force` (the `tornado` command holds the
        # mesocyclone and wall cloud up)
        "rotation", "rotation_v", "wall", "wall_v", "overshoot", "overshoot_v", "hook", "hook_v",
        "meso_dx", "wall_drop", "rot_peak", "wall_peak", "ovs_peak", "sc_phase", "sc_force", "tornado_on")

    # -- derived -----------------------------------------------------------------------
    @property
    def life_frac(self) -> float:
        return min(1.0, self.age / self.lifetime)

    def refresh(self):
        """Recompute puffs and the smooth bounds x/y/w/h/top_y/base_y from the smooth state.

        The bounds are smooth functions of `form` and `anvil` (not a tight bbox, whose edges would
        jump whenever a puff pops across a threshold): the cloud centre glides from the vapour core
        to the finished cloud's centre, w/h grow with form and the anvil adds its spread."""
        raw, _ = eval_layout(self.layout, self.form, self.anvil, self.shear, self.age, self.core,
                             False, self.morph, self.merge_axes(),
                             (self.wall, self.overshoot) if self.type == "supercell" else None)
        g = _smoothstep(self.form)
        ox = self.cx_full * g
        oy = self.core[1] * (1.0 - g) + self.cy_full * g
        self.x = self.ax + ox
        self.y = self.ay + oy
        self.w = self.body_w * g + (self.base_w - self.body_w) * self.anvil
        self.h = self.base_h * g
        self.top_y = self.y - self.h * 0.5
        self.base_y = self.y + self.h * 0.5
        self.puffs = [[round(x - ox, 1), round(y - oy, 1), round(r, 1) if r > 0.04 else 0.0, shade]
                      for x, y, r, shade in raw]

    def merge_axes(self):
        """Progress of each absorbed cloud's puffs: finished merges are 1, the one in flight is `merge`."""
        n = len(self.parts)
        if n == 0:
            return None
        ax = [1.0, 1.0, 1.0]
        for k in range(n, 3):
            ax[k] = 0.0
        if self.mg is not None:
            ax[n - 1] = self.merge
        return ax

    def full_puffs(self):
        """The finished skeleton (form = anvil = 1, no shear) as [dx, dy, r, shade] -- for tests/signatures."""
        raw, _ = eval_layout(self.layout, 1.0, 1.0, 0.0, 0.0, self.core, False, 1.0, None,
                             (1.0, 1.0) if self.type == "supercell" else None)
        return [[round(x - self.cx_full, 1), round(y - self.cy_full, 1), round(r, 1), s]
                for x, y, r, s in raw]

    # -- state --------------------------------------------------------------------------
    def to_state(self) -> dict:
        s = {k: getattr(self, k) for k in self.__slots__
             if k not in _NESTED_SLOTS}
        s["morph_from"] = _copy_morph(self.morph_from)
        s["morph_to"] = _copy_morph(self.morph_to)
        s["parts"] = [dict(p) for p in self.parts]
        s["puff_part"] = list(self.puff_part)
        s["mg"] = _copy_morph(self.mg)
        s["rng"] = _rng_to_state(self.rng)
        s["layout"] = [list(p) for p in self.layout]
        s["precip_types"] = list(self.precip_types)
        s["tint"] = list(self.tint)
        s["stage_times"] = dict(self.stage_times)
        s["core"] = list(self.core)
        s["puffs"] = [list(p) for p in self.puffs]
        return s

    @classmethod
    def from_state(cls, s: dict) -> "Cloud":
        c = cls()
        for k in cls.__slots__:
            if k in _NESTED_SLOTS:
                continue
            # state written before v2.1 has no fair / morph keys: such clouds are not fair, never morph;
            # before v2.3 no merge keys: such clouds have merged with nothing
            default = _V21_DEFAULTS.get(k, _V23_DEFAULTS.get(k, _V26_DEFAULTS.get(k)))
            setattr(c, k, s[k] if k in s or k not in _V21_DEFAULTS and k not in _V23_DEFAULTS
                    and k not in _V26_DEFAULTS else default)
        c.morph_from = _copy_morph(s.get("morph_from"))
        c.morph_to = _copy_morph(s.get("morph_to"))
        c.parts = [dict(p) for p in s.get("parts", [])]
        c.mg = _copy_morph(s.get("mg"))
        c.rng = _rng_from_state(s["rng"])
        c.layout = [tuple(p) for p in s["layout"]]
        c.precip_types = tuple(s["precip_types"])
        c.tint = tuple(s["tint"])
        c.stage_times = dict(s["stage_times"])
        c.core = (float(s["core"][0]), float(s["core"][1]))
        c.puffs = [list(p) for p in s["puffs"]]
        c.puff_part = list(s["puff_part"]) if "puff_part" in s else [0] * len(c.layout)
        return c


# from_type "" (not None) marks a cloud loaded from a pre-v2.1 file: it keeps the old behaviour, so it
# may never tower (begin_tower only accepts from_type None); it is never sent to the viewer either.
_V21_DEFAULTS = {"fair": False, "fill_cap": 1.0, "from_type": "", "morph": 0.0, "morph_v": 0.0}
_V23_DEFAULTS = {"merge": 1.0, "merge_v": 0.0, "merging_into": None, "merge_f0": 1.0, "merge_dx": 0.0,
                 "merge_dy": 0.0, "merge_w0": 0.0, "merge_i0": 0.0,
                 "rotation": 0.0, "rotation_v": 0.0, "wall": 0.0, "wall_v": 0.0, "overshoot": 0.0,
                 "overshoot_v": 0.0, "hook": 0.0, "hook_v": 0.0, "meso_dx": 0.0, "wall_drop": 0.0,
                 "rot_peak": 0.0, "wall_peak": 0.0, "ovs_peak": 0.0, "sc_phase": 0.0, "sc_force": 0.0,
                 "tornado_on": 0.0}
# Phase O: `diurnal` = the cloud follows the day cycle of convection (towering gated by the hour, convective clouds age
# faster after dark). False for a cloud the player asked for with the `spawn` command and for showcase clouds; a file
# written before Phase O has no key: its clouds follow the day cycle.
_V26_DEFAULTS = {"diurnal": True}
# slots that are lists / dicts and are copied by hand in to_state / from_state
_NESTED_SLOTS = ("rng", "layout", "precip_types", "tint", "stage_times", "core", "puffs",
                 "morph_from", "morph_to", "parts", "mg", "puff_part")


def _copy_morph(d):
    if d is None:
        return None
    return {k: (list(v) if isinstance(v, (list, tuple)) else v) for k, v in d.items()}


def _rng_to_state(rng: random.Random):
    ver, internal, gauss = rng.getstate()
    return [ver, list(internal), gauss]


def _rng_from_state(s) -> random.Random:
    rng = random.Random()
    rng.setstate((int(s[0]), tuple(int(v) for v in s[1]), s[2]))
    return rng


rng_to_state = _rng_to_state
rng_from_state = _rng_from_state


def _quantize_signature(cloud_type, w, h, layout, tint):
    tint_bucket = tuple(c // 20 for c in tint)
    size_bucket = (round(w / 15), round(h / 15))
    puff_fp = tuple(sorted((round(p[3] / max(w, 1.0), 1), round(p[4] / max(h, 1.0), 1),
                            round(p[5] / max(h, 1.0), 1)) for p in layout))
    return (cloud_type, size_bucket, tint_bucket, puff_fp)


def spawn_cloud(cloud_id, world_seed, counter, x, y, world_t, signatures, world_w=3840, world_h=1080,
                terrain=None, rng_source=None, wind_dir=1.0, cloud_type=None, fair=None, type_mix=None,
                atmo=None, diurnal=True):
    """Create a fully-unique Cloud as invisible vapour (form = 0).

    (x, y) is where the vapour starts (its core). y=None picks an altitude from the type's band.
    `rng_source` lets an explicit spawn still flow through the world's seed stream.
    `fair` (None = decide from the cloud's rng) forces the fair-weather flag; tests use it.
    `type_mix` ({type: weight}) replaces the default type weights (region presets, the climate).
    `atmo` (an atmosphere.Atmosphere, optional) lets the aerosol and humidity shape the new cloud: more
    nuclei -> longer life, a smaller reservoir (it rains less), more fair-weather clouds.
    `diurnal` (Phase O; False for a cloud the player asked for): the cloud follows the day cycle of convection in
    update_cloud (a cumulus towers only while the ground is hot, a convective cloud ages faster after dark)."""
    attempt = 0
    aer = atmo.aerosol if atmo is not None else atm.REF_AEROSOL
    hum = atmo.humidity if atmo is not None else atm.REF_HUMIDITY
    life_k = atm.lifetime_factor(aer, hum)
    af = atmo.af() if atmo is not None else 1.0       # 1.0 except in a desert preset's dry air
    cap_k = atm.capacity_factor(aer) * af ** 4
    base_seed = rng_source if rng_source is not None else (world_seed * 1_000_003 + counter * 97)
    scale = _clamp(world_h / 720.0, 1.0, 1.8)
    while True:
        seed = (base_seed + attempt * 7919) & 0xFFFFFFFF
        rng = random.Random(seed)
        if cloud_type:
            ctype = cloud_type
        elif type_mix:
            ctype = rng.choices(TYPE_NAMES, weights=[type_mix.get(k, 0.0) for k in TYPE_NAMES], k=1)[0]
        else:
            ctype = rng.choices(TYPE_NAMES, weights=[TYPE_PARAMS[k]["weight"] for k in TYPE_NAMES], k=1)[0]
        tp = TYPE_PARAMS[ctype]
        W = rng.uniform(*tp["w"]) * scale
        H = rng.uniform(*tp["h"]) * scale
        lifetime = rng.uniform(*tp["life"]) * life_k
        capacity = rng.uniform(*tp["cap"]) * cap_k
        tint = tuple(_clamp(int(c + rng.uniform(-14, 14)), 0, 255) for c in tp["tint"])
        if y is None:
            base_y = world_h * rng.uniform(*tp["band"])
        else:
            base_y = None
        top_room = (base_y if base_y is not None else (y + 0.45 * H)) - 0.04 * world_h
        if ctype in ("cumulonimbus", "supercell") and top_room > 0:
            H = min(H, top_room * 0.9)
        dirn = 1.0 if wind_dir >= 0 else -1.0
        layout = build_layout(rng, ctype, W, H, dirn)
        full, bb = eval_layout(layout, 1.0, 1.0, 0.0, 0.0, (0.0, -0.45 * H), want_bbox=True)
        base_w = bb[2] - bb[0]
        base_h = bb[3] - bb[1]
        cx_full = (bb[0] + bb[2]) * 0.5
        cy_full = (bb[1] + bb[3]) * 0.5
        if tp["cb"]:
            _b, bb0 = eval_layout(layout, 1.0, 0.0, 0.0, 0.0, (0.0, -0.45 * H), want_bbox=True)
            body_w = bb0[2] - bb0[0]
        else:
            body_w = base_w
        sig = _quantize_signature(ctype, base_w, base_h, layout, tint)
        if sig not in signatures or attempt >= MAX_SEED_ATTEMPTS:
            signatures.add(sig)
            break
        attempt += 1

    name = make_name(rng, ctype, cloud_id)
    vx = rng.uniform(-4.0, 10.0)
    vy = rng.uniform(-0.3, 0.3)
    precip_types = tp["precip"]
    if precip_types == ("none",):
        water_capacity = capacity * (0.1 if ctype == "cirrus" else 0.4)
        ice_capacity = capacity * (0.9 if ctype == "cirrus" else 0.35)
    else:
        water_capacity = capacity * (0.55 if "rain" in precip_types else 0.4)
        ice_capacity = capacity * (0.6 if tp["cb"] or "snow" in precip_types else 0.25)

    c = Cloud()
    c.id = cloud_id
    c.seed = seed
    c.rng = rng
    c.type = ctype
    c.name = name
    c.core = (0.0, -0.45 * H)
    c.ax = x
    c.ay = (base_y if base_y is not None else y + 0.45 * H)
    c.ay_pref = c.ay
    c.vx = vx
    c.vy = vy
    c.base_w = base_w
    c.base_h = base_h
    c.body_w = body_w
    c.cx_full = cx_full
    c.cy_full = cy_full
    c.is_cb = tp["cb"]
    c.lifetime = lifetime
    c.water_capacity = water_capacity
    c.ice_capacity = ice_capacity
    c.precip_types = precip_types
    c.layout = layout
    c.tint = tint
    c.born_t = world_t
    c.age = 0.0
    c.stage = "forming"
    c.water = 0.0
    c.ice = 0.0
    c.updraft = 0.0
    c.precip_type = "none"
    c.precip_rate = 0.0
    c.total_precip_mm = 0.0
    c.peak_water = 0.0
    c.opacity = 0.0
    c.darkness = 0.0
    c.stage_times = {"forming": round(world_t, 1)}
    c.form = 0.0
    c.form_v = 0.0
    c.anvil = 0.0
    c.anvil_v = 0.0
    c.anvil_max = rng.uniform(0.75, 1.0) if tp["cb"] else 0.0
    c.shear = 0.0
    c.op_max = tp["opacity"]
    c.dark_k = tp["dark"]
    # fair weather: decided last so every other draw of the cloud's stream is unchanged
    if precip_types == ("none",):
        c.fair = True                                   # cirrus / altocumulus: always fair
    elif ctype in FAIR_PARAMS:
        c.fair = (rng.random() < atm.fair_probability(FAIR_PARAMS[ctype][0], aer, hum, af)) if fair is None else bool(fair)
    else:
        c.fair = False
    c.fill_cap = FAIR_FILL if (c.fair and precip_types != ("none",)) else 1.0
    c.from_type = None
    c.diurnal = bool(diurnal)
    c.morph = 0.0
    c.morph_v = 0.0
    c.morph_from = None
    c.morph_to = None
    c.parts = []
    c.merge = 1.0
    c.merge_v = 0.0
    c.mg = None
    c.puff_part = [0] * len(layout)
    c.merging_into = None
    c.merge_f0 = 1.0
    c.merge_dx = 0.0
    c.merge_dy = 0.0
    c.merge_w0 = 0.0
    c.merge_i0 = 0.0
    c.rotation = c.rotation_v = c.wall = c.wall_v = c.overshoot = c.overshoot_v = 0.0
    c.hook = c.hook_v = c.sc_force = c.tornado_on = 0.0
    c.meso_dx = c.wall_drop = c.rot_peak = c.wall_peak = c.ovs_peak = c.sc_phase = 0.0
    if ctype == "supercell":
        # (drawn last, so no other type's stream changes) how strong this storm's mesocyclone, wall cloud and
        # overshooting top can get: a weak one never lowers a wall cloud, a strong one may drop a tornado
        c.rot_peak = round(rng.uniform(0.55, 1.0), 3)
        c.wall_peak = round(rng.uniform(0.7, 1.0), 3)
        c.ovs_peak = round(rng.uniform(0.6, 1.0), 3)
        c.sc_phase = round(rng.uniform(0.0, 6.283), 3)
        c.meso_dx, c.wall_drop = wall_geometry(layout)
    if terrain is not None:
        lim = _clearance_limit(c, terrain)
        if c.ay > lim:
            c.ay = lim
            c.ay_pref = min(c.ay_pref, lim)
    # keep the finished cloud on screen (only for auto-placed clouds; a click is taken literally)
    if y is None:
        min_ay = base_h * 1.05 + 0.02 * world_h
        if c.ay < min_ay:
            c.ay = c.ay_pref = min_ay
    c.refresh()
    return c


def wall_geometry(layout):
    """(x offset of the wall cloud's centre from the cloud anchor, how far its puffs hang below the base level)."""
    sw = sx = drop = 0.0
    for p in layout:
        if p[0] == 6:
            sw += p[5]
            sx += p[3] * p[5]
            drop = max(drop, p[4] + p[5])
    return (round(sx / sw, 1), round(drop, 1)) if sw > 0.0 else (0.0, 0.0)


def _clearance_limit(cloud, terrain):
    half = cloud.base_w * 0.35
    ha = terrain.height_at
    surf = min(ha(cloud.ax - half), ha(cloud.ax), ha(cloud.ax + half))
    return surf - 40.0


def _advance_stage(cloud, new_stage, world_t, events):
    if cloud.stage == new_stage:
        return
    cloud.stage = new_stage
    cloud.stage_times[new_stage] = round(world_t, 1)
    events.append(f"t={world_t:.1f} {cloud.name} entered {new_stage}")


def _spring(x, v, target, omega, dt):
    """Critically damped spring step (sub-stepped when omega*dt is large)."""
    n = int(omega * dt / 0.3) + 1
    h = dt / n
    w2 = omega * omega
    w2x = 2.0 * omega
    for _ in range(n):
        v += (w2 * (target - x) - w2x * v) * h
        x += v * h
    if x < 0.0:
        x = 0.0
        v = 0.0
    elif x > 1.0:
        x = 1.0
        v = 0.0
    return x, v


# -- fair weather: developing, and towering into a cumulonimbus -----------------------------
_MORPH_KEYS = ("body_w", "base_w", "base_h", "cx_full", "cy_full", "lifetime", "water_capacity",
               "ice_capacity", "op_max", "dark_k")


def _tri(lf, lo, pk, hi):
    """Triangular hazard shape on life_frac: 0 outside (lo, hi), 1 at pk."""
    if lf <= lo or lf >= hi:
        return 0.0
    return (lf - lo) / (pk - lo) if lf < pk else (hi - lf) / (hi - pk)


def develop_cloud(cloud, world_t, events):
    """A fair cloud starts developing into a rain cloud: `fair` is false from now on, `fill_cap`
    (the share of capacity the reservoirs may hold) then rises smoothly to 1."""
    cloud.fair = False
    events.append(f"t={world_t:.1f} {cloud.name} began developing into a rain cloud")


def begin_tower(cloud, world, terrain, world_t, events):
    """Turn a cumulus into a cumulonimbus (once). Returns False, changing nothing but the rng
    stream, when there is not enough sky above it. The existing puffs keep their index and values;
    a tower, a wider base skirt and an anvil are appended at r = 0 and swell with `morph` (then
    `anvil`); every size, colour and capacity glides from the cumulus's value to the
    cumulonimbus's with `morph` (see _apply_morph). All randomness comes from cloud.rng."""
    if cloud.type != "cumulus" or cloud.from_type is not None:
        return False
    if cloud.mg is not None or cloud.merging_into is not None:
        return False          # a merge is still settling: the tower waits for the next hazard roll
    rng = cloud.rng
    cb = TYPE_PARAMS["cumulonimbus"]
    scale = _clamp(world.h / 720.0, 1.0, 1.8)
    W = rng.uniform(*cb["w"]) * scale
    Hc = rng.uniform(*cb["h"]) * scale
    tint1 = [_clamp(int(c + rng.uniform(-14, 14)), 0, 255) for c in cb["tint"]]
    life1 = rng.uniform(*cb["life"])
    capk = rng.uniform(*cb["cap"])
    anvil_max = rng.uniform(0.75, 1.0)

    # sky above the (wider) base, same rule as spawn_cloud for a native cumulonimbus
    half = W * 0.9 * 0.35
    ha = terrain.height_at
    lim = min(ha(cloud.ax - half), ha(cloud.ax), ha(cloud.ax + half)) - 40.0
    room = min(cloud.ay_pref, lim, cloud.ay) - 0.04 * world.h
    Ht = min(Hc, room * 0.9)

    full, bb = eval_layout(cloud.layout, 1.0, 1.0, 0.0, 0.0, cloud.core, want_bbox=True)
    top0 = min(y - r for _x, y, r, _s in full)
    low0 = max(y + r for _x, y, r, _s in full)
    h0 = low0 - top0
    if Ht < max(1.5 * h0, 100.0 * scale):
        return False
    L = len(cloud.layout)
    budget = (MERGED_MAX_PUFFS if cloud.parts else MAX_PUFFS) - L
    na = rng.randint(5, 6)
    # the tower must be a connected stack from the cumulus's top to the top of the cloud: as many
    # turrets as the height needs (3..5), each at least 0.7 x the spacing wide so neighbours overlap
    y0 = top0 + 0.35 * Ht * 0.17
    R_top = Ht * 0.13
    y2 = -Ht + R_top
    n_t = int(_clamp(math.ceil((y0 - y2) / (Ht * 0.21)) + 1, 3, 5))
    while n_t > 3 and budget < n_t + na + 2:
        n_t -= 1
    if budget < n_t + na + 2:
        na = 4
    if budget < n_t + na + 2:
        return False

    dirn = 1.0 if world.prevailing_u >= 0 else -1.0
    b = _Builder(rng, Ht)
    b.p = [tuple(p) for p in cloud.layout]
    spare = budget - (n_t + na + 2)
    n_side, lobe_levels, child = 1, 0, False
    for item, cost in (("skirt", 2), ("lobes", 2), ("lobes", 2), ("child", 1), ("skirt", 2)):
        if spare >= cost:
            spare -= cost
            if item == "skirt":
                n_side += 1
            elif item == "lobes":
                lobe_levels += 1
            else:
                child = True

    # a wider base skirt on both sides, level with the cumulus's flat base
    cx0 = (bb[0] + bb[2]) * 0.5
    x_in = (bb[2] - bb[0]) * 0.5 * 0.85
    base_like = [j for j in range(L) if full[j][1] + full[j][2] >= low0 - 0.25 * h0] or list(range(L))
    R_s = Ht * 0.10
    spacing = min((0.42 * W - x_in) / n_side, 1.2 * R_s)     # neighbours overlap, so the skirt is one piece
    for sgn in (-1.0, 1.0):
        for i in range(n_side):
            R = R_s * rng.uniform(0.9, 1.15)
            px = cx0 + sgn * (x_in + spacing * (i + 1))
            par = min(base_like, key=lambda j: abs(full[j][0] - px))
            b.add(px, low0 - R, R, rng.uniform(0.0, 0.25), rng.uniform(0.3, 0.45), par, drive=2)

    # the tower: stacked turrets rising from the cumulus's highest lobe
    tl = min(range(L), key=lambda j: full[j][1] - full[j][2])
    px0 = full[tl][0]
    step = (y0 - y2) / (n_t - 1)
    prev = tl
    top = tl
    for j in range(n_t):
        R = max(Ht * (0.17 - 0.04 * j / (n_t - 1)), 0.7 * step)
        py = y0 + (y2 - y0) * j / (n_t - 1)
        px = px0 + rng.uniform(-0.04, 0.04) * W
        a = 0.05 + j * (0.5 / (n_t - 1)) + rng.uniform(0.0, 0.04)
        t = b.add(px, py, R, a, rng.uniform(0.3, 0.42), prev, drive=2)
        if j >= n_t - lobe_levels and j >= 1:
            for sgn in (-1.0, 1.0):
                b.add(px + sgn * R * rng.uniform(0.75, 1.05), py + R * rng.uniform(0.1, 0.4),
                      R * rng.uniform(0.62, 0.82), a + rng.uniform(0.04, 0.10),
                      rng.uniform(0.3, 0.42), t, drive=2)
        prev = t
        top = t
    if child:
        b.children(top, 1, scale=(0.4, 0.55), ang=(-130.0, -50.0), gap=(0.08, 0.16))
    # the anvil, spread downwind from the top of the tower as `anvil` rises
    tp = b.p[top]
    # anvil: a row that overlaps neighbour to neighbour, leaning downwind from the top of the tower
    sp = min(W * 0.55 * 1.25 / (na - 1), 1.2 * Ht * 0.09)
    for j in range(na):
        u = j / (na - 1)
        R = Ht * rng.uniform(0.09, 0.115) * (1.0 - 0.3 * u)
        b.add(tp[3] + dirn * sp * (j - 0.6),
              tp[4] + tp[5] * 0.30 + u * Ht * 0.010 + rng.uniform(-3.0, 3.0),
              R, u * 0.45 + rng.uniform(0.0, 0.08), rng.uniform(0.35, 0.5), top, drive=1,
              shade=_clamp(rng.uniform(0.05, 0.3) + u * 0.1, 0.0, 1.0))
    layout = [tuple(p) for p in b.p]

    _f, bb1 = eval_layout(layout, 1.0, 1.0, 0.0, 0.0, cloud.core, want_bbox=True, morph=1.0)
    _b, bb2 = eval_layout(layout, 1.0, 0.0, 0.0, 0.0, cloud.core, want_bbox=True, morph=1.0)
    morph_from = {"body_w": cloud.body_w, "base_w": cloud.base_w, "base_h": cloud.base_h,
                  "cx_full": cloud.cx_full, "cy_full": cloud.cy_full, "lifetime": cloud.lifetime,
                  "water_capacity": cloud.water_capacity, "ice_capacity": cloud.ice_capacity,
                  "op_max": cloud.op_max, "dark_k": cloud.dark_k,
                  "tint": list(cloud.tint), "up": TYPE_PARAMS["cumulus"]["updraft"]}
    life_to = max(life1, cloud.age / 0.45, cloud.age + 80.0)    # a long stretch of life left to rain in
    morph_to = {"body_w": bb2[2] - bb2[0], "base_w": bb1[2] - bb1[0], "base_h": bb1[3] - bb1[1],
                "cx_full": (bb1[0] + bb1[2]) * 0.5, "cy_full": (bb1[1] + bb1[3]) * 0.5,
                "lifetime": life_to,
                "water_capacity": max(cloud.water_capacity, capk * 0.55),
                "ice_capacity": max(cloud.ice_capacity, capk * 0.6),
                "op_max": cb["opacity"], "dark_k": cb["dark"],
                "tint": tint1, "up": cb["updraft"], "T": _clamp(0.14 * life_to, 24.0, 30.0)}   # ~T s from the switch to morph 0.95

    old_name = cloud.name
    cloud.puff_part = list(cloud.puff_part) + [0] * (len(layout) - L)     # the tower is the cloud's own
    cloud.layout = layout
    cloud.type = "cumulonimbus"
    cloud.from_type = "cumulus"
    cloud.name = TYPE_PREFIX["cumulonimbus"] + old_name[len(TYPE_PREFIX["cumulus"]):]
    cloud.is_cb = True
    cloud.precip_types = cb["precip"]
    cloud.anvil_max = anvil_max
    cloud.fair = False
    cloud.morph = 0.0
    cloud.morph_v = 0.0
    cloud.morph_from = morph_from
    cloud.morph_to = morph_to
    events.append(f"t={world_t:.1f} {old_name} began towering into a cumulonimbus (now {cloud.name})")
    return True


def _apply_morph(cloud, dt):
    """Advance `morph` and glide everything the cumulonimbus changes from the cumulus value to the
    cumulonimbus value (smoothstep of morph). Reservoirs keep their fill when capacities grow."""
    mt = cloud.morph_to
    mf = cloud.morph_from
    cloud.morph, cloud.morph_v = _spring(cloud.morph, cloud.morph_v, 1.0, 4.7 / mt["T"], dt)
    m = cloud.morph       # already a critically damped spring: no second easing on top
    wc0 = cloud.water_capacity
    ic0 = cloud.ice_capacity
    for k in _MORPH_KEYS:
        setattr(cloud, k, mf[k] + (mt[k] - mf[k]) * m)
    if wc0 > 1e-9:
        cloud.water *= cloud.water_capacity / wc0
    if ic0 > 1e-9:
        cloud.ice *= cloud.ice_capacity / ic0
    t0 = mf["tint"]
    t1 = mt["tint"]
    cloud.tint = tuple(int(round(t0[i] + (t1[i] - t0[i]) * m)) for i in range(3))


# -- merging (protocol v2.3) --------------------------------------------------------------------
# Two condensed clouds that overlap enough merge: the larger one (a cumulonimbus always beats a
# non-cb, then the larger capacity) survives, the other is absorbed. The absorbed cloud's finished puffs
# are appended to the survivor's layout as drive 2 + k puffs (k = 1, 2, 3 for the 1st, 2nd, 3rd merger)
# at radius 0 and swell with `merge`, a critically damped spring 0 -> 1; the absorbed cloud fades with
# form = form0 * (1 - merge), its puffs' growth windows are the mirror image of the copies' windows, so
# where one shrinks the other swells and the sky never thins out. Everything else the merge changes
# (bounds, capacities, reservoirs, lifetime) moves by `delta * d(merge)` each tick, so it is smooth and
# lands exactly on the combined cloud's values; the tint glides to the capacity-weighted mean.
_MERGE_KEYS = ("body_w", "base_w", "base_h", "cx_full", "cy_full", "lifetime", "water_capacity",
               "ice_capacity", "water", "ice")


def merge_ready(c) -> bool:
    """May `c` take part in a merge (either side)?"""
    return (c.form >= MERGE_FORM_MIN and c.stage != "dissipating" and c.merging_into is None
            and c.mg is None and (c.morph_to is None or c.morph >= 0.999))


def merge_overlap(a, b) -> bool:
    """The overlap test, on the clouds' smooth bounds (x, y, w, h). Two supercells never merge (neither would
    survive: a supercell is never absorbed)."""
    if a.type == "supercell" and b.type == "supercell":
        return False
    aw = a.w
    bw = b.w
    ox = min(a.x + aw * 0.5, b.x + bw * 0.5) - max(a.x - aw * 0.5, b.x - bw * 0.5)
    if ox <= MERGE_OVERLAP_X * min(aw, bw):
        return False
    oy = min(a.base_y, b.base_y) - max(a.top_y, b.top_y)
    return oy > MERGE_OVERLAP_Y * min(a.h, b.h)


def merge_survivor(a, b):
    """(survivor, absorbed): a cumulonimbus always survives a non-cb, else the larger capacity, else the older id."""
    ka = (a.type == "supercell", a.is_cb, a.water_capacity + a.ice_capacity, -a.id)
    kb = (b.type == "supercell", b.is_cb, b.water_capacity + b.ice_capacity, -b.id)
    return (a, b) if ka >= kb else (b, a)


def merge_room(s) -> int:
    """How many more puffs the survivor may take (a cumulus that can still tower keeps TOWER_RESERVE free)."""
    cap = MERGED_MAX_PUFFS - (TOWER_RESERVE if (s.type == "cumulus" and s.from_type is None) else 0)
    return cap - len(s.layout)


def begin_merge(s, a, world_t: float, events: list) -> bool:
    """Absorb cloud `a` into `s`. Returns False and changes nothing when there is no room for the puffs."""
    if len(s.parts) >= MAX_PARTS or s.mg is not None or a.mg is not None:
        return False
    room = merge_room(s)
    # the absorbed cloud's finished puffs as they look now (its own shear and anvil), in its own frame
    raw_a, _ = eval_layout(a.layout, 1.0, a.anvil, a.shear, 0.0, a.core, False, 1.0, None)
    keep = [i for i, (_x, _y, r, _sh) in enumerate(raw_a)
            if r >= 1.5 and not (s.is_cb and a.layout[i][0] == 1)]    # a cumulonimbus keeps its own anvil
    if len(keep) < MIN_COPIES or room < MIN_COPIES:
        return False
    if len(keep) > room:                                               # short on room: the largest puffs
        keep = sorted(sorted(keep, key=lambda i: -raw_a[i][2])[:room])
    k = len(s.parts) + 1
    L0 = len(s.layout)
    kept = set(keep)
    new_index = {i: L0 + n for n, i in enumerate(keep)}
    full_s, _ = eval_layout(s.layout, 1.0, 1.0, 0.0, 0.0, s.core, False, 1.0, None)
    anchors = [j for j, q in enumerate(full_s) if q[2] > 1.0]
    dx = a.ax - s.ax
    dy = a.ay - s.ay
    hs = max(s.base_h, 1.0)
    f0 = max(a.form, 0.5)
    copies = []
    for i in keep:
        x, y, r, shade = raw_a[i]
        d0, a0, dd, _px, _py, _R, _sh, par0, _hf, w_amp, w_ang, w_ph = a.layout[i]
        tx = x + dx
        ty = y + dy
        p = par0
        while p >= 0 and p not in kept:
            p = a.layout[p][7]
        if p >= 0:
            par = new_index[p]
        else:       # a root puff buds from the survivor's puff nearest to where it ends up
            par = min(anchors, key=lambda j: (full_s[j][0] - tx) ** 2 + (full_s[j][1] - ty) ** 2)
        hfrac = _clamp(-ty / hs, 0.0, 1.0)
        if d0 == 0:         # mirror image of the absorbed puff's own window on the form axis
            dn = _clamp(dd / f0, 0.26, 0.7)
            an = _clamp(1.0 - (a0 + dd) / f0, 0.0, 1.0 - dn)
        else:
            dn = _clamp(dd, 0.26, 0.7)
            an = _clamp(a0, 0.0, 1.0 - dn)
        r_final = r / (1.0 + w_amp * math.sin(w_ph))          # eval_layout applies the breathing again
        # a big puff gets a longer window, so no copy swells faster than ~2.5 px a frame (a fully condensed
        # cumulonimbus taken in by a supercell reached 3 px a frame with the 0.26 minimum)
        if r_final * MERGE_WINDOW_PER_PX > dn:
            dn = min(0.7, r_final * MERGE_WINDOW_PER_PX)
            an = _clamp(an, 0.0, 1.0 - dn)
        copies.append((2 + k, round(an, 3), round(dn, 3), round(tx - s.shear * hfrac, 1), round(ty, 1),
                       round(r_final, 1), shade, par, round(hfrac, 3), w_amp, w_ang, w_ph))
    layout = [tuple(p) for p in s.layout] + copies
    _f, bb1 = eval_layout(layout, 1.0, 1.0, 0.0, 0.0, s.core, True, 1.0, None)
    _b, bb2 = eval_layout(layout, 1.0, 0.0, 0.0, 0.0, s.core, True, 1.0, None)
    new_base_w = bb1[2] - bb1[0]
    cap_s = s.water_capacity + s.ice_capacity
    cap_a = a.water_capacity + a.ice_capacity
    sr = max(0.0, s.lifetime - s.age)
    ar = max(0.0, a.lifetime - a.age)
    new_life = s.age + max(sr, ar) + 0.3 * min(sr, ar)
    wcap = (s.water_capacity + a.water_capacity) * s.fill_cap
    icap = (s.ice_capacity + a.ice_capacity) * s.fill_cap
    mg = {"prev": 0.0, "a_id": a.id,
          "tint0": list(s.tint),
          "tint1": [(s.tint[i] * cap_s + a.tint[i] * cap_a) / max(cap_s + cap_a, 1e-9) for i in range(3)],
          "d_body_w": ((bb2[2] - bb2[0]) if s.is_cb else new_base_w) - s.body_w,
          "d_base_w": new_base_w - s.base_w,
          "d_base_h": (bb1[3] - bb1[1]) - s.base_h,
          "d_cx_full": (bb1[0] + bb1[2]) * 0.5 - s.cx_full,
          "d_cy_full": (bb1[1] + bb1[3]) * 0.5 - s.cy_full,
          "d_lifetime": max(0.0, new_life - s.lifetime),
          "d_water_capacity": a.water_capacity, "d_ice_capacity": a.ice_capacity,
          "d_water": max(0.0, min(a.water, wcap - s.water)),
          "d_ice": max(0.0, min(a.ice, icap - s.ice))}
    # the survivor from here on: a towered cloud stops gliding towards the cumulonimbus's numbers (its
    # tower is finished, morph >= 0.999) so that the merge's glide is the only thing moving them
    if s.morph_to is not None:
        s.morph_from = None
        s.morph_to = None
    s.puff_part = list(s.puff_part) + [k] * len(copies)
    s.layout = layout
    s.parts.append({"id": a.id, "type": a.type, "name": a.name})
    s.merge = 0.0
    s.merge_v = 0.0
    s.mg = mg
    if not a.fair and a.precip_types != ("none",) and s.fair and s.precip_types != ("none",):
        develop_cloud(s, world_t, events)          # a fair cloud that takes in a rain cloud stops being fair
    # the absorbed cloud: dissipating, dry, locked to the survivor while it fades
    a.merging_into = s.id
    a.merge_f0 = max(a.form, 1e-6)
    a.merge_dx = dx
    a.merge_dy = dy
    a.merge_w0 = a.water
    a.merge_i0 = a.ice
    a.stage = "dissipating"
    a.stage_times["dissipating"] = round(world_t, 1)
    a.precip_rate = 0.0
    s.refresh()             # the frame of this tick lists the appended r = 0 puffs next to puff_part
    events.append(f"t={world_t:.1f} {s.name} absorbed {a.name}")
    return True


def advance_merge(s, a, dt: float) -> None:
    """One tick of the merge in flight on survivor `s` (`a` = the absorbed cloud if it is still around)."""
    mg = s.mg
    if mg is None:
        return
    s.merge, s.merge_v = _spring(s.merge, s.merge_v, 1.0, 4.7 / MERGE_T, dt)
    m = s.merge
    if m >= MERGE_DONE:
        m = s.merge = 1.0
        s.merge_v = 0.0
    dm = m - mg["prev"]
    mg["prev"] = m
    for key in _MERGE_KEYS:
        setattr(s, key, getattr(s, key) + mg["d_" + key] * dm)
    t0 = mg["tint0"]
    t1 = mg["tint1"]
    s.tint = tuple(int(round(t0[i] + (t1[i] - t0[i]) * m)) for i in range(3))
    if a is not None:
        a.form = a.merge_f0 * (1.0 - m)
        a.form_v = 0.0
        a.water = a.merge_w0 * (1.0 - m)
        a.ice = a.merge_i0 * (1.0 - m)
    if m >= 1.0:
        s.mg = None


def lock_absorbed(a, s) -> None:
    """Keep the absorbed cloud where it was relative to the survivor's anchor, whatever the survivor did."""
    a.ax = s.ax + a.merge_dx
    a.ay = s.ay + a.merge_dy
    a.refresh()


def _finish_look(cloud, cb_amt):
    """Opacity and darkness: smooth functions of the reservoirs and `form`."""
    fill = (cloud.water + cloud.ice) / max(1e-6, cloud.water_capacity + cloud.ice_capacity)
    f = cloud.form
    f = f * f * (3.0 - 2.0 * f)
    cloud.opacity = round(min(0.98, cloud.op_max * (0.62 + 0.38 * fill) * f), 3)
    dark = 0.15 + 0.6 * (cloud.water / max(1e-6, cloud.water_capacity)) + 0.15 * cb_amt
    cloud.darkness = round(min(0.9, dark * cloud.dark_k) * f, 3)


def _update_absorbed(cloud, dt, wind):
    """A cloud that is being merged away: form, water and ice follow the survivor's `merge` (see
    advance_merge), its anchor is locked to the survivor's (lock_absorbed); only age, shear, anvil and
    the look are its own. It leaves once it has faded to nothing."""
    cloud.age += dt
    if cloud.form < 0.02:
        cloud.form = 0.0
        return False
    wu_t, _ = wind.sample(cloud.ax, max(1.0, cloud.top_y))
    wu_b, _ = wind.sample(cloud.ax, cloud.base_y)
    cb_amt = 1.0 if cloud.is_cb else 0.0
    lim = cloud.base_w * 0.4
    target_shear = max(-lim, min(lim, (wu_t - wu_b) * (12.0 + 6.0 * cb_amt)))
    cloud.shear += (target_shear - cloud.shear) * min(1.0, dt * 0.4)
    if cloud.is_cb:
        cloud.anvil, cloud.anvil_v = _spring(cloud.anvil, cloud.anvil_v, 0.0, 4.7 / MERGE_T, dt)
    cloud.refresh()
    _finish_look(cloud, cb_amt)
    return True


# -- supercell: rotation, wall cloud, overshooting top, rear-flank downdraft (protocol v2.3) -----------------
SUPERCELL_ONSET_SHIFT = 0.14    # a supercell starts raining earlier in its life than other clouds
SUPERCELL_DRAIN = 0.30          # ... but empties 3x slower,
SUPERCELL_REFILL = 2.5          # ... and its persistent updraft refills it faster
SC_SPRING_T = 9.0               # seconds for rotation / wall / hook to get ~95 % of the way to a new target
SC_OVS_T = 10.0
HOOK_NO_TORNADO = 0.8           # a supercell without a tornado: its rear-flank downdraft peaks at this
TORNADO_MIN_ROTATION = 0.7      # the genesis gate (also used by the `tornado` command)
TORNADO_MIN_WALL = 0.6

# -- sustained rain (the sandbox; see the precipitating branch of update_cloud) ------------------------------
RAIN_END_LF = 0.8               # life_frac at which the engine ends any rain (as before)
SUSTAINED_END_FILL = 0.35       # reservoir fill a sustained rain arrives at when it ends (above the 0.12 exit level)
SUSTAINED_RATE = 0.3            # how hard a sustained rain falls for a given reservoir, against the old ~10 s burst


def smooth01(x, lo, hi):
    """0 below lo, 1 above hi, smoothstep between."""
    if x <= lo:
        return 0.0
    if x >= hi:
        return 1.0
    u = (x - lo) / (hi - lo)
    return u * u * (3.0 - 2.0 * u)


def supercell_mature(c) -> bool:
    """A supercell the tornado logic may use: a condensed cloud past its growing stage, not fading."""
    return c.type == "supercell" and c.stage in ("mature", "precipitating") and c.form >= 0.85


def _update_supercell(cloud, dt):
    """Advance rotation, wall, overshoot and hook: critically damped springs towards targets that follow the
    cloud's life (so no value ever jumps, whatever the stage does). rotation rises with the tower
    (life_frac 0.10-0.32) up to this storm's peak; the wall cloud lowers once the mesocyclone is strong;
    the overshooting top pulses on the mature tower; the rear-flank downdraft (hook) follows the rotation."""
    lf = cloud.life_frac
    alive = cloud.stage in ("growing", "mature", "precipitating") and cloud.form > 0.5
    rain = cloud.stage == "precipitating"
    t_rot = t_wall = t_ovs = t_hook = 0.0
    if alive:
        t_rot = cloud.rot_peak * smooth01(lf, 0.10, 0.32) * (1.0 - smooth01(lf, 0.74, 0.90))
        if cloud.sc_force:
            t_rot = max(t_rot, 0.9)
        mature = cloud.stage != "growing"
        t_wall = (cloud.wall_peak * smooth01(cloud.rotation, 0.45, 0.72) * (1.0 if mature else 0.0)
                  * (1.0 - smooth01(lf, 0.80, 0.92)))
        if cloud.sc_force:
            t_wall = max(t_wall, 0.95)
        pulse = 0.82 + 0.18 * math.sin(0.17 * cloud.age + cloud.sc_phase)
        t_ovs = (cloud.ovs_peak * smooth01(lf, 0.16, 0.30) * smooth01(cloud.form, 0.8, 0.97) * pulse
                 * (1.0 - smooth01(lf, 0.72, 0.86)))
        # the rear-flank downdraft belongs to the storm, tornado or not: it follows the (lagging) mesocyclone,
        # stronger once the storm rains and the wall cloud is down, and strongest (up to 1) while it has a tornado
        t_hook = (smooth01(cloud.rotation, 0.25, 0.85) * (0.7 + 0.3 * (1.0 if rain else 0.0))
                  * (0.7 + 0.3 * smooth01(cloud.wall, 0.1, 0.6)) * (1.0 if (cloud.tornado_on or cloud.sc_force)
                                                                       else HOOK_NO_TORNADO))
    om = 4.7 / SC_SPRING_T
    cloud.rotation, cloud.rotation_v = _spring(cloud.rotation, cloud.rotation_v, t_rot, om, dt)
    cloud.wall, cloud.wall_v = _spring(cloud.wall, cloud.wall_v, t_wall, om, dt)
    cloud.overshoot, cloud.overshoot_v = _spring(cloud.overshoot, cloud.overshoot_v, t_ovs, 4.7 / SC_OVS_T, dt)
    cloud.hook, cloud.hook_v = _spring(cloud.hook, cloud.hook_v, t_hook, om, dt)


def meso_x(cloud) -> float:
    """World x of the mesocyclone / wall cloud centre (rear flank, upwind of the rain)."""
    return cloud.ax + cloud.meso_dx


def day_age_rate(cloud, world, atmo) -> float:
    """Phase O: how much faster than the clock `cloud` ages right now. 1.0 for everything that does not follow the day cycle
    (no `atmo`, a cloud the player asked for, the sheets and high cloud, a storm the `tornado` command is holding up); a
    convective cloud ages faster once the sun's heat is gone (atmosphere.cb_age_rate), a cumulus a little faster after sunset."""
    if atmo is None or not cloud.diurnal:
        return 1.0
    if cloud.is_cb:
        if cloud.sc_force >= 0.5:
            return 1.0
        return atm.cb_age_rate(world.time_of_day, atmo.night_storm, atm.SC_AGE_K if cloud.type == "supercell" else None)
    if cloud.type == "cumulus":
        return atm.cu_age_rate(world.time_of_day)
    return 1.0


def update_cloud(cloud: Cloud, dt: float, world, wind, terrain, world_t: float, events: list,
                 atmo=None, sustained: bool = False) -> bool:
    """Advance one cloud by dt seconds. Returns False once it has evaporated away.
    `atmo` (atmosphere.Atmosphere, optional) lets aerosol and humidity shape when and how hard it rains.
    `sustained` (the sandbox passes True; the showcase stage runs its own rain programme and passes
    nothing): a raining cloud keeps raining until life_frac RAIN_END_LF instead of emptying in ~10 s."""
    if cloud.merging_into is not None:
        return _update_absorbed(cloud, dt, wind)
    # Phase O: the day cycle of convection. Only a cloud of the automatic spawner (cloud.diurnal) in a sky that knows its
    # hour (atmo given) follows it; `day_gate` is 1.0 for everything else, so those run exactly as before.
    age_rate = day_age_rate(cloud, world, atmo)
    day_gate = 1.0
    if atmo is not None and cloud.diurnal and cloud.type == "cumulus":
        day_gate = atm.gate(atm.cb_day(world.time_of_day), atmo.night_storm)    # a cumulus towers only in the hot hours
    cloud.age += dt * age_rate
    aer = atmo.aerosol if atmo is not None else atm.REF_AEROSOL
    hum = atmo.humidity if atmo is not None else atm.REF_HUMIDITY
    af = atmo.af() if atmo is not None else 1.0
    towering = cloud.morph_to is not None
    if towering:
        _apply_morph(cloud, dt)
    lf = cloud.life_frac

    # Drift with the local wind (advected like a very large, slow particle).
    wu, wv = wind.sample(cloud.ax, cloud.ay + cloud.core[1])
    cloud.ax += (cloud.vx + wu * 0.6) * dt
    cloud.ay_pref += (cloud.vy + wv * 0.15) * dt
    cloud.ax = max(-cloud.base_w, min(world.w + cloud.base_w, cloud.ax))
    # a cloud that has drifted (almost) out of the world just fades away
    if cloud.stage != "dissipating" and (cloud.ax <= -cloud.base_w * 0.5 or cloud.ax >= world.w + cloud.base_w * 0.5):
        _advance_stage(cloud, "dissipating", world_t, events)
    # ride up and over high ground, ease back down afterwards
    target = min(cloud.ay_pref, _clearance_limit(cloud, terrain))
    cloud.ay += (target - cloud.ay) * min(1.0, dt * 0.5)

    # --- fair weather: may start developing, or a cumulus may tower (hazard rolls on the cloud's rng)
    stg = cloud.stage
    if (stg == "growing" or stg == "mature") and cloud.form >= 0.85:
        if cloud.type == "cumulus" and cloud.from_type is None and TOWER_HAZARD > 0.0:
            shape = _tri(lf, *TOWER_WINDOW)
            # (a desert preset's dry air rarely towers: af is exactly 1.0 everywhere else)
            if shape > 0.0 and cloud.rng.random() < (TOWER_HAZARD * af ** 6 * day_gate * shape * dt / cloud.lifetime):
                begin_tower(cloud, world, terrain, world_t, events)
        if cloud.fair and cloud.type in FAIR_PARAMS and DEVELOP_HAZARD > 0.0:
            shape = _tri(lf, *DEVELOP_WINDOW)
            if shape > 0.0 and cloud.rng.random() < (DEVELOP_HAZARD * FAIR_PARAMS[cloud.type][1]
                                                     * atm.develop_scale(aer, hum, af)
                                                     * shape * dt / cloud.lifetime):
                develop_cloud(cloud, world_t, events)
    if cloud.fill_cap < 1.0 and not cloud.fair:
        cloud.fill_cap = min(1.0, cloud.fill_cap + dt / max(8.0, 0.12 * cloud.lifetime))
    wcap = cloud.water_capacity * cloud.fill_cap
    icap = cloud.ice_capacity * cloud.fill_cap
    towering = cloud.morph_to is not None     # (also true on the very tick the tower begins)
    m_s = cloud.morph
    if towering:
        tp_up = cloud.morph_from["up"] + (cloud.morph_to["up"] - cloud.morph_from["up"]) * m_s
        cb_amt = m_s
    else:
        tp_up = TYPE_PARAMS[cloud.type]["updraft"]
        cb_amt = 1.0 if cloud.is_cb else 0.0
    wobble = 0.5 + 0.5 * math.sin(cloud.age * 0.35 + cloud.seed % 7)
    growth_phase = max(0.0, 1.0 - abs(lf - 0.35) * 2.2)
    cloud.updraft = round(tp_up * (0.3 + 0.7 * growth_phase) * (0.6 + 0.4 * wobble), 2)

    top_y = cloud.top_y
    base_y = cloud.base_y
    above_freezing = top_y < world.freezing_y  # smaller y = higher altitude = colder
    form = cloud.form

    # --- stage machine -------------------------------------------------
    if cloud.stage == "forming":
        if lf > 0.06 and form >= 0.15:
            _advance_stage(cloud, "growing", world_t, events)

    elif cloud.stage == "growing":
        if above_freezing:
            # Bergeron-Findeisen process: most condensate deposits straight onto ice.
            cloud.water = min(wcap, cloud.water + cloud.updraft * 0.006 * dt)
            cloud.ice = min(icap, cloud.ice + cloud.updraft * 0.028 * dt)
        else:
            cloud.water = min(wcap, cloud.water + cloud.updraft * 0.02 * dt)
        fill = (cloud.water + cloud.ice) / max(1e-6, cloud.water_capacity + cloud.ice_capacity)
        if (lf > 0.28 or fill > 0.85) and form >= 0.85:
            _advance_stage(cloud, "mature", world_t, events)

    elif cloud.stage == "mature":
        if above_freezing:
            cloud.water = min(wcap, cloud.water + cloud.updraft * 0.003 * dt)
            cloud.ice = min(icap, cloud.ice + cloud.updraft * 0.018 * dt)
        else:
            cloud.water = min(wcap, cloud.water + cloud.updraft * 0.012 * dt)
        fill = (cloud.water + cloud.ice) / max(1e-6, cloud.water_capacity + cloud.ice_capacity)
        # a fair cloud, one whose reservoirs are still opening up, or a tower that has not finished
        # becoming a cumulonimbus does not rain yet
        if not cloud.fair and cloud.fill_cap >= 0.9 and (not towering or cloud.morph >= MORPH_READY):
            onset = atm.rain_onset_lf(aer, hum) - (SUPERCELL_ONSET_SHIFT if cloud.type == "supercell" else 0.0)
            if (lf > onset or fill > 0.9) and form >= 0.93:
                _advance_stage(cloud, "precipitating", world_t, events)
        elif lf > (0.78 if cloud.fair else 0.9):
            _advance_stage(cloud, "dissipating", world_t, events)

    elif cloud.stage == "precipitating":
        # Precip character is judged at the release point (cloud base), where particles spawn.
        has_ice = cloud.ice > 0.02
        has_water = cloud.water > 0.02
        base_above_freezing = base_y < world.freezing_y
        if cloud.precip_types == ("none",):
            cloud.precip_type = "none"      # cirrus / altocumulus: virga at most, nothing reaches the ground
        elif cloud.is_cb and has_water and has_ice and cloud.updraft > 5.0 and cloud.rng.random() < 0.02:
            cloud.precip_type = "graupel"
        elif base_above_freezing:
            if has_ice and has_water:
                cloud.precip_type = "mixed"
            elif has_ice:
                cloud.precip_type = "snow"
            else:
                cloud.precip_type = "none"
        else:
            if has_water:
                cloud.precip_type = "rain"
            elif has_ice:
                cloud.precip_type = "mixed"
            else:
                cloud.precip_type = "none"

        sup = cloud.type == "supercell"
        drain_rate = 0.09 * (1.2 if cloud.is_cb else 1.0) * atm.drain_scale(aer, hum) * (SUPERCELL_DRAIN if sup else 1.0)
        if sustained and not sup and cloud.precip_types != ("none",):
            # Sandbox rain (2026-10-09). The plain engine below empties a reservoir at ~27-32 %/s, so a
            # cumulonimbus or a nimbostratus rained for 8-12 s of a 90-140 s life and then faded at half its
            # stated lifetime. Here the updraft keeps feeding the cloud while it rains: the reservoirs glide
            # from whatever they hold now to SUSTAINED_END_FILL of capacity at RAIN_END_LF (a straight line
            # from the present point to that end point, so it needs no memory and survives save / load), and
            # what falls out each tick is the same share of the reservoir as before times SUSTAINED_RATE.
            # The rain therefore lasts from its onset to life_frac 0.8, heaviest at the start.
            total = cloud.water + cloud.ice
            released = total * drain_rate * dt * 3.0 * SUSTAINED_RATE
            fill = total / max(1e-6, cloud.water_capacity + cloud.ice_capacity)
            left = RAIN_END_LF - lf
            if left > 1e-9 and fill > SUSTAINED_END_FILL:
                k = 1.0 - (1.0 - SUSTAINED_END_FILL / fill) * min(1.0, dt / cloud.lifetime / left)
                cloud.water *= k
                cloud.ice *= k
            cloud.precip_rate = round(released / max(dt, 1e-6), 3)
            cloud.total_precip_mm = round(cloud.total_precip_mm + released * 9.0, 2)
            cloud.peak_water = max(cloud.peak_water, cloud.water)
        else:
            take_water = min(cloud.water, cloud.water * drain_rate * dt * 3.0)
            take_ice = min(cloud.ice, cloud.ice * drain_rate * dt * 3.0)
            cloud.water -= take_water
            cloud.ice -= take_ice
            released = take_water + take_ice
            if cloud.precip_types == ("none",):
                cloud.precip_rate = 0.0
            else:
                cloud.precip_rate = round(released / max(dt, 1e-6), 3)
                cloud.total_precip_mm = round(cloud.total_precip_mm + released * 9.0, 2)
            cloud.peak_water = max(cloud.peak_water, cloud.water)

            if lf < 0.75:
                k_re = SUPERCELL_REFILL if sup else 1.0       # the persistent updraft keeps feeding a supercell
                cloud.water = min(cloud.water_capacity, cloud.water + cloud.updraft * 0.004 * k_re * dt)
                if above_freezing:
                    cloud.ice = min(cloud.ice_capacity, cloud.ice + cloud.updraft * 0.003 * k_re * dt)

        fill = (cloud.water + cloud.ice) / max(1e-6, cloud.water_capacity + cloud.ice_capacity)
        if (fill < 0.05 and lf > 0.5 or lf > 0.82) if sup else (fill < 0.12 or lf > RAIN_END_LF):
            _advance_stage(cloud, "dissipating", world_t, events)

    elif cloud.stage == "dissipating":
        # (kept unrounded: rounded to 3 places every tick it could never fall below 0.018 at 30 ticks/s, so a
        # cloud that had rained reported rain until it died; the frame rounds what it sends)
        rate = cloud.precip_rate * (1.0 - dt * 0.8)
        cloud.precip_rate = rate if rate >= 0.001 else 0.0
        cloud.water = max(0.0, cloud.water - cloud.water * 0.6 * dt)
        cloud.ice = max(0.0, cloud.ice - cloud.ice * 0.5 * dt)
        if cloud.form < 0.02 and (cloud.water + cloud.ice) < 0.02:
            cloud.form = 0.0
            return False

    # --- smooth state: form (condensation), anvil, shear ---------------------
    dissipating = cloud.stage == "dissipating"
    if dissipating:
        omega = 4.7 / max(8.0, 0.18 * cloud.lifetime)
        cloud.form, cloud.form_v = _spring(cloud.form, cloud.form_v, 0.0, omega, dt)
    else:
        omega = 4.7 / max(10.0, 0.22 * cloud.lifetime)
        cloud.form, cloud.form_v = _spring(cloud.form, cloud.form_v, 1.0, omega, dt)
    if cloud.is_cb:
        want = cloud.anvil_max if (cloud.stage in ("mature", "precipitating") and cloud.form > 0.9
                                   and (not towering or cloud.morph >= 0.8)) else 0.0
        a_om = 4.7 / (12.0 if towering else max(5.0, 0.15 * cloud.lifetime))   # a towered cloud has a stretched lifetime
        cloud.anvil, cloud.anvil_v = _spring(cloud.anvil, cloud.anvil_v, want, a_om, dt)
    if cloud.age > cloud.lifetime * 2.5:   # safety net, never reached in normal play
        return False

    wu_t, _ = wind.sample(cloud.ax, max(1.0, top_y))
    wu_b, _ = wind.sample(cloud.ax, base_y)
    k = 12.0 + 6.0 * cb_amt
    lim = cloud.base_w * 0.4
    target_shear = max(-lim, min(lim, (wu_t - wu_b) * k))
    cloud.shear += (target_shear - cloud.shear) * min(1.0, dt * 0.4)

    if cloud.type == "supercell":
        _update_supercell(cloud, dt)
    cloud.refresh()

    # --- opacity / darkness follow the reservoirs and `form` smoothly ------------
    _finish_look(cloud, cb_amt)
    return True
