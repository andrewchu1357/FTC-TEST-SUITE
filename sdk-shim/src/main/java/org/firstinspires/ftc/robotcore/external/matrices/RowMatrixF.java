package org.firstinspires.ftc.robotcore.external.matrices;

/** A vector seen as a one-row matrix. */
public class RowMatrixF extends MatrixF {
    private final VectorF vector;

    public RowMatrixF(VectorF vector) {
        super(1, vector.length());
        this.vector = vector;
    }

    @Override public float get(int row, int col) { return vector.get(col); }
    @Override public void put(int row, int col, float value) { vector.put(col, value); }
    @Override public MatrixF emptyMatrix(int numRows, int numCols) { return new GeneralMatrixF(numRows, numCols); }
}
