package com.qualcomm.robotcore.eventloop.opmode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public static method taking an OpModeManager (or a Context and an OpModeManager) that
 * registers OpModes in code, as Road Runner's TuningOpModes does. The simulator calls it when it
 * looks for OpModes, as the Robot Controller app does.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpModeRegistrar {
}
