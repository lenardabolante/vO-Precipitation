"""Simulation: owns world state, terrain, objects, clouds, particles, wind and turns
them into protocol v2 messages (see PROTOCOL.md).
"""
from __future__ import annotations

import math
import random

from atmosphere import Atmosphere, gas_offset_c
from cars import CarSystem
from clouds import (Cloud, TORNADO_MIN_ROTATION, TORNADO_MIN_WALL, TYPE_NAMES, TYPE_PARAMS, advance_merge,
                    begin_merge, day_age_rate, lock_absorbed, merge_overlap, merge_ready, merge_room, merge_survivor,
                    meso_x, MIN_COPIES, rng_from_state, rng_to_state, smooth01, spawn_cloud,
                    supercell_mature, update_cloud)
import presets as region_presets
from objects import GROUND_STEP, GroundCover, ObjectField, apply_snow_start, generate_world
from particles import (ICE, MELT, RAIN, SNOW, GRAUPEL, ParticleSystem, particle_cap)
from savestate import save_state
from settlements import MODES as SETTLEMENT_MODES, plan_roads
from terrain import KINDS as TERRAIN_KINDS, Terrain, profile_m_per_px
from tornado import Tornado, apply_to_wind, draw_peak_ef
from water import MODES as DRAIN_MODES, WaterField
from windfield import WindField, WindModel, build_streamlines, mean_wind_magnitude
from world import (Climate, DAY_LENGTH_S, DEFAULT_HOUR, DEFAULT_MONTH, DEFAULT_YEAR_DAYS, YEAR_DAYS_RANGE, World)

# The cloud count is steered by the climate (atmosphere.py: cloud_target, spawn_per_min). These two are
# only the hard floor and ceiling per 1280 px of width: below the floor the spawner speeds up, the
# ceiling is never crossed by the automatic spawner (a `spawn` command may).
MIN_CLOUDS_PER_1280 = 1
MAX_CLOUDS_PER_1280 = 7
INITIAL_SHARE = (0.75, 1.0)   # a new world opens with this share of the climate's cloud target
MERGE_CHECK_EVERY = 6         # frames between looks for clouds that overlap (5 per second at 30 fps)
MERGE_HAZARD_PER_S = 0.006     # once a pair overlaps enough it merges with this hazard per second
CONTROL_GAIN = 1.6            # spawner speed-up per unit of relative cloud deficit
CONTROL_MAX = 3.0
STREAMLINES_PER_1280 = 10
PRECIP_RATE = 900.0           # particles per second per unit precip_rate (tuned for the 30 ticks/s budget)
PRECIP_TICK_CAP = 14          # max particles one cloud releases per tick
PRECIP_TICK_CAP_SUPERCELL = 20
GROUND_EMITS_PER_S = 3
# supercell rain: the forward flank only (downwind of the mesocyclone): a column of FLANK_HALF * width
# centred FLANK_CENTRE * width downwind of the anchor; hail = graupel particles, the big stones up to size 9
FLANK_CENTRE = 0.22
FLANK_HALF = 0.16
HAIL_BIG = 0.10               # share of a supercell's precipitation that is large hail (size 5-9)
HAIL_SMALL = 0.10             # ... and small hail (size 3-5.5)
# tornadoes: a mature supercell with rotation >= TORNADO_MIN_ROTATION and wall >= TORNADO_MIN_WALL, between
# these life fractions, drops one with this hazard (per second, scaled up with a stronger mesocyclone and
# a stronger rear-flank downdraft); a cooldown keeps a supercell from re-forming one at once
TORNADO_HAZARD_PER_S = 0.030
TORNADO_WINDOW = (0.30, 0.64)
TORNADO_COOLDOWN_S = 40.0
DEBRIS_PER_S = 42.0           # debris particles thrown per second by a full-size tornado
DEBRIS_PER_DAMAGE = 14.0      # extra pieces thrown per unit of damage dealt to a mid-size building
# sanity limits of the `wind` and `temp` commands (far outside anything the keys are used for: the keys step
# by 1 m/s and 1 C); without them a huge delta overflowed the wind grid or sent a non-JSON `Infinity`
REAL_LAPSE_C_PER_M = 0.0065   # real air cools 6.5 C per km of height (see Simulation._melt_on_warm_ground)
WIND_LIMIT = 100.0            # m/s, the prevailing wind either way
TEMP_USER_LIMIT = 100.0       # C, the sum of the `temp` commands either way


def r2(x: float) -> float:
    return round(float(x), 2)


def _freeze(x):
    return tuple(_freeze(i) for i in x) if isinstance(x, list) else x


