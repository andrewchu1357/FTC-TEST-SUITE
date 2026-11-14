package com.qualcomm.hardware.limelightvision;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.ftctestbed.shim.SimHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One frame's results from a Limelight, as the SDK reads them from its JSON. The simulator builds
 * the same JSON fields (as a map): tx/ty in degrees from the crosshair, ta in percent of the
 * picture, poses as {x, y, z, roll, pitch, yaw} in meters and degrees.
 */
public class LLResult {
    private final Map<String, Object> jsonData;
    private final List<LLResultTypes.BarcodeResult> barcodeResults = new ArrayList<LLResultTypes.BarcodeResult>();
    private final List<LLResultTypes.ClassifierResult> classifierResults = new ArrayList<LLResultTypes.ClassifierResult>();
    private final List<LLResultTypes.DetectorResult> detectorResults = new ArrayList<LLResultTypes.DetectorResult>();
    private final List<LLResultTypes.FiducialResult> fiducialResults = new ArrayList<LLResultTypes.FiducialResult>();
    private final List<LLResultTypes.ColorResult> colorResults = new ArrayList<LLResultTypes.ColorResult>();
    private double parseLatency;
    private long controlHubTimeStamp;
    private long controlHubTimeStampNanos;

    @SuppressWarnings("unchecked")
    protected LLResult(Map<String, Object> json) {
        this.jsonData = json;
        for (Map<String, Object> m : list("Fiducial")) fiducialResults.add(new LLResultTypes.FiducialResult(m));
        for (Map<String, Object> m : list("Retro")) colorResults.add(new LLResultTypes.ColorResult(m));
        for (Map<String, Object> m : list("Detector")) detectorResults.add(new LLResultTypes.DetectorResult(m));
        for (Map<String, Object> m : list("Classifier")) classifierResults.add(new LLResultTypes.ClassifierResult(m));
        for (Map<String, Object> m : list("Barcode")) barcodeResults.add(new LLResultTypes.BarcodeResult(m));
    }

    /** Simulator: a result built from the Limelight's JSON fields, received by the Control Hub now. */
    public static LLResult simCreate(Map<String, Object> json, double parseLatencyMs) {
        LLResult r = new LLResult(json);
        r.parseLatency = parseLatencyMs;
        r.controlHubTimeStampNanos = SimHooks.nanoTime();
        r.controlHubTimeStamp = SimHooks.clockEpochMillis() + r.controlHubTimeStampNanos / 1_000_000L;
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String key) {
        Object v = jsonData.get(key);
        return v instanceof List ? (List<Map<String, Object>>) v : Collections.<Map<String, Object>>emptyList();
    }

    private double num(String key) {
        return LLResultTypes.num(jsonData, key);
    }

    private double[] getDoubleArray(String key, int length) {
        Object v = jsonData.get(key);
        if (v instanceof double[] && ((double[]) v).length >= length) return (double[]) v;
        return new double[0];
    }

    /** A pose from {x, y, z, roll, pitch, yaw} (meters, degrees); all zero if there isn't one. */
    protected static Pose3D createPose3DRobot(double[] pose) {
        if (pose.length < 6) {
            return new Pose3D(new Position(), new YawPitchRollAngles(AngleUnit.DEGREES, 0, 0, 0, 0));
        }
        return new Pose3D(new Position(DistanceUnit.METER, pose[0], pose[1], pose[2], 0),
                new YawPitchRollAngles(AngleUnit.DEGREES, pose[5], pose[4], pose[3], 0));
    }

    /** System.currentTimeMillis() when the Control Hub received this result (simulated clock). */
    public long getControlHubTimeStamp() { return controlHubTimeStamp; }

    public long getControlHubTimeStampNanos() { return controlHubTimeStampNanos; }

    /** How old this result is (ms) since the Control Hub received it. */
    public long getStaleness() {
        return SimHooks.clockEpochMillis() + SimHooks.nanoTime() / 1_000_000L - controlHubTimeStamp;
    }

    public List<LLResultTypes.BarcodeResult> getBarcodeResults() { return barcodeResults; }
    public List<LLResultTypes.ClassifierResult> getClassifierResults() { return classifierResults; }
    public List<LLResultTypes.DetectorResult> getDetectorResults() { return detectorResults; }
    public List<LLResultTypes.FiducialResult> getFiducialResults() { return fiducialResults; }
    public List<LLResultTypes.ColorResult> getColorResults() { return colorResults; }

    public double getFocusMetric() { return num("focus_metric"); }

    /** The robot's pose on the field from the AprilTags in view (MegaTag 1), meters and degrees. */
    public Pose3D getBotpose() { return createPose3DRobot(getDoubleArray("botpose", 6)); }

    /** The robot's pose from MegaTag 2, which takes its heading from updateRobotOrientation(). */
    public Pose3D getBotpose_MT2() { return createPose3DRobot(getDoubleArray("botpose_orb", 6)); }

    public double[] getStddevMt1() { return getDoubleArray("stdev_mt1", 6); }
    public double[] getStddevMt2() { return getDoubleArray("stdev_mt2", 6); }
    public int getBotposeTagCount() { return (int) num("botpose_tagcount"); }
    public double getBotposeSpan() { return num("botpose_span"); }
    public double getBotposeAvgDist() { return num("botpose_avgdist"); }
    public double getBotposeAvgArea() { return num("botpose_avgarea"); }
    public double[] getPythonOutput() { return getDoubleArray("PythonOut", 0); }
    /** Milliseconds from the start of the exposure to the picture being ready. */
    public double getCaptureLatency() { return num("cl"); }
    public String getPipelineType() { return LLResultTypes.str(jsonData, "pipelineType"); }
    /** The primary target's degrees right (+) of the crosshair. */
    public double getTx() { return num("tx"); }
    /** The primary target's degrees above (+) the crosshair. */
    public double getTy() { return num("ty"); }
    public double getTxNC() { return num("txnc"); }
    public double getTyNC() { return num("tync"); }
    /** The primary target's area, percent of the picture. */
    public double getTa() { return num("ta"); }
    /** The pipeline that produced this result (after a switch, a frame or two still come from the old one). */
    public int getPipelineIndex() { return (int) num("pID"); }
    /** Milliseconds the pipeline took. */
    public double getTargetingLatency() { return num("tl"); }
    /** The Limelight's own clock when the frame was taken (ms). */
    public double getTimestamp() { return num("ts"); }
    /** True when the pipeline found a target. */
    public boolean isValid() { return num("v") == 1; }
    public double getParseLatency() { return parseLatency; }

    @Override
    public String toString() {
        return "LLResult{pipeline=" + getPipelineIndex() + " " + getPipelineType() + ", valid=" + isValid()
                + ", tx=" + getTx() + ", ty=" + getTy() + ", ta=" + getTa()
                + ", fiducials=" + fiducialResults.size() + ", colors=" + colorResults.size() + "}";
    }
}
