package org.ftctestbed.viewer;

import com.qualcomm.robotcore.hardware.Gamepad;
import org.ftctestbed.shim.SimHooks;
import org.ftctestbed.sim.SimHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GamepadTest {
    @Test
    void controllerNamesMapToSdkTypes() {
        assertEquals(Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL, PhysicalGamepads.typeFor("DualSense Wireless Controller"));
        assertEquals(Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL, PhysicalGamepads.typeFor("PS5 Controller"));
        assertEquals(Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL, PhysicalGamepads.typeFor("PS4 Controller"));
        assertEquals(Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL, PhysicalGamepads.typeFor("Wireless Controller")); // DualShock 4 over Bluetooth
        assertEquals(Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL, PhysicalGamepads.typeFor("Sony Interactive Entertainment Wireless Controller"));
        assertEquals(Gamepad.Type.XBOX_360, PhysicalGamepads.typeFor("Xbox Wireless Controller"));
        assertEquals(Gamepad.Type.LOGITECH_F310, PhysicalGamepads.typeFor("Logitech Gamepad F310"));
        assertEquals(Gamepad.Type.UNKNOWN, PhysicalGamepads.typeFor("8BitDo Pro 2"));
    }

    @Test
    void rumbleAndLedEffectsFollowSimulatedTime() {
        long[] nanos = {0};
        SimHooks.install(() -> nanos[0], null, null); // a clock the test steps by hand
        Gamepad g = new Gamepad();
        assertFalse(g.isRumbling());
        g.runRumbleEffect(new Gamepad.RumbleEffect.Builder()
                .addStep(1.0, 0.0, 200).addStep(0.0, 0.5, 200).build());
        nanos[0] += 100_000_000L;
        assertArrayEquals(new double[]{1.0, 0.0}, g.simRumble(), 0.01);
        nanos[0] += 200_000_000L;
        assertArrayEquals(new double[]{0.0, 0.5}, g.simRumble(), 0.01);
        nanos[0] += 200_000_000L;
        assertFalse(g.isRumbling());

        g.rumble(0.3, 0.3, Gamepad.RUMBLE_DURATION_CONTINUOUS);
        nanos[0] += 5_000_000_000L;
        assertTrue(g.isRumbling());
        g.stopRumble();
        assertFalse(g.isRumbling());

        g.rumbleBlips(2); // 2 × (75 ms on, 100 ms off)
        nanos[0] += 50_000_000L;
        assertTrue(g.isRumbling());
        nanos[0] += 50_000_000L;
        assertFalse(g.isRumbling());
        nanos[0] += 100_000_000L;
        assertTrue(g.isRumbling());

        g.runLedEffect(new Gamepad.LedEffect.Builder()
                .addStep(1, 0, 0, 100).addStep(0, 1, 0, 100).setRepeating(true).build());
        nanos[0] += 150_000_000L;
        assertArrayEquals(new double[]{0, 1, 0}, g.simGetLedColor(), 0.01);
        nanos[0] += 100_000_000L; // 250 ms into a 200 ms loop → first step again
        assertArrayEquals(new double[]{1, 0, 0}, g.simGetLedColor(), 0.01);
    }

    @Test
    void buzzBotTeleOpSetsAllianceLed() {
        try (SimHarness sim = SimHarness.load()) {
            sim.startAt("Red Wall").init("BuzzBot TeleOp").runFor(0.2);
            sim.gamepad1(g -> g.x = true).runFor(0.2);
            assertArrayEquals(new double[]{0, 0, 1}, sim.gamepad1().simGetLedColor(), 0.01);
            sim.gamepad1(g -> { g.x = false; g.b = true; }).runFor(0.2);
            assertArrayEquals(new double[]{1, 0, 0}, sim.gamepad1().simGetLedColor(), 0.01);
        }
    }
}
