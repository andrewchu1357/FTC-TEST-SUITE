package org.ftctestbed.sim.physics;

/**
 * A 12 V FTC battery: ten NiMH cells in series. Its resting (open-circuit) voltage falls as
 * charge is used - slowly through most of the charge, quickly near empty - and its internal
 * resistance (plus wiring) makes the voltage sag under load. Resistance rises as the battery
 * empties, so a tired battery sags much more on the same load.
 *
 * A match uses a few hundred mAh of a 3000 mAh pack, so the voltage at the end of TELEOP is
 * noticeably lower than at the start of AUTO, as on a real robot.
 */
public final class Battery {
    private static final int CELLS = 10;
    /** Resting cell voltage by state of charge, typical for NiMH (a fresh charge reads ~1.38 V/cell). */
    private static final double[][] CELL_VOLTS = {
            {0.00, 1.00}, {0.05, 1.12}, {0.10, 1.18}, {0.20, 1.22}, {0.40, 1.25},
            {0.60, 1.27}, {0.80, 1.30}, {0.95, 1.34}, {1.00, 1.38},
    };
    /**
     * How quickly the terminal voltage follows the load. The pack's chemistry and the hubs' input
     * capacitors smooth sudden current steps, and it keeps the voltage/current feedback (lower
     * voltage, lower motor current) from oscillating step to step.
     */
    private static final double TIME_CONSTANT = 0.01;

    private final double capacityAh;
    private final double resistance;
    private final double startCharge;
    private double charge;      // 0..1
    private double usedAh;
    private double volts;
    private double current;

    /** startVolts: the resting voltage when the battery goes in (sets how charged it is). */
    public Battery(double startVolts, double capacityAh, double resistanceOhms) {
        this.capacityAh = capacityAh;
        this.resistance = resistanceOhms;
        this.startCharge = chargeForVolts(startVolts);
        reset();
    }

    /** A freshly charged battery of the same kind goes in. */
    public void reset() {
        charge = startCharge;
        usedAh = 0;
        current = 0;
        volts = restingVolts();
    }

    /** Draws amps for dt seconds. */
    public void step(double amps, double dt) {
        current = amps;
        double ah = Math.max(0, amps) * dt / 3600.0;
        usedAh += ah;
        charge = Math.max(0, charge - ah / capacityAh);
        double loaded = Math.max(0, restingVolts() - resistanceNow() * amps);
        volts += (loaded - volts) * (dt / (TIME_CONSTANT + dt));
    }

    /** Voltage at the terminals (what the hubs and motors get). */
    public double volts() { return volts; }

    /** Resting voltage at the current state of charge. */
    public double restingVolts() { return CELLS * cellVolts(charge); }

    /** 0..1 */
    public double charge() { return charge; }

    public double usedMah() { return usedAh * 1000; }

    public double current() { return current; }

    /** Internal resistance plus wiring; up to three times as high when nearly empty. */
    public double resistanceNow() {
        double low = Math.max(0, 0.15 - charge) / 0.15;
        return resistance * (1 + 2 * low);
    }

    static double cellVolts(double charge) {
        double[][] t = CELL_VOLTS;
        if (charge <= t[0][0]) return t[0][1];
        for (int i = 1; i < t.length; i++) {
            if (charge <= t[i][0]) {
                double f = (charge - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
                return t[i - 1][1] + f * (t[i][1] - t[i - 1][1]);
            }
        }
        return t[t.length - 1][1];
    }

    /** The state of charge whose resting voltage is the given pack voltage. */
    static double chargeForVolts(double packVolts) {
        double v = packVolts / CELLS;
        double[][] t = CELL_VOLTS;
        if (v <= t[0][1]) return 0;
        for (int i = 1; i < t.length; i++) {
            if (v <= t[i][1]) {
                double f = (v - t[i - 1][1]) / (t[i][1] - t[i - 1][1]);
                return t[i - 1][0] + f * (t[i][0] - t[i - 1][0]);
            }
        }
        return 1;
    }
}
