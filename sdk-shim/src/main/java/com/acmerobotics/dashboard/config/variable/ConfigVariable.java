package com.acmerobotics.dashboard.config.variable;

/** One editable value (or group of values) on the dashboard's configuration page. */
public abstract class ConfigVariable<T> {
    public abstract VariableType getType();

    public abstract T getValue();

    public abstract void update(ConfigVariable<T> newVariable);
}
