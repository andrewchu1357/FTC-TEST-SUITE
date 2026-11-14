package com.acmerobotics.dashboard;

import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FTC Dashboard. Simulator stand-in: there's no web page, but packets your code sends (and
 * dashboard telemetry) are kept so the simulator window can show them. Camera streaming and
 * live config editing are accepted and ignored.
 */
public class FtcDashboard {
    private static final FtcDashboard INSTANCE = new FtcDashboard();

    private volatile TelemetryPacket lastPacket;
    private volatile long lastPacketNanos;
    private final DashboardTelemetry telemetry = new DashboardTelemetry();

    public static FtcDashboard getInstance() {
        return INSTANCE;
    }

    public void sendTelemetryPacket(TelemetryPacket packet) {
        lastPacket = packet.simCopy();
        lastPacketNanos = System.nanoTime();
    }

    public void clearTelemetry() {
        lastPacket = null;
    }

    public Telemetry getTelemetry() {
        return telemetry;
    }

    public void setTelemetryTransmissionInterval(int ms) {}

    public int getTelemetryTransmissionInterval() { return 100; }

    public void startCameraStream(Object source, double maxFps) {}

    public void stopCameraStream() {}

    public void sendImage(Object bitmap) {}

    public void updateConfig() {}

    /** Live configuration editing: accepted; there's no dashboard page to edit from. */
    public void withConfigRoot(CustomVariableConsumer function) {
        function.accept(configRoot);
    }

    public void addConfigVariable(String category, String name, Object provider) {}

    private final com.acmerobotics.dashboard.config.variable.CustomVariable configRoot =
            new com.acmerobotics.dashboard.config.variable.CustomVariable();

    public boolean isEnabled() { return true; }

    /** Simulator: the most recent packet, or null if none was sent in the last 2 s. */
    public TelemetryPacket simLastPacket() {
        TelemetryPacket p = lastPacket;
        return p != null && System.nanoTime() - lastPacketNanos < 2_000_000_000L ? p : null;
    }

    /** Simulator: forget everything (a new OpMode starts). */
    public void simReset() {
        lastPacket = null;
        telemetry.clearAll();
    }

    /** The dashboard's Telemetry: items become a packet on update(). */
    private final class DashboardTelemetry implements Telemetry {
        private final List<String[]> items = new ArrayList<String[]>();
        private final List<String> logLines = new ArrayList<String>();
        private String captionSeparator = " : ", itemSeparator = " | ";

        private Item item(final String caption, String value) {
            final String[] entry = {caption, value};
            synchronized (this) {
                items.add(entry);
            }
            return new SimpleItem(entry);
        }

        @Override public Item addData(String caption, String format, Object... args) { return item(caption, String.format(Locale.US, format, args)); }
        @Override public Item addData(String caption, Object value) { return item(caption, String.valueOf(value)); }
        @Override public <T> Item addData(String caption, Func<T> producer) { return item(caption, String.valueOf(producer.value())); }
        @Override public <T> Item addData(String caption, String format, Func<T> producer) { return item(caption, String.format(Locale.US, format, producer.value())); }
        @Override public synchronized boolean removeItem(Item item) { return false; }
        @Override public synchronized void clear() { items.clear(); }
        @Override public synchronized void clearAll() { items.clear(); logLines.clear(); }
        @Override public Object addAction(Runnable action) { return action; }
        @Override public boolean removeAction(Object token) { return false; }
        @Override public void speak(String text) {}
        @Override public void speak(String text, String languageCode, String countryCode) {}

        @Override
        public synchronized boolean update() {
            TelemetryPacket p = new TelemetryPacket();
            for (String[] e : items) p.put(e[0], e[1]);
            for (String l : logLines) p.addLine(l);
            sendTelemetryPacket(p);
            items.clear();
            return true;
        }

        @Override public Line addLine() { return addLine(""); }

        @Override
        public Line addLine(final String caption) {
            if (!caption.isEmpty()) item(caption, "");
            return new Line() {
                @Override public Item addData(String c, String format, Object... args) { return DashboardTelemetry.this.addData(c, format, args); }
                @Override public Item addData(String c, Object value) { return DashboardTelemetry.this.addData(c, value); }
                @Override public <T> Item addData(String c, Func<T> producer) { return DashboardTelemetry.this.addData(c, producer); }
                @Override public <T> Item addData(String c, String format, Func<T> producer) { return DashboardTelemetry.this.addData(c, format, producer); }
            };
        }

        @Override public boolean removeLine(Line line) { return false; }
        @Override public boolean isAutoClear() { return true; }
        @Override public void setAutoClear(boolean autoClear) {}
        @Override public int getMsTransmissionInterval() { return 100; }
        @Override public void setMsTransmissionInterval(int ms) {}
        @Override public String getItemSeparator() { return itemSeparator; }
        @Override public void setItemSeparator(String s) { itemSeparator = s; }
        @Override public String getCaptionValueSeparator() { return captionSeparator; }
        @Override public void setCaptionValueSeparator(String s) { captionSeparator = s; }
        @Override public void setDisplayFormat(DisplayFormat f) {}

        @Override
        public Log log() {
            return new Log() {
                @Override public int getCapacity() { return 9; }
                @Override public void setCapacity(int capacity) {}
                @Override public DisplayOrder getDisplayOrder() { return DisplayOrder.OLDEST_FIRST; }
                @Override public void setDisplayOrder(DisplayOrder order) {}
                @Override public void add(String entry) { synchronized (DashboardTelemetry.this) { logLines.add(entry); } }
                @Override public void add(String format, Object... args) { add(String.format(Locale.US, format, args)); }
                @Override public void clear() { synchronized (DashboardTelemetry.this) { logLines.clear(); } }
            };
        }

        private final class SimpleItem implements Item {
            private final String[] entry;
            private boolean retained;

            SimpleItem(String[] entry) { this.entry = entry; }

            @Override public String getCaption() { return entry[0]; }
            @Override public Item setCaption(String caption) { entry[0] = caption; return this; }
            @Override public Item setValue(String format, Object... args) { entry[1] = String.format(Locale.US, format, args); return this; }
            @Override public Item setValue(Object value) { entry[1] = String.valueOf(value); return this; }
            @Override public <T> Item setValue(Func<T> producer) { entry[1] = String.valueOf(producer.value()); return this; }
            @Override public <T> Item setValue(String format, Func<T> producer) { entry[1] = String.format(Locale.US, format, producer.value()); return this; }
            @Override public Item setRetained(Boolean retained) { this.retained = retained != null && retained; return this; }
            @Override public boolean isRetained() { return retained; }
            @Override public Item addData(String caption, String format, Object... args) { return DashboardTelemetry.this.addData(caption, format, args); }
            @Override public Item addData(String caption, Object value) { return DashboardTelemetry.this.addData(caption, value); }
            @Override public <T> Item addData(String caption, Func<T> producer) { return DashboardTelemetry.this.addData(caption, producer); }
            @Override public <T> Item addData(String caption, String format, Func<T> producer) { return DashboardTelemetry.this.addData(caption, format, producer); }
        }
    }
}
