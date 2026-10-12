# Cloud Formations mode — feature test report

Tested 2026-10-09 on the real Python sim driven through the Java viewer (compiled to `viewer/out_test_cf`).
All pictures are real viewer renders at seed 11 unless stated; files starting `fake_` use the built-in fake source. Control pictures (21–30) come from the input drive, which starts the sim with a random seed.
Stage pictures are separate runs of the same seed (same named cloud, e.g. Cu-0001 "Rolling Curtain"), run at x8 to reach the stage.

Result counts: **PASS 27, FIXED 7, FAIL (not fixed) 1, NOT TESTED 2** (37 rows). Three more defects in other areas' files are listed under "not fixed" without a row of their own.

## Measured stage durations (sim seconds, x1, 2000x900 stage, 3 cycles each of seed 11 and seed 3 = 6 cycles per type)

| Type | Forming | Growing | Mature | Precipitating | Dissipating | Whole life | Rain share of life | Peak / mean rate | Pause |
|---|---|---|---|---|---|---|---|---|---|
| cumulus | 3.6-4.5 | 13.2-16.5 | 12.6-16.6 | 29.1-38.1 | 17.1-22.0 | 75.6-97.7 | 35-39 % | 0.10-0.15 / 0.06-0.09 | 4.03 |
| cumulonimbus | 5.4 | 19.8 | 18.9-19.9 | 37.8-48.1 | 23.9-27.1 | 106.1-120.3 | 35-40 % | 0.38-0.50 / 0.23-0.30 | 4.03 |
| nimbostratus | 6.0 | 22.0 | 21.0-22.1 | 63.2-85.3 | 32.9-39.4 | 146.0-174.7 | 43-49 % | 0.19-0.25 / 0.16-0.21 | 4.03 |
| stratocumulus | 3.9-4.5 | 14.1-16.5 | 14.1-16.6 | 31.5-40.0 | 18.8-22.6 | 83.1-100.2 | 35-40 % | 0.07-0.10 / 0.06-0.08 | 4.03 |
| altostratus | 4.8 | 17.6 | 16.8-17.7 | 35.3-45.0 | 21.8-24.8 | 96.6-109.8 | 36-41 % | 0.11-0.16 / 0.09-0.12 | 4.03 |
| altocumulus | 4.5-9.3 | 12.4-15.2 | 40.4-96.4 | never | 16.7-34.7 | 74.5-155.6 | 0 % | 0 | 4.03 |
| stratus | 4.5 | 16.5 | 15.7-16.6 | 31.7-40.0 | 20.0-22.6 | 88.6-100.2 | 36-40 % | 0.05-0.07 / 0.04-0.06 | 4.03 |
| cirrus | 6.3-12.8 | 10.4-20.9 | 65.6-132.8 | never | 23.7-47.8 | 106.0-214.3 | 0 % | 0 | 4.03 |
| supercell (engine stages) | 6.6 | 18.7-22.4 | 4.2-6.1 | 82.6-85.2 | 30.5-31.9 | 143.4-150.0 | 55-58 % | 0.15-0.18 / 0.11 | 4.03 |

Supercell tour steps (6 cycles): towering 20.0, mesocyclone 10.0-13.7, mature 12.9, wall_cloud 10.0, funnel 13.6-16.9, tornado 27.4-28.6, rope_out 13.7-17.7, decay 33.3-34.7. One tornado id per cycle in all 6, stages funnel, touchdown, mature (EF3, 62-68 m/s), roping.

Speed x8 (seed 11, 3 cycles per type) gives the same durations within 1 s (e.g. nimbostratus rain 63.2 / 85.3 / 78.9 s at both speeds); pause 4.27 s; still one tornado per cycle.
The "rains 9 s of a 100 s life" class of defect was not found on any tab.

## Features

