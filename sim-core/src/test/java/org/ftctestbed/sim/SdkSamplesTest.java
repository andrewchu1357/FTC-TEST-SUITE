package org.ftctestbed.sim;

import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIRST's own SDK 12.0 sample OpModes (src/test/resources/sdk-samples-12.0) compile against the
 * simulator's SDK stand-in, the way a team's copy of one would. A sample that uses an SDK class
 * or method the stand-in lacks fails here.
 */
class SdkSamplesTest {
    /** Samples that don't compile yet, and why. Each must still fail, so this list stays true. */
    static final Map<String, String> NOT_YET = new TreeMap<>(Map.ofEntries(
            Map.entry("SensorHuskyLens", "DFRobot HuskyLens camera"),
            Map.entry("SensorMRGyro", "Modern Robotics gyro (legacy hardware)"),
            Map.entry("SensorMRRangeSensor", "Modern Robotics range sensor (legacy hardware)"),
            Map.entry("SensorKLNavxMicro", "Kauai Labs navX-Micro (legacy hardware)")));

    static Path samples() throws Exception {
        return Path.of(SdkSamplesTest.class.getResource("/sdk-samples-12.0/README.md").toURI()).getParent();
    }

    /** Compiles these files together; returns the first errors, or null if they compiled. */
    static String compile(JavaCompiler javac, StandardJavaFileManager files, List<File> sources, Path out) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("--release", "8", "-proc:none", "-implicit:none", "-nowarn", "-Xlint:none",
                "-classpath", System.getProperty("java.class.path"), "-d", out.toString());
        boolean ok = javac.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromFiles(sources)).call();
        if (ok) return null;
        StringBuilder sb = new StringBuilder();
        diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR).limit(3)
                .forEach(d -> sb.append("\n    line ").append(d.getLineNumber()).append(": ").append(d.getMessage(null)));
        return sb.toString();
    }

    @Test
    void theSdk12SamplesCompileInTheSimulator() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Path dir = samples();
        Path out = Files.createTempDirectory("sdk-samples");
        List<String> failed = new ArrayList<>(), compiledButListed = new ArrayList<>();
        int compiled = 0;
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, null, null);
             Stream<Path> list = Files.list(dir)) {
            List<Path> sources = list.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            assertTrue(sources.size() > 60, "the samples are there: " + sources.size());
            for (Path p : sources) {
                String name = p.getFileName().toString().replace(".java", "");
                String errors = compile(javac, files, List.of(p.toFile()), out);
                if (errors == null) compiled++;
                if (NOT_YET.containsKey(name)) {
                    if (errors == null) compiledButListed.add(name);
                } else if (errors != null) {
                    failed.add(name + errors);
                }
            }
            // The external hardware class sample is two files that go together.
            try (Stream<Path> hw = Files.list(dir.resolve("externalhardware"))) {
                String errors = compile(javac, files, hw.map(Path::toFile).toList(), out);
                if (errors != null) failed.add("externalhardware" + errors);
            }
        }
        System.out.println("SDK 12.0 samples compiled: " + compiled + " (+ externalhardware), not yet: " + NOT_YET.size());
        assertTrue(failed.isEmpty(), "samples that don't compile against the simulator's SDK:\n  " + String.join("\n  ", failed));
        assertTrue(compiledButListed.isEmpty(), "these compile now; take them off NOT_YET: " + compiledButListed);
    }
}
