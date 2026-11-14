package com.qualcomm.robotcore.eventloop.opmode;

/** Tells listeners when OpModes start and stop. The simulator's OpModeRunner calls registered listeners. */
public interface OpModeManagerNotifier {
    interface Notifications {
        void onOpModePreInit(OpMode opMode);

        void onOpModePreStart(OpMode opMode);

        void onOpModePostStop(OpMode opMode);
    }

    OpMode registerListener(Notifications listener);

    void unregisterListener(Notifications listener);
}
