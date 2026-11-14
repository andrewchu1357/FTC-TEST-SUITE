package org.ftctestbed.sim.opmode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds @TeleOp / @Autonomous / @Utility OpModes the same way the Driver Station does. Scans the
 * classpath entries listed in the "testbed.opmodeRoots" system property, or otherwise every
 * classpath entry whose path contains "TeamCode".
 */
public final class OpModeRegistry {
    public record Result(List<OpModeInfo> opModes, List<String> warnings) {}

    private OpModeRegistry() {}

    public static Result scan() {
        List<String> roots = teamCodeRoots();
        Result r = scan(roots, OpModeRegistry.class.getClassLoader());
        if (roots.isEmpty()) r.warnings().add("No TeamCode classes found on the classpath");
        return r;
    }

    /** Where the project's TeamCode classes are on the classpath (see the class comment). */
    public static List<String> teamCodeRoots() {
        List<String> roots = new ArrayList<>();
        String prop = System.getProperty("testbed.opmodeRoots");
        if (prop != null && !prop.isBlank()) {
            for (String p : prop.split(File.pathSeparator)) if (!p.isBlank()) roots.add(p);
        } else {
            for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) {
                if (p.contains("TeamCode")) roots.add(p);
            }
        }
        return roots;
    }

    /** Finds OpModes in the given class folders / jars, loading them with loader. */
    public static Result scan(List<String> roots, ClassLoader loader) {
        List<String> classNames = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String root : roots) {
            File f = new File(root);
            try {
                if (f.isDirectory()) {
                    Path base = f.toPath();
                    try (Stream<Path> walk = Files.walk(base)) {
                        walk.filter(p -> p.toString().endsWith(".class"))
                                .forEach(p -> classNames.add(toClassName(base.relativize(p).toString())));
                    }
                } else if (f.isFile() && f.getName().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(f)) {
                        Enumeration<JarEntry> e = jar.entries();
                        while (e.hasMoreElements()) {
                            String n = e.nextElement().getName();
                            if (n.endsWith(".class") && !n.startsWith("META-INF")) classNames.add(toClassName(n));
                        }
                    }
                }
            } catch (IOException e) {
                warnings.add("Could not scan " + root + ": " + e.getMessage());
            }
        }
        List<OpModeInfo> found = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String cn : classNames) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, loader);
            } catch (Throwable t) {
                warnings.add("Skipped " + cn + ": " + t);
                continue;
            }
            registrars(c, found, names, warnings);
            if (!OpMode.class.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers())) continue;
            TeleOp tele = c.getAnnotation(TeleOp.class);
            Autonomous auto = c.getAnnotation(Autonomous.class);
            com.qualcomm.robotcore.eventloop.opmode.Utility utility = c.getAnnotation(com.qualcomm.robotcore.eventloop.opmode.Utility.class);
            if (tele == null && auto == null && utility == null) continue;
            if (c.isAnnotationPresent(Disabled.class)) continue;
            String name = tele != null ? tele.name() : auto != null ? auto.name() : utility.name();
            if (name.isEmpty()) name = c.getSimpleName();
            String group = tele != null ? tele.group() : auto != null ? auto.group() : "";
            if (!names.add(name)) {
                warnings.add("Duplicate OpMode name \"" + name + "\" (" + cn + ") - the real robot would refuse to start");
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends OpMode> type = (Class<? extends OpMode>) c;
            found.add(new OpModeInfo(name, group, auto != null, auto != null ? auto.preselectTeleOp() : "", type, null,
                    tele == null && auto == null));
        }
        found.sort(Comparator.comparing(OpModeInfo::group).thenComparing(OpModeInfo::name));
        return new Result(found, warnings);
    }

    /**
     * Calls a class's @OpModeRegistrar methods (public static, taking an OpModeManager, or a
     * Context and an OpModeManager) the way the Robot Controller does, collecting the OpModes they
     * register. Road Runner's TuningOpModes registers its tuning OpModes this way.
     */
    private static void registrars(Class<?> c, List<OpModeInfo> found, Set<String> names, List<String> warnings) {
        java.lang.reflect.Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            return; // a class whose methods mention missing types: it can't be a registrar
        }
        for (java.lang.reflect.Method m : methods) {
            int mods = m.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || Modifier.isAbstract(mods)
                    || !m.isAnnotationPresent(com.qualcomm.robotcore.eventloop.opmode.OpModeRegistrar.class)) continue;
            int n = m.getParameterCount();
            if (n != 1 && n != 2) continue;
            Manager manager = new Manager(found, names, warnings, c.getName() + "." + m.getName());
            try {
                if (n == 1) m.invoke(null, manager);
                else m.invoke(null, null, manager);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                warnings.add("@OpModeRegistrar " + c.getSimpleName() + "." + m.getName() + " failed: " + cause);
            }
        }
    }

    private record Manager(List<OpModeInfo> found, Set<String> names, List<String> warnings, String where)
            implements com.qualcomm.robotcore.eventloop.opmode.OpModeManager {
        private void add(org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta meta, Class<? extends OpMode> type, OpMode instance) {
            if (!names.add(meta.name)) {
                warnings.add("Duplicate OpMode name \"" + meta.name + "\" (" + where + ") - the real robot would refuse to start");
                return;
            }
            String group = meta.group == null || meta.group.equals(org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta.DefaultGroup) ? "" : meta.group;
            found.add(new OpModeInfo(meta.name, group,
                    meta.flavor == org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta.Flavor.AUTONOMOUS,
                    meta.autoTransition != null ? meta.autoTransition : "", type, instance,
                    meta.flavor == org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta.Flavor.UTILITY));
        }

        private static org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta meta(String name) {
            return new org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta.Builder().setName(name).build();
        }

        @Override public void register(String name, Class<? extends OpMode> type) { add(meta(name), type, null); }
        @Override public void register(org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta meta, Class<? extends OpMode> type) { add(meta, type, null); }
        @Override public void register(String name, OpMode instance) { add(meta(name), instance.getClass(), instance); }
        @Override public void register(org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta meta, OpMode instance) { add(meta, instance.getClass(), instance); }
    }

    private static String toClassName(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.').replace('\\', '.');
    }
}