| # | Feature | Code (file:function) | What it does | How tested | Picture | Result |
|---|---|---|---|---|---|---|
| 1 | Tab list from the sim | `showcase.py:hello_message`, `ShowcaseScreen.readHello` | Hello carries 9 types, a lesson per type, one tour (supercell); the screen builds its tabs from it | Real process hello read (`proc_cmds.py`), input drive check 1 | all pictures (tab bar) | PASS: types = cumulus, cumulonimbus, nimbostratus, stratocumulus, altostratus, altocumulus, stratus, cirrus, supercell; 9 lessons |
| 2 | Cumulus tab, whole life | `showcase.py:_spawn_stage_cloud/_govern_rain` | One cumulus forms, grows, matures, showers, dissipates, 4 s pause, new cloud | 6 cycles measured; 6 pictures opened | `02_cumulus_a_forming` … `02_cumulus_f_pause` | PASS: all 5 stages, rain 29-38 s = 35-39 % of life, type stays cumulus (never towers), base at 28-44 % of stage height |
| 3 | Cumulonimbus tab | same | Tower with anvil, heavy burst with a peak, lightning | 6 cycles; 6 pictures opened | `03_cumulonimbus_a..f` | PASS: peak 0.38-0.50 (heaviest of all tabs), 306-527 lightning frames per cycle, anvil 0.77-0.88, top reaches 9 % of stage height |
| 4 | Nimbostratus tab | same | Deep grey layer, longest and steadiest rain | 6 cycles; 6 pictures opened | `04_nimbostratus_a..f` | PASS: rain 63-85 s (longest), peak/mean 1.15-1.18 (steady) |
| 5 | Stratocumulus tab | same | Low lumpy cloud, light drizzle | 6 cycles; 6 pictures opened | `05_stratocumulus_a..f` | PASS: peak 0.07-0.10, rain 31-40 s |
| 6 | Altostratus tab | same | Mid-level sheet, light precipitation | 6 cycles; 6 pictures opened | `06_altostratus_a..f` | PASS: base at 12-26 % of stage height, peak 0.11-0.16 |
| 7 | Altocumulus tab | same; stays `fair` | Mid-level cloudlets, never precipitates | 6 cycles; 3 of 5 pictures opened | `07_altocumulus_a,b,c,e,f` | PASS: no precipitating stage, 0 particles, 0 mm; strip says "skipped — this cloud never did", Precip "none (fair)" |
| 8 | Stratus tab | same | Low flat sheet, weakest drizzle | 6 cycles; 3 of 6 pictures opened | `08_stratus_a..f` | PASS: lowest base (36-47 %), peak 0.05-0.07 (lightest) |
| 9 | Cirrus tab | same; stays `fair` | High ice streaks, never precipitates | 6 cycles; 4 of 5 pictures opened | `09_cirrus_a,b,c,e,f` | PASS: base at 9-17 % (highest), no precipitation, ice 0.29 vs water 0.03 |
| 10 | Pause between cycles | `showcase.py:_maybe_spawn_cloud` (PAUSE_S 4) | Empty stage for 4 s, amber "the cloud is gone" header, meters show dashes, then cycle N+1 with a new unique cloud | Pause measured on every type; pictures | `0N_*_f_pause`, `11_supercell_tour_i_pause` | PASS: 4.03-4.04 s (4.27 at x8), never more than 1 cloud on stage in 54 measured cycles |
| 11 | Supercell lifetime tour | `showcase.py:_govern_supercell/_tour_tick/_update_tornadoes`, `ShowcaseScreen.tourStrip` | Eight steps in order, each lit with its text, rotation / wall meters, red tornado chip, exactly one tornado | 6 cycles measured; all 9 pictures opened | `11_supercell_tour_a_towering` … `11_supercell_tour_i_pause` | PASS: 8 steps in order in 6/6 cycles, 1 tornado each, every step >= 10 s; chip reads "FUNNEL 61 % down", "TORNADO EF3 · 65 m/s · mature", "TORNADO lifting" |
| 12 | Tour chip titles | `ShowcaseScreen.tourStrip` | Chips name the steps | Picture at 1680x760 | `12_tour_chips_before_1680x760_mature_sup_cut`, `12_tour_chips_after_1680x760` | FIXED: "3  Mature sup…" was cut at the default size although the strip had room; chips are now sized by their titles |
| 13 | Life bar / "% of its life" (PLAN open question 14/23) | `showcase.py:_build_frame/_stage_whole_life`, `ShowcaseScreen.lifeStrip/tourStrip` | Bar and "N s old of about M s" from the frame's `life_frac` / `lifetime` | Measured per frame over 3 cycles x 9 types before and after; pictures at the same instant | `13_lifebar_before_nimbostratus_held_at_77pct_105s`, `13_lifebar_before_supercell_decay_stuck_at_90pct`, `13_lifebar_after_nimbostratus_raining_105s`, `13_lifebar_after_supercell_decay` | FIXED: before, the bar stood at 77 % for 12-59 s of the rain while "of about" grew 100 -> 175 s; supercell stood at 48 % for ~60 s, jumped to 90 % in 3.5 s and vanished at 90 %. After: steady 0 -> 98-100 %, longest stall 3.1 s, reported length within 1.5 s of the real life |
| 14 | Five-stage strip at small sizes | `ShowcaseScreen.lifeStrip` | Five stage boxes with "began at / lasted" lines | Pictures at 1680x760, 1280x720, 1040x620 | `14_stage_strip_before_1040x620_titles_cut`, `14_stage_strip_after_1040x620`, `14_stage_strip_after_1280x720` | FIXED: at 1040x620 titles read "4  Precipit…", "5  Dissipat…" and lines "began at 0…", "← the cloud…"; box bottoms also ran through the "born / gone" text at every size. Now full titles, short line forms, 5 px clear |
| 15 | Lesson text and facts panel | `showcase.py:LESSONS/TOURS`, `ShowcaseScreen.lessonPanel` | Title, altitude line, five parts per type; smaller font then scroll bar when it does not fit | Every line of 9 lessons and 8 tour texts read in the source; fit checked in pictures at three sizes | `15_lesson_fit_1040x620_*` (9 opened), `15_lesson_fit_1280x720_*` (2 of 9 opened), `15_tour_text_before_1280x720_cut` | FIXED (one item): no spelling or factual error found; at 1680x760 all nine fit; at 1280x720 the supercell lesson gets the scroll bar and cumulonimbus fits (the other seven not opened at that size); at 1040x620 cumulonimbus and supercell get the scroll bar, the rest fit. The tornado step text was cut ("… travel a …") at 1280x720; it now takes the status line's row when it needs it |
| 16 | Precipitation shown versus the lesson | `clouds.py:update_cloud` (engine), `showcase.py:RAIN_PLAN` | Rate and type per tab | precip types and particle kinds counted per cycle | `0N_*_d_precipitating` | PASS with a note: who rains and how hard matches the lessons (Cb heaviest, Ns longest, St/Sc drizzle, Ci/Ac none). Every raining tab reports type "mixed" and what is drawn falling is mostly snowflakes that melt near the ground (see questionable 1) |
| 17 | Wind and streamlines | `showcase.py:_reset` (3 m/s, 30 % gusts), `ShowcaseScreen.style` | Streamlines, six wind labels, updraft / growth / fall arrows | Pictures; `wind` command +7 then -14 m/s | `17_wind_default_3ms`, `17_wind_command_plus7`, `17_wind_command_leftward` | PASS: labels 3.0-4.1 m/s by default; a +4 command takes the mean from 2.9 to 6.7 m/s; reversed, arrows and labels point left at 4.5-5.2 m/s |
| 18 | Under-cloud shade | `ShowcaseScreen.style` (`r.shadeOn = false`) | Deliberately off: "a lesson: the stage stays evenly lit" | Code read; no darkening under any cloud in the pictures | `03_cumulonimbus_d_precipitating` | PASS (off by design, see questionable 4) |
| 19 | Day lighting | `showcase.py:_reset` (`hold_clock` month 5.8, hour 15) | Fixed late-spring afternoon, no day or season drift | `time_of_day` 0.625 and month 5.8 read from a frame at t = 1 s; the sun sits in the same place in pictures up to t = 160 s | every picture | PASS |
| 20 | Tab keys 1-9 and tab clicks | `ShowcaseScreen.key/pick`, `Ui.seg` | Send `showcase type`; new cloud of that type, cycle 1 | Synthetic keys and clicks on the real screen + real sim (input drive checks 3-16) | `28_after_91_rapid_tab_keys_stratus` | PASS: 9/9 keys and 5/5 clicks, new tab on stage in 0.5-1.0 s |
| 21 | Pause / resume (Space, P, button) | `ShowcaseScreen.togglePause` | Time stops, frames keep coming, button turns into a blue "Resume" | Input drive 21-23, process test | `21_control_paused_resume_button`, `21_control_tab_switched_while_paused` | PASS: t stays fixed over 40 painted frames; a tab switch while paused is taken and the cloud forms after resume |
| 22 | Speed (=, -, numpad +, buttons) | `ShowcaseScreen.changeSpeed` | Steps 0.25, 0.5, 1, 2, 4, 8 | Input drive 24-27; process test with 100, 0.001, -5 | `22_control_speed_x8` | PASS: clamps at 8 and 0.25; bad values clamp or are ignored |
| 23 | Next / previous (Left, Right, Tab) | `ShowcaseScreen.step` | Neighbouring tab with wrap-around | Input drive 17-20 | — | PASS: Left on cumulus -> supercell, Right on supercell -> cumulus |
| 24 | Lesson scroll (wheel) | `ShowcaseScreen.wheel/lessonPanel` | Scrolls the lesson when it is longer than the panel | Wheel events at 1040x620 on the supercell tab | `24_lesson_scroll_1040x620_top`, `24_lesson_scroll_1040x620_scrolled` | PASS: 7254 sampled panel pixels changed, last part "HOW IT ENDS" reachable |
| 25 | Restart (R, button) | `showcase.py:_cmd_showcase` | Drops the cloud at once, same type, cycle 1 | Input drive 28-29, process test | — | PASS: new cloud id, cycle 1, age < 2 s |
| 26 | F12 screenshot | `AppShell.screenshot` | Writes what is on screen to `screenshots/` and toasts the name | F12 through the real key path | `26_f12_screenshot_as_written_by_the_game` | PASS: PNG written, toast "Screenshot saved: screenshots/precipitation-….png" (file moved here, folder removed) |
| 27 | Commands with good and bad values | `showcase.py:handle_command`, `simulation.py:handle_command` | `showcase`, `pause`, `speed`, `wind`, `temp`, `view`, `quit` work; `spawn`, `reseed`, `tornado` ignored | 30 checks against the real process over stdin, incl. 21 bad commands and non-JSON lines | `27_temp_command_cold_stage_snow` | PASS: 30/30, process alive, same cloud id after the bad ones; at -4 °C snow lies on the ground |
| 28 | Rapid tab switching leaves nothing behind | `showcase.py:_cmd_showcase` | Clears clouds, particles, debris, tornadoes, lightning | 91 keys in a row; 301 commands in one burst; leaving a mature EF3 tornado | `28_supercell_tornado_before_leaving_tab`, `28_cirrus_right_after_leaving_tornado_no_leftovers` | PASS: first frame after leaving the tornado (424 particles, 94 debris, 1 tornado) has 0 tornadoes, 0 particles, 1 cloud of the new type |
| 29 | `save` is refused | `showcase.py:_cmd_save`, `GameSession.onSavedReply` | Sim answers `saved` ok:false; the mode session has no slot | Command sent through the viewer session and to the process | — | PASS: `{"ok":false,"error":"saving is not available in showcase mode"}`, no file, no error screen, frames keep coming |
| 30 | Last tab remembered in settings | `Settings.java`, `MainMenuScreen.cloudType`, `AppShell.showMenu` | Should keep the last tab (PLAN open question 8) | Esc from the supercell tab, then Enter in the menu | `30_menu_after_esc_cumulus_preselected_again` | FAIL (not fixed): menu preselects Cumulus again, Enter restarts on cumulus, no `settings.json` written; no field exists for it |
| 31 | `--fake` source | `FakeSim.showcase` | Built-in fake cloud with placeholder lesson text | 4 fake snapshots | `fake_31_cumulus_precipitating`, `fake_31_supercell_tornado` (opened), `fake_31_cumulonimbus_mature`, `fake_31_cirrus` | PASS: both sources render; fake shows "[Fake simulation: placeholder text]" and its own tour titles |
| 32 | Esc and Menu button | `ShowcaseScreen.key`, `AppShell.leaveNow` | Back to the main menu, Cloud Formations still selected, sim stopped | Input drive 38, 41 | `30_menu_after_esc_cumulus_preselected_again` | PASS: session null, panel disposed, sim process logs "exiting" |
| 33 | `--until-stage` snapshot flag | `ModeSnapshot.showcase` (SHARED) | Runs until the cloud is in a stage | Used for every stage picture | — | FIXED: `--until-stage forming` could never be reached (it waited 7 % of the lifetime inside a stage that lasts 6 %), sat in the 150 s timeout and pictured cycle 14 "growing"; same for the supercell's 5 s "mature". Now reached at t = 3.3 s |
| 34 | Precip meter after the rain | `ShowcaseScreen.lifeStrip/tourStrip`; root cause `clouds.py:1473` | Shows type and rate | Driven at x1 into "dissipating" | `34_precip_meter_before_mixed_0.02_while_dissipating_x1`, `34_precip_meter_after_none_while_dissipating_x1` | FIXED in the screen: it read "mixed 0.02" for the whole dissipating stage with 0 particles and 0.00 water / ice; now "none". The engine cause is not fixed (not-fixed 1) |
| 35 | Sim start line | `precip_sim.py:main` (SHARED) | Logs how the sim started | stderr read | — | FIXED: showcase said "terrain=rolling settlements=normal" for a flat stage with no settlements; now "terrain=flat settlements=none" |
| 36 | Cumulus towering into cumulonimbus | `showcase.py:_spawn_stage_cloud` (`from_type = ""`) | Not shown in this mode: the forced type holds | Code read; one type per cycle in 54 cycles | — | NOT TESTED as a feature: switched off here by design |
| 37 | Speeds 0.25, 0.5, 2, 4 over whole cycles | `simulation.py:step` (dt = dt_real x speed) | Larger time step at higher speed | Only x1 and x8 measured over full cycles | — | NOT TESTED: the 0.25 run was stopped for time; 0.5, 2 and 4 not run |

