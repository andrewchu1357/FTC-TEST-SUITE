package android.util;

/** A width and height in pixels. */
public final class Size {
    private final int width, height;

    public Size(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public static Size parseSize(String string) {
        String[] p = string.trim().split("[x*]");
        return new Size(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
    }

    @Override public boolean equals(Object o) { return o instanceof Size && ((Size) o).width == width && ((Size) o).height == height; }
    @Override public int hashCode() { return width * 31 + height; }
    @Override public String toString() { return width + "x" + height; }
}
