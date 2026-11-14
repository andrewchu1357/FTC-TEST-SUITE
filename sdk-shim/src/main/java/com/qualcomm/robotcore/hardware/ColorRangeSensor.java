package com.qualcomm.robotcore.hardware;

/** A color sensor that also measures how far away the object is (REV Color Sensor V3, AndyMark color sensor). */
public interface ColorRangeSensor extends ColorSensor, NormalizedColorSensor, DistanceSensor, OpticalDistanceSensor {
}
