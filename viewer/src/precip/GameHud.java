package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * The small things drawn over a running game: the edge tabs that slide the side panels in and out
 * with the mouse (each shows its key), the menu button, the save indicator and the FPS counter.
 */
final class GameHud {
    private final AppShell app;

    GameHud(AppShell app) { this.app = app; }

    void paint(Ui ui, int w, int h, Renderer r, GameSession gs) {
        if (r == null) return;
        Settings s = app.settings;
        ViewerPanel p = app.panel;
        double px = Math.min(w, r.panelX);       // where the docked right panel starts
        boolean clean = !r.showLeftPanel && !r.showRightPanel && !r.showMinimap;
        boolean busy = r.showHelp;
        if (busy) return;                        // the help overlay owns the screen

        // ---- right panel tab (on the panel's left edge)
        double ty = Math.round(h / 2.0 - 36);
        tab(ui, px - 20, ty, 20, 72, clean, r.showRightPanel ? 'R' : 'L', KeyMap.first(s.keys, Action.TOGGLE_RIGHT_PANEL), true,
                () -> act(p, Action.TOGGLE_RIGHT_PANEL));

        // ---- left panel tab (left edge; below the panel while it is open)
        double ly = r.showLeftPanel && r.leftPanelBottom > 0 ? Math.min(h - 84, r.leftPanelBottom + 10) : 12;
        tab(ui, 0, ly, 20, 72, clean, r.showLeftPanel ? 'L' : 'R', KeyMap.first(s.keys, Action.TOGGLE_LEFT_PANEL), true,
                () -> act(p, Action.TOGGLE_LEFT_PANEL));

        // ---- minimap tab (bottom centre; rides on top of the minimap while it is open)
        Rectangle2D mm = r.minimapRect;
        double mx = Math.round(px / 2 - 36), my = r.showMinimap && mm != null ? mm.getY() - 5 - 20 : h - 20;
        if (px >= 420) tab(ui, mx, Math.min(h - 20, my), 72, 20, clean, r.showMinimap ? 'D' : 'U', KeyMap.first(s.keys, Action.TOGGLE_MINIMAP), false,
                () -> act(p, Action.TOGGLE_MINIMAP));

        // ---- menu button (top right of the world view)
        double bx = px - 20 - 12 - 34, by = 10;
        boolean hov = ui.hover(bx, by, 34, 28);
        int a = hov ? 235 : clean ? 70 : 185;
        ui.fill(bx, by, 34, 28, 9, new Color(10, 14, 24, a));
        ui.stroke(bx, by, 34, 28, 9, new Color(255, 255, 255, hov ? 120 : 40), 1f);
        ui.g.setColor(new Color(236, 242, 250, hov ? 255 : clean ? 110 : 220));
        ui.g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 0; k < 3; k++) ui.g.draw(new Line2D.Double(bx + 10, by + 9 + k * 5, bx + 24, by + 9 + k * 5));
        ui.hit(bx, by, 34, 28, app::openPause);
        if (hov) {
            String key = KeyMap.first(s.keys, Action.MENU);
            String tip = "Pause menu" + (key.isEmpty() ? "" : "  (" + key + ")");
            int tw = ui.width(tip, Ui.F_SMALL) + 14;
            ui.fill(bx + 34 - tw, by + 32, tw, 20, 8, new Color(10, 14, 24, 230));
            ui.text(tip, bx + 34 - tw + 7, by + 46, Ui.F_SMALL, Ui.TEXT);
        }

