package org.firstinspires.ftc.robotcore.external;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.hardware.camera.BuiltinCameraDirection;
import org.firstinspires.ftc.robotcore.external.hardware.camera.BuiltinCameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraManager;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.internal.camera.delegating.SwitchableCameraName;
import org.ftctestbed.shim.vision.SimVision;

import java.util.List;

/** Factory for SDK services; here, the camera manager. */
public abstract class ClassFactory {
    private static final ClassFactory INSTANCE = new ClassFactory() {
        private final CameraManager cameraManager = new SimCameraManager();

        @Override
        public CameraManager getCameraManager() {
            return cameraManager;
        }
    };

    public static ClassFactory getInstance() {
        return INSTANCE;
    }

    public abstract CameraManager getCameraManager();

    /** All the robot's webcams as one switchable camera. */
    public static CameraName createSwitchableCameraNameForAllWebcams(HardwareMap hardwareMap) {
        List<WebcamName> webcams = hardwareMap.getAll(WebcamName.class);
        return getInstance().getCameraManager().nameForSwitchableCamera(webcams.toArray(new CameraName[0]));
    }

    private static final class SimCameraManager implements CameraManager {
        @Override
        public List<WebcamName> getAllWebcams() {
            return SimVision.webcams();
        }

        @Override
        public CameraName nameFromCameraDirection(final BuiltinCameraDirection direction) {
            return new BuiltinCameraName() {
                @Override public BuiltinCameraDirection getCameraDirection() { return direction; }
                @Override public boolean isWebcam() { return false; }
                @Override public boolean isCameraDirection() { return true; }
                @Override public boolean isSwitchable() { return false; }
                @Override public boolean isUnknown() { return false; }
                @Override public String toString() { return "BuiltinCamera(" + direction + ")"; }
            };
        }

        @Override
        public CameraName nameForUnknownCamera() {
            return new CameraName() {
                @Override public boolean isWebcam() { return false; }
                @Override public boolean isCameraDirection() { return false; }
                @Override public boolean isSwitchable() { return false; }
                @Override public boolean isUnknown() { return true; }
                @Override public String toString() { return "UnknownCamera"; }
            };
        }

        @Override
        public SwitchableCameraName nameForSwitchableCamera(final CameraName... names) {
            final CameraName[] members = names.clone();
            return new SwitchableCameraName() {
                @Override public CameraName[] getMembers() { return members.clone(); }

                @Override
                public boolean allMembersAreWebcams() {
                    for (CameraName n : members) if (!n.isWebcam()) return false;
                    return true;
                }

                @Override public boolean isWebcam() { return false; }
                @Override public boolean isCameraDirection() { return false; }
                @Override public boolean isSwitchable() { return true; }
                @Override public boolean isUnknown() { return false; }
                @Override public String toString() { return "Switchable(" + java.util.Arrays.toString(members) + ")"; }
            };
        }
    }
}
