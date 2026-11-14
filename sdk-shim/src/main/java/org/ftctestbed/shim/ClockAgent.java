package org.ftctestbed.shim;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Java agent (-javaagent:sim-clock-agent.jar, added by the Gradle tasks) that runs
 * {@link ClockRewriter} on robot code and its libraries as they load, so their own timing runs on
 * simulated time. The JDK, the simulator, the shim and the simulator's own libraries are left
 * alone.
 */
public final class ClockAgent {
    private ClockAgent() {}

    private static volatile boolean active;

    private static final String[] SKIP = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/", "com/apple/", "apple/", "org/ftctestbed/", "org/dyn4j/", "javafx/",
            "org/junit/", "junit/", "org/gradle/", "worker/org/gradle/", "org/opentest4j/", "org/apiguardian/",
            "net/bytebuddy/", "org/objectweb/", "kotlin/", "kotlinx/", "com/badlogic/", "org/w3c/", "org/xml/",
            "com/google/gson/", "com/studiohartman/", "fi/iki/elonen/",
    };

    /** True when robot code's clock calls run on simulated time (the agent is loaded). */
    public static boolean isActive() {
        return active;
    }

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (className == null || redefined != null || !wanted(className, loader, domain)) return null;
                return ClockRewriter.rewrite(bytes);
            }
        });
        active = true;
    }

    /**
     * Whether a class gets its clock calls rewritten. Never the JDK's own classes: they load
     * through the boot or platform class loader, which can't see SimTime, so a rewritten one fails
     * with NoClassDefFoundError when it runs (macOS's com.apple.laf combo box did, swallowing every
     * drop-down menu choice in the window).
     */
    public static boolean wanted(String className, ClassLoader loader, ProtectionDomain domain) {
        if (loader == null || loader == ClassLoader.getSystemClassLoader().getParent()) return false; // boot or platform
        for (String prefix : SKIP) if (className.startsWith(prefix)) return false;
        CodeSource source = domain == null ? null : domain.getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location != null) {
            String path = location.getPath();
            // The shim's own classes (its FTC Dashboard and Panels stand-ins measure wall time for the viewer).
            if (path.contains("/sdk-shim/") || path.contains("sdk-shim.jar") || path.contains("/sim-core/")
                    || path.contains("/viewer/")) {
                return false;
            }
        }
        return true;
    }
}
