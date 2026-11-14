package com.bylazar.configurables;

/** Panels live configuration. Simulator stand-in: there is no Panels page to edit values from. */
public final class PanelsConfigurables {
    public static final PanelsConfigurables INSTANCE = new PanelsConfigurables();

    private PanelsConfigurables() {}

    public void refreshClass(Object instance) {}
}
