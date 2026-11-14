package com.acmerobotics.dashboard.telemetry;

import com.acmerobotics.dashboard.canvas.Canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** FTC Dashboard telemetry packet: key/value data, log lines and a field overlay. Simulator stand-in. */
public class TelemetryPacket {
    private final Map<String, String> data = new LinkedHashMap<String, String>();
    private final List<String> log = new ArrayList<String>();
    private final Canvas fieldOverlay;
    private long timestamp;

    public TelemetryPacket() {
        fieldOverlay = new Canvas();
    }

    private TelemetryPacket(TelemetryPacket from) {
        data.putAll(from.data);
        log.addAll(from.log);
        fieldOverlay = from.fieldOverlay.simCopy();
        timestamp = from.timestamp;
    }

    public TelemetryPacket(boolean drawDefaultField) {
        this();
    }

    public void put(String key, Object value) {
        data.put(key, String.valueOf(value));
    }

    public void putAll(Map<String, ?> map) {
        for (Map.Entry<String, ?> e : map.entrySet()) put(e.getKey(), e.getValue());
    }

    public void addLine(String line) {
        log.add(line);
    }

    public void clearLines() {
        log.clear();
    }

    public long addTimestamp() {
        timestamp = System.currentTimeMillis();
        return timestamp;
    }

    public Canvas fieldOverlay() {
        return fieldOverlay;
    }

    public Canvas field() {
        return fieldOverlay;
    }

    /** Simulator: a copy, so code can keep changing (or reuse) the packet it sent. */
    public TelemetryPacket simCopy() {
        return new TelemetryPacket(this);
    }

    /** Simulator: the data in this packet. */
    public Map<String, String> simData() {
        return Collections.unmodifiableMap(data);
    }

    /** Simulator: the log lines in this packet. */
    public List<String> simLines() {
        return Collections.unmodifiableList(log);
    }
}
