package org.firstinspires.ftc.vision;

import android.graphics.Bitmap;
import android.util.Size;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.function.ContinuationResult;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.robotcore.internal.camera.delegating.SwitchableCameraName;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.ftctestbed.shim.SimHooks;
import org.ftctestbed.shim.vision.SimCamera;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimPortal;
import org.ftctestbed.shim.vision.SimProcessor;
import org.ftctestbed.shim.vision.SimVision;
import org.opencv.core.Mat;

import java.io.File;
import java.util.List;

/**
 * The SDK's VisionPortal on a simulated webcam. Opening the camera, starting and stopping the
 * stream take time as on a Control Hub [ESTIMATE: about 1.7 s from build() to STREAMING]. Frames
 * come at the camera's rate for the resolution and stream format; the processors run on each in
 * turn, and a frame that arrives while they're busy waits (only the newest), so a slow pipeline
 * lowers getFps() and detections lag the robot's motion. Results appear when processing ends.
 * When the OpMode stops, the camera closes (as EasyOpenCV does).
 */
public class VisionPortalImpl extends VisionPortal implements SimPortal {
    static final long OPEN_NANOS = 1_200_000_000L;   // [ESTIMATE]
    static final long START_NANOS = 500_000_000L;    // [ESTIMATE]
    static final long STOP_NANOS = 100_000_000L;     // [ESTIMATE]
    static final long CLOSE_NANOS = 200_000_000L;    // [ESTIMATE]
    static final long SWITCH_NANOS = 150_000_000L;   // switchable webcams [ESTIMATE]
    /** Per-frame cost before any processor runs: conversion and the preview (ms) [ESTIMATE]. */
    static final double FRAME_OVERHEAD_MS = 2.0;

    private final Object lock = new Object();
    private final CameraName cameraName;
    private final SimCamera[] cameras;
    protected final VisionProcessor[] processors;
    protected final boolean[] processorsEnabled;
    private final Size cameraResolution;
    private final boolean mjpeg;
    private final boolean autoStartStream;
    private final int cameraMonitorViewId;
    private final Object robotKey;
    private final OpModeManagerNotifier.Notifications opModeListener;

    private volatile CameraState cameraState = CameraState.CAMERA_DEVICE_CLOSED;
    private volatile SimCamera active;
    /** When the current transitional state ends (simulated ns); UNSET: start timing at the next step. */
    private long transitionAt = UNSET;
    private static final long UNSET = Long.MIN_VALUE;
    private boolean claimed, processorsInitialized, warnedCustom;
    private String openError;
    private long nextFrameAt = -1, processingDoneAt, switchUntil;
    private SimFrame pending, processing;
    private volatile float fps;
    private long lastDoneAt = -1;
    private volatile String saveNext;