## Bugs found and fixed

1. **Life bar stood still, then jumped** (row 13). Root cause: the rain and supercell programmes hold the engine's life fraction by stretching `cloud.lifetime`, and the frame passed those numbers on. Fix in `sim/showcase.py`: `_stage_whole_life()`, `reported_whole()`, `SC_STEP_NOMINAL_S`, `FADE_SEEN`; `_build_frame` now reports the planned whole life for the stage cloud. Engine clock and PROTOCOL untouched. Before / after (seed 11): nimbostratus held at 0.77 for 36.8 / 58.8 / 52.5 s -> longest stall 1.8 s; supercell death at 0.90 -> 0.99-1.00. Four regression tests (`TestLifeBar` in `sim/tests/test_showcase.py`); run against the old `showcase.py`, two of them fail (stall 13.0 s on cumulus, 61.0 s on the supercell).
2. **Tour chip "3  Mature sup…" cut at the default window** (`ShowcaseScreen.tourStrip`): chips sized by title when all fit.
3. **Five-stage strip cut and overlapping** (`ShowcaseScreen.lifeStrip`): box height `h - 32 - 39` (was 34, bottom edge through the text); in narrow windows boxes sized by title, live-number column min 190 (was 210), short forms "lasted 6 s" / "8 s so far" / "← here" / "skipped".
4. **Tornado step text cut at 1280x720** (`ShowcaseScreen.tourStrip`): the text takes the status line's row when it needs it.
5. **Precip meter said "mixed 0.02" while nothing fell** (`ShowcaseScreen.lifeStrip/tourStrip`): the meter reads a rate only in the precipitating stage.
6. **`--until-stage forming` / supercell `mature` never reached** (`ModeSnapshot.java:194-197`, SHARED): brief stages wait min(2.5 s, 4 % of lifetime).
7. **Wrong start line in showcase mode** (`precip_sim.py:226-231`, SHARED): log text only.

