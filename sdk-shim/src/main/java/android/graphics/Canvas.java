package android.graphics;

/**
 * The camera preview a VisionProcessor draws on in onDrawFrame. The simulator's preview is its own
 * camera view, so these drawing calls do nothing.
 */
public class Canvas {
    public Canvas() {}
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {}
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {}
    public void drawCircle(float cx, float cy, float radius, Paint paint) {}
    public void drawText(String text, float x, float y, Paint paint) {}
    public int getWidth() { return 640; }
    public int getHeight() { return 480; }
}
