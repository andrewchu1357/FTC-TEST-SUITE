package org.firstinspires.ftc.robotcore.external.hardware.camera;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.internal.system.Deadline;

/** Which camera: a webcam, a phone's built-in camera, or a switchable group of webcams. */
public interface CameraName {
    boolean isWebcam();

    boolean isCameraDirection();

    boolean isSwitchable();

    boolean isUnknown();

    /** The simulator has no Android permissions to ask for: always granted. */
    default void asyncRequestCameraPermission(android.content.Context context, Deadline deadline,
                                              Continuation<? extends Consumer<Boolean>> continuation) {
        @SuppressWarnings("unchecked")
        Continuation<Consumer<Boolean>> c = (Continuation<Consumer<Boolean>>) continuation;
        c.dispatch(new org.firstinspires.ftc.robotcore.external.function.ContinuationResult<Consumer<Boolean>>() {
            @Override public void handle(Consumer<Boolean> consumer) { consumer.accept(true); }
        });
    }

    default boolean requestCameraPermission(Deadline deadline) {
        return true;
    }
}
