package org.firstinspires.ftc.vision;

import android.util.Size;

import org.firstinspires.ftc.robotcore.external.ClassFactory;
import org.firstinspires.ftc.robotcore.external.hardware.camera.BuiltinCameraDirection;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;
import org.firstinspires.ftc.robotcore.external.stream.CameraStreamSource;
import org.ftctestbed.shim.vision.SimVision;

import java.util.ArrayList;
import java.util.List;

/** A camera and the VisionProcessors that look at its frames. */
public abstract class VisionPortal implements CameraStreamSource {
    /** The Robot Controller screen's camera preview. */
    public static final int DEFAULT_VIEW_CONTAINER_ID = 0x7F0A0071;

    private static int nextViewId = 0x7F0B0001;

    public enum CameraState {
        OPENING_CAMERA_DEVICE, CAMERA_DEVICE_READY, STARTING_STREAM, STREAMING, STOPPING_STREAM,
        CLOSING_CAMERA_DEVICE, CAMERA_DEVICE_CLOSED, ERROR
    }

    public enum MultiPortalLayout { VERTICAL, HORIZONTAL }

    public enum StreamFormat { YUY2, MJPEG }

    /** View ids for portals sharing the Robot Controller's preview (setLiveViewContainerId). */
    public static synchronized int[] makeMultiPortalView(int numPortals, MultiPortalLayout mpl) {
        int[] ids = new int[numPortals];
        for (int i = 0; i < numPortals; i++) ids[i] = nextViewId++;
        return ids;
    }

    public static VisionPortal easyCreateWithDefaults(BuiltinCameraDirection cameraDirection, VisionProcessor... processors) {
        return new Builder().setCamera(cameraDirection).addProcessors(processors).build();
    }

    public static VisionPortal easyCreateWithDefaults(CameraName cameraName, VisionProcessor... processors) {
        return new Builder().setCamera(cameraName).addProcessors(processors).build();
    }

    public static class Builder {
        private CameraName camera;
        private int liveViewContainerId = DEFAULT_VIEW_CONTAINER_ID;
        private boolean autoStopLiveView = true;
        private boolean autoStartStreamOnBuild = true;
        private boolean showStatsOverlay = true;
        private Size cameraResolution = new Size(640, 480);
        private StreamFormat streamFormat = null;
        private final List<VisionProcessor> processors = new ArrayList<VisionProcessor>();

        public Builder setCamera(CameraName camera) {
            this.camera = camera;
            return this;
        }

        public Builder setCamera(BuiltinCameraDirection cameraDirection) {
            this.camera = ClassFactory.getInstance().getCameraManager().nameFromCameraDirection(cameraDirection);
            return this;
        }

        public Builder setStreamFormat(StreamFormat streamFormat) {
            this.streamFormat = streamFormat;
            return this;
        }

        public Builder enableLiveView(boolean enableLiveView) {
            return setLiveViewContainerId(enableLiveView ? DEFAULT_VIEW_CONTAINER_ID : 0);
        }

        public Builder setAutoStopLiveView(boolean autoPause) {
            this.autoStopLiveView = autoPause;
            return this;
        }

        public Builder setLiveViewContainerId(int id) {
            this.liveViewContainerId = id;
            return this;
        }

        public Builder setCameraResolution(Size cameraResolution) {
            this.cameraResolution = cameraResolution;
            return this;
        }

        public Builder addProcessor(VisionProcessor processor) {
            if (!SimVision.attach(processor)) {
                throw new RuntimeException("This VisionProcessor has already been attached to a VisionPortal, either a different one or perhaps even this same portal.");
            }
            processors.add(processor);
            return this;
        }

        public Builder addProcessors(VisionProcessor... processors) {
            for (VisionProcessor p : processors) addProcessor(p);
            return this;
        }

        public Builder setAutoStartStreamOnBuild(boolean autoStartStreamOnBuild) {
            this.autoStartStreamOnBuild = autoStartStreamOnBuild;
            return this;
        }

        public Builder setShowStatsOverlay(boolean showStatsOverlay) {
            this.showStatsOverlay = showStatsOverlay;
            return this;
        }

        public VisionPortal build() {
            if (camera == null) throw new RuntimeException("You can't build a vision portal without setting a camera!");
            if (streamFormat != null && !camera.isWebcam() && !camera.isSwitchable()) {
                throw new IllegalStateException("setStreamFormat() may only be used with a webcam");
            }
            VisionPortal portal = new VisionPortalImpl(camera, liveViewContainerId, autoStopLiveView, cameraResolution, streamFormat,
                    autoStartStreamOnBuild, showStatsOverlay, processors.toArray(new VisionProcessor[0]));
            processors.clear();
            return portal;
        }
    }

    public abstract void setProcessorEnabled(VisionProcessor processor, boolean enabled);

    public abstract boolean getProcessorEnabled(VisionProcessor processor);

    public abstract CameraState getCameraState();

    public abstract void saveNextFrameRaw(String filename);

    public abstract void stopStreaming();

    public abstract void resumeStreaming();

    public abstract void stopLiveView();

    public abstract void resumeLiveView();

    public abstract float getFps();

    public abstract <T extends CameraControl> T getCameraControl(Class<T> controlType);

    public abstract void setActiveCamera(WebcamName webcamName);

    public abstract WebcamName getActiveCamera();

    public abstract void close();
}
