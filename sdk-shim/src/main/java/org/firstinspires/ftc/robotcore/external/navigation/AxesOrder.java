package org.firstinspires.ftc.robotcore.external.navigation;

public enum AxesOrder {
    XZX(new int[]{0, 2, 0}), XYX(new int[]{0, 1, 0}), YXY(new int[]{1, 0, 1}),
    YZY(new int[]{1, 2, 1}), ZYZ(new int[]{2, 1, 2}), ZXZ(new int[]{2, 0, 2}),
    XZY(new int[]{0, 2, 1}), XYZ(new int[]{0, 1, 2}), YXZ(new int[]{1, 0, 2}),
    YZX(new int[]{1, 2, 0}), ZYX(new int[]{2, 1, 0}), ZXY(new int[]{2, 0, 1});

    private final int[] indices;

    AxesOrder(int[] indices) {
        this.indices = indices;
    }

    public int[] indices() {
        return indices.clone();
    }

    public Axis[] axes() {
        return new Axis[]{Axis.fromIndex(indices[0]), Axis.fromIndex(indices[1]), Axis.fromIndex(indices[2])};
    }

    public AxesOrder reverse() {
        for (AxesOrder o : values()) {
            if (o.indices[0] == indices[2] && o.indices[1] == indices[1] && o.indices[2] == indices[0]) return o;
        }
        return this;
    }
}
