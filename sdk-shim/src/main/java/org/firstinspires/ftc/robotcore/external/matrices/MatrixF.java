package org.firstinspires.ftc.robotcore.external.matrices;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;

import java.util.Locale;

/** A matrix of floats. A 4 x 4 one is often a transform: rotation plus a translation in its last column. */
public abstract class MatrixF {
    protected int numRows;
    protected int numCols;

    public MatrixF(int numRows, int numCols) {
        if (numRows <= 0 || numCols <= 0) throw new IllegalArgumentException("matrix dimensions must be positive");
        this.numRows = numRows;
        this.numCols = numCols;
    }

    public SliceMatrixF slice(int row, int col, int numRows, int numCols) { return new SliceMatrixF(this, row, col, numRows, numCols); }
    public SliceMatrixF slice(int numRows, int numCols) { return slice(0, 0, numRows, numCols); }

    public static MatrixF identityMatrix(int dim) { return diagonalMatrix(dim, 1f); }

    public static MatrixF diagonalMatrix(int dim, float scale) {
        GeneralMatrixF m = new GeneralMatrixF(dim, dim);
        for (int i = 0; i < dim; i++) m.put(i, i, scale);
        return m;
    }

    public static MatrixF diagonalMatrix(VectorF v) {
        GeneralMatrixF m = new GeneralMatrixF(v.length(), v.length());
        for (int i = 0; i < v.length(); i++) m.put(i, i, v.get(i));
        return m;
    }

    public abstract MatrixF emptyMatrix(int numRows, int numCols);

    public int numRows() { return numRows; }
    public int numCols() { return numCols; }

    public abstract float get(int row, int col);
    public abstract void put(int row, int col, float value);

    public VectorF getRow(int row) {
        float[] r = new float[numCols];
        for (int c = 0; c < numCols; c++) r[c] = get(row, c);
        return new VectorF(r);
    }

    public VectorF getColumn(int col) {
        float[] r = new float[numRows];
        for (int i = 0; i < numRows; i++) r[i] = get(i, col);
        return new VectorF(r);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (int r = 0; r < numRows; r++) {
            b.append(r == 0 ? "{" : " ");
            for (int c = 0; c < numCols; c++) b.append(c > 0 ? " " : "").append(String.format(Locale.US, "%.2f", get(r, c)));
            b.append(r == numRows - 1 ? "}" : "\n");
        }
        return b.toString();
    }

    /** A point transformed by this matrix (a 3-vector through a 4 x 4 transform gets w = 1 and comes back as a 3-vector). */
    public VectorF transform(VectorF him) {
        if (him.length() == numCols - 1) {
            VectorF h = new VectorF(java.util.Arrays.copyOf(him.getData(), numCols));
            h.put(numCols - 1, 1);
            return multiplied(h).normalized3D();
        }
        return multiplied(him);
    }

    /** "{x y z} {first second third}" with angles XYZ extrinsic in degrees. */
    public String formatAsTransform() {
        return formatAsTransform(AxesReference.EXTRINSIC, AxesOrder.XYZ, AngleUnit.DEGREES);
    }

    public String formatAsTransform(AxesReference reference, AxesOrder order, AngleUnit unit) {
        VectorF t = getTranslation();
        Orientation o = Orientation.getOrientation(this, reference, order, unit);
        return String.format(Locale.US, "{%.2f %.2f %.2f} %s", t.get(0), t.get(1), t.get(2), o.toString());
    }

