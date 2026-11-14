package org.firstinspires.ftc.teamcode.calibration;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

import java.io.IOException;

import static org.firstinspires.ftc.teamcode.calibration.CalibrationConfig.*;

/**
 * Measures how the hub IMU's heading drifts and jitters while the robot stands perfectly still
 * for a minute (./gradlew calibrate then sets the simulated IMU to match). Put the robot down,
 * press START and don't touch it or the field until it says done.
 */
@TeleOp(name = "Calibrate IMU", group = "Calibration")
public class CalibrateImu extends LinearOpMode {
    public static double SECONDS = 60;

    @Override
    public void runOpMode() {
        IMU imu = hardwareMap.get(IMU.class, IMU);
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(IMU_LOGO, IMU_USB)));
        telemetry.addLine("CALIBRATE IMU: the robot must stand completely still for " + (int) SECONDS + " s.");
        telemetry.addLine("Press START, then step away from the robot and the field.");
        telemetry.update();
        waitForStart();
        if (isStopRequested()) return;
        sleep(1000); // let the robot stop rocking after START was pressed
        imu.resetYaw();
        ElapsedTime clock = new ElapsedTime();
        String result;
        CalibrationLog log = null;
        try {
            log = new CalibrationLog("imu");
            log.header("t", "t_cmd", "segment", "yaw_deg", "yaw_rate_dps");
            double yaw = 0, last = 0;
            boolean have = false;
            while (opModeIsActive() && clock.seconds() < SECONDS) {
                double t = clock.seconds();
                double reading = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
                double rate = imu.getRobotAngularVelocity(AngleUnit.DEGREES).zRotationRate;
                if (have) yaw += AngleUnit.normalizeDegrees(reading - last);
                else yaw = reading;
                last = reading;
                have = true;
                log.row(t, t, "still", yaw, rate);
                telemetry.addData("Time left", "%.0f s", SECONDS - t);
                telemetry.addData("Heading", "%.3f deg", yaw);
                telemetry.update();
                sleep(20);
            }
            result = "Done: " + log.rows() + " samples saved to " + log.path();
        } catch (IOException e) {
            result = "Couldn't write the log: " + e.getMessage();
        } finally {
            try {
                if (log != null) log.close();
            } catch (IOException ignored) {
            }
        }
        telemetry.addLine(result);
        telemetry.update();
        while (opModeIsActive()) idle();
    }
}
