package org.firstinspires.ftc.robotcore.external.navigation;

public class Orientation {
    public AxesReference axesReference;
    public AxesOrder axesOrder;
    public AngleUnit angleUnit;
    public float firstAngle;
    public float secondAngle;
    public float thirdAngle;
    public long acquisitionTime;

    public Orientation() {
        this(AxesReference.EXTRINSIC, AxesOrder.XYZ, AngleUnit.RADIANS, 0, 0, 0, 0);
    }

    public Orientation(AxesReference axesReference, AxesOrder axesOrder, AngleUnit angleUnit,
                       float firstAngle, float secondAngle, float thirdAngle, long acquisitionTime) {
        this.axesReference = axesReference;
        this.axesOrder = axesOrder;
        this.angleUnit = angleUnit;
        this.firstAngle = firstAngle;
        this.secondAngle = secondAngle;
        this.thirdAngle = thirdAngle;
        this.acquisitionTime = acquisitionTime;
    }

    /** Which of the two Euler angle solutions getOrientation() returns. */
    public enum AngleSet { THEONE, THEOTHER }

    /** The rotation these angles describe, as a 4 x 4 transform. */
    public org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix getRotationMatrix() {
        return getRotationMatrix(axesReference, axesOrder, angleUnit, firstAngle, secondAngle, thirdAngle);
    }

    public static org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix getRotationMatrix(
            AxesReference axesReference, AxesOrder axesOrder, AngleUnit unit, float firstAngle, float secondAngle, float thirdAngle) {
        double[][] r = org.ftctestbed.shim.Rotations.fromEuler(axesReference, axesOrder,
                unit.toRadians(firstAngle), unit.toRadians(secondAngle), unit.toRadians(thirdAngle));
        org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix m = new org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m.put(i, j, (float) r[i][j]);
        return m;
    }

    /** The Euler angles (in this reference and order) of a rotation matrix (its top-left 3 x 3). */
    public static Orientation getOrientation(org.firstinspires.ftc.robotcore.external.matrices.MatrixF rot,
                                             AxesReference axesReference, AxesOrder axesOrder, AngleUnit unit) {
        // As the SDK does: of the two solutions, the one with the smaller angles.
        Orientation one = getOrientation(rot, axesReference, axesOrder, unit, AngleSet.THEONE);
        Orientation other = getOrientation(rot, axesReference, axesOrder, unit, AngleSet.THEOTHER);
        org.firstinspires.ftc.robotcore.external.matrices.VectorF vOne = new org.firstinspires.ftc.robotcore.external.matrices.VectorF(one.firstAngle, one.secondAngle, one.thirdAngle);
        org.firstinspires.ftc.robotcore.external.matrices.VectorF vOther = new org.firstinspires.ftc.robotcore.external.matrices.VectorF(other.firstAngle, other.secondAngle, other.thirdAngle);
        return vOne.magnitude() <= vOther.magnitude() ? one : other;
    }

