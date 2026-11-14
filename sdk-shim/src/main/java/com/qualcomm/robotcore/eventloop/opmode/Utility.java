package com.qualcomm.robotcore.eventloop.opmode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SDK 11.2: an OpMode for the Driver Station's Utility menu (hardware tests, setup tools) rather
 * than the TeleOp or Autonomous list. It runs like a TeleOp.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Utility {
    String name() default "";

    String description() default "";
}
