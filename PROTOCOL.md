# Precipitation — Python ⇄ Java protocol (v2)

**Python** (`sim/`) owns the whole simulation, including saving and loading its own state.
**Java** (`viewer/`) owns every pixel, all input, the menus, the save *slots* and the settings.
They talk over the Python process's stdin/stdout with **one JSON object per line** (UTF-8, `\n`).
Python must never print anything else to stdout (debug → stderr).

v2 replaces v1 outright (bigger world, terrain heightmap, settlements, static objects moved into
`hello`, richer clouds, save/load, view culling). The viewer only has to speak v2.

## Launch
Java starts:

```
python -u sim/precip_sim.py --seed <int> --width 3840 --height 1080 --fps 30
       [--terrain flat|rolling|hilly|mountainous] [--settlements none|sparse|normal|dense]
       [--load <state file>]
```

* `--seed` optional (default time-based). `--width/--height` default **3840 × 1080**; supported
  range 1280–9600 wide, 720–1440 tall. `--terrain` default `rolling`, `--settlements` default `normal`.
* `--load FILE` restores a state previously written by the `save` command. Seed, size, terrain
  and settlement arguments are ignored; the first line is still a `hello` (with `"loaded":true`)
  and the stream continues from the saved `t`/`frame`.
* `--headless-frames N` runs N frames with no stdin and exits (tests); `--dump-every K` prints
  only every Kth frame.

## Coordinates
2D world in pixels, origin top-left, **x → right, y → down**. `world.w × world.h` equals the
requested size. The ground surface is the **terrain heightmap** (below). `world.ground_y` is the
*lowest* point of that surface (largest y): everything below `ground_y` is always ground. Time `t`
is simulated seconds; 1 sim second = 1 real second at speed 1.0.

## Python → Java messages

### `hello` (first line; sent again after `reseed`)
```json
{
 "type":"hello","version":2,"seed":1234,"loaded":false,"t":0.0,
 "world":{"w":3840,"h":1080,"ground_y":1000,"terrain_kind":"rolling",
          "terrain":{"step":8,"y":[1000.0,998.5, ...]}},
 "settlements":[{"id":1,"kind":"village","name":"Eldermoor","x0":820.0,"x1":1310.0,"population":340}],
 "objects":[{"id":17,"kind":"house","variant":"cottage","x":900.0,"y":962.0,"w":64.0,"h":52.0,
             "seed":90123,"wall":[214,196,170],"roof":[142,62,48],"floors":1,"settlement":1,
             "layer":0,"sway":0.0,"snow":0.0,"wet":0.0}]
}
```

* `terrain.y[i]` is the ground-surface y at `x = i*step`, `i = 0 .. ceil(w/step)` (so
  `len = ceil(w/step)+1`); linear interpolation in between. It is fixed for the life of the world.
* `settlements.kind` ∈ `hamlet | village | town | city`. `x0..x1` is the built-up span.
* `objects` is the **complete** object list with stable integer `id`s. Every object has
  `id, kind, x, y, w, h, sway, snow, wet`; `x,y` = base centre **on the terrain surface**.
  Optional keys (absent = viewer default): `variant` (string), `seed` (int — the viewer derives
  per-object detail such as window layout from it, so it must be stable), `wall`/`roof`
  (`[r,g,b]`), `floors` (int), `settlement` (id), `layer` (0 = foreground row, 1 = a row behind it).
* `objects.kind` ∈
  `mountain | hill | tree | pond | puddle | snowpack` (scenery) and
  `house | barn | church | windmill | shop | apartment | office | skyscraper | factory | water_tower | streetlamp` (buildings).
  `windsock` was a scenery kind up to v2.3 and is **gone** (Phase L): a v2.4 sim never creates one. State files
  and replay files written before that still contain `windsock` objects (and may send delta rows for them), so
  **a viewer must ignore an object whose `kind` it does not know**: not draw it as something else, not fail,
  and keep reading the rest of the list.
  Variants: `house` → `cottage | cabin | farmhouse | townhouse | bungalow | manor`;
  `tree` → `pine | oak | birch | bush`; other kinds may carry any variant string, unknown
  variants draw as the kind's default.
* `mountain`/`hill` are background scenery standing on the terrain (`w`,`h` = their size); the
  walkable ground itself is always the heightmap.

### `frame` (every tick, ~fps per second)
```json
{
 "type":"frame","frame":812,"t":27.07,"speed":1.0,"paused":false,
 "world":{"w":3840,"h":1080,"ground_y":1000,"freezing_y":610,"surface_temp_c":4.5,
          "time_of_day":0.42},
 "wind":{"cols":48,"rows":14,"cell":80,"u":[...],"v":[...]},
 "streamlines":[{"pts":[[x,y],[x,y],...],"speed":6.2,"phase":0.3}],
 "clouds":[{
    "id":42,"seed":3141592,"name":"Cb-0042 \"Tall Ember\"","type":"cumulonimbus",
    "x":500.0,"y":180.0,"w":260.0,"h":190.0,
    "stage":"precipitating","age":61.3,"lifetime":140.0,"life_frac":0.44,
    "form":0.97,"shear":14.0,"anvil":0.6,
    "water":0.71,"ice":0.35,"updraft":4.2,
    "precip_type":"mixed","precip_rate":0.8,"total_precip_mm":3.1,
    "puffs":[[dx,dy,r,shade],...],
    "tint":[232,236,242],"opacity":0.92,"darkness":0.55,
    "stage_times":{"forming":0.0,"growing":8.1,"mature":30.2,"precipitating":44.0}
 }],
 "particles":[[kind,x,y,vx,vy,size,rot], ...],
 "arrows":[{"x1":0,"y1":0,"x2":0,"y2":0,"kind":"updraft","mag":4.2,"label":"updraft 4.2 m/s"}],
 "objects":[[17,0.0,0.12,0.3,64.0,52.0], ...],
 "ground":{"step":32,"snow":[0.0,0.4,...],"wet":[0.1,0.0,...]},
 "lightning":[{"pts":[[x,y],...],"alpha":0.9}],
 "stats":{"clouds_alive":5,"clouds_born":17,"clouds_dead":12,"rain_mm":12.4,"snow_mm":3.3,
          "particles":2210,"ice_crystals":340,"wind_mean":5.1},
 "events":["t=27.1 Cb-0042 began precipitating (mixed)"],
 "deaths":[{"id":39,"seed":777,"name":"Cu-0039 \"Soft Ash\"","type":"cumulus","born":2.1,"died":27.0,
            "lifetime":24.9,"peak_water":0.63,"total_precip_mm":0.8,"precip_type":"rain",
            "stage_times":{"forming":2.1,"growing":6.0,"mature":14.0,"precipitating":18.2,"dissipating":23.5},
            "puffs":[[dx,dy,r,shade],...],"tint":[r,g,b]}]
}
```

Field notes:
* `wind.u`/`wind.v` — flattened **row-major** grids (`index = row*cols + col`) in m/s; cell (c,r)
  is centred at `((c+0.5)*cell, (r+0.5)*cell)`. Positive u = to the right, positive v = downward.
  `cell` may be larger than in v1 for big worlds; the viewer reads it from the message.
