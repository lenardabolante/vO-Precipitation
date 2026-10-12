package precip;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Key bindings as the settings keep them: action → key codes (several keys may trigger one action,
 * one key triggers at most one action). {@link #table} turns that into the key code → action table
 * the game view dispatches through; {@link #helpRows} is the help overlay text for the bindings in
 * force.
 */
final class KeyMap {
    private KeyMap() {}

    /** The shipped bindings (from {@link Action#defaults()}), every action present. */
    static EnumMap<Action, List<Integer>> defaults() {
        EnumMap<Action, List<Integer>> m = new EnumMap<>(Action.class);
        for (Action a : Action.values()) m.put(a, new ArrayList<>());
        for (Map.Entry<Integer, Action> e : Action.defaults().entrySet()) m.get(e.getValue()).add(e.getKey());
        return m;
    }

    static EnumMap<Action, List<Integer>> copy(Map<Action, List<Integer>> src) {
        EnumMap<Action, List<Integer>> m = new EnumMap<>(Action.class);
        for (Action a : Action.values()) m.put(a, new ArrayList<>(src.getOrDefault(a, List.of())));
        return m;
    }

    /** Key code → action, for {@code ViewerPanel.setBindings}. */
    static Map<Integer, Action> table(Map<Action, List<Integer>> keys) {
        Map<Integer, Action> t = new LinkedHashMap<>();
        for (Action a : Action.values()) for (int code : keys.getOrDefault(a, List.of())) t.putIfAbsent(code, a);
        return t;
    }

    /** The action a key is bound to, or null. */
    static Action owner(Map<Action, List<Integer>> keys, int code) {
        for (Action a : Action.values()) if (keys.getOrDefault(a, List.of()).contains(code)) return a;
        return null;
    }

    /**
     * Bind {@code code} to {@code action}, replacing the action's previous keys. A key that belonged
     * to another action moves over. Returns a warning to show when that happened (the other action
     * may now be without a key), else null.
     */
    static String assign(Map<Action, List<Integer>> keys, Action action, int code) {
        String warn = null;
        Action old = owner(keys, code);
        if (old != null && old != action) {
            keys.get(old).remove((Integer) code);
            warn = keyName(code) + " was bound to \"" + old.label + "\""
                    + (keys.get(old).isEmpty() ? " — that action now has no key." : " — it keeps " + keysLabel(keys.get(old)) + ".");
        }
        List<Integer> l = keys.get(action);
        l.clear();
        l.add(code);
        return warn;
    }

    static void clear(Map<Action, List<Integer>> keys, Action action) { keys.get(action).clear(); }

    /** Actions that have no key at all. */
    static List<Action> unbound(Map<Action, List<Integer>> keys) {
        List<Action> out = new ArrayList<>();
        for (Action a : Action.values()) if (keys.getOrDefault(a, List.of()).isEmpty()) out.add(a);
        return out;
    }

    static boolean isDefault(Map<Action, List<Integer>> keys) { return keys.equals(defaults()); }

    /** A key that can be bound (modifier and lock keys cannot). */
    static boolean bindable(int code) {
        return switch (code) {
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META, KeyEvent.VK_WINDOWS,
                 KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK, KeyEvent.VK_CONTEXT_MENU, KeyEvent.VK_UNDEFINED,
                 KeyEvent.VK_PRINTSCREEN -> false;
            default -> true;
        };
    }

    /** Short display name of a key. */
    static String keyName(int code) {
        return switch (code) {
            case KeyEvent.VK_SPACE -> "Space";
            case KeyEvent.VK_ESCAPE -> "Esc";
            case KeyEvent.VK_ENTER -> "Enter";
            case KeyEvent.VK_TAB -> "Tab";
            case KeyEvent.VK_BACK_SPACE -> "Backspace";
            case KeyEvent.VK_DELETE -> "Del";
            case KeyEvent.VK_INSERT -> "Ins";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            case KeyEvent.VK_PAGE_UP -> "PgUp";
            case KeyEvent.VK_PAGE_DOWN -> "PgDn";
            case KeyEvent.VK_HOME -> "Home";
            case KeyEvent.VK_END -> "End";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_PLUS -> "+";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_ADD -> "Num +";
            case KeyEvent.VK_SUBTRACT -> "Num -";
            case KeyEvent.VK_MULTIPLY -> "Num *";
            case KeyEvent.VK_DIVIDE -> "Num /";
            case KeyEvent.VK_DECIMAL -> "Num .";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SEMICOLON -> ";";
            case KeyEvent.VK_QUOTE -> "'";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            default -> {
                if (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD9) yield "Num " + (code - KeyEvent.VK_NUMPAD0);
                if ((code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) || (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9)) yield String.valueOf((char) code);
                if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) yield "F" + (code - KeyEvent.VK_F1 + 1);
                String t = KeyEvent.getKeyText(code);
                yield t == null || t.isEmpty() ? "Key " + code : t;
            }
        };
    }

    /** Parse what {@link #keyName} prints (or a {@code VK_} constant name, or a number). -1 = unknown. */
    static int parseKey(String name) {
        String n = name.trim();
        for (int code = 1; code < 0x100; code++) if (bindable(code) && keyName(code).equalsIgnoreCase(n)) return code;
        try {
            String f = n.toUpperCase().startsWith("VK_") ? n.toUpperCase() : "VK_" + n.toUpperCase();
            return KeyEvent.class.getField(f).getInt(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        try { return Integer.parseInt(n); } catch (NumberFormatException e) { return -1; }
    }

    /** All keys of one action, e.g. {@code "=  +  Num +"}; a long dash when it has none. */
    static String keysLabel(List<Integer> codes) {
        if (codes == null || codes.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (int c : codes) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(keyName(c));
        }
        return sb.toString();
    }

    /** The first key of an action, for compact labels (edge tabs, hints); "" when unbound. */
    static String first(Map<Action, List<Integer>> keys, Action a) {
        List<Integer> l = keys.get(a);
        return l == null || l.isEmpty() ? "" : keyName(l.get(0));
    }

    private static String k(Map<Action, List<Integer>> keys, Action a) {
        String s = first(keys, a);
        return s.isEmpty() ? "—" : s;
    }

    private static String join(Map<Action, List<Integer>> keys, String sep, Action... as) {
        StringBuilder sb = new StringBuilder();
        for (Action a : as) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(k(keys, a));
        }
        return sb.toString();
    }

    /** The help overlay rows for these bindings (see {@code Renderer.helpRows}). */
    static String[][] helpRows(Map<Action, List<Integer>> keys) {
        return new String[][]{
                {k(keys, Action.PAUSE), "pause / resume"},
                {join(keys, "  /  ", Action.SPEED_UP, Action.SPEED_DOWN), "speed up / slow down (0.25× – 8×)"},
                {"Mouse wheel", "zoom at the cursor (over the panel: scroll the timeline)"},
                {"Right / middle drag", "pan the view"},
                {join(keys, " ", Action.PAN_LEFT, Action.PAN_RIGHT, Action.PAN_UP, Action.PAN_DOWN), "pan the view"},
                {join(keys, " / ", Action.ZOOM_IN, Action.ZOOM_OUT), "zoom in / out"},
                {join(keys, "  ·  ", Action.FIT_WORLD, Action.HOME_VIEW), "fit the whole world  ·  default view"},
                {k(keys, Action.TOGGLE_MINIMAP), "toggle the minimap (click or drag it to move)"},
                {join(keys, "  ", Action.TOGGLE_LEFT_PANEL, Action.TOGGLE_RIGHT_PANEL, Action.TOGGLE_ALL_PANELS), "left panel · right panel · all panels"},
                {"Click sky", "seed a new unique cloud there"},
                {"Click cloud / row", "open its life card"},
                {join(keys, " / ", Action.WIND_LEFT, Action.WIND_RIGHT), "prevailing wind left / right"},
                {join(keys, " / ", Action.COLDER, Action.WARMER), "colder / warmer surface (moves the freezing level)"},
                {join(keys, " / ", Action.HUMIDITY_DOWN, Action.HUMIDITY_UP), "humidity − / + : water vapour for clouds (more, wetter clouds)"},
                {join(keys, " / ", Action.CO2_DOWN, Action.CO2_UP), "CO2 − / + : greenhouse gas (3 °C warmer per doubling)"},
                {join(keys, " / ", Action.AEROSOL_DOWN, Action.AEROSOL_UP), "aerosol − / + : more nuclei, more clouds that rain later"},
                {join(keys, " / ", Action.SEASON_PREV, Action.SEASON_NEXT), "previous / next season (three months)"},
                {join(keys, " / ", Action.HOUR_BACK, Action.HOUR_FORWARD), "one hour back / forward"},
                {join(keys, "  ·  ", Action.SPAWN_TYPE, Action.TORNADO), "cloud type a click seeds  ·  ask a supercell for a tornado"},
                {k(keys, Action.TOGGLE_CLIMATE), "toggle the climate panel"},
                {k(keys, Action.TOGGLE_DRAINS), "show the storm drain pipes under every town"},
                {join(keys, " / ", Action.DRAIN_SMALLER, Action.DRAIN_LARGER), "smaller / larger drain pipe for the town on the drains card"},
                {k(keys, Action.RESEED), "reseed the world"},
                {join(keys, "  ", Action.TOGGLE_WIND, Action.TOGGLE_ARROWS, Action.TOGGLE_LABELS, Action.TOGGLE_LEGEND), "wind field · arrows · labels · legend"},
                {k(keys, Action.TOGGLE_HELP), "toggle this help"},
                {join(keys, "  ", Action.QUICK_SAVE, Action.SCREENSHOT, Action.FULLSCREEN), "quick save · screenshot · fullscreen"},
                {k(keys, Action.MENU), "pause menu: save, settings, leave"},
        };
    }
}
