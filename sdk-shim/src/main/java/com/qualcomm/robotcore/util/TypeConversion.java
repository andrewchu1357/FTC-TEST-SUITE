package com.qualcomm.robotcore.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Byte-array conversions used by I2C drivers. Big-endian unless a ByteOrder is given, as in the SDK. */
public class TypeConversion {
    private TypeConversion() {}

    public static byte[] shortToByteArray(short shortInt) { return shortToByteArray(shortInt, ByteOrder.BIG_ENDIAN); }
    public static byte[] shortToByteArray(short shortInt, ByteOrder byteOrder) { return ByteBuffer.allocate(2).order(byteOrder).putShort(shortInt).array(); }
    public static byte[] intToByteArray(int integer) { return intToByteArray(integer, ByteOrder.BIG_ENDIAN); }
    public static byte[] intToByteArray(int integer, ByteOrder byteOrder) { return ByteBuffer.allocate(4).order(byteOrder).putInt(integer).array(); }
    public static byte[] longToByteArray(long longInt) { return longToByteArray(longInt, ByteOrder.BIG_ENDIAN); }
    public static byte[] longToByteArray(long longInt, ByteOrder byteOrder) { return ByteBuffer.allocate(8).order(byteOrder).putLong(longInt).array(); }
    public static short byteArrayToShort(byte[] byteArray) { return byteArrayToShort(byteArray, ByteOrder.BIG_ENDIAN); }
    public static short byteArrayToShort(byte[] byteArray, ByteOrder byteOrder) { return byteArrayToShort(byteArray, 0, byteOrder); }
    public static short byteArrayToShort(byte[] byteArray, int ibFirst, ByteOrder byteOrder) { return ByteBuffer.wrap(byteArray, ibFirst, 2).order(byteOrder).getShort(); }
    public static int byteArrayToInt(byte[] byteArray) { return byteArrayToInt(byteArray, ByteOrder.BIG_ENDIAN); }
    public static int byteArrayToInt(byte[] byteArray, ByteOrder byteOrder) { return ByteBuffer.wrap(byteArray).order(byteOrder).getInt(); }
    public static long byteArrayToLong(byte[] byteArray) { return byteArrayToLong(byteArray, ByteOrder.BIG_ENDIAN); }
    public static long byteArrayToLong(byte[] byteArray, ByteOrder byteOrder) { return ByteBuffer.wrap(byteArray).order(byteOrder).getLong(); }
    public static int unsignedByteToInt(byte b) { return b & 0xFF; }
    public static int unsignedShortToInt(short s) { return s & 0xFFFF; }
    public static double unsignedByteToDouble(byte b) { return b & 0xFF; }
    public static long unsignedIntToLong(int i) { return i & 0xFFFFFFFFL; }
    public static byte[] stringToUtf8(String javaString) { return javaString.getBytes(StandardCharsets.UTF_8); }
    public static String utf8ToString(byte[] utf8) { return new String(utf8, StandardCharsets.UTF_8); }
    public static int doubleToFixedInt(double d, int cbitsFraction) { return (int) Math.round(d * (1 << cbitsFraction)); }
    public static double doubleFromFixed(int i, int cbitsFraction) { return i / (double) (1 << cbitsFraction); }
    public static long doubleToFixedLong(double d, int cbitsFraction) { return Math.round(d * (1L << cbitsFraction)); }
    public static double doubleFromFixed(long l, int cbitsFraction) { return l / (double) (1L << cbitsFraction); }
    public static boolean toBoolean(Boolean value) { return toBoolean(value, false); }
    public static boolean toBoolean(Boolean value, boolean defaultValue) { return value == null ? defaultValue : value; }
}
