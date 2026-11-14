package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.usb.RobotArmingStateNotifier;

/**
 * Base class of I2C device drivers (goBILDA Pinpoint, SparkFun OTOS, team-written drivers).
 * The hardware map initializes the driver (doInitialize) the first time code gets it, and
 * again after each OpMode resets the device configuration, as in the SDK.
 */
public abstract class I2cDeviceSynchDevice<DEVICE_CLIENT extends I2cDeviceSynchSimple>
        implements RobotArmingStateNotifier.Callback, HardwareDevice {
    protected DEVICE_CLIENT deviceClient;
    protected boolean deviceClientIsOwned;
    protected boolean isInitialized;

    protected I2cDeviceSynchDevice(DEVICE_CLIENT deviceClient, boolean deviceClientIsOwned) {
        this.deviceClient = deviceClient;
        this.deviceClientIsOwned = deviceClientIsOwned;
        this.isInitialized = false;
        this.deviceClient.enableWriteCoalescing(false);
    }

    /** The simulated hub is always armed: with doInitialCallback the driver initializes now. */
    protected void registerArmingStateCallback(boolean doInitialCallback) {
        if (doInitialCallback) onModuleStateChange(null, RobotArmingStateNotifier.ARMINGSTATE.ARMED);
    }

    protected void engage() {
        if (deviceClient instanceof Engagable) ((Engagable) deviceClient).engage();
    }

    protected void disengage() {
        if (deviceClient instanceof Engagable) ((Engagable) deviceClient).disengage();
    }

    public DEVICE_CLIENT getDeviceClient() {
        return deviceClient;
    }

    @Override
    public void onModuleStateChange(RobotArmingStateNotifier module, RobotArmingStateNotifier.ARMINGSTATE state) {
        if (state == RobotArmingStateNotifier.ARMINGSTATE.ARMED) {
            initializeIfNecessary();
        } else if (state == RobotArmingStateNotifier.ARMINGSTATE.PRETENDING) {
            initializeIfNecessary();
            isInitialized = false;
        }
    }

    protected synchronized void initializeIfNecessary() {
        if (!isInitialized) initialize();
    }

    public synchronized boolean initialize() {
        isInitialized = doInitialize();
        return isInitialized;
    }

    protected abstract boolean doInitialize();

    @Override
    public void resetDeviceConfigurationForOpMode() {
        deviceClient.resetDeviceConfigurationForOpMode();
        isInitialized = false;
    }

    @Override
    public void close() {
        if (deviceClientIsOwned) deviceClient.close();
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public String getConnectionInfo() {
        return deviceClient.getConnectionInfo();
    }

    /** For the hardware map: initialize on first use, as the SDK does. */
    static void initializeOnGet(HardwareDevice device) {
        if (device instanceof I2cDeviceSynchDevice) ((I2cDeviceSynchDevice<?>) device).initializeIfNecessary();
    }
}
