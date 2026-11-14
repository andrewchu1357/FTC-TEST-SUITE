package com.acmerobotics.dashboard.config.variable;

import java.util.LinkedHashMap;
import java.util.Map;

/** A group of configuration values. Simulator stand-in: kept, but there's no page to edit them on. */
public class CustomVariable extends ConfigVariable<Object> {
    private final Map<String, ConfigVariable<?>> variables = new LinkedHashMap<String, ConfigVariable<?>>();

    public CustomVariable() {}

    public void putVariable(String name, ConfigVariable<?> variable) { variables.put(name, variable); }

    public void removeVariable(String name) { variables.remove(name); }

    public ConfigVariable<?> getVariable(String name) { return variables.get(name); }

    public int size() { return variables.size(); }

    @Override public VariableType getType() { return VariableType.CUSTOM; }

    @Override public Object getValue() { return variables; }

    @Override public void update(ConfigVariable<Object> newVariable) {}
}
