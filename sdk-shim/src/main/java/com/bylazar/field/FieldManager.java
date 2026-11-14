package com.bylazar.field;

import com.acmerobotics.dashboard.canvas.Canvas;

import java.util.UUID;

/**
 * Draws on the field with a cursor: moveCursor(x, y), then circle(r), line(x2, y2) or
 * rect(w, h); update() shows the drawing. Coordinates are in the preset given to setOffsets
 * (Pedro Pathing's: origin in a field corner); the simulator turns them into field inches.
 */
public final class FieldManager {
    private Canvas canvas = new Canvas();
    private volatile Canvas lastCanvas = new Canvas();
    private FieldPresetParams offsets = FieldPresets.INSTANCE.getPANELS();
    private double cursorX, cursorY, cursorHeading;
    private String currentFill = "", currentOutlineFill = "", lastFill = null, lastStroke = null;
    private double currentOutlineWidth = 1;
    private long lastUpdate = 0;

    public FieldManager() {}

    public void init() {}
    public long getUpdateInterval() { return 50; }
    public long getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }
    public long getTimeSinceLastUpdate() { return System.currentTimeMillis() - lastUpdate; }
    public boolean getShouldUpdateCanvas() { return getTimeSinceLastUpdate() >= getUpdateInterval(); }

    public synchronized Canvas getCanvas() { return canvas; }
    public synchronized void setCanvas(Canvas canvas) { this.canvas = canvas; }
    public Canvas getLastCanvas() { return lastCanvas; }
    public void setLastCanvas(Canvas canvas) { this.lastCanvas = canvas; }

    public double getCursorX() { return cursorX; }
    public void setCursorX(double x) { cursorX = x; }
    public double getCursorY() { return cursorY; }
    public void setCursorY(double y) { cursorY = y; }
    public double getCursorHeading() { return cursorHeading; }
    public void setCursorHeading(double h) { cursorHeading = h; }
    public String getCurrentFill() { return currentFill; }
    public void setCurrentFill(String fill) { currentFill = fill; }
    public String getCurrentOutlineFill() { return currentOutlineFill; }
    public void setCurrentOutlineFill(String fill) { currentOutlineFill = fill; }
    public double getCurrentOutlineWidth() { return currentOutlineWidth; }
    public void setCurrentOutlineWidth(double width) { currentOutlineWidth = width; }
    public Style getCurrentStyle() { return new Style(currentFill, currentOutlineFill, currentOutlineWidth); }

    public void moveCursor(double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    public void setFill(String fill) { currentFill = fill; }
    public void setOutlineFill(String fill) { currentOutlineFill = fill; }
    public void setOutlineWidth(double width) { currentOutlineWidth = width; }

    public void setOutline(String fill, double width) {
        currentOutlineFill = fill;
        currentOutlineWidth = width;
    }

    public void setStyle(String fill, String outlineFill, double outlineWidth) {
        currentFill = fill;
        currentOutlineFill = outlineFill;
        currentOutlineWidth = outlineWidth;
    }

    public void setStyle(Style style) { setStyle(style.getFill(), style.getOutlineFill(), style.getOutlineWidth()); }
    public void clearFill() { currentFill = ""; }
    public void clearOutline() { currentOutlineFill = ""; }

    public void clearStyle() {
        clearFill();
        clearOutline();
    }

    public synchronized void circle(double radius) {
        double[] p = toField(cursorX, cursorY);
        if (filled()) fill().fillCircle(p[0], p[1], radius);
        if (outlined()) stroke().strokeCircle(p[0], p[1], radius);
    }

    public synchronized void line(double x2, double y2) {
        double[] a = toField(cursorX, cursorY), b = toField(x2, y2);
        if (outlined() || filled()) stroke(outlined() ? currentOutlineFill : currentFill).strokeLine(a[0], a[1], b[0], b[1]);
    }

    public synchronized void rect(double width, double height) {
        double[] a = toField(cursorX, cursorY), b = toField(cursorX + width, cursorY + height);
        double x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]), w = Math.abs(b[0] - a[0]), h = Math.abs(b[1] - a[1]);
        if (filled()) fill().fillRect(x, y, w, h);
        if (outlined()) stroke().strokeRect(x, y, w, h);
    }

    public UUID registerImage(ImagePreset preset) { return UUID.randomUUID(); }
    public UUID registerBase64Image(String base64) { return UUID.randomUUID(); }
    public UUID registerImage(String path) { return UUID.randomUUID(); }
    public void img(double width, double height, UUID id) {}
    public void setBase64Background(String base64) {}
    public void setBackground(String path) {}
    public void setBackground(UUID id) {}
    public void setBackground(ImagePreset preset) {}

    /** Shows what was drawn since the last update. */
    public synchronized void update() {
        lastCanvas = canvas;
        canvas = new Canvas();
        lastFill = null;
        lastStroke = null;
        lastUpdate = System.currentTimeMillis();
    }

    public void setOffsets(FieldPresetParams preset) {
        offsets = preset;
    }

    /** Simulator: the drawing to show (field inches, center origin), or null. */
    public Canvas simCanvas() {
        Canvas c = lastCanvas;
        return c.getOperations().isEmpty() ? null : c;
    }

    /** Simulator: forget everything (a new OpMode starts). */
    public synchronized void simReset() {
        canvas = new Canvas();
        lastCanvas = new Canvas();
        offsets = FieldPresets.INSTANCE.getPANELS();
    }

    private boolean filled() { return !isNone(currentFill); }
    private boolean outlined() { return !isNone(currentOutlineFill) && currentOutlineWidth > 0; }

    private static boolean isNone(String color) {
        return color == null || color.isEmpty() || color.equalsIgnoreCase("transparent");
    }

    private Canvas fill() {
        if (!currentFill.equals(lastFill)) canvas.setFill(currentFill);
        lastFill = currentFill;
        return canvas;
    }

    private Canvas stroke() {
        return stroke(currentOutlineFill);
    }

    private Canvas stroke(String color) {
        if (!color.equals(lastStroke)) {
            canvas.setStroke(color);
            canvas.setStrokeWidth((int) Math.max(1, Math.round(currentOutlineWidth)));
        }
        lastStroke = color;
        return canvas;
    }

    /** Preset coordinates to field inches (center origin). Pedro Pathing: x_ftc = y - 72, y_ftc = 72 - x. */
    private double[] toField(double x, double y) {
        FieldPresetParams p = offsets;
        if (p != null && p.getRotation() == CanvasRotation.DEG_90 && p.getOffsetX() == -72 && p.getOffsetY() == -72) {
            return new double[]{y - 72, 72 - x};
        }
        return new double[]{x + (p == null ? 0 : p.getOffsetX()), y + (p == null ? 0 : p.getOffsetY())};
    }
}
