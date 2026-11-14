package com.bylazar.field;

/** A field coordinate system: where its origin is relative to the field center, and how it's turned. */
public final class FieldPresetParams {
    private String name;
    private double offsetX;
    private double offsetY;
    private CanvasRotation rotation;
    private boolean flipX;
    private boolean flipY;
    private boolean reverseXY;

    public FieldPresetParams(String name, double offsetX, double offsetY, CanvasRotation rotation,
                             boolean flipX, boolean flipY, boolean reverseXY) {
        this.name = name;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.rotation = rotation;
        this.flipX = flipX;
        this.flipY = flipY;
        this.reverseXY = reverseXY;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getOffsetX() { return offsetX; }
    public void setOffsetX(double offsetX) { this.offsetX = offsetX; }
    public double getOffsetY() { return offsetY; }
    public void setOffsetY(double offsetY) { this.offsetY = offsetY; }
    public CanvasRotation getRotation() { return rotation; }
    public void setRotation(CanvasRotation rotation) { this.rotation = rotation; }
    public boolean getFlipX() { return flipX; }
    public void setFlipX(boolean flipX) { this.flipX = flipX; }
    public boolean getFlipY() { return flipY; }
    public void setFlipY(boolean flipY) { this.flipY = flipY; }
    public boolean getReverseXY() { return reverseXY; }
    public void setReverseXY(boolean reverseXY) { this.reverseXY = reverseXY; }

    @Override
    public String toString() {
        return "FieldPresetParams(name=" + name + ", offsetX=" + offsetX + ", offsetY=" + offsetY + ", rotation=" + rotation
                + ", flipX=" + flipX + ", flipY=" + flipY + ", reverseXY=" + reverseXY + ")";
    }
}
