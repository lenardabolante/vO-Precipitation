#!/bin/bash
# Regenerates the v2.3 sandbox evidence pictures (built-in fake simulation) into viewer/snapshots/v23/sandbox/.
# Run from the project root after:  javac -encoding UTF-8 -d viewer/out_sandbox viewer/src/precip/*.java
# (car_variants_sheet.png was made by a throw-away harness and is not regenerated here.)
set -u
OUT="viewer/snapshots/v23/sandbox"
CFG="$OUT/.cfg"                # a throw-away settings folder (made and removed here), so the player's own key bindings do not show in the pictures
mkdir -p "$OUT"
J="java -cp viewer/out_sandbox precip.Main"
CLEAN="--hide panel,minimap,left,wind,arrows,climate"
say() { "$@" 2>&1 | grep -E "snapshot|written|camera on|reached" | sed -E 's#C:.*[\\/]sandbox[\\/]##' | cut -c1-200; }

# ---- merging: a stratus sheet absorbed by a cumulus (the flat band), and two cumulus becoming one
say $J --fake-scene merge --frames 45  --cam 1150,395,1.05 --hide panel,minimap,left,wind,arrows --snapshot $OUT/merge_1_before.png
say $J --fake-scene merge --until merge:0.45 --cam cloud:merged,1.15 --hide panel,minimap,left,wind,arrows --snapshot $OUT/merge_2_during.png
say $J --fake-scene merge --until merge:1+4 --cam cloud:merged,1.15 --hide panel,minimap,left,wind,arrows --snapshot $OUT/merge_3_after_flat_band.png
say $J --fake-scene merge --until merge:1+4 --cam cloud:merged,2.3 $CLEAN,labels --snapshot $OUT/merge_4_flat_band_close.png
say $J --fake-scene merge --until t:46 --cam 2330,400,1.15 $CLEAN --snapshot $OUT/merge_5_two_cumulus.png
say $J --fake-scene merge --until t:20 --cam 2440,400,1.0 $CLEAN --snapshot $OUT/merge_5a_two_cumulus_before.png
say $J --fake-scene merge --until t:46 --select 2 --cam 1700,430,0.80 --hide wind,arrows,climate --snapshot $OUT/merge_6_timeline_and_card.png

# ---- supercell: wall cloud, anvil, overshooting top
say $J --fake-scene supercell --frames 560 --cam cloud:supercell --shade off $CLEAN --snapshot $OUT/supercell_1_wide.png
say $J --fake-scene supercell --frames 560 --cam 640,330,1.25 --shade off $CLEAN --snapshot $OUT/supercell_2_close.png

# ---- tornado: funnel, touchdown, mature, roping out
say $J --fake-scene supercell --until tornado:funnel+4.5 --cam tornado,1.6 --shade off $CLEAN --snapshot $OUT/tornado_1_funnel.png
say $J --fake-scene supercell --until tornado:touchdown+2 --cam tornado $CLEAN --snapshot $OUT/tornado_2_touchdown.png
say $J --fake-scene supercell --until tornado:mature+10 --cam tornado $CLEAN --snapshot $OUT/tornado_3_mature.png
say $J --fake-scene supercell --until tornado:roping+5 --cam tornado $CLEAN --snapshot $OUT/tornado_4_roping.png
say $J --fake-scene supercell --until tornado:mature+13.6 --cam 880,560,0.80 --hide wind,arrows --snapshot $OUT/tornado_5_full_hud.png

# ---- cars and damage
say $J --fake-scene supercell --until car:tossed+0.5 --cam car:tossed,2.6 --shade off $CLEAN --snapshot $OUT/car_1_tossed.png
say $J --fake-scene supercell --until tornado:roping+9 --cam car:wrecked,3.4 --shade off $CLEAN --snapshot $OUT/car_2_wrecked.png
say $J --fake-scene supercell --until tornado:roping+9 --cam 900,868,1.7 --shade off $CLEAN --snapshot $OUT/damage_street.png
say $J --fake-scene cars --tod 0.92 --frames 500 --cam 2000,860,1.5 $CLEAN --snapshot $OUT/cars_night_rain.png
say $J --fake-scene cars --tod 0.92 --frames 500 --cam car:lights,3.2 $CLEAN --snapshot $OUT/cars_night_rain_close.png
say $J --fake-scene cars --tod 0.45 --frames 400 --cam car:any,3.0 --shade off $CLEAN --snapshot $OUT/cars_day_variants.png

# ---- climate: the same scene in winter and summer, by day and by night, with the Climate panel
SEASON="--frames 700 --cam 1500,560,0.75 --hide panel,minimap,wind,arrows"
say $J --fake --month 1.5 --hour 9.5 $SEASON --snapshot $OUT/season_winter_day.png
say $J --fake --month 1.5 --hour 2   $SEASON --snapshot $OUT/season_winter_night.png
say $J --fake --month 7.5 --hour 14  $SEASON --snapshot $OUT/season_summer_day.png
say $J --fake --month 7.5 --hour 2   $SEASON --snapshot $OUT/season_summer_night.png
say $J --fake --month 7.5 --hour 14 --cmd '{"cmd":"gas","name":"humidity","value":0.95}' --cmd '{"cmd":"gas","name":"co2","value":840}' --cmd '{"cmd":"gas","name":"aerosol","value":0.8}' $SEASON --snapshot $OUT/climate_humid_co2_doubled.png

# ---- screens (the app shell paints these exactly as the window does)
U="$J --config-dir $CFG"
say $U --mode showcase --fake --cloud-type supercell --until step:wall_cloud+6 --snapshot $OUT/showcase_supercell_1_wall_cloud.png
say $U --mode showcase --fake --cloud-type supercell --until step:tornado+8 --snapshot $OUT/showcase_supercell_2_tornado.png
say $U --mode showcase --fake --cloud-type supercell --until step:rope_out+4 --snapshot $OUT/showcase_supercell_3_rope_out.png
say $U --mode showcase --fake --cloud-type supercell --until step:tornado+8 --size 1040x581 --snapshot $OUT/showcase_supercell_min_window.png
say $U --ui-snapshot new-month $OUT/new_simulation_month_time.png
say $U --ui-snapshot new-month $OUT/new_simulation_month_time_min_window.png --size 1040x581
say $U --ui-snapshot game-help $OUT/help_overlay_new_keys.png
say $U --ui-snapshot settings-controls $OUT/settings_controls_42_actions.png
say $U --ui-snapshot game-storm $OUT/game_hud_storm.png
say $U --ui-snapshot game $OUT/game_hud_default.png
say $U --ui-snapshot game $OUT/game_hud_1280.png --size 1280x720
rm -rf "$OUT/.cfg"
ls "$OUT" | wc -l
