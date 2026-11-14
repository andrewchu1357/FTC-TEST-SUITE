package com.qualcomm.robotcore.hardware.usb;

import com.qualcomm.robotcore.util.SerialNumber;

public interface RobotArmingStateNotifier {
    enum ARMINGSTATE { ARMED, PRETENDING, DISARMED, CLOSED, TO_ARMED, TO_PRETENDING, TO_DISARMED }

    interface Callback {
        void onModuleStateChange(RobotArmingStateNotifier module, ARMINGSTATE state);
    }

    SerialNumber getSerialNumber();

    ARMINGSTATE getArmingState();

    void registerCallback(Callback callback, boolean doInitialCallback);

    void unregisterCallback(Callback callback);
}
