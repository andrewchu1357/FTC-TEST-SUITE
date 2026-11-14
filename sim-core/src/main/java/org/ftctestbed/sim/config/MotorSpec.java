package org.ftctestbed.sim.config;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

/**
 * Electrical/mechanical characteristics of a motor at its output shaft, at 12 V.
 * Values come from the parts catalog (manufacturer spec sheets); teams can override any field.
 */
public record MotorSpec(
        String id,
        String sdkName,
        double ticksPerRev,
        double freeRpm,
        double stallTorqueNm,
        double stallCurrentA,
        double freeCurrentA) {

    public static final double NOMINAL_VOLTS = 12.0;
    private static final double KGCM = 0.0980665; // kg*cm -> N*m

    public double freeSpeedRadPerSec() {
        return freeRpm * 2 * Math.PI / 60.0;
    }

    public double ticksPerRadian() {
        return ticksPerRev / (2 * Math.PI);
    }

    public MotorConfigurationType toSdkType() {
        return new MotorConfigurationType(sdkName, "Motor", ticksPerRev, 1, freeRpm, 0.85);
    }

    /**
     * Accepts either a parts-catalog id string ("goBILDA-5203-312") or an object with
     * "base" (optional catalog id) plus any fields to override.
     */
    public static MotorSpec parse(ConfigNode owner, String key, String defaultId, PartsCatalog catalog) {
        Object raw = owner.has(key) ? owner.getRaw(key) : defaultId;
        if (raw instanceof String id) return catalog.motor(id, owner.path() + "." + key);
        ConfigNode n = owner.child(key);
        MotorSpec base = catalog.motor(n.getString("base", defaultId), n.path());
        return new MotorSpec(
                n.getString("name", base.id + "-custom"),
                n.getString("sdkName", base.sdkName),
                n.getDouble("ticksPerRev", base.ticksPerRev),
                n.getDouble("freeRpm", base.freeRpm),
                n.has("stallTorqueKgCm") ? n.getDouble("stallTorqueKgCm", 0) * KGCM : n.getDouble("stallTorqueNm", base.stallTorqueNm),
                n.getDouble("stallCurrentA", base.stallCurrentA),
                n.getDouble("freeCurrentA", base.freeCurrentA));
    }
}
