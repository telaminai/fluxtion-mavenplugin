package com.fluxtion.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and string quoter for the compiler's small auth and usage responses. The plugin keeps no
 * runtime dependencies, so this stays deliberately small: objects, arrays, strings, numbers, booleans and null.
 */
final class Json {

    /** Deeper nesting is refused rather than recursed into: a hostile body must not end in StackOverflowError. */
    static final int MAX_DEPTH = 32;

    private final String s;
    private int i;
    private int depth;

    private Json(String s) {
        this.s = s;
    }

    /** Parses a JSON object; throws {@link IllegalArgumentException} if the text is not one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Json p = new Json(text == null ? "" : text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!(v instanceof Map) || p.i != p.s.length()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return (Map<String, Object>) v;
    }

    /** A JSON string literal for {@code v}. */
    static String quote(String v) {
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

    /** {@code map.get(key)} as a string, or null when absent or JSON null. */
    static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    /** {@code map.get(key)} as a long, or {@code dflt} when absent or not a number. */
    static long num(Map<String, Object> map, String key, long dflt) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }

    private Object value() {
        if (i >= s.length()) throw fail();
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Map<String, Object> obj() {
        if (++depth > MAX_DEPTH) throw fail();
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            depth--;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw fail();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') {
                depth--;
                return m;
            }
            if (c != ',') throw fail();
        }
    }

    private List<Object> arr() {
        if (++depth > MAX_DEPTH) throw fail();
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            depth--;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') {
                depth--;
                return l;
            }
            if (c != ',') throw fail();
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw fail();
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw fail();
                    }
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw fail();
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw fail();
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw fail();
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        if (i >= s.length()) throw fail();
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) throw fail();
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw fail();
    }

    private IllegalArgumentException fail() {
        // never echo the text: it may carry a credential
        return new IllegalArgumentException("malformed JSON at offset " + i);
    }
}
