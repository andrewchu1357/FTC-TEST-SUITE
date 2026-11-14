package org.ftctestbed.sim.tools;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks that precompiled libraries (FTCLib, Road Runner, ...) only use FTC SDK / Android
 * classes, methods and fields that the shim provides. TeamCode itself is checked by the
 * compiler, but a library jar was compiled against the real SDK and would only fail at
 * runtime with NoSuchMethodError / NoClassDefFoundError.
 *
 * Usage: ShimCheck lib1.jar lib2.jar ...   (the shim must be on this tool's classpath)
 */
public final class ShimCheck {
    private static final String[] CHECKED_PREFIXES = {"com/qualcomm/", "org/firstinspires/", "android/", "androidx/"};

    private record Ref(String kind, String owner, String name, String descriptor) {}

    public static void main(String[] args) throws IOException {
        Map<String, Set<String>> missing = new TreeMap<>();
        int jars = 0, classes = 0;
        for (String path : args) {
            File f = new File(path);
            if (!f.getName().endsWith(".jar") || !f.isFile()) continue;
            jars++;
            try (JarFile jar = new JarFile(f)) {
                Enumeration<JarEntry> e = jar.entries();
                while (e.hasMoreElements()) {
                    JarEntry entry = e.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF")) continue;
                    if (isChecked(entry.getName())) continue; // the library's own copy of an SDK class
                    classes++;
                    String user = f.getName() + ": " + entry.getName().replace(".class", "").replace('/', '.');
                    try (InputStream in = jar.getInputStream(entry)) {
                        for (Ref ref : readRefs(in)) {
                            String problem = check(ref);
                            if (problem != null) missing.computeIfAbsent(problem, k -> new TreeSet<>()).add(user);
                        }
                    }
                }
            }
        }

        System.out.println("Scanned " + classes + " classes in " + jars + " library jars.");
        if (missing.isEmpty()) {
            System.out.println("OK - every FTC SDK / Android API these libraries use is provided by the shim.");
            return;
        }
        System.out.println(missing.size() + " SDK/Android APIs used by libraries are missing from the shim.");
        System.out.println("Library code that touches them will fail if it runs:\n");
        for (Map.Entry<String, Set<String>> m : missing.entrySet()) {
            System.out.println("  MISSING " + m.getKey());
            int shown = 0;
            for (String u : m.getValue()) {
                if (shown++ == 3) {
                    System.out.println("      ... and " + (m.getValue().size() - 3) + " more");
                    break;
                }
                System.out.println("      used by " + u);
            }
        }
    }

    private static boolean isChecked(String internalName) {
        for (String p : CHECKED_PREFIXES) if (internalName.startsWith(p)) return true;
        return false;
    }

    private static String check(Ref ref) {
        if (!isChecked(ref.owner)) return null;
        String cn = ref.owner.replace('/', '.');
        Class<?> c;
        try {
            c = Class.forName(cn, false, ShimCheck.class.getClassLoader());
        } catch (Throwable t) {
            return "class " + cn;
        }
        if (ref.kind.equals("class")) return null;
        if (ref.kind.equals("field")) {
            return findField(c, ref.name, ref.descriptor) ? null : "field " + cn + "." + ref.name + " " + ref.descriptor;
        }
        return findMethod(c, ref.name, ref.descriptor) ? null : "method " + cn + "." + ref.name + ref.descriptor;
    }

    private static boolean findField(Class<?> c, String name, String desc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) if (f.getName().equals(name) && descriptor(f.getType()).equals(desc)) return true;
            for (Class<?> i : k.getInterfaces()) if (findField(i, name, desc)) return true;
        }
        return false;
    }

    private static boolean findMethod(Class<?> c, String name, String desc) {
        if (name.equals("<init>")) {
            for (Constructor<?> k : c.getDeclaredConstructors()) if (descriptor(k.getParameterTypes(), void.class).equals(desc)) return true;
            return false;
        }
        if (name.equals("<clinit>")) return true;
        List<Class<?>> todo = new ArrayList<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> k = todo.remove(todo.size() - 1);
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && descriptor(m.getParameterTypes(), m.getReturnType()).equals(desc)) return true;
            }
            if (k.getSuperclass() != null) todo.add(k.getSuperclass());
            todo.addAll(List.of(k.getInterfaces()));
        }
        // Methods every class inherits from Object (e.g. an interface ref to toString()).
        for (Method m : Object.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptor(m.getParameterTypes(), m.getReturnType()).equals(desc)) return true;
        }
        return false;
    }

    private static String descriptor(Class<?>[] params, Class<?> ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(descriptor(p));
        return sb.append(')').append(descriptor(ret)).toString();
    }

    private static String descriptor(Class<?> t) {
        if (t.isArray()) return t.getName().replace('.', '/');
        if (t == void.class) return "V";
        if (t == boolean.class) return "Z";
        if (t == byte.class) return "B";
        if (t == char.class) return "C";
        if (t == short.class) return "S";
        if (t == int.class) return "I";
        if (t == long.class) return "J";
        if (t == float.class) return "F";
        if (t == double.class) return "D";
        return "L" + t.getName().replace('.', '/') + ";";
    }

    /** Reads class, field and method references from a class file's constant pool. */
    private static List<Ref> readRefs(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[][] links = new int[count][];
        int[] tags = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                case 7, 8, 16, 19, 20 -> links[i] = new int[]{in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> links[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                default -> throw new IOException("bad constant pool tag " + tag);
            }
        }
        List<Ref> refs = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 7) {
                String name = (String) pool[links[i][0]];
                if (!name.startsWith("[")) refs.add(new Ref("class", name, "", ""));
            } else if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                String owner = (String) pool[links[links[i][0]][0]];
                int[] nat = links[links[i][1]];
                if (owner.startsWith("[")) continue;
                refs.add(new Ref(tags[i] == 9 ? "field" : "method", owner, (String) pool[nat[0]], (String) pool[nat[1]]));
            }
        }
        return refs;
    }
}
