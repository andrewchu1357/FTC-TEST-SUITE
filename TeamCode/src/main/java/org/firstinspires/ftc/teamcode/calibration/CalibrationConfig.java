package org.firstinspires.ftc.teamcode.calibration;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;

/**
 * What the calibration OpModes need to know about YOUR robot. Change these to match its
 * Driver Station configuration and drive code before running them. The defaults are BuzzBot's.
 */
public final class CalibrationConfig {
    private CalibrationConfig() {}

    // ---- Drivetrain (Calibrate Drive) ----

    /** Drive motor names in the robot configuration. */
    public static String LEFT_FRONT = "leftFront";
    public static String RIGHT_FRONT = "rightFront";
    public static String LEFT_BACK = "leftBack";
    public static String RIGHT_BACK = "rightBack";

    /** Set these like your drive code does, so that positive power drives each wheel forward. */
    public static boolean REVERSE_LEFT = true;
    public static boolean REVERSE_RIGHT = false;

    /**
     * Which drive motor ports read the drive wheel's own encoder (left front, right front, left
     * back, right back). Set false for a port whose encoder cable goes to a dead wheel instead
     * (BuzzBot's right side) or has no encoder. Used for the distance limits.
     */
    public static boolean[] WHEEL_ENCODERS = {true, false, true, false};

    /** Drive wheel diameter and encoder ticks per wheel turn (motor ticks x gear ratio). */
    public static double WHEEL_DIAMETER_IN = 104 / 25.4;
    public static double TICKS_PER_WHEEL_REV = 537.7;

    /** The hub IMU and how the hub is mounted. */
    public static String IMU = "imu";
    public static RevHubOrientationOnRobot.LogoFacingDirection IMU_LOGO = RevHubOrientationOnRobot.LogoFacingDirection.UP;
    public static RevHubOrientationOnRobot.UsbFacingDirection IMU_USB = RevHubOrientationOnRobot.UsbFacingDirection.FORWARD;

    /**
     * The heading sensor Calibrate Turn measures: "imu" (the hub's), "pinpoint" (a goBILDA
     * Pinpoint's own IMU) or "otos" (a SparkFun OTOS), with those devices' names.
     */
    public static String TURN_HEADING = "imu";
    public static String PINPOINT = "pinpoint";
    public static String OTOS = "sensor_otos";

    /**
     * How far the robot may drive from where it starts, in inches. It needs this much clear
     * space in front of it and to its left (it drives forward, back, left and right, and spins).
     */
    public static double MAX_TRAVEL_IN = 36;

    // ---- One mechanism (Calibrate Mechanism) ----

    /** The motors that drive the mechanism, and which ones your code reverses. */
    public static String[] MECHANISM_MOTORS = {"shooterLeft", "shooterRight"};
    public static boolean[] MECHANISM_REVERSED = {false, true};

    /**
     * true: something that spins freely (flywheel, intake). false: something that travels between
     * limits (lift, arm) - it is driven up to MECHANISM_TRAVEL_TICKS and back down, so make sure
     * that's below the top and positive power moves it up.
     */
    public static boolean MECHANISM_SPINS = true;
    public static int MECHANISM_TRAVEL_TICKS = 1800;
}
