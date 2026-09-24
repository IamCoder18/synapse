package com.aaravlabs.synapse.bench.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer/parser for the fixed benchmark schema. No third-party deps
 * (the library's "no new dependencies" rule keeps the benchmark runner simple too).
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ write

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(4096);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(String.format(java.util.Locale.ROOT, "%.6f", d));
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            writeObject(sb, (Map<?, ?>) value, indent);
        } else if (value instanceof List) {
            writeArray(sb, (List<?>) value, indent);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            pad(sb, indent + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < map.size()) sb.append(',');
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i + 1 < list.size()) sb.append(',');
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    // ------------------------------------------------------------------- read

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObj();
                case '[':
                    return parseArr();
                case '"':
                    return parseStr();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNum();
            }
        }

        Map<String, Object> parseObj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String key = parseStr();
                skipWs();
                if (peek() != ':') throw new IllegalArgumentException("expected : at " + i);
                i++;
                m.put(key, parseValue());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + i);
            }
        }

        List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + i);
            }
        }

        String parseStr() {
            if (next() != '"') throw new IllegalArgumentException("expected string at " + i);
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("bad escape " + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNum() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, i);
            if (t.contains(".") || t.contains("e") || t.contains("E")) {
                return Double.parseDouble(t);
            }
            long l = Long.parseLong(t);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            return s.charAt(i);
        }

        char next() {
            return s.charAt(i++);
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("expected " + word + " at " + i);
            }
            i += word.length();
        }
    }
}
