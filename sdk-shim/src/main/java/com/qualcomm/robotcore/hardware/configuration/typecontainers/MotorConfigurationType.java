package com.qualcomm.robotcore.hardware.configuration.typecontainers;

/**
 * Describes a motor model (encoder resolution, max RPM). In the real SDK these come from
 * the robot configuration; in the simulator they come from config/robot.jsonc.
 */
public class MotorConfigurationType implements Cloneable {
    private String name;
    private String xmlTag;
    private double ticksPerRev;
    private double gearing;
    private double maxRPM;
    private double achieveableMaxRPMFraction;

    public MotorConfigurationType(String name, String xmlTag, double ticksPerRev, double gearing, double maxRPM, double achieveableMaxRPMFraction) {
        this.name = name;
        this.xmlTag = xmlTag;
        this.ticksPerRev = ticksPerRev;
        this.gearing = gearing;
        this.maxRPM = maxRPM;
        this.achieveableMaxRPMFraction = achieveableMaxRPMFraction;
    }

    public static MotorConfigurationType getUnspecifiedMotorType() {
        return new MotorConfigurationType("Unspecified Motor", "Motor", 28, 1, 6000, 0.85);
    }

    public String getName() { return name; }
    public String getXmlTag() { return xmlTag; }

    public double getTicksPerRev() { return ticksPerRev; }
    public void setTicksPerRev(double ticksPerRev) { this.ticksPerRev = ticksPerRev; }

    public double getGearing() { return gearing; }
    public void setGearing(double gearing) { this.gearing = gearing; }

    public double getMaxRPM() { return maxRPM; }
    public void setMaxRPM(double maxRPM) { this.maxRPM = maxRPM; }

    public double getAchieveableMaxRPMFraction() { return achieveableMaxRPMFraction; }
    public void setAchieveableMaxRPMFraction(double fraction) { this.achieveableMaxRPMFraction = fraction; }

    public double getAchieveableMaxTicksPerSecond() {
        return achieveableMaxRPMFraction * maxRPM * ticksPerRev / 60.0;
    }

    public int getAchieveableMaxTicksPerSecondRounded() {
        return (int) Math.round(getAchieveableMaxTicksPerSecond());
    }

    public boolean isUnspecified() { return "Unspecified Motor".equals(name); }

    @Override
    public MotorConfigurationType clone() {
        try {
            return (MotorConfigurationType) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() { return name; }
}
