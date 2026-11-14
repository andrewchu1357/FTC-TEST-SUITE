package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

public interface DcMotor extends DcMotorSimple {
    MotorConfigurationType getMotorType();

    void setMotorType(MotorConfigurationType motorType);

    DcMotorController getController();

    int getPortNumber();

    enum ZeroPowerBehavior {
        UNKNOWN, BRAKE, FLOAT
    }

    void setZeroPowerBehavior(ZeroPowerBehavior zeroPowerBehavior);

    ZeroPowerBehavior getZeroPowerBehavior();

    @Deprecated
    void setPowerFloat();

    boolean getPowerFloat();

    void setTargetPosition(int position);

    int getTargetPosition();

    boolean isBusy();

    int getCurrentPosition();

    enum RunMode {
        RUN_WITHOUT_ENCODER,
        RUN_USING_ENCODER,
        RUN_TO_POSITION,
        STOP_AND_RESET_ENCODER,
        @Deprecated RUN_WITHOUT_ENCODERS,
        @Deprecated RUN_USING_ENCODERS,
        @Deprecated RESET_ENCODERS;

        public RunMode migrate() {
            switch (this) {
                case RUN_USING_ENCODERS: return RUN_USING_ENCODER;
                case RUN_WITHOUT_ENCODERS: return RUN_WITHOUT_ENCODER;
                case RESET_ENCODERS: return STOP_AND_RESET_ENCODER;
                default: return this;
            }
        }

        public boolean isPIDMode() {
            return this == RUN_USING_ENCODER || this == RUN_USING_ENCODERS || this == RUN_TO_POSITION;
        }
    }

    void setMode(RunMode mode);

    RunMode getMode();
}
