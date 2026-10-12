package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Debug mode's ninth tab, <b>Flood</b> (PLAN Phase L, then Phase N): protocol v2.4.1 standing water, the film that
 * runs over the ground, storm drains and flooding on a built-in world, without Python. The world is {@link FakeSim}'s
 * {@code flood} scene (a city beside a hollow, a village on a hilltop with the road falling away east of it, a hamlet
 * in a hollow of its own, two more hollows in the west) with {@link FakeWater}, the Java copy of the simulation's
 * water model; what is drawn is the game's own renderer ({@link WaterPainter}, {@link FlowPainter},
 * {@link DrainPainter}) with the pipe overlay switched on.
 * <p>
 * The rain is set here (the fake's own {@code rain} command, hits a second per 32 px bucket, the unit of the model);
 * the drain sizes go through the protocol's real {@code drains} command; the slope control builds the world again
 * with the road beyond the village gentle, steep or a cliff. The upper picture follows the settlement picked (the
 * village and its run, the city and its hollow, the hamlet below the cliff), the lower one shows the western hollows:
 * a natural pool that fills far past the 140 px a puddle used to be capped at and spills into the next hollow. The
 * numbers are read from {@code frame.flood} and {@code frame.flow}.
 */
final class DebugFlood {
    private DebugFlood() {}

    static final class FloodTab extends DebugScreen.Tab {
        /** The six speeds of the game ({@code ShowcaseScreen.SPEEDS} is private). */
        static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
        static final String[] SPEED_NAMES = {"¼×", "½×", "1×", "2×", "4×", "8×"};
        static final double RATE_MAX = 16, STORM_S = 60;
        /** The measured rain classes of the simulation, hits a second per bucket (PLAN Phase L, the rain-delivery table). */
        static final double[] MARKS = {2.5, 3.4, 4.3, 8};
        static final String[] MARK_NAMES = {"Drizzle", "Steady", "Downpour", "Supercell"};
        /** The pipe sizes the two towns start with here: a small-drained village beside a large-drained city. */
        static final double VILLAGE_SIZE = FakeWater.SMALL, CITY_SIZE = FakeWater.LARGE;
        static final Color[] SERIES = {new Color(120, 190, 255), new Color(255, 176, 96), new Color(150, 226, 150)};
        /** The settlements of the scene, in the picker's order, and how the road falls away beyond the village. */
        static final String[] PICKS = {"village", "city", "hamlet"};
        static final String[] SLOPE_NAMES = {"Gentle", "Steep", "Cliff"};

        private final DebugScreen ds;
        DebugWorlds.Pane pane;
        private Second hollows;
        /** The rain rate chosen, and whether it is falling. */
        double rate = 8;
        boolean raining, paused;
        /** Sim time at which the storm in progress ends (NaN = none). */
        double stormUntil = Double.NaN;
        int speedIdx = 2, pick;
        /** v2.4.1: the run beyond the village (an index into {@link FakeSim#FLOOD_RUNS}): gentle, steep, a cliff. */
        int slopeIdx;
        /** The settlements of the scene: the village, the city, the hamlet (-1 = not found). */
        final long[] town = {-1, -1, -1};
        /** {t, depth of the village, of the city, of the hamlet}: the strip at the bottom of the controls. */
        final List<double[]> history = new ArrayList<>();
        private long builtSeed = -1;
        private final List<String[]> pending = new ArrayList<>();
        private Rectangle2D.Double topRect = new Rectangle2D.Double(), botRect = new Rectangle2D.Double();

        FloodTab(DebugScreen ds) { this.ds = ds; }

        /** A second view of the pane's world with a camera and a renderer of its own. */
        private static final class Second {
            final ParallelRenderer pr = new ParallelRenderer(ParallelRenderer.defaultThreads());
            final Camera cam = new Camera();
            private BufferedImage img;
            private boolean placed;
            private double animSec;

            Second(boolean still) {
                if (still) pr.shared.setAsync(false);
                DebugWorlds.style(pr.primary());
            }

            void render(DebugWorlds.Pane src, BufferedImage buf, double scale, Rectangle2D.Double r, double dt, java.util.function.Consumer<Camera> placer) {
                int pw = Math.max(64, (int) Math.floor(r.width)), ph = Math.max(64, (int) Math.floor(r.height));
                int dw = (int) Math.ceil(pw * scale), dh = (int) Math.ceil(ph * scale);
                if (img == null || img.getWidth() != dw || img.getHeight() != dh) { img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB); placed = false; }
                pr.layout(cam, src.st, pw, ph);
                if (!placed && cam.initialised) { placed = true; placer.accept(cam); }
                cam.update(dt);
                animSec += dt;
                Model.Frame f = src.frame();
                pr.render(img, scale, 0, 0, pw, ph, src.st, cam, src.still ? -1 : System.nanoTime(), src.still && f != null ? f.t : animSec, "debug");
                Graphics2D g = buf.createGraphics();
                g.drawImage(img, (int) Math.round(r.x * scale), (int) Math.round(r.y * scale), null);
                g.dispose();
            }

            void dispose() { pr.shutdown(); }
        }

        @Override void leave() {
            if (pane != null) pane.dispose();
            if (hollows != null) hollows.dispose();
            pane = null;
            hollows = null;
            builtSeed = -1;
            history.clear();
            stormUntil = Double.NaN;
            raining = false;
        }

        /**
         * Snapshots and tests: {@code rate=8}, {@code rain=on|off}, {@code storm} (start one), {@code run=SECONDS} (advance the
         * world that long at once), {@code drains=small|normal|large|random}, {@code village=SIZE}, {@code city=SIZE},
         * {@code pick=village|city|hamlet}, {@code slope=gentle|steep|cliff} (the world starts again), {@code speed=1}.
         * Applied in the order given, once the world is up.
         */
        @Override void option(String key, String value) {
            if (pane == null) pending.add(new String[]{key, value});
            else apply(key, value);
        }

        private void apply(String key, String value) {
            switch (key) {
                case "rate" -> setRate(Double.parseDouble(value));
                case "rain" -> setRaining(!value.equals("off"));
                case "storm" -> storm();
                case "run" -> run(Double.parseDouble(value));
                case "drains" -> mode(value);
                case "village" -> size(0, Double.parseDouble(value));
                case "city" -> size(1, Double.parseDouble(value));
                case "pick" -> setPick(Math.max(0, java.util.Arrays.asList(PICKS).indexOf(value)));
                case "slope" -> {
                    int k = java.util.Arrays.asList(FakeSim.FLOOD_RUNS).indexOf(value);
                    if (k >= 0 && k != slopeIdx) { slopeIdx = k; build(); }
                }
                case "speed" -> { for (int k = 0; k < SPEEDS.length; k++) if (SPEEDS[k] == Double.parseDouble(value)) setSpeed(k); }
                default -> { }
            }
        }

        private static String cmd(String body) { return "{\"cmd\":" + body + "}"; }

        private void sendRain() { if (pane != null) pane.cmd(cmd(String.format(Locale.ROOT, "\"rain\",\"rate\":%.2f", raining ? rate : 0))); }

        void setRate(double r) {
            rate = Model.clamp(Math.round(r * 10) / 10.0, 0, RATE_MAX);
            sendRain();
        }

        void setRaining(boolean on) {
            raining = on;
            stormUntil = Double.NaN;
            sendRain();
        }

        /** Rain at the chosen rate for {@link #STORM_S} simulated seconds, then it stops by itself. */
        void storm() {
            Model.Frame f = pane == null ? null : pane.frame();
            if (f == null) return;
            raining = true;
            stormUntil = f.t + STORM_S;
            sendRain();
        }

        /** Every settlement gets the size of a mode again (the protocol's {@code drains} command). */
        void mode(String m) { if (pane != null) pane.cmd(cmd("\"drains\",\"mode\":\"" + m + "\"")); }

        void setPick(int k) {
            pick = Math.max(0, Math.min(PICKS.length - 1, k));
            if (pane != null) pane.replace();              // the upper picture goes to that settlement
        }

        /** The road beyond the village: gentle, steep or a cliff. The world is built again (dry). */
        void setSlope(int k) {
            if (k == slopeIdx || k < 0 || k >= SLOPE_NAMES.length) return;
            slopeIdx = k;
            builtSeed = -1;
        }

        /** {h, q, world x of the cell's centre, direction} of the film at world x as last sent, or null when the ground is dry there. */
        double[] flowAt(double x) {
            if (pane == null) return null;
            Model.Hello h = pane.hello();
            if (h == null || h.flow == null) return null;
            int k = (int) Math.round(x / h.flow.cell);
            synchronized (pane.st) {
                for (Model.Run r : pane.st.flow) {
                    int i = (k - r.k0) * r.dir;
                    if (i >= 0 && i < r.h.length) return new double[]{r.h[i], r.q[i], k * h.flow.cell, r.dir};
                }
            }
            return null;
        }

        /** Where the stream is read when the pointer is not on it: just beyond the low end of the settlement picked (the top of the cliff for the hamlet). */
        double probeX() {
            Model.Settlement s = settlement(pick);
            if (s == null) return 0;
            return pick == 1 ? s.x1 - 24 : pick == 2 ? s.x0 - 60 : s.x1 + 80;
        }

        /** Set one town's pipe size (0 = the village, 1 = the city, 2 = the hamlet). */
        void size(int which, double px) {
            if (pane != null && town[which] >= 0) pane.cmd(cmd(String.format(Locale.ROOT, "\"drains\",\"settlement\":%d,\"size\":%.1f", town[which], px)));
        }

        void setSpeed(int k) {
            speedIdx = Math.max(0, Math.min(SPEEDS.length - 1, k));
            if (pane != null) pane.cmd(cmd("\"speed\",\"value\":" + SPEEDS[speedIdx]));
        }

        private void togglePause() {
            if (pane == null) return;
            paused = !paused;
            pane.cmd(cmd(paused ? "\"pause\"" : "\"resume\""));
        }

        /** Advance the world by {@code seconds} of simulated time at once (stills and tests). */
        void run(double seconds) {
            if (pane == null) return;
            Model.Frame f0 = pane.frame();
            double end = (f0 == null ? 0 : f0.t) + seconds;
            for (int k = 0; k < 200_000; k++) {
                Model.Frame f = pane.frame();
                if (f == null || f.t >= end - 1e-6 || f.paused) break;
                pane.step(1);
                tick();
            }
        }

        /** After every step (or every painted frame): end a storm that has run its time, and note the depths. */
        private void tick() {
            Model.Frame f = pane.frame();
            if (f == null) return;
            if (!Double.isNaN(stormUntil) && f.t >= stormUntil) { stormUntil = Double.NaN; raining = false; sendRain(); }
            // (a day of the fake lasts five minutes: the clock is held in the late morning so that a long flood is not watched in the dark)
            if (f.timeOfDay > 0.53 || f.timeOfDay < 0.40) pane.cmd(cmd("\"hour\",\"value\":10.6"));
            if (!history.isEmpty() && f.t - history.get(history.size() - 1)[0] < 0.25) return;
            double[] row = {f.t, 0, 0, 0};
            synchronized (pane.st) {
                for (int k = 0; k < town.length; k++) { Model.Flood fl = pane.st.floodOf(town[k]); if (fl != null) row[1 + k] = fl.depth; }
            }
            history.add(row);
            if (history.size() > 2400) history.remove(0);
        }

        Model.Flood flood(int which) {
            if (pane == null) return null;
            synchronized (pane.st) { return pane.st.floodOf(town[which]); }
        }

        Model.Drain drain(int which) {
            if (pane == null) return null;
            synchronized (pane.st) { return pane.st.drains.get(town[which]); }
        }

        private Model.Settlement settlement(int which) {
            Model.Hello h = pane == null ? null : pane.hello();
            if (h != null) for (Model.Settlement s : h.settlements) if (s.id == town[which]) return s;
            return null;
        }

        private void build() {
            leave();
            FakeSim sim = new FakeSim(ds.seed, DebugScreen.LIGHT_TOD[0], DebugWorlds.WORLD_W, DebugWorlds.WORLD_H, null, null, Double.NaN, Double.NaN,
                    slopeIdx == 0 ? "flood" : "flood:" + FakeSim.FLOOD_RUNS[slopeIdx]);
            pane = new DebugWorlds.Pane(sim, ds.still);
            hollows = new Second(ds.still);
            town[0] = -1; town[1] = -1; town[2] = -1;
            for (Model.Settlement s : pane.hello().settlements) {
                if (s.kind.equals("village") && town[0] < 0) town[0] = s.id;
                if ((s.kind.equals("city") || s.kind.equals("town")) && town[1] < 0) town[1] = s.id;
                if (s.kind.equals("hamlet") && town[2] < 0) town[2] = s.id;
            }
            raining = false;
            stormUntil = Double.NaN;
            sendRain();                                   // the scene would rain by itself: here the rain is the tab's
            size(0, VILLAGE_SIZE);
            size(1, CITY_SIZE);
            pane.cmd(cmd("\"speed\",\"value\":" + SPEEDS[speedIdx]));
            paused = false;
            pane.placer = p -> {
                // the settlement picked and where its water goes: the village and the road down to the hollow at the bottom,
                // the city and the hollow beside it, the steep stretch, the cliff and the hamlet below it
                Model.Settlement v = settlement(0), c = settlement(1), m = settlement(2);
                Model.Hello h = p.hello();
                double x0, x1;
                if (pick == 1 && c != null) { x0 = c.x0 - 70; x1 = c.x1 + 300; }
                else if (pick == 2 && m != null) { x0 = m.x0 - 340; x1 = m.x1 + 90; }
                else { x0 = (v != null ? v.x0 : h.w * 0.3) - 30; x1 = (v != null ? v.x1 : h.w * 0.5) + (slopeIdx == 0 ? 400 : 290); }
                double z = Model.clamp(p.cam.vw / (x1 - x0), p.cam.minZoom(), Camera.MAX_ZOOM), xm = (x0 + x1) / 2, lo = 1e18, hi = -1e18;
                for (double x = x0; x <= x1; x += 16) { lo = Math.min(lo, h.terrain.at(x)); hi = Math.max(hi, h.terrain.at(x)); }
                p.cam.set(xm, (lo + hi) / 2 - p.cam.vh / z * 0.06, z, true);            // the ground a little below the middle: room for the pipes under the streets
            };
            for (Renderer r : new Renderer[]{pane.r(), hollows.pr.primary()}) { r.showDrains = true; r.showDrainCard = false; r.showLabels = true; }
            builtSeed = ds.seed;
            pane.step(2);
            tick();
            List<String[]> todo = new ArrayList<>(pending);
            pending.clear();
            for (String[] o : todo) apply(o[0], o[1]);
        }

        /** Where the lower picture looks: the two western hollows, from the far rim of the first to the bank at the city's edge. */
        private void placeHollows(Camera cam) {
            FakeWater w = pane.sim.water();
            double x0 = 360, x1 = 960;
            if (w != null && w.basinCount() >= 2) {
                // the hollows west of the city
                Model.Settlement c = settlement(1);
                double lim = c != null ? c.x0 : 1e9, a = Double.NaN, b = Double.NaN;
                for (int k = 0; k < w.basinCount(); k++) {
                    double[] q = w.basin(k);
                    if (q[2] > lim || q[1] < 300) continue;
                    if (Double.isNaN(a)) a = Math.max(q[0], q[1] - 190);
                    b = q[2];
                }
                if (!Double.isNaN(a)) { x0 = a - 30; x1 = b + 40; }
            }
            double z = Model.clamp(cam.vw / (x1 - x0), cam.minZoom(), Camera.MAX_ZOOM), xm = (x0 + x1) / 2;
            cam.set(xm, pane.hello().terrain.at(xm) + 10 - cam.vh / z * 0.22, z, true);
        }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            if (pane == null || builtSeed != ds.seed) build();
            pane.pump(dt);
            tick();
            Rectangle2D.Double m = DebugWorlds.below(DebugScreen.main(r));
            double cap = DebugWorlds.CAP, hTop = Math.floor((m.height - cap - 4) * 0.56);
            topRect = new Rectangle2D.Double(m.x, m.y, m.width, hTop);
            botRect = new Rectangle2D.Double(m.x, m.y + hTop + 4 + cap, m.width, Math.max(60, m.height - hTop - 4 - cap));
            pane.render(buf, scale, topRect, dt);
            hollows.render(pane, buf, scale, botRect, dt, this::placeHollows);
        }

        @Override void mouse(int type, int x, int y, int button) { if (pane != null) pane.mouse(type, x, y, button); }

        @Override void wheel(int x, int y, double rotation) { if (pane != null) pane.wheel(x, y, rotation); }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_SPACE, KeyEvent.VK_P -> togglePause();
                case KeyEvent.VK_S, KeyEvent.VK_ENTER -> storm();
                case KeyEvent.VK_R -> { builtSeed = -1; }
                case KeyEvent.VK_RIGHT -> setRate(rate + 0.5);
                case KeyEvent.VK_LEFT -> setRate(rate - 0.5);
                case KeyEvent.VK_UP -> setSpeed(speedIdx + 1);
                case KeyEvent.VK_DOWN -> setSpeed(speedIdx - 1);
                case KeyEvent.VK_TAB -> setPick((pick + 1) % PICKS.length);
                case KeyEvent.VK_G -> setSlope((slopeIdx + 1) % SLOPE_NAMES.length);
                default -> { }
            }
        }

        private static String name(Model.Settlement s) { return s == null ? "—" : s.name.isEmpty() ? s.kind : s.name; }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            if (pane == null) return;
            Model.Frame f = pane.frame();
            for (Rectangle2D.Double pr : new Rectangle2D.Double[]{topRect, botRect}) ui.stroke(pr.x, pr.y, pr.width, pr.height, 4, new Color(120, 180, 255, 80), 1f);
            String rain = !raining ? "no rain" : String.format(Locale.ROOT, "raining %.1f hits/s per bucket%s", rate,
                    Double.isNaN(stormUntil) || f == null ? "" : String.format(Locale.ROOT, ", %.0f s of the storm left", Math.max(0, stormUntil - f.t)));
            double y1 = topRect.y - 8, y2 = botRect.y - 8;
            ui.text("TOWNS", topRect.x + 2, y1, Ui.F_BOLD, Ui.ACCENT);
            ui.textFit(rain + "   ·   the stream is as thick as the flow; pipes as thick as their size, filled to inflow / capacity   ·   drag to pan, wheel to zoom", topRect.x + 62, y1, topRect.width - 66, Ui.F_NORM, Ui.TEXT);
            streamOverlay(ui);
            ui.text("HOLLOWS", botRect.x + 2, y2, Ui.F_BOLD, Ui.ACCENT);
            ui.textFit(hollowText(), botRect.x + 76, y2, botRect.width - 80, Ui.F_NORM, Ui.TEXT);

            double[] s = DebugScreen.side(ui, r);
            double x = s[0], y = s[1], w = s[2];
            // ---- rain
            DebugScreen.label(ui, String.format(Locale.ROOT, "RAIN   %.1f hits/s per 32 px bucket", rate), x, y);
            y += 14;
            ui.g.setColor(new Color(255, 226, 140, 150));
            for (double mk : MARKS) ui.g.fill(new Rectangle2D.Double(x + 8 + (w - 16) * mk / RATE_MAX - 0.75, y + 2, 1.5, 6));
            ui.slider(rate / RATE_MAX, x, y, w, v -> setRate(v * RATE_MAX));
            y += 26;
            double bw = (w - 9) / 4;
            for (int k = 0; k < MARKS.length; k++) {
                final double mk = MARKS[k];
                DebugScreen.pick(ui, MARK_NAMES[k], Math.abs(rate - mk) < 0.05, x + k * (bw + 3), y, bw, 24, () -> setRate(mk));
            }
            y += 30;
            double hw = (w - 6) / 2;
            ui.button(Double.isNaN(stormUntil) ? "Storm (60 s)" : "Storm running…", x, y, hw, 30, Ui.PRIMARY, true, false, this::storm);
            ui.button(raining ? "Stop the rain" : "Rain on", x + hw + 6, y, hw, 30, Ui.NORMAL, true, false, () -> setRaining(!raining));
            y += 38;
            // ---- drains
            String mode;
            synchronized (pane.st) { mode = pane.st.drainMode; }
            DebugScreen.label(ui, "DRAINS OF EVERY TOWN" + (mode.equals("custom") ? "   (now: custom)" : ""), x, y);
            y += 16;
            ui.seg(DrainCard.MODE_LABELS, java.util.Arrays.asList(DrainCard.MODES).indexOf(mode), x, y, w, 28, k -> mode(DrainCard.MODES[k]));
            y += 36;
            Model.Drain d = drain(pick);
            DebugScreen.label(ui, d == null ? "PIPE OF" : String.format(Locale.ROOT, "PIPE OF   %s px · %s · cap %.0f px²/s", DrainPainter.trim(d.size), d.cls, d.cap), x, y);
            y += 16;
            ui.seg(new String[]{name(settlement(0)), name(settlement(1)), name(settlement(2))}, pick, x, y, w, 28, this::setPick);
            y += 30;
            Model.Hello h = pane.hello();
            double lo = h.drains != null ? h.drains.sizeMin : FakeWater.SIZE_MIN, hi = h.drains != null ? h.drains.sizeMax : FakeWater.SIZE_MAX;
            ui.slider(d == null ? 0 : (d.size - lo) / (hi - lo), x, y, w, v -> size(pick, Math.round((lo + v * (hi - lo)) * 10) / 10.0));
            y += 30;
            // ---- v2.4.1: the run beyond the village, and what frame.flow says of the stream
            DebugScreen.label(ui, "ROAD BEYOND THE VILLAGE", x, y);
            y += 16;
            ui.seg(SLOPE_NAMES, slopeIdx, x, y, w, 26, this::setSlope);
            y += 31;
            ui.textFit(streamText(), x, y + 10, w, Ui.F_SMALL, Ui.TEXT);
            y += 18;
            // ---- time
            ui.button(paused ? "Resume" : "Pause", x, y, 70, 28, paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
            ui.seg(SPEED_NAMES, speedIdx, x + 76, y, w - 76, 28, this::setSpeed);
            y += 36;
            // ---- what frame.flood says
            for (int k = 0; k < town.length; k++) {
                Model.Flood fl = flood(k);
                Model.Drain dk = drain(k);
                ui.g.setColor(SERIES[k]);
                ui.g.fill(new Rectangle2D.Double(x, y + 3, 3, 11));
                ui.textFit(name(settlement(k)), x + 8, y + 13, 84, Ui.F_BOLD, Ui.TEXT);
                String st = fl == null ? "dry" : fl.state;
                ui.text(fl != null && fl.backed ? st + " ←" : st, x + 96, y + 13, Ui.F_BOLD, DrainPainter.stateColor(st));      // (← = a hollow beside it has backed up over the street)
                ui.textRight(fl == null ? "" : String.format(Locale.ROOT, "%.1f px · %.0f / %.0f", fl.depth, fl.inflow, fl.cap > 0 ? fl.cap : dk == null ? 0 : dk.cap), x + w, y + 13, Ui.F_SMALL, Ui.TEXT);
                y += 17;
            }
            y += 4;
            // ---- depth against time: the flood rises, then drains
            double gh = Math.min(74, r.y + r.height - 12 - y - 14);
            if (gh >= 34) {
                DebugScreen.label(ui, "DEPTH ON THE STREET, LAST 3 MIN", x, y);
                y += 15;
                strip(ui, x, y, w, gh, h);
                y += gh + 6;
            }
            if (y + 46 + 8 < r.y + r.height - 8) y += ds.seedRow(ui, "WORLD SEED", x, y, w) + 6;
            for (String l : ui.wrap("Rain above what the drains take runs off the street as a sheet and on downhill to the lowest ground; only a hollow holds it (← = a hollow has backed up over the street). S = storm, Space = pause, G = slope, R = restart.", Ui.F_SMALL, w)) {
                if (y + 14 > r.y + r.height - 8) break;
                ui.text(l, x, y + 10, Ui.F_SMALL, Ui.DIM);
                y += 14;
            }
        }

        /** The stream where it is read ({@link #probeX}, or under the pointer): depth, flux and speed from {@code frame.flow}. */
        private String streamText() {
            double[] c = flowAt(probe);
            if (c == null) return String.format(Locale.ROOT, "stream at x %.0f: dry", probe);
            return String.format(Locale.ROOT, "stream at x %.0f: h %.2f px, q %.1f px²/s, %.0f px/s", c[2], c[0], c[1], c[0] > 0 ? c[1] / c[0] : 0);
        }

        /** The numbers printed on the stream in the upper picture, at the pointer when it is over the picture, else at {@link #probeX}. */
        private void streamOverlay(Ui ui) {
            boolean pointed = topRect.contains(ui.mx, ui.my);
            probe = pointed ? pane.worldX(ui.mx) : probeX();
            double[] c = flowAt(probe);
            Model.Hello h = pane.hello();
            if (c == null || h == null) return;
            Camera cam = pane.cam;
            double sx = topRect.x + cam.vx + cam.vw / 2 + (c[2] - cam.cx) * cam.zoom, sy = topRect.y + cam.vy + cam.vh / 2 + (h.terrain.at(c[2]) - cam.cy) * cam.zoom;
            if (sx < topRect.x + 6 || sx > topRect.x + topRect.width - 6 || sy < topRect.y + 40 || sy > topRect.y + topRect.height - 4) return;
            String text = String.format(Locale.ROOT, "h %.2f px, q %.1f px²/s, %.0f px/s", c[0], c[1], c[0] > 0 ? c[1] / c[0] : 0);
            double tw = ui.width(text, Ui.F_SMALL) + 12, tx = Model.clamp(sx - tw / 2, topRect.x + 4, topRect.x + topRect.width - tw - 4), ty = sy - 44;
            ui.g.setStroke(new BasicStroke(1f));
            ui.g.setColor(new Color(232, 242, 255, 190));
            ui.g.draw(new Line2D.Double(sx, ty + 17, sx, sy - 4));
            ui.fill(tx, ty, tw, 17, 8, new Color(8, 12, 22, 205));
            ui.text(text, tx + 6, ty + 12.5, Ui.F_SMALL, new Color(232, 242, 255));
        }

        /** World x the stream readout is about in this frame. */
        private double probe;

        /** What the hollows are doing, from the water list: the widest natural pool and whether one is at its rim. */
        private String hollowText() {
            double widest = 0;
            int n = 0;
            Model.Settlement vil = settlement(1);
            final double lim = vil != null ? vil.x0 : 1e9;         // (the hollows of this picture lie west of the city)
            synchronized (pane.st) {
                for (Model.Body b : pane.st.water) if (!b.street() && b.x1 < lim) { n++; widest = Math.max(widest, b.x1 - b.x0); }
            }
            FakeWater w = pane.sim.water();
            boolean full = false;
            if (w != null) for (FakeWater.Pool p : w.pools) if (p.full && p.x1 < lim) full = true;
            if (n == 0) return "dry   ·   rain runs downhill into them and stands as a level surface on the ground";
            return String.format(Locale.ROOT, "%d %s, the widest %.0f px (a puddle used to stop at 140)%s", n, n == 1 ? "pool" : "pools", widest,
                    full ? "   ·   full to the rim: the excess spills into the next hollow" : "");
        }

        private void strip(Ui ui, double x, double y, double w, double gh, Model.Hello h) {
            Graphics2D g = ui.g;
            ui.fill(x, y, w, gh, 6, new Color(4, 8, 16, 220));
            ui.stroke(x, y, w, gh, 6, new Color(255, 255, 255, 30), 1f);
            // (v2.4.1: there is no curb; the upper mark is the depth from which a street is "overflowing". A v2.4 stream keeps its curb mark.)
            final boolean hasCurb = h.drains != null && h.drains.curb > 0;
            double curb = hasCurb ? h.drains.curb : h.drains != null ? h.drains.overflowDepth : FakeWater.OVERFLOW_DEPTH;
            double fd = h.drains != null ? h.drains.floodDepth : FakeWater.FLOOD_DEPTH, top = curb * (hasCurb ? 1.08 : 1.3);
            double span = 180, tEnd = history.isEmpty() ? 0 : history.get(history.size() - 1)[0];
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
            g.setColor(Renderer.alpha(DrainPainter.AMBER, 150));
            g.draw(new Line2D.Double(x + 2, y + gh - gh * fd / top, x + w - 2, y + gh - gh * fd / top));
            g.setColor(Renderer.alpha(DrainPainter.RED, 170));
            g.draw(new Line2D.Double(x + 2, y + gh - gh * curb / top, x + w - 2, y + gh - gh * curb / top));
            ui.text(hasCurb ? "curb" : "overflowing", x + 4, y + gh - gh * curb / top + 11, Ui.F_SMALL.deriveFont(9.5f), Renderer.alpha(DrainPainter.RED, 220));
            ui.text("flooded", x + 4, y + gh - gh * fd / top - 3, Ui.F_SMALL.deriveFont(9.5f), Renderer.alpha(DrainPainter.AMBER, 220));
            java.awt.Shape clip = g.getClip();
            g.clip(new Rectangle2D.Double(x + 1, y + 1, w - 2, gh - 2));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int k = 0; k < town.length; k++) {
                Path2D.Double p = new Path2D.Double();
                boolean first = true;
                for (double[] row : history) {
                    if (row[0] < tEnd - span) continue;
                    double px = x + w - 2 - (tEnd - row[0]) / span * (w - 4), py = y + gh - 1.5 - (gh - 3) * Math.min(top, row[1 + k]) / top;
                    if (first) p.moveTo(px, py); else p.lineTo(px, py);
                    first = false;
                }
                g.setColor(SERIES[k]);
                if (!first) g.draw(p);
            }
            g.setClip(clip);
        }
    }
}
