package org.ftctestbed.sim;

import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.PartsCatalog;
import org.ftctestbed.sim.config.RobotConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartsCatalogTest {
    private static final double IN = 0.0254;

    static String robot(String extra) {
        return """
                {
                  "drivetrain": {
                    "wheel": "goBILDA-96mm-mecanum",
                    "motors": {
                      "frontLeft":  { "name": "lf" }, "frontRight": { "name": "rf" },
                      "backLeft":   { "name": "lb" }, "backRight":  { "name": "rb" }
                    }
                  },
                  "motors": [ { "name": "lift", "type": "goBILDA-5203-435" } ],
                  "mechanisms": [ { "name": "lift", "type": "slide", "motors": ["lift"], "kit": "goBILDA-Viper-4stage-336mm" } ],
                  "servos": [ { "name": "claw", "type": "goBILDA-2000-25-3" },
                              { "name": "wrist", "type": "goBILDA-2000-25-3", "rangeDeg": 180 } ],
                  "crServos": [ { "name": "spinner", "type": "goBILDA-2000-25-2" } ],
                  "odometryPods": [ { "name": "par", "type": "goBILDA-4-Bar-Odometry-Pod" } ],
                  "distanceSensors": [ { "name": "front", "type": "REV-2m-Distance-Sensor" } ]
                  %s
                }
                """.formatted(extra);
    }

    @Test
    void builtInCatalogHasEveryCategory() {
        PartsCatalog c = PartsCatalog.builtIn();
        for (String cat : PartsCatalog.CATEGORIES) assertFalse(c.all(cat).isEmpty(), cat);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        for (PartsCatalog.Part p : c.all()) assertFalse(PartsCatalog.summary(p).isBlank(), p.id());
    }

    @Test
    void robotConfigTakesValuesFromParts() {
        RobotConfig r = RobotConfig.parse(robot(""), "robot");
        assertEquals(0.048, r.drivetrain().wheelRadius(), 1e-6);
        RobotConfig.Mechanism lift = r.mechanisms().get(0);
        assertEquals(0.976, lift.travel(), 1e-6);
        assertEquals(120.0, lift.spoolRadius() * 2 * Math.PI * 1000, 0.2); // 120 mm per spool turn
        RobotConfig.Servo claw = r.servos().get(0), wrist = r.servos().get(1), spinner = r.servos().get(2);
        assertEquals(Math.toRadians(300), claw.rangeRad(), 1e-9);
        // 25-3: 0.11 s/60° at 4.8 V, 0.09 at 6 V → interpolated for the hub's 5 V
        assertEquals(0.11 - 0.02 / 6, claw.secPer60Deg(), 1e-6);
        assertEquals(Math.toRadians(180), wrist.rangeRad(), 1e-9, "robot.jsonc settings override the part");
        assertEquals(60.0 / (6 * (0.25 - 0.05 / 6)), spinner.crMaxRpm(), 1e-6);
        assertEquals(0.016, r.odometryPods().get(0).wheelRadius(), 1e-9);
        assertEquals(2.0, r.distanceSensors().get(0).maxRange(), 1e-9);
        assertTrue(r.partsUsed().contains(new RobotConfig.PartUse("claw", "servos", "goBILDA-2000-25-3")));
        assertTrue(r.partsUsed().contains(new RobotConfig.PartUse("lift", "slides", "goBILDA-Viper-4stage-336mm")));
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
    }

    @Test
    void servoVoltsChangesServoSpeed() {
        RobotConfig r = RobotConfig.parse(robot(", \"servoVolts\": 6.0"), "robot");
        assertEquals(0.09, r.servos().get(0).secPer60Deg(), 1e-9);
    }

    @Test
    void unknownPartsListTheKnownOnes() {
        ConfigException e = assertThrows(ConfigException.class, () -> RobotConfig.parse(
                robot("").replace("goBILDA-2000-25-3\" },", "goBILDA-9999\" },"), "robot"));
        assertTrue(e.getMessage().contains("unknown servo type \"goBILDA-9999\""), e.getMessage());
        assertTrue(e.getMessage().contains("goBILDA-2000-25-2"), e.getMessage());
    }

    @Test
    void teamPartsFilesAddAndOverrideParts(@TempDir Path dir) throws Exception {
        Path parts = Files.createDirectories(dir.resolve("config/parts"));
        Files.writeString(parts.resolve("ours.jsonc"), """
                // our custom parts
                {
                  "servos": [
                    { "id": "Team-Fast-Servo", "name": "Our fast servo", "rangeDeg": 270, "secPer60Deg6v": 0.06 },
                    { "id": "goBILDA-2000-25-3", "name": "25-3 (measured)", "rangeDeg": 300, "secPer60Deg": 0.12 }
                  ],
                  "wheels": [ { "id": "Team-Omni", "type": "omni", "diameterMm": 75, "massG": 120 } ]
                }
                """);
        Path robotFile = dir.resolve("config/robot.jsonc");
        Files.writeString(robotFile, robot("").replace("\"name\": \"wrist\", \"type\": \"goBILDA-2000-25-3\"",
                "\"name\": \"wrist\", \"type\": \"Team-Fast-Servo\""));
        RobotConfig r = RobotConfig.load(robotFile);
        assertEquals(0.12, r.servos().get(0).secPer60Deg(), 1e-9, "team file overrides the built-in 25-3");
        assertEquals(0.06 * 6 / 5, r.servos().get(1).secPer60Deg(), 1e-9, "6 V spec scaled to 5 V");
        PartsCatalog.Part omni = r.catalog().find("wheels", "team-omni");
        assertEquals("ours.jsonc", omni.source());
        assertEquals(List.of(parts), r.catalog().folders());
    }
}
