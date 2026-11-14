package com.qualcomm.hardware.limelightvision;

import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The kinds of target a Limelight pipeline reports, each read from the Limelight's result JSON. */
public class LLResultTypes {
    public LLResultTypes() {}

    static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : "";
    }

    static Pose3D pose(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return LLResult.createPose3DRobot(v instanceof double[] ? (double[]) v : new double[0]);
    }

    @SuppressWarnings("unchecked")
    static List<List<Double>> corners(Map<String, Object> m) {
        Object v = m.get("pts");
        if (!(v instanceof List)) return Collections.emptyList();
        List<List<Double>> out = new ArrayList<List<Double>>();
        for (Object o : (List<Object>) v) {
            if (o instanceof List) out.add(new ArrayList<Double>((List<Double>) o));
        }
        return out;
    }

    /** A target's place in the picture, shared by every kind of result. */
    abstract static class Target {
        protected final double targetXPixels, targetYPixels, targetXDegrees, targetYDegrees;
        protected final double targetXDegreesNoCrosshair, targetYDegreesNoCrosshair, targetArea;
        protected final List<List<Double>> targetCorners;

        Target(Map<String, Object> m) {
            targetXPixels = num(m, "txp");
            targetYPixels = num(m, "typ");
            targetXDegrees = num(m, "tx");
            targetYDegrees = num(m, "ty");
            targetXDegreesNoCrosshair = num(m, "tx_nocross");
            targetYDegreesNoCrosshair = num(m, "ty_nocross");
            targetArea = num(m, "ta");
            targetCorners = corners(m);
        }

        public double getTargetXPixels() { return targetXPixels; }
        public double getTargetYPixels() { return targetYPixels; }
        /** Degrees right (+) of the crosshair. */
        public double getTargetXDegrees() { return targetXDegrees; }
        /** Degrees above (+) the crosshair. */
        public double getTargetYDegrees() { return targetYDegrees; }
        public double getTargetXDegreesNoCrosshair() { return targetXDegreesNoCrosshair; }
        public double getTargetYDegreesNoCrosshair() { return targetYDegreesNoCrosshair; }
        /** Percent of the picture the target covers (0-100). */
        public double getTargetArea() { return targetArea; }
        public List<List<Double>> getTargetCorners() { return targetCorners; }
    }

    /** An AprilTag. Poses are meters and degrees. */
    public static class FiducialResult extends Target {
        private final int fiducialId;
        private final String family;
        private final double skew;
        private final Pose3D cameraPoseTargetSpace, robotPoseFieldSpace, robotPoseTargetSpace,
                targetPoseCameraSpace, targetPoseRobotSpace;

        FiducialResult(Map<String, Object> m) {
            super(m);
            fiducialId = (int) num(m, "fID");
            family = str(m, "fam");
            skew = num(m, "skew");
            cameraPoseTargetSpace = pose(m, "t6c_ts");
            robotPoseFieldSpace = pose(m, "t6r_fs");
            robotPoseTargetSpace = pose(m, "t6r_ts");
            targetPoseCameraSpace = pose(m, "t6t_cs");
            targetPoseRobotSpace = pose(m, "t6t_rs");
        }

        public int getFiducialId() { return fiducialId; }
        public String getFamily() { return family; }
        public double getSkew() { return skew; }
        public Pose3D getCameraPoseTargetSpace() { return cameraPoseTargetSpace; }
        public Pose3D getRobotPoseFieldSpace() { return robotPoseFieldSpace; }
        public Pose3D getRobotPoseTargetSpace() { return robotPoseTargetSpace; }
        public Pose3D getTargetPoseCameraSpace() { return targetPoseCameraSpace; }
        public Pose3D getTargetPoseRobotSpace() { return targetPoseRobotSpace; }
    }

    /** A blob found by a color pipeline. */
    public static class ColorResult extends Target {
        private final Pose3D cameraPoseTargetSpace, robotPoseFieldSpace, robotPoseTargetSpace,
                targetPoseCameraSpace, targetPoseRobotSpace;

        ColorResult(Map<String, Object> m) {
            super(m);
            cameraPoseTargetSpace = pose(m, "t6c_ts");
            robotPoseFieldSpace = pose(m, "t6r_fs");
            robotPoseTargetSpace = pose(m, "t6r_ts");
            targetPoseCameraSpace = pose(m, "t6t_cs");
            targetPoseRobotSpace = pose(m, "t6t_rs");
        }

        public Pose3D getCameraPoseTargetSpace() { return cameraPoseTargetSpace; }
        public Pose3D getRobotPoseFieldSpace() { return robotPoseFieldSpace; }
        public Pose3D getRobotPoseTargetSpace() { return robotPoseTargetSpace; }
        public Pose3D getTargetPoseCameraSpace() { return targetPoseCameraSpace; }
        public Pose3D getTargetPoseRobotSpace() { return targetPoseRobotSpace; }
    }

    /** An object found by a neural detector pipeline (not simulated: never reported). */
    public static class DetectorResult extends Target {
        private final String className;
        private final int classId;
        private final double confidence;

        DetectorResult(Map<String, Object> m) {
            super(m);
            className = str(m, "class");
            classId = (int) num(m, "classID");
            confidence = num(m, "conf");
        }

        public String getClassName() { return className; }
        public int getClassId() { return classId; }
        public double getConfidence() { return confidence; }
    }

    /** A whole-picture neural classification (not simulated: never reported). */
    public static class ClassifierResult {
        private final String className;
        private final int classId;
        private final double confidence;

        ClassifierResult(Map<String, Object> m) {
            className = str(m, "class");
            classId = (int) num(m, "classID");
            confidence = num(m, "conf");
        }

        public String getClassName() { return className; }
        public int getClassId() { return classId; }
        public double getConfidence() { return confidence; }
    }

    /** A barcode or QR code (not simulated: never reported). */
    public static class BarcodeResult extends Target {
        private final String family, data;

        BarcodeResult(Map<String, Object> m) {
            super(m);
            family = str(m, "fam");
            data = str(m, "data");
        }

        public String getFamily() { return family; }
        public String getData() { return data; }
    }

    /** A camera calibration: resolution, the camera matrix (fx 0 cx 0 fy cy 0 0 1) and lens distortion. */
    public static class CalibrationResult {
        private final String displayName;
        private final double resX, resY, reprojectionError;
        private final double[] camMatVector, distortionCoefficients;
        private final boolean valid;

        public CalibrationResult() {
            this("", 0, 0, 0, new double[0], new double[0]);
        }

        public CalibrationResult(String displayName, double resX, double resY, double reprojectionError,
                                 double[] camMatVector, double[] distortionCoefficients) {
            this.displayName = displayName;
            this.resX = resX;
            this.resY = resY;
            this.reprojectionError = reprojectionError;
            this.camMatVector = camMatVector;
            this.distortionCoefficients = distortionCoefficients;
            this.valid = resX > 0 && camMatVector.length == 9;
        }

        public boolean isValid() { return valid; }
        public String getDisplayName() { return displayName; }
        public double getResX() { return resX; }
        public double getResY() { return resY; }
        public double getReprojectionError() { return reprojectionError; }
        public double[] getCamMatVector() { return camMatVector; }
        public double[] getDistortionCoefficients() { return distortionCoefficients; }
    }
}
