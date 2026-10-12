#!/bin/bash
# Regenerates the preset-biome pictures (real renders of the game) into viewer/snapshots/v23/presets/.
# Run from the project root after:  javac -encoding UTF-8 -d viewer/out_sandbox viewer/src/precip/*.java
#   bash tools/snap_v23_presets.sh          everything
#   bash tools/snap_v23_presets.sh fake     the eight biomes from the built-in fake simulation (no Python needed)
#   bash tools/snap_v23_presets.sh real     all 21 presets from the real simulation: start month, mid-morning, fit view
#   bash tools/snap_v23_presets.sh close    close-ups of six presets in a contrasting season
set -u
OUT="viewer/snapshots/v23/presets"
mkdir -p "$OUT"
J="java -cp viewer/out_sandbox precip.Main"
CLEAN="--hide panel,left,minimap,climate,wind,arrows"
WHAT="${1:-all}"
say() { "$@" 2>&1 | grep -E "snapshot written|FAIL|Exception|unknown|no object|no settlement" | sed -E 's#C:.*[\\/]presets[\\/]##' | cut -c1-150; }
want() { [ "$WHAT" = all ] || [ "$WHAT" = "$1" ]; }

if want fake; then
  for b in temperate mediterranean tropical savanna desert alpine tundra polar; do
    say $J --fake-scene biome:$b --hour 10 --frames 200 $CLEAN --snapshot $OUT/fake_biome_$b.png &
  done
  wait
  # the new trees and houses close up
  say $J --fake-scene biome:desert --hour 10 --frames 120 --cam obj:cactus,3.0 --shade off $CLEAN,labels --snapshot $OUT/fake_close_desert_cactus_palm_adobe.png &
  say $J --fake-scene biome:tropical --hour 10 --frames 120 --cam obj:palm,2.6 --shade off $CLEAN,labels --snapshot $OUT/fake_close_tropical_palm_hut.png &
  say $J --fake-scene biome:savanna --hour 10 --frames 120 --cam obj:acacia,3.0 --shade off $CLEAN,labels --snapshot $OUT/fake_close_savanna_acacia_hut.png &
  say $J --fake-scene biome:polar --hour 10 --frames 120 --cam obj:station,3.0 --shade off $CLEAN,labels --snapshot $OUT/fake_close_polar_station.png &
  wait
fi

if want real; then
  n=0
  for p in washington philippines tibet russia united_kingdom central_europe oklahoma florida colorado california alaska hawaii japan meghalaya \
           amazon atacama sahara kenya australia new_zealand antarctica; do
    say $J --preset $p --seed 5 --hour 10 --frames 150 --cam fit $CLEAN --snapshot $OUT/$p.png &
    n=$((n + 1)); [ $((n % 5)) = 0 ] && wait
  done
  wait
  # one with the whole HUD: the region card names the biome
  say $J --preset sahara --seed 5 --hour 10 --frames 150 --hide wind,arrows --snapshot $OUT/sahara_hud_region_card.png
fi

if want close; then
  C="--seed 5 --hour 10 --frames 150 $CLEAN"
  say $J --preset sahara --month 1 $C --cam settlement:town,1.5 --snapshot $OUT/sahara_closeup_january.png &
  say $J --preset antarctica --month 6 $C --cam settlement:hamlet,2.0 --snapshot $OUT/antarctica_closeup_june.png &
  say $J --preset hawaii --month 8 $C --cam obj:palm,1.7 --snapshot $OUT/hawaii_closeup_august.png &
  wait
  say $J --preset kenya --month 9 $C --cam obj:acacia,1.8 --snapshot $OUT/kenya_closeup_september.png &
  say $J --preset amazon --month 9 $C --cam obj:hut,1.8 --snapshot $OUT/amazon_closeup_september.png &
  say $J --preset alaska --month 7 $C --cam settlement:city,1.2 --snapshot $OUT/alaska_closeup_july.png &
  wait
fi
