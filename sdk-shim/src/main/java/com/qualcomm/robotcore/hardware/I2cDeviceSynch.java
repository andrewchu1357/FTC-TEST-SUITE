package com.qualcomm.robotcore.hardware;

/** An I2C device with read windows (registers polled in the background) and heartbeats. */
public interface I2cDeviceSynch extends I2cDeviceSynchSimple, Engagable {
    void setReadWindow(ReadWindow window);

    ReadWindow getReadWindow();

    void ensureReadWindow(ReadWindow windowNeeded, ReadWindow windowToSet);

    TimestampedData readTimeStamped(int ireg, int creg, ReadWindow readWindowNeeded, ReadWindow readWindowSet);

    void setHeartbeatInterval(int ms);

    int getHeartbeatInterval();

    void setHeartbeatAction(HeartbeatAction action);

    HeartbeatAction getHeartbeatAction();

    enum ReadMode { REPEAT, BALANCED, ONLY_ONCE }

    class ReadWindow {
        public static final int READ_REGISTER_COUNT_MAX = 26;
        public static final int WRITE_REGISTER_COUNT_MAX = 26;
        private final int iregFirst;
        private final int creg;
        private final ReadMode readMode;
        private boolean usedForRead;

        public ReadWindow(int iregFirst, int creg, ReadMode readMode) {
            if (creg < 0 || creg > READ_REGISTER_COUNT_MAX) {
                throw new IllegalArgumentException(String.format("buffer length %d invalid; max is %d", creg, READ_REGISTER_COUNT_MAX));
            }
            this.iregFirst = iregFirst;
            this.creg = creg;
            this.readMode = readMode;
            this.usedForRead = false;
        }

        public int getRegisterFirst() { return iregFirst; }
        public int getRegisterMax() { return iregFirst + creg; }
        public int getRegisterCount() { return creg; }
        public ReadMode getReadMode() { return readMode; }
        public boolean hasWindowBeenUsedForRead() { return usedForRead; }
        public void noteWindowUsedForRead() { usedForRead = true; }
        public boolean canBeUsedToRead() { return !usedForRead || readMode != ReadMode.ONLY_ONCE; }
        public boolean mayInitiateSwitchToReadMode() { return !usedForRead || readMode == ReadMode.REPEAT; }
        public ReadWindow readableCopy() { return new ReadWindow(iregFirst, creg, readMode); }

        public boolean sameAsIncludingMode(ReadWindow him) {
            return him != null && iregFirst == him.iregFirst && creg == him.creg && readMode == him.readMode;
        }

        public boolean contains(ReadWindow him) {
            return him != null && contains(him.getRegisterFirst(), him.getRegisterCount());
        }

        public boolean containsWithSameMode(ReadWindow him) {
            return contains(him) && readMode == him.readMode;
        }

        public boolean contains(int ireg, int creg) {
            return ireg >= getRegisterFirst() && ireg + creg <= getRegisterMax();
        }
    }

    class HeartbeatAction {
        public final boolean rereadLastRead;
        public final boolean rewriteLastWritten;
        public final ReadWindow heartbeatReadWindow;

        public HeartbeatAction(boolean rereadLastRead, boolean rewriteLastWritten, ReadWindow readWindow) {
            this.rereadLastRead = rereadLastRead;
            this.rewriteLastWritten = rewriteLastWritten;
            this.heartbeatReadWindow = readWindow;
        }
    }
}
