package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.ftctestbed.sim.hardware.MotorPort;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Writes the robot's pose and every motor's power/encoder to CSV at a fixed rate. Motor
 * values are what robot code sees (after setDirection), so a log recorded on the real robot
 * with the same columns can be compared directly.
 */
public final class PoseLogger implements Consumer<Simulation>, AutoCloseable {
    private static final double IN = 0.0254;

    private final BufferedWriter out;
    private final double interval;
    private final Path file;
    private final java.util.List<MotorPort> ports = new java.util.ArrayList<>();
    private final java.util.List<DcMotor> devices = new java.util.ArrayList<>();
    private double startTime = Double.NaN;
    private double next = 0;

    public PoseLogger(Path file, Simulation sim, double intervalSeconds) {
        this.file = file;
        this.interval = intervalSeconds;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            out = Files.newBufferedWriter(file);
            StringBuilder header = new StringBuilder("t_s,x_in,y_in,heading_deg,vx_in_s,vy_in_s,omega_deg_s,battery_v");
            for (MotorPort p : sim.motorPorts().values()) {
                ports.add(p);
                devices.add(sim.hardwareMap.tryGet(DcMotor.class, p.name));
                header.append(',').append(p.name).append("_power,").append(p.name).append("_ticks");
            }
            out.write(header.toString());
            out.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path file() { return file; }

    @Override
    public void accept(Simulation sim) {
        double t = sim.clock.seconds();
        if (Double.isNaN(startTime)) startTime = t;
        if (t - startTime + 1e-9 < next) return;
        next += interval;
        Snapshot s = sim.snapshot();
        StringBuilder row = new StringBuilder(String.format(Locale.US, "%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f,%.2f",
                t - startTime, s.x() / IN, s.y() / IN, Math.toDegrees(s.heading()),
                s.vx() / IN, s.vy() / IN, Math.toDegrees(s.omega()), s.batteryVolts()));
        for (int i = 0; i < ports.size(); i++) {
            DcMotor d = devices.get(i);
            int sign = d != null && d.getDirection() == DcMotorSimple.Direction.REVERSE ? -1 : 1;
            row.append(String.format(Locale.US, ",%.3f,%d", sign * ports.get(i).getPower(), sign * ports.get(i).livePosition()));
        }
        try {
            out.write(row.toString());
            out.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
