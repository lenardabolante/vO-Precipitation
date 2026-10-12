package precip;

import java.awt.event.KeyEvent;
import java.util.List;

/**
 * One custom-drawn screen or dialog of the app shell. Screens live on a stack ({@link AppShell});
 * the top one gets the input, the ones below are drawn dimmed.
 */
abstract class Screen {
    /** Draw the screen (and register its widgets) for a window of {@code w} x {@code h} logical pixels. */
    abstract void paint(Ui ui, int w, int h);

    /** A key press; every key is swallowed while a screen is open, this only says what it does. */
    void key(KeyEvent e) { }

    void typed(char c) { }

    void wheel(int x, int y, double rotation) { }

    /**
     * A mouse press, drag or release ({@code MouseEvent.MOUSE_*}) that did not start on one of the screen's widgets
     * (the world map pans and picks with it). {@code button} is the button of a press / release, 0 for a drag.
     */
    void mouse(int type, int x, int y, int button) { }

    /** Darken what lies underneath. */
    boolean dims() { return true; }

    /** True while the screen wants every key for itself (rebinding): global shortcuts are then off. */
    boolean capturesKeys() { return false; }

    // =====================================================================================
    // small general-purpose dialogs

    /** A question with a confirm and a cancel button. */
    static final class Confirm extends Screen {
        final String title, message, yes, no;
        final boolean danger;
        final Runnable onYes, onNo;
        private final AppShell app;
        private int focus = 1;   // 0 = cancel, 1 = confirm

        Confirm(AppShell app, String title, String message, String yes, String no, boolean danger, Runnable onYes, Runnable onNo) {
            this.app = app;
            this.title = title; this.message = message; this.yes = yes; this.no = no;
            this.danger = danger; this.onYes = onYes; this.onNo = onNo;
            if (danger) focus = 0;   // Enter on a destructive question must not destroy by default
        }

        private void answer(boolean ok) {
            app.pop(this);
            Runnable r = ok ? onYes : onNo;
            if (r != null) r.run();
        }

        @Override void paint(Ui ui, int w, int h) {
            ui.g.setFont(Ui.F_MED);
            double dw = Math.min(480, w - 24);
            List<String> lines = ui.wrap(message, Ui.F_MED, dw - 48);
            double[] d = ui.dialog(w, h, dw, 60 + lines.size() * 19 + 76, title);
            double y = d[1] + 64;
            for (String l : lines) { ui.text(l, d[0] + 24, y, Ui.F_MED, Ui.TEXT); y += 19; }
            double by = d[1] + d[3] - 56, bw = 176;
            ui.button(no, d[0] + d[2] - 24 - bw * 2 - 10, by, bw, 36, Ui.NORMAL, true, focus == 0, () -> answer(false));
            ui.button(yes, d[0] + d[2] - 24 - bw, by, bw, 36, danger ? Ui.DANGER : Ui.PRIMARY, true, focus == 1, () -> answer(true));
        }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ESCAPE -> answer(false);
                case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> answer(focus == 1);
                case KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_TAB, KeyEvent.VK_UP, KeyEvent.VK_DOWN -> focus = 1 - focus;
                default -> { }
            }
        }
    }

    /** Ask for one line of text (rename). */
    static final class Prompt extends Screen {
        final String title, label, ok;
        final Ui.TextField field;
        final java.util.function.Consumer<String> onOk;
        private final AppShell app;
        String error;

        Prompt(AppShell app, String title, String label, String initial, String ok, java.util.function.Consumer<String> onOk) {
            this.app = app;
            this.title = title; this.label = label; this.ok = ok; this.onOk = onOk;
            field = new Ui.TextField(initial, 48, false);
        }

        private void accept() {
            String v = SaveStore.cleanName(field.value());
            if (v.isEmpty()) { error = "Please type a name."; return; }
            app.pop(this);
            onOk.accept(v);
        }

        @Override void paint(Ui ui, int w, int h) {
            double[] d = ui.dialog(w, h, 460, 206, title);
            ui.text(label, d[0] + 24, d[1] + 68, Ui.F_NORM, Ui.DIM);
            ui.field(field, "", d[0] + 24, d[1] + 78, d[2] - 48, 36, true, null);
            if (error != null) ui.text(error, d[0] + 24, d[1] + 134, Ui.F_NORM, Ui.RED);
            double by = d[1] + d[3] - 56, bw = 130;
            ui.button("Cancel", d[0] + d[2] - 24 - bw * 2 - 10, by, bw, 36, Ui.NORMAL, true, false, () -> app.pop(this));
            ui.button(ok, d[0] + d[2] - 24 - bw, by, bw, 36, Ui.PRIMARY, true, false, this::accept);
        }

        @Override void key(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) app.pop(this);
            else if (e.getKeyCode() == KeyEvent.VK_ENTER) accept();
            else field.key(e);
        }

        @Override void typed(char c) {
            error = null;
            field.typed(c);
        }
    }

    /** "Saving..." — shown while something finishes; takes no input. */
    static final class Busy extends Screen {
        final String text;
        private final long t0 = System.nanoTime();

        Busy(String text) { this.text = text; }

        @Override void paint(Ui ui, int w, int h) {
            double[] d = ui.dialog(w, h, 320, 96, null);
            int dots = (int) ((System.nanoTime() - t0) / 350_000_000L % 4);
            ui.text(text + ".".repeat(dots), d[0] + 28, d[1] + 55, Ui.F_HEAD, Ui.TEXT);
        }
    }

    /** A message with one or more buttons (errors). Escape and Enter press the last button. */
    static final class Message extends Screen {
        final String title, message;
        final String[] buttons;
        final Runnable[] actions;
        final boolean error;
        private final AppShell app;

        Message(AppShell app, String title, String message, boolean error, String[] buttons, Runnable[] actions) {
            this.app = app;
            this.title = title; this.message = message; this.error = error; this.buttons = buttons; this.actions = actions;
        }

        private void press(int k) {
            app.pop(this);
            if (actions[k] != null) actions[k].run();
        }

        @Override void paint(Ui ui, int w, int h) {
            double dw = Math.min(640, w - 24);
            List<String> lines = ui.wrap(message, Ui.F_NORM, dw - 48);
            int max = Math.max(4, (h - 24 - 140) / 17);
            if (lines.size() > max) lines = lines.subList(lines.size() - max, lines.size());
            double[] d = ui.dialog(w, h, dw, 64 + lines.size() * 17 + 76, null);
            if (error) ui.stroke(d[0], d[1], d[2], d[3], 16, new java.awt.Color(255, 110, 110, 200), 1.5f);
            ui.text(title, d[0] + 24, d[1] + 36, Ui.F_BIG, error ? Ui.RED : java.awt.Color.WHITE);
            double y = d[1] + 64;
            for (String l : lines) { ui.text(l, d[0] + 24, y, Ui.F_NORM, Ui.TEXT); y += 17; }
            double by = d[1] + d[3] - 56, bw = Math.min(190, (d[2] - 48 - 10 * (buttons.length - 1)) / buttons.length);
            double bx = d[0] + d[2] - 24 - buttons.length * bw - (buttons.length - 1) * 10;
            for (int k = 0; k < buttons.length; k++) {
                final int idx = k;
                boolean last = k == buttons.length - 1;
                ui.button(buttons[k], bx + k * (bw + 10), by, bw, 36, last ? Ui.PRIMARY : Ui.NORMAL, true, last, () -> press(idx));
            }
        }

        @Override void key(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_ESCAPE) press(buttons.length - 1);
        }
    }
}
