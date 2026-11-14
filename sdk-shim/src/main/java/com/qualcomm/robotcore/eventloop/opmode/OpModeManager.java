package com.qualcomm.robotcore.eventloop.opmode;

import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta;

/** Where @OpModeRegistrar methods register OpModes: by class (a new one per run) or as an instance (reused). */
public interface OpModeManager {
    String DEFAULT_OP_MODE_NAME = "$Stop$Robot$";

    void register(String name, Class<? extends OpMode> opModeClass);

    void register(OpModeMeta meta, Class<? extends OpMode> opModeClass);

    void register(String name, OpMode opModeInstance);

    void register(OpModeMeta meta, OpMode opModeInstance);
}
