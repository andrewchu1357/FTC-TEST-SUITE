package com.bylazar.field;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FieldPresets {
    public static final FieldPresets INSTANCE = new FieldPresets();

    private final FieldPresetParams panels = new FieldPresetParams("Panels", 0, 0, CanvasRotation.DEG_0, false, false, true);
    private final FieldPresetParams defaultFtc = new FieldPresetParams("Default FTC", 0, 0, CanvasRotation.DEG_0, false, false, true);
    /** Pedro Pathing: origin in a field corner (field center at 72, 72). */
    private final FieldPresetParams pedroPathing = new FieldPresetParams("Pedro Pathing", -72, -72, CanvasRotation.DEG_90, false, true, false);
    private final FieldPresetParams roadRunner = new FieldPresetParams("Road Runner", 0, 0, CanvasRotation.DEG_0, false, false, true);

    private FieldPresets() {}

    public FieldPresetParams getPANELS() { return panels; }
    public FieldPresetParams getDEFAULT_FTC() { return defaultFtc; }
    public FieldPresetParams getPEDRO_PATHING() { return pedroPathing; }
    public FieldPresetParams getROAD_RUNNER() { return roadRunner; }
    public List<FieldPresetParams> getAllPresets() { return Collections.unmodifiableList(Arrays.asList(panels, defaultFtc, pedroPathing, roadRunner)); }
}
