package com.acmerobotics.dashboard.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * FTC Dashboard: marks a class whose public static fields can be tuned live. Simulator
 * stand-in (the fields keep working as plain static fields).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Config {
    String value() default "";
}
