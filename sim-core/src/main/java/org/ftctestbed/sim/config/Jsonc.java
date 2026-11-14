package org.ftctestbed.sim.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser that also accepts line and block comments and trailing commas,
 * so config files can be documented inline. Objects become LinkedHashMap, arrays
 * become ArrayList, numbers become Double.
 */
public final class Jsonc {
    private final String s;
    private final String source;
    private int i;

    private Jsonc(String s, String source) {
        this.s = s;
        this.source = source;
    }

    /** A JSON string literal, for writing config files. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    public static Object parse(String text, String sourceName) {
        Jsonc p = new Jsonc(text, sourceName);
        Object v = p.value();
        p.skipWs();
        if (p.i < p.s.length()) throw p.error("Unexpected trailing content");
        return v;
    }

    private Object value() {
        skipWs();
        if (i >= s.length()) throw error("Unexpected end of file");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) return number();
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        while (true) {
            skipWs();
            if (peek() == '}') { i++; return map; }
            if (peek() != '"') throw error("Expected a quoted key");
            String key = string();
            skipWs();
            expect(':');
            if (map.containsKey(key)) throw error("Duplicate key \"" + key + "\"");
            map.put(key, value());
            skipWs();
            if (peek() == ',') { i++; continue; }
            if (peek() == '}') { i++; return map; }
            throw error("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        while (true) {
            skipWs();
            if (peek() == ']') { i++; return list; }
            list.add(value());
            skipWs();
            if (peek() == ',') { i++; continue; }
            if (peek() == ']') { i++; return list; }
            throw error("Expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            i = start;
            throw error("Invalid number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("Unexpected token");
        i += word.length();
        return v;
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) throw error("Unterminated comment");
                i = end + 2;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (i >= s.length()) throw error("Unexpected end of file");
        return s.charAt(i);
    }

    private void expect(char c) {
        if (peek() != c) throw error("Expected '" + c + "'");
        i++;
    }

    private ConfigException error(String msg) {
        int line = 1, col = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
        }
        return new ConfigException(source + ":" + line + ":" + col + ": " + msg);
    }
}
