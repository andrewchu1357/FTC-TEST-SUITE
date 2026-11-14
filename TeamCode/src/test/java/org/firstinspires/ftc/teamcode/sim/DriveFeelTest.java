package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.teamcode.buzzbot.DriverAids;
import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BuzzBot (312 rpm goBILDA motors, 104 mm mecanum wheels, 32 lb) drives like a real robot of
 * that build: balanced motors (straight when told to go straight), real top speeds and
 * acceleration, strafing slower than driving, and a power it needs before it moves at all.
 */
class DriveFeelTest {
    /** Each run: {forward, left, turn} powers on every wheel, held. */
    public static class Hold extends LinearOpMode {
        static volatile double fwd, left, turn;

        @Override
        public void runOpMode() {
            DcMotorEx[] m = {hardwareMap.get(DcMotorEx.class, "leftFront"), hardwareMap.get(DcMotorEx.class, "rightFront"),
                    hardwareMap.get(DcMotorEx.class, "leftBack"), hardwareMap.get(DcMotorEx.class, "rightBack")};
            m[0].setDirection(DcMotorSimple.Direction.REVERSE);
            m[2].setDirection(DcMotorSimple.Direction.REVERSE);
            waitForStart();
            while (opModeIsActive()) {
                m[0].setPower(fwd - left - turn);
                m[1].setPower(fwd + left + turn);
                m[2].setPower(fwd + left - turn);
                m[3].setPower(fwd - left + turn);
            }
        }
    }

    /** {speed in/s every 0.1 s for 1.5 s..., forward in, left in, heading change deg, turn rate deg/s at the end}. */
    record Run(double[] speed, double forward, double left, double heading, double turnRate) {
        double top() { return speed[speed.length - 1]; }

        double timeTo(double fraction) {
            for (int i = 0; i < speed.length; i++) if (speed[i] >= fraction * top()) return 0.1 * (i + 1);
            return Double.NaN;
        }
    }

    static Run drive(double f, double l, double t) {
        Hold.fwd = f;
        Hold.left = l;
        Hold.turn = t;
        try (SimHarness h = SimHarness.of(RobotConfig.load(Path.of("config/robot.jsonc")),
                FieldConfig.parse("{ \"robotStart\": { \"xIn\": -45, \"yIn\": -55, \"headingDeg\": 0 } }", "f"),
                new OpModeRegistry.Result(List.of(new OpModeInfo("Hold", "", false, null, Hold.class)), List.of()))) {
            h.init("Hold").runFor(0.5).start();
            double x0 = h.xInches(), y0 = h.yInches(), h0 = h.headingDegrees();
            double[] speed = new double[15];
            for (int i = 0; i < speed.length; i++) {
                h.runFor(0.1);
                speed[i] = h.sim.robotSpeed() / 0.0254;
            }
            assertNull(h.error(), h.error());
            return new Run(speed, h.xInches() - x0, h.yInches() - y0, h.headingDegrees() - h0, Math.toDegrees(h.sim.robotOmega()));
        }
    }

    @Test
    void fullPowerForwardIsStraightAndRealisticallyFast() {
        Run r = drive(1, 0, 0);
        assertTrue(r.top() > 55 && r.top() < 70, "top speed " + r.top() + " in/s (free speed at 12.8 V: 71)");
        assertTrue(r.timeTo(0.9) >= 0.3 && r.timeTo(0.9) <= 0.8, "90% of top speed after " + r.timeTo(0.9) + " s");
        assertTrue(Math.abs(r.left()) < 2 && Math.abs(r.heading()) < 2,
                "balanced: drifted " + r.left() + " in and turned " + r.heading() + " deg in " + r.forward() + " in");
    }

    @Test
    void strafingIsSlowerThanDrivingAndStraight() {
        double forward = drive(1, 0, 0).top();
        Run r = drive(0, 1, 0);
        double ratio = r.top() / forward;
        assertTrue(ratio > 0.7 && ratio < 0.9, "strafe speed " + ratio + " of forward (mecanum: about 0.75-0.85)");
        assertTrue(Math.abs(r.forward()) < 2 && Math.abs(r.heading()) < 2, "strafed straight: " + r);
    }

    @Test
    void turnsAtARealisticRate() {
        Run r = drive(0, 0, 1);
        assertTrue(r.turnRate() > 200 && r.turnRate() < 300, "full-power turn " + r.turnRate() + " deg/s");
        assertTrue(Math.abs(r.forward()) < 1 && Math.abs(r.left()) < 1, "turned in place");
    }

    @Test
    void itNeedsSomePowerBeforeItMoves() {
        assertTrue(drive(0.03, 0, 0).top() < 1, "3% power barely moves it (friction holds it)");
        Run tenth = drive(0.1, 0, 0), fifth = drive(0.2, 0, 0);
        assertTrue(tenth.top() > 0.5 && tenth.top() < 6, "10% power: " + tenth.top() + " in/s");
        assertTrue(fifth.top() > 2 * tenth.top(), "speed rises faster than power past the friction");
    }

    @Test
    void driverAidsGiveFineControlAndFullSpeed() {
        DriverAids aids = new DriverAids();
        double[] c = null;
        for (int i = 0; i < 50; i++) c = aids.update(1, 0, 1, 0.02);
        assertEquals(1, c[0], 1e-9, "full stick is still full speed");
        assertEquals(DriverAids.MAX_TURN, c[2], 1e-9, "turning capped");
        aids.reset();
        double first = aids.update(1, 0, 0, 0.02)[0];
        assertTrue(first <= DriverAids.ACCEL * 0.02 + 1e-9, "speeding up is smooth: " + first);
        for (int i = 0; i < 50; i++) aids.update(1, 0, 0, 0.02);
        assertEquals(0, aids.update(0, 0, 0, 0.02)[0], 1e-9, "stopping is instant");
        assertTrue(DriverAids.shape(0.2) < 0.2 * 0.5, "a small stick is a smaller power: fine control");
    }
}