* `stage` ∈ `forming | growing | mature | precipitating | dissipating`. A cloud leaves the list
  when it dies and appears once in `deaths` on that frame (Java keeps the history).
* `stage_times` — sim time at which each stage *started* (only stages reached so far).
* `type` ∈ `cumulus | cumulonimbus | nimbostratus | stratocumulus | altostratus | altocumulus | stratus | cirrus`.
* `seed` — the cloud's own RNG seed, stable for its whole life. The viewer seeds its procedural
  noise from it, so each cloud's fine detail is unique and does not flicker between frames.
* `form` — 0..1 condensation: 0 = invisible vapour, rising smoothly (no jumps) as the cloud
  gathers out of thin haze into a solid body, and falling smoothly back to 0 as it evaporates.
  A cloud is born with `form` ≈ 0 and must reach ≈ 0 again before it is removed.
* `shear` — px the cloud top leans downwind relative to its base (negative = leftward).
* `anvil` — 0..1 anvil spread at the top (cumulonimbus; 0 for other types).
* `puffs` — the cloud's unique skeleton: circles relative to (x,y), radius r, `shade` 0..1
  (0 = lit top, 1 = shadowed base). They are a *smooth* function of time: individual puffs swell
  up and shrink, they never pop in or out at full size, and the list order/length is stable
  except for appending new puffs with r≈0 or dropping puffs whose r has already reached ≈0.
* `fair` — (v2.1, optional, default `false`) `true` while the cloud is an ordinary fair-weather
  cloud that carries no rain. Most `cumulus`, `stratocumulus`, `stratus` and `altostratus` are born
  fair. A fair cloud either **develops** (thickens and darkens; `fair` flips to `false` for good and
  it later enters `precipitating` as usual) or never does, in which case its stages run
  `forming → growing → mature → dissipating` and `precipitating` never appears in `stage_times`.
  `cumulonimbus` and `nimbostratus` are never fair; `cirrus`/`altocumulus` are always fair.
* `from_type` / `morph` — (v2.1, optional) a `cumulus` may tower into a `cumulonimbus` once in its
  life. From the frame that starts, `type` is `"cumulonimbus"`, `from_type` is `"cumulus"` and
  `morph` rises smoothly 0 → 1 (then stays 1). `id` and `seed` never change; `name` keeps its
  quoted part and swaps the type prefix (`Cu-0042 "Soft Ash"` → `Cb-0042 "Soft Ash"`). Nothing
  jumps on that frame: at `morph` = 0 the puffs, bounds, tint, opacity and darkness are exactly the
  cumulus's; the tower and anvil puffs are *appended* with r ≈ 0 and swell as `morph` rises, and
  `w`/`h`/`tint` glide to the cumulonimbus's. The stage never goes backwards during a morph.
  Absent `from_type` = the cloud has always been its `type` (`morph` absent or 0). `deaths` entries
  carry `from_type` too when set.
* `precip_type` ∈ `none | rain | snow | mixed | graupel`.
* `particles` — compact arrays, **7 numbers each**: `kind` int, x, y, vx, vy (px/s), size (px),
  rot (radians). Kind: `0` cloud droplet, `1` raindrop, `2` snowflake, `3` ice crystal,
  `4` graupel/hail, `5` splash, `6` melting flake (sleet). Numbers are rounded to 1 decimal.
  Particles land on the terrain surface, not on a flat line. If a `view` command is active only
  particles inside the view (plus margin) are listed; `stats.particles` is always the full count.
* `arrows.kind` ∈ `updraft | downdraft | wind | fall | growth`.
* `objects` (frame) — **only dynamic state**, and only for objects whose state changed since the
  last frame that listed them: `[id, sway, snow, wet, w, h]`. `sway` −1..1 (wind lean), `snow`
  0..1 cover, `wet` 0..1 darkness; `puddle`/`snowpack` grow (`w`,`h`). The viewer keeps the last
  known state per id, starting from `hello.objects`. (v2.4: when `hello.water` is present the `puddle`
  objects are **legacy**: the standing water is in `frame.water`, and the viewer does not draw a `puddle`.)
* `ground` — optional, present only on frames where it changed (at most a few times a second):
  snow cover and wetness 0..1 of the terrain surface sampled every `step` px. The viewer keeps
  the last one. Snow lingers where the surface is above the freezing level.
* `lightning` — short-lived bolts from cumulonimbus, ending on the terrain; `alpha` fades to 0.
* `events` — human readable log lines produced this frame (may be empty).
* Unknown extra keys must be ignored by Java.

### `saved` (reply to the `save` command)
```json
{"type":"saved","ok":true,"path":"C:/.../saves/a1b2/state.sav","t":812.4,"frame":24372,"error":null}
```
Exactly one per `save` command, emitted between frames. On failure `ok` is false and `error`
is a short message; the previous file at `path` (if any) is left intact.

## Java → Python commands (stdin, one JSON per line)
| command | meaning |
|---|---|
| `{"cmd":"pause"}` / `{"cmd":"resume"}` | stop/continue time (frames still sent while paused) |
| `{"cmd":"speed","value":2.0}` | time multiplier, clamp 0.25–8 |
| `{"cmd":"spawn","x":400,"y":200}` | seed a new unique cloud at the point |
| `{"cmd":"wind","delta":1.5}` | change prevailing wind speed (m/s, may go negative = leftward) |
| `{"cmd":"temp","delta":-2}` | change surface temperature °C (moves the freezing level) |
| `{"cmd":"reseed","seed":999}` | restart the world with a new seed (same size/terrain kind); new `hello` |
| `{"cmd":"save","path":"<absolute file>"}` | write the complete sim state to the file, atomically (temp file + replace), then send `saved` |
| `{"cmd":"view","x0":0,"x1":1920}` | only list particles with `x0-200 ≤ x ≤ x1+200` in frames; `{"cmd":"view"}` with no range lists everything again |
| `{"cmd":"quit"}` | exit cleanly |

Unknown commands are ignored. Python must read stdin on a background thread and must exit
when stdin closes (Java died).

## Save files
The state file is opaque to Java and owned by Python: versioned, self-contained, and complete
enough that *run N frames → save → load → run M frames* produces the same frames as an
uninterrupted run of N+M frames (same clouds, particles, RNG streams, object state, counters).
Loading a file from an incompatible version must fail with a clear stderr message and a non-zero
exit instead of a corrupted world. State files written before v2.1 (no `fair`/`morph` cloud state)
must still load: their clouds keep the old behaviour (not fair, never morphing).

## Uniqueness rule
Every cloud gets its own RNG seed. Its type, size, puff skeleton, tint, lifetime, water/ice
capacity, drift and name are derived from it, and Python keeps a set of shape signatures so
no two clouds in a run are the same (the set survives save/load).

---

# v2.2 additions (additive; `version` stays 2)

Three game modes and region presets. Everything here is optional on the wire: a v2.1 viewer that
ignores unknown keys still plays a sandbox world, and v2.1 save files still load (as
`mode:"sandbox"`, no preset).

