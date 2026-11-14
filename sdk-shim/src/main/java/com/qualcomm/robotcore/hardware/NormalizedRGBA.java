package com.qualcomm.robotcore.hardware;

public class NormalizedRGBA {
    public float red;
    public float green;
    public float blue;
    public float alpha;

    public int toColor() {
        int a = clip(alpha), r = clip(red), g = clip(green), b = clip(blue);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clip(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255)));
    }
}
