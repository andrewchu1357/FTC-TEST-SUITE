package org.firstinspires.ftc.robotcore.external.matrices;

/** A matrix of any size, stored row by row. */
public class GeneralMatrixF extends RowMajorMatrixF {
    private final float[] data;

    public GeneralMatrixF(int numRows, int numCols) {
        this(numRows, numCols, new float[numRows * numCols]);
    }

    public GeneralMatrixF(int numRows, int numCols, float[] data) {
        super(numRows, numCols);
        if (data.length != numRows * numCols) throw new IllegalArgumentException("data length " + data.length + " for a " + numRows + "x" + numCols + " matrix");
        this.data = data;
    }

    @Override public float[] getData() { return data; }
    @Override public MatrixF emptyMatrix(int numRows, int numCols) { return new GeneralMatrixF(numRows, numCols); }

    @Override
    public GeneralMatrixF transposed() {
        GeneralMatrixF m = new GeneralMatrixF(numCols, numRows);
        for (int r = 0; r < numRows; r++) for (int c = 0; c < numCols; c++) m.put(c, r, get(r, c));
        return m;
    }
}