## Bugs found and not fixed

1. `sim/clouds.py:1473` (sandbox-weather agent). `cloud.precip_rate = round(max(0.0, cloud.precip_rate * (1.0 - dt * 0.8)), 3)` cannot decay below a floor because of the rounding: 0.018 at x1, 0.037 at x0.5, 0.074 at x0.25 (0.009 / 0.004 / 0.002 at x2 / x4 / x8). Reproduction: `x = 0.018; round(x * (1 - 0.8 / 30), 3) == 0.018`. Effect: every dissipating cloud that rained reports a non-zero precip rate until it dies (frames show 0.02), in the sandbox too. `test_the_rate_changes_smoothly` passes because a stuck value is "non-increasing".
2. `Settings.java:66` (only `lastSlot`), `MainMenuScreen.java:36` (`int cloudType`, fresh 0 for every menu), `AppShell.java:507`. The last Cloud Formations tab is not remembered, against PLAN open question 8. Not in this area's file list (question Q2, default applied).
3. `Renderer.java:1015` (sandbox-weather agent). The freezing-level legend always says "▼ melting → rain (surface N °C)"; at -4.0 °C it still says so while snow lies on the ground (`27_temp_command_cold_stage_snow`).
4. `Renderer.java:1967 drawArrow` (sandbox-weather agent). Arrow labels overlap each other on every tab: "fall 3.0 m/s" covers the label beside it, "growth 0.12" covers the updraft label (`02_cumulus_b_growing`, `02_cumulus_d_precipitating`). In-world wind labels also sit on the cloud's name plate when the cloud is at their height (`06_altostratus_a_forming`, `07_altocumulus_a_forming`).

