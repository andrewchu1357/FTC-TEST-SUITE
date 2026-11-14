package com.qualcomm.robotcore.hardware;

public abstract class I2cDeviceSynchDeviceWithParameters<DEVICE_CLIENT extends I2cDeviceSynchSimple, PARAMETERS>
        extends I2cDeviceSynchDevice<DEVICE_CLIENT> {
    protected volatile PARAMETERS parameters;
    protected final PARAMETERS defaultParameters;

    protected I2cDeviceSynchDeviceWithParameters(DEVICE_CLIENT deviceClient, boolean isOwned, PARAMETERS defaultParameters) {
        super(deviceClient, isOwned);
        this.defaultParameters = defaultParameters;
        this.parameters = defaultParameters;
    }

    public PARAMETERS getParameters() {
        return parameters;
    }

    @Override
    protected synchronized boolean doInitialize() {
        return initialize(parameters);
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        super.resetDeviceConfigurationForOpMode();
        parameters = defaultParameters;
    }

    public boolean initialize(PARAMETERS parameters) {
        synchronized (this) {
            isInitialized = internalInitialize(parameters);
            if (isInitialized) this.parameters = parameters;
            return isInitialized;
        }
    }

    protected abstract boolean internalInitialize(PARAMETERS parameters);
}
