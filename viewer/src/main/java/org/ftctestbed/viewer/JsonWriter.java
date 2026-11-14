package org.ftctestbed.viewer;

import java.util.ArrayDeque;
import java.util.Deque;

/** A small streaming JSON writer (the project has a JSON reader in Jsonc, but no writer). */
final class JsonWriter {
    private final StringBuilder sb = new StringBuilder(16 * 1024);
    private final Deque<boolean[]> first = new ArrayDeque<>(); // per open container: no element written yet
    private boolean afterName;

    JsonWriter beginObject() {
        prefix();
        sb.append('{');
        first.push(new boolean[]{true});
        return this;
    }

    JsonWriter endObject() {
        first.pop();
        sb.append('}');
        return this;
    }

    JsonWriter beginArray() {
        prefix();
        sb.append('[');
        first.push(new boolean[]{true});
        return this;
    }

    JsonWriter endArray() {
        first.pop();
        sb.append(']');
        return this;
    }

    JsonWriter name(String name) {
        prefix();
        string(name);
        sb.append(':');
        afterName = true;
        return this;
    }

    /** Numbers are rounded to 4 decimals (0.1 mm in meters); NaN and infinities become null. */
    JsonWriter value(double v) {
        prefix();
        if (!Double.isFinite(v)) {
            sb.append("null");
        } else {
            double r = Math.round(v * 1e4) / 1e4;
            if (r == Math.rint(r) && Math.abs(r) < 1e15) sb.append((long) r);
            else sb.append(r);
        }
        return this;
    }

    JsonWriter value(long v) {
        prefix();
        sb.append(v);
        return this;
    }

    JsonWriter value(boolean v) {
        prefix();
        sb.append(v);
        return this;
    }

    JsonWriter value(String v) {
        prefix();
        if (v == null) sb.append("null");
        else string(v);
        return this;
    }

    JsonWriter field(String name, double v) { return name(name).value(v); }
    JsonWriter field(String name, long v) { return name(name).value(v); }
    JsonWriter field(String name, boolean v) { return name(name).value(v); }
    JsonWriter field(String name, String v) { return name(name).value(v); }

    /** A color as "#rrggbb" (negative = none, written as null). */
    JsonWriter color(String name, int rgb) {
        return name(name).value(rgb < 0 ? null : String.format("#%06x", rgb & 0xFFFFFF));
    }

    private void prefix() {
        if (afterName) {
            afterName = false;
            return;
        }
        boolean[] f = first.peek();
        if (f == null) return;
        if (!f[0]) sb.append(',');
        f[0] = false;
    }

    private void string(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
