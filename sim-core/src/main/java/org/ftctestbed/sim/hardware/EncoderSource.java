package org.ftctestbed.sim.hardware;

/** Something wired into a motor port's encoder input other than the motor itself (a dead wheel). */
public interface EncoderSource {
    double ticks();

    double ticksPerSecond();
}
