#!/bin/bash
# Regenerates the Phase O evidence pictures of the trees (TreePainter) into viewer/snapshots/v26/trees/.
# Run from the project root after:  javac -encoding UTF-8 -d viewer/out_trees viewer/src/precip/*.java
#   bash tools/snap_v26_trees.sh            everything
#   bash tools/snap_v26_trees.sh sheets     only the sheets drawn by precip.TreeTest (no game, no window)
#   bash tools/snap_v26_trees.sh world      only the pictures of the running game (built-in fake simulation)
# The sheets go through BuildingPainter.paintTree (the direct painter: tile bake, Debug line-up); the world pictures
# through the live pass (TreePainter.paintLive: one cached sprite per tree). Settings and saves are read from a
# throw-away folder that is removed again, never from the player's config/ and saves/.
set -u
# (OUT and CP can be given from outside: OUT=/some/scratch CP=viewer/out_fix bash tools/snap_v26_trees.sh sheets)
OUT="${OUT:-viewer/snapshots/v26/trees}"
CP="${CP:-viewer/out_trees}"

TMP="${TMP_DIR:-$OUT/.tmp}"
WHAT="${1:-all}"
mkdir -p "$OUT" "$TMP/cfg" "$TMP/saves"
T="java -cp $CP precip.TreeTest"
J="java -cp $CP precip.Main --config-dir $TMP/cfg --saves-dir $TMP/saves"
CLEAN="--hide panel,minimap,left,wind,arrows,climate,labels"
say() { "$@" 2>&1 | grep -E "sheet written|snapshot written|FAIL|Exception|unknown" | sed -E 's#[A-Za-z]:.*[\\/]trees[\\/]##' | cut -c1-160; }
# (2026-10-12: the storm ladder has more steps - cracked 0.38, hanging 0.50, lowered 0.60, snapped 0.85 = down)
ALL="dry,wind,wet,snow,bent,cracked,hanging,snapped,back"
BROKEN="bent,cracked,hanging,lowered,snapped,snappedwet,snappedsnow,snappedback"

if [ "$WHAT" = all ] || [ "$WHAT" = sheets ]; then
  # ---- every variant at zoom 0.5, 1, 2 and 4 (the four levels of detail side by side)
  for v in pine bush birch oak palm cactus acacia shrub dead; do say $T --lod $v $OUT/lod_$v.png; done
  # ---- every variant x whole / wind / wet / snow / bent / snapped / back row (haze 0.36 at 0.9 size), by day, at dusk and at night
  for l in day dusk night; do
    say $T --sheet $OUT/states_z05_$l.png --zoom 0.5 --light $l --rows $ALL
    say $T --sheet $OUT/states_z1_$l.png --zoom 1 --light $l --rows $ALL
    say $T --sheet $OUT/states_z2_${l}_a.png --zoom 2 --light $l --rows dry,wind,wet,snow
    say $T --sheet $OUT/states_z2_${l}_b.png --zoom 2 --light $l --rows bent,cracked,hanging,snapped,back
    # ---- the storm ladder alone, every variant: bent, cracked, hanging, nearly down, down (dry, wet, under snow, back row)
    say $T --sheet $OUT/broken_z1_$l.png --zoom 1 --light $l --rows $BROKEN
    say $T --sheet $OUT/broken_z2_$l.png --zoom 2 --light $l --rows $BROKEN
  done
  say $T --sheet $OUT/broken_z05_day.png --zoom 0.5 --light day --rows $BROKEN
  # ---- close up (zoom 4): one picture per variant with its seven states
  for v in pine bush birch oak palm cactus acacia shrub dead; do
    c=3; [ $v = acacia ] && c=2; [ $v = bush ] && c=4; [ $v = shrub ] && c=4
    say $T --sheet $OUT/z4_${v}_day.png --zoom 4 --variants $v --rows $ALL --cols $c
    # (the break close up: hanging, nearly down, down, down under snow)
    say $T --sheet $OUT/z4_break_${v}.png --zoom 4 --variants $v --rows hanging,lowered,snapped,snappedsnow --cols 2
  done
  say $T --sheet $OUT/z4_dusk_oak_pine_palm.png --zoom 4 --light dusk --variants oak,pine,palm --rows dry,wet,snow
  say $T --sheet $OUT/z4_night_oak_pine_palm.png --zoom 4 --light night --variants oak,pine,palm --rows dry,wet,snow
fi

if [ "$WHAT" = all ] || [ "$WHAT" = world ]; then
  # ---- the running game (live pass): the town's trees by day at zoom 1, 2 and 4, at night, and a palm coast
  say $J --fake --tod 0.45 --frames 60 --cam 1341,800,1 --shade off $CLEAN --snapshot $OUT/world_z1_day.png
  say $J --fake --tod 0.45 --frames 60 --cam 1341,850,2 --shade off $CLEAN --snapshot $OUT/world_z2_day.png
  say $J --fake --tod 0.45 --frames 60 --cam 1300,880,4 --shade off $CLEAN --snapshot $OUT/world_z4_day.png
  say $J --fake --tod 0.02 --frames 60 --cam 1341,850,2 --shade off $CLEAN --snapshot $OUT/world_z2_night.png
  say $J --fake --tod 0.76 --frames 60 --cam 1341,850,2 --shade off $CLEAN --snapshot $OUT/world_z2_dusk.png
  say $J --fake-scene biome:tropical --tod 0.45 --frames 60 --cam 342,850,2 --shade off $CLEAN --snapshot $OUT/world_tropical_z2.png
  say $J --fake-scene biome:desert --tod 0.45 --frames 60 --cam 120,850,2 --shade off $CLEAN --snapshot $OUT/world_desert_z2.png
  say $J --fake --tod 0.45 --frames 60 --cam 1341,700,0.45 --shade off $CLEAN --snapshot $OUT/world_z045_baked.png
  # ---- the Debug mode's line-up (every plant intact and at 60 % storm damage)
  say $J --ui-snapshot debug-buildings:scroll=9999 $OUT/debug_lineup_day.png --size 1680x900
  say $J --ui-snapshot debug-buildings:scroll=9999,light=night,snow=0.6 $OUT/debug_lineup_night_snow.png --size 1680x900
fi
rm -rf "$TMP"
ls "$OUT" | wc -l
