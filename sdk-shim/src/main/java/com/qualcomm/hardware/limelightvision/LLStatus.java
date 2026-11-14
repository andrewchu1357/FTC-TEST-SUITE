package com.qualcomm.hardware.limelightvision;

import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

/** The Limelight's own status: temperature, CPU, frame rate, which pipeline is running. */
public class LLStatus {
    private Quaternion cameraQuat = new Quaternion(1, 0, 0, 0, 0);
    private int cid, hwType, pipeImgCount, pipelineIndex, snapshotMode;
    private double cpu, finalYaw, fps, ram, temp;
    private String name = "", pipelineType = "";

    public LLStatus() {}

    /** Simulator: a status with these values. */
    public static LLStatus simCreate(String name, int pipelineIndex, String pipelineType, double fps, double cpu,
                                     double temp, double finalYaw) {
        LLStatus s = new LLStatus();
        s.name = name;
        s.pipelineIndex = pipelineIndex;
        s.pipelineType = pipelineType;
        s.fps = fps;
        s.cpu = cpu;
        s.temp = temp;
        s.finalYaw = finalYaw;
        s.ram = 38;
        s.hwType = 4; // Limelight 3A
        return s;
    }

    public Quaternion getCameraQuat() { return cameraQuat; }
    public int getCid() { return cid; }
    public double getCpu() { return cpu; }
    public double getFinalYaw() { return finalYaw; }
    public double getFps() { return fps; }
    public int getHwType() { return hwType; }
    public String getName() { return name; }
    public int getPipeImgCount() { return pipeImgCount; }
    public int getPipelineIndex() { return pipelineIndex; }
    public String getPipelineType() { return pipelineType; }
    public double getRam() { return ram; }
    public int getSnapshotMode() { return snapshotMode; }
    public double getTemp() { return temp; }

    @Override
    public String toString() {
        return "LLStatus{name=" + name + ", pipeline=" + pipelineIndex + " " + pipelineType + ", fps=" + fps
                + ", cpu=" + cpu + ", temp=" + temp + "}";
    }
}