## Things that look questionable but are design choices

1. Everything that falls on this 12 °C late-spring stage is drawn mostly as snowflakes that melt just above the ground, and every raining tab reports type "mixed", including stratus ("drizzle") and cumulus ("a shower"). The freezing level is at y 661 of 828, below every cloud base, so the engine is consistent; the lesson would read more naturally with rain under the low types.
2. The supercell's rain is lighter than the cumulonimbus's (peak 0.15-0.18 vs 0.38-0.50) although its lesson says "Heavy rain and large hail"; graupel is 29-42 of about 3400 raining frames. It also starts at its peak in one frame (0 -> 0.18) and then eases to 0.10, where the other tabs build up.
3. Rain onset is a one-frame step on all tabs (0 -> 20-29 % of the peak); `test_the_rate_changes_smoothly` allows it explicitly.
4. Under-cloud shade is switched off in this mode on purpose.
5. At 1040x620 the tornado step text (the longest) still ends in "…" after three lines, chips other than the current one show numbers only, and the supercell header is shortened to make room for the tornado chip.
6. Altostratus bases (12-26 % of stage height) overlap the cirrus band (9-17 %) in some cycles.
7. The supercell's five engine stages are odd (mature 4-6 s, precipitating 83 s) but the tab shows the tour, not those.
8. PROTOCOL.md line 207 still says `--cloud-type` is "one of the eight cloud types"; line 249 lists `spawn`, `reseed`, `save` as ignored and not `tornado`, which is ignored too.
9. `viewer --selftest` failed once in two runs on UI check 47 (sandbox right panel slide) while other agents loaded the machine; the rerun passed 76/76. Not in this area.

