package com.qualcomm.hardware.digitalchickenlabs;

import com.qualcomm.robotcore.hardware.HardwareDevice;

/**
 * Digital Chicken Labs' OctoQuad: 8 encoder inputs (quadrature, or pulse width for absolute
 * encoders), and on the FTC Edition MK2 an IMU and a localizer that tracks the robot's pose from
 * two dead wheels at 1.92 kHz (firmware 3). The simulator's OctoQuad (sim-core SimOctoQuad)
 * implements this.
 */
public interface OctoQuad extends HardwareDevice {
    byte OCTOQUAD_CHIP_ID = 0x51;
    int SUPPORTED_FW_VERSION_MAJ = 3;
    int ENCODER_FIRST = 0;
    int ENCODER_LAST = 7;
    int NUM_ENCODERS = 8;
    int MIN_VELOCITY_MEASUREMENT_INTERVAL_MS = 1;
    int MAX_VELOCITY_MEASUREMENT_INTERVAL_MS = 255;
    int MIN_PULSE_WIDTH_US = 0;
    int MAX_PULSE_WIDTH_US = 0xFFFF;

    class FirmwareVersion {
        public final int maj;
        public final int min;
        public final int eng;

        public FirmwareVersion(int maj, int min, int eng) {
            this.maj = maj;
            this.min = min;
            this.eng = eng;
        }

        @Override
        public String toString() {
            return String.format("%d.%d.%d", maj, min, eng);
        }
    }

    enum EncoderDirection { FORWARD, REVERSE }

    enum ChannelBankConfig { ALL_QUADRATURE, ALL_PULSE_WIDTH, BANK1_QUADRATURE_BANK2_PULSE_WIDTH }

    enum CachingMode { MANUAL, AUTO }

    enum I2cRecoveryMode { NONE, MODE_1_PERIPH_RST_ON_FRAME_ERR, MODE_2_M1_PLUS_SCL_IDLE_ONESHOT_TGL }

    enum LocalizerStatus { INVALID, NOT_INITIALIZED, WARMING_UP_IMU, CALIBRATING_IMU, RUNNING, FAULT_NO_IMU }

    enum LocalizerYawAxis { UNDECIDED, X, X_INV, Y, Y_INV, Z, Z_INV }

    /** Positions (counts) and velocities (counts per velocity sample interval) of all 8 channels. */
    class EncoderDataBlock {
        public int[] positions = new int[NUM_ENCODERS];
        public short[] velocities = new short[NUM_ENCODERS];
        public boolean crcOk;

        public EncoderDataBlock() {}

        public boolean isDataValid() {
            return crcOk;
        }

        public void copyTo(EncoderDataBlock other) {
            System.arraycopy(positions, 0, other.positions, 0, NUM_ENCODERS);
            System.arraycopy(velocities, 0, other.velocities, 0, NUM_ENCODERS);
            other.crcOk = crcOk;
        }
    }

    /** The localizer's pose: mm and radians, +X out the robot's front at reset, +Y left, CCW positive. */
    class LocalizerDataBlock {
        public LocalizerStatus localizerStatus;
        public boolean crcOk;
        public float heading_rad;
        public short posX_mm;
        public short posY_mm;
        public short velX_mmS;
        public short velY_mmS;
        public float velHeading_radS;

        public LocalizerDataBlock() {}

        public boolean isDataValid() {
            return crcOk && localizerStatus == LocalizerStatus.RUNNING;
        }
    }

    class ChannelPulseWidthParams {
        public int min_length_us;
        public int max_length_us;

        public ChannelPulseWidthParams() {}

        public ChannelPulseWidthParams(int minLengthUs, int maxLengthUs) {
            this.min_length_us = minLengthUs;
            this.max_length_us = maxLengthUs;
        }
    }

    byte getChipId();

    FirmwareVersion getFirmwareVersion();

    String getFirmwareVersionString();

    void setSingleEncoderDirection(int idx, EncoderDirection dir);

    EncoderDirection getSingleEncoderDirection(int idx);

    void setAllEncoderDirections(boolean[] reverse);

    void setChannelBankConfig(ChannelBankConfig config);

    ChannelBankConfig getChannelBankConfig();

    void readAllEncoderData(EncoderDataBlock out);

    EncoderDataBlock readAllEncoderData();

    void setCachingMode(CachingMode mode);

    void refreshCache();

    int readSinglePosition_Caching(int idx);

    short readSingleVelocity_Caching(int idx);

    short readSingleVelocity(int idx);

    int readSinglePosition(int idx);

    void resetSinglePosition(int idx);

    void resetAllPositions();

    void resetMultiplePositions(boolean[] resets);

    void resetMultiplePositions(int... indices);

    void setSingleVelocitySampleInterval(int idx, int intvlms);

    int getSingleVelocitySampleInterval(int idx);

    void setAllVelocitySampleIntervals(int intvlms);

    void setSingleChannelPulseWidthParams(int idx, int minLengthUs, int maxLengthUs);

    void setSingleChannelPulseWidthParams(int idx, ChannelPulseWidthParams params);

    ChannelPulseWidthParams getSingleChannelPulseWidthParams(int idx);

    void setSingleChannelPulseWidthTracksWrap(int idx, boolean trackWrap);

    boolean getSingleChannelPulseWidthTracksWrap(int idx);

    void setAllChannelsPulseWidthTracksWrap(boolean[] trackWrap);

    void setLocalizerPortX(int port);

    void setLocalizerPortY(int port);

    void setLocalizerCountsPerMM_X(float ticksPerMM_x);

    void setLocalizerCountsPerMM_Y(float ticksPerMM_y);

    void setLocalizerTcpOffsetMM_X(float tcpOffsetMM_X);

    void setLocalizerTcpOffsetMM_Y(float tcpOffsetMM_Y);

    void setLocalizerImuHeadingScalar(float headingScalar);

    void setLocalizerVelocityIntervalMS(int ms);

    void setAllLocalizerParameters(int portX, int portY, float ticksPerMM_x, float ticksPerMM_y,
                                   float tcpOffsetMM_X, float tcpOffsetMM_Y, float headingScalar, int velocityIntervalMs);

    void readLocalizerData(LocalizerDataBlock out);

    LocalizerDataBlock readLocalizerData();

    void readLocalizerDataAndAllEncoderData(LocalizerDataBlock localizerOut, EncoderDataBlock encoderOut);

    void setLocalizerPose(int posX_mm, int posY_mm, float heading_rad);

    void setLocalizerHeading(float headingRad);

    LocalizerStatus getLocalizerStatus();

    LocalizerYawAxis getLocalizerHeadingAxisChoice();

    void resetLocalizerAndCalibrateIMU();

    void setI2cRecoveryMode(I2cRecoveryMode mode);

    I2cRecoveryMode getI2cRecoveryMode();

    int getUptimeMs();

    int getIMUDiagParam();

    void saveParametersToFlash();

    void resetEverything();
}
