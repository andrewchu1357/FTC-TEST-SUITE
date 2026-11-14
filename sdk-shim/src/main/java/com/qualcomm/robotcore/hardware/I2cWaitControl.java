package com.qualcomm.robotcore.hardware;

/** How long an I2C write waits: not at all, until it is queued in order, or until it has reached the device. */
public enum I2cWaitControl { NONE, ATOMIC, WRITTEN }
