package com.timetable.util;

import java.util.*;

/**
 * Minimal JSON support so the project needs no Gson/Jackson dependency.
 * toJson: Map / List / String / Number / Boolean / null  ->  text
 * parse : text -> LinkedHashMap / ArrayList / String / Long / Double / Boolean / null
 */
public class JsonUtil {

    // ------------------------------------------------------------ writing
    public static String toJson(Object obj) {
        StringBuilder sb = new StringBuilder();
        write(obj, sb);
        return sb.toString();
    }

    private static void write(Object obj, StringBuilder sb) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String) {
            quote((String) obj, sb);
        } else if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj.toString());
        } else if (obj instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (obj instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) obj) {
                if (!first) sb.append(',');
                first = false;
                write(item, sb);
            }
            sb.append(']');
        } else if (obj instanceof int[]) {
            sb.append('[');
            int[] a = (int[]) obj;
            for (int k = 0; k < a.length; k++) { if (k > 0) sb.append(','); sb.append(a[k]); }
            sb.append(']');
        } else if (obj instanceof int[][]) {
            sb.append('[');
            int[][] a = (int[][]) obj;
            for (int k = 0; k < a.length; k++) { if (k > 0) sb.append(','); write(a[k], sb); }
            sb.append(']');
        } else {
            quote(obj.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
        sb.append('"');
    }

    public static String errorJson(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return toJson(m);
    }

    // ------------------------------------------------------------ parsing
    public static Object parse(String json) {
        Parser p = new Parser(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos < p.s.length()) throw new IllegalArgumentException("Unexpected trailing characters in JSON.");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object.");
        return (Map<String, Object>) o;
    }

    /** Backwards-compatible helper: flat object whose values are all turned into Strings. */
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return out;
        for (Map.Entry<String, Object> e : parseObject(json).entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v == null ? null : (v instanceof Map || v instanceof List ? toJson(v) : v.toString()));
        }
        return out;
    }

    private static class Parser {
        final String s;
        int pos = 0;

        Parser(String s) { this.s = s == null ? "" : s; }

        void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        char peek() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON.");
            return s.charAt(pos);
        }

        Object value() {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; ws();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (peek() != ':') throw new IllegalArgumentException("Expected ':' in JSON object.");
                pos++; ws();
                m.put(k, value());
                ws();
                char c = peek();
                pos++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' in JSON object.");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; ws();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                pos++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' in JSON array.");
            }
        }

        String string() {
            if (peek() != '"') throw new IllegalArgumentException("Expected a JSON string.");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = peek();
                    pos++;
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String t = s.substring(start, pos);
            if (t.isEmpty()) throw new IllegalArgumentException("Invalid JSON value at position " + start + ".");
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.valueOf(t);
            return Long.valueOf(t);
        }
    }
}