## What was and was not checked

- Pictures in this folder: 103. Opened and looked at: about 78. Not opened:
  - `07_altocumulus_b_growing`, `07_altocumulus_f_pause`
  - `08_stratus_a_forming`, `08_stratus_e_dissipating`, `08_stratus_f_pause`
  - `09_cirrus_f_pause`
  - seven of the nine `15_lesson_fit_1280x720_*` (all but cumulonimbus and supercell)
  - `17_wind_default_3ms`, `17_wind_command_plus7`
  - `21_control_tab_switched_while_paused`, `22_control_speed_x8`, `24_lesson_scroll_1040x620_top`
  - `fake_31_cumulonimbus_mature`, `fake_31_cirrus`
- `24_lesson_scroll_1040x620_scrolled`, `26_f12_screenshot_as_written_by_the_game`, `28_after_91_rapid_tab_keys_stratus` and `30_menu_after_esc_cumulus_preselected_again` were opened in their first version and re-rendered with the final build without being reopened.
- `14_stage_strip_after_1040x620` is a copy of a render that was opened before it was copied.
- Lesson facts were checked by reading, against general meteorology, not against the cited pages (no network).
- Final checks: `javac` exit 0 on the whole tree; `test_showcase` 46/46 OK (261 s); `test_modes` 11/11 OK; `--selftest` PASS (32 steps, 76 UI checks) on the rerun.
- Assumptions: Q1 (life bar) default A applied; Q2 (last tab) default A applied; neither was answered.

