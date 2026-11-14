package org.firstinspires.ftc.robotcore.external.navigation;

public class Quaternion {
    public float w, x, y, z;
    public long acquisitionTime;

    public Quaternion() {
        this(1, 0, 0, 0, 0);
    }

    public Quaternion(float w, float x, float y, float z, long acquisitionTime) {
        this.w = w;
        this.x = x;
        this.y = y;
        this.z = z;
        this.acquisitionTime = acquisitionTime;
    }

    public static Quaternion identityQuaternion() {
        return new Quaternion(1, 0, 0, 0, 0);
    }

    public float magnitude() {
        return (float) Math.sqrt(w * w + x * x + y * y + z * z);
    }

    public Quaternion normalized() {
        float m = magnitude();
        return new Quaternion(w / m, x / m, y / m, z / m, acquisitionTime);
    }

    /** (sic) the real SDK spells it this way. */
    public Quaternion congugate() {
        return new Quaternion(w, -x, -y, -z, acquisitionTime);
    }

    public Quaternion conjugate() {
        return congugate();
    }

    public Quaternion inverse() {
        return normalized().congugate();
    }

    public Quaternion multiply(Quaternion q, long acquisitionTime) {
        return new Quaternion(
                w * q.w - x * q.x - y * q.y - z * q.z,
                w * q.x + x * q.w + y * q.z - z * q.y,
                w * q.y - x * q.z + y * q.w + z * q.x,
                w * q.z + x * q.y - y * q.x + z * q.w,
                acquisitionTime);
    }

    /** This rotation as a 3 x 3 matrix. */
    public org.firstinspires.ftc.robotcore.external.matrices.MatrixF toMatrix() {
        double[][] r = org.ftctestbed.shim.Rotations.fromQuaternion(this);
        org.firstinspires.ftc.robotcore.external.matrices.GeneralMatrixF m = new org.firstinspires.ftc.robotcore.external.matrices.GeneralMatrixF(3, 3);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m.put(i, j, (float) r[i][j]);
        return m;
    }

    /** A vector turned by this rotation. */
    public org.firstinspires.ftc.robotcore.external.matrices.VectorF applyToVector(org.firstinspires.ftc.robotcore.external.matrices.VectorF vector) {
        return toMatrix().multiplied(vector);
    }

    public Orientation toOrientation(AxesReference axesReference, AxesOrder axesOrder, AngleUnit angleUnit) {
        return Orientation.getOrientation(toMatrix(), axesReference, axesOrder, angleUnit);
    }

    /** The rotation in a matrix's top-left 3 x 3. */
    public static Quaternion fromMatrix(org.firstinspires.ftc.robotcore.external.matrices.MatrixF m, long acquisitionTime) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = m.get(i, j);
        return org.ftctestbed.shim.Rotations.toQuaternion(r, acquisitionTime);
    }

    @Override
    public String toString() {
        return String.format("{w=%.3f, x=%.3f, y=%.3f, z=%.3f}", w, x, y, z);
    }
}
