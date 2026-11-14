package org.firstinspires.ftc.vision.apriltag;

import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.firstinspires.ftc.vision.VisionProcessor;

import java.util.ArrayList;

/** Finds AprilTags in each frame and works out where they are relative to the camera (and robot). */
public abstract class AprilTagProcessor implements VisionProcessor {
    public static final int THREADS_DEFAULT = 3;

    public static AprilTagProcessor easyCreateWithDefaults() {
        return new Builder().build();
    }

    public enum TagFamily {
        TAG_36h11("36h11", 6), TAG_25h9("25h9", 5), TAG_16h5("16h5", 4), TAG_standard41h12("standard41h12", 5);

        final String code;
        /** Cells across the black part of a tag (data bits plus the black border). */
        final int cells;

        TagFamily(String code, int dataCells) {
            this.code = code;
            this.cells = dataCells + 2;
        }
    }

    public enum PoseSolver {
        APRILTAG_BUILTIN(-1), OPENCV_ITERATIVE(0), OPENCV_SOLVEPNP_EPNP(1), OPENCV_IPPE(6), OPENCV_IPPE_SQUARE(7), OPENCV_SQPNP(8);

        final int code;

        PoseSolver(int code) {
            this.code = code;
        }
    }

    public static class Builder {
        private Position cameraPosition = new Position();
        private YawPitchRollAngles cameraOrientation = new YawPitchRollAngles(AngleUnit.DEGREES, 0, 0, 0, 0);
        private double fx, fy, cx, cy;
        private TagFamily tagFamily = TagFamily.TAG_36h11;
        private AprilTagLibrary tagLibrary = AprilTagGameDatabase.getCurrentGameTagLibrary();
        private DistanceUnit outputUnitsLength = DistanceUnit.INCH;
        private AngleUnit outputUnitsAngle = AngleUnit.DEGREES;
        private int threads = THREADS_DEFAULT;
        private boolean drawAxes = true, drawCube = false, drawOutline = true, drawTagId = true;
        private boolean suppressCalibrationWarnings;

        /**
         * Where the camera is on the robot (inches; x right, y forward, z up from the robot's
         * center) and how it's turned: pitch -90 is level and facing forward.
         */
        public Builder setCameraPose(Position position, YawPitchRollAngles ypr) {
            this.cameraPosition = position;
            this.cameraOrientation = ypr;
            return this;
        }

        public Builder setLensIntrinsics(double fx, double fy, double cx, double cy) {
            this.fx = fx;
            this.fy = fy;
            this.cx = cx;
            this.cy = cy;
            return this;
        }

        public Builder setSuppressCalibrationWarnings(boolean suppressCalibrationWarnings) {
            this.suppressCalibrationWarnings = suppressCalibrationWarnings;
            return this;
        }

        public Builder setTagFamily(TagFamily tagFamily) {
            this.tagFamily = tagFamily;
            return this;
        }

        public Builder setTagLibrary(AprilTagLibrary tagLibrary) {
            this.tagLibrary = tagLibrary;
            return this;
        }

        public Builder setOutputUnits(DistanceUnit distanceUnit, AngleUnit angleUnit) {
            this.outputUnitsLength = distanceUnit;
            this.outputUnitsAngle = angleUnit;
            return this;
        }

        public Builder setDrawAxes(boolean drawAxes) {
            this.drawAxes = drawAxes;
            return this;
        }

        public Builder setDrawCubeProjection(boolean drawCube) {
            this.drawCube = drawCube;
            return this;
        }

        public Builder setDrawTagOutline(boolean drawOutline) {
            this.drawOutline = drawOutline;
            return this;
        }

        public Builder setDrawTagID(boolean drawTagID) {
            this.drawTagId = drawTagID;
            return this;
        }

        public Builder setNumThreads(int threads) {
            this.threads = threads;
            return this;
        }

        public AprilTagProcessor build() {
            if (tagLibrary == null) throw new RuntimeException("Cannot create AprilTagProcessor without setting tag library!");
            if (tagFamily == null) throw new RuntimeException("Cannot create AprilTagProcessor without setting tag family!");
            OpenGLMatrix rotation = new Orientation(AxesReference.INTRINSIC, AxesOrder.ZXZ, AngleUnit.DEGREES,
                    (float) cameraOrientation.getYaw(AngleUnit.DEGREES), (float) cameraOrientation.getPitch(AngleUnit.DEGREES),
                    (float) cameraOrientation.getRoll(AngleUnit.DEGREES), cameraOrientation.getAcquisitionTime()).getRotationMatrix();
            Position p = cameraPosition.toUnit(DistanceUnit.INCH);
            OpenGLMatrix robotInCameraFrame = OpenGLMatrix.identityMatrix().translated((float) p.x, (float) p.y, (float) p.z).multiplied(rotation).inverted();
            return new AprilTagProcessorImpl(robotInCameraFrame, fx, fy, cx, cy, outputUnitsLength, outputUnitsAngle, tagLibrary,
                    drawAxes, drawCube, drawOutline, drawTagId, tagFamily, threads, suppressCalibrationWarnings);
        }
    }

    /** Lower decimation sees smaller (farther) tags but takes longer per frame. Default 3. */
    public abstract void setDecimation(float decimation);

    public abstract void setPoseSolver(PoseSolver poseSolver);

    public abstract int getPerTagAvgPoseSolveTime();

    /** The newest frame's detections. */
    public abstract ArrayList<AprilTagDetection> getDetections();

    /** Detections from a frame not returned before, else null. */
    public abstract ArrayList<AprilTagDetection> getFreshDetections();
}
