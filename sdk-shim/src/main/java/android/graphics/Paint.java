package android.graphics;

/** How Canvas draws (a stand-in: the simulator has no camera preview to draw on). */
public class Paint {
    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    public Paint() {}
    public void setColor(int color) {}
    public void setStyle(Style style) {}
    public void setStrokeWidth(float width) {}
    public void setTextSize(float size) {}
    public void setAntiAlias(boolean aa) {}
    public void setAlpha(int alpha) {}
}