## Launch (v2.2)
```
python -u sim/precip_sim.py ... [--preset washington|philippines|tibet|russia|united_kingdom|central_europe]
                                [--mode sandbox|showcase|hurricane] [--cloud-type <type>]
```
* `--mode` default `sandbox` (the world described above).
* `--preset ID` (sandbox only) builds the terrain from a **real elevation cross-section** of that
  region and starts from its **real climate** (surface temperature, prevailing wind, cloud mix).
  `--terrain` is ignored; `world.terrain_kind` is `"preset"`. `--seed` still varies fine detail,
  settlements and clouds, but the large-scale profile and landmark order are the region's.
  The preset is part of the save state, so a loaded world's `hello` carries it again.
* `--cloud-type` (showcase only, default `cumulus`) is one of the eight cloud `type`s.

## `hello` (v2.2 keys)
```json
{"type":"hello","version":2,"mode":"sandbox",
 "preset":{"id":"washington","name":"Washington","summary":"one or two sentences",
   "transect":"47.0°N, Pacific coast → Columbia Plateau (west → east)","span_km":480,
   "max_elev_m":4392,"m_per_px":11.5,
   "climate":{"station":"Seattle","temp_c":11.5,"annual_precip_mm":950,"wind":"westerly",
              "note":"wet windward slopes, dry rain shadow east of the Cascades"},
   "landmarks":[{"x":1510.0,"name":"Mount Rainier","elev_m":4392}],
   "sources":["https://..."]}}
```
* `mode` ∈ `sandbox | showcase | hurricane`; absent = `sandbox`.
* `preset` is absent for a non-preset world. `landmarks[].x` is a world x; the surface there is
  `terrain.y` as usual. `m_per_px` is the vertical scale of the *relief* (the real profile is
  compressed to fit the relief cap; the numbers in `landmarks`/`max_elev_m` are the real ones).
  `climate.temp_c` is the real annual mean at `station`; `world.surface_temp_c` (at the lowest
  ground) starts from the value consistent with it. Every figure must come from a cited source
  (the example values above are placeholders, not data).

## Showcase mode (`--mode showcase`) — "Cloud formations"
A small stage for one cloud type at a time: flat ground, no settlements, light steady wind, and
**exactly one cloud** of the chosen type that lives its whole life in view (forming → … →
dissipating → gone), then after a short pause a new unique cloud of the same type starts. A type
that can precipitate does so in every cycle (it is never left `fair` for its whole life);
`cirrus`/`altocumulus` stay fair. Frames are ordinary `frame` messages (wind, streamlines,
particles, arrows all present) plus:
```json
"showcase":{"type":"cumulus","cycle":3,"cloud_id":42}
```
`cloud_id` is `null` during the pause between cycles. `hello` carries:
```json
"mode":"showcase",
"showcase":{"type":"cumulus","types":["cumulus","cumulonimbus","nimbostratus","stratocumulus",
            "altostratus","altocumulus","stratus","cirrus"],
  "lessons":{"cumulus":{"title":"Cumulus","altitude":"600–2,000 m base",
     "formation":"...","life":"...","precipitation":"...","wind":"...","ending":"..."}}}
```
(`lessons` has an entry for every type; each text value is 1–3 plain sentences of real
meteorology.) Commands: `{"cmd":"showcase","type":"cirrus"}` drops the current cloud at once (no
`deaths` entry) and starts cycle 1 of the new type; `{"cmd":"showcase","restart":true}` restarts
the current type. `spawn`, `reseed` and `save` are ignored in this mode (`save` answers `saved`
with `ok:false`). `wind`/`temp`/`pause`/`speed`/`view`/`quit` work as usual.

## Hurricane mode (`--mode hurricane`) — "Hurricane formation"
A different model with its own frame type. `--width/--height/--terrain/--settlements/--preset`
are ignored. Distances are **km**, not pixels; the viewer chooses every layout.

### `hello`
The usual keys are still present so generic parsers work (`world` = `{"w":1920,"h":1080,
"ground_y":1000,"terrain_kind":"flat","terrain":{...flat...}}`, empty `settlements`/`objects`), plus:
```json
"mode":"hurricane",
"hurricane":{
  "domain":{"radius_km":600,"top_km":18},
  "stages":[{"id":"disturbance","name":"Tropical disturbance","wind_kt":[0,25],
             "pressure_hpa":1010,"category":-2,"summary":"2–4 sentences"}],
  "lesson":[{"id":"ingredients","title":"What a hurricane needs","text":"3–6 sentences",
             "view":"outside","focus":"sst","stage":"disturbance"}],
  "sources":["https://..."]}
```
* `stages` in order, ids exactly:
  `disturbance, depression, storm, cat1, cat2, cat3, cat4, cat5, landfall, dissipation`.
  `category`: −2 disturbance, −1 depression, 0 tropical storm, 1–5 Saffir–Simpson; `landfall`
  and `dissipation` carry the category they start from / end at. `wind_kt` = `[min,max]`
  1-minute sustained (max may be `null` for cat5).
* `lesson` is the ordered guided tour (text summary). `view` ∈ `outside | inside | both` says
  which picture the chapter is about; `focus` ∈ `sst | coriolis | convection | inflow | eye |
  eyewall | rainbands | outflow | warm_core | surge | decay | none` says what to highlight;
  `stage` is the stage the storm should be in while the chapter is shown. The tour must cover:
  ingredients, how it spins up (heat engine, Coriolis), the outside (eye, eyewall, spiral
  rainbands, outflow cirrus shield), the inside (cross-section: inflow at the surface, rising
  eyewall, outflow aloft, sinking warm clear eye, warm core), the stages, and how it dies.

### `hurricane` frame (every tick)
```json
{"type":"hurricane","frame":812,"t":27.07,"speed":1.0,"paused":false,
 "stage":"cat3","stage_index":5,"stage_frac":0.40,"auto":true,
 "state":{"vmax_ms":54.0,"vmax_kt":105,"pressure_hpa":955,"category":3,"rmw_km":32.0,
          "eye_km":14.0,"r34_km":230.0,"sst_c":29.0,"warm_core_c":9.5,"cloud_top_km":15.5,
          "rain_mmh":38.0,"surge_m":3.2,"organization":0.85,"eye_clarity":0.7,
          "over_land":false,"rotation":4.21,"hemisphere":"N"},
 "profile":{"r_km":[0,10,20],"wind_ms":[2,21,48],"pressure_hpa":[955,958,966],
            "rain_mmh":[0,5,38],"cloud_top_km":[2,9,15.5]},
 "bands":[{"theta":0.6,"r0_km":60,"r1_km":380,"pitch":0.28,"width_km":38,"intensity":0.8}],
 "section":{"cols":48,"rows":16,"dr_km":12.5,"dz_km":1.125,
            "u":[...],"w":[...],"v":[...],"cloud":[...],"temp_anom":[...]},
 "events":["t=27.1 the eye cleared out"]}
```
* `stage` is the stage the storm is in or heading to; `stage_frac` 0..1 progress through it.
  All of `state`, `profile`, `bands` and `section` are **smooth functions of time**: jumping to a
  stage glides there over a few seconds, nothing snaps. The `bands` list keeps a stable length
  and order (a band that is not there yet has `intensity` 0).