class Simulation:
    mode = "sandbox"              # PROTOCOL.md v2.2 `hello.mode`
    merging = True                # clouds that overlap merge (the showcase stage turns this off)
    auto_tornado = True           # supercells drop tornadoes by hazard (the showcase stage runs its own)

    def __init__(self, seed: int, w: int, h: int, fps: int, terrain_kind: str = "rolling",
                 settlements: str = "normal", preset: str | None = None, month: float | None = None,
                 hour: float = DEFAULT_HOUR, year_days: int = DEFAULT_YEAR_DAYS, drains: str = "normal"):
        if terrain_kind not in TERRAIN_KINDS:
            raise ValueError(f"terrain must be one of {TERRAIN_KINDS}")
        if settlements not in SETTLEMENT_MODES:
            raise ValueError(f"settlements must be one of {SETTLEMENT_MODES}")
        if drains not in DRAIN_MODES:
            raise ValueError(f"drains must be one of {DRAIN_MODES}")
        if not (YEAR_DAYS_RANGE[0] <= int(year_days) <= YEAR_DAYS_RANGE[1]):
            raise ValueError(f"year_days must be between {YEAR_DAYS_RANGE[0]} and {YEAR_DAYS_RANGE[1]}")
        # the calendar the world opens at (v2.3); reseed keeps it
        self.start_month = None if month is None else float(month)
        self.start_hour = float(hour)
        self.year_days = int(year_days)
        # a region preset (v2.2) builds the terrain from a real cross-section and replaces --terrain
        self.preset = region_presets.get_preset(preset) if preset else None
        if self.preset:
            terrain_kind = "preset"
        self.fps = fps
        self.paused = False
        self.speed = 1.0
        self.frame = 0
        self.running = True
        self.loaded = False
        self.terrain_kind = terrain_kind
        self.settlements_mode = settlements
        self.drains_mode = drains     # v2.4: the named mode drain sizes are drawn in (a reseed keeps it)
        self.outbox: list = []        # protocol messages to send between frames (hello, saved)
        self.view = None              # (x0, x1) particle culling for the stream, or None
        self._reset(seed, w, h)

    # -- world (re)initialisation, also used by `reseed` ------------------
    def _reset(self, seed: int, w: int, h: int):
        self.seed = seed
        self.world_rng = random.Random(seed)
        rng = self.world_rng
        ready = None
        if self.preset:
            ready, _m_per_px, _lm_x = region_presets.build_terrain(self.preset, rng, w, h)
        # a region preset also dresses the world after its biome (v2.3): trees, ponds, real settlement names
        biome = self.preset["biome"] if self.preset else None
        plan = region_presets.settlement_plan(self.preset, w) if self.preset else None
        self.terrain, self.settlements, objs = generate_world(rng, w, h, self.terrain_kind,
                                                             self.settlements_mode, terrain=ready,
                                                             biome=biome, plan=plan)
        self.world = World(w, h, self.terrain.ground_y, rng, seed)
        if self.preset:
            region_presets.apply_climate(self.preset, self.world)
        self._setup_climate(seed)
        self.ground = GroundCover(self.terrain, w, max(1, round(self.fps / GROUND_EMITS_PER_S)))
        if self.preset:
            # the world opens white where the starting month is at or below freezing (biome `snow_start`)
            apply_snow_start(objs, self.ground, self._mean_freezing_y(), self.preset["biome"]["snow_start"])
        self.objects = ObjectField(objs, self.terrain)
        # v2.4 standing water and drains: sandbox mode only (showcase, hurricane and world frames carry none of it)
        self.water = (WaterField(self.terrain, self.settlements, objs, seed, self.drains_mode)
                      if self.mode == "sandbox" else None)
        self.wind_model = WindModel(self.world, self.terrain)
        self.particles = ParticleSystem(particle_cap(w))
        self.signatures: set = set()
        self.clouds: list = []
        # v2.3: tornadoes (always empty until a supercell makes one), roads and cars
        self.tornadoes: list = []
        self.next_tornado_id = 1
        self.tornadoes_total = 0
        self.tor_rng = random.Random((int(seed) * 7919 + 0x70A4ED0) & 0xFFFFFFFF)
        self.tor_cool: dict = {}
        rp = self.preset["biome"]["roads"] if self.preset else {"highway": True, "traffic": 1.0, "mix": None}
        self.roads = plan_roads(self.settlements, w, highway=rp["highway"])
        self.cars = CarSystem(self.roads, w, seed, traffic=rp["traffic"], mix=rp["mix"])
        self.cars.populate(self.terrain)
        self.next_cloud_id = 1
        self.clouds_born = 0
        self.clouds_dead = 0
        self.world_rain_mm = 0.0
        self.world_snow_mm = 0.0
        # Released precip is split into rain/snow by what actually reaches the ground
        # (snow melts to rain below the freezing level, so the cloud's precip_type isn't enough).
        self.world_precip_mm = 0.0
        self.ground_rain_hits = 0
        self.ground_snow_hits = 0
        self._lightning: list = []
        # spawn hazard (see _maybe_spawn_cloud): `_spawn_timer` accumulates births-worth of time,
        # a cloud is born when it reaches `_next_spawn_delay`, an Exp(1) draw
        self._spawn_timer = 0.0
        self._next_spawn_delay = -math.log(1.0 - rng.random())
        self._merge_acc = 0.0
        self.wind = self._build_wind()
        self.n_streamlines = max(STREAMLINES_PER_1280, int(round(STREAMLINES_PER_1280 * self.width_units)))
        self.streamlines = build_streamlines(self.wind, self.world, self.terrain, rng, self.n_streamlines)
        self.objects.settle_sway(self.wind)

        self._initial_clouds(rng)

    def _mean_freezing_y(self) -> float:
        """Screen y of the freezing level for the month's mean temperature (no day cycle, no `temp` commands)."""
        parts = self.world.temp_parts()
        return self.world.ground_y - (parts["base_c"] + parts["season_c"]) * self.world.px_per_c

    def _opening_frozen(self) -> bool:
        """True when the world's mean temperature is below freezing: ponds draw as ice (biome `frozen_water`)."""
        parts = self.world.temp_parts()
        return parts["base_c"] + parts["season_c"] <= 0.0

    def _initial_clouds(self, rng):
        """The clouds already in the sky when a world starts (showcase mode starts with none): a share
        of what the climate steers towards. A very humid preset (Meghalaya in July) opens with its clouds
        already well on the way to raining (they are fast-forwarded further into their life)."""
        # (Phase O: the count is the climate's daytime target, not the night's smaller one, so the opening sky holds as many
        # clouds as before and the shared world_rng makes the same draws: only their types follow the hour)
        target = self._get_atm()["target_open_f"]
        ff_lo, ff_hi = 0.0, 0.4
        if self.preset:
            t = max(0.0, min(1.0, (self.atmo.humidity - 0.8) / 0.15))
            ff_lo, ff_hi = 0.22 * t, 0.4 + 0.15 * t
        for _ in range(max(1, int(round(target * rng.uniform(*INITIAL_SHARE))))):
            cloud = self._spawn_cloud_auto()
            self._fast_forward_new_cloud(cloud, rng.uniform(ff_lo, ff_hi))

    # -- climate and atmosphere (v2.3) -------------------------------------------------------
    @staticmethod
    def _seeded_humidity(seed: int) -> float:
        return round(random.Random((int(seed) * 7919 + 17) & 0xFFFFFFFF).uniform(0.45, 0.70), 2)

    def _setup_climate(self, seed: int):
        """Calendar, climate and gases of a new world. A preset with a `climate_block` (twelve monthly
        means, daily range, latitude, humidity, aerosol) drives them; any other world gets the
        generated climate World built from its seed (a preset without a block keeps its v2.2 start
        temperature as 'right now')."""
        w = self.world
        block = None
        fn = getattr(region_presets, "climate_block", None) if self.preset else None
        if fn is not None:
            block = fn(self.preset)
        month = self.start_month
        if block:
            if month is None:
                month = block.get("start_month", DEFAULT_MONTH)
            clim = Climate.from_monthly(block["monthly_temp_c"], block["diurnal_range_c"],
                                        block.get("lat", 45.0), "preset")
            w.configure(clim, month, self.start_hour, self.year_days)
            self.atmo = Atmosphere(block.get("humidity", self._seeded_humidity(seed)), 420.0,
                                   block.get("aerosol", 0.3), arid=self.preset["biome"]["id"] == "desert")
        else:
            if month is None:
                month = (self.preset or {}).get("start_month", DEFAULT_MONTH)
            w.configure(None, month, self.start_hour, self.year_days)
            if self.preset:
                w.fit_generated_climate(self.preset["start"]["surface_temp_c"])
            self.atmo = Atmosphere(self._seeded_humidity(seed), 420.0, 0.3)
        w.set_gas_target(0.0)
        self._mix0 = self._base_mix()
        self._atm = None

    def _base_mix(self) -> dict:
        if self.preset:
            return dict(self.preset["cloud_mix"])
        return {k: float(v["weight"]) for k, v in TYPE_PARAMS.items()}

    def _refresh_atmosphere(self):
        w = self.world
        w.set_gas_target(gas_offset_c(self.atmo.co2_ppm))
        speed = math.hypot(w.prevailing_u, w.prevailing_v)
        self._atm = self.atmo.snapshot(w.temp_parts(), speed, self.width_units, self._mix0, w.time_of_day)

    def _get_atm(self) -> dict:
        if self._atm is None:
            self._refresh_atmosphere()
        return self._atm

    # -- derived sizes -----------------------------------------------------------
    @property
    def width_units(self) -> float:
        return self.world.w / 1280.0

    @property
    def min_clouds(self) -> int:
        """Below this many clouds the spawner runs at full tilt, whatever the climate."""
        return max(1, int(round(MIN_CLOUDS_PER_1280 * self.width_units)))

    @property
    def max_clouds(self) -> int:
        return max(self.min_clouds, int(round(MAX_CLOUDS_PER_1280 * self.width_units)))

    @property
    def cloud_scale(self) -> float:
        return max(1.0, min(1.8, self.world.h / 720.0))

    def _build_wind(self) -> WindField:
        w = self.world
        wind = self.wind_model.build(w.t, w.prevailing_u, w.prevailing_v, w.gust_phase)
        if self.tornadoes:
            apply_to_wind(wind, self.tornadoes)       # convergence, updraft and swirl near a grounded tornado
        return wind

    def _fast_forward_new_cloud(self, cloud, age_frac: float):
        """Used only for the clouds present at world start/reseed, so the scene doesn't open
        with every cloud freshly condensing. The cloud is genuinely simulated up to the chosen
        age (coarse steps, nothing is spawned), so stage, form, reservoirs and shape are
        consistent with each other by construction."""
        target = cloud.lifetime * age_frac
        scratch: list = []
        while cloud.age < target:
            step = min(0.25, target - cloud.age)
            if step <= 1e-9:
                break
            update_cloud(cloud, step, self.world, self.wind, self.terrain, 0.0, scratch, self.atmo,
                         self.mode == "sandbox")
        for k in cloud.stage_times:
            cloud.stage_times[k] = 0.0
        cloud.born_t = 0.0

    # -- cloud spawning -----------------------------------------------------
    def _spawn_cloud_auto(self, x=None, y=None, rng_source=None, cloud_type=None, fair=None, diurnal=None):
        """`diurnal` (Phase O): the cloud follows the day cycle of convection; None = only a cloud whose type the climate
        picked (a forced type, i.e. the showcase and a `spawn` command with a type, ignores the clock)."""
        if diurnal is None:
            diurnal = cloud_type is None
        if x is None:
            x = self.world_rng.uniform(self.world.w * 0.1, self.world.w * 0.9)
        cloud = spawn_cloud(self.next_cloud_id, self.seed, self.next_cloud_id, x, y,
                            self.world.t, self.signatures, world_w=self.world.w,
                            world_h=self.world.h, terrain=self.terrain, rng_source=rng_source,
                            wind_dir=self.world.prevailing_u, cloud_type=cloud_type, fair=fair,
                            type_mix=self._get_atm()["weights_f"], atmo=self.atmo, diurnal=diurnal)
        self.next_cloud_id += 1
        self.clouds_born += 1
        self.clouds.append(cloud)
        return cloud

    def spawn_rate_per_min(self) -> float:
        """The spawner's actual birth rate right now: the climate's `spawn_per_min`, sped up while the sky
        holds fewer clouds than `cloud_target` and slowed (to nothing at 1.6 x the target) while it holds more."""
        a = self._get_atm()
        n = len(self.clouds)
        target = max(a["target_f"], 1.0)
        boost = max(0.0, min(CONTROL_MAX, 1.0 + CONTROL_GAIN * (target - n) / target))
        if n < self.min_clouds:
            boost = CONTROL_MAX
        return a["spawn_per_min_f"] * boost

    def _maybe_spawn_cloud(self, dt: float, events: list):
        """Births are an inhomogeneous Poisson process of rate `spawn_rate_per_min`: the timer
        integrates the rate and a cloud forms when it passes an Exp(1) threshold."""
        if len(self.clouds) >= self.max_clouds:
            return
        self._spawn_timer += self.spawn_rate_per_min() / 60.0 * dt
        if self._spawn_timer >= self._next_spawn_delay:
            cloud = self._spawn_cloud_auto()
            self._spawn_timer = 0.0
            self._next_spawn_delay = -math.log(1.0 - self.world_rng.random())
            events.append(f"t={self.world.t:.1f} {cloud.name} formed ({cloud.type})")

    # -- merging (v2.3) -----------------------------------------------------------------------
    def _detect_merges(self, dt: float, events: list):
        """Look (a few times a second) for two condensed clouds that overlap enough; a qualifying pair
        merges with a hazard of MERGE_HAZARD_PER_S. At most one new merge per look."""
        self._merge_acc += dt
        if self.frame % MERGE_CHECK_EVERY != 0:
            return
        acc = self._merge_acc
        self._merge_acc = 0.0
        ready = [c for c in self.clouds if merge_ready(c)]
        if len(ready) < 2:
            return
        ready.sort(key=lambda c: c.x - c.w * 0.5)
        p = 1.0 - math.exp(-MERGE_HAZARD_PER_S * acc)
        for i, a in enumerate(ready):
            right = a.x + a.w * 0.5
            for b in ready[i + 1:]:
                if b.x - b.w * 0.5 >= right:
                    break
                if not merge_overlap(a, b):
                    continue
                s, ab = merge_survivor(a, b)
                if len(s.parts) >= 3 or merge_room(s) < MIN_COPIES:
                    continue
                if self.world_rng.random() < p and begin_merge(s, ab, self.world.t, events):
                    return

    def _step_merges(self, dt: float):
        """Before the clouds update: advance every merge in flight (the survivor's `merge`, the absorbed
        cloud's form) and release an absorbed cloud whose survivor is gone."""
        busy = [c for c in self.clouds if c.mg is not None or c.merging_into is not None]
        if not busy:
            return
        ids = {c.id for c in self.clouds}
        absorbed = {}
        for c in busy:
            if c.merging_into is not None:
                if c.merging_into in ids:
                    absorbed[c.merging_into] = c
                else:
                    c.merging_into = None        # its survivor died first: it just dissipates on its own
        for c in busy:
            if c.mg is not None:
                advance_merge(c, absorbed.get(c.id), dt)

    def _lock_absorbed(self):
        by_id = None
        for c in self.clouds:
            if c.merging_into is not None:
                if by_id is None:
                    by_id = {k.id: k for k in self.clouds}
                s = by_id.get(c.merging_into)
                if s is not None:
                    lock_absorbed(c, s)

    # -- precipitation particle spawning ------------------------------------
    def _spawn_precip_particles(self, cloud, dt: float):
        if cloud.stage != "precipitating" or cloud.precip_type == "none":
            return
        raw = cloud.precip_rate * dt * PRECIP_RATE
        count = int(raw)
        if cloud.rng.random() < (raw - count):
            count += 1
        sup = cloud.type == "supercell"
        count = min(count, PRECIP_TICK_CAP_SUPERCELL if sup else PRECIP_TICK_CAP)
        if count <= 0:
            return

        base_y = cloud.base_y
        base_above_freezing = base_y < self.world.freezing_y
        pt = cloud.precip_type
        rain_w = min(cloud.w, cloud.base_w) * 0.85
        rng = cloud.rng
        spawn = self.particles.spawn
        if sup:
            x0, x1 = self.rain_span(cloud)
        for _ in range(count):
            x = rng.uniform(x0, x1) if sup else cloud.ax + rng.uniform(-rain_w * 0.45, rain_w * 0.45)
            if sup and pt in ("rain", "mixed", "graupel"):
                hail = rng.random()
                if hail < HAIL_BIG:
                    spawn(GRAUPEL, x, base_y, cloud.vx * 0.5 + rng.uniform(-6, 6), rng.uniform(5, 25),
                          rng.uniform(5.0, 9.0), rng.uniform(0, 6.28))
                    continue
                if hail < HAIL_BIG + HAIL_SMALL:
                    spawn(GRAUPEL, x, base_y, cloud.vx * 0.5 + rng.uniform(-6, 6), rng.uniform(5, 25),
                          rng.uniform(3.0, 5.5), rng.uniform(0, 6.28))
                    continue
            if pt == "rain":
                kind = RAIN
            elif pt == "snow":
                kind = ICE if rng.random() < 0.15 else SNOW
            elif pt == "mixed":
                # Keep the spawned kind consistent with the release point's own temperature.
                if base_above_freezing:
                    # (no sleet when the whole lowest ground is below 0 C: nothing there to melt it)
                    kind = rng.choices([SNOW, ICE, MELT],
                                       weights=[5, 1, 1 if self.world.surface_temp_c > 0.0 else 0])[0]
                else:
                    kind = rng.choices([RAIN, MELT], weights=[3, 1])[0]
            elif pt == "graupel":
                kind = GRAUPEL
            else:
                continue

            if kind in (RAIN, MELT):
                size = rng.uniform(1.5, 3.0)
            elif kind == SNOW:
                size = rng.uniform(2.0, 4.5)
            elif kind == ICE:
                size = rng.uniform(1.0, 2.0)
            else:
                size = rng.uniform(3.0, 5.5)

            vx0 = cloud.vx * 0.5 + rng.uniform(-6, 6)
            vy0 = rng.uniform(5, 25)
            rot0 = rng.uniform(0, 6.28)
            spawn(kind, x, base_y, vx0, vy0, size, rot0)

    def rain_span(self, cloud):
        """(x0, x1): the stretch of ground a raining cloud's precipitation falls on. A supercell rains from
        its forward flank only (downwind of the mesocyclone), never under the wall cloud at `meso_x`."""
        if cloud.type == "supercell":
            fwd = -1.0 if cloud.meso_dx > 0.0 else 1.0
            bw = min(cloud.w, cloud.base_w)
            c = cloud.ax + fwd * FLANK_CENTRE * bw
            return c - FLANK_HALF * bw, c + FLANK_HALF * bw
        rain_w = min(cloud.w, cloud.base_w) * 0.85
        return cloud.ax - rain_w * 0.45, cloud.ax + rain_w * 0.45

    # -- lightning ------------------------------------------------------
    def _update_lightning(self, dt: float, events: list):
        survivors = []
        for lt in self._lightning:
            lt["alpha"] -= dt * 1.8
            if lt["alpha"] > 0:
                survivors.append(lt)
        self._lightning = survivors

        if len(self._lightning) >= 6:
            return
        height_at = self.terrain.height_at
        for cloud in self.clouds:
            if cloud.is_cb and cloud.stage == "precipitating" and cloud.rng.random() < 0.02:
                x = cloud.ax + cloud.rng.uniform(-cloud.w * 0.2, cloud.w * 0.2)
                if cloud.type == "supercell":          # a supercell's lightning comes from its forward flank
                    x = cloud.rng.uniform(*self.rain_span(cloud))
                y = cloud.base_y
                pts = [[round(x, 1), round(y, 1)]]
                steps = cloud.rng.randint(4, 7)
                x_end = x + cloud.rng.uniform(-30, 30)
                y_end = height_at(x_end)
                for i in range(1, steps + 1):
                    frac = i / steps
                    if i == steps:
                        pts.append([round(x_end, 1), round(y_end, 1)])   # ends exactly on the surface
                    else:
                        y2 = y + (y_end - y) * frac
                        x2 = x + (x_end - x) * frac + cloud.rng.uniform(-18, 18)
                        pts.append([round(x2, 1), round(min(y2, height_at(x2) - 1.0), 1)])
                self._lightning.append({"pts": pts, "alpha": 1.0})
                events.append(f"t={self.world.t:.1f} lightning from {cloud.name}")
                break  # at most one new bolt per tick

    # -- arrows -----------------------------------------------------------
    def _build_arrows(self):
        arrows = []
        for cloud in self.clouds:
            cx, cy = cloud.x, cloud.y
            if cloud.stage in ("growing", "mature"):
                mag = cloud.updraft
                arrows.append({"x1": r2(cx), "y1": r2(cy), "x2": r2(cx),
                               "y2": r2(cy - 40 - mag * 3), "kind": "updraft",
                               "mag": r2(mag), "label": f"updraft {mag:.1f} m/s"})
                if cloud.stage == "growing":
                    g = r2(cloud.water + cloud.ice)
                    arrows.append({"x1": r2(cx - 20), "y1": r2(cy + 10),
                                   "x2": r2(cx - 35), "y2": r2(cy - 5), "kind": "growth",
                                   "mag": g, "label": f"growth {g}"})
            elif cloud.stage == "precipitating":
                dd = r2(cloud.updraft * 0.5)
                by = cloud.base_y
                arrows.append({"x1": r2(cx), "y1": r2(by), "x2": r2(cx),
                               "y2": r2(by + 40 + dd * 3), "kind": "downdraft",
                               "mag": dd, "label": f"downdraft {dd:.1f} m/s"})
                fall_mag = {"rain": 6.0, "snow": 1.2, "mixed": 3.0, "graupel": 9.0,
                            "none": 0.0}.get(cloud.precip_type, 0.0)
                if fall_mag > 0:
                    y0 = by + 20
                    arrows.append({"x1": r2(cx), "y1": r2(y0), "x2": r2(cx),
                                   "y2": r2(y0 + fall_mag * 4), "kind": "fall",
                                   "mag": fall_mag, "label": f"fall {fall_mag:.1f} m/s"})

        n_wind = max(6, min(12, int(round(self.world.w / 320.0))))
        for i in range(n_wind):
            x = (i + 0.5) * self.world.w / n_wind
            y = self.world.h * 0.15
            wu, wv = self.wind.sample(x, y)
            mag = r2((wu * wu + wv * wv) ** 0.5)
            arrows.append({"x1": r2(x), "y1": r2(y), "x2": r2(x + wu * 3), "y2": r2(y + wv * 3),
                           "kind": "wind", "mag": mag, "label": f"wind {mag:.1f} m/s"})
        return arrows

    # -- deaths -----------------------------------------------------------
    def _death_record(self, cloud):
        rec = {
            "id": cloud.id, "seed": cloud.seed, "name": cloud.name, "type": cloud.type,
            "born": r2(cloud.born_t), "died": r2(self.world.t),
            "lifetime": r2(cloud.age),
            "peak_water": round(cloud.peak_water, 3),
            "total_precip_mm": round(cloud.total_precip_mm, 2),
            "precip_type": cloud.precip_type,
            "stage_times": dict(cloud.stage_times),
            "puffs": cloud.puffs,
            "tint": list(cloud.tint),
        }
        if cloud.from_type:
            rec["from_type"] = cloud.from_type
        if cloud.merging_into is not None:
            rec["merged_into"] = cloud.merging_into
        return rec

    # -- commands -----------------------------------------------------------
    def handle_command(self, cmd: dict, events: list):
        c = cmd.get("cmd")
        if c == "pause":
            self.paused = True
        elif c == "resume":
            self.paused = False
        elif c == "speed":
            try:
                v = float(cmd.get("value", 1.0))
            except (TypeError, ValueError, OverflowError):
                return
            if not math.isfinite(v):
                return
            self.speed = max(0.25, min(8.0, v))
        elif c == "spawn":
            try:
                x = float(cmd.get("x", self.world.w / 2))
                y = float(cmd.get("y", self.world.h * 0.2))
            except (TypeError, ValueError, OverflowError):
                return
            if not (math.isfinite(x) and math.isfinite(y)):
                return
            ctype = cmd.get("type")
            if not isinstance(ctype, str) or ctype not in TYPE_NAMES:
                ctype = None             # a missing or bad type is ignored: the climate picks one
            # (the player's cloud ignores the clock: it lives its whole life at any hour)
            cloud = self._spawn_cloud_auto(x=x, y=y, cloud_type=ctype, diurnal=False)
            events.append(f"t={self.world.t:.1f} spawned {cloud.name} by command")
        elif c == "tornado":
            self._cmd_tornado(cmd, events)
        elif c == "drains":
            if self.water is not None and self.water.command(cmd):        # (v2.4; ignored outside sandbox mode)
                self.drains_mode = self.water.base_mode
        elif c == "wind":
            try:
                delta = float(cmd.get("delta", 0.0))
            except (TypeError, ValueError, OverflowError):
                return
            if not math.isfinite(delta):
                return
            # (kept inside +-WIND_LIMIT: an absurd delta used to overflow the wind grid and kill the process)
            self.world.prevailing_u = max(-WIND_LIMIT, min(WIND_LIMIT, self.world.prevailing_u + delta))
            events.append(f"t={self.world.t:.1f} wind delta {delta:+.1f} m/s")
        elif c == "temp":
            try:
                delta = float(cmd.get("delta", 0.0))
            except (TypeError, ValueError, OverflowError):
                return
            if not math.isfinite(delta):
                return
            # the same as raising surface_temp_c by delta (kept inside +-TEMP_USER_LIMIT: two absurd deltas
            # used to put `Infinity` on the wire, which is not JSON)
            self.world.user_c = max(-TEMP_USER_LIMIT, min(TEMP_USER_LIMIT, self.world.user_c + delta))
            self._atm = None
            events.append(f"t={self.world.t:.1f} temp delta {delta:+.1f}C "
                          f"(freezing_y={self.world.freezing_y:.0f})")
        elif c == "gas":
            self._cmd_gas(cmd, events)
        elif c == "season":
            self._cmd_calendar(cmd, "month", events)
        elif c == "hour":
            self._cmd_calendar(cmd, "value", events)
        elif c == "reseed":
            try:
                new_seed = int(cmd.get("seed", 0))
            except (TypeError, ValueError, OverflowError):
                new_seed = 0
            self.loaded = False
            self._reset(new_seed, self.world.w, self.world.h)
            events.append(f"t=0.0 world reseeded to {new_seed}")
            self.outbox.append(self.hello_message())
        elif c == "save":
            self._cmd_save(cmd)
        elif c == "view":
            self._cmd_view(cmd)
        elif c == "quit":
            self.running = False
        # unknown commands are ignored per PROTOCOL.md

    # -- tornadoes (v2.3) ---------------------------------------------------------------------
    def tornado_candidates(self) -> list:
        """Mature supercells that have no tornado right now."""
        busy = {t.cloud for t in self.tornadoes}
        return [c for c in self.clouds if supercell_mature(c) and c.merging_into is None and c.id not in busy]

    def _cmd_tornado(self, cmd: dict, events: list):
        """{"cmd":"tornado","cloud":42}: that supercell produces one now (ignored unless it is a mature
        supercell without one); {"cmd":"tornado"}: the strongest such supercell does."""
        cands = self.tornado_candidates()
        if "cloud" in cmd:
            cid = cmd["cloud"]
            if isinstance(cid, bool) or not isinstance(cid, int):
                return
            cands = [c for c in cands if c.id == cid]
        if not cands:
            return
        cloud = max(cands, key=lambda c: (c.rotation, c.wall, -c.id))
        self._start_tornado(cloud, events, forced=True)

    def _start_tornado(self, cloud, events: list, forced=False, peak_ef=None, mature_s=None):
        """A funnel cloud starts to descend from `cloud`'s wall cloud. A forced tornado (the command, the
        showcase) also holds the mesocyclone and the wall cloud up while it lives."""
        tid = self.next_tornado_id
        self.next_tornado_id += 1
        if peak_ef is None:
            peak_ef = draw_peak_ef(self.tor_rng)
        seed = (self.seed * 1000003 + tid * 7919 + cloud.seed) & 0xFFFFFFFF
        attach = cloud.ay + 0.75 * cloud.wall_drop * min(1.0, cloud.wall)
        t = Tornado.create(tid, cloud.id, cloud.name, seed, peak_ef, meso_x(cloud), attach, self.terrain,
                           self.world.h, mature_s)
        self.tornadoes.append(t)
        self.tornadoes_total += 1
        if forced:
            cloud.sc_force = 1.0
        events.append(f"t={self.world.t:.1f} {cloud.name}: funnel cloud descends from the wall cloud "
                      f"(tornado {tid})")
        return t

    def _update_tornadoes(self, dt: float, events: list):
        """Genesis (a hazard on mature supercells with a strong mesocyclone and a lowered wall cloud) and
        the life of every tornado; a tornado that has roped out is removed."""
        world = self.world
        if self.auto_tornado and self.clouds:
            busy = {t.cloud for t in self.tornadoes}
            for c in self.clouds:
                if (c.type != "supercell" or c.id in busy or not supercell_mature(c) or c.merging_into is not None
                        or c.rotation < TORNADO_MIN_ROTATION or c.wall < TORNADO_MIN_WALL
                        or world.t < self.tor_cool.get(c.id, 0.0)):
                    continue
                lf = c.life_frac
                if not (TORNADO_WINDOW[0] <= lf <= TORNADO_WINDOW[1]):
                    continue
                lam = (TORNADO_HAZARD_PER_S * (0.4 + 1.6 * smooth01(c.rotation, 0.7, 1.0))
                       * (0.5 + 0.5 * c.hook))
                # (Phase O: the hazard is per second of the storm's own clock. One that ages faster in the evening crosses the
                # window in less time, so its hazard is scaled up by the same factor: it keeps its chance of a tornado, sooner)
                lam *= day_age_rate(c, self.world, self.atmo)
                if self.tor_rng.random() < 1.0 - math.exp(-lam * dt):
                    self._start_tornado(c, events)
        if not self.tornadoes:
            for c in self.clouds:
                c.tornado_on = 0.0
            return
        by_id = {c.id: c for c in self.clouds}
        for c in self.clouds:
            c.tornado_on = 0.0
        for t in self.tornadoes:
            if t.cloud in by_id:
                by_id[t.cloud].tornado_on = 1.0
        keep = []
        for t in self.tornadoes:
            c = by_id.get(t.cloud)
            ok = c is not None and c.stage != "dissipating" and c.merging_into is None
            if c is not None:
                mx = meso_x(c)
                attach = c.ay + 0.75 * c.wall_drop * min(1.0, c.wall)
            else:
                mx = attach = None
            if t.update(dt, mx, attach, ok, self.terrain, world.t, events):
                keep.append(t)
                if t.stage == "roping" and c is not None:
                    c.sc_force = 0.0            # the wall cloud may lift now
            else:
                self.tor_cool[t.cloud] = world.t + TORNADO_COOLDOWN_S
                if c is not None:
                    c.sc_force = 0.0
        self.tornadoes = keep

    def _tornado_effects(self, dt: float):
        """Damage to objects, the debris thrown up, and the debris already in the air."""
        grounded = [t for t in self.tornadoes if t.ground_factor > 0.0]
        hit = self.objects.apply_tornadoes(dt, grounded)
        if grounded or self.particles.debris:
            rng = self.tor_rng
            spawn = self.particles.spawn_debris
            for t in grounded:
                n = t.debris * DEBRIS_PER_S * dt
                k = int(n)
                if rng.random() < n - k:
                    k += 1
                for _ in range(k):
                    size = rng.uniform(1.5, 4.5) if rng.random() < 0.85 else rng.uniform(4.5, 7.0)
                    spawn(t.x + rng.uniform(-0.6, 0.6) * t.width, t.ground_y - rng.uniform(0.0, 12.0),
                          rng.uniform(-40.0, 40.0), -rng.uniform(60.0, 220.0), size, rng.uniform(0.0, 6.28),
                          rng.uniform(7.0, 13.0))
            for ox, oy, delta, w, h in hit:
                n = delta * DEBRIS_PER_DAMAGE * (w * h) / 2400.0
                k = int(n)
                if rng.random() < n - k:
                    k += 1
                for _ in range(min(k, 6)):
                    spawn(ox + rng.uniform(-0.5, 0.5) * w, oy + rng.uniform(-0.5, 0.5) * h,
                          rng.uniform(-60.0, 60.0), -rng.uniform(40.0, 200.0), rng.uniform(2.0, 6.5),
                          rng.uniform(0.0, 6.28), rng.uniform(6.0, 11.0))
            self.particles.update_debris(dt, self.terrain, grounded)

    def _melt_on_warm_ground(self, rain_hits: dict, snow_hits: dict):
        """Region presets only. A preset squeezes its real relief into the height of the sky, so a real 3 km
        tropical summit (the Amazon's Pico da Neblina) stands above the *drawn* freezing level although the air
        up there is really several degrees above zero. Snow that lands where the real temperature (lowest-ground
        temperature minus 6.5 C per real km of height above it) is above +1 C wets the ground instead of
        whitening it. A really cold summit (Rainier, Tibet) is below zero by the same sum and keeps its snow."""
        mpp = profile_m_per_px(self.preset["max_elev_m"] - self.preset["min_elev_m"], self.world.h)
        t0 = self.world.surface_temp_c
        gy = self.world.ground_y
        surf = self.ground.surf
        n1 = len(surf) - 1
        for k in list(snow_hits):
            i = 0 if k < 0 else (n1 if k > n1 else k)
            if t0 - REAL_LAPSE_C_PER_M * mpp * (gy - surf[i]) > 1.0:
                rain_hits[k] = rain_hits.get(k, 0) + snow_hits.pop(k)

    def _weather_spans(self):
        """(rain spans, dark spans) over the ground for the cars' lights and wipers: [(x0, x1, 0..1)]."""
        rain = []
        dark = []
        for c in self.clouds:
            if c.merging_into is not None:
                continue
            if c.stage == "precipitating" and c.precip_type != "none" and c.precip_rate > 0.02:
                x0, x1 = self.rain_span(c)
                rain.append((x0, x1, min(1.0, 0.3 + 4.0 * c.precip_rate)))
            if c.darkness > 0.45 and c.form > 0.7:
                half = c.w * 0.5
                dark.append((c.x - half, c.x + half, min(1.0, (c.darkness - 0.3) * 2.5)))
        return rain, dark

    def _cmd_gas(self, cmd: dict, events: list):
        """{"cmd":"gas","name":"humidity|co2|aerosol","delta":0.05} or "value": 560 (clamped)."""
        name = cmd.get("name")
        got = self.atmo.set_gas(name, cmd.get("delta") if "value" not in cmd else None,
                                cmd.get("value"))
        if got is None:
            return
        self._atm = None
        events.append(f"t={self.world.t:.1f} {name} is now {got:.2f}" if name != "co2"
                      else f"t={self.world.t:.1f} co2 is now {got:.0f} ppm")

    def _cmd_calendar(self, cmd: dict, key: str, events: list):
        """{"cmd":"season","month":7.5} / {"cmd":"hour","value":14.0}: jump the calendar, the temperature glides."""
        v = cmd.get(key)
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            return
        try:                        # (an integer too large for a float is not a month or an hour either)
            v = float(v)
        except OverflowError:
            return
        if not math.isfinite(v):
            return
        if key == "month":
            m = (float(v) - 1.0) % 12.0 + 1.0
            self.world.jump_clock(month=m)
            events.append(f"t={self.world.t:.1f} the calendar jumps to month {m:.1f} ({self.world.season})")
        else:
            h = float(v) % 24.0
            self.world.jump_clock(hour=h)
            events.append(f"t={self.world.t:.1f} the clock jumps to {h:.1f} h")
        self._atm = None

    def _cmd_save(self, cmd: dict):
        path = cmd.get("path")
        if not isinstance(path, str) or not path.strip():
            ok, err = False, "save needs a non-empty 'path'"
            path = path if isinstance(path, str) else ""
        else:
            try:
                state = self.to_state()
                ok, err = save_state(state, path)
            except Exception as exc:  # noqa: BLE001
                ok, err = False, f"{type(exc).__name__}: {exc}"
        self.outbox.append({"type": "saved", "ok": ok, "path": path, "t": r2(self.world.t),
                            "frame": self.frame, "error": err})

    def _cmd_view(self, cmd: dict):
        if "x0" not in cmd and "x1" not in cmd:
            self.view = None
            return
        try:
            x0 = float(cmd.get("x0"))
            x1 = float(cmd.get("x1"))
        except (TypeError, ValueError, OverflowError):
            return
        if not (math.isfinite(x0) and math.isfinite(x1)):
            return
        if x1 < x0:
            x0, x1 = x1, x0
        self.view = (x0, x1)

    def drain_outbox(self) -> list:
        msgs = self.outbox
        self.outbox = []
        return msgs

    # -- stepping -----------------------------------------------------------
    def step(self, dt_real: float, extra_events=None) -> dict:
        events: list = list(extra_events) if extra_events else []
        deaths: list = []

        if not self.paused:
            dt = dt_real * self.speed
            world = self.world
            world.t += dt
            self._refresh_atmosphere()
            self.wind = self._build_wind()
            if self.frame % 6 == 0:
                self.streamlines = build_streamlines(self.wind, world, self.terrain, self.world_rng,
                                                     self.n_streamlines)

            self._step_merges(dt)
            survivors = []
            for cloud in self.clouds:
                prev_mm = cloud.total_precip_mm
                # (sandbox clouds rain until life_frac 0.8; the showcase stage keeps its own rain programme)
                alive = update_cloud(cloud, dt, world, self.wind, self.terrain, world.t, events, self.atmo,
                                     self.mode == "sandbox")
                mm_delta = cloud.total_precip_mm - prev_mm
                if mm_delta > 0:
                    self.world_precip_mm += mm_delta
                if alive:
                    self._spawn_precip_particles(cloud, dt)
                    survivors.append(cloud)
                else:
                    self.clouds_dead += 1
                    deaths.append(self._death_record(cloud))
                    events.append(f"t={world.t:.1f} {cloud.name} "
                                  + ("merged away" if cloud.merging_into is not None else "dissipated"))
            self.clouds = survivors
            self._lock_absorbed()
            self._update_tornadoes(dt, events)

            rain_hits, snow_hits, ice_alive = self.particles.update(
                dt, world, self.terrain, self.wind, self.world_rng)
            fz = world.freezing_y
            if snow_hits and self.preset:
                self._melt_on_warm_ground(rain_hits, snow_hits)
            self._tornado_effects(dt)
            self.objects.update(dt, self.wind, rain_hits, snow_hits, fz)
            self.ground.update(dt, rain_hits, snow_hits, fz)
            if self.water is not None:
                self.water.update(dt, rain_hits)
            if self.roads:
                rain_spans, dark_spans = self._weather_spans()
                self.cars.update(dt, self.terrain, world.time_of_day, self.ground, rain_spans, dark_spans,
                                 self.tornadoes, self.water)
            self.ground_rain_hits += sum(rain_hits.values())
            self.ground_snow_hits += sum(snow_hits.values())
            hits = self.ground_rain_hits + self.ground_snow_hits
            if hits:
                self.world_snow_mm = self.world_precip_mm * self.ground_snow_hits / hits
                self.world_rain_mm = self.world_precip_mm - self.world_snow_mm

            self._update_lightning(dt, events)
            self._maybe_spawn_cloud(dt, events)
            if self.merging:
                self._detect_merges(dt, events)
        else:
            ice_alive = self.particles.ice_count()

        self.frame += 1
        return self._build_frame(events, deaths, ice_alive)

    # -- serialisation -----------------------------------------------------------
    def hello_message(self) -> dict:
        t = self.terrain
        msg = {
            "type": "hello", "version": 2, "mode": self.mode, "seed": self.seed, "loaded": self.loaded,
            "t": r2(self.world.t),
            "world": {"w": self.world.w, "h": self.world.h, "ground_y": self.world.ground_y,
                      "terrain_kind": t.kind, "terrain": {"step": t.step, "y": list(t.y)}},
            "settlements": [dict(s) for s in self.settlements],
            "objects": [dict(o) for o in self.objects.objs],
        }
        if self.water is not None:        # v2.4: the water model's constants and every settlement's drain
            msg["settlements"] = self.water.decorate(self.settlements)
            msg["water"] = self.water.hello_water()
            msg["drains"] = self.water.hello_drains()
            msg["flow"] = self.water.hello_flow()      # v2.4.1: no virtual curb; the film runs downhill (frame.flow)
        if self.roads:
            msg["roads"] = [dict(r) for r in self.roads]
        if self.preset:
            msg["preset"] = region_presets.hello_block(self.preset, self.world.w, self.world.h,
                                                       frozen=self._opening_frozen())
        msg["climate"] = self.climate_hello()
        return msg

    def climate_hello(self) -> dict:
        """`hello.climate` (protocol v2.3)."""
        c = self.world.climate
        return {"year_days": self.world.year_days, "day_length_s": int(DAY_LENGTH_S),
                "hemisphere": c.hemisphere, "lat": round(c.lat, 2),
                "monthly_temp_c": [round(v, 2) for v in c.monthly_list()],
                "diurnal_range_c": round(c.diurnal_range_c, 2), "base_c": round(c.base_c, 2),
                "start_month": round(1.0 + 12.0 * (self.world.hold[0] if self.world.hold else self.world.year0), 2),
                "source": c.source}

    @staticmethod
    def _cloud_to_protocol(c):
        d = {
            "id": c.id, "seed": c.seed, "name": c.name, "type": c.type,
            "x": r2(c.x), "y": r2(c.y), "w": r2(c.w), "h": r2(c.h),
            "stage": c.stage, "age": r2(c.age), "lifetime": r2(c.lifetime),
            "life_frac": r2(c.life_frac),
            "form": round(c.form, 3), "shear": round(c.shear, 1), "anvil": round(c.anvil, 3),
            "water": round(c.water, 3), "ice": round(c.ice, 3), "updraft": r2(c.updraft),
            "precip_type": c.precip_type, "precip_rate": r2(c.precip_rate),
            "total_precip_mm": round(c.total_precip_mm, 2),
            "puffs": c.puffs,
            "tint": list(c.tint), "opacity": c.opacity, "darkness": c.darkness,
            "stage_times": dict(c.stage_times),
            "fair": c.fair,
        }
        if c.from_type:
            d["from_type"] = c.from_type
            d["morph"] = round(c.morph, 3)
        if c.parts:
            d["parts"] = [dict(p) for p in c.parts]
            d["merge"] = round(c.merge, 3)
            d["puff_part"] = c.puff_part
        if c.merging_into is not None:
            d["merging_into"] = c.merging_into
        if c.type == "supercell":
            d["rotation"] = round(c.rotation, 3)
            d["wall"] = round(c.wall, 3)
            d["overshoot"] = round(c.overshoot, 3)
            d["meso_x"] = round(meso_x(c), 1)
            d["hook"] = round(c.hook, 3)
        return d

    def _build_frame(self, events, deaths, ice_alive) -> dict:
        stats = {
            "clouds_alive": len(self.clouds),
            "clouds_born": self.clouds_born,
            "clouds_dead": self.clouds_dead,
            "rain_mm": round(self.world_rain_mm, 2),
            "snow_mm": round(self.world_snow_mm, 2),
            "particles": len(self.particles),
            "ice_crystals": ice_alive,
            "wind_mean": r2(mean_wind_magnitude(self.wind)),
            "tornadoes": len(self.tornadoes),
            "tornadoes_total": self.tornadoes_total,
            "cars": len(self.cars.cars),
        }
        view = self.view
        frame = {
            "type": "frame", "frame": self.frame, "t": r2(self.world.t),
            "speed": r2(self.speed), "paused": self.paused,
            "world": self.world.to_protocol(),
            "wind": self.wind.to_protocol(),
            "streamlines": self.streamlines,
            "clouds": [self._cloud_to_protocol(c) for c in self.clouds],
            "particles": (self.particles.protocol_list(view[0], view[1]) if view
                          else self.particles.protocol_list()),
            "arrows": self._build_arrows(),
            "objects": self.objects.pop_rows(),
            "lightning": [{"pts": lt["pts"], "alpha": round(max(0.0, lt["alpha"]), 2)}
                          for lt in self._lightning],
            "atmosphere": self.atmo.to_protocol(self._get_atm()),
            "stats": stats,
            "events": events,
            "deaths": deaths,
        }
        if self.tornadoes:
            frame["tornadoes"] = [t.to_protocol() for t in self.tornadoes]
        if self.roads:
            frame["cars"] = self.cars.to_protocol()
        dmg = self.objects.pop_damage()
        if dmg:
            frame["damage"] = dmg
        ground = self.ground.maybe_emit(self.frame)
        if ground is not None:
            frame["ground"] = ground
        if self.water is not None:
            frame.update(self.water.emit(self.frame))      # v2.4 `water`, `flood`, `drains` (and v2.4.1 `flow`) where their send rules say
        return frame

    # -- save / load ------------------------------------------------------------------
    def to_state(self) -> dict:
        state = {
            "seed": self.seed, "w": self.world.w, "h": self.world.h, "fps": self.fps,
            "terrain_kind": self.terrain_kind, "settlements_mode": self.settlements_mode,
            "preset": self.preset["id"] if self.preset else None, "mode": self.mode,
            "frame": self.frame, "speed": self.speed, "paused": self.paused,
            "rng": rng_to_state(self.world_rng),
            "world": self.world.to_state(),
            "terrain": self.terrain.to_state(),
            "settlements": [dict(s) for s in self.settlements],
            "objects": self.objects.to_state(),
            "ground": self.ground.to_state(),
            "wind": self.wind.to_state(),
            "streamlines": self.streamlines,
            "clouds": [c.to_state() for c in self.clouds],
            "next_cloud_id": self.next_cloud_id,
            "clouds_born": self.clouds_born, "clouds_dead": self.clouds_dead,
            "world_rain_mm": self.world_rain_mm, "world_snow_mm": self.world_snow_mm,
            "world_precip_mm": self.world_precip_mm,
            "ground_rain_hits": self.ground_rain_hits, "ground_snow_hits": self.ground_snow_hits,
            "lightning": [{"pts": lt["pts"], "alpha": lt["alpha"]} for lt in self._lightning],
            "spawn_timer": self._spawn_timer, "next_spawn_delay": self._next_spawn_delay,
            # v2.3
            "atmo": self.atmo.to_state(), "merge_acc": self._merge_acc,
            "start_month": self.start_month, "start_hour": self.start_hour, "year_days": self.year_days,
            "signatures": sorted([list(s) if isinstance(s, tuple) else s for s in self.signatures],
                                 key=repr),
            "particles": self.particles.to_state(),
            # v2.3 supercells: tornadoes, roads and cars (damage lives in the object field's state)
            "tornadoes": [t.to_state() for t in self.tornadoes], "next_tornado_id": self.next_tornado_id,
            "tornadoes_total": self.tornadoes_total, "tor_rng": rng_to_state(self.tor_rng),
            "tor_cool": {str(k): v for k, v in self.tor_cool.items()},
            "cars": self.cars.to_state(),
        }
        if self.water is not None:
            state["water"] = self.water.to_state()               # v2.4
        return state

    @classmethod
    def from_state(cls, s: dict) -> "Simulation":
        sim = cls.__new__(cls)
        sim.fps = int(s["fps"])
        sim.paused = bool(s["paused"])
        sim.speed = float(s["speed"])
        sim.frame = int(s["frame"])
        sim.running = True
        sim.loaded = True
        sim.outbox = []
        sim.view = None
        sim.seed = int(s["seed"])
        sim.terrain_kind = str(s["terrain_kind"])
        sim.settlements_mode = str(s["settlements_mode"])
        # v2.2 key; saves from v2 / v2.1 have none and load as a plain sandbox world
        sim.preset = region_presets.get_preset(s["preset"]) if s.get("preset") else None
        sim.world_rng = rng_from_state(s["rng"])
        sim.world = World.from_state(s["world"], sim.seed)
        sim.terrain = Terrain.from_state(s["terrain"])
        sim.settlements = [dict(x) for x in s["settlements"]]
        sim.objects = ObjectField.from_state(s["objects"], sim.terrain)
        sim.ground = GroundCover.from_state(s["ground"], sim.terrain, sim.world.w)
        sim.ground.force = True       # a fresh viewer has no ground cover: first frame resends it
        sim.wind_model = WindModel(sim.world, sim.terrain)
        sim.wind = WindField.from_state(s["wind"])
        sim.streamlines = s["streamlines"]
        sim.n_streamlines = max(STREAMLINES_PER_1280, int(round(STREAMLINES_PER_1280 * sim.world.w / 1280.0)))
        sim.particles = ParticleSystem.from_state(s["particles"])
        sim.clouds = [Cloud.from_state(c) for c in s["clouds"]]
        sim.next_cloud_id = int(s["next_cloud_id"])
        sim.clouds_born = int(s["clouds_born"])
        sim.clouds_dead = int(s["clouds_dead"])
        sim.world_rain_mm = float(s["world_rain_mm"])
        sim.world_snow_mm = float(s["world_snow_mm"])
        sim.world_precip_mm = float(s["world_precip_mm"])
        sim.ground_rain_hits = int(s["ground_rain_hits"])
        sim.ground_snow_hits = int(s["ground_snow_hits"])
        sim._lightning = [{"pts": lt["pts"], "alpha": float(lt["alpha"])} for lt in s["lightning"]]
        sim._spawn_timer = float(s["spawn_timer"])
        sim._next_spawn_delay = float(s["next_spawn_delay"])
        sim.signatures = {_freeze(x) for x in s["signatures"]}
        # v2.3 keys; a v2.2 file has none: default gases, the default calendar, no merges in flight
        sim.atmo = Atmosphere.from_state(s["atmo"]) if "atmo" in s else Atmosphere(
            cls._seeded_humidity(sim.seed), 420.0, 0.3)
        sim._merge_acc = float(s.get("merge_acc", 0.0))
        if sim.preset:        # (the desert preset's dry air is a property of the preset, not of the save)
            sim.atmo.arid = sim.preset["biome"]["id"] == "desert"
        sim.start_month = s.get("start_month")
        sim.start_hour = float(s.get("start_hour", DEFAULT_HOUR))
        sim.year_days = int(s.get("year_days", DEFAULT_YEAR_DAYS))
        sim.tornadoes = [Tornado.from_state(t) for t in s.get("tornadoes", [])]
        sim.next_tornado_id = int(s.get("next_tornado_id", 1))
        sim.tornadoes_total = int(s.get("tornadoes_total", 0))
        if "tor_rng" in s:
            sim.tor_rng = rng_from_state(s["tor_rng"])
        else:
            sim.tor_rng = random.Random((int(sim.seed) * 7919 + 0x70A4ED0) & 0xFFFFFFFF)
        sim.tor_cool = {int(k): float(v) for k, v in s.get("tor_cool", {}).items()}
        # a v2.2 file has no roads and no cars
        if "cars" in s:
            sim.cars = CarSystem.from_state(s["cars"])
        else:
            sim.cars = CarSystem([], sim.world.w, sim.seed)
        sim.roads = sim.cars.roads
        # v2.4: a state without a `water` block (v2.3 and older) loads with no standing water, mode `normal` and the
        # drain sizes a new world of that seed would get; a sandbox only (the other modes carry no water)
        sim.water = (WaterField.from_state(sim.terrain, sim.settlements, sim.objects.objs, sim.seed, s.get("water"))
                     if sim.mode == "sandbox" else None)
        sim.drains_mode = sim.water.base_mode if sim.water is not None else "normal"
        sim._mix0 = sim._base_mix()
        sim._atm = None
        return sim
