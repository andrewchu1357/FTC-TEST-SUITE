package org.ftctestbed.sim.code;

import org.ftctestbed.shim.ClockRewriter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;

/**
 * Loads a robot package's compiled team code (and its jars) ahead of the simulator's own
 * classpath, so its classes replace the example TeamCode classes of the same name. SDK, shim,
 * Android and simulator classes always come from the simulator, so the code talks to the
 * simulated hardware. A fresh loader per launch means a fresh set of static fields.
 */
public final class TeamCodeClassLoader extends URLClassLoader {
    private static final String[] PARENT_FIRST = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "com.qualcomm.", "org.firstinspires.ftc.robotcore.", "org.firstinspires.ftc.robotcontroller.",
            "org.firstinspires.ftc.vision.", "org.firstinspires.ftc.ftccommon.", "org.firstinspires.ftc.robotserver.",
            "android.", "androidx.annotation.", "org.ftctestbed.",
            // stand-ins for FTC Dashboard and Panels: a real copy in libs/ must not replace them
            "com.acmerobotics.dashboard.", "com.bylazar."};

    static {
        registerAsParallelCapable();
    }

    private final String[] alsoParentFirst;

    public TeamCodeClassLoader(URL[] urls, ClassLoader parent) {
        this(urls, parent, new String[0]);
    }

    /** alsoParentFirst: more package prefixes that always come from the simulator's classpath. */
    public TeamCodeClassLoader(URL[] urls, ClassLoader parent, String... alsoParentFirst) {
        super("team code", urls, parent);
        this.alsoParentFirst = alsoParentFirst;
    }

    private boolean parentFirst(String name) {
        for (String p : PARENT_FIRST) if (name.startsWith(p)) return true;
        for (String p : alsoParentFirst) if (name.startsWith(p)) return true;
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null && !parentFirst(name)) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    // not team code: fall through to the simulator's classpath
                }
            }
            if (c == null) c = super.loadClass(name, false);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    /** Team classes run with their clock calls on simulated time (see ClockRewriter). */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        URL url = findResource(name.replace('.', '/') + ".class");
        if (url == null) throw new ClassNotFoundException(name);
        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        byte[] rewritten = ClockRewriter.rewrite(bytes);
        if (rewritten != null) bytes = rewritten;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String pkg = name.substring(0, dot);
            if (getDefinedPackage(pkg) == null) {
                try {
                    definePackage(pkg, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException alreadyDefined) {
                    // another thread defined it first
                }
            }
        }
        return defineClass(name, bytes, 0, bytes.length, new CodeSource(url, (CodeSigner[]) null));
    }

    @Override
    public URL getResource(String name) {
        URL u = findResource(name);
        return u != null ? u : super.getResource(name);
    }
}