* `state.rotation` — radians, the accumulated rotation of the cloud pattern (counter-clockwise
  seen from above for `hemisphere:"N"`); `organization` 0 = scattered thunderstorms, 1 = a
  symmetric storm; `eye_clarity` 0 = no eye, 1 = a clear eye; `eye_km` is the eye *radius*.
* `profile` — radial profiles on a shared `r_km` axis, centre → `domain.radius_km`.
* `bands` — spiral rainbands as log spirals seen from above: starting at radius `r0_km` and
  angle `theta` (before `rotation` is added), `r = r0 * exp(pitch * (angle - theta))` out to `r1_km`.
* `section` — the vertical cross-section through the centre (one side; mirror it): flattened
  **row-major** grids, row 0 = surface, cell (c,r) centred at `((c+0.5)*dr_km, (r+0.5)*dz_km)`.
  `u` radial wind m/s (negative = inflow toward the eye), `w` vertical m/s (positive = **up**),
  `v` tangential m/s, `cloud` 0..1 cloud/rain density, `temp_anom` °C warmer than the
  environment. Optional: present on the first frame and then only when it changed noticeably
  (at most ~6 times a second); the viewer keeps the last one.

### Commands (hurricane)
| command | meaning |
|---|---|
| `{"cmd":"stage","id":"cat3"}` | glide the storm to the start of that stage; turns `auto` off |
| `{"cmd":"auto","value":true}` | let the storm run through all stages on its own, looping to `disturbance` after `dissipation` |
| `pause` / `resume` / `speed` / `quit` | as in the sandbox |

Everything else is ignored (`save` answers `saved` with `ok:false`).

---

# v2.3 additions (additive; `version` stays 2)

Climate cycles and gases, climate-driven cloud spawning, cloud merging, supercells and tornadoes,
cars, and a fourth mode (`world`). Every key here is optional on the wire: a v2.2 viewer that ignores
unknown keys still plays, and v2.2 save files still load (their world gets the default climate
below, continues from the saved `surface_temp_c`, and has no cars).

