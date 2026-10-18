package com.timetable.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class JsonUtil {

    public static String toJson(Object obj) {
        StringBuilder sb = new StringBuilder();
        write(obj, sb);
        return sb.toString();
    }

    private static void write(Object obj, StringBuilder sb) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String) {
            sb.append('"').append(escape((String) obj)).append('"');
        } else if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj.toString());
        } else if (obj instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (obj instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) obj) {
                if (!first) sb.append(',');
                first = false;
                write(item, sb);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(obj.toString())).append('"');
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static String errorJson(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return toJson(m);
    }

   
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
        if (json.trim().isEmpty()) return map;

        // split on commas that are outside double quotes
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            if (pair.trim().isEmpty()) continue;
            int idx = pair.indexOf(':');
            if (idx < 0) continue;
            String key = stripQuotes(pair.substring(0, idx).trim());
            String value = stripQuotes(pair.substring(idx + 1).trim());
            map.put(key, value);
        }
        return map;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
