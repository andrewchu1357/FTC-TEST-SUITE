package org.firstinspires.ftc.robotcore.external.matrices;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;

/**
 * A 4 x 4 transform, stored column by column as OpenGL does. translated/rotated/scaled return
 * this transform followed (on the right) by the new one.
 */
public class OpenGLMatrix extends ColumnMajorMatrixF {
    protected float[] data;

    public OpenGLMatrix() {
        super(4, 4);
        data = new float[16];
        for (int i = 0; i < 4; i++) data[i * 5] = 1;
    }

    public OpenGLMatrix(float[] data) {
        super(4, 4);
        if (data.length != 16) throw new IllegalArgumentException("an OpenGLMatrix has 16 values");
        this.data = data;
    }

    public OpenGLMatrix(MatrixF him) {
        this();
        for (int r = 0; r < Math.min(4, him.numRows()); r++) for (int c = 0; c < Math.min(4, him.numCols()); c++) put(r, c, him.get(r, c));
    }

    @Override public MatrixF emptyMatrix(int numRows, int numCols) {
        return numRows == 4 && numCols == 4 ? new OpenGLMatrix(new float[16]) : new GeneralMatrixF(numRows, numCols);
    }

    /** A rotation of angle about the axis (dx, dy, dz). */
    public static OpenGLMatrix rotation(AngleUnit unit, float angle, float dx, float dy, float dz) {
        double a = unit.toRadians(angle), len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double x = dx / len, y = dy / len, z = dz / len, c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        OpenGLMatrix m = new OpenGLMatrix();
        m.put(0, 0, (float) (t * x * x + c)); m.put(0, 1, (float) (t * x * y - s * z)); m.put(0, 2, (float) (t * x * z + s * y));
        m.put(1, 0, (float) (t * x * y + s * z)); m.put(1, 1, (float) (t * y * y + c)); m.put(1, 2, (float) (t * y * z - s * x));
        m.put(2, 0, (float) (t * x * z - s * y)); m.put(2, 1, (float) (t * y * z + s * x)); m.put(2, 2, (float) (t * z * z + c));
        return m;
    }

    public static OpenGLMatrix rotation(AxesReference reference, AxesOrder order, AngleUnit unit, float first, float second, float third) {
        return new Orientation(reference, order, unit, first, second, third, 0).getRotationMatrix();
    }

    public static OpenGLMatrix translation(float dx, float dy, float dz) {
        OpenGLMatrix m = new OpenGLMatrix();
        m.put(0, 3, dx);
        m.put(1, 3, dy);
        m.put(2, 3, dz);
        return m;
    }

    public static OpenGLMatrix identityMatrix() {
        return new OpenGLMatrix();
    }

    @Override public float[] getData() { return data; }

    public void scale(float sx, float sy, float sz) { multiply(scaling(sx, sy, sz)); }
    public void scale(float s) { scale(s, s, s); }
    public void translate(float dx, float dy, float dz) { multiply(translation(dx, dy, dz)); }
    public void rotate(AngleUnit unit, float angle, float dx, float dy, float dz) { multiply(rotation(unit, angle, dx, dy, dz)); }
    public void rotate(AxesReference reference, AxesOrder order, AngleUnit unit, float first, float second, float third) {
        multiply(rotation(reference, order, unit, first, second, third));
    }

    public OpenGLMatrix scaled(float sx, float sy, float sz) { return multiplied(scaling(sx, sy, sz)); }
    public OpenGLMatrix scaled(float s) { return scaled(s, s, s); }
    public OpenGLMatrix translated(float dx, float dy, float dz) { return multiplied(translation(dx, dy, dz)); }
    public OpenGLMatrix rotated(AngleUnit unit, float angle, float dx, float dy, float dz) { return multiplied(rotation(unit, angle, dx, dy, dz)); }
    public OpenGLMatrix rotated(AxesReference reference, AxesOrder order, AngleUnit unit, float first, float second, float third) {
        return multiplied(rotation(reference, order, unit, first, second, third));
    }

    private static OpenGLMatrix scaling(float sx, float sy, float sz) {
        OpenGLMatrix m = new OpenGLMatrix();
        m.put(0, 0, sx);
        m.put(1, 1, sy);
        m.put(2, 2, sz);
        return m;
    }

    @Override public OpenGLMatrix inverted() { return new OpenGLMatrix(super.inverted()); }
    @Override public OpenGLMatrix transposed() { return new OpenGLMatrix(super.transposed()); }

    public OpenGLMatrix multiplied(OpenGLMatrix him) { return new OpenGLMatrix(super.multiplied((MatrixF) him)); }

    @Override
    public MatrixF multiplied(MatrixF him) {
        return him instanceof OpenGLMatrix ? multiplied((OpenGLMatrix) him) : super.multiplied(him);
    }

    public void multiply(OpenGLMatrix him) {
        float[] r = multiplied(him).getData();
        System.arraycopy(r, 0, data, 0, 16);
    }

    @Override
    public void multiply(MatrixF him) {
        if (him instanceof OpenGLMatrix) multiply((OpenGLMatrix) him);
        else super.multiply(him);
    }
}
