# The windsock in the viewer: what was removed and how to put it back

Removed from `viewer/src/precip` on 2026-10-10 (Phase L, session `phase-l-viewer-a-glint-dot-wind-droplets-20261010-194541`).
The removed text is in `viewer_windsock.java.txt` in this folder, unedited, in eight numbered pieces. The sim's side of the
windsock is in the sim agent's files next to this one (`SIM.md`, `sim_windsock.py.txt`, `sim_windsock_tests.py.txt`).

## What a windsock was, for the viewer

A plain object of the `hello` message: `kind: "windsock"`, `x`, `y` (the foot of the pole, on the ground), `w` 10, `h` 70
(the height of the pole). Its `sway` (-1 .. 1; the wind at the top of the pole divided by 14 m/s) arrived in the per-frame
object rows `[id, sway, snow, wet, w, h]`. It was drawn live every frame by `Renderer.drawWindsock`: a grey pole and a
five-band orange and white sock that droops in calm air, lifts and points downwind as `|sway|` grows, and ripples with the
animation clock. It was never part of the baked scenery tiles, took no snow and no wetness, and had no entry in the legend,
the help or the minimap.

## What the viewer does with a windsock now

Nothing. An old save, a replay or a simulation that still sends `windsock` objects is read without an error: the object stays
in `ViewerState.objects` (so its state rows still find their id) and no painter draws it, exactly like any other kind the
viewer has no picture for. `AdvancedTest` pins this (the check "a world with a "windsock" and an object of a kind nobody
knows ... is drawn exactly like the world without them").

## Putting it back (viewer only; the sim has to send the objects again, see `SIM.md`)

Piece numbers are those in `viewer_windsock.java.txt`. Line numbers there are from the file as it was before the removal;
find the places by the method names.

1. **`Renderer.java`, the method (piece 1).** Paste `drawWindsock(Graphics2D, Model.Obj)` back after `drawSnowpack`, before
   the `// ---- clouds` section heading. It needs nothing new: `anim`, `lit(Color)`, `Model.clamp`, `BasicStroke`, `Line2D`,
   `Path2D`, `AffineTransform` and `Color` are all still there. Optionally restore the section heading of piece 3
   (`water, snow, windsock`).
2. **`Renderer.java`, the dispatch (piece 2).** In `drawWorld`, in the loop over `objs` that draws the trees and the moving
   parts of buildings, put the line
   `} else if (o.kind.equals("windsock")) drawWindsock(g, o);`
   back between the tree branch and the `else if (o.building && BuildingPainter.hasLive(o.kind) ...)` branch (piece 2 shows
   the whole loop as it was). The comment that now sits in that last branch ("an object of any other kind ... is simply not
   drawn") stays true for every other unknown kind.
3. **`ViewerState.java`, `applyFrame` (piece 4).** Add `|| n.kind.equals("windsock")` back to the `live` test, so that a
   windsock's changing sway does not count as a change of the baked scenery (without this it still draws correctly; the
   test only saves needless `coverVersion` bumps).
4. **`FakeSim.java` (pieces 5 and 6), only if the built-in fake world should have windsocks again.** In the world
   generation, after the snowpacks, add the line that makes the two windsocks (piece 5; they must stay the last objects made,
   so that no other object's id changes). In `tick()`, put `"windsock"` back into the `case "tree", "windmill", "factory"`
   arm that sets `sway` from the wind, and into the `case "mountain", "hill"` arm that keeps snow and wetness off.
5. **Debug mode (piece 7), optional.** In `DebugWorlds.WeatherTab`: add `"windsock"` to `WATER`, the zoom
   `kind.equals("windsock") ? 2.4` in the placer of the `water` view, the last half-sentence of its note, and `"Windsock"` to
   the selector `ui.seg(new String[]{"Pond", "Puddle", "Snowpack"}, ...)`.
6. **`DebugTest.java` (piece 8), only together with step 5.** The weather check then again requires `"windsock"` among the
   kinds of the water scene.
7. **`AdvancedTest.java`.** The unknown-kind check uses a `windsock` as one of its two unknown objects and expects it not to
   be drawn. Once the windsock is drawn again, change that object's kind to another made-up name (the second object,
   `no_such_kind`, already covers the unknown case).
8. Build (`build.bat`) and run `java -cp viewer\out precip.Main --selftest-ui`.

## Where it was called from (summary)

| Piece | File | Place | Role |
|---|---|---|---|
| 1 | `Renderer.java` | `drawWindsock`, after `drawSnowpack` | the drawing |
| 2 | `Renderer.java` | `drawWorld`, the live-object loop | the only call |
| 3 | `Renderer.java` | section heading above `drawPond` | comment |
| 4 | `ViewerState.java` | `applyFrame`, object rows | "live" kinds that do not re-bake the tiles |
| 5 | `FakeSim.java` | world generation, after the snowpacks | the fake world's two windsocks |
| 6 | `FakeSim.java` | `tick()`, object update | sway from the wind; no snow, no wetness |
| 7 | `DebugWorlds.java` | `WeatherTab`: `WATER`, placer, note, selector | the Debug "Ground water" view |
| 8 | `DebugTest.java` | weather checks | the Debug water scene had to hold one |

Pictures of it: `viewer/snapshots/v2/phase-l/windsock_before.png` (the last build that drew it) and `windsock_after.png`
(the same world and camera now).
