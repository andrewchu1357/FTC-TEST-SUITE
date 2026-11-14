package org.ftctestbed.viewer.agent;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The robot's hardwareMap, as robot code sees it: every device by name, its readings (every
 * getter, distance in inches, angles in degrees), and any method called by name, so an agent can
 * check wiring, read sensors and drive a mechanism directly without writing an OpMode.
 */
final class HardwareTools {
    private static final Set<String> SKIP = Set.of("getClass", "hashCode", "toString", "getDeviceClient", "getController",
            "getManufacturer", "getVersion", "getConnectionInfo", "getDeviceName", "getMotorType", "getParameters");

    private final AgentApi api;

    HardwareTools(AgentApi api) {
        this.api = api;
    }

    List<Tool> tools() {
        return List.of(new Tool("hardware", "Robot hardware", "Robot",
                "The robot's hardwareMap as robot code sees it. list: every device name with its type. read: a device's readings "
                        + "(every getter: motor power, mode, encoder position, velocity, current; servo position; sensor distances (inches), "
                        + "colors, angles (degrees), IMU yaw/pitch/roll, Pinpoint/OTOS poses, Limelight results...). call: run any method "
                        + "on a device with arguments, e.g. setPower [0.5], setTargetPosition [1200], setMode [\"RUN_TO_POSITION\"], "
                        + "getDistance [\"CM\"]. Enum arguments go as their names. Calls made while an OpMode runs compete with it.",
                Tool.Schema.of().session().robot()
                        .oneOf("action", "list (default), read, call.", "list", "read", "call")
                        .str("name", "Device name (as in hardwareMap.get(..., \"name\")); read without one reads every device.")
                        .str("method", "call: the method name, e.g. setPower.")
                        .array("args", "call: arguments, converted to the method's parameter types.", Json.obj())
                        .build(), false, this::hardware));
    }

    /** Every name in the hardware map with the device types under it. */
    static Map<String, Object> names(HardwareMap hm) {
        Map<String, Object> out = new TreeMap<>();
        for (HardwareDevice d : hm) {
            for (String n : hm.getNamesOf(d)) {
                @SuppressWarnings("unchecked")
                List<String> types = (List<String>) out.computeIfAbsent(n, k -> new ArrayList<String>());
                String t = typeName(d);
                if (!types.contains(t)) types.add(t);
            }
        }
        return out;
    }

