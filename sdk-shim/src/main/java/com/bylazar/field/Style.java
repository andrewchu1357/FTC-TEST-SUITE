package com.bylazar.field;

/** A drawing style: fill color, outline color and outline width ("" or "transparent": none). */
public final class Style {
    private final String fill;
    private final String outlineFill;
    private final double outlineWidth;

    public Style(String fill, String outlineFill, double outlineWidth) {
        this.fill = fill;
        this.outlineFill = outlineFill;
        this.outlineWidth = outlineWidth;
    }

    public String getFill() { return fill; }
    public String getOutlineFill() { return outlineFill; }
    public double getOutlineWidth() { return outlineWidth; }
    public String component1() { return fill; }
    public String component2() { return outlineFill; }
    public double component3() { return outlineWidth; }
    public Style copy(String fill, String outlineFill, double outlineWidth) { return new Style(fill, outlineFill, outlineWidth); }

    @Override
    public String toString() {
        return "Style(fill=" + fill + ", outlineFill=" + outlineFill + ", outlineWidth=" + outlineWidth + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Style)) return false;
        Style s = (Style) o;
        return eq(fill, s.fill) && eq(outlineFill, s.outlineFill) && Double.compare(outlineWidth, s.outlineWidth) == 0;
    }

    @Override
    public int hashCode() {
        return (fill == null ? 0 : fill.hashCode()) * 31 + (outlineFill == null ? 0 : outlineFill.hashCode()) * 17
                + Double.valueOf(outlineWidth).hashCode();
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
