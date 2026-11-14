package org.firstinspires.ftc.robotcore.external.matrices;

/** A rectangular part of another matrix (changes go through to it). */
public class SliceMatrixF extends MatrixF {
    protected final MatrixF matrix;
    protected final int row, col;

    public SliceMatrixF(MatrixF matrix, int row, int col, int numRows, int numCols) {
        super(numRows, numCols);
        this.matrix = matrix;
        this.row = row;
        this.col = col;
        if (row + numRows > matrix.numRows() || col + numCols > matrix.numCols()) throw new IllegalArgumentException("slice out of range");
    }

    @Override public float get(int r, int c) { return matrix.get(row + r, col + c); }
    @Override public void put(int r, int c, float value) { matrix.put(row + r, col + c, value); }
    @Override public MatrixF emptyMatrix(int numRows, int numCols) { return matrix.emptyMatrix(numRows, numCols); }
}
