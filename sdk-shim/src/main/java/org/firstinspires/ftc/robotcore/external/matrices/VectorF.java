package org.firstinspires.ftc.robotcore.external.matrices;

import java.util.Locale;

/** A vector of floats (positions, translations). */
public class VectorF {
    protected float[] data;

    public static VectorF length(int length) {
        return new VectorF(new float[length]);
    }

    public VectorF(float[] data) { this.data = data; }
    public VectorF(float x) { this(new float[]{x}); }
    public VectorF(float x, float y) { this(new float[]{x, y}); }
    public VectorF(float x, float y, float z) { this(new float[]{x, y, z}); }
    public VectorF(float x, float y, float z, float w) { this(new float[]{x, y, z, w}); }

    public float[] getData() { return data; }
    public int length() { return data.length; }
    public float get(int index) { return data[index]; }
    public void put(int index, float value) { data[index] = value; }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < data.length; i++) b.append(i > 0 ? " " : "").append(String.format(Locale.US, "%.2f", data[i]));
        return b.append("}").toString();
    }

    /** The first three components scaled by the fourth (homogeneous), or a copy if there are three. */
    public VectorF normalized3D() {
        if (data.length == 3) return new VectorF(data.clone());
        float w = data[3];
        return new VectorF(data[0] / w, data[1] / w, data[2] / w);
    }

    public float magnitude() {
        return (float) Math.sqrt(dotProduct(this));
    }

    public float dotProduct(VectorF him) {
        if (him.length() != length()) throw new IllegalArgumentException("vectors of different lengths");
        double s = 0;
        for (int i = 0; i < data.length; i++) s += data[i] * him.data[i];
        return (float) s;
    }

    /** This vector as a row times the matrix. */
    public MatrixF multiplied(MatrixF him) {
        return new RowMatrixF(this).multiplied(him);
    }

    public MatrixF added(MatrixF addend) { return new ColumnMatrixF(this).added(addend); }

    public VectorF added(VectorF addend) {
        float[] r = new float[data.length];
        for (int i = 0; i < r.length; i++) r[i] = data[i] + addend.data[i];
        return new VectorF(r);
    }

    public void add(VectorF addend) {
        for (int i = 0; i < data.length; i++) data[i] += addend.data[i];
    }

    public MatrixF subtracted(MatrixF subtrahend) { return new ColumnMatrixF(this).subtracted(subtrahend); }

    public VectorF subtracted(VectorF subtrahend) {
        float[] r = new float[data.length];
        for (int i = 0; i < r.length; i++) r[i] = data[i] - subtrahend.data[i];
        return new VectorF(r);
    }

    public void subtract(VectorF subtrahend) {
        for (int i = 0; i < data.length; i++) data[i] -= subtrahend.data[i];
    }

    public VectorF multiplied(float scale) {
        float[] r = new float[data.length];
        for (int i = 0; i < r.length; i++) r[i] = data[i] * scale;
        return new VectorF(r);
    }

    public void multiply(float scale) {
        for (int i = 0; i < data.length; i++) data[i] *= scale;
    }
}
