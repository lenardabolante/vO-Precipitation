# Hurricane Formation mode: feature test report

Tested 2026-10-09 against the real Python sim (`sim/hurricane.py`, seed 1 unless stated) and the Java viewer compiled to `viewer/out_test_hu`. All pictures in this folder are real viewer renders (`--mode hurricane --snapshot`). Files starting with `fake_` come from the built-in fake or from a recorded stream, because the real sim cannot reach that state (it never sends `hemisphere:"S"`).

**Row count by result (42 rows): PASS 31, FIXED 9, FAIL (not fixed) 1, NOT TESTED 1.**

**How the pictures were checked.** There are 69 pictures. 65 were opened at least once. Four were rendered but never opened: `33_layout_1680x760_dpi150_tour_both.png`, `33_layout_1680x760_dpi150_tour_outside.png`, `33_layout_1680x760_dpi150_tour_inside.png` and `34_labels_off_tour_ch06_keeps_chapter_label.png`.

After the last round of fixes everything was rendered again. Of that final set I re-opened:
- tour 05, 08, 15, 17, 20;
- stage pictures 22 outside, 24 inside, 25 inside, 26 both, 30 inside;
- `31_layout_1280x720_explore_cat5.png`, `32_layout_1040x620_min_explore_cat5.png`, `33_layout_1680x760_dpi150_explore_cat5.png`;
- `34_labels_off_explore_cat3_both.png`;
- all five `fake_` pictures.

The other final renders were opened only in an earlier version. They differ from that version only by the fixes listed below (same seed, same stage, same numbers in the snapshot log line).

## Measured storm life

Direct sim run, auto-play, speed 1, 30 frames/s, seed 1. The whole life is 210.0 s (6300 frames), then it loops to a new disturbance. Seeds 7 and 12345 give the same durations, winds, pressures and categories; only sizes, rain and sea temperature differ by a few percent.

| Stage | Duration | Max wind (kt) | Central pressure (hPa) | Category seen | RMW (km) | Eye radius (km) | 34-kt radius (km) | Surge (m) | Over land |
|---|---|---|---|---|---|---|---|---|---|
| disturbance | 22 s | 12 to 23 | 1010.0 to 1007.0 | disturbance | 82 to 62 | 0 to 1 | 0 | 0.05 to 0.09 | no |
| depression | 16 s | 23 to 31 | 1008.2 to 1003.5 | disturbance, then depression | 66 to 52 | 0 to 11 | 0 | 0.09 to 0.38 | no |
| storm | 20 s | 31 to 58 | 1003.5 to 993.9 | depression, then tropical storm | 52 to 42 | 11 to 19 | 0 to 140 | 0.38 to 1.06 | no |
| cat1 | 18 s | 58 to 78 | 993.9 to 981.9 | storm, then 1 | 42 to 35 | 19 to 20 | 140 to 162 | 1.06 to 1.65 | no |
| cat2 | 14 s | 78 to 92 | 981.9 to 967.8 | 1, then 2 | 35 to 30 | 20 to 19 | 159 to 179 | 1.66 to 2.41 | no |
| cat3 | 16 s | 92 to 109 | 967.8 to 949.1 | 2, then 3 | 30 to 24 | 19 to 16 | 179 to 186 | 2.41 to 3.66 | no |
| cat4 | 18 s | 109 to 131 | 949.0 to 924.4 | 3, then 4 | 24 to 18 | 16 to 12 | 185 to 168 | 3.66 to 5.13 | no |
| cat5 | 20 s | 131 to 151 | 924.4 to 903.7 | 4, then 5 | 18 to 20 | 12 to 14 | 157 to 180 | 5.14 to 6.40 | no |
| landfall | 30 s | 151 to 67 | 903.7 to 986.0 | 5, 4, 3, 2, 1 | 20 to 34 | 14 to 21 | 169 to 193 | 6.40 to 1.29 | from 3.2 s in |
| dissipation | 36 s | 67 to 19 | 986.0 to 1007.0 | 1, storm, depression, disturbance | 34 to 62 | 22 to 1 | 169 to 0 | 1.28 to 0.07 | yes |

The storm trails the stage name by about 2 s (two cascaded 1.05 s lags), which is why each stage starts in the category of the stage before it.

Picking a stage settles at the start of that stage: 12 kt/1010 hPa, 27/1007, 40/1002, 66/989, 85/978, 99/963, 117/943, 141/918, 130/932 (landfall), 60/990 (dissipation).

