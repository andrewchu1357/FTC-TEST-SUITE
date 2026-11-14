package com.qualcomm.robotcore.hardware;

public class I2cAddr {
    private final int i2cAddr7Bit;

    public I2cAddr(int i2cAddr7Bit) { this.i2cAddr7Bit = i2cAddr7Bit & 0x7F; }

    public static I2cAddr zero() { return create7bit(0); }
    public static I2cAddr create7bit(int i2cAddr7Bit) { return new I2cAddr(i2cAddr7Bit); }
    public static I2cAddr create8bit(int i2cAddr8Bit) { return new I2cAddr(i2cAddr8Bit / 2); }

    public int get7Bit() { return i2cAddr7Bit; }
    public int get8Bit() { return i2cAddr7Bit << 1; }
    public boolean isDefault() { return i2cAddr7Bit == 0; }
}
