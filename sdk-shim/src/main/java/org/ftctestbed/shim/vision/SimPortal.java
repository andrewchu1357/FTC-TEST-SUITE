package org.ftctestbed.shim.vision;

/** A VisionPortal streaming from simulated cameras: each camera steps it from the physics thread. */
public interface SimPortal {
    /** Physics thread, every step: simulated time is now (SimHooks.nanoTime()). */
    void simStep(SimCamera from, long nowNanos);
}
