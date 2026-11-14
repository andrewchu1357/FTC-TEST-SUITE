package com.bylazar.telemetry;

import java.util.Collections;
import java.util.List;

/**
 * Panels telemetry (Pedro Pathing's dashboard). Simulator stand-in: there's no Panels web page;
 * lines sent with update() are kept so the simulator shows them under the Driver Station
 * telemetry, and update(telemetry) copies them to the Driver Station as Panels does.
 */
public final class PanelsTelemetry {
    public static final PanelsTelemetry INSTANCE = new PanelsTelemetry();

    private final TelemetryManager telemetry = new TelemetryManager();
    private volatile List<String> published = Collections.emptyList();

    private PanelsTelemetry() {}

    public TelemetryManager getTelemetry() {
        return telemetry;
    }

    public TelemetryManager.TelemetryWrapper getFtcTelemetry() {
        return telemetry.getWrapper();
    }

    void publish(List<String> lines) {
        published = Collections.unmodifiableList(lines);
    }

    /** Simulator: the lines last sent to Panels. */
    public List<String> simLines() {
        return published;
    }

    /** Simulator: forget everything (a new OpMode starts). */
    public void simReset() {
        telemetry.getLines().clear();
        published = Collections.emptyList();
    }
}
