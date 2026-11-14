package org.firstinspires.ftc.teamcode.calibration;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import com.qualcomm.robotcore.util.ElapsedTime;

import java.io.IOException;
import java.util.List;

import static org.firstinspires.ftc.teamcode.calibration.CalibrationConfig.*;

/**
 * Runs one mechanism (CalibrationConfig.MECHANISM_MOTORS) through a fixed test and logs it, so
 * the simulator can be fitted to it (./gradlew calibrate). A spinning mechanism (flywheel,
 * intake) spins up at full power, coasts down, spins up at half power and brakes. A lift or arm
 * goes up to MECHANISM_TRAVEL_TICKS, holds, comes back down, and repeats at full power. About
 * 15 seconds; STOP ends it at any time.
 */
@TeleOp(name = "Calibrate Mechanism", group = "Calibration")
public class CalibrateMechanism extends LinearOpMode {
    private DcMotorEx[] motors;
    private int[] pos;
    private double[] vel;
    private double power;
    private boolean brake = true, lastBrake = false;
    private String segment = "";
    private final ElapsedTime clock = new ElapsedTime();
    private VoltageSensor battery;
    private List<LynxModule> hubs;
    private CalibrationLog log;

    @Override
    public void runOpMode() {
        int n = MECHANISM_MOTORS.length;
        motors = new DcMotorEx[n];
        pos = new int[n];
        vel = new double[n];
        StringBuilder names = new StringBuilder(), rev = new StringBuilder();
        for (int i = 0; i < n; i++) {
            motors[i] = hardwareMap.get(DcMotorEx.class, MECHANISM_MOTORS[i]);
            motors[i].setDirection(MECHANISM_REVERSED[i] ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
            motors[i].setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            motors[i].setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            if (i > 0) { names.append(';'); rev.append(';'); }
            names.append(MECHANISM_MOTORS[i]);
            rev.append(MECHANISM_REVERSED[i]);
        }
        battery = hardwareMap.voltageSensor.iterator().next();
        hubs = hardwareMap.getAll(LynxModule.class);
        for (LynxModule hub : hubs) hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);

        telemetry.addLine("CALIBRATE MECHANISM: " + names);
        telemetry.addLine(MECHANISM_SPINS ? "It spins up to full speed, coasts, and spins up again."
                : "It goes up to " + MECHANISM_TRAVEL_TICKS + " ticks and back down, twice. Start it at the bottom.");
        telemetry.addLine("Press START when ready; STOP ends it at any time.");
        telemetry.update();
        waitForStart();
        if (isStopRequested()) return;

        String result;
        try {
            log = new CalibrationLog("mechanism");
            log.info("motors", names);
            log.info("reversed", rev);
            log.info("spins", MECHANISM_SPINS);
            String[] full = new String[5 + 2 * n + 1];
            full[0] = "t"; full[1] = "t_cmd"; full[2] = "segment"; full[3] = "power"; full[4] = "zpb";
            for (int i = 0; i < n; i++) { full[5 + i] = "pos_" + i; full[5 + n + i] = "vel_" + i; }
            full[5 + 2 * n] = "volts";
            log.header(full);
            clock.reset();
            if (MECHANISM_SPINS) {
                phase("still", 0, true, 1.0);
                phase("spinup", 1.0, true, 2.5);
                phase("coast", 0, false, 3.0);
                phase("spinup", 0.5, true, 2.5);
                phase("brake", 0, true, 2.0);
            } else {
                phase("still", 0, true, 0.5);
                travel("up", 0.6, 2.5);
                phase("hold", 0, true, 1.5);
                travel("down", -0.4, 2.5);
                phase("hold", 0, true, 1.0);
                travel("up", 1.0, 2.0);
                phase("hold", 0, true, 1.5);
                travel("down", -0.4, 2.5);
                phase("still", 0, true, 1.0);
            }
            result = "Done: " + log.rows() + " samples saved to " + log.path();
        } catch (IOException e) {
            result = "Couldn't write the log: " + e.getMessage();
        } finally {
            for (DcMotorEx m : motors) m.setPower(0);
            try {
                if (log != null) log.close();
            } catch (IOException ignored) {
            }
        }
        telemetry.addLine(result);
        telemetry.update();
        while (opModeIsActive()) idle();
    }

    private boolean tick() throws IOException {
        for (LynxModule hub : hubs) hub.clearBulkCache();
        int n = motors.length;
        Object[] row = new Object[5 + 2 * n + 1];
        row[0] = clock.seconds();
        row[2] = segment;
        row[3] = power;
        row[4] = brake ? "B" : "F";
        for (int i = 0; i < n; i++) {
            pos[i] = motors[i].getCurrentPosition();
            vel[i] = motors[i].getVelocity();
            row[5 + i] = pos[i];
            row[5 + n + i] = vel[i];
        }
        row[5 + 2 * n] = battery.getVoltage();
        if (brake != lastBrake) {
            for (DcMotorEx m : motors) m.setZeroPowerBehavior(brake ? DcMotor.ZeroPowerBehavior.BRAKE : DcMotor.ZeroPowerBehavior.FLOAT);
            lastBrake = brake;
        }
        for (DcMotorEx m : motors) m.setPower(power);
        row[1] = clock.seconds(); // when the new power had reached the hub
        log.row(row);
        return opModeIsActive();
    }

    private void phase(String seg, double p, boolean brakeMode, double seconds) throws IOException {
        segment = seg;
        power = p;
        brake = brakeMode;
        double end = clock.seconds() + seconds;
        while (clock.seconds() < end && tick()) { }
    }

    /** Up (p > 0) to MECHANISM_TRAVEL_TICKS, or down (p < 0) to near the bottom. */
    private void travel(String seg, double p, double maxSeconds) throws IOException {
        segment = seg;
        power = p;
        brake = true;
        double end = clock.seconds() + maxSeconds;
        while (tick()) {
            if (clock.seconds() > end) break;
            if (p > 0 && pos[0] >= MECHANISM_TRAVEL_TICKS) break;
            if (p < 0 && pos[0] <= 0.05 * MECHANISM_TRAVEL_TICKS) break;
        }
        power = 0;
    }
}
