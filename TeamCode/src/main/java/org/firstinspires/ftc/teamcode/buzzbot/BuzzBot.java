package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.NormalizedColorSensor;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.TouchSensor;

/** All of BuzzBot's hardware and subsystems. Call update() once per loop. */
public class BuzzBot {
    private Alliance alliance;
    public final Localizer localizer;
    public final Drive drive;
    public final Intake intake;
    public final Launcher launcher;
    public final Lift lift;
    /** The HIVE AprilTags through the Limelight (does nothing if the robot has none). */
    public final HiveTags tags;
    private final java.util.List<LynxModule> hubs;

    public BuzzBot(HardwareMap hw, Alliance alliance) {
        this.alliance = alliance;
        hubs = hw.getAll(LynxModule.class);
        for (LynxModule hub : hubs) hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);

        IMU imu = hw.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));

        DcMotorEx lf = hw.get(DcMotorEx.class, "leftFront");
        DcMotorEx rf = hw.get(DcMotorEx.class, "rightFront");
        DcMotorEx lb = hw.get(DcMotorEx.class, "leftBack");
        DcMotorEx rb = hw.get(DcMotorEx.class, "rightBack");
        // Dead wheels: parallel in rightFront's encoder port, perpendicular in rightBack's.
        localizer = new Localizer(rf, rb, imu);
        drive = new Drive(lf, rf, lb, rb, localizer);

        intake = new Intake(hw.get(DcMotorEx.class, "intake"), hw.get(CRServo.class, "transfer"),
                hw.get(NormalizedColorSensor.class, "hopperColor"), alliance);
        launcher = new Launcher(hw.get(DcMotorEx.class, "shooterLeft"), hw.get(DcMotorEx.class, "shooterRight"),
                hw.get(Servo.class, "kicker"));
        lift = new Lift(hw.get(DcMotorEx.class, "lift"), hw.get(Servo.class, "claw"), hw.get(TouchSensor.class, "liftDown"));
        tags = new HiveTags(hw);
    }

    /** Read sensors once (bulk read) and run every subsystem's control. */
    public void update() {
        for (LynxModule hub : hubs) hub.clearBulkCache();
        localizer.update();
        tags.update();
        intake.update();
        launcher.update();
        lift.update();
    }

    public Alliance alliance() {
        return alliance;
    }

    public void setAlliance(Alliance alliance) {
        this.alliance = alliance;
        intake.setAlliance(alliance);
    }

    public Pose pose() {
        return localizer.getPose();
    }

    /** Distance (in) from the launcher to a CELL opening, in field coordinates. */
    public double launcherDistanceTo(double[] opening) {
        Pose p = pose();
        double lx = p.x + Math.cos(p.heading), ly = p.y + Math.sin(p.heading);
        return Math.hypot(opening[0] - lx, opening[1] - ly);
    }
}