    /**
     * The SDK's own extraction (ported from SDK 12.0), so ambiguous cases pick the same of the two
     * solutions: intrinsic orders are worked out as the reversed extrinsic order.
     */
    public static Orientation getOrientation(org.firstinspires.ftc.robotcore.external.matrices.MatrixF rot,
                                             AxesReference axesReference, AxesOrder axesOrder, AngleUnit unit, AngleSet angleSet) {
        float firstAngle, secondAngle, thirdAngle;
        if (axesReference == AxesReference.INTRINSIC) {
            return getOrientation(rot, axesReference.reverse(), axesOrder.reverse(), unit, angleSet).toAxesReference(axesReference);
        }
        boolean one = angleSet == AngleSet.THEONE;
        switch (axesOrder) {
            default: { // XZX
                float test = rot.get(0, 0);
                if (test == 1.0f) {
                    secondAngle = 0; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(2, 1), rot.get(1, 1)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = (float) Math.PI; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(1, 2), rot.get(2, 2)));
                } else {
                    secondAngle = (float) (one ? Math.acos(rot.get(0, 0)) : -Math.acos(rot.get(0, 0)));
                    firstAngle = (float) Math.atan2(rot.get(0, 2) / Math.sin(secondAngle), -rot.get(0, 1) / Math.sin(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(2, 0) / Math.sin(secondAngle), rot.get(1, 0) / Math.sin(secondAngle));
                }
                break;
            }
            case XYX: {
                float test = rot.get(0, 0);
                if (test == 1.0f) {
                    secondAngle = 0; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(2, 1), rot.get(1, 1)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = (float) Math.PI; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(-rot.get(1, 2), rot.get(1, 1)));
                } else {
                    secondAngle = (float) (one ? Math.acos(rot.get(0, 0)) : -Math.acos(rot.get(0, 0)));
                    firstAngle = (float) Math.atan2(rot.get(0, 1) / Math.sin(secondAngle), rot.get(0, 2) / Math.sin(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(1, 0) / Math.sin(secondAngle), -rot.get(2, 0) / Math.sin(secondAngle));
                }
                break;
            }
            case YXY: {
                float test = rot.get(1, 1);
                if (test == 1.0f) {
                    secondAngle = 0; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(0, 2), rot.get(0, 0)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = (float) Math.PI; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(0, 2), rot.get(0, 0)));
                } else {
                    secondAngle = (float) (one ? Math.acos(rot.get(1, 1)) : -Math.acos(rot.get(1, 1)));
                    firstAngle = (float) Math.atan2(rot.get(1, 0) / Math.sin(secondAngle), -rot.get(1, 2) / Math.sin(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(0, 1) / Math.sin(secondAngle), rot.get(2, 1) / Math.sin(secondAngle));
                }
                break;
            }
            case YZY: {
                float test = rot.get(1, 1);
                if (test == 1.0f) {
                    secondAngle = 0; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(0, 2), rot.get(0, 0)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = (float) Math.PI; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(-rot.get(0, 2), rot.get(2, 2)));
                } else {
                    secondAngle = (float) (one ? Math.acos(rot.get(1, 1)) : -Math.acos(rot.get(1, 1)));
                    firstAngle = (float) Math.atan2(rot.get(1, 2) / Math.sin(secondAngle), rot.get(1, 0) / Math.sin(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(2, 1) / Math.sin(secondAngle), -rot.get(0, 1) / Math.sin(secondAngle));
                }
                break;
            }
            case ZYZ: {
                float test = rot.get(2, 2);
                if (test == 1.0f) {
                    secondAngle = 0; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(1, 0), rot.get(0, 0)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = (float) Math.PI; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(0, 1), rot.get(1, 1)));
                } else {
                    secondAngle = (float) (one ? Math.acos(rot.get(2, 2)) : -Math.acos(rot.get(2, 2)));
                    firstAngle = (float) Math.atan2(rot.get(2, 1) / Math.sin(secondAngle), -rot.get(2, 0) / Math.sin(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(1, 2) / Math.sin(secondAngle), rot.get(0, 2) / Math.sin(secondAngle));
                }
                break;
            }
            case ZXZ: {
                float test = rot.get(2, 2);
                if (test == 1.0f) {
                    secondAngle = 0; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(1, 0), rot.get(0, 0)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = (float) Math.PI; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(-rot.get(0, 1), rot.get(0, 0)));
                } else {
                    secondAngle = (float) (one ? Math.acos(rot.get(2, 2)) : -Math.acos(rot.get(2, 2)));
                    firstAngle = (float) Math.atan2(rot.get(2, 0) / Math.sin(secondAngle), rot.get(2, 1) / Math.sin(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(0, 2) / Math.sin(secondAngle), -rot.get(1, 2) / Math.sin(secondAngle));
                }
                break;
            }
            case XZY: {
                float test = rot.get(1, 0);
                if (test == 1.0f) {
                    secondAngle = 1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(0, 2), rot.get(2, 2)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = -1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(2, 1), rot.get(0, 1)));
                } else {
                    secondAngle = (float) (one ? Math.asin(rot.get(1, 0)) : Math.PI - Math.asin(rot.get(1, 0)));
                    firstAngle = (float) Math.atan2(-rot.get(1, 2) / Math.cos(secondAngle), rot.get(1, 1) / Math.cos(secondAngle));
                    thirdAngle = (float) Math.atan2(-rot.get(2, 0) / Math.cos(secondAngle), rot.get(0, 0) / Math.cos(secondAngle));
                }
                break;
            }
            case XYZ: {
                float test = rot.get(2, 0);
                if (test == -1.0f) {
                    secondAngle = 1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(0, 1), rot.get(0, 2)));
                } else if (test == 1.0f) {
                    secondAngle = -1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(-rot.get(0, 1), rot.get(1, 1)) - firstAngle);
                } else {
                    secondAngle = (float) (one ? -Math.asin(rot.get(2, 0)) : Math.PI + Math.asin(rot.get(2, 0)));
                    firstAngle = (float) Math.atan2(rot.get(2, 1) / Math.cos(secondAngle), rot.get(2, 2) / Math.cos(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(1, 0) / Math.cos(secondAngle), rot.get(0, 0) / Math.cos(secondAngle));
                }
                break;
            }
            case YXZ: {
                float test = rot.get(2, 1);
                if (test == 1.0f) {
                    secondAngle = 1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(0, 2), rot.get(0, 0)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = -1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(0, 2), rot.get(0, 0)));
                } else {
                    secondAngle = (float) (one ? Math.asin(rot.get(2, 1)) : Math.PI - Math.asin(rot.get(2, 1)));
                    firstAngle = (float) Math.atan2(-rot.get(2, 0) / Math.cos(secondAngle), rot.get(2, 2) / Math.cos(secondAngle));
                    thirdAngle = (float) Math.atan2(-rot.get(0, 1) / Math.cos(secondAngle), rot.get(1, 1) / Math.cos(secondAngle));
                }
                break;
            }
            case YZX: {
                float test = rot.get(0, 1);
                if (test == -1.0f) {
                    secondAngle = 1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(1, 2), rot.get(1, 0)));
                } else if (test == 1.0f) {
                    secondAngle = -1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(-rot.get(1, 2), rot.get(2, 2)) - firstAngle);
                } else {
                    secondAngle = (float) (one ? -Math.asin(rot.get(0, 1)) : Math.PI + Math.asin(rot.get(0, 1)));
                    firstAngle = (float) Math.atan2(rot.get(0, 2) / Math.cos(secondAngle), rot.get(0, 0) / Math.cos(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(2, 1) / Math.cos(secondAngle), rot.get(1, 1) / Math.cos(secondAngle));
                }
                break;
            }
            case ZYX: {
                float test = rot.get(0, 2);
                if (test == 1.0f) {
                    secondAngle = 1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(rot.get(1, 0), rot.get(1, 1)) - firstAngle);
                } else if (test == -1.0f) {
                    secondAngle = -1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(1, 0), rot.get(1, 1)));
                } else {
                    secondAngle = (float) (one ? Math.asin(rot.get(0, 2)) : Math.PI - Math.asin(rot.get(0, 2)));
                    firstAngle = (float) Math.atan2(-rot.get(0, 1) / Math.cos(secondAngle), rot.get(0, 0) / Math.cos(secondAngle));
                    thirdAngle = (float) Math.atan2(-rot.get(1, 2) / Math.cos(secondAngle), rot.get(2, 2) / Math.cos(secondAngle));
                }
                break;
            }
            case ZXY: {
                float test = rot.get(1, 2);
                if (test == -1.0f) {
                    secondAngle = 1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (firstAngle - Math.atan2(rot.get(2, 0), rot.get(0, 0)));
                } else if (test == 1.0f) {
                    secondAngle = -1.5707964f; firstAngle = 0;
                    thirdAngle = (float) (Math.atan2(-rot.get(0, 1), rot.get(0, 0)) - firstAngle);
                } else {
                    secondAngle = (float) (one ? -Math.asin(rot.get(1, 2)) : Math.PI + Math.asin(rot.get(1, 2)));
                    firstAngle = (float) Math.atan2(rot.get(1, 0) / Math.cos(secondAngle), rot.get(1, 1) / Math.cos(secondAngle));
                    thirdAngle = (float) Math.atan2(rot.get(0, 2) / Math.cos(secondAngle), rot.get(2, 2) / Math.cos(secondAngle));
                }
                break;
            }
        }
        return new Orientation(axesReference, axesOrder, unit, unit.fromRadians(firstAngle), unit.fromRadians(secondAngle), unit.fromRadians(thirdAngle), 0);
    }

    /** The same rotation described in another axes order. */
    public Orientation toAxesOrder(AxesOrder axesOrder) {
        if (this.axesOrder == axesOrder) return this;
        return getOrientation(getRotationMatrix(), axesReference, axesOrder, angleUnit);
    }

    /** The same rotation described with the other kind of axes: extrinsic XYZ (a, b, c) is intrinsic ZYX (c, b, a). */
    public Orientation toAxesReference(AxesReference axesReference) {
        if (this.axesReference == axesReference) return this;
        return new Orientation(this.axesReference.reverse(), axesOrder.reverse(), angleUnit, thirdAngle, secondAngle, firstAngle, acquisitionTime);
    }

    public Orientation toAngleUnit(AngleUnit angleUnit) {
        if (angleUnit == this.angleUnit) return this;
        return new Orientation(axesReference, axesOrder, angleUnit,
                angleUnit.fromUnit(this.angleUnit, firstAngle),
                angleUnit.fromUnit(this.angleUnit, secondAngle),
                angleUnit.fromUnit(this.angleUnit, thirdAngle),
                acquisitionTime);
    }

    @Override
    public String toString() {
        String unit = angleUnit == AngleUnit.DEGREES ? "°" : "rad";
        return String.format("{%s %s %.1f%s %.1f%s %.1f%s}", axesReference, axesOrder,
                firstAngle, unit, secondAngle, unit, thirdAngle, unit);
    }
}
