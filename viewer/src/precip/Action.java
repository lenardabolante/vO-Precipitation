package precip;

import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything a key can do in the game view. Keys are never interpreted directly: a key code is
 * looked up in a key → action table and the action is performed by {@code ViewerPanel.perform}.
 * <p>
 * <b>Phase C seam:</b> rebinding is a table edit. {@link #defaults()} returns a fresh, mutable
 * default table (key code → action; several keys may map to one action); give the panel another
 * table with {@code ViewerPanel.setBindings}. {@link #label} is the human-readable name for a
 * controls screen.
 */
enum Action {
    PAUSE("Pause / resume"),
    SPEED_UP("Speed up"),
    SPEED_DOWN("Slow down"),
    PAN_LEFT("Pan left"),
    PAN_RIGHT("Pan right"),
    PAN_UP("Pan up"),
    PAN_DOWN("Pan down"),
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    FIT_WORLD("Fit the whole world"),
    HOME_VIEW("Default view"),
    WIND_LEFT("Wind to the left"),
    WIND_RIGHT("Wind to the right"),
    COLDER("Colder surface"),
    WARMER("Warmer surface"),
    RESEED("Reseed the world"),
    // ---- v2.3: the climate (PROTOCOL.md: the gas, season, hour and tornado commands, spawn with a type)
    HUMIDITY_DOWN("Humidity −"),
    HUMIDITY_UP("Humidity +"),
    CO2_DOWN("CO2 −"),
    CO2_UP("CO2 +"),
    AEROSOL_DOWN("Aerosol −"),
    AEROSOL_UP("Aerosol +"),
    SEASON_PREV("Previous season"),
    SEASON_NEXT("Next season"),
    HOUR_BACK("One hour back"),
    HOUR_FORWARD("One hour forward"),
    SPAWN_TYPE("Cloud type for click"),
    TORNADO("Trigger a tornado"),
    TOGGLE_CLIMATE("Toggle climate panel"),
    // ---- v2.4: storm drains (PROTOCOL.md: the drains command)
    TOGGLE_DRAINS("Toggle drain pipes"),
    DRAIN_SMALLER("Drain pipe smaller"),
    DRAIN_LARGER("Drain pipe larger"),
    TOGGLE_WIND("Toggle wind field"),
    TOGGLE_ARROWS("Toggle arrows"),
    TOGGLE_LABELS("Toggle labels"),
    TOGGLE_LEGEND("Toggle legend"),
    TOGGLE_HELP("Toggle help"),
    TOGGLE_MINIMAP("Toggle minimap"),
    TOGGLE_LEFT_PANEL("Toggle left panel"),
    TOGGLE_RIGHT_PANEL("Toggle right panel"),
    TOGGLE_ALL_PANELS("Toggle all panels"),
    /** Esc: runs the panel's {@code onMenu} callback (the app shell opens the pause menu). */
    MENU("Pause menu"),
    /** F5; handled by the app shell through the panel's action listener. */
    QUICK_SAVE("Quick save"),
    /** F11; handled by the app shell through the panel's action listener. */
    FULLSCREEN("Fullscreen"),
    /** F12; handled by the app shell through the panel's action listener. */
    SCREENSHOT("Screenshot");

    final String label;

    Action(String label) { this.label = label; }

    /** The default key code → action table. */
    static Map<Integer, Action> defaults() {
        Map<Integer, Action> m = new LinkedHashMap<>();
        m.put(KeyEvent.VK_SPACE, PAUSE);
        m.put(KeyEvent.VK_EQUALS, SPEED_UP);
        m.put(KeyEvent.VK_PLUS, SPEED_UP);
        m.put(KeyEvent.VK_ADD, SPEED_UP);
        m.put(KeyEvent.VK_MINUS, SPEED_DOWN);
        m.put(KeyEvent.VK_SUBTRACT, SPEED_DOWN);
        m.put(KeyEvent.VK_LEFT, PAN_LEFT);
        m.put(KeyEvent.VK_RIGHT, PAN_RIGHT);
        m.put(KeyEvent.VK_UP, PAN_UP);
        m.put(KeyEvent.VK_DOWN, PAN_DOWN);
        m.put(KeyEvent.VK_PAGE_UP, ZOOM_IN);
        m.put(KeyEvent.VK_PAGE_DOWN, ZOOM_OUT);
        m.put(KeyEvent.VK_HOME, FIT_WORLD);
        m.put(KeyEvent.VK_END, HOME_VIEW);
        m.put(KeyEvent.VK_Q, WIND_LEFT);
        m.put(KeyEvent.VK_E, WIND_RIGHT);
        m.put(KeyEvent.VK_Z, COLDER);
        m.put(KeyEvent.VK_X, WARMER);
        m.put(KeyEvent.VK_R, RESEED);
        // v2.3: the number row in pairs (down / up), then three letters
        m.put(KeyEvent.VK_1, HUMIDITY_DOWN);
        m.put(KeyEvent.VK_2, HUMIDITY_UP);
        m.put(KeyEvent.VK_3, CO2_DOWN);
        m.put(KeyEvent.VK_4, CO2_UP);
        m.put(KeyEvent.VK_5, AEROSOL_DOWN);
        m.put(KeyEvent.VK_6, AEROSOL_UP);
        m.put(KeyEvent.VK_7, SEASON_PREV);
        m.put(KeyEvent.VK_8, SEASON_NEXT);
        m.put(KeyEvent.VK_9, HOUR_BACK);
        m.put(KeyEvent.VK_0, HOUR_FORWARD);
        m.put(KeyEvent.VK_C, SPAWN_TYPE);
        m.put(KeyEvent.VK_T, TORNADO);
        m.put(KeyEvent.VK_K, TOGGLE_CLIMATE);
        m.put(KeyEvent.VK_D, TOGGLE_DRAINS);
        m.put(KeyEvent.VK_COMMA, DRAIN_SMALLER);
        m.put(KeyEvent.VK_PERIOD, DRAIN_LARGER);
        m.put(KeyEvent.VK_W, TOGGLE_WIND);
        m.put(KeyEvent.VK_A, TOGGLE_ARROWS);
        m.put(KeyEvent.VK_L, TOGGLE_LABELS);
        m.put(KeyEvent.VK_G, TOGGLE_LEGEND);
        m.put(KeyEvent.VK_H, TOGGLE_HELP);
        m.put(KeyEvent.VK_F1, TOGGLE_HELP);
        m.put(KeyEvent.VK_M, TOGGLE_MINIMAP);
        m.put(KeyEvent.VK_OPEN_BRACKET, TOGGLE_LEFT_PANEL);
        m.put(KeyEvent.VK_CLOSE_BRACKET, TOGGLE_RIGHT_PANEL);
        m.put(KeyEvent.VK_BACK_SLASH, TOGGLE_ALL_PANELS);
        m.put(KeyEvent.VK_ESCAPE, MENU);
        m.put(KeyEvent.VK_F5, QUICK_SAVE);
        m.put(KeyEvent.VK_F11, FULLSCREEN);
        m.put(KeyEvent.VK_F12, SCREENSHOT);
        return m;
    }
}