    /** The SDK interface or class robot code would ask for (DcMotorEx, Servo, GoBildaPinpointDriver...). */
    static String typeName(Object d) {
        Class<?> c = d.getClass();
        if (!c.getName().startsWith("org.ftctestbed.")) return c.getSimpleName();
        // A simulator class: name the most specific SDK interface it implements.
        List<Class<?>> ifs = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (!k.getName().startsWith("org.ftctestbed.") && k != Object.class) return k.getSimpleName();
            for (Class<?> i : k.getInterfaces()) collect(i, ifs);
        }
        ifs.removeIf(i -> i.getName().startsWith("org.ftctestbed.") || i.getName().startsWith("java."));
        ifs.removeIf(i -> ifs.stream().anyMatch(j -> j != i && i.isAssignableFrom(j)));
        if (ifs.isEmpty()) return c.getSimpleName();
        ifs.sort(Comparator.comparing(Class::getSimpleName));
        return String.join("/", ifs.stream().map(Class::getSimpleName).toList());
    }

    private static void collect(Class<?> i, List<Class<?>> out) {
        if (!out.contains(i)) out.add(i);
        for (Class<?> j : i.getInterfaces()) collect(j, out);
    }

    private Tool.Result hardware(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int r = RobotTools.robot(c, a);
        HardwareMap hm = c.hardwareMap(r);
        String action = a.str("action", "list");
        return switch (action) {
            case "list" -> {
                List<Object> out = new ArrayList<>();
                Set<HardwareDevice> seen = new LinkedHashSet<>();
                for (HardwareDevice d : hm) {
                    if (!seen.add(d)) continue;
                    String desc;
                    try {
                        desc = d.getDeviceName();
                    } catch (RuntimeException e) {
                        desc = null;
                    }
                    out.add(Json.obj("names", new ArrayList<>(hm.getNamesOf(d)), "type", typeName(d), "description", desc));
                }
                yield Tool.Result.of("session", c.id(), "robot", r, "devices", out);
            }
            case "read" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                if (a.has("name")) {
                    for (HardwareDevice d : devices(hm, a.str("name"))) out.put(typeName(d), read(d));
                    yield Tool.Result.of("session", c.id(), "robot", r, "name", a.str("name"), "timeSeconds", Views.r3(c.sim().clock.seconds()), "readings", out);
                }
                Set<HardwareDevice> seen = new LinkedHashSet<>();
                for (HardwareDevice d : hm) {
                    if (!seen.add(d)) continue;
                    out.put(String.join(", ", hm.getNamesOf(d)) + " (" + typeName(d) + ")", read(d));
                }
                yield Tool.Result.of("session", c.id(), "robot", r, "timeSeconds", Views.r3(c.sim().clock.seconds()), "readings", out);
            }
            case "call" -> {
                String method = a.str("method");
                List<Object> args = a.list("args");
                Object result = null;
                Method used = null;
                List<String> tried = new ArrayList<>();
                for (HardwareDevice d : devices(hm, a.str("name"))) {
                    for (Method m : publicMethods(d.getClass())) {
                        if (!m.getName().equals(method) || m.getParameterCount() != args.size()) continue;
                        Object[] conv = convert(m.getParameterTypes(), args);
                        tried.add(signature(m));
                        if (conv == null) continue;
                        try {
                            m.trySetAccessible();
                            result = m.invoke(d, conv);
                        } catch (InvocationTargetException e) {
                            throw new Json.ToolError(method + " threw " + org.ftctestbed.sim.opmode.OpModeRunner.describe(e.getCause()));
                        }
                        used = m;
                        break;
                    }
                    if (used != null) break;
                }
                if (used == null) {
                    List<String> all = new ArrayList<>();
                    for (HardwareDevice d : devices(hm, a.str("name"))) {
                        for (Method m : publicMethods(d.getClass())) if (m.getName().equals(method)) all.add(signature(m));
                    }
                    throw new Json.ToolError(all.isEmpty() ? "\"" + a.str("name") + "\" has no method " + method
                            : "No " + method + " takes those arguments. It has: " + all);
                }
                yield Tool.Result.of("session", c.id(), "robot", r, "called", signature(used),
                        "returned", used.getReturnType() == void.class ? null : value(result));
            }
            default -> throw new Json.ToolError("action: list, read or call");
        };
    }

    private static List<HardwareDevice> devices(HardwareMap hm, String name) {
        List<HardwareDevice> out = new ArrayList<>();
        for (HardwareDevice d : hm) if (hm.getNamesOf(d).contains(name) && !out.contains(d)) out.add(d);
        if (out.isEmpty()) throw new Json.ToolError("No device named \"" + name + "\". Names: " + names(hm).keySet());
        return out;
    }

    /** Public methods robot code could call (not the simulator's own sim... and internal... ones), once per signature. */
    static List<Method> publicMethods(Class<?> c) {
        Map<String, Method> out = new TreeMap<>();
        for (Method m : c.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) continue;
            String n = m.getName();
            if (n.startsWith("sim") || n.startsWith("internal") || n.startsWith("lambda$")) continue;
            out.putIfAbsent(signature(m), m);
        }
        return new ArrayList<>(out.values());
    }

    static String signature(Method m) {
        StringBuilder b = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(", ");
            b.append(p[i].getSimpleName());
        }
        return b.append(')').toString();
    }

    /** Every reading: getters without arguments, and those taking a unit (inches, degrees, amps, ...). */
    static Map<String, Object> read(Object d) {
        Map<String, Object> out = new TreeMap<>();
        for (Method m : publicMethods(d.getClass())) {
            String n = m.getName();
            if (!(n.startsWith("get") || n.startsWith("is")) || SKIP.contains(n) || m.getReturnType() == void.class) continue;
            Object[] args;
            String key = n;
            if (m.getParameterCount() == 0) {
                args = new Object[0];
            } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isEnum()) {
                Object unit = defaultUnit(m.getParameterTypes()[0]);
                if (unit == null) continue;
                args = new Object[]{unit};
                key = n + "(" + unit + ")";
            } else {
                continue;
            }
            try {
                m.trySetAccessible();
                out.put(key, value(m.invoke(d, args)));
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause();
                if (t instanceof UnsupportedOperationException) continue;
                out.put(key, "(threw " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()) + ")");
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not readable from here
            }
        }
        return out;
    }

    /** Inches, degrees, amps, ... for the SDK's unit enums. */
    private static Object defaultUnit(Class<?> enumType) {
        String want = switch (enumType.getSimpleName()) {
            case "DistanceUnit" -> "INCH";
            case "AngleUnit", "UnnormalizedAngleUnit" -> "DEGREES";
            case "CurrentUnit" -> "AMPS";
            case "VoltageUnit" -> "VOLTS";
            case "TempUnit" -> "CELSIUS";
            default -> null;
        };
        if (want == null) return null;
        for (Object c : enumType.getEnumConstants()) if (((Enum<?>) c).name().equals(want)) return c;
        return null;
    }

    /** A value as JSON can carry it: numbers, text, lists, records; other objects by their own description. */
    static Object value(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof Enum<?> || v instanceof Record) return v;
        if (v instanceof Character ch) return String.valueOf(ch);
        if (v.getClass().isArray()) {
            if (v instanceof byte[] b && b.length > 64) return "(" + b.length + " bytes)";
            List<Object> out = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < Math.min(n, 200); i++) out.add(value(java.lang.reflect.Array.get(v, i)));
            return out;
        }
        if (v instanceof java.util.Collection<?> col) {
            List<Object> out = new ArrayList<>();
            for (Object o : col) {
                if (out.size() >= 200) break;
                out.add(value(o));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), value(e.getValue()));
            return out;
        }
        String s = v.toString();
        // Without a toString of its own (Temperature, Pose2D...): its public fields say what it is.
        if (s.equals(v.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(v)))) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (java.lang.reflect.Field f : v.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    Object fv = f.get(v);
                    out.put(f.getName(), fv == v ? null : fv instanceof Number || fv instanceof Boolean || fv instanceof String || fv instanceof Enum<?> ? fv : String.valueOf(fv));
                } catch (IllegalAccessException ignored) {
                    // not readable
                }
            }
            if (!out.isEmpty()) return out;
        }
        return s;
    }

    /** Arguments converted to these parameter types, or null if they don't fit. */
    static Object[] convert(Class<?>[] types, List<Object> args) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object a = args.get(i);
            Class<?> t = types[i];
            Object v = convertOne(t, a);
            if (v == NO) return null;
            out[i] = v;
        }
        return out;
    }

    private static final Object NO = new Object();

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convertOne(Class<?> t, Object a) {
        if (a == null) return t.isPrimitive() ? NO : null;
        if (a instanceof Number n) {
            double d = n.doubleValue();
            boolean whole = d == Math.rint(d);
            if (t == double.class || t == Double.class) return d;
            if (t == float.class || t == Float.class) return (float) d;
            if ((t == int.class || t == Integer.class) && whole) return (int) d;
            if ((t == long.class || t == Long.class) && whole) return (long) d;
            if ((t == short.class || t == Short.class) && whole) return (short) d;
            if ((t == byte.class || t == Byte.class) && whole) return (byte) d;
            if (t == Object.class || t == Number.class) return d;
            return NO;
        }
        if (a instanceof Boolean b) return t == boolean.class || t == Boolean.class || t == Object.class ? b : NO;
        if (a instanceof String s) {
            if (t == String.class || t == Object.class || t == CharSequence.class) return s;
            if (t.isEnum()) {
                for (Object c : t.getEnumConstants()) if (((Enum<?>) c).name().equalsIgnoreCase(s)) return c;
                return NO;
            }
            if ((t == char.class || t == Character.class) && s.length() == 1) return s.charAt(0);
            return NO;
        }
        if (a instanceof List<?> l && t.isArray()) {
            Object arr = java.lang.reflect.Array.newInstance(t.getComponentType(), l.size());
            for (int i = 0; i < l.size(); i++) {
                Object v = convertOne(t.getComponentType(), l.get(i));
                if (v == NO) return NO;
                java.lang.reflect.Array.set(arr, i, v);
            }
            return arr;
        }
        return NO;
    }
}