        // ---- save indicator + fps, to the left of the menu button
        double right = bx - 10;
        if (gs != null) {
            String ind = gs.indicatorText();
            if (ind != null && !(clean && !gs.indicatorImportant())) {
                Color c = gs.saveFailed() ? Ui.RED : gs.saving() ? Ui.ACCENT : Ui.GREEN;
                double fade = gs.indicatorImportant() ? 1 : Model.clamp(1.25 - gs.indicatorAge() / 8.0, 0.45, 1);
                ui.g.setFont(Ui.F_SMALL);
                FontMetrics fm = ui.g.getFontMetrics();
                String txt = ui.fit(ind, Ui.F_SMALL, Math.max(60, right - 300));
                int tw = fm.stringWidth(txt) + 30;
                ui.fill(right - tw, by + 3, tw, 22, 11, new Color(10, 14, 24, (int) (170 * fade)));
                ui.g.setColor(Renderer.alpha(c, (int) (255 * fade)));
                ui.g.fill(new java.awt.geom.Ellipse2D.Double(right - tw + 9, by + 10, 7, 7));
                ui.text(txt, right - tw + 22, by + 18, Ui.F_SMALL, Renderer.alpha(Ui.TEXT, (int) (255 * fade)));
                right -= tw + 8;
            }
        }
        if (s.showFps && p != null) {
            String txt = String.format("%.0f fps", p.fps());
            int tw = ui.width(txt, Ui.F_SMALL) + 16;
            ui.fill(right - tw, by + 3, tw, 22, 11, new Color(10, 14, 24, 170));
            ui.text(txt, right - tw + 8, by + 18, Ui.F_SMALL, Ui.TEXT);
        }
        climate(ui, r, s, p);
        drains(ui, r, s, p);
    }

    /**
     * v2.4: the buttons on the drains card (the renderer paints the card and its four mode chips): pipe size down / up for
     * the settlement on the card, each the same action as its key, and a click on a chip gives every settlement that
     * mode's size again. The tooltips name the keys.
     */
    private void drains(Ui ui, Renderer r, Settings s, ViewerPanel p) {
        Rectangle2D rc = r.drainRect;
        if (rc == null) return;
        double x = rc.getX(), y = rc.getY(), w = rc.getWidth();
        pair(ui, x + w - 10 - 38, y + DrainCard.PIPE_ROW, '-', '+', Action.DRAIN_SMALLER, Action.DRAIN_LARGER, s, p);
        double cw = DrainCard.chipWidth();
        String[] tips = {"Small pipes everywhere: they flood in a downpour", "Every town its usual size, by its kind", "Large pipes everywhere: they take a supercell",
                "A random size for every town"};
        for (int k = 0; k < DrainCard.MODES.length; k++) {
            final String mode = DrainCard.MODES[k];
            double cx = x + 10 + k * (cw + 4), cy = y + DrainCard.CHIPS_TOP;
            boolean hov = ui.hover(cx, cy, cw, DrainCard.CHIP_H);
            if (hov) {
                ui.stroke(cx, cy, cw, DrainCard.CHIP_H, 8, new Color(255, 255, 255, 150), 1f);
                String tip = tips[k];
                int tw = ui.width(tip, Ui.F_SMALL) + 14;
                double tx = Math.min(cx + cw / 2 - tw / 2.0, x + w - tw);
                ui.fill(tx, cy - 23, tw, 19, 8, new Color(10, 14, 24, 240));
                ui.text(tip, tx + 7, cy - 9, Ui.F_SMALL, Ui.TEXT);
            }
            ui.hit(cx, cy, cw, DrainCard.CHIP_H, () -> { if (p != null) p.drainMode(mode); });
        }
    }

    /**
     * v2.3: the small buttons on the Climate panel (the renderer paints the panel and says where its rows are):
     * calendar and clock back / forward, each gas down / up, the click-to-spawn type and the tornado request.
     * Every one of them runs the same action as its key, and its tooltip names that key.
     */
    private void climate(Ui ui, Renderer r, Settings s, ViewerPanel p) {
        double[] rows = r.climateRows;
        if (rows == null || r.climateRect == null) return;
        double x = rows[0], w = rows[1], bx = x + w - 10 - 38;
        if (!Double.isNaN(rows[2])) pair(ui, bx, rows[2], '<', '>', Action.SEASON_PREV, Action.SEASON_NEXT, s, p);
        pair(ui, bx, rows[3], '<', '>', Action.HOUR_BACK, Action.HOUR_FORWARD, s, p);
        if (!Double.isNaN(rows[4])) pair(ui, bx, rows[4], '-', '+', Action.HUMIDITY_DOWN, Action.HUMIDITY_UP, s, p);
        if (!Double.isNaN(rows[5])) pair(ui, bx, rows[5], '-', '+', Action.CO2_DOWN, Action.CO2_UP, s, p);
        if (!Double.isNaN(rows[6])) pair(ui, bx, rows[6], '-', '+', Action.AEROSOL_DOWN, Action.AEROSOL_UP, s, p);
        if (!Double.isNaN(rows[7])) {
            double fy = rows[7];
            // the type chip (click to step through the nine types) and the tornado request
            small(ui, x + w - 10 - 62, fy - 12, 62, 16, "Tornado", Action.TORNADO, s, p, new Color(120, 40, 34, 235));
            small(ui, x + w - 10 - 62 - 6 - 44, fy - 12, 44, 16, "Type", Action.SPAWN_TYPE, s, p, null);
        }
    }

    private void pair(Ui ui, double x, double baseline, char a, char b, Action down, Action up, Settings s, ViewerPanel p) {
        small(ui, x, baseline - 11, 18, 14, String.valueOf(a == '<' ? '◀' : '−'), down, s, p, null);
        small(ui, x + 20, baseline - 11, 18, 14, String.valueOf(b == '>' ? '▶' : '+'), up, s, p, null);
    }

    private void small(Ui ui, double x, double y, double w, double h, String label, Action act, Settings s, ViewerPanel p, Color tint) {
        boolean hov = ui.hover(x, y, w, h);
        ui.fill(x, y, w, h, 6, hov ? new Color(74, 98, 150, 250) : tint != null ? tint : new Color(34, 44, 68, 235));
        ui.stroke(x, y, w, h, 6, new Color(255, 255, 255, hov ? 120 : 44), 1f);
        java.awt.Font f = label.length() > 1 ? Ui.F_SMALL : Ui.F_SMALL.deriveFont(label.equals("−") || label.equals("+") ? java.awt.Font.BOLD : java.awt.Font.PLAIN, label.equals("−") || label.equals("+") ? 12f : 8.5f);
        ui.g.setFont(f);
        FontMetrics fm = ui.g.getFontMetrics();
        ui.text(label, x + (w - fm.stringWidth(label)) / 2.0, y + (h + fm.getAscent() - fm.getDescent()) / 2.0, f, Ui.TEXT);
        ui.hit(x, y, w, h, () -> act(p, act));
        if (hov) {
            String key = KeyMap.first(s.keys, act);
            String tip = act.label + (key.isEmpty() ? "" : "  (" + key + ")");
            int tw = ui.width(tip, Ui.F_SMALL) + 14;
            double tx = Math.min(x + w / 2 - tw / 2.0, x + w - tw + 12);
            ui.fill(tx, y - 23, tw, 19, 8, new Color(10, 14, 24, 240));
            ui.text(tip, tx + 7, y - 9, Ui.F_SMALL, Ui.TEXT);
        }
    }

    private static void act(ViewerPanel p, Action a) { if (p != null) p.perform(a); }

    /**
     * One edge tab: a small dark pill with an arrow ({@code dir}: L, R, U, D = where the panel will
     * go or come from) and the key that does the same.
     */
    private void tab(Ui ui, double x, double y, double w, double h, boolean faint, char dir, String key, boolean vertical, Runnable onClick) {
        boolean hov = ui.hover(x, y, w, h);
        int a = hov ? 240 : faint ? 60 : 180;
        ui.fill(x, y, w, h, 8, new Color(10, 14, 24, a));
        ui.stroke(x, y, w, h, 8, new Color(255, 255, 255, hov ? 130 : faint ? 22 : 44), 1f);
        Color ink = new Color(236, 242, 250, hov ? 255 : faint ? 110 : 215);
        // arrow
        double ax = vertical ? x + w / 2 : x + 16, ay = vertical ? y + 18 : y + h / 2, q = 4.2;
        Path2D.Double tri = new Path2D.Double();
        switch (dir) {
            case 'L' -> { tri.moveTo(ax - q, ay); tri.lineTo(ax + q * 0.8, ay - q * 1.3); tri.lineTo(ax + q * 0.8, ay + q * 1.3); }
            case 'R' -> { tri.moveTo(ax + q, ay); tri.lineTo(ax - q * 0.8, ay - q * 1.3); tri.lineTo(ax - q * 0.8, ay + q * 1.3); }
            case 'U' -> { tri.moveTo(ax, ay - q); tri.lineTo(ax - q * 1.3, ay + q * 0.8); tri.lineTo(ax + q * 1.3, ay + q * 0.8); }
            default -> { tri.moveTo(ax, ay + q); tri.lineTo(ax - q * 1.3, ay - q * 0.8); tri.lineTo(ax + q * 1.3, ay - q * 0.8); }
        }
        tri.closePath();
        ui.g.setColor(ink);
        ui.g.fill(tri);
        // key
        if (!key.isEmpty()) {
            String k = ui.fit(key, Ui.F_BOLD, vertical ? w - 4 : w - 30);
            if (vertical) ui.textCenter(k, x + w / 2, y + h - 22, Ui.F_BOLD, Renderer.alpha(Ui.ACCENT, ink.getAlpha()));
            else ui.textCenter(k, x + w / 2 + 10, y + h / 2 + 4.5, Ui.F_BOLD, Renderer.alpha(Ui.ACCENT, ink.getAlpha()));
        }
        ui.hit(x, y, w, h, onClick);
    }
}
