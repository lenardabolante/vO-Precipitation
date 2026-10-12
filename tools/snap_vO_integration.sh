#!/bin/bash
# Regenerates the Phase O integration pictures (OI) into viewer/snapshots/vO/i/.
# Run from the project root after:  javac -encoding UTF-8 -d viewer/out_oi viewer/src/precip/*.java
#   bash tools/snap_vO_integration.sh                 people + hit (no Python needed)
#   bash tools/snap_vO_integration.sh people          the people: day, evening, night, rain, a tornado near (zoom 2.5 and 4)
#   bash tools/snap_vO_integration.sh hit             the fake scene "hit": first piece, the swirl, the lift, the fall, all down
#   bash tools/snap_vO_integration.sh record DIR [SEED] [EF]
#                                                     the REAL simulation: a forced EF3 through the village with the most houses,
#                                                     recorded into DIR (hit.jsonl ~150 MB, hit.sav, reload.jsonl, hit.txt); default seed 13
#   bash tools/snap_vO_integration.sh real DIR        the pictures of that recording: before, funnel arriving, mid-hit, just after,
#                                                     settled, and the same world after save + reload, each at zoom 1 and 2.5;
#                                                     then settled against reloaded, pixel by pixel
#   bash tools/snap_vO_integration.sh old             a save from before Phase O (sim/tests/data) and a recorded v2.4 stream still draw
# CP=<classes folder> picks another build (default viewer/out_oi). Never viewer/out: the player's game runs from it.
# Settings and saves are read from a throw-away folder that is removed again, never from the player's config/ and saves/.
# "record" imports sim/ read-only and drives it as the planning script did (a mature supercell held over the village,
# Simulation._start_tornado(..., peak_ef=3)); nothing in sim/ is written.
set -u
OUT="viewer/snapshots/vO/i"
CP="${CP:-viewer/out_oi}"
TMP="$OUT/.tmp"
WHAT="${1:-all}"
mkdir -p "$OUT" "$TMP/cfg" "$TMP/saves"
J="java -Xmx900m -cp $CP precip.Main --config-dir $TMP/cfg --saves-dir $TMP/saves"
CLEAN="--hide panel,minimap,left,wind,arrows,climate,labels"
say() { "$@" 2>&1 | grep -E "snapshot written|NOT reached|FAIL|Exception|unknown" | sed -E 's#written: .*[\\/]i[\\/]#written: #' | cut -c1-150; }

