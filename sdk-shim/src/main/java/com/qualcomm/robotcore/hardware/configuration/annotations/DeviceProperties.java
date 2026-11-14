package com.qualcomm.robotcore.hardware.configuration.annotations;

import com.qualcomm.robotcore.hardware.ControlSystem;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Names a configurable device type: the Driver Station name and the robot configuration XML tag. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeviceProperties {
    String xmlTag();

    String name();

    String description() default "";

    boolean builtIn() default false;

    boolean defaultDevice() default true;

    ControlSystem[] compatibleControlSystems() default {ControlSystem.REV_HUB};

    String[] xmlTagAliases() default {};
}
