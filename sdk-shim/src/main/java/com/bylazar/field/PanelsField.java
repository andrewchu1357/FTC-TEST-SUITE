package com.bylazar.field;

/** Panels field drawing. Simulator stand-in: drawings appear on the simulator's field. */
public final class PanelsField {
    public static final PanelsField INSTANCE = new PanelsField();

    private final FieldManager field = new FieldManager();

    private PanelsField() {}

    public FieldManager getField() { return field; }
    public FieldPresets getPresets() { return FieldPresets.INSTANCE; }
    public String getTRANSPARENT() { return "transparent"; }
    public String getWHITE() { return "#FFFFFF"; }
    public String getBLACK() { return "#000000"; }
    public String getRED() { return "#F44336"; }
    public String getBLUE() { return "#2196F3"; }
}
