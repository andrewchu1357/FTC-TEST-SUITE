package org.ftctestbed.viewer.agent;

import com.qualcomm.robotcore.hardware.Gamepad;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gamepad states from JSON: the SDK's own field names (left_stick_y, a, dpad_up, right_trigger...),
 * so what an agent sends reads like the TeleOp code it drives. Sticks and triggers are numbers
 * (-1..1, triggers 0..1; pushing a stick up is negative y, as on a real gamepad), buttons true/false.
 */
final class Gamepads {
    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    static {
        for (Field f : Gamepad.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != float.class && f.getType() != boolean.class) continue;
            if (f.getName().endsWith("_pressed")) continue; // worked out from the triggers
            FIELDS.put(f.getName(), f);
        }
    }

    private Gamepads() {}

    static List<String> names() {
        return new ArrayList<>(FIELDS.keySet());
    }

    /** A copy of current with these fields changed (neutral: everything released first). */
    static Gamepad edited(Gamepad current, Map<String, Object> fields, boolean neutral) {
        Gamepad g = new Gamepad();
        if (!neutral && current != null) g.copy(current);
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Field f = FIELDS.get(e.getKey());
            if (f == null) throw new Json.ToolError("No gamepad field \"" + e.getKey() + "\". Fields: " + FIELDS.keySet());
            try {
                if (f.getType() == float.class) {
                    if (!(e.getValue() instanceof Number n)) throw new Json.ToolError(e.getKey() + " takes a number");
                    float v = (float) Math.max(-1, Math.min(1, n.doubleValue()));
                    f.setFloat(g, v);
                } else {
                    Object v = e.getValue();
                    boolean b = v instanceof Boolean bb ? bb : v instanceof Number n ? n.doubleValue() != 0 : Boolean.parseBoolean(String.valueOf(v));
                    f.setBoolean(g, b);
                }
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return g;
    }

    /** The pressed buttons and moved sticks of a gamepad (what's not at rest). */
    static Map<String, Object> describe(Gamepad g) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Field f : FIELDS.values()) {
            try {
                if (f.getType() == float.class) {
                    float v = f.getFloat(g);
                    if (v != 0) m.put(f.getName(), Views.r3(v));
                } else if (f.getBoolean(g)) {
                    m.put(f.getName(), true);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return m;
    }
}
