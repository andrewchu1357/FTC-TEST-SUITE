package com.bylazar.telemetry;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Panels telemetry lines: addData/addLine/debug add lines, update() sends them. */
public final class TelemetryManager {
    private List<String> lines = new ArrayList<String>();
    private long updateInterval = 100;
    private long lastUpdate = 0;
    private final TelemetryWrapper wrapper = new TelemetryWrapper();

    public List<String> getLines() { return lines; }
    public void setLines(List<String> lines) { this.lines = lines; }
    public long getUpdateInterval() { return updateInterval; }
    public void setUpdateInterval(long updateInterval) { this.updateInterval = updateInterval; }
    public long getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }
    public long getTimeSinceLastUpdate() { return System.currentTimeMillis() - lastUpdate; }
    public boolean getShouldUpdateLines() { return getTimeSinceLastUpdate() >= updateInterval; }

    public synchronized void addData(String key, Object data) { lines.add(key + ": " + data); }
    public synchronized void addData(String key, String data) { lines.add(key + ": " + data); }
    public synchronized void addLine(String line) { lines.add(line); }

    public synchronized void debug(String... data) {
        for (String d : data) lines.add(d);
    }

    public synchronized void debug(Object... data) {
        for (Object d : data) lines.add(String.valueOf(d));
    }

    /** Sends the lines to Panels and starts over. */
    public synchronized void update() {
        PanelsTelemetry.INSTANCE.publish(new ArrayList<String>(lines));
        lastUpdate = System.currentTimeMillis();
        lines.clear();
    }

    /** Also shows the lines on the Driver Station (and updates it). */
    public void update(Telemetry telemetry) {
        List<String> copy;
        synchronized (this) {
            copy = new ArrayList<String>(lines);
        }
        for (String line : copy) telemetry.addLine(line);
        telemetry.update();
        update();
    }

    public TelemetryWrapper getWrapper() {
        return wrapper;
    }

    /** An FTC Telemetry whose items go to Panels. */
    public final class TelemetryWrapper implements Telemetry {
        private String captionSeparator = " : ", itemSeparator = " | ";

        public TelemetryWrapper() {}

        private Item item(String caption, String value) {
            addLine(caption + captionSeparator + value);
            final String[] entry = {caption, value};
            return new Item() {
                @Override public String getCaption() { return entry[0]; }
                @Override public Item setCaption(String caption) { entry[0] = caption; return this; }
                @Override public Item setValue(String format, Object... args) { entry[1] = String.format(Locale.US, format, args); return this; }
                @Override public Item setValue(Object value) { entry[1] = String.valueOf(value); return this; }
                @Override public <T> Item setValue(Func<T> producer) { entry[1] = String.valueOf(producer.value()); return this; }
                @Override public <T> Item setValue(String format, Func<T> producer) { entry[1] = String.format(Locale.US, format, producer.value()); return this; }
                @Override public Item setRetained(Boolean retained) { return this; }
                @Override public boolean isRetained() { return false; }
                @Override public Item addData(String c, String format, Object... args) { return TelemetryWrapper.this.addData(c, format, args); }
                @Override public Item addData(String c, Object value) { return TelemetryWrapper.this.addData(c, value); }
                @Override public <T> Item addData(String c, Func<T> producer) { return TelemetryWrapper.this.addData(c, producer); }
                @Override public <T> Item addData(String c, String format, Func<T> producer) { return TelemetryWrapper.this.addData(c, format, producer); }
            };
        }

        @Override public Item addData(String caption, String format, Object... args) { return item(caption, String.format(Locale.US, format, args)); }
        @Override public Item addData(String caption, Object value) { return item(caption, String.valueOf(value)); }
        @Override public <T> Item addData(String caption, Func<T> producer) { return item(caption, String.valueOf(producer.value())); }
        @Override public <T> Item addData(String caption, String format, Func<T> producer) { return item(caption, String.format(Locale.US, format, producer.value())); }
        @Override public boolean update() { TelemetryManager.this.update(); return true; }
        @Override public String getItemSeparator() { return itemSeparator; }
        @Override public void setItemSeparator(String s) { itemSeparator = s; }
        @Override public String getCaptionValueSeparator() { return captionSeparator; }
        @Override public void setCaptionValueSeparator(String s) { captionSeparator = s; }
        @Override public Line addLine() { return addLine(""); }

        @Override
        public Line addLine(String lineCaption) {
            if (!lineCaption.isEmpty()) TelemetryManager.this.addLine(lineCaption);
            return new Line() {
                @Override public Item addData(String c, String format, Object... args) { return TelemetryWrapper.this.addData(c, format, args); }
                @Override public Item addData(String c, Object value) { return TelemetryWrapper.this.addData(c, value); }
                @Override public <T> Item addData(String c, Func<T> producer) { return TelemetryWrapper.this.addData(c, producer); }
                @Override public <T> Item addData(String c, String format, Func<T> producer) { return TelemetryWrapper.this.addData(c, format, producer); }
            };
        }

        @Override public void setMsTransmissionInterval(int ms) { setUpdateInterval(ms); }
        @Override public boolean removeItem(Item item) { return false; }
        @Override public void clear() { synchronized (TelemetryManager.this) { lines.clear(); } }
        @Override public void clearAll() { clear(); }
        @Override public boolean removeLine(Line line) { return false; }
        @Override public boolean isAutoClear() { return true; }
        @Override public void setAutoClear(boolean autoClear) {}
        @Override public int getMsTransmissionInterval() { return (int) getUpdateInterval(); }
        @Override public void setDisplayFormat(DisplayFormat displayFormat) {}

        @Override
        public Log log() {
            return new Log() {
                @Override public int getCapacity() { return 9; }
                @Override public void setCapacity(int capacity) {}
                @Override public DisplayOrder getDisplayOrder() { return DisplayOrder.OLDEST_FIRST; }
                @Override public void setDisplayOrder(DisplayOrder order) {}
                @Override public void add(String entry) { TelemetryManager.this.addLine(entry); }
                @Override public void add(String format, Object... args) { add(String.format(Locale.US, format, args)); }
                @Override public void clear() {}
            };
        }

        @Override public Object addAction(Runnable action) { return action; }
        @Override public boolean removeAction(Object token) { return false; }
        @Override public void speak(String text) {}
        @Override public void speak(String text, String languageCode, String countryCode) {}
    }
}
