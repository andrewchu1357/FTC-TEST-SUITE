package org.ftctestbed.sim.opmode;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Telemetry with Driver Station semantics: update() publishes, auto-clear wipes non-retained items. */
public class SimTelemetry implements Telemetry {
    private final List<LineImpl> lines = new ArrayList<>();
    private final Map<Object, Runnable> actions = new IdentityHashMap<>();
    private final LogImpl log = new LogImpl();
    private volatile List<String> published = Collections.emptyList();
    private volatile List<String> spoken = Collections.emptyList();
    private boolean autoClear = true;
    private int msTransmissionInterval = 250;
    private String itemSeparator = " | ";
    private String captionValueSeparator = " : ";
    private DisplayFormat displayFormat = DisplayFormat.CLASSIC;
    /** Like the SDK: a double or float value shows at most 4 decimals ("0.####"), as on a Control Hub (US locale). */
    private final java.text.DecimalFormat decimalFormat =
            new java.text.DecimalFormat("0.####", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));

    /** Lines as last shown on the "Driver Station". */
    public List<String> publishedLines() { return published; }

    public DisplayFormat displayFormat() { return displayFormat; }

    private final class ItemImpl implements Item {
        final LineImpl line;
        String caption;
        Supplier<String> value;
        boolean retained;

        ItemImpl(LineImpl line, String caption, Supplier<String> value) {
            this.line = line;
            this.caption = caption;
            this.value = value;
        }

        String render() {
            String v;
            try {
                v = value.get();
            } catch (RuntimeException e) {
                v = "<" + e.getClass().getSimpleName() + ">";
            }
            return caption + captionValueSeparator + v;
        }

        @Override public String getCaption() { synchronized (SimTelemetry.this) { return caption; } }
        @Override public Item setCaption(String caption) { synchronized (SimTelemetry.this) { this.caption = caption; return this; } }
        @Override public Item setValue(String format, Object... args) { synchronized (SimTelemetry.this) { value = formatted(format, args); return this; } }
        @Override public Item setValue(Object v) { synchronized (SimTelemetry.this) { value = constant(v); return this; } }
        @Override public <T> Item setValue(Func<T> producer) { synchronized (SimTelemetry.this) { value = () -> String.valueOf(producer.value()); return this; } }
        @Override public <T> Item setValue(String format, Func<T> producer) { synchronized (SimTelemetry.this) { value = () -> String.format(format, producer.value()); return this; } }
        @Override public Item setRetained(Boolean r) { synchronized (SimTelemetry.this) { retained = r != null && r; return this; } }
        @Override public boolean isRetained() { synchronized (SimTelemetry.this) { return retained; } }
        @Override public Item addData(String c, String f, Object... a) { return line.addData(c, f, a); }
        @Override public Item addData(String c, Object v) { return line.addData(c, v); }
        @Override public <T> Item addData(String c, Func<T> p) { return line.addData(c, p); }
        @Override public <T> Item addData(String c, String f, Func<T> p) { return line.addData(c, f, p); }
    }

    private final class LineImpl implements Line {
        final String caption;
        final List<ItemImpl> items = new ArrayList<>();

        LineImpl(String caption) {
            this.caption = caption;
        }

        private Item add(String caption, Supplier<String> v) {
            synchronized (SimTelemetry.this) {
                ItemImpl item = new ItemImpl(this, caption, v);
                items.add(item);
                return item;
            }
        }

        @Override public Item addData(String c, String f, Object... a) { return add(c, formatted(f, a)); }
        @Override public Item addData(String c, Object v) { return add(c, constant(v)); }
        @Override public <T> Item addData(String c, Func<T> p) { return add(c, () -> String.valueOf(p.value())); }
        @Override public <T> Item addData(String c, String f, Func<T> p) { return add(c, () -> String.format(f, p.value())); }

        String render() {
            StringBuilder sb = new StringBuilder(caption);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(itemSeparator);
                sb.append(items.get(i).render());
            }
            return sb.toString();
        }

        boolean retainedOnly() {
            items.removeIf(i -> !i.retained);
            return !items.isEmpty();
        }
    }

    private Supplier<String> constant(Object v) {
        String s;
        synchronized (decimalFormat) {
            s = v instanceof Double || v instanceof Float ? decimalFormat.format(v) : String.valueOf(v);
        }
        return () -> s;
    }

    private static Supplier<String> formatted(String format, Object... args) {
        String s;
        try {
            s = String.format(format, args);
        } catch (RuntimeException e) {
            s = format + " <format error: " + e.getMessage() + ">";
        }
        String result = s;
        return () -> result;
    }

    private Item topLevel(String caption, Supplier<String> v) {
        LineImpl line = new LineImpl("");
        lines.add(line);
        return line.add(caption, v);
    }

    @Override public synchronized Item addData(String caption, String format, Object... args) { return topLevel(caption, formatted(format, args)); }
    @Override public synchronized Item addData(String caption, Object value) { return topLevel(caption, constant(value)); }

    @Override
    public void setNumDecimalPlaces(int minDecimalPlaces, int maxDecimalPlaces) {
        synchronized (decimalFormat) {
            decimalFormat.setMinimumFractionDigits(minDecimalPlaces);
            decimalFormat.setMaximumFractionDigits(maxDecimalPlaces);
        }
    }
    @Override public synchronized <T> Item addData(String caption, Func<T> p) { return topLevel(caption, () -> String.valueOf(p.value())); }
    @Override public synchronized <T> Item addData(String caption, String format, Func<T> p) { return topLevel(caption, () -> String.format(format, p.value())); }

    @Override
    public synchronized boolean removeItem(Item item) {
        for (LineImpl l : lines) if (l.items.remove(item)) return true;
        return false;
    }

    @Override
    public synchronized void clear() {
        lines.removeIf(l -> !l.retainedOnly());
    }

    @Override
    public synchronized void clearAll() {
        lines.clear();
        actions.clear();
    }

    @Override
    public synchronized Object addAction(Runnable action) {
        Object token = new Object();
        actions.put(token, action);
        return token;
    }

    @Override public synchronized boolean removeAction(Object token) { return actions.remove(token) != null; }

    @Override public void speak(String text) { speak(text, null, null); }

    @Override
    public synchronized void speak(String text, String languageCode, String countryCode) {
        List<String> s = new ArrayList<>(spoken);
        s.add(text);
        spoken = s;
    }

    @Override
    public synchronized boolean update() {
        for (Runnable r : new ArrayList<>(actions.values())) r.run();
        List<String> out = new ArrayList<>();
        for (LineImpl l : lines) out.add(l.render());
        out.addAll(log.render());
        published = Collections.unmodifiableList(out);
        if (autoClear) clear();
        return true;
    }

    @Override
    public synchronized Line addLine() { return addLine(""); }

    @Override
    public synchronized Line addLine(String lineCaption) {
        LineImpl l = new LineImpl(lineCaption);
        lines.add(l);
        return l;
    }

    @Override public synchronized boolean removeLine(Line line) { return lines.remove(line); }
    @Override public synchronized boolean isAutoClear() { return autoClear; }
    @Override public synchronized void setAutoClear(boolean autoClear) { this.autoClear = autoClear; }
    @Override public synchronized int getMsTransmissionInterval() { return msTransmissionInterval; }
    @Override public synchronized void setMsTransmissionInterval(int ms) { msTransmissionInterval = ms; }
    @Override public synchronized String getItemSeparator() { return itemSeparator; }
    @Override public synchronized void setItemSeparator(String s) { itemSeparator = s; }
    @Override public synchronized String getCaptionValueSeparator() { return captionValueSeparator; }
    @Override public synchronized void setCaptionValueSeparator(String s) { captionValueSeparator = s; }
    @Override public synchronized void setDisplayFormat(DisplayFormat f) { displayFormat = f; }
    @Override public Log log() { return log; }

    private final class LogImpl implements Log {
        private final List<String> entries = new ArrayList<>();
        private int capacity = 9;
        private DisplayOrder order = DisplayOrder.OLDEST_FIRST;

        @Override public int getCapacity() { synchronized (SimTelemetry.this) { return capacity; } }

        @Override
        public void setCapacity(int c) {
            synchronized (SimTelemetry.this) {
                capacity = c;
                trim();
            }
        }

        @Override public DisplayOrder getDisplayOrder() { synchronized (SimTelemetry.this) { return order; } }
        @Override public void setDisplayOrder(DisplayOrder o) { synchronized (SimTelemetry.this) { order = o; } }

        @Override
        public void add(String entry) {
            synchronized (SimTelemetry.this) {
                entries.add(entry);
                trim();
                published = withLog();
            }
        }

        @Override public void add(String format, Object... args) { add(String.format(format, args)); }

        @Override
        public void clear() {
            synchronized (SimTelemetry.this) {
                entries.clear();
            }
        }

        private void trim() {
            while (entries.size() > capacity) entries.remove(0);
        }

        List<String> render() {
            List<String> r = new ArrayList<>(entries);
            if (order == DisplayOrder.NEWEST_FIRST) Collections.reverse(r);
            return r;
        }

        private List<String> withLog() {
            List<String> out = new ArrayList<>();
            for (LineImpl l : lines) out.add(l.render());
            out.addAll(render());
            return Collections.unmodifiableList(out);
        }
    }
}