## Commands used

```bash
javac -encoding UTF-8 -d viewer/out_test_cf viewer/src/precip/*.java
J="java -cp viewer/out_test_cf precip.Main --mode showcase --config-dir Testing/_tmp/cf/config --saves-dir Testing/_tmp/cf/saves --seed 11"
$J --cloud-type cumulus --until-stage forming|growing|mature|precipitating|dissipating --snapshot out.png
$J --cloud-type cumulus --until t:83.4 --snapshot pause.png          # pause: cb 110.0, ns 149.4, sc 92.6, as 100.9, ac 115.8, st 92.6, ci 159.8, supercell 153.8
$J --cloud-type supercell --until step:tornado+12 --snapshot out.png  # towering+10 mesocyclone+6 mature+8 wall_cloud+7 funnel+9 rope_out+7 decay+5
$J --cloud-type X --until-stage mature --size 1280x720|1040x620 --snapshot out.png
$J --fake --cloud-type supercell --until step:tornado+6 --snapshot out.png
bash Testing/_tmp/cf/batch1.sh ; bash Testing/_tmp/cf/batch2.sh        # all stage, tour, size and fake pictures
python Testing/_tmp/cf/measure.py <seed> 3 all <speed>                 # durations, rain, life bar, altitude, continuity
python Testing/_tmp/cf/proc_cmds.py                                    # 30 command checks against the real process
javac -encoding UTF-8 -cp viewer/out_test_cf -d Testing/_tmp/cf/harness_out Testing/_tmp/cf/harness/precip/*.java
java -cp "viewer/out_test_cf;Testing/_tmp/cf/harness_out" precip.CfDrive  Testing/_tmp/cf/drive  Testing/_tmp/cf/config Testing/_tmp/cf/saves   # 41 control checks, control pictures
java -cp "viewer/out_test_cf;Testing/_tmp/cf/harness_out" precip.CfDrive2 Testing/_tmp/cf/drive2 Testing/_tmp/cf/config Testing/_tmp/cf/saves   # x1 dissipation, wind, cold stage
cd sim/tests && python -m unittest test_showcase && python -m unittest test_modes
```
