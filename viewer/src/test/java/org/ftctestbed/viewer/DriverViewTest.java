package org.ftctestbed.viewer;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The driver's view puts the driver's alliance wall at the bottom; the controller's deadzone. */
class DriverViewTest {
    private static FieldRenderer renderer(double rotation) {
        FieldRenderer r = new FieldRenderer(FieldConfig.load(Path.of("config/field.jsonc")), RobotConfig.load(Path.of("config/robot.jsonc")));
        r.setViewRotation(rotation);
        r.layout(800, 800, 0);
        return r;
    }

    @Test
    void redDriversSeePlusXUpTheScreen() {
        FieldRenderer r = renderer(FieldRenderer.driverView(0)); // red drivers look along +x
        Point2D.Double center = r.toField(400, 400);
        Point2D.Double above = r.toField(400, 200), right = r.toField(600, 400);
        assertTrue(above.x - center.x > 0.5 && Math.abs(above.y - center.y) < 0.05, "up the screen is +x (away from red): " + above);
        assertTrue(right.y - center.y < -0.5 && Math.abs(right.x - center.x) < 0.05, "screen right is -y (red driver's right): " + right);
    }

    @Test
    void blueDriversSeeMinusXUpTheScreen() {
        FieldRenderer r = renderer(FieldRenderer.driverView(Math.PI));
        Point2D.Double center = r.toField(400, 400), above = r.toField(400, 200);
        assertTrue(above.x - center.x < -0.5, "up the screen is -x (away from blue): " + above);
    }

    @Test
    void theDeadzoneDoesNotJump() {
        float dz = PhysicalGamepads.deadzone(com.qualcomm.robotcore.hardware.Gamepad.Type.SONY_PS4);
        assertEquals(0f, PhysicalGamepads.dead(dz * 0.9f, dz));
        assertEquals(0.01f, PhysicalGamepads.dead(dz + 0.01f * (1 - dz), dz), 1e-4, "just past the deadzone: just above 0");
        assertEquals(-1f, PhysicalGamepads.dead(-1f, dz), 1e-6);
        assertEquals(0.5f, PhysicalGamepads.dead(dz + 0.5f * (1 - dz), dz), 1e-4);
    }
}
