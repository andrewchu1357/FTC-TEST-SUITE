package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.viewer.model.ModelConfig;
import org.ftctestbed.viewer.model.ModelData;
import org.ftctestbed.viewer.model.ModelLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The example robot package: its own code, config and CAD model work together. */
class ExampleBotTest {
    static final Path ROBOT = Path.of("robots/ExampleBot/robot.jsonc");

    @Test
    void ownCodeDrivesAndLifts() {
        try (SimHarness sim = SimHarness.load(ROBOT, Path.of("config/field.jsonc"))) {
            assertEquals(List.of("ExampleBot Auto", "ExampleBot TeleOp"),
                    sim.availableOpModes().stream().map(i -> i.name()).sorted().toList(), "only the package's OpModes");
            sim.startAt("Red Wall");
            double x0 = sim.xInches();
            sim.init("ExampleBot Auto").start();
            double maxLift = 0;
            for (int i = 0; i < 80; i++) {
                sim.runFor(0.1);
                for (Snapshot.Mechanism m : sim.sim.snapshot().mechanisms()) if (m.name().equals("lift")) maxLift = Math.max(maxLift, m.position());
            }
            sim.runUntilStopped(5);
            assertNull(sim.error());
            assertEquals(24, sim.xInches() - x0, 1.5, "drove 24 in with encoders");
            assertTrue(maxLift > 17, "lift went up: " + maxLift);
            assertTrue(sim.telemetry().stream().anyMatch(l -> l.startsWith("Done")), sim.telemetry().toString());
        }
    }

    @Test
    void cadModelLinesUpAndEveryBindingResolves() throws Exception {
        RobotConfig robot = RobotConfig.load(ROBOT);
        ModelConfig cfg = ModelConfig.load(ModelConfig.fileFor(ROBOT));
        ModelData model = ModelLoader.load(ROBOT.resolveSibling(cfg.file));
        assertEquals(List.of(), cfg.problems(model, robot));
        double[] b = ModelData.bounds(model.root, cfg.alignment(model));
        assertEquals(0, b[2], 1e-6, "sits on the floor");
        assertEquals(0, (b[0] + b[3]) / 2, 1e-6);
        assertEquals(17.1, (b[3] - b[0]) / 0.0254, 0.3, "18 in robot, front along +x");
        assertTrue((b[5] - b[2]) / 0.0254 > 14, "upright (z up)");
    }
}