    public VisionPortalImpl(CameraName camera, int cameraMonitorViewId, boolean autoPauseCameraMonitor, Size cameraResolution,
                            StreamFormat webcamStreamFormat, boolean autoStartStreamOnBuild, boolean showStatsOverlay, VisionProcessor[] processors) {
        this.robotKey = SimHooks.robotKey();
        this.cameraName = camera;
        this.cameraMonitorViewId = cameraMonitorViewId;
        synchronized (SimVision.viewLock()) {
            if (cameraMonitorViewId != 0) {
                List<Integer> views = SimVision.viewsInUse();
                if (views.contains(cameraMonitorViewId)) {
                    if (cameraMonitorViewId == DEFAULT_VIEW_CONTAINER_ID) {
                        throw new IllegalStateException("When using multiple vision portals, you MUST use setLiveViewContainerId(int), NOT enableLiveView(bool)");
                    }
                    throw new IllegalStateException("User code attempted to set up multiple LiveViews with the same View ID. If you are using makeMultiPortalView(...), make sure you are using different index values on the returned array for each portal.");
                }
                if (views.contains(DEFAULT_VIEW_CONTAINER_ID) || (views.size() > 0 && cameraMonitorViewId == DEFAULT_VIEW_CONTAINER_ID)) {
                    throw new IllegalStateException("Misconfigured MultiPortal View IDs for LiveView. Make sure you are using setLiveViewContainerId(int) for ALL portals");
                }
                views.add(cameraMonitorViewId);
            }
        }
        this.processors = processors;
        this.processorsEnabled = new boolean[processors.length];
        for (int i = 0; i < processors.length; i++) processorsEnabled[i] = true;
        this.cameraResolution = cameraResolution;
        this.mjpeg = webcamStreamFormat == StreamFormat.MJPEG;
        this.autoStartStream = autoStartStreamOnBuild;
        this.cameras = createCamera(camera);
        this.opModeListener = new OpModeManagerNotifier.Notifications() {
            @Override public void onOpModePreInit(OpMode opMode) {}
            @Override public void onOpModePreStart(OpMode opMode) {}
            @Override public void onOpModePostStop(OpMode opMode) {
                Object key = SimHooks.robotKey();
                if (key == robotKey || (key != null && key.equals(robotKey))) closeNow();
            }
        };
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).registerListener(opModeListener);
        startCamera();
    }

    protected SimCamera[] createCamera(CameraName camera) {
        if (camera == null) throw new IllegalArgumentException("parameters.camera == null");
        if (camera.isWebcam()) {
            if (!(camera instanceof SimCamera)) throw new IllegalArgumentException("Unknown camera name");
            return new SimCamera[]{(SimCamera) camera};
        }
        if (camera.isCameraDirection()) {
            return new SimCamera[0]; // a Control Hub has no built-in camera: opening fails
        }
        if (camera.isSwitchable()) {
            SwitchableCameraName s = (SwitchableCameraName) camera;
            if (!s.allMembersAreWebcams()) throw new IllegalArgumentException("All members of a switchable camera name must be webcam names");
            CameraName[] members = s.getMembers();
            SimCamera[] out = new SimCamera[members.length];
            for (int i = 0; i < members.length; i++) {
                if (!(members[i] instanceof SimCamera)) throw new IllegalArgumentException("Unknown camera name");
                out[i] = (SimCamera) members[i];
            }
            return out;
        }
        throw new IllegalArgumentException("Unknown camera name");
    }

    protected void startCamera() {
        if (cameraResolution == null) throw new IllegalArgumentException("parameters.cameraResolution == null");
        synchronized (lock) {
            cameraState = CameraState.OPENING_CAMERA_DEVICE;
            transitionAt = UNSET;
            if (cameras.length == 0) {
                openError = "This robot has no built-in camera (a Control Hub has none). Use a webcam: hardwareMap.get(WebcamName.class, \"Webcam 1\").";
                cameraState = CameraState.ERROR;
                SimHooks.warn("VisionPortal: " + openError);
                return;
            }
            claimed = true;
            for (SimCamera c : cameras) {
                if (!c.simClaim(this)) {
                    claimed = false;
                    openError = c.getDeviceName() + " is already in use by another VisionPortal; close() that one first.";
                }
            }
            active = cameras[0];
            if (!claimed) {
                for (SimCamera c : cameras) c.simRelease(this);
                cameraState = CameraState.ERROR; // the camera library's open fails
                SimHooks.warn("VisionPortal: " + openError);
            }
        }
    }

    // ------------------------------------------------------------------ physics thread

    @Override
    public void simStep(SimCamera from, long now) {
        List<VisionProcessor> due = null;
        SimFrame done = null;
        synchronized (lock) {
            if (from != active) return;
            if (transitionAt == UNSET) transitionAt = now + duration(cameraState);
            if (switchUntil == UNSET) switchUntil = now + SWITCH_NANOS;
            switch (cameraState) {
                case OPENING_CAMERA_DEVICE:
                    if (now < transitionAt) break;
                    if (openError != null) {
                        cameraState = CameraState.ERROR;
                        SimHooks.warn("VisionPortal: " + openError);
                        break;
                    }
                    cameraState = CameraState.CAMERA_DEVICE_READY;
                    if (autoStartStream) {
                        cameraState = CameraState.STARTING_STREAM;
                        transitionAt = now + START_NANOS;
                    }
                    break;
                case STARTING_STREAM:
                    if (now < transitionAt) break;
                    String sizeError = active.simCheckSize(cameraResolution.getWidth(), cameraResolution.getHeight());
                    if (sizeError != null) {
                        cameraState = CameraState.ERROR;
                        SimHooks.warn("VisionPortal: " + sizeError);
                        break;
                    }
                    cameraState = CameraState.STREAMING;
                    nextFrameAt = now;
                    lastDoneAt = -1;
                    if (!processorsInitialized) {
                        processorsInitialized = true;
                        due = java.util.Arrays.asList(processors);
                    }
                    break;
                case STOPPING_STREAM:
                    if (now >= transitionAt) {
                        cameraState = CameraState.CAMERA_DEVICE_READY;
                        pending = processing = null;
                        fps = 0;
                    }
                    break;
                case CLOSING_CAMERA_DEVICE:
                    if (now >= transitionAt) finishClose();
                    break;
                default:
                    break;
            }
        }
        if (due != null) initProcessors(due);
        if (cameraState != CameraState.STREAMING) return;
        synchronized (lock) {
            if (processing != null && now >= processingDoneAt) {
                done = processing;
                processing = null;
            }
            if (now >= nextFrameAt && now >= switchUntil) {
                int w = cameraResolution.getWidth(), h = cameraResolution.getHeight();
                pending = active.simCapture(w, h); // only the newest frame waits
                double interval = Math.max(1.0 / active.simMaxFps(w, h, mjpeg), active.simControls().exposureSeconds());
                long step = (long) (interval * 1e9);
                nextFrameAt = nextFrameAt + step > now ? nextFrameAt + step : now + step;
            }
        }
        if (done != null) publish(done, now);
        synchronized (lock) {
            if (processing == null && pending != null && cameraState == CameraState.STREAMING) {
                processing = pending;
                pending = null;
                processingDoneAt = now + (long) (cost(processing) * 1e6);
            }
        }
    }

    private static long duration(CameraState s) {
        switch (s) {
            case OPENING_CAMERA_DEVICE: return OPEN_NANOS;
            case STARTING_STREAM: return START_NANOS;
            case STOPPING_STREAM: return STOP_NANOS;
            case CLOSING_CAMERA_DEVICE: return CLOSE_NANOS;
            default: return 0;
        }
    }

    private void initProcessors(List<VisionProcessor> list) {
        int w = cameraResolution.getWidth(), h = cameraResolution.getHeight();
        CameraCalibration calibration = active.simCalibration(w, h);
        for (VisionProcessor p : list) {
            try {
                p.init(w, h, calibration);
            } catch (RuntimeException e) {
                SimHooks.log("E", "VisionPortal", p.getClass().getSimpleName() + ".init threw " + e);
            }
        }
    }

    private double cost(SimFrame f) {
        double ms = FRAME_OVERHEAD_MS * f.width * f.height / 307200.0;
        for (int i = 0; i < processors.length; i++) {
            if (!processorsEnabled[i]) continue;
            ms += processors[i] instanceof SimProcessor ? ((SimProcessor) processors[i]).simCostMillis(f) : 5;
        }
        return ms;
    }

    private void publish(SimFrame f, long now) {
        for (int i = 0; i < processors.length; i++) {
            if (!processorsEnabled[i]) continue;
            VisionProcessor p = processors[i];
            try {
                if (p instanceof SimProcessor) {
                    ((SimProcessor) p).simProcessFrame(f);
                } else {
                    if (!warnedCustom) {
                        warnedCustom = true;
                        SimHooks.warn("VisionPortal: " + p.getClass().getSimpleName() + " is a custom VisionProcessor. "
                                + "The simulator calls its processFrame() each frame, but the Mat has no pixels.");
                    }
                    p.processFrame(new Mat(f.height, f.width, 0), f.captureNanos);
                }
            } catch (RuntimeException e) {
                SimHooks.log("E", "VisionPortal", p.getClass().getSimpleName() + ".processFrame threw " + e);
            }
        }
        String save = saveNext;
        if (save != null) {
            saveNext = null;
            saveFrame(f, save);
        }
        if (lastDoneAt >= 0 && now > lastDoneAt) {
            float instant = (float) (1e9 / (now - lastDoneAt));
            fps = fps == 0 ? instant : fps * 0.8f + instant * 0.2f;
        }
        lastDoneAt = now;
    }

    private void saveFrame(SimFrame f, String name) {
        try {
            int[] px = f.render(0, 0, f.width, f.height);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(f.width, f.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, f.width, f.height, px, 0, f.width);
            File file = new File(AppUtil.ROOT_FOLDER, "VisionPortal-" + name + ".png");
            AppUtil.getInstance().ensureDirectoryExists(file.getParentFile());
            javax.imageio.ImageIO.write(img, "png", file);
            SimHooks.log("I", "VisionPortal", "saved " + file);
        } catch (java.io.IOException | RuntimeException e) {
            SimHooks.log("E", "VisionPortal", "couldn't save a frame: " + e);
        }
    }

    // ------------------------------------------------------------------ robot thread

    private int indexOf(VisionProcessor processor) {
        for (int i = 0; i < processors.length; i++) if (processors[i] == processor) return i;
        throw new IllegalArgumentException("Processor not attached to this helper!");
    }

    @Override
    public void setProcessorEnabled(VisionProcessor processor, boolean enabled) {
        int i = indexOf(processor);
        synchronized (lock) {
            processorsEnabled[i] = enabled;
        }
    }

    @Override
    public boolean getProcessorEnabled(VisionProcessor processor) {
        int i = indexOf(processor);
        synchronized (lock) {
            return processorsEnabled[i];
        }
    }

    @Override
    public CameraState getCameraState() {
        return cameraState;
    }

    @Override
    public void saveNextFrameRaw(String filename) {
        saveNext = filename;
    }

    @Override
    public void stopStreaming() {
        synchronized (lock) {
            CameraState s = cameraState;
            if (s == CameraState.STREAMING || s == CameraState.STARTING_STREAM) {
                cameraState = CameraState.STOPPING_STREAM;
                transitionAt = UNSET;
            } else if (s != CameraState.STOPPING_STREAM && s != CameraState.CAMERA_DEVICE_READY && s != CameraState.CLOSING_CAMERA_DEVICE) {
                throw new RuntimeException(String.format("Illegal CameraState %s when calling stopStreaming()", s));
            }
        }
    }

    @Override
    public void resumeStreaming() {
        synchronized (lock) {
            CameraState s = cameraState;
            if (s == CameraState.CAMERA_DEVICE_READY || s == CameraState.STOPPING_STREAM) {
                cameraState = CameraState.STARTING_STREAM;
                transitionAt = UNSET;
            } else if (s != CameraState.STREAMING && s != CameraState.STARTING_STREAM) {
                throw new RuntimeException(String.format("Illegal CameraState %s when calling resumeStreaming()", s));
            }
        }
    }

    @Override public void stopLiveView() {}
    @Override public void resumeLiveView() {}

    @Override
    public float getFps() {
        return cameraState == CameraState.STREAMING ? fps : 0;
    }

    @Override
    public <T extends CameraControl> T getCameraControl(Class<T> controlType) {
        if (cameraState != CameraState.STREAMING) {
            throw new IllegalStateException("You cannot use camera controls until the camera is streaming");
        }
        if (!cameraName.isWebcam() && !cameraName.isSwitchable()) {
            throw new UnsupportedOperationException("Getting controls is only supported for webcams");
        }
        return active.simControls().get(controlType);
    }

    @Override
    public void setActiveCamera(WebcamName webcamName) {
        if (!cameraName.isSwitchable()) throw new UnsupportedOperationException("setActiveCamera is only supported for switchable webcams");
        synchronized (lock) {
            for (SimCamera c : cameras) {
                if (c == webcamName) {
                    if (c != active) {
                        active = c;
                        switchUntil = UNSET;
                        pending = null;
                    }
                    return;
                }
            }
        }
        throw new IllegalArgumentException("That webcam is not part of this switchable camera");
    }

    @Override
    public WebcamName getActiveCamera() {
        if (!cameraName.isSwitchable()) throw new UnsupportedOperationException("getActiveCamera is only supported for switchable webcams");
        return active;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (cameras.length > 0 && cameraState != CameraState.CAMERA_DEVICE_CLOSED && cameraState != CameraState.CLOSING_CAMERA_DEVICE) {
                cameraState = CameraState.CLOSING_CAMERA_DEVICE;
                transitionAt = UNSET;
                if (!claimed) finishClose();
            }
        }
        freeView();
    }

    /** The OpMode ended: EasyOpenCV closes the camera right away. */
    void closeNow() {
        synchronized (lock) {
            if (cameraState != CameraState.CAMERA_DEVICE_CLOSED) finishClose();
        }
        freeView();
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).unregisterListener(opModeListener);
    }

    private void finishClose() {
        cameraState = CameraState.CAMERA_DEVICE_CLOSED;
        pending = processing = null;
        fps = 0;
        if (claimed) for (SimCamera c : cameras) c.simRelease(this);
        claimed = false;
    }

    private void freeView() {
        synchronized (SimVision.viewLock()) {
            if (cameraMonitorViewId != 0) SimVision.viewsInUse().remove(Integer.valueOf(cameraMonitorViewId));
        }
    }

    /** Camera power loss (robot reset): the stream is gone. */
    public void simCameraLost() {
        synchronized (lock) {
            if (cameraState != CameraState.CAMERA_DEVICE_CLOSED) finishClose();
            cameraState = CameraState.ERROR;
        }
    }

    @Override
    public void getFrameBitmap(Continuation<? extends Consumer<Bitmap>> continuation) {
        @SuppressWarnings("unchecked")
        Continuation<Consumer<Bitmap>> c = (Continuation<Consumer<Bitmap>>) continuation;
        final Bitmap b = Bitmap.createBitmap(cameraResolution.getWidth(), cameraResolution.getHeight(), Bitmap.Config.RGB_565);
        c.dispatch(new ContinuationResult<Consumer<Bitmap>>() {
            @Override public void handle(Consumer<Bitmap> consumer) { consumer.accept(b); }
        });
    }
}
