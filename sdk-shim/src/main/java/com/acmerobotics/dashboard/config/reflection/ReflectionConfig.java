package com.acmerobotics.dashboard.config.reflection;

import com.acmerobotics.dashboard.config.variable.CustomVariable;

/** Turns a class's public static fields into dashboard configuration. Simulator stand-in. */
public class ReflectionConfig {
    private ReflectionConfig() {}

    public static CustomVariable createVariableFromClass(Class<?> configClass) {
        return new CustomVariable();
    }
}
