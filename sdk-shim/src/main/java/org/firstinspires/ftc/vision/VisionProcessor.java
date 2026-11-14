package org.firstinspires.ftc.vision;

/**
 * Something that looks at each camera frame. The SDK's own (AprilTagProcessor,
 * ColorBlobLocatorProcessor, PredominantColorProcessor) work in the simulator; a custom one gets
 * init() and a processFrame() call per frame, but its Mat has no pixels.
 */
public interface VisionProcessor extends VisionProcessorInternal {
}
