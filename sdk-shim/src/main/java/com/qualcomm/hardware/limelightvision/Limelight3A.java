package com.qualcomm.hardware.limelightvision;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.util.SerialNumber;

import java.net.InetAddress;

/**
 * A Limelight 3A smart camera (USB, seen as a network device). After start(), the SDK polls it
 * for results (100 times a second unless setPollRateHz) and getLatestResult() returns the newest.
 * This class on its own is a Limelight that isn't plugged in; the simulator's Limelight
 * (sim-core SimLimelight) does the work.
 */
public class Limelight3A implements HardwareDevice {
    protected final String name;

    public Limelight3A(SerialNumber serialNumber, String name, InetAddress ipAddress) {
        this.name = name;
    }

    /** Simulator: a subclass that simulates the camera. */
    protected Limelight3A(String name) {
        this.name = name;
    }

    /** Starts polling for results (without it getLatestResult() stays null). */
    public synchronized void start() {}

    public synchronized void pause() {}

    public synchronized void stop() {}

    public boolean isRunning() { return false; }

    /** How often the Control Hub asks for a result, 1-250 times a second. */
    public synchronized void setPollRateHz(int rateHz) {}

    /** Milliseconds since the last result arrived. */
    public long getTimeSinceLastUpdate() { return Long.MAX_VALUE; }

    public boolean isConnected() { return false; }

    public LLResult getLatestResult() { return null; }

    public LLStatus getStatus() { return new LLStatus(); }

    public boolean reloadPipeline() { return false; }

    /** Runs the pipeline in this slot (0-9). Takes effect a frame or two later. */
    public boolean pipelineSwitch(int index) { return false; }

    public boolean captureSnapshot(String snapname) { return false; }

    public boolean deleteSnapshots() { return false; }

    public boolean deleteSnapshot(String snapname) { return false; }

    public boolean updatePythonInputs(double input1, double input2, double input3, double input4,
                                      double input5, double input6, double input7, double input8) {
        return updatePythonInputs(new double[]{input1, input2, input3, input4, input5, input6, input7, input8});
    }

    public boolean updatePythonInputs(double[] inputs) { return false; }

    /** The robot's heading (degrees, counter-clockwise) for MegaTag 2. Send it every loop. */
    public boolean updateRobotOrientation(double yaw) { return false; }

    public boolean uploadPipeline(String jsonString, Integer index) { return false; }

    public boolean uploadFieldmap(LLFieldMap fieldmap, Integer index) { return false; }

    public boolean uploadPython(String pythonString, Integer index) { return false; }

    public LLResultTypes.CalibrationResult getCalDefault() { return new LLResultTypes.CalibrationResult(); }

    public LLResultTypes.CalibrationResult getCalFile() { return new LLResultTypes.CalibrationResult(); }

    public LLResultTypes.CalibrationResult getCalEEPROM() { return new LLResultTypes.CalibrationResult(); }

    public LLResultTypes.CalibrationResult getCalLatest() { return new LLResultTypes.CalibrationResult(); }

    public void shutdown() { stop(); }

    @Override public Manufacturer getManufacturer() { return Manufacturer.LimelightVision; }
    @Override public String getDeviceName() { return "Limelight 3A"; }
    @Override public String getConnectionInfo() { return "USB (" + name + ")"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() { shutdown(); }
}
