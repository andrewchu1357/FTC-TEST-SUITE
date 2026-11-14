package com.qualcomm.robotcore.util;

import java.io.Serializable;

/** A USB or embedded device serial number. The simulator's hubs use embedded or fake ones. */
public abstract class SerialNumber implements Serializable {
    protected final String serialNumberString;

    protected SerialNumber(String serialNumberString) {
        this.serialNumberString = serialNumberString;
    }

    public static SerialNumber createFake() {
        return new SerialNumber("FakeUSB:" + Long.toHexString(System.identityHashCode(new Object()))) {};
    }

    public static SerialNumber createEmbedded() {
        return new SerialNumber("(embedded)") {};
    }

    public static SerialNumber fromString(String s) {
        return new SerialNumber(s) {};
    }

    public static SerialNumber fromStringOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : fromString(s);
    }

    public static SerialNumber fromUsbOrNull(String s) {
        return fromStringOrNull(s);
    }

    public boolean isVendorProduct() { return false; }
    public boolean isFake() { return serialNumberString.startsWith("FakeUSB:"); }
    public boolean isUsb() { return !isFake() && !isEmbedded(); }
    public boolean isEmbedded() { return serialNumberString.equals("(embedded)"); }
    public String getString() { return serialNumberString; }
    public SerialNumber getScannableDeviceSerialNumber() { return this; }
    public boolean matches(Object o) { return equals(o); }

    @Override
    public boolean equals(Object o) {
        if (o instanceof SerialNumber) return serialNumberString.equals(((SerialNumber) o).serialNumberString);
        return o instanceof String && serialNumberString.equals(o);
    }

    public boolean equals(String s) { return serialNumberString.equals(s); }

    @Override
    public int hashCode() { return serialNumberString.hashCode() ^ 0xabcd9873; }

    @Override
    public String toString() { return serialNumberString; }
}