## Launch (v2.3)
```
python -u sim/precip_sim.py ... [--mode sandbox|showcase|hurricane|world]
                                [--month 1..12.99] [--hour 0..24] [--year-days N]
                                [--cloud-type <type incl. supercell>]
```
* `--month` (sandbox; default: the preset's `start_month`, else 4) is the calendar month the world
  starts in (fractions allowed, 1.0 = 1 January, 7.5 = mid July). `--hour` (default 7.2 = the old
  mid-morning start) is the local solar hour it starts at. `--year-days` (default 8, range 2–365)
  is how many sim days make one year. One sim day is still 300 sim seconds.
* `--preset` accepts every id in `sim/presets.py` (the six v2.2 regions plus the world-map
  locations).

## Climate: day, season and gases (sandbox and showcase)
`frame.world` gains:
```json
"world":{"surface_temp_c":14.2, "time_of_day":0.42,
         "year_frac":0.29,"month":4.5,"day":3,"season":"spring","hemisphere":"N",
         "temp":{"base_c":11.0,"season_c":1.4,"diurnal_c":2.1,"gas_c":0.0,"user_c":-0.3,
                 "day_min_c":7.6,"day_max_c":15.9}}
```
* `surface_temp_c` (at the lowest ground, as before) is now
  `base_c + season_c + diurnal_c + gas_c + user_c` and changes smoothly every tick.
  `base_c` = the climate's annual mean; `season_c` = this point of the year minus the annual mean
  (from the preset's twelve monthly means, smoothly interpolated; a plain world uses a sine with a
  seeded amplitude, coldest in mid January for `hemisphere:"N"`); `diurnal_c` = the day cycle
  (coldest just before sunrise, warmest mid-afternoon ≈ `time_of_day` 0.62, never a step);
  `gas_c` = greenhouse offset from `atmosphere.co2_ppm`; `user_c` = the sum of `temp` commands.
* `year_frac` 0..1 (0 = 1 January 00:00), `month` = `1 + 12*year_frac`, `day` = whole sim days
  since the world started, `season` ∈ `winter | spring | summer | autumn` (meteorological seasons,
  flipped for `hemisphere:"S"`). `day_min_c`/`day_max_c` = today's expected extremes.
* The `temp` command now changes `user_c` (same visible effect as before).

`frame.atmosphere` (new, every frame):
```json
"atmosphere":{"humidity":0.62,"co2_ppm":420,"aerosol":0.30,"instability":0.41,"shear":0.35,
              "spawn_per_min":5.2,"cloud_target":14,
              "type_weights":{"cumulus":0.31,"cumulonimbus":0.06,"supercell":0.01}}
```
* `humidity` 0..1 — water vapour available to clouds (relative-humidity-like). `co2_ppm`
  180–2000 (greenhouse gas: `gas_c` = 3.0 °C per doubling relative to 420 ppm). `aerosol` 0..1 —
  condensation nuclei (dust, smoke, sea salt): more nuclei make more, longer-lived clouds whose
  droplets are smaller, so they rain later and less.
* `instability` 0..1 — derived: warm, humid air under a cold sky (rises by day in the warm season).
  `shear` 0..1 — derived from the wind speed and its change with height.
* `spawn_per_min` — the current expected number of new clouds per sim minute; `cloud_target` — the
  cloud count the spawner is steering towards. Both are smooth functions of temperature, wind
  speed, humidity, aerosol and instability: warm humid unstable air breeds many convective clouds,
  cold or dry air few; a calm sky spawns slowly, a moderate wind fastest, a gale tears small
  clouds apart again. `type_weights` (all nine types, sum 1) is the current chance of each type
  for the *next* spawned cloud: the climate's base mix (preset `cloud_mix`) re-weighted by the same
  factors (convective types need warmth and instability, sheets want cool stable humid air, cirrus
  is nearly indifferent, `supercell` needs instability **and** shear).
* Commands: `{"cmd":"gas","name":"humidity|co2|aerosol","delta":0.05}` (delta in the gas's own
  unit: 0..1 for humidity/aerosol, ppm for co2; clamped), `{"cmd":"gas","name":"co2","value":560}`
  to set, `{"cmd":"season","month":7.5}` jumps the calendar (the temperature *glides* there over a
  few seconds), `{"cmd":"hour","value":14.0}` jumps the clock the same way.

`hello.climate` (sandbox and showcase):
```json
"climate":{"year_days":8,"day_length_s":300,"hemisphere":"N","lat":47.4,
           "monthly_temp_c":[5.5,6.3,8.0,10.4,13.6,16.2,19.0,19.2,16.3,11.8,7.6,5.2],
           "diurnal_range_c":8.5,"base_c":11.0,"start_month":4.0,"source":"preset"}
```
(`source` ∈ `preset | generated`; `monthly_temp_c` are the values at the lowest ground.)
`hello.preset.climate` gains `monthly_temp_c` (12, the station's real monthly means),
`monthly_precip_mm` (12), `diurnal_range_c` (mean daily max − min) and `lat`; `hello.preset` gains
`lat`,`lon` (the map dot), `region` (e.g. `"US-WA"`, `"PH"`) and `country`.

### Preset biome (v2.3)
`hello.preset.biome` makes a region look like itself with the game's existing art:
```json
"biome":{"id":"desert","ground":[194,160,104],"soil":[150,112,70],"grass":null,
         "water":[70,120,150],"trees":0.05,"snow_start":0.0,"frozen_water":false}
```
* `id` ∈ `temperate | mediterranean | tropical | savanna | desert | alpine | tundra | polar`.
  `ground` = surface colour, `soil` = the colour deeper down, `grass` = the turf strip colour or
  `null` for bare ground (sand, rock, ice), `water` = pond colour. `trees` 0..1 is informational
  (the sim already placed them). `snow_start` 0..1 = ground snow cover the world starts with below
  the freezing level (the first `ground` message carries it); `frozen_water` = ponds draw as ice.
* The sim builds the world to match: tree density and variants follow the biome (new `tree`
  variants `palm | cactus | acacia | shrub | dead`; `polar` and `desert` have next to none),
  ponds are rare in dry biomes, settlement sizes follow the real place (no skyscraper city at a
  research station or an oasis town; building `variant`s may add `adobe | hut | station` which
  draw as the nearest existing kind if unknown), and a settlement standing on a landmark that is a
  real town takes that town's name. Absent `biome` = `temperate`, today's look.

## Cloud merging
Two clouds that overlap enough (both condensed, neither dissipating) merge into one. The larger
one **survives** (same `id`, `seed`, name); the other is **absorbed**.
* Survivor: gains `"parts":[{"id":17,"type":"stratus","name":"St-0017 \"Low Veil\""}]` (in merge
  order, kept for life) and `"merge":0..1` (progress of the newest merger, smooth, stays 1).
  Copies of the absorbed cloud's puffs are **appended** to its `puffs` at r ≈ 0, positioned where
  the absorbed cloud's puffs are, and swell with `merge` — so the merged cloud takes the shape of
  both: a stratus sheet absorbed by a cumulus leaves a flat straight band through the cumulus,
  two cumulus make one wider multi-turret cumulus. `w`/`h`/`x`/`y`/`tint` glide to the combined
  cloud's; water, ice and capacity are added; lifetime is extended, never shortened.
  Optional `"puff_part":[0,0,1,1]` (same length as `puffs`): 0 = the cloud's own puff,
  k = a puff that came from `parts[k-1]` (the viewer may draw sheet parts flatter).
* Absorbed: stays in `clouds` for the few seconds of the crossfade with `"merging_into":42`, stage
  `dissipating`, moving with the survivor while its `form` falls to 0; then it leaves the list and
  appears in `deaths` with `"merged_into":42`. It never precipitates during the crossfade.
* `type` of the survivor: unchanged (a merged cumulus may still tower as usual; anything absorbed
  by a `cumulonimbus`/`supercell` just feeds it). A fair survivor that absorbs a rain cloud stops
  being fair. A cloud holds at most 3 `parts`; `puffs` may reach 72 entries.
* Nothing jumps on the merge frame (same no-jump rule as `morph`). An `events` line is logged.

## Supercell
A ninth cloud `type`: `"supercell"` (name prefix `Sp-`). It is a cumulonimbus-family cloud
(`anvil` is used, never `fair`) with a persistent rotating updraft. Extra cloud keys:
```json
"rotation":0.8,"wall":0.6,"overshoot":0.4,"meso_x":512.0,"hook":0.5
```
* `rotation` 0..1 mesocyclone strength; `wall` 0..1 how far the wall cloud has lowered under the
  rain-free base; `overshoot` 0..1 overshooting top above the anvil; `meso_x` world x of the
  mesocyclone / wall cloud centre (rear flank, upwind side of the rain); `hook` 0..1
  rear-flank-downdraft strength. All smooth. The puff skeleton includes the tower, a long anvil
  with a short back-sheared part, an overshooting dome, a flanking line of stepped cumulus on the
  rear flank and wall-cloud puffs below the base (grown by `wall`).
* Precipitation falls from the forward flank only (`rain`, `mixed`, `graupel`; large hail =
  `graupel` particles of size up to 9); the base around `meso_x` is rain-free.
* Sandbox: supercells spawn by `type_weights` (rare unless `instability` and `shear` are high) and
  by `{"cmd":"spawn","x":400,"y":200,"type":"supercell"}` (`spawn` accepts an optional `type` for
  any of the nine types; a bad type is ignored). A `cumulonimbus` does not turn into a supercell.

## Tornadoes
`frame.tornadoes` (omitted or `[]` when none):
```json
"tornadoes":[{"id":3,"cloud":42,"stage":"mature","x":512.0,"base_y":640.0,"ground_y":988.0,
              "reach":1.0,"width":46.0,"top_width":120.0,"lean":-38.0,"strength":0.7,"ef":2,
              "wind_ms":58.0,"age":21.4,"phase":12.6,"debris":0.6,"path_x0":430.0}]
```
* A tornado belongs to a `supercell` (`cloud`) with strong `rotation` and a lowered `wall`.
  `stage` ∈ `funnel | touchdown | mature | roping`: the funnel descends from the wall cloud
  (`reach` 0 → 1 = fraction of the way from `base_y` to the ground; on the ground when
  `reach` = 1), matures, then ropes out (`width` shrinks, `|lean|` grows, `reach` falls back) and
  leaves the list once `width` ≈ 0. Every number is smooth in time.
* `x` = ground contact point (or where it would touch), `ground_y` = terrain there, `base_y` =
  cloud base; `width` px at the ground, `top_width` at the cloud base; `lean` px the top is offset
  from `x`; `strength` 0..1; `ef` 0–5 (Enhanced Fujita from `wind_ms`, 3-second gust);
  `phase` radians, accumulated rotation for the viewer's swirl; `debris` 0..1 size of the debris
  cloud at the base; `path_x0` where it first touched down (damage path = `path_x0`..`x`;
  absent before touchdown).
* Effects while on the ground: the wind field converges and spins around it (streamlines bend
  in), particles of kind `7` (debris, 7 numbers as usual) are thrown up, trees `sway` hard, cars
  are tossed (below) and objects take **damage**: optional `frame.damage` = `[[id, dmg], ...]`
  (`dmg` 0..1, only for objects whose damage changed; it never decreases; `hello.objects[].damage`
  carries it for a loaded world). Stats gain `"tornadoes"` (alive) and `"tornadoes_total"`.
* Commands: `{"cmd":"tornado","cloud":42}` asks that supercell to produce one now (ignored if it
  is not a mature supercell or already has one); `{"cmd":"tornado"}` picks the strongest supercell.
* `events` log the funnel, the touchdown, the peak EF rating, the rope-out and the path length.

## Cars
`hello.roads` = `[{"x0":800.0,"x1":1330.0,"kind":"street"},{"x0":0.0,"x1":3840.0,"kind":"highway"}]`
(spans of the terrain surface painted as road; streets lie inside settlements, at most one
`highway` joins them; absent/empty with `--settlements none`).
`frame.cars`:
```json
"cars":[{"id":7,"x":912.4,"y":961.0,"angle":0.04,"dir":1,"speed":38.0,
         "variant":"sedan","color":[178,34,34],"lights":true,"wipers":false,
         "state":"driving","lift":0.0,"spin":0.0,"wet":0.2,"snow":0.0}]
```
* `x,y` = the point on the road surface under the car's centre; `angle` = slope of the road there
  (radians, the viewer rotates the body); `dir` +1 = facing right; `speed` px/s (≥ 0).
  `variant` ∈ `sedan | hatchback | pickup | van | truck | bus`; `color` body paint.
  `lights` = headlights on (night, rain, snow, under a dark storm); `wipers` = raining on it.
* `state` ∈ `driving | stopped | tossed | wrecked`. Cars drive along roads, slow down on wet or
  snowy ground and steep slopes, keep a gap to the car ahead, turn back at a street's end, and
  enter and leave at the world edges on a highway. A tornado **tosses** cars it reaches: `lift` px
  above the surface and `spin` radians change smoothly through the air, then the car lands and
  stays `wrecked` (never moves again; removed after a while). `wet`/`snow` 0..1 are optional.
* Car ids are unique per world and stable; cars are part of the save state. Stats gain `"cars"`.

## Showcase (v2.3)
* `hello.showcase.types` includes `"supercell"`; `lessons.supercell` exists with the usual keys.
* `hello.showcase.tours` = `{"supercell":[{"id":"towering","title":"...","text":"2–4 sentences"}]}` —
  an ordered **lifetime tour**; ids for the supercell, in order:
  `towering, mesocyclone, mature, wall_cloud, funnel, tornado, rope_out, decay`.
  `frame.showcase.tour_step` = the id the cloud on stage is at now (`null` in the pause).
  On the supercell stage every cycle runs the whole tour: the cloud always lowers a wall cloud and
  produces exactly one tornado that touches down, matures and ropes out before the storm decays.
* Showcase frames carry `atmosphere` and the climate keys too (the stage holds a fixed late-spring
  afternoon: no day or season drift there, so the lesson always looks the same).

## World mode (`--mode world`) — "World simulation"
The whole Earth on an equirectangular map with its own frame type (like hurricane mode, the usual
`hello` keys are present with a flat dummy world). Latitude −90..90 (north positive), longitude
−180..180 (east positive). The **viewer owns the map geometry** (coastlines, countries, US states:
`assets/world/*.json`, from Natural Earth 110m, public domain) — the sim never sends polygons.

### `hello`
```json
"mode":"world",
"world_map":{
  "grid":{"cols":72,"rows":36,"dlon":5.0,"dlat":5.0},
  "year_days":8,"day_length_s":300,
  "locations":[{"id":"washington","name":"Washington","lat":47.45,"lon":-122.31,
                "region":"US-WA","country":"US","kind":"state",
                "summary":"one sentence","station":"Seattle-Tacoma Airport",
                "annual_temp_c":12.1,"annual_precip_mm":999,"highlights":["rain shadow","snow"]}],
  "regions":{"US-WA":"washington","PH":"philippines"},
  "sources":["https://..."]}
```
* `locations` = every preset in `sim/presets.py` (one map dot each; `kind` ∈ `state | country |
  place`). `regions` maps a region code (ISO 3166-1 alpha-2 for a country, `US-XX` for a US
  state — the same codes as the viewer's map files) to the preset id that a click on that region
  opens. A region that is not in `regions` has no preset of its own.

### `world` frame (every tick)
```json
{"type":"world","frame":812,"t":27.07,"speed":1.0,"paused":false,
 "year_frac":0.29,"month":4.5,"day":3,"utc_hour":13.2,
 "sun":{"lat":9.4,"lon":-18.0},
 "grid":{"temp_c":[],"cloud":[],"precip":[],"u":[],"v":[],"pressure_hpa":[],"snow":[]},
 "systems":[{"id":4,"kind":"low","lat":52.0,"lon":-28.0,"strength":0.7,"radius_deg":11.0,
             "name":null}],
 "locations":[{"id":"washington","temp_c":9.8,"cloud":0.8,"precip":0.3,"wind_ms":6.1,
               "weather":"rain","local_hour":5.1,"month":4.5}],
 "stats":{"mean_temp_c":14.6,"cloud_cover":0.61,"storms":3},
 "events":["day 3: a tropical cyclone formed east of the Philippines"]}
```
* `grid` arrays are flattened **row-major** (`cols*rows` numbers each), row 0 = the northernmost
  band, cell (c,r) centred at `lon = -180 + (c+0.5)*dlon`, `lat = 90 - (r+0.5)*dlat`. `temp_c` 2 m
  temperature, `cloud` 0..1 cover, `precip` 0..1 intensity, `u`/`v` wind m/s (u east, v **north**),
  `snow` 0..1 cover. Optional: present on the first frame and then at most ~4 times a second; the
  viewer keeps the last one. All fields move smoothly.
* `sun` = the subsolar point (day/night terminator and season follow from it). `utc_hour` 0..24.
* `systems.kind` ∈ `low | high | cyclone | front | tornado_outbreak`; they drift with the
  large-scale wind (westerlies in mid-latitudes, trades in the tropics), the ITCZ rain band follows
  the sun, tropical cyclones form over warm seas in their hemisphere's season and get a `name`.
* `locations[].weather` ∈ `clear | cloudy | rain | snow | storm | fog`; `local_hour` = local solar
  time there; `month` = the calendar month a sandbox opened now should start in.
* The model is a smooth parametric climate (zonal climatology + season + day cycle + land/sea
  contrast + moving systems), not a fluid solver; location readouts are anchored to the presets'
  real monthly normals.

### Commands (world)
| command | meaning |
|---|---|
| `{"cmd":"season","month":7.5}` | jump the calendar (fields glide) |
| `{"cmd":"hour","value":14.0}` | jump the UTC clock |
| `pause` / `resume` / `speed` (0.25–64) / `quit` | as in the sandbox |

Everything else is ignored (`save` answers `saved` with `ok:false`, as in hurricane mode). Opening
a location is the viewer's job: it starts a **sandbox** sim with `--preset <id> --month
<locations[].month> --hour <locations[].local_hour>`, so the place opens in the season and at the
time of day the world map showed.

---

# v2.4 additions (additive; `version` stays 2)

Windsocks are gone (see the object kinds above), puddles grow without a width limit, settlements get
storm drains whose size decides whether the town floods, and standing water has a level surface that
always rests on the ground. Every key here is optional on the wire: a v2.3 viewer that ignores unknown
keys still plays (it draws the legacy `puddle` objects as before), a v2.4 viewer on a v2.3 stream, a
replay or a state file without these keys draws the legacy puddles and shows no drains, and v2.3 state files
still load (below). **Sandbox only:** showcase, hurricane and world mode send none of these keys.

## Launch (v2.4)
```
python -u sim/precip_sim.py ... [--drains small|normal|large|random]
```
* `--drains` (sandbox; default `normal`) is how the settlements' drain sizes are chosen (below). It is
  accepted with `--settlements none` and then has no effect. With `--load` the saved choice wins.

## Standing water
A puddle is no longer an ellipse of fixed shape that widens. Water is a **volume in a hollow of the
heightmap**: it fills the hollow from the bottom as a *level surface* (one `y` for the whole body), so it
rests on the ground at any size. When the level reaches the lower rim of the hollow the excess spills into the
next hollow downhill; two hollows whose levels both reach the rim between them become one body; water that
spills past a world edge is lost. There is **no width cap** in this model: a body is as wide as its level
makes it. Water leaves a body only by soaking away or evaporating (and, in a settlement street, through the drains).
Rain is tallied on the ground in 32 px buckets already; each bucket's rain runs downhill to a hollow and
adds its volume there. A `pond` object is a closed hollow: rain that runs into it is absorbed and no body forms there.
A hollow shallower than `hello.water.basin_min_depth` is not a hollow (water crosses it as if it were level
ground), so a flat world has no standing water, only wet ground.

`hello.water` (sandbox mode only; its presence means "this stream uses the v2.4 water model"):
```json
"water":{"model":1,"basin_min_depth":1.0}
```

`frame.water` (optional; the **complete** list of bodies, replacing the previous list; `[]` = everything
is dry). Sent on the first frame after a `hello` or a load, then only on frames where something changed
(a body appeared, vanished or merged, its `y` moved by 0.05 px or its `x0` / `x1` by 0.5 px), at most every
3rd frame (about 10 times a second). The viewer keeps the last one.
```json
"water":[{"id":9,"kind":"pool","x":1204.0,"x0":1163.5,"x1":1247.0,"y":988.4,"depth":3.2,"vol":96.0},
         {"id":2,"kind":"street","settlement":2,"x":1010.0,"x0":820.0,"x1":1310.0,"y":991.0,"depth":4.6,
          "vol":2254.0,"spill":false}]
```
* `id` — stable for the body's life, unique per run, never reused. A body that merges into a larger one
  disappears and the larger keeps its id (tie: the lower id). A `street` body's id **is its settlement's `id`**, fixed for
  the whole world (pools count on from one more than the largest settlement `id`, so the two never clash);
  it is in the list only while it holds water: a `street` body only while its `depth` is at least 0.05 px (a thinner
  film is not listed; `frame.flood` still reports the street), a `pool` while its volume is at least 0.3 px².
* `kind` ∈ `pool | street`. A `street` body is the water standing on a settlement's street (below);
  `settlement` is that settlement's `id`.
* `y` — the water **surface**, a world y (y grows down, so a *smaller* `y` is a higher level).
  `x0`, `x1` — where that level meets the ground on the left and on the right (the stretch of ground under
  water); for a street body the ends are the curbs when the level is above the street's ends.
  `x` — the deepest point of the bed; `depth` — `terrain(x) − y`, the water depth there, in px; `vol` — the
  cross-section area in px² (the world is 2-D; 1 px² is the unit of water). `spill` (street only, optional,
  default `false`) — true while the level is at the curb and water is running over it. A `pool` that runs over its rim
  into the next hollow carries no `spill` key: the flag is for streets only.
* **How the viewer draws it:** the body is the region between the surface line at `y` and the terrain
  polyline (`hello.world.terrain`, linear between samples), over `[x0, x1]`, wherever the terrain is below `y`.
  It never draws above `y` or beyond `[x0, x1]`, so it cannot float; it needs nothing else from the sim.
  The list arrives about 10 times a second, so the viewer **eases** `y`, `x0` and `x1` of each id towards the
  latest values (about 0.12 s) and fades a new id in and a vanished id out (about 0.3 s). The water is drawn
  after the cars and the falling rain, so a car stands in it and a drop that reaches the surface is covered.
* A legacy `puddle` object is **not drawn** when `hello.water` is present.

## Drains and flooding
Every settlement (`hamlet`, `village`, `town`, `city`) has storm drains: **inlets** along its street and an
internal pipe network whose **size** sets how much water it removes per second. There are none outside a
settlement.

`hello.drains` (sandbox only; the constants the viewer needs):
```json
"drains":{"mode":"normal","inlet_spacing":60.0,"curb":10.0,"flood_depth":3.0,
          "size_min":1.5,"size_max":12.0,"classes":{"small":3.0,"normal":4.7,"large":8.2}}
```
* `curb` — height in px of the virtual curb at each end of a street (replaced by v2.4.1 when `hello.flow` is present).
* `mode` ∈ `small | normal | large | random | custom` — how the sizes were chosen: `small` / `large` give every
  settlement the class size, `normal` gives each settlement a size by its kind with a seeded spread (so some
  towns flood in a storm another shrugs off), `random` a seeded size in `[2.5, 10]` for each; `custom` once a
  single settlement's size has been changed by command. The sizes are a function of the world seed, the
  settlement `id` and `kind` (never of the world's random stream), so one seed always gets the same sizes.
* `classes` — pipe size in px of each class; a settlement's `class` is the nearest class by ratio
  (below 3.76 `small`, from 6.2 `large`, else `normal`).

Each entry of `hello.settlements` gains:
```json
"drain":{"size":4.7,"class":"normal","inlets":8,"cap":30.0}
```
* `size` — the pipe diameter in px (the visible measure: the inlet grates and the cutaway pipe are drawn from it);
  `inlets` — `max(1, round((x1 − x0) / inlet_spacing))`, inlet `i` (0-based) at
  `x = x0 + (i + 0.5) · (x1 − x0) / inlets`; `cap` — the most water the whole network removes, px²/s
  (every inlet takes `size² · k`, `k` belongs to the sim; the viewer never computes `cap`, it reads it).
  Absent `drain` = no drains known (an old stream): draw none.

**Street flooding.** (replaced by v2.4.1 when `hello.flow` is present) The rain that falls on a settlement's span `x0 .. x1` runs into its drains; the sim keeps
one water body per settlement bounded by a virtual curb `curb` px high at each end (the curb is not in the
terrain; it only decides where water spills). While the rain's inflow is **below** `cap` the drains keep up and the
street is at most wet; above it the excess stands on the street and its level rises; when the rain stops the
level falls again at the rate the drains clear it. At the curb the excess spills over the lower end into the next
hollow. `frame.flood` reports each settlement:

`frame.flood` (optional; the complete list, one entry per settlement; sent on the first frame after a `hello` or a
load, then only when an entry changed, at most every 6th frame; once every entry is `dry` it is sent one last time and then not again).
"Changed" means any value of any entry differs from the list last sent, as it goes out (`depth` to 0.01 px, `inflow` to
0.1 px²/s): there is no tolerance as `frame.water` has, so while rain falls and `inflow` moves the list goes out at every
6th frame (about 5 times a second):
```json
"flood":[{"id":2,"state":"flooded","depth":4.6,"inflow":41.0,"cap":30.0},
         {"id":1,"state":"wet","depth":0.0,"inflow":6.2,"cap":14.0}]
```
* `depth` — px of water over the street's lowest point (0 when none); `inflow` — px²/s of rain reaching the
  drains' catchment, averaged over the last 2 s; `cap` — as in `drain.cap` (repeated so a card needs nothing else).
* `state`: `dry` (replaced by v2.4.1 when `hello.flow` is present) (no water and `inflow` below 0.01), `wet` (no water, rain falling, drains keeping up), `ponding`
  (`0.05 ≤ depth < flood_depth`), `flooded` (`flood_depth ≤ depth`, below the curb), `overflowing`
  (water running over the curb: the street's `spill` is true, **or** `depth ≥ curb − 0.05`). "The city floods" means
  `flooded` or `overflowing`. Two consequences of how the sim reads this: `depth` is measured at the street's lowest
  ground, and the spill level is the lower of the street's two end elevations plus `curb`, so on a street whose lowest ground lies below both
  ends `depth` is already 9.95 px (and `state` already `overflowing`) before the level reaches the curb, `depth` can
  exceed `curb` once water spills, and `overflowing` can read true while `spill` is still `false`; after the rain stops,
  `overflowing` also lasts until the drains bring `depth` under 9.95 px even though nothing spills any more. A viewer
  takes `state` from `frame.flood` and `spill` from `frame.water` and does not derive either from the other.

`frame.drains` (optional; present only on the frame after a `drains` command, the full state):
```json
"drains":{"mode":"custom","settlements":[{"id":2,"size":6.5,"class":"large","inlets":8,"cap":56.0}]}
```
`cars[].depth` (optional, px, default 0): the depth of standing water at the car's `x`. A car slows as it deepens and
stands still when the water reaches the middle of its wheels (the sim decides); its `state` stays `driving | stopped`.

## Commands (v2.4)
| command | meaning |
|---|---|
| `{"cmd":"drains","mode":"small"}` | `small`, `normal`, `large` or `random`: every settlement gets that mode's size again (water already standing stays) |
| `{"cmd":"drains","settlement":2,"size":6.5}` | set one settlement's pipe size (px, clamped to `size_min .. size_max`); `mode` becomes `custom` |
| `{"cmd":"drains","settlement":2,"delta":1.0}` | change one settlement's size by `delta` px (same clamp) |
| `{"cmd":"drains","delta":-0.5}` | change every settlement's size by `delta` px |

A command that carries `mode` is read as a mode command and its other keys are ignored. A bad value, an unknown
settlement id or a missing key is ignored. Each accepted command is answered by one
`frame.drains` on the next frame. `drains` is ignored outside sandbox mode.

## Save files (v2.4)
The state file gains a `water` block: the drain mode and each settlement's size, every standing body (id,
anchor, volume), each street's volume and inflow average, the id counter, and the emit bookkeeping, so
*save → load → run M* stays frame-for-frame identical to an uninterrupted run. A state file without it (v2.3
and older) loads with no standing water, `drains.mode` `"normal"`, and the sizes **computed from the seed
exactly as a new world of that seed would get them**; legacy `puddle` objects in it keep their saved sizes, and
a `windsock` object in it is **dropped on load** when it is in the trailing run of the object list (windsocks were
the last objects, so nothing is renumbered; a retired object elsewhere in the list would stay as an inert row, and no
save the game wrote has one). A replay file or an older stream may still carry windsocks; viewers ignore them.

# v2.4.1 additions (additive; `version` stays 2)

v2.4's standing water is amended: there is **no virtual curb**. Water never stands above lower ground; a street is
ordinary ground with drains. Water on the ground runs downhill as a film to the lowest ground it can reach, fills
that hollow to a level surface that spreads over whatever land its level reaches (a street included), and spills
onward when the hollow is full. Everything here is switched on by `hello.flow`: a stream without it is a v2.4
stream and means what v2.4 says. Every key is optional: a v2.4 viewer ignores the new ones (it draws the bodies
that exist and its own trickle, and no sheet of water on a street), and a v2.4.1 viewer on a v2.4 stream or save
draws what it did before. **Sandbox only** (with `hello.water`).

## Overland flow
`hello.flow` (present only when the sim carries the film):
```json
"flow":{"model":1,"cell":32,"min_h":0.05}
```
`frame.flow` (optional; the **complete** list of runs, replacing the previous list; `[]` = no flow). Sent on the first
frame after a `hello` or a load, then only when a run appeared, vanished, or a cell's `h` moved by 0.1 px or its `q`
by 10 %, at most every 3rd frame; once everything is dry it is sent one last time as `[]` and then not again.
```json
"flow":[{"k0":87,"dir":1,"src":3,"dst":4,"h":[2.05,2.1,1.9,1.4,0.9],"q":[36.0,35.2,34.8,30.1,22.0]},
        {"k0":22,"dir":-1,"src":0,"dst":2,"h":[0.35,0.55,0.8],"q":[2.1,4.0,6.3]}]
```
* A run is a chain of neighbouring cells, **downhill**: cell `i` of the run has its centre at `x = 32 x (k0 + dir x i)`,
  `h[i]` is the depth of the film there (px, steps of 0.05), `q[i]` the flux leaving it (px^2/s, one decimal); the
  speed the viewer shows is `q / h`. A cell is in at most one run; a run has at most 40 cells and is cut where a body
  begins. **A street's own sheet is part of a run** (its cells lie inside the settlement's span).
* `src` - the id of the body that feeds the run's first cell, or the **settlement `id`** of the street the run starts
  on, `0` = the rain on the slope only. `dst` - the id of the body the last cell runs into, `-1` = off a world edge,
  `-2` = into a pond (absorbed).
* **How the viewer draws it:** a ribbon along `hello.world.terrain` over the cells, its top `h` above the ground
  (times `sqrt(1 + slope^2)`), eased per cell (about 0.12 s), a new run fading in (about 0.3 s); never above the
  ground by more than `h`, beyond the run, or inside a body's water.

## Standing water and flooding without a curb
* `hello.drains.curb` is **0.0** on a stream with `hello.flow` (a viewer that ignores the new keys reads 0 and draws
  no kerb or ramp); `flood_depth` stays 3.0; new `overflow_depth` (default 8.0).
* A body's ends are where its level meets the ground (`x0`, `x1`) and the ground just outside is higher; there is no
  ramp and no wall. `frame.water` lists a `street` body (id = its settlement's `id`) **only while the street itself is
  a hollow** that holds water; a pool whose level reaches a street covers it and is listed as a `pool`. `spill` is true
  while a body runs over its real rim.
* `frame.flood[]` entries (the complete list, as v2.4) have the same keys, with new meanings: `depth` - the greatest
  water depth on the settlement's span (the thickest film cell, or the body's depth at its lowest point); `inflow` -
  px^2/s entering the span (rain, film from upslope, a hollow's spill), 2 s mean; `state` - `dry`, `wet`, `ponding`
  (`0.05 <= depth < flood_depth`), `flooded` (`flood_depth <= depth < overflow_depth`), `overflowing`
  (`depth >= overflow_depth`, or the body that covers the span is running over its rim); optional `backed` (default
  false) - true while a body that came from a hollow covers part of the span. "The city floods" = `flooded` or
  `overflowing`; a town on high ground has no hollow to hold water and floods only as a sheet running off its
  street, when its drains are exceeded.
* `cars[].depth` is the depth of the body or of the film at the car's `x`.

## Save files (v2.4.1)
The `water` block gains `film`: `[[k, S], ...]` (the cells that hold water). A v2.4 `water` block loads: the water
its street bodies held is put on the street as film. A state without `film` loads with a dry ground; an older sim
ignores the key.
