package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Test-shoots BuzzBot at the red HIVE's upward CELL from many distances and flywheel speeds,
 * like a team tuning its shooter on a practice field. Prints which speeds score.
 * Run: ./gradlew :TeamCode:test --tests '*LauncherSweep*' -Dsweep=true
 */
public final class LauncherSweep {
    static final double IN = 0.0254;

    /** Returns "IN" if the shot entered the CELL, else where it ended up. */
    static String shoot(RobotConfig robot, FieldConfig field, double distanceIn, double approachDeg, double tps) {
        Simulation sim = new Simulation(robot, field);
        double tx = -BioBuzzField.HIVE_X, ty = -BioBuzzField.MOUTH_OFFSET_Y;
        double a = Math.toRadians(approachDeg);
        // Place the launcher exit (1 in ahead of center) distanceIn from the opening, facing it.
        double lx = tx + Math.cos(a) * distanceIn * IN, ly = ty + Math.sin(a) * distanceIn * IN;
        double heading = a + Math.PI;
        sim.moveRobot(lx - Math.cos(heading) * IN, ly - Math.sin(heading) * IN, heading);
        DcMotorEx left = sim.hardwareMap.get(DcMotorEx.class, "shooterLeft");
        DcMotorEx right = sim.hardwareMap.get(DcMotorEx.class, "shooterRight");
        right.setDirection(DcMotor.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[]{left, right}) {
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            m.setVelocity(tps);
        }
        Servo kicker = sim.hardwareMap.get(Servo.class, "kicker");
        kicker.setPosition(0.2);
        for (int i = 0; i < 2500; i++) sim.step();
        kicker.setPosition(0.8);
        GamePiece shot = null;
        for (int i = 0; i < 3000; i++) {
            sim.step();
            if (shot == null) {
                shot = sim.pieces().all().stream().filter(p -> p.state() == GamePiece.State.AIRBORNE).findFirst().orElse(null);
            } else if (shot.state() != GamePiece.State.AIRBORNE) {
                break;
            }
        }
        if (shot == null) return "no shot";
        if (shot.state() == GamePiece.State.CONTAINED) return "IN";
        return shot.state().name();
    }

    public static void main(String[] args) {
        RobotConfig robot = RobotConfig.load(Path.of("config/robot.jsonc"));
        FieldConfig field = FieldConfig.load(Path.of("config/field.jsonc"));
        // Red's audience-side CELL opening faces the audience (-y). -90 = straight in front of it.
        for (double approach : new double[]{-90, -120, -150}) {
            System.out.printf(Locale.US, "%nRobot at %.0f deg from the opening (-90 = straight in front)%n", approach);
            for (int d = 30; d <= 57; d += 3) {
                StringBuilder sb = new StringBuilder();
                int first = -1, last = -1;
                for (int tps = 700; tps <= 1700; tps += 10) {
                    boolean in = shoot(robot, field, d, approach, tps).equals("IN");
                    sb.append(in ? '#' : '.');
                    if (in && first < 0) first = tps;
                    if (in) last = tps;
                }
                System.out.printf(Locale.US, "%4d in  %s  %s%n", d, sb, first < 0 ? "no hits" : first + "-" + last + " tps");
            }
        }
    }
}
