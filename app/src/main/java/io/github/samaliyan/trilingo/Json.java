package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and string quoting. Pure Java so the parsing of the translation services
 * and of the saved history can be unit-tested without Android.
 * Values: Map (object), List (array), String, Double, Boolean, or null.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) throws IllegalArgumentException {
        if (text == null) throw new IllegalArgumentException("null");
        Json j = new Json(text);
        j.ws();
        Object v = j.value(0);
        j.ws();
        if (j.i != j.s.length()) throw j.err("trailing data");
        return v;
    }

    // ------------------------------------------------------------------ typed access (never throw)

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    static Map<String, Object> obj(Map<String, Object> m, String key) {
        return m == null ? null : obj(m.get(key));
    }

    static List<Object> arr(Map<String, Object> m, String key) {
        return m == null ? null : arr(m.get(key));
    }

    static String str(Map<String, Object> m, String key) {
        return m == null ? null : str(m.get(key));
    }

    static double num(Map<String, Object> m, String key, double def) {
        Object o = m == null ? null : m.get(key);
        return o instanceof Double ? (Double) o : def;
    }

    static Object at(List<Object> l, int index) {
        return l != null && index >= 0 && index < l.size() ? l.get(index) : null;
    }

    // ------------------------------------------------------------------ writing

    static String quote(String v) {
        if (v == null) return "null";
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ reading

    private IllegalArgumentException err(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value(int depth) {
        if (depth > 64) throw err("too deep");
        if (i >= s.length()) throw err("end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected '" + c + "'");
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw err("bad literal");
        i += w.length();
        return v;
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("key expected");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("':' expected");
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) throw err("end in object");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("',' expected");
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            // Google's array format can contain empty slots like [1,,2]; read them as null.
            if (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == ']')) l.add(null);
            else l.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw err("end in array");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("',' expected");
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("end in string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw err("end in escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("bad \\u");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw err("bad \\u");
                    }
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Double number() {
        int st = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        try {
            return Double.parseDouble(s.substring(st, i));
        } catch (NumberFormatException x) {
            throw err("bad number");
        }
    }
}
