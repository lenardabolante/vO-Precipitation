#!/bin/bash
# Regenerates the v2.3 look-check pictures taken from the REAL simulation into viewer/snapshots/v23/real/.
# Run from the project root after:  javac -encoding UTF-8 -d viewer/out_sandbox viewer/src/precip/*.java
#   bash tools/snap_v23_real.sh            all groups
#   bash tools/snap_v23_real.sh storm      one group: storm | merge | cars | damage | climate | tour
# The simulation is driven with --cmd (spawn a supercell, ask for tornadoes), run to a condition with --until,
# then the clock is put at midday with --then so the picture is in daylight (a simulated day is only 300 s).
set -u
OUT="viewer/snapshots/v23/real"
mkdir -p "$OUT"
J="java -cp viewer/out_sandbox precip.Main"
CLEAN="--hide panel,minimap,left,wind,arrows,climate"
WHAT="${1:-all}"
say() { "$@" 2>&1 | grep -E "snapshot written|camera on|reached|FAIL|Exception" | sed -E 's#C:.*[\\/]real[\\/]##' | cut -c1-330; }
want() { [ "$WHAT" = all ] || [ "$WHAT" = "$1" ]; }
# for conditions that depend on chance in the simulation: run again (up to 5 times) while --until was not reached
retry() { for k in 1 2 3 4 5; do o=$(say "$@"); echo "$o"; echo "$o" | grep -q "NOT reached" || return 0; echo "   (try $k did not get there - again)"; done; }

DRY='{"cmd":"gas","name":"humidity","value":0.2}'
NOON='{"cmd":"hour","value":13}'
# a storm over open country (seed 21): the supercell, and a tornado asked for every 4 s until the storm grants one
storm() { say $J --seed 21 --hour 9 --cmd "$DRY" --cmd '{"cmd":"spawn","x":1500,"y":300,"type":"supercell"}' --cmd '@every:4:{"cmd":"tornado"}' "$@"; }
# the same storm put upwind of the town's streets (531..1368), so the tornado crosses houses
town() { say $J --seed 21 --hour 9 --cmd "$DRY" --cmd '{"cmd":"spawn","x":1050,"y":300,"type":"supercell"}' --cmd '@every:4:{"cmd":"tornado"}' "$@"; }
# ... asking only during the first minute, so no second funnel comes down while the damage is being looked at
town1() { say $J --seed 21 --hour 9 --cmd "$DRY" --cmd '{"cmd":"spawn","x":1050,"y":300,"type":"supercell"}' --cmd '@32:{"cmd":"tornado"}' --cmd '@38:{"cmd":"tornado"}' --cmd '@44:{"cmd":"tornado"}' --cmd '@50:{"cmd":"tornado"}' --cmd '@56:{"cmd":"tornado"}' --cmd '@62:{"cmd":"tornado"}' "$@"; }

if want storm; then
  storm --until tornado:mature+4 --then "$NOON" --then-frames 90 --cam cloud:supercell --shade off $CLEAN,labels --snapshot $OUT/supercell_wide_day.png &
  storm --until tornado:mature+4 --then "$NOON" --then-frames 90 --cam cloud:supercell,1.5 --shade off $CLEAN,labels --snapshot $OUT/supercell_close_day.png &
  storm --until tornado:funnel+3 --then "$NOON" --then-frames 45 --cam 1460,640,1.3 --shade off $CLEAN --snapshot $OUT/tornado_1_funnel.png &
  wait
  storm --until tornado:mature+6 --then "$NOON" --then-frames 60 --cam tornado,1.2 --shade off $CLEAN --snapshot $OUT/tornado_2_mature.png &
  storm --until tornado:roping+8 --then "$NOON" --then-frames 20 --cam tornado,1.2 --shade off $CLEAN --snapshot $OUT/tornado_3_roping.png &
  storm --until tornado:mature+6 --cam tornado,0.8 --hide wind,arrows --snapshot $OUT/tornado_4_full_hud_under_storm.png &
  # the underside: wall cloud at meso_x with the funnel, the clear slot behind it and the thin rain curtain of the hook
  storm --until tornado:mature+4 --then "$NOON" --then-frames 60 --cam 1500,600,1.7 --shade off $CLEAN,labels --snapshot $OUT/supercell_wall_cloud_hook_clear_slot.png &
  wait
fi

