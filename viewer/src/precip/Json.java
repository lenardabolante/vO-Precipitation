package precip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON parser and writer helpers.
 * Objects become LinkedHashMap, arrays ArrayList, numbers Double, plus String/Boolean/null.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing characters");
        return v;
    }

    private RuntimeException err(String msg) {
        return new IllegalArgumentException("JSON " + msg + " at " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                // tolerate NaN / Infinity that Python's json module may emit
                if (s.startsWith("NaN", i)) { i += 3; return Double.NaN; }
                if (s.startsWith("Infinity", i)) { i += 8; return Double.POSITIVE_INFINITY; }
                throw err("unexpected char '" + c + "'");
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw err("expected " + word);
        i += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected key");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
            i++; ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("unterminated object");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (i >= s.length()) throw err("unterminated array");
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder sb = null;
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                String out = sb == null ? s.substring(start, i) : sb.append(s, start, i).toString();
                i++;
                return out;
            }
            if (c == '\\') {
                if (sb == null) sb = new StringBuilder();
                sb.append(s, start, i);
                i++;
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw err("bad escape");
                }
                start = i;
            } else {
                i++;
            }
        }
        throw err("unterminated string");
    }

    private Double number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        if (s.startsWith("Infinity", i)) { i += 8; return Double.NEGATIVE_INFINITY; }
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        return Double.parseDouble(s.substring(start, i));
    }

    // ---------------------------------------------------------------- accessors

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static Map<String, Object> obj(Map<String, Object> m, String k) { return m == null ? null : obj(m.get(k)); }

    static List<Object> arr(Map<String, Object> m, String k) { return m == null ? null : arr(m.get(k)); }

    static double num(Object o, double def) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) ? def : d;
        }
        if (o instanceof Boolean b) return b ? 1 : 0;
        return def;
    }

    static double num(Map<String, Object> m, String k, double def) { return m == null ? def : num(m.get(k), def); }

    static String str(Map<String, Object> m, String k, String def) {
        if (m == null) return def;
        Object o = m.get(k);
        return o == null ? def : String.valueOf(o);
    }

    static boolean bool(Map<String, Object> m, String k, boolean def) {
        if (m == null) return def;
        Object o = m.get(k);
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        return def;
    }

    // ---------------------------------------------------------------- writing

    static String quote(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Compact number formatting (one decimal max unless tiny). */
    static void num(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append('0'); return; }
        long r = Math.round(d * 10);
        if (r % 10 == 0) sb.append(r / 10);
        else {
            if (r < 0) { sb.append('-'); r = -r; }
            sb.append(r / 10).append('.').append(r % 10);
        }
    }

    static void num2(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append('0'); return; }
        sb.append(Math.round(d * 100) / 100.0);
    }

    /** Serialise plain data (Map / List / String / Number / Boolean / null, double[] and int[]) as compact JSON. */
    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) sb.append(quote(s));
        else if (o instanceof Boolean b) sb.append(b.booleanValue());
        else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (k++ > 0) sb.append(',');
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List<?> l) {
            sb.append('[');
            for (int k = 0; k < l.size(); k++) { if (k > 0) sb.append(','); write(sb, l.get(k)); }
            sb.append(']');
        } else if (o instanceof double[] a) {
            sb.append('[');
            for (int k = 0; k < a.length; k++) { if (k > 0) sb.append(','); write(sb, a[k]); }
            sb.append(']');
        } else if (o instanceof int[] a) {
            sb.append('[');
            for (int k = 0; k < a.length; k++) { if (k > 0) sb.append(','); sb.append(a[k]); }
            sb.append(']');
        } else if (o instanceof double[][] a) {
            sb.append('[');
            for (int k = 0; k < a.length; k++) { if (k > 0) sb.append(','); write(sb, a[k]); }
            sb.append(']');
        } else sb.append(quote(String.valueOf(o)));
    }
}
