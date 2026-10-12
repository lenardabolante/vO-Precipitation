package precip;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Settings: Background, Graphics, Advanced, Game, Controls and Sound. Every change is applied and written to
 * {@code config/settings.json} at once ({@link AppShell#settingsChanged}); there is no "apply".
 */
final class SettingsScreen extends Screen {
    static final String[] TABS = {"Background", "Graphics", "Advanced", "Game", "Controls", "Sound"};
    /** (Phase P: Sound is appended, so no older index moves.) */
    static final int BACKGROUND = 0, GRAPHICS = 1, ADVANCED = 2, GAME = 3, CONTROLS = 4, SOUND = 5;

    private final AppShell app;
    int tab;
    /** The action whose key is being chosen (Controls page); null = not rebinding. */
    Action capturing;
    /** The conflict message of the last rebinding, shown until the next change. */
    String keyWarning;

    SettingsScreen(AppShell app, int tab) {
        this.app = app;
        this.tab = tab;
    }

    @Override boolean capturesKeys() { return capturing != null; }

    private void changed() { app.settingsChanged(); }

    @Override void paint(Ui ui, int w, int h) {
        double[] d = ui.dialog(w, h, 900, 600, "Settings");
        double x = d[0], y = d[1], dw = d[2], dh = d[3];
        // tabs
        double ty = y + 62;
        for (int k = 0; k < TABS.length; k++) {
            final int idx = k;
            boolean sel = k == tab;
            if (sel) ui.fill(x + 16, ty, 150, 38, 10, new Color(58, 110, 200, 245));
            else if (ui.hover(x + 16, ty, 150, 38)) ui.fill(x + 16, ty, 150, 38, 10, new Color(255, 255, 255, 26));
            ui.text(TABS[k], x + 32, ty + 24, sel ? Ui.F_MEDB : Ui.F_MED, sel ? Color.WHITE : Ui.TEXT);
            ui.hit(x + 16, ty, 150, 38, () -> { tab = idx; capturing = null; });
            ty += 44;
        }
        ui.g.setColor(new Color(255, 255, 255, 22));
        ui.g.fill(new Rectangle2D.Double(x + 180, y + 58, 1, dh - 76));

        double cx = x + 204, cy = y + 62, cw = dw - 204 - 24, ch = dh - 62 - 66;
        switch (tab) {
            case BACKGROUND -> paintBackground(ui, cx, cy, cw, ch);
            case GRAPHICS -> paintGraphics(ui, cx, cy, cw);
            case ADVANCED -> paintAdvanced(ui, cx, cy, cw);
            case GAME -> paintGame(ui, cx, cy, cw);
            case SOUND -> paintSound(ui, cx, cy, cw);
            default -> paintControls(ui, cx, cy, cw, ch);
        }
        // (the Advanced page uses this line for the explanation of the switch under the pointer)
        String note = tab == ADVANCED && advHint != null ? advHint : "Changes are saved as you make them.";
        ui.textFit(note, cx, y + dh - 30, dw - 204 - 24 - 150, Ui.F_SMALL, tab == ADVANCED && advHint != null ? Ui.DIM : Ui.FAINT);
        ui.button("Done", x + dw - 24 - 130, y + dh - 56, 130, 38, Ui.PRIMARY, true, false, () -> app.pop(this));
    }

    // ------------------------------------------------------------------ background

    private void paintBackground(Ui ui, double x, double y, double w, double h) {
        Settings s = app.settings;
        java.awt.Graphics2D g = ui.g;
        double pw = Math.min(320, w * 0.5), ph = pw * 9 / 16;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(x, y, pw, ph, 10, 10));
        BufferedImage img = app.backdrop();
        if (img != null) {
            g.setColor(Color.BLACK);
            g.fill(new Rectangle2D.Double(x, y, pw, ph));
            double kx = pw / img.getWidth(), ky = ph / img.getHeight();
            double k = s.bgFit == Renderer.FIT_COVER ? Math.max(kx, ky) : Math.min(kx, ky);
            double iw = s.bgFit == Renderer.FIT_STRETCH ? pw : img.getWidth() * k, ih = s.bgFit == Renderer.FIT_STRETCH ? ph : img.getHeight() * k;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, (int) Math.round(x + (pw - iw) / 2), (int) Math.round(y + (ph - ih) / 2), (int) Math.round(iw), (int) Math.round(ih), null);
            g.setColor(new Color(4, 8, 20, (int) (255 * Model.clamp(s.bgDim, 0, 0.92))));
            g.fill(new Rectangle2D.Double(x, y, pw, ph));
        } else {
            g.setPaint(new GradientPaint(0, (float) y, new Color(64, 118, 196), 0, (float) (y + ph), new Color(170, 204, 236)));
            g.fill(new Rectangle2D.Double(x, y, pw, ph));
            ui.textCenter("Default sky", x + pw / 2, y + ph / 2 + 5, Ui.F_HEAD, new Color(255, 255, 255, 230));
        }
        g.setClip(clip);
        ui.stroke(x, y, pw, ph, 10, new Color(255, 255, 255, 60), 1f);

        double rx = x + pw + 22, rw = w - pw - 22;
        ui.text("Background picture", rx, y + 16, Ui.F_HEAD, Color.WHITE);
        if (img != null) {
            ui.textFit(s.bgFile, rx, y + 38, rw, Ui.F_NORM, Ui.TEXT);
            ui.text(app.backdropInfo(), rx, y + 56, Ui.F_NORM, Ui.DIM);
        } else {
            ui.text("None — the sky is painted by the game.", rx, y + 38, Ui.F_NORM, Ui.DIM);
        }
        ui.button(app.backdropBusy() ? "Loading…" : "Choose image…", rx, y + 74, Math.min(210, rw), 38, Ui.PRIMARY, !app.backdropBusy(), false, app::chooseBackgroundDialog);
        ui.button("Use default sky", rx, y + 120, Math.min(210, rw), 38, Ui.NORMAL, img != null, false, app::clearBackground);

        double ry = y + ph + 26, lw = 150;
        ui.text("Fit", x, ry + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Settings.FIT_NAMES, s.bgFit, x + lw, ry, 300, 34, k -> { s.bgFit = k; changed(); });
        ui.text(switch (s.bgFit) {
            case Renderer.FIT_CONTAIN -> "whole picture visible, black bars if needed";
            case Renderer.FIT_STRETCH -> "stretched to the window";
            default -> "fills the window, edges may be cropped";
        }, x + lw + 314, ry + 21, Ui.F_SMALL, Ui.FAINT);
        ry += 48;
        ui.text("Dim", x, ry + 17, Ui.F_MED, Ui.DIM);
        ui.slider(s.bgDim / 0.85, x + lw, ry, 300, v -> { s.bgDim = Math.round(v * 0.85 * 100) / 100.0; changed(); });
        ui.text(Math.round(s.bgDim * 100) + " %", x + lw + 314, ry + 17, Ui.F_MED, Ui.TEXT);
        ry += 44;
        ui.text("Tint with time of day", x, ry + 17, Ui.F_MED, Ui.DIM);
        ui.toggle(s.bgTint, x + lw, ry, () -> { s.bgTint = !s.bgTint; changed(); });
        ui.text("the picture darkens at night and warms at sunset, so the scene still reads", x + lw + 60, ry + 17, Ui.F_SMALL, Ui.FAINT);
        ry += 44;
        String err = app.backdropError();
        if (err != null) {
            List<String> lines = ui.wrap(err, Ui.F_NORM, w);
            for (String l : lines) { ui.text(l, x, ry + 8, Ui.F_NORM, Ui.RED); ry += 17; }
        } else {
            ui.text("Any picture on this computer works: PNG, JPEG, GIF, BMP and whatever else Java can read.", x, ry + 8, Ui.F_SMALL, Ui.FAINT);
            ui.text("You can also drop an image file onto the window. It is copied into config/backgrounds.", x, ry + 24, Ui.F_SMALL, Ui.FAINT);
        }
    }

    // ------------------------------------------------------------------ graphics

    private void paintGraphics(Ui ui, double x, double y, double w) {
        Settings s = app.settings;
        double lw = 170, fw = Math.min(380, w - lw);
        ui.text("Cloud quality", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Settings.QUALITY_NAMES, s.cloudQuality, x + lw, y, fw, 34, k -> { s.cloudQuality = k; changed(); });
        ui.text("Detail of the cloud shapes. Lower is faster; clouds repaint within a few seconds.", x + lw, y + 50, Ui.F_SMALL, Ui.FAINT);
        y += 78;
        ui.text("Render resolution", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Settings.SCALE_NAMES, s.renderScale, x + lw, y, fw, 34, k -> { s.renderScale = k; changed(); });
        ui.text(switch (s.renderScale) {
            case 0 -> "1.0× — fastest; soft on high-DPI displays";
            case 2 -> "the display's full resolution — sharpest text, most work per frame";
            default -> "up to 1.5× — sharp enough on high-DPI displays without the full cost";
        }, x + lw, y + 50, Ui.F_SMALL, Ui.FAINT);
        y += 78;
        ui.text("Fullscreen", x, y + 17, Ui.F_MED, Ui.DIM);
        ui.toggle(s.fullscreen, x + lw, y, app::toggleFullscreen);
        ui.text("key: " + KeyMap.keysLabel(s.keys.get(Action.FULLSCREEN)), x + lw + 62, y + 17, Ui.F_SMALL, Ui.FAINT);
        y += 50;
        ui.text("Show FPS", x, y + 17, Ui.F_MED, Ui.DIM);
        ui.toggle(s.showFps, x + lw, y, () -> { s.showFps = !s.showFps; changed(); });
        ui.text("frames per second in the corner of the game view", x + lw + 62, y + 17, Ui.F_SMALL, Ui.FAINT);
        y += 50;
        ui.text("Dim under clouds", x, y + 17, Ui.F_MED, Ui.DIM);
        ui.toggle(s.cloudShade, x + lw, y, () -> { s.cloudShade = !s.cloudShade; changed(); });
        ui.text("the view darkens smoothly while the camera is under a cloud; the rain stands out", x + lw + 62, y + 17, Ui.F_SMALL, Ui.FAINT);
        y += 42;
        ui.text("Dimming strength", x, y + 17, Ui.F_MED, s.cloudShade ? Ui.DIM : Ui.FAINT);
        if (s.cloudShade) ui.slider((s.cloudShadeStrength - 0.1) / 0.9, x + lw, y, Math.min(300, fw), v -> { s.cloudShadeStrength = Math.round((0.1 + v * 0.9) * 100) / 100.0; changed(); });
        ui.text(s.cloudShade ? Math.round(s.cloudShadeStrength * 100) + " %" : "off", x + lw + Math.min(300, fw) + 14, y + 17, Ui.F_MED, s.cloudShade ? Ui.TEXT : Ui.FAINT);
        y += 46;
        ui.text("Epilepsy-safe lightning", x, y + 17, Ui.F_MED, Ui.DIM);
        ui.toggle(s.epilepsySafe, x + lw, y, () -> { s.epilepsySafe = !s.epilepsySafe; changed(); });
        ui.text("no flashes: lightning casts no light, and the bolts stay dim and fade in and out slowly", x + lw + 62, y + 17, Ui.F_SMALL, Ui.FAINT);
    }

    // ------------------------------------------------------------------ advanced

    /**
     * The eleven on/off switches of the Advanced page: the left column, then the right one ({@link Advanced} bit, label,
     * explanation). The twelfth bit, {@link Advanced#GLINT_STAR}, is the "Rain glint" choice drawn under the right column.
     */
    static final int[] ADV_ORDER = {Advanced.DETAIL_OFF, Advanced.GLOBAL, Advanced.BRANCH_OFF, Advanced.NO_FLASH, Advanced.SIMPLE, Advanced.NO_TINT,
            Advanced.CULL, Advanced.NO_SNOW, Advanced.NO_TRAILS, Advanced.NO_SHINE, Advanced.NO_PUDDLE};
    static final String[] ADV_LABELS = {"Lightning detail off", "Global lightning", "Lightning branching off", "No flash", "Simple clouds", "No tint",
            "Don't render what you can't see", "No snow", "No trails", "No shining particles", "No puddle impact"};
    static final String[] ADV_HINTS = {
            "Lightning still lights its surroundings, but casts no shadows and the cloud does not glow from within.",
            "Every bolt lights the whole view evenly, wherever it strikes - even far off screen. No shadows.",
            "Bolts are drawn as a single channel, without side branches.",
            "Lightning lights nothing at all; the bolts themselves look as always.",
            "Clouds are flat, evenly coloured shapes: much faster to repaint. They change within a few seconds.",
            "No colour casts from the time of day (blue nights, orange dusk). It still gets dark at night.",
            "Skips rain and snow that a cloud hides completely. The picture is the same; it seldom finds much.",
            "Nothing snow is drawn: no flakes, no snow on the ground, roofs, trees or mountains.",
            "Raindrops and sleet are drawn as dots, without their streaks.",
            "No glints on the rain, no twinkling ice crystals, no sparkle on snow heaps.",
            "No ripples on ponds and puddles and no splashes where drops land."};
    /** How many of {@link #ADV_ORDER} stand in the left column. */
    static final int ADV_LEFT = 6;
    /** The explanation of the Advanced switch under the pointer (null = none); shown on the dialog's bottom line. */
    private String advHint;

    /** The explanation shown on the bottom line right now (tests). */
    String advHint() { return advHint; }
    /** The explanation of the "Rain glint" choice (the last control of the right column: {@link Advanced#GLINT_STAR}, shown as Dot | Star). */
    static final String GLINT_HINT = "The shining drop in the rain: a tiny bright dot, or the four-point star it used to be.";
    /**
     * Test hook: where the controls of the Advanced page were last drawn (dialog coordinates x, y, w, h), value rows first, then
     * {@link #ADV_ORDER}, then the "Rain glint" choice.
     */
    final double[][] advRects = new double[2 + ADV_ORDER.length + 1][];

    private void paintAdvanced(Ui ui, double x, double y, double w) {
        Settings s = app.settings;
        double lw = 170, fw = Math.min(380, w - lw);
        advHint = null;
        ui.text("Max particles", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Advanced.PARTICLE_NAMES, s.adv.particleIndex(), x + lw, y, fw, 34, k -> { s.adv = s.adv.withParticles(Advanced.PARTICLES[k]); changed(); });
        ui.textFit("Share of the rain, snow and cloud particles that is drawn.", x + lw, y + 49, w - lw, Ui.F_SMALL, Ui.FAINT);
        advRects[0] = new double[]{x + lw, y, fw, 34};
        y += 60;
        ui.text("Render resolution", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Advanced.SCALE_NAMES, s.adv.renderScale, x + lw, y, fw, 34, k -> { s.adv = s.adv.withRenderScale(k); changed(); });
        ui.textFit(s.adv.renderScale == 0 ? "As set on the Graphics page."
                : "The game is drawn smaller and stretched: faster, but soft. The Graphics value stays the upper limit.", x + lw, y + 49, w - lw, Ui.F_SMALL, Ui.FAINT);
        advRects[1] = new double[]{x + lw, y, fw, 34};
        y += 60;
        ui.text("Switches", x, y + 14, Ui.F_HEAD, Color.WHITE);
        y += 28;
        double colW = w / 2;
        for (int k = 0; k < ADV_ORDER.length; k++) {
            final int bit = ADV_ORDER[k];
            double rx = x + (k < ADV_LEFT ? 0 : colW), ry = y + (k < ADV_LEFT ? k : k - ADV_LEFT) * 38;
            boolean hov = ui.hover(rx, ry - 4, colW - 8, 32);
            if (hov) { ui.fill(rx - 6, ry - 4, colW - 6, 32, 8, new Color(255, 255, 255, 14)); advHint = ADV_HINTS[k]; }
            ui.toggle(s.adv.flag(bit), rx, ry, () -> { s.adv = s.adv.toggle(bit); changed(); });
            ui.textFit(ADV_LABELS[k], rx + 58, ry + 17, colW - 58 - 12, Ui.F_MED, s.adv.flag(bit) ? Ui.TEXT : Ui.DIM);
            advRects[2 + k] = new double[]{rx, ry, 46, 24};
        }
        // the shape of the rain glint: not an on/off question, so a choice of two in the free row under the right column
        double gx = x + colW, gy = y + (ADV_ORDER.length - ADV_LEFT) * 38, gw = 104;
        if (ui.hover(gx, gy - 4, colW - 8, 32)) { ui.fill(gx - 6, gy - 4, colW - 6, 32, 8, new Color(255, 255, 255, 14)); advHint = GLINT_HINT; }
        ui.seg(Advanced.GLINT_NAMES, s.adv.glintStar ? 1 : 0, gx, gy - 1, gw, 26, k -> { s.adv = s.adv.with(Advanced.GLINT_STAR, k == 1); changed(); });
        ui.textFit("Rain glint", gx + gw + 12, gy + 17, colW - gw - 12 - 12, Ui.F_MED, s.adv.noShine ? Ui.FAINT : Ui.DIM);
        advRects[2 + ADV_ORDER.length] = new double[]{gx, gy - 1, gw, 26};
    }

    // ------------------------------------------------------------------ game

    private void paintGame(Ui ui, double x, double y, double w) {
        Settings s = app.settings;
        double lw = 170, fw = Math.min(440, w - lw);
        int ai = 2;
        String[] labels = new String[Settings.AUTOSAVE_MINUTES.length];
        for (int k = 0; k < labels.length; k++) {
            labels[k] = Settings.AUTOSAVE_MINUTES[k] == 0 ? "Off" : Settings.AUTOSAVE_MINUTES[k] + " min";
            if (Settings.AUTOSAVE_MINUTES[k] == s.autosaveMinutes) ai = k;
        }
        ui.text("Autosave every", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(labels, ai, x + lw, y, fw, 34, k -> { s.autosaveMinutes = Settings.AUTOSAVE_MINUTES[k]; changed(); });
        ui.text("A simulation is also saved on Save & Leave, on quitting and when the window is closed.", x + lw, y + 50, Ui.F_SMALL, Ui.FAINT);
        y += 84;
        ui.text("New simulations start with", x, y + 6, Ui.F_HEAD, Color.WHITE);
        y += 24;
        ui.text("World size", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Settings.SIZE_NAMES, s.defaultSize, x + lw, y, fw, 34, k -> { s.defaultSize = k; changed(); });
        ui.text(Settings.SIZES[s.defaultSize][0] + " × " + Settings.SIZES[s.defaultSize][1] + " px", x + lw, y + 50, Ui.F_SMALL, Ui.FAINT);
        y += 74;
        ui.text("Terrain", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(NewSimScreen.TERRAIN_LABELS, java.util.Arrays.asList(Settings.TERRAINS).indexOf(s.defaultTerrain), x + lw, y, fw, 34,
                k -> { s.defaultTerrain = Settings.TERRAINS[k]; changed(); });
        y += 54;
        ui.text("Settlements", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(NewSimScreen.SETTLEMENT_LABELS, java.util.Arrays.asList(Settings.SETTLEMENTS).indexOf(s.defaultSettlements), x + lw, y, fw, 34,
                k -> { s.defaultSettlements = Settings.SETTLEMENTS[k]; changed(); });
        ui.text("These are only the starting choices of the New simulation dialog.", x + lw, y + 50, Ui.F_SMALL, Ui.FAINT);
    }

    // ------------------------------------------------------------------ sound (Phase P)

    static final String[] SOUND_LABELS = {"Master volume", "Rain", "Thunder", "Traffic", "Towns", "Storm"};
    static final String[] SOUND_NOTES = {"everything", "while it rains or snows", "one clap per bolt", "cars passing by", "towns and cities", "siren, tornado, hurricane"};
    /** Test hook: where the controls of the Sound page were last drawn (x, y, w, h): the on/off switch, the six sliders, then the two Test buttons. */
    final double[][] soundRects = new double[1 + SOUND_LABELS.length + 2][];

    private static double soundValue(Settings s, int k) {
        return switch (k) { case 0 -> s.soundMaster; case 1 -> s.soundRain; case 2 -> s.soundThunder; case 3 -> s.soundTraffic; case 4 -> s.soundTowns; default -> s.soundStorm; };
    }

    private static void soundValue(Settings s, int k, double v) {
        v = Math.round(Model.clamp(v, 0, 1) * 100) / 100.0;
        switch (k) {
            case 0 -> s.soundMaster = v;
            case 1 -> s.soundRain = v;
            case 2 -> s.soundThunder = v;
            case 3 -> s.soundTraffic = v;
            case 4 -> s.soundTowns = v;
            default -> s.soundStorm = v;
        }
    }

    private void paintSound(Ui ui, double x, double y, double w) {
        Settings s = app.settings;
        boolean have = app.sound.available(), on = s.soundOn;
        double lw = 170, sw = Math.min(300, w - lw - 60);
        ui.text("Sound", x, y + 17, Ui.F_MED, Ui.DIM);
        ui.toggle(on, x + lw, y, () -> { s.soundOn = !s.soundOn; changed(); });
        ui.textFit(on ? "rain, thunder, traffic, towns and storms are heard" : "muted", x + lw + 62, y + 17, w - lw - 62, Ui.F_SMALL, Ui.FAINT);
        soundRects[0] = new double[]{x + lw, y, 46, 24};
        y += 46;
        for (int k = 0; k < SOUND_LABELS.length; k++) {
            final int idx = k;
            if (k == 1) {                                         // the five groups under the master
                ui.text("Volumes", x, y + 14, Ui.F_HEAD, Color.WHITE);
                y += 28;
            }
            ui.text(SOUND_LABELS[k], x, y + 17, Ui.F_MED, on ? Ui.DIM : Ui.FAINT);
            ui.slider(soundValue(s, k), x + lw, y, sw, v -> { soundValue(s, idx, v); changed(); });
            ui.text(Math.round(soundValue(s, k) * 100) + " %", x + lw + sw + 14, y + 17, Ui.F_MED, on ? Ui.TEXT : Ui.FAINT);
            ui.textFit(SOUND_NOTES[k], x + lw + sw + 70, y + 17, w - lw - sw - 70, Ui.F_SMALL, Ui.FAINT);
            soundRects[1 + k] = new double[]{x + lw, y, sw, 24};
            y += k == 0 ? 44 : 36;
        }
        y += 8;
        // the game is paused behind this dialog: the Test buttons are how a volume is heard from here
        ui.button("Test thunder", x + lw, y, 140, 34, Ui.NORMAL, have && on, false, () -> app.sound.preview("thunder"));
        ui.button("Test rain", x + lw + 152, y, 140, 34, Ui.NORMAL, have && on, false, () -> app.sound.preview("rain"));
        soundRects[1 + SOUND_LABELS.length] = new double[]{x + lw, y, 140, 34};
        soundRects[2 + SOUND_LABELS.length] = new double[]{x + lw + 152, y, 140, 34};
        y += 52;
        if (!have) {
            String why = Sound.problem != null ? Sound.problem : "There is no sound in this view.";
            for (String l : ui.wrap(why, Ui.F_NORM, w)) { ui.text(l, x, y + 8, Ui.F_NORM, Ui.AMBER); y += 17; }
            y += 4;
        }
        ui.textFit("Sound files: assets/sounds (credits in CREDITS.md)", x, y + 8, w, Ui.F_SMALL, Ui.FAINT);
    }

    // ------------------------------------------------------------------ controls

    private void paintControls(Ui ui, double x, double y, double w, double h) {
        Settings s = app.settings;
        Action[] all = Action.values();
        int rows = (all.length + 1) / 2;
        double rowH = controlsRowH(h), colW = (w - 20) / 2, keyW = 124;
        final boolean dense = rowH < 18.5;
        ui.text(capturing != null ? "Press the new key for \"" + capturing.label + "\"   ·   Esc cancels   ·   Backspace removes the key"
                : "Click a key, then press the new one.", x, y + 8, Ui.F_NORM, capturing != null ? Ui.ACCENT : Ui.DIM);
        double top = y + 22;
        for (int k = 0; k < all.length; k++) {
            Action a = all[k];
            double rx = x + (k / rows) * (colW + 20), ry = top + (k % rows) * rowH;
            List<Integer> codes = s.keys.get(a);
            boolean cap = a == capturing, none = codes.isEmpty();
            if (ui.hover(rx, ry, colW, rowH)) ui.fill(rx - 6, ry, colW + 6, rowH - 2, 6, new Color(255, 255, 255, 14));
            ui.textFit(a.label, rx, ry + rowH / 2 + (dense ? 3 : 4), colW - keyW - 12, dense ? Ui.F_SMALL : Ui.F_NORM, Ui.TEXT);
            double kx = rx + colW - keyW, kh = rowH - (dense ? 2.5 : 4);
            ui.fill(kx, ry, keyW, kh, 7, cap ? new Color(120, 96, 30, 250) : new Color(30, 40, 62, 245));
            ui.stroke(kx, ry, keyW, kh, 7, cap ? Ui.ACCENT : none ? Ui.AMBER : new Color(255, 255, 255, 40), cap || none ? 1.4f : 1f);
            String label = cap ? "press a key…" : none ? "unbound" : KeyMap.keysLabel(codes);
            java.awt.Font f = cap || none ? (dense ? Ui.F_SMALL : Ui.F_NORM) : dense ? Ui.F_SMALL.deriveFont(java.awt.Font.BOLD) : Ui.F_BOLD;
            String fitted = ui.fit(label, f, keyW - 10);
            ui.textCenter(fitted, kx + keyW / 2, ry + kh / 2 + (dense ? 3.5 : 4), f, cap ? Color.WHITE : none ? Ui.AMBER : Ui.ACCENT);
            ui.hit(kx, ry, keyW, kh, () -> { capturing = cap ? null : a; });
        }
        double fy = top + rows * rowH + 8;
        List<Action> missing = KeyMap.unbound(s.keys);
        String warn = keyWarning;
        if (warn == null && !missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("No key: ");
            for (int k = 0; k < missing.size(); k++) sb.append(k > 0 ? ", " : "").append(missing.get(k).label);
            warn = sb.toString();
        }
        boolean def = KeyMap.isDefault(s.keys);
        if (warn != null) ui.textFit("⚠  " + warn, x, fy + 20, w - 190, Ui.F_NORM, Ui.AMBER);
        else ui.text(def ? "These are the default keys." : "Custom keys are in use.", x, fy + 20, Ui.F_NORM, Ui.FAINT);
        ui.button("Reset to defaults", x + w - 170, fy + 2, 170, 30, Ui.NORMAL, !def, false, () -> {
            s.keys = KeyMap.defaults();
            keyWarning = null;
            capturing = null;
            changed();
        });
    }

    /** Height of one row of the Controls page when the page is {@code pageH} high (all actions in two columns). */
    static double controlsRowH(double pageH) {
        int rows = (Action.values().length + 1) / 2;
        return Math.max(15.5, Math.min(26, (pageH - 74) / rows));
    }

    /** Give {@code action} the key {@code code} (the Controls page and the command line both come through here). */
    void bind(Action action, int code) {
        keyWarning = KeyMap.assign(app.settings.keys, action, code);
        capturing = null;
        changed();
    }

    @Override void key(KeyEvent e) {
        int code = e.getKeyCode();
        if (capturing != null) {
            if (code == KeyEvent.VK_ESCAPE && capturing != Action.MENU) { capturing = null; return; }
            if (code == KeyEvent.VK_BACK_SPACE) {
                KeyMap.clear(app.settings.keys, capturing);
                keyWarning = null;
                capturing = null;
                changed();
                return;
            }
            if (KeyMap.bindable(code)) bind(capturing, code);
            return;
        }
        switch (code) {
            case KeyEvent.VK_ESCAPE, KeyEvent.VK_ENTER -> app.pop(this);
            case KeyEvent.VK_TAB, KeyEvent.VK_DOWN -> tab = (tab + 1) % TABS.length;
            case KeyEvent.VK_UP -> tab = (tab + TABS.length - 1) % TABS.length;
            default -> { }
        }
    }
}
