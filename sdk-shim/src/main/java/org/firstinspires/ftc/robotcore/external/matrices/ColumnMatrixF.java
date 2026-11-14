package org.firstinspires.ftc.robotcore.external.matrices;

/** A vector seen as a one-column matrix. */
public class ColumnMatrixF extends MatrixF {
    private final VectorF vector;

    public ColumnMatrixF(VectorF vector) {
        super(vector.length(), 1);
        this.vector = vector;
    }

    @Override public float get(int row, int col) { return vector.get(row); }
    @Override public void put(int row, int col, float value) { vector.put(row, value); }
    @Override public MatrixF emptyMatrix(int numRows, int numCols) { return new GeneralMatrixF(numRows, numCols); }
}