if [ "$WHAT" = all ] || [ "$WHAT" = people ]; then
  # ---- the street of scene "hit" before the storm reaches it (dry): day, evening, night, at zoom 2.5 and 4
  for l in day:0.45 evening:0.74 night:0.02; do
    n=${l%%:*}; t=${l##*:}
    say $J --fake-scene hit --tod $t --frames 5 --cam 560,838,2.5 --shade off $CLEAN --snapshot $OUT/people_${n}_z25.png
    say $J --fake-scene hit --tod $t --frames 5 --cam 455,872,4 --shade off $CLEAN --snapshot $OUT/people_${n}_z4.png
  done
  # ---- rain on the fake village (the buildings are wet by frame 400): nobody out
  say $J --fake --tod 0.45 --frames 400 --cam 1180,838,2.5 --shade off $CLEAN --snapshot $OUT/people_rain_z25.png
  say $J --fake --tod 0.45 --frames 400 --cam 1215,872,4 --shade off $CLEAN --snapshot $OUT/people_rain_z4.png
  # ---- a tornado on the ground just west of the street: nobody out anywhere within 1500 px
  say $J --fake-scene hit --tod 0.45 --until tornado:touchdown+1 --cam 560,838,2.5 --shade off $CLEAN --snapshot $OUT/people_tornado_z25.png
  say $J --fake-scene hit --tod 0.45 --until tornado:touchdown+1 --cam 455,872,4 --shade off $CLEAN --snapshot $OUT/people_tornado_z4.png
fi

if [ "$WHAT" = all ] || [ "$WHAT" = hit ]; then
  # ---- the fake hit, the swept cottage (the first house of the street) close up, then the whole street
  for s in first first+3 first+6.6 first+7.3 first+11; do
    say $J --fake-scene hit --until hit:$s --cam 430,830,3.6 --size 1500x640 --shade off $CLEAN --snapshot $OUT/hit_swept_$s.png
  done
  for s in first+20 lift lift+1.2 lift+2.5 done; do
    say $J --fake-scene hit --until hit:$s --cam 620,700,1.6 --size 1500x700 --shade off $CLEAN --snapshot $OUT/hit_street_$s.png
  done
  say $J --fake-scene hit --until hit:done --cam 470,850,3.6 --size 1500x560 --shade off $CLEAN --snapshot $OUT/hit_swept_done.png
  say $J --fake-scene hit --until hit:done --cam 760,790,1 --shade off $CLEAN --snapshot $OUT/hit_done_z1.png
  say $J --fake-scene hit --until hit:done --cam 620,838,2.5 --shade off $CLEAN --snapshot $OUT/hit_done_z25.png
fi

if [ "$WHAT" = record ]; then
  DIR="${2:?record needs a folder}"; SEED="${3:-13}"; EF="${4:-3}"
  python - "$(pwd)" "$DIR" "$SEED" "$EF" <<'PYREC'
"""The recorder of tools/snap_vO_integration.sh (reads sim/, never writes there): a forced EF3 through the widest town of a real-simulation world.

    python - ROOT OUT_DIR [seed] [ef]

Writes OUT_DIR/hit.jsonl (hello + every frame from just before the funnel to the settled town, the last frames paused),
OUT_DIR/hit.sav (the simulation's own save of that settled, paused state), OUT_DIR/reload.jsonl (a NEW simulation loaded
from the save: hello + a few paused frames) and OUT_DIR/hit.txt (the camera spot and the frame numbers of the five moments).
The pattern is the planning script's: a mature supercell is held over the town and `_start_tornado(..., peak_ef=3)` is called.
"""
import json
import os
import sys

root, out = sys.argv[1], sys.argv[2]
seed = int(sys.argv[3]) if len(sys.argv) > 3 else 11
ef = int(sys.argv[4]) if len(sys.argv) > 4 else 3
sys.path.insert(0, os.path.join(root, "sim"))
import clouds as C                      # noqa: E402
from simulation import Simulation       # noqa: E402
from savestate import save_state, load_state   # noqa: E402

DT = 1.0 / 30
os.makedirs(out, exist_ok=True)
sim = Simulation(seed, 3840, 1080, 30, "rolling", "normal")
sim.clouds.clear()
sim._next_spawn_delay = 1e18
sim.auto_tornado = False
# the settlement with the most houses (a street of small buildings shows the hit best; a city is mostly towers)
def houses(s):
    return sum(1 for o in sim.objects.objs if o["kind"] == "house" and s["x0"] <= o["x"] <= s["x1"])
towns = sorted(sim.settlements, key=lambda s: (-houses(s), -(s["x1"] - s["x0"])))
town = towns[0]
print("settlements:", [(s.get("kind"), round(s["x0"]), round(s["x1"]), houses(s)) for s in sim.settlements], file=sys.stderr)
mid = (town["x0"] + town["x1"]) / 2
try:
    c = sim._spawn_cloud_auto(x=mid + 100, cloud_type="supercell", diurnal=False)
except TypeError:
    c = sim._spawn_cloud_auto(x=mid + 100, cloud_type="supercell")
c.rot_peak = c.wall_peak = 0.95
c.vx = c.vy = 0.0
warm = 0
for warm in range(30 * 260):
    sim.step(DT)
    c.vx = c.vy = 0.0
    if C.supercell_mature(c):
        break
c.ax = mid - c.meso_dx - 150
c.refresh()
# (a day is 300 s and the hit with its settling takes about 90 s = 7 h of it: the clock is set to 9 h now, so the funnel is
# on the ground from about 10 h to 14.7 h and the settled town is seen at about 16 h, by daylight. From 11 h on the settled
# picture is already dusk, 18.3 h, and the rubble cannot be made out)
sim.handle_command({"cmd": "hour", "value": 9.0}, [])
ground_y = sim.terrain.height_at(mid)
dump = lambda o: json.dumps(o, separators=(",", ":"))
n = 0
marks = {}
first_ground = last_ground = None
rows = 0
per_frame_rows = []
with open(os.path.join(out, "hit.jsonl"), "w", encoding="utf-8", newline="\n") as f:
    f.write(dump(sim.hello_message()) + "\n")

    def step():
        global n, rows
        fr = sim.step(DT)
        c.vx = c.vy = 0.0
        f.write(dump(fr) + "\n")
        n += 1
        k = len(fr.get("damage", []) or [])
        rows += k
        per_frame_rows.append(k)
        return fr

    for _ in range(40):
        step()
    marks["before"] = 30
    tor = sim._start_tornado(c, [], forced=True, peak_ef=ef)
    wind_max = 0.0
    xs = []
    while sim.tornadoes and n < 30 * 240:
        fr = step()
        if not sim.tornadoes:
            break
        tn = sim.tornadoes[0]
        wind_max = max(wind_max, tn.wind_ms)
        if tn.ground_factor > 0:
            if first_ground is None:
                first_ground = n
            last_ground = n
            xs.append(tn.x)
    gone = n
    for _ in range(30 * 14):
        step()
    sim.paused = True
    for _ in range(4):
        step()
ok, err = save_state(sim.to_state(), os.path.join(out, "hit.sav"))
sim2 = Simulation.from_state(load_state(os.path.join(out, "hit.sav")))
sim2.paused = True
with open(os.path.join(out, "reload.jsonl"), "w", encoding="utf-8", newline="\n") as f:
    f.write(dump(sim2.hello_message()) + "\n")
    for _ in range(4):
        f.write(dump(sim2.step(DT)) + "\n")
# the moments
busiest = max(range(len(per_frame_rows) - 30), key=lambda i: sum(per_frame_rows[i:i + 30])) + 15 if rows else (first_ground + last_ground) // 2
marks["funnel_arriving"] = first_ground + 20
marks["mid_hit"] = busiest
marks["just_after"] = last_ground + 40
marks["settled"] = n
objs = {o["id"]: o for o in sim.objects.objs}
hit = sorted(((o["damage"], o["kind"], o.get("variant"), round(o["x"])) for o in objs.values() if o.get("damage", 0) > 0), reverse=True)
xa, xb = (min(xs), max(xs)) if xs else (mid, mid)
info = {
    "seed": seed, "ef_asked": ef, "town": [round(town["x0"]), round(town["x1"])], "town_kind": town.get("kind"), "town_houses": houses(town), "ground_y": round(ground_y),
    "cam_x": round((xa + xb) / 2), "cam_y_z1": round(ground_y - 230), "cam_y_z25": round(ground_y - 85),
    "warmup_frames": warm, "frames": n, "first_ground": first_ground, "last_ground": last_ground, "tornado_gone": gone,
    "grounded_s": round((last_ground - first_ground) / 30.0, 1), "path_x": [round(xa), round(xb)], "wind_max_ms": round(wind_max, 1),
    "damage_rows": rows, "marks": marks, "save_ok": ok, "save_err": err, "damaged": len(hit), "destroyed": sum(1 for h in hit if h[0] >= 0.98),
    "most_damaged": hit[:14], "hour_end": round(sim.world.time_of_day * 24, 1),
}
with open(os.path.join(out, "hit.txt"), "w", encoding="utf-8") as f:
    f.write(json.dumps(info, indent=1))
print(json.dumps(info, indent=1))
PYREC
fi

if [ "$WHAT" = real ]; then
  DIR="${2:?real needs the folder that record wrote}"
  # (the camera spot and the frame numbers of the moments come from the recorder's hit.txt)
  read -r CX GY F1 F2 F3 F4 F5 <<< "$(python -c "import json,sys; d=json.load(open(sys.argv[1])); m=d['marks']; print(d['cam_x'], d['ground_y'], m['before'], m['funnel_arriving'], m['mid_hit'], m['just_after'], m['settled'])" "$DIR/hit.txt")"
  Y1=$((GY-230)); Y25=$((GY-85))
  i=0
  for n in 1_before:$F1 2_funnel_arriving:$F2 3_mid_hit:$F3 4_just_after:$F4 5_settled:$F5; do
    say $J --replay "$DIR/hit.jsonl" --frames ${n##*:} --cam $CX,$Y1,1 --shade off $CLEAN --snapshot $OUT/real_${n%%:*}_z1.png
    say $J --replay "$DIR/hit.jsonl" --frames ${n##*:} --cam $CX,$Y25,2.5 --shade off $CLEAN --snapshot $OUT/real_${n%%:*}_z25.png
  done
  # ---- OI2: a new simulation loaded from the save of the settled world (paused, as the recording ends)
  say $J --replay "$DIR/reload.jsonl" --frames 4 --cam $CX,$Y1,1 --shade off $CLEAN --snapshot $OUT/real_6_reloaded_z1.png
  say $J --replay "$DIR/reload.jsonl" --frames 4 --cam $CX,$Y25,2.5 --shade off $CLEAN --snapshot $OUT/real_6_reloaded_z25.png
  python - "$OUT" "$GY" "$CX" "$DIR/hit.txt" <<'PYCMP'
import json, sys
from PIL import Image, ImageChops
out, gy, cx = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
town = json.load(open(sys.argv[4]))["town"]
for z, zoom in (("z1", 1.0), ("z25", 2.5)):
    a = Image.open(f"{out}/real_5_settled_{z}.png").convert("RGB"); b = Image.open(f"{out}/real_6_reloaded_{z}.png").convert("RGB")
    diff = ImageChops.difference(a, b)
    px = diff.load()
    # (the town on the screen: world x -> width / 2 + (x - camera x) * zoom)
    tx0, tx1 = a.width / 2 + (town[0] - cx) * zoom, a.width / 2 + (town[1] - cx) * zoom
    n = inside = worst = 0
    mask = Image.new("L", a.size, 0); mp = mask.load()
    for y in range(a.height):
        for x in range(a.width):
            v = max(px[x, y])
            if v:
                n += 1; worst = max(worst, v); mp[x, y] = 255
                if tx0 <= x <= tx1: inside += 1
    print(f"settled against reloaded, {z}: {n} of {a.width * a.height} px differ (largest difference {worst} of 255); {inside} of them inside the town's span (screen x {max(0, round(tx0))}..{min(a.width, round(tx1))})"
          + (f"; all inside the box {mask.getbbox()}" if n else " (pixel-identical)"))
    if n:
        vis = Image.blend(a, Image.new("RGB", a.size, (255, 255, 255)), 0.6); vis.paste((255, 0, 0), mask=mask); vis.save(f"{out}/real_7_reload_diff_{z}.png")
PYCMP
fi

if [ "$WHAT" = old ]; then
  # ---- a save written before Phase O (the simulation's own test fixture), loaded by the real simulation under this viewer
  say $J --load sim/tests/data/save_with_windsock_v2.sav --frames 20 --cam fit --shade off $CLEAN --snapshot $OUT/old_save_prephase_o.png
  # ---- a recorded v2.4 stream (flow-test 14's fixture), replayed
  say $J --replay viewer/snapshots/v241/fixtures/v24-real-city-end.jsonl --frames 4 --cam 2830,930,2.4 --shade off $CLEAN --snapshot $OUT/old_stream_v24_city.png
fi
rm -rf "$TMP"
ls "$OUT" | wc -l