    public MatrixF transposed() {
        MatrixF m = emptyMatrix(numCols, numRows);
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) m.put(c, r, get(r, c));
        return m;
    }

    public void multiply(MatrixF him) {
        MatrixF p = multiplied(him);
        if (p.numRows != numRows || p.numCols != numCols) throw new IllegalArgumentException("in-place multiply needs a square result of the same size");
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) put(r, c, p.get(r, c));
    }

    public MatrixF multiplied(MatrixF him) {
        if (numCols != him.numRows) throw new IllegalArgumentException("matrix dimensions don't match: " + numCols + " vs " + him.numRows);
        MatrixF m = emptyMatrix(numRows, him.numCols);
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < him.numCols; c++) {
                double s = 0;
                for (int k = 0; k < numCols; k++) s += get(r, k) * him.get(k, c);
                m.put(r, c, (float) s);
            }
        }
        return m;
    }

    public MatrixF multiplied(float scale) {
        MatrixF m = emptyMatrix(numRows, numCols);
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) m.put(r, c, get(r, c) * scale);
        return m;
    }

    public void multiply(float scale) {
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) put(r, c, get(r, c) * scale);
    }

    public VectorF multiplied(VectorF him) {
        return multiplied(him.getData());
    }

    public void multiply(VectorF him) {
        multiply(new ColumnMatrixF(him));
    }

    public VectorF multiplied(float[] him) {
        if (him.length == numCols - 1 && numRows == numCols) return transform(new VectorF(him));
        if (him.length != numCols) throw new IllegalArgumentException("vector length " + him.length + " vs " + numCols + " columns");
        float[] r = new float[numRows];
        for (int i = 0; i < numRows; i++) {
            double s = 0;
            for (int k = 0; k < numCols; k++) s += get(i, k) * him[k];
            r[i] = (float) s;
        }
        return new VectorF(r);
    }

    public void multiply(float[] him) {
        multiply(new VectorF(him));
    }

    public VectorF toVector() {
        if (numCols == 1) return getColumn(0);
        if (numRows == 1) return getRow(0);
        float[] r = new float[numRows * numCols];
        for (int i = 0; i < numRows; i++) for (int c = 0; c < numCols; c++) r[i * numCols + c] = get(i, c);
        return new VectorF(r);
    }

    public MatrixF added(MatrixF addend) {
        MatrixF m = emptyMatrix(numRows, numCols);
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) m.put(r, c, get(r, c) + addend.get(r, c));
        return m;
    }

    public void add(MatrixF addend) {
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) put(r, c, get(r, c) + addend.get(r, c));
    }

    public MatrixF subtracted(MatrixF subtrahend) {
        MatrixF m = emptyMatrix(numRows, numCols);
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) m.put(r, c, get(r, c) - subtrahend.get(r, c));
        return m;
    }

    public void subtract(MatrixF subtrahend) {
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) put(r, c, get(r, c) - subtrahend.get(r, c));
    }

    public MatrixF added(VectorF addend) { return added(new ColumnMatrixF(addend)); }
    public MatrixF added(float[] addend) { return added(new VectorF(addend)); }
    public MatrixF subtracted(VectorF subtrahend) { return subtracted(new ColumnMatrixF(subtrahend)); }
    public MatrixF subtracted(float[] subtrahend) { return subtracted(new VectorF(subtrahend)); }
    public void add(VectorF addend) { add(new ColumnMatrixF(addend)); }
    public void add(float[] addend) { add(new VectorF(addend)); }
    public void subtract(VectorF subtrahend) { subtract(new ColumnMatrixF(subtrahend)); }
    public void subtract(float[] subtrahend) { subtract(new VectorF(subtrahend)); }

    /** The translation of a transform: the last column, without its last row. */
    public VectorF getTranslation() {
        float[] r = new float[numRows - 1];
        for (int i = 0; i < r.length; i++) r[i] = get(i, numCols - 1);
        return new VectorF(r);
    }

    /** The inverse (Gauss-Jordan elimination); square matrices only. */
    public MatrixF inverted() {
        if (numRows != numCols) throw new IllegalArgumentException("only square matrices can be inverted");
        int n = numRows;
        double[][] a = new double[n][2 * n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) a[r][c] = get(r, c);
            a[r][n + r] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            if (Math.abs(a[pivot][col]) < 1e-12) throw new IllegalArgumentException("matrix is singular");
            double[] t = a[col]; a[col] = a[pivot]; a[pivot] = t;
            double p = a[col][col];
            for (int c = 0; c < 2 * n; c++) a[col][c] /= p;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = a[r][col];
                if (f != 0) for (int c = 0; c < 2 * n; c++) a[r][c] -= f * a[col][c];
            }
        }
        MatrixF m = emptyMatrix(n, n);
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) m.put(r, c, (float) a[r][n + c]);
        return m;
    }
}
