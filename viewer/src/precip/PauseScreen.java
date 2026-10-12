package precip;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** The pause menu (Esc in a game). The simulation is paused while it is open. */
final class PauseScreen extends Screen {
    private record Item(String label, int style, boolean enabled, Runnable action) { }

    private final AppShell app;
    private final GameSession gs;
    private int focus;

    PauseScreen(AppShell app, GameSession gs) {
        this.app = app;
        this.gs = gs;
    }

    private List<Item> items() {
        List<Item> l = new ArrayList<>();
        boolean slot = gs.slot != null, can = gs.canSave();
        l.add(new Item("Resume", Ui.PRIMARY, true, app::resumeFromPause));
        if (slot) {
            l.add(new Item("Save", Ui.NORMAL, can, () -> app.saveNow("Saved")));
            l.add(new Item("Save & Leave", Ui.NORMAL, can, app::saveAndLeave));
        }
        l.add(new Item("Settings", Ui.NORMAL, true, () -> app.openSettings(0)));
        if (slot) l.add(new Item("Leave without saving", Ui.NORMAL, true, app::confirmLeaveWithoutSaving));
        else l.add(new Item("Back to the main menu", Ui.NORMAL, true, app::leaveNow));
        l.add(new Item(slot && can ? "Save & quit to desktop" : "Quit to desktop", Ui.NORMAL, true, app::requestQuit));
        return l;
    }

    @Override void paint(Ui ui, int w, int h) {
        List<Item> items = items();
        focus = Math.max(0, Math.min(items.size() - 1, focus));
        double bh = 42, gap = 8;
        double[] d = ui.dialog(w, h, 380, 108 + items.size() * (bh + gap) + 30, "Paused");
        String sub = gs.slot != null ? gs.slot.name : gs.label;
        ui.textFit(sub + "  ·  " + SaveStore.duration(gs.simTime()) + " simulated", d[0] + 24, d[1] + 58, d[2] - 48, Ui.F_NORM, Ui.DIM);
        double y = d[1] + 78;
        for (int k = 0; k < items.size(); k++) {
            Item it = items.get(k);
            ui.button(it.label, d[0] + 24, y, d[2] - 48, bh, it.style, it.enabled, k == focus, it.action);
            y += bh + gap;
        }
        String foot = gs.slot == null ? "Diagnostic session — nothing is saved" : gs.saveStatus();
        ui.textFit(foot, d[0] + 24, d[1] + d[3] - 20, d[2] - 48, Ui.F_SMALL, gs.saveFailed() ? Ui.RED : Ui.FAINT);
    }

    @Override void key(KeyEvent e) {
        List<Item> items = items();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> app.resumeFromPause();
            case KeyEvent.VK_UP -> { do { focus = (focus + items.size() - 1) % items.size(); } while (!items.get(focus).enabled); }
            case KeyEvent.VK_DOWN, KeyEvent.VK_TAB -> { do { focus = (focus + 1) % items.size(); } while (!items.get(focus).enabled); }
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { if (items.get(focus).enabled) items.get(focus).action.run(); }
            default -> {
                // the quick-save key works in the pause menu too
                if (KeyMap.owner(app.settings.keys, e.getKeyCode()) == Action.QUICK_SAVE && gs.canSave()) app.saveNow("Saved");
            }
        }
    }
}