if want merge; then
  # stratus sheets put where cumulus clouds are growing. Two overlapping clouds merge by chance (a hazard per
  # second), so eight pairs are set out and the picture follows the first heap that takes a sheet in.
  # Which cloud that is differs from run to run (it may be a cumulus or a cumulonimbus): check the pictures.
  MC=""
  for i in 0 1 2 3 4 5 6 7; do
    x=$((260 + 460 * i))
    MC="$MC --cmd {\"cmd\":\"spawn\",\"x\":$x,\"y\":340,\"type\":\"cumulus\"} --cmd @5:{\"cmd\":\"spawn\",\"x\":$((x + 110)),\"y\":345,\"type\":\"stratus\"}"
  done
  retry $J --seed 21 --hour 8 $MC --until flatband:0.35 --then "$NOON" --then-frames 75 --cam cloud:flatband,1.4 --shade off --hide panel,minimap,left,wind,arrows,climate --snapshot $OUT/merge_sheet_into_cumulus_1_during.png &
  retry $J --seed 21 --hour 8 $MC --until flatband:1+3 --then "$NOON" --then-frames 75 --cam cloud:flatband,1.4 --shade off --hide panel,minimap,left,wind,arrows,climate --snapshot $OUT/merge_sheet_into_cumulus_2_after.png &
  retry $J --seed 21 --hour 8 $MC --until flatband:1+3 --then "$NOON" --then-frames 75 --cam cloud:flatband,2.4 --shade off $CLEAN,labels --snapshot $OUT/merge_sheet_into_cumulus_3_close.png &
  wait
fi

if want cars; then
  say $J --seed 21 --hour 12 --until t:14 --cam car:any,2.6 --shade off $CLEAN --snapshot $OUT/cars_day_close.png &
  say $J --seed 21 --hour 12 --until t:14 --cam 950,900,1.3 --shade off $CLEAN --snapshot $OUT/cars_day_street.png &
  RAIN='{"cmd":"spawn","x":900,"y":420,"type":"nimbostratus"}'
  say $J --seed 21 --hour 23 --cmd "$RAIN" --cmd '{"cmd":"gas","name":"humidity","value":0.9}' --until t:60 --then '{"cmd":"hour","value":23}' --then-frames 30 --cam car:lights,2.6 $CLEAN --snapshot $OUT/cars_night_rain_close.png &
  wait
  say $J --seed 21 --hour 23 --cmd "$RAIN" --cmd '{"cmd":"gas","name":"humidity","value":0.9}' --until t:60 --then '{"cmd":"hour","value":23}' --then-frames 30 --cam 950,900,1.3 $CLEAN --snapshot $OUT/cars_night_rain_street.png
fi

if want damage; then
  # (a rope-out lasts at most 21 s, so 24 s after it began the tornado is gone and the damage stands in the open)
  town1 --until tornado:roping+24 --then "$NOON" --then-frames 60 --cam damage:house,1.7 --shade off $CLEAN --snapshot $OUT/damage_after_tornado_houses.png &
  town1 --until tornado:roping+24 --then "$NOON" --then-frames 60 --cam damage:house,3.2 --shade off $CLEAN,labels --snapshot $OUT/damage_after_tornado_house_close.png &
  town --until tornado:roping+1 --then "$NOON" --then-frames 60 --cam damage:any,1.0 --shade off --hide wind,arrows,climate --snapshot $OUT/damage_during_tornado_wide_hud.png &
  wait
  town --until car:tossed+0.4 --cam car:tossed,2.2 --shade off $CLEAN --snapshot $OUT/car_tossed_by_tornado.png &
  wait
fi

if want climate; then
  for m in 1 7; do
    for h in 12 0; do
      n=$([ $m = 1 ] && echo january || echo july); d=$([ $h = 12 ] && echo noon || echo night)
      say $J --preset oklahoma --seed 5 --month $m --hour $h --frames 240 --hide wind,arrows --snapshot $OUT/oklahoma_${n}_${d}_climate_panel.png &
    done
  done
  wait
fi

if want tour; then
  T="$J --mode showcase --cloud-type supercell"
  say $T --until step:mature+2 --snapshot $OUT/showcase_supercell_1_mature.png &
  say $T --until step:wall_cloud+3 --snapshot $OUT/showcase_supercell_2_wall_cloud.png &
  say $T --until step:tornado+4 --snapshot $OUT/showcase_supercell_3_tornado.png &
  wait
fi
