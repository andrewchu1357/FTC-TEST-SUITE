package org.ftctestbed.shim.vision;

/**
 * The SDK's own VisionProcessors in the simulator: they read a SimFrame instead of pixels (custom
 * VisionProcessors get processFrame() with an empty Mat).
 */
public interface SimProcessor {
    /** How long this processor takes on a Control Hub for this frame (ms). */
    double simCostMillis(SimFrame frame);

    /** Physics thread: process the frame and publish what was found, as processFrame() would. */
    void simProcessFrame(SimFrame frame);
}
