# Windsock: the sim side, and how to put it back

Removed from the sim on 2026-10-10 (Phase L). The viewer side is in `VIEWER.md` and `viewer_windsock.java.txt` in this
folder. This file and its two companions hold the sim side:

| File | What is in it |
|---|---|
| `sim_windsock.py.txt` | every piece of `sim/objects.py` (and the `sim/tests/_schema.py` line) that was removed, verbatim, each under a header saying where it came from and its old line numbers |
| `sim_windsock_tests.py.txt` | every test and every test expectation removed or changed with it, verbatim, same headers |
| `SIM.md` | this file |

## What a windsock was

* A scenery object of the `hello` message: `kind: "windsock"`, `w` 8.0, `h` 40.0, `x`, `y` (the ground at `x`), `seed`, and
  the usual dynamic fields `sway`, `snow`, `wet`. No extra fields, no variant. `PROTOCOL.md` line 58 lists the kind in
  `hello.objects[].kind` (`mountain | hill | tree | pond | puddle | snowpack | windsock`); it is the plan-writer's file, so
  check whether that line still names it before restoring.
* `max(1, round(0.7 * w / 1280))` per world (1 up to a 2740 px world, 2 at 3840, 4 at 6400), placed last in
  `generate_world`, after the puddles and the snowpacks, so its ids are the highest of the list.
* Placed by `flat_spot("windsock")` like the puddles and snowpacks (flattest of 5 draws that is clear of the avoid
  spans), with half width `0.5 * 8.0 + SPOT_MARGIN["windsock"]` (12 px margin). Its avoid set was buildings, ponds and
  streets. It did **not** include the puddles, which is why one could stand in a puddle (PLAN.md open question 41):
  if it comes back, put the puddle spans in its avoid set.
* It leaned in the wind like a tree (`SWAYERS`: the wind lean loop in `ObjectField.update`, the storm sway in
  `apply_tornadoes`, `settle_sway`; all of that is unchanged and still serves the trees) and a tornado damaged it from
  `DAMAGE_VT["windsock"] = 24.0` m/s, the weakest thing in the game (`dmg_sorted` is built from `DAMAGE_VT`, so adding the kind
  there is all it takes).

## The rng stream: the draws are still made

`generate_world(rng, ...)` is called on the world's own seeded stream (`Simulation._reset`: `world_rng`), and the stream is used
after it returns: `World(...)` draws the base wind and gust phase, then the first cloud spawn delay, the streamlines and the
opening clouds. The windsock loop was the last consumer inside `generate_world`, so leaving its draws out would have moved
every world's wind, sky and streamlines. They are kept: per windsock, 5 times `rng.uniform(20.0, w - 20.0)` (the 5 tries of
`flat_spot`; the scan after them draws nothing) and one `rng.getrandbits(31)` for its seed. Today's code in
`generate_world` makes those calls and creates nothing. The loop count is unchanged: `max(1, int(round(0.7 * u)))`.

Evidence (taken at removal, against the code as it was): for 22 plain worlds (4 sizes, 4 terrains, 4 settlement modes) and 8
simulations (4 plain, 4 presets: oklahoma, japan, sahara, antarctica) the hash of terrain + settlements + every non-windsock
object, the ids (1..N with no gap), the stream state after `generate_world`, and for the simulations the opening clouds and
streamlines, were identical before and after. With the draws cut out, all 30 differed. The pinned values are in
`sim/tests/test_objects.py` (`WINDSOCK_FREE_WORLDS`, `WINDSOCK_FREE_SIMS`, `RNG_END_STATE`).

Because the draws are the same, **restoring the windsock does not change any other part of any world**: swap the draw-only loop for
the original loop (below) and the same seed gives the same world as before the removal, windsocks included.

## Steps to put it back (sim)

1. `sim/objects.py`, constants: restore `windsock` in `SCENERY_KINDS`, `SWAYERS` and `DAMAGE_VT`, and `"windsock": 12.0` in
   `SPOT_MARGIN` (the old lines are the first four pieces of `sim_windsock.py.txt`).
2. `sim/objects.py`, `generate_world`:
   * add `"windsock": (foot, pond_spans, street_spans)` to `avoid_sets` (or the better set, see above);
   * in `flat_spot`, restore the half width line: `half = 0.5 * (20.0 if kind != "windsock" else 8.0) + SPOT_MARGIN[kind]`;
   * replace the loop with the comment that says the draws are still made by the original placement loop (the piece
     "the placement loop" in `sim_windsock.py.txt`). Keep it after the snowpack loop and before the ids are numbered.
3. **Take `"windsock"` out of `RETIRED_KINDS`** (`sim/objects.py`, near the top). This is the step that is easy to forget:
   `ObjectField.from_state` cuts trailing objects of a retired kind off a save. With the windsock back in the game and still in
   `RETIRED_KINDS`, every save made with a windsock would lose it on load. If the set is empty afterwards, `_without_retired`
   is a no-op and can stay or go.
4. `sim/tests/_schema.py`: restore `windsock` in `SCENERY_KINDS` (last piece of `sim_windsock.py.txt`), or `validate_hello` rejects it.
5. Tests, from `sim_windsock_tests.py.txt`: put back `test_no_windsock_stands_on_a_building_a_street_or_a_pond`, the windsock
   entries in the spot tuples and kind loops of `test_objects.py` (headers say which test and old line), the old
   `PLAIN_WORLD_HASHES` in `test_presets.py` (valid only if nothing else in the generation changed since; otherwise take them
   again: sha256 of `json.dumps({"y": terrain.y, "s": settlements, "o": objects}, sort_keys=True, separators=(",", ":"))`
   of `generate_world(random.Random(seed), w, h, kind, mode)` for each key), and the windsock in the `spots` tuple of
   `test_plain_worlds_keep_terrain_settlements_and_buildings_when_the_ground_spots_moved` (its hashes stay what they are).
6. Delete what only makes sense while it is gone: `TestWindsockRemoved` in `sim/tests/test_objects.py` (it asserts there is
   no windsock; the `WINDSOCK_FREE_*` tables stay valid if the test filters `kind != "windsock"` before hashing), and
   `TestSaveFromBeforeTheWindsockWasRemoved` in `sim/tests/test_saveload.py` with its fixture
   `sim/tests/data/save_with_windsock_v2.sav` (a real save of the old code; it is an ordinary save once windsocks exist).
7. Check: `cd sim/tests` then `python -m unittest test_objects test_presets test_saveload test_tornado test_protocol_schema`, and
   `grep -ri windsock sim tools` should show the restored code.
8. Viewer: `VIEWER.md` in this folder.

## Old saves

A save made before the removal lists windsocks as its last objects. Loading one with the new code **drops them**
(`ObjectField._without_retired`): the list is cut where the windsocks start, so no id and no list index of any other object
changes, and the per-index state (`wet_f`, `snow_f`, `sway_f`, `dmg_f`, `active`, `changed`, `dmg_changed`, `storm_sway`,
`size_f`) is cut to the same length. The file on disk is not touched by a load; it loses the windsocks only when the game
saves it again. The save written by the old code that the test uses is `sim/tests/data/save_with_windsock_v2.sav`.

One difference that cannot be avoided: a tornado that was damaging a windsock threw debris from it, using the tornado stream
(`tor_rng`). Without the windsock those draws are not made, so a save loaded in the middle of a tornado that reaches where a
windsock stood continues differently from what the old code would have done. A tornado that does not reach it, and all other
weather, continues frame for frame (checked on four old saves, 150 frames each; and 120 frames under a stub EF3 over a house).