Thresholds compared against (NHC Saffir-Simpson Hurricane Wind Scale, 1-minute sustained): depression up to 33 kt, tropical storm 34 to 63, Cat 1 64 to 82, Cat 2 83 to 95, Cat 3 96 to 112, Cat 4 113 to 136, Cat 5 137 and above. The code cites the same in `category_for_kt` and `SOURCES` (nhc.noaa.gov/aboutsshws.php). The 26-kt lower edge of "depression" is the model's own convention (NHC has no lower limit).

## Features

| # | Feature | Code (file:function) | What it does | How tested | Picture | Result |
|---|---|---|---|---|---|---|
| 1 | Tour ch 1 "What a hurricane needs" | `hurricane.py:_LESSON`, `HurricaneScreen.enterChapter/tourPanel` | Outside view, warm-sea tint, storm at disturbance (12 kt, 1010 hPa) | Real sim snapshot; text read against NOAA figures | `01_tour_ch01_ingredients.png` | FIXED: text said 5 degrees of latitude is "555 km or 300 miles" (555 km = 345 miles); the "200 km" ring label was cut by the inset. Both fixed, picture re-opened after the fix |
| 2 | Tour ch 2 "A cluster of thunderstorms" | same | Outside, focus convection, disturbance | Snapshot, text read | `02_tour_ch02_disturbance.png` | PASS: scattered cells, no eye; label quotes rain and cloud tops from the frame (state: 6.4 mm/h, tops 10.0 km) |
| 3 | Tour ch 3 "The ocean-powered heat engine" | same, `HurricanePainter.paintOutsideArrows` | Both views, inflow arrows, depression (27 kt, 1007 hPa) | Snapshot, text read | `03_tour_ch03_heat_engine.png` | PASS: arrows point inward and counter-clockwise; label "up to 2 m/s inward" equals the section minimum u (-2.06) |
| 4 | Tour ch 4 "Why it spins" | same, `HurricanePainter.paintRotation` | Outside, rotation arrows, depression | Snapshot, text read | `04_tour_ch04_coriolis.png` | PASS: arrows counter-clockwise, label "counter-clockwise · hemisphere N" |
| 5 | Tour ch 5 "Getting organised" | same, `HurricanePainter.paintBandLines` | Outside, rainbands traced, storm (40 kt, 1002 hPa) | Snapshot, text read | `05_tour_ch05_spin_up.png` | FIXED: readout said "Eye radius 13 km" while eye clarity was 0.10 and no eye is drawn; now "no eye". Label "3 bands spiralling in" |
| 6 | Tour ch 6 "From outside: the eye" | same, `HurricanePainter.ring` | Outside, eye ring, cat3 (99 kt, 963 hPa) | Snapshot | `06_tour_ch06_outside_eye.png` | PASS: clear eye, "radius 18 km" = `eye_km` 18.2 (36 km across, inside the text's 30 to 65 km) |
| 7 | Tour ch 7 "From outside: the eyewall" | same | Outside, ring at the radius of maximum wind, cat3 | Snapshot | `07_tour_ch07_outside_eyewall.png` | PASS: label quotes 99 kt at 29 km, the state values |
| 8 | Tour ch 8 "From outside: spiral rainbands" | same | Outside, 6 bands traced, cat3 | Snapshot | `08_tour_ch08_outside_bands.png` | FIXED: the callout dot sat on the "200 km" ring label; dot moved along the ring, label readable. "6 bands spiralling in" |
| 9 | Tour ch 9 "From outside: the cirrus shield" | same | Outside, shield ring, cat3 | Snapshot | `09_tour_ch09_outside_outflow.png` | PASS: ring at the edge of the white deck, tops 15.5 km |
| 10 | Tour ch 10 "Inside: air spirals in" | same, `HurricanePainter.paintInsideArrows/paintInsideFlow` | Inside, inflow emphasised, cat3 | Snapshot | `10_tour_ch10_inside_inflow.png` | PASS: blue arrows toward the centre along the sea; label speed matches the section minimum u (-12.4 m/s) |
| 11 | Tour ch 11 "Inside: the rising eyewall" | same | Inside, eyewall emphasised, cat3 | Snapshot | `11_tour_ch11_inside_eyewall.png` | PASS: orange arrows up the outward-sloping wall; label speed matches the section maximum w (3.66 m/s) |
| 12 | Tour ch 12 "Inside: outflow aloft" | same | Inside, outflow emphasised, cat3 | Snapshot | `12_tour_ch12_inside_outflow.png` | PASS: white arrows outward under the anvil, near 14 km up |
| 13 | Tour ch 13 "Inside: the sinking, warm, clear eye" | same | Inside, eye emphasised, cat4 (117 kt, 943 hPa) | Snapshot, text read | `13_tour_ch13_inside_eye.png` | FIXED: text said "the sinking also presses a little on the surface, so the eye ... is warmer" (wrong: the eye has the lowest pressure). Sentence replaced; new text fits the box at 1680 and 1280 |
| 14 | Tour ch 14 "The warm core" | same, `HurricanePainter.warmCorePeak` | Inside, red shading, cat4 | Snapshot | `14_tour_ch14_warm_core.png` | PASS: label matches the section maximum (+10.9 °C at 10.7 km); text says 7 to 13 |
| 15 | Tour ch 15 "The stages of a hurricane" | same | Both, no focus, storm | Snapshot; thresholds in the text checked | `15_tour_ch15_stages.png` | PASS: all kt/mph pairs correct; readout "no eye" after fix 3 |
| 16 | Tour ch 16 "Categories 1 to 5" | same | Outside, eyewall ring, cat5 (141 kt, 918 hPa) | Snapshot | `16_tour_ch16_intensity.png` | PASS: small sharp eye, ring at 17 km |
| 17 | Tour ch 17 "Storm surge" | `HurricaneScreen.outsideLabels/insideLabels` (case surge) | Outside, landfall (130 kt, 932 hPa), land on the west | Snapshot, plus an inside-view render of the same chapter kept in `Testing/_tmp/hu/before/` | `17_tour_ch17_surge.png` | FIXED: the "5.1 m of water pushed ashore" dot sat on land east of the eye, and in the inside view on dry ground under the centre; both now point at the shore |
| 18 | Tour ch 18 "Landfall: losing the ocean" | same | Both, decay, landfall | Snapshot | `18_tour_ch18_landfall.png` | PASS: land under the centre in both views, label "the centre is over land · 130 kt" |
| 19 | Tour ch 19 "How a hurricane dies" | same | Both, decay, dissipation (60 kt, 990 hPa) | Snapshot | `19_tour_ch19_dissipation.png` | PASS: eye filled with cloud, shield shrunk, tops down to 12 km |
| 20 | Tour ch 20 "Now explore" | same | Both, cat3, last chapter | Snapshot | `20_tour_ch20_explore.png` | PASS: button reads "Explore the stages", Skip greyed, counter 20 / 20 |
| 21 | Stage explorer: disturbance | `HurricaneScreen.explorePanel/pickStage`, `hurricane.py:handle_command` (stage) | Sends `stage`, shows the summary and the live readout | 3 snapshots | `21_stage_disturbance_outside.png`, `21_stage_disturbance_inside.png`, `21_stage_disturbance_both.png` | PASS: 12 kt, 1010 hPa, no eye, no bands |
| 22 | Stage explorer: depression | same | same | 3 snapshots | `22_stage_depression_outside.png`, `22_stage_depression_inside.png`, `22_stage_depression_both.png` | PASS: 27 kt, 1007 hPa, first band, "no eye" |
| 23 | Stage explorer: storm | same | same | 3 snapshots | `23_stage_storm_outside.png`, `23_stage_storm_inside.png`, `23_stage_storm_both.png` | PASS: 40 kt, 1002 hPa, bands curling in, "no eye" after fix 3 |
| 24 | Stage explorer: cat3 | same | same | 3 snapshots | `26_stage_cat3_outside.png`, `26_stage_cat3_inside.png`, `26_stage_cat3_both.png` | PASS: 99 kt, 963 hPa, eye radius 18 km, pink arrow down in the eye, eyewall rising 3.6 m/s |
| 25 | Stage explorer: cat4 | same | same | 3 snapshots | `27_stage_cat4_outside.png`, `27_stage_cat4_inside.png`, `27_stage_cat4_both.png` | PASS: 117 kt, 943 hPa, eye 16 km, warm core +10.9 °C |
| 26 | Stage explorer: cat5 | same | same | 3 snapshots | `28_stage_cat5_outside.png`, `28_stage_cat5_inside.png`, `28_stage_cat5_both.png` | PASS: 141 kt, 918 hPa, eye 12 km, eyewall rising 5.6 m/s, surge dome under the centre |
| 27 | Stage explorer: landfall | same | same | 3 snapshots | `29_stage_landfall_outside.png`, `29_stage_landfall_inside.png`, `29_stage_landfall_both.png` | PASS: coast east of the centre, land in the section, 130 kt, 932 hPa, "Land" label |
| 28 | Stage explorer: cat1, cat2, dissipation (the "eye" arrow) | same, `HurricanePainter.paintInsideArrows` | same; a broad arrow stands for the sinking air of the eye | 9 snapshots; section values read from the sim | `24_stage_cat1_outside.png`, `24_stage_cat1_inside.png`, `24_stage_cat1_both.png`, `25_stage_cat2_outside.png`, `25_stage_cat2_inside.png`, `25_stage_cat2_both.png`, `30_stage_dissipation_outside.png`, `30_stage_dissipation_inside.png`, `30_stage_dissipation_both.png` | FIXED: the eye arrow pointed UP in these three stages, because the air at the centre still rises there (up to +0.69, +0.29, +0.48 m/s); the arrow is now drawn only where the air sinks. Stage numbers correct: 66 kt/989 hPa, 85/978, 60/990 |
| 29 | Physics: smoothness, pressure against wind, category | `hurricane.py:_advance_filters/_core/_build_frame/category_for_kt` | Targets per stage, two lags, Holland profile | `Testing/_tmp/hu/measure_life.py` over 6660 frames for 3 seeds | none (numbers) | PASS: category equals the NHC thresholds in every frame (0 mismatches); largest step per frame 1 kt, 0.4 hPa, 0.3 km RMW, 1.2 km 34-kt radius; profile pressure monotone and equal to the state pressure at the centre, 1010.7 to 1011.8 hPa at 600 km. Wind/pressure pairs agree with the Dvorak Atlantic values as I recalled them, not looked up (no network) |
| 30 | Physics: eye, eyewall, bands, warm core, surge, sea temperature, land, units | `hurricane.py:_bands/_rain_at/_profile/_build_section/surge_for_kt`, `HurricaneScreen.readout` | See the table above | Same script; viewer labels read against the frame | the stage pictures (rows 21 to 28) | PASS: ranges sane (RMW 17 to 88 km, eye radius 0 to 23 km, tops 6 to 17 km, warm core 0.6 to 12.9 °C at 10.7 km, u -19 to +15 m/s, w -1.25 to +5.6 m/s, rain to 55 mm/h, sea 27.5 to 30.1 °C, surge 0.05 to 6.4 m); always 6 bands in a stable order; sections at most 6 per second. Viewer units match the numbers: kt and km/h (kt x 1.852), hPa, km, m, °C, m/s; `eye_km` is a radius and labelled "radius". The model has no track, wind shear or forward speed |
| 31 | Rotation sense | `hurricane.py:step` (rotation), `HurricanePainter.paintOutside/paintBandLines/stepTracers` | Rotation angle grows; bands are log spirals | Sim: rotation step always positive (0.002 to 0.023 rad/frame); code read; pictures; southern mirror through a recorded stream | `04_tour_ch04_coriolis.png`, `fake_36_southern_hemisphere_ch04_coriolis.png` | PASS: counter-clockwise for N (bands wind inward counter-clockwise, arrows agree); the S stream draws the mirror image with clockwise arrows and the label "clockwise · hemisphere S" |
| 32 | Eye appears only when the storm is strong enough | `HurricanePainter.tables` (tEye), `HurricaneScreen.outsideLabels` | The eye hole scales with `eye_clarity` | The 30 stage pictures | rows 21 to 28 | PASS: no hole at disturbance, depression, storm; faint at cat1 (0.35); clear from cat3; filled again at dissipation (0.25) |
| 33 | Every key and control | `HurricaneScreen.key/topBar/stageStrip/tourPanel/explorePanel` | Keys, chips, dots, inset click, wheel | Live screen driven off screen through the panel's own listeners against the real sim (`Testing/_tmp/hu/src/precip/HuDrive.java keys`) | none | PASS 29/29: Right/Enter/PageDown, Left/PageUp, Home, T, R, Space, P, =, -, numpad +, O, I, B, V, Tab, L, E, A, stage chips, chapter dots, inset click, wheel, Esc (python stopped, no save slot). Top-bar buttons call the same methods and were not clicked one by one |
| 34 | Commands with good and bad values | `hurricane.py:handle_command`, `precip_sim.py` loop | pause, resume, speed, stage, auto, save, quit; everything else ignored | 38 direct calls and 20 lines to the real process, including non-JSON, arrays, null, NaN, 1e999, unknown ids (`measure_cmds.py`, `real_process.py`) | none | PASS: no exception, process alive throughout, speed clamped to 0.25 to 8, unknown stage ignored, pause freezes state and time, `quit` exits 0 |
| 35 | Jumping quickly and out of order | `hurricane.py:_advance_filters`, `HurricanePainter.update`, `HurricaneScreen.paintScene` | Lags turn every jump into a glide | Sim: a random stage every 0.23 s for 120 s. Viewer: change per paint measured (`HuDrive pop`, `trans`) | none | FIXED: the sim is smooth (at most 2 kt, 0.7 hPa per frame; cat3 reached directly and after a detour gives identical state, profile and bands). The viewer had one flash: leaving the "warm sea" chapter switched the sea tint in a single paint (mean change 22.9/255); the tint is now eased and the same step measures 3.3, then 7.6, 5.1, 2.7 |
| 36 | `save` is refused | `hurricane.py:handle_command` (save), `GameSession.requestSave` | The sim answers `saved` with `ok:false`; the viewer never sends it (no slot) | Real process; `--selftest-ui`; drive test | none | PASS: reply `saved`, `ok:false`, "saving is not available in hurricane mode"; no file written; no slot folder created |
| 37 | Last chapter / stage remembered in settings | none: `Settings.java` has only `lastSlot` (line 66); `HurricaneScreen` field `chapter` starts at 0 | Not implemented | Code read (no such field or key in `Settings.java`) | none | FAIL (not fixed): see below |
| 38 | Layout at 1680x760, 1280x720, 1040x620 (window minimum), `--dpi 1.5` | `HurricaneScreen.targets/stageStrip/readout/explorePanel` | Side by side when wide, stacked or inset when narrow | 12 snapshots; 9 opened (see the note at the top) | `31_layout_1280x720_tour_both.png`, `31_layout_1280x720_tour_outside.png`, `31_layout_1280x720_tour_inside.png`, `31_layout_1280x720_explore_cat5.png`, `32_layout_1040x620_min_tour_both.png`, `32_layout_1040x620_min_tour_outside.png`, `32_layout_1040x620_min_tour_inside.png`, `32_layout_1040x620_min_explore_cat5.png`, `33_layout_1680x760_dpi150_tour_both.png`, `33_layout_1680x760_dpi150_tour_outside.png`, `33_layout_1680x760_dpi150_tour_inside.png`, `33_layout_1680x760_dpi150_explore_cat5.png` | FIXED: at 1040x620 the chips read "hurricane (…", "64–8…", "96–1…" and the button "← Pre…"; at 1280 and 1040 the category read "Category 5 hurrican…". All whole in the re-opened explorer pictures. Panels inside the window at every size opened; the dpi 1.5 explorer picture is sharp and complete. The three dpi 1.5 tour pictures were not opened |
| 39 | `--labels-off` | `HurricaneScreen.labels`, `ModeSnapshot.Opts.labelsOff` | Hides the explorer callouts (by the code, chapter callouts stay) | 2 snapshots; the explorer one opened | `34_labels_off_explore_cat3_both.png`, `34_labels_off_tour_ch06_keeps_chapter_label.png` | PASS: no callouts in the explorer, button reads "Labels off". The tour picture was not opened, so "chapter callouts stay" is from the code only |
| 40 | Fake versus real | `FakeHurricane.java` | Stand-in sim for work without Python | 4 fake snapshots beside the real 06, 11, 28, 29 pictures; code read | `fake_35_tour_ch04_eye_outside.png`, `fake_35_tour_ch08_eyewall_inside.png`, `fake_35_explore_cat5_both.png`, `fake_35_explore_landfall_both.png` | FIXED: the fake reported a stage 83 % complete with auto-play off (real: 0 %), which filled the stage chip; now 0 %. Both render. Remaining differences listed below |
| 41 | F12 screenshot | `AppShell.key/screenshot` | Writes a PNG of what is on screen | Drive test on the fake with a temp root (`HuDrive f12`) | none (file in `Testing/_tmp/hu/f12root/screenshots/`) | PASS: a 433768-byte PNG was written and the screen stayed open. Not tried in a visible window |
| 42 | Design rule (the game's own look) | `HurricanePainter` | Cloud detail from the same `Noise` functions the cloud painter uses | Pictures | `26_stage_cat3_both.png`, `29_stage_landfall_both.png` | NOT TESTED as pass or fail: it is a judgement. See "questionable" |

## Bugs found and fixed

1. **Wrong conversion in chapter 1.**
   - Symptom: "5 degrees of latitude (roughly 555 km or 300 miles)".
   - Root cause: 555 km is 345 miles; 300 is the figure in nautical miles.
   - Change: `sim/hurricane.py:188`.
   - Test: `test_unit_conversions_in_the_text_agree` in `sim/tests/test_hurricane.py:170` (checks every unit pair in all chapters and summaries, at least 30 pairs).

2. **Wrong physics in chapter 13.**
   - Symptom: "The sinking also presses a little on the surface, so the eye and the air above it are warmer than their surroundings."
   - Root cause: the eye has the lowest surface pressure of the storm; the warmth comes from compression of the sinking air.
   - Change: `sim/hurricane.py:254-257`, now "That warming makes the eye and the air above it warmer than their surroundings, and the lowest surface pressure of the whole storm is found here."
   - Test: `test_the_eye_chapter_does_not_say_sinking_air_presses_on_the_surface` (`sim/tests/test_hurricane.py:209`).

3. **Readout quoted an eye that is not there.**
   - Symptom: "Eye radius 13 km" for a 40-kt tropical storm with `eye_clarity` 0.10, beside text saying there is no eye.
   - Root cause: the readout tested only `eye_km > 0.5`; the "Eye" label also requires `eye_clarity > 0.2`.
   - Change: `HurricaneScreen.java:648`, same test as the label.
   - Evidence: `05_tour_ch05_spin_up.png` read "13 km" before, "no eye" after.

4. **Storm-surge callouts pointed at dry land.**
   - Symptom: outside, the dot sat 24 km east of the centre, which is inland at landfall; inside, it sat at the centre, where the ground is.
   - Root cause: fixed anchor positions that ignore the coast.
   - Change: `HurricaneScreen.java:403` and `:482`; new accessor `HurricanePainter.coastKm()` at `HurricanePainter.java:352`.
   - Evidence: `17_tour_ch17_surge.png`, dot now on the shoreline.

5. **"Eye" arrow pointing up.**
   - Symptom: at cat1, cat2 and dissipation an orange arrow pointed upward in the eye, under the label "Eye".
   - Root cause: the arrow for "sinking air in the eye" was drawn in whatever direction the air moves; in those stages the air at the centre still rises (up to +0.69, +0.29, +0.48 m/s).
   - Change: `HurricanePainter.java:1047`, skipped unless w < 0.
   - Evidence: `24_stage_cat1_inside.png`, `25_stage_cat2_inside.png`, `30_stage_dissipation_inside.png`; the arrow was present before and is gone in the 24 inside render re-opened after the fix.

6. **Km ring label cut by the inset.**
   - Symptom: "200 km" half hidden under the inside inset in outside-view pictures.
   - Change: `HurricanePainter.java:515,530` (label left out when the inset covers it), `HurricaneScreen.java:298`.

7. **Rainband callout dot on top of the "200 km" label.**
   - Change: `HurricaneScreen.java:369` and `:433`, dot moved from 318° to 300° on the same ring; the pill stays where it was.
   - Evidence: `08_tour_ch08_outside_bands.png`, `26_stage_cat3_both.png`.

8. **One-frame flash when leaving chapter 1.**
   - Symptom: the whole outside picture changed colour in a single paint.
   - Root cause: the warm-sea tint (and the warm-core emphasis) was switched on and off instantly with the chapter's focus.
   - Change: `HurricaneScreen.java:52` (`sstGlow`, `warmGlow`) and `paintScene`, eased over 0.35 s.
   - Evidence: mean change per paint 22.9/255 before; 3.3, then 7.6, 5.1, 2.7 after.

9. **Cut-off text in narrow windows.**
   - Symptom at 1040x620: chips "Category 3 hurricane (…", "64–8…", "96–1…"; button "← Pre…". At 1280 and 1040: "Category 5 hurrican…" in the readout.
   - Change: `HurricaneScreen.java:611-617` (three name lines on the low strip; the "Cat N" tag is dropped before the wind range is cut), `:648-660` (the category drops "(major)" before it is cut), `:757-766` (Previous / Next / Replay share the row by label width).
   - Evidence: `32_layout_1040x620_min_explore_cat5.png`, `31_layout_1280x720_explore_cat5.png`.

10. **Fake stage progress.**
    - Symptom: the fake reported `stage_frac` 0.83 with auto-play off; the real sim reports 0.
    - Change: `FakeHurricane.java:136`, `stageT` advances only in auto-play.
    - Evidence: snapshot log line "stage cat3 83%" before, "0%" after.

All ten were re-verified: `sim/tests/test_hurricane.py` 54 tests OK, `test_modes.py` 11 tests OK (through discover), `--selftest-ui` PASS with 76 checks, `javac` exit 0 on the whole tree.

## Bugs found and not fixed

- **Last chapter / stage is not remembered** (area brief and PLAN.md open question 8).
  - Evidence: `Settings.java:66` has only `lastSlot`; `HurricaneScreen.java` field `chapter` starts at 0 and nothing reads or writes settings.
  - Why not fixed: it needs new keys in `Settings.java` (owned by the menu/settings area) plus `AppShell.beginMode`. Asked as SA-Asker Q1 (advisory); unanswered, default taken: report only.
- **`python -m unittest sim/tests/test_modes.py` fails to import.**
  - Evidence: `ModuleNotFoundError: No module named '_pathfix'` at `sim/tests/test_modes.py:13`. The same file passes with `python -m unittest discover -s sim/tests -p "test_modes.py"` (11 tests OK). `test_hurricane.py` has a fallback for this invocation; `test_modes.py` does not.
  - Why not fixed: shared file, not a hurricane defect.

## Things that look questionable but are design choices

- **Storm surge is shown over open ocean.** `surge_m` is a function of wind only, so a Cat 5 far from land reads "Storm surge 5.8 m" and the sea is drawn with a dome under the eye.
- **"Sea surface 28.2 °C" while the centre is over land** (landfall, dissipation).
- **Eye sinking speed is high.** Up to 1.25 m/s at Cat 5 (label "air sinking at 0.94 m/s" at Cat 4). Real mean eye subsidence is of the order of centimetres per second.
- **The radial wind profile misses the peak.** `profile.wind_ms` is sampled every 10 km; with a 20 km RMW its highest sample is up to 6.5 m/s (8 %) below `state.vmax_ms`. The viewer shows the state value, so no wrong number is displayed; only tracer colours use the profile.
- **Stage header "about N hPa" versus the readout.** A picked stage settles at its start, so Landfall shows "about 960 hPa" beside a readout of 932, and Cat 5 "about 910" beside 918.
- **After the loop, `over_land` stays true for 1.7 s into the new disturbance** (the coast slides away).
- **At 8x speed** a whole life takes 26 s and steps reach 2 kt and 2.8 hPa per frame.
- **Switching view** (Outside / Inside / Both) redraws the layout over about 0.3 s; the first paints change by 40 to 60/255. It is a glide, but it is the largest visual change in the mode.
- **At 1040x620 the longest chapters need the mouse wheel** for the last one or two lines (a scroll bar is shown).
- **Art style.** The top-down view is a satellite-style picture and the section is a painted diagram with arrows and callouts. Neither uses the sandbox's side-on cloud sprites or terrain renderer; the land is a green noise texture. Cloud detail does come from the game's `Noise` functions. For the user to judge: `26_stage_cat3_both.png`, `29_stage_landfall_both.png`.
- **Fake versus real, remaining differences.** 13 placeholder chapters instead of 20; placeholder text; an almost vertical eyewall; one long band rather than six; the stage header pressure taken from the start values (932 for landfall); no `saved` reply and no events.

## Could not verify

- Anything in a visible window (the rules forbid opening one): real key focus, hover states, F12 through the OS, the window minimum being enforced.
- Mouse clicks on the top-bar buttons (they call the methods the keys call).
- Southern hemisphere with the real sim (it always sends "N").
- The four pictures listed at the top as not opened.

## Commands used

Run from the project root. Full per-picture lists are in `Testing/_tmp/hu/tour_cmds.txt`, `stage_cmds.txt`, `layout_cmds.txt` and `extra_cmds.txt`.

```
javac -encoding UTF-8 -d viewer/out_test_hu viewer/src/precip/*.java

# guided tour: N = 0..19, NN = N+1, ID = chapter id
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --chapter N --frames 300 --snapshot Testing/05_hurricane_formation/NN_tour_chNN_ID.png

# stage explorer: ID in disturbance depression storm cat1 cat2 cat3 cat4 cat5 landfall dissipation (NN = 21..30), V in outside inside both
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --explore --stage ID --hview V --frames 300 --snapshot Testing/05_hurricane_formation/NN_stage_ID_V.png

# layouts: SIZE in "--size 1280x720" (31_layout_1280x720), "--size 1040x620" (32_layout_1040x620_min), "--size 1680x760 --dpi 1.5" (33_layout_1680x760_dpi150)
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --chapter 2 --frames 300 SIZE --snapshot Testing/05_hurricane_formation/PREFIX_tour_both.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --chapter 5 --frames 300 SIZE --snapshot Testing/05_hurricane_formation/PREFIX_tour_outside.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --chapter 12 --frames 300 SIZE --snapshot Testing/05_hurricane_formation/PREFIX_tour_inside.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --explore --stage cat5 --frames 300 SIZE --snapshot Testing/05_hurricane_formation/PREFIX_explore_cat5.png

# labels off
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --explore --stage cat3 --hview both --labels-off --frames 300 --snapshot Testing/05_hurricane_formation/34_labels_off_explore_cat3_both.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --seed 1 --chapter 5 --labels-off --frames 300 --snapshot Testing/05_hurricane_formation/34_labels_off_tour_ch06_keeps_chapter_label.png

# built-in fake
java -cp viewer/out_test_hu precip.Main --mode hurricane --fake --chapter 3 --frames 300 --snapshot Testing/05_hurricane_formation/fake_35_tour_ch04_eye_outside.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --fake --chapter 7 --frames 300 --snapshot Testing/05_hurricane_formation/fake_35_tour_ch08_eyewall_inside.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --fake --explore --stage cat5 --hview both --frames 300 --snapshot Testing/05_hurricane_formation/fake_35_explore_cat5_both.png
java -cp viewer/out_test_hu precip.Main --mode hurricane --fake --explore --stage landfall --hview both --frames 300 --snapshot Testing/05_hurricane_formation/fake_35_explore_landfall_both.png

# southern hemisphere: real sim frames recorded with hemisphere set to "S" (Testing/_tmp/hu/south_depression.jsonl)
java -cp viewer/out_test_hu precip.Main --mode hurricane --replay Testing/_tmp/hu/south_depression.jsonl --chapter 3 --frames 330 --snapshot Testing/05_hurricane_formation/fake_36_southern_hemisphere_ch04_coriolis.png

# measurements (direct sim)
python Testing/_tmp/hu/measure_life.py        # storm life, smoothness, categories
python Testing/_tmp/hu/measure_cmds.py        # jumps, commands, settled table
python Testing/_tmp/hu/real_process.py        # real process over stdin
python Testing/_tmp/hu/scan_text.py           # unit pairs in the text

# live-screen drive harness (test code, lives under Testing/_tmp/hu, not part of the game)
javac -encoding UTF-8 -cp viewer/out_test_hu -d Testing/_tmp/hu/classes Testing/_tmp/hu/src/precip/HuDrive.java
java -cp "viewer/out_test_hu;Testing/_tmp/hu/classes" precip.HuDrive keys . Testing/_tmp/hu/drive
java -cp "viewer/out_test_hu;Testing/_tmp/hu/classes" precip.HuDrive pop . Testing/_tmp/hu/drive
java -cp "viewer/out_test_hu;Testing/_tmp/hu/classes" precip.HuDrive trans . Testing/_tmp/hu/drive
java -cp "viewer/out_test_hu;Testing/_tmp/hu/classes" precip.HuDrive f12 Testing/_tmp/hu/f12root Testing/_tmp/hu/f12root

# tests
python -m unittest sim/tests/test_hurricane.py                    # 54 tests OK
python -m unittest discover -s sim/tests -p "test_modes.py"       # 11 tests OK
java -cp viewer/out_test_hu precip.Main --selftest-ui --config-dir Testing/_tmp/hu/config --saves-dir Testing/_tmp/hu/saves   # PASS, 76 checks
```

## Orchestrator check (2026-10-09)

The four pictures the agent listed as not opened were opened afterwards by the orchestrator: the three `33_layout_1680x760_dpi150_tour_*` pictures are sharp, complete and nothing is clipped at 150 % scaling; `34_labels_off_tour_ch06_keeps_chapter_label.png` shows the button reading "Labels off" with the chapter's own "Eye" callout still drawn, which confirms row 39 from a picture. Also re-opened: `28_stage_cat5_both.png`, `32_layout_1040x620_min_explore_cat5.png`, `17_tour_ch17_surge.png`. All 69 pictures have now been opened at least once.
