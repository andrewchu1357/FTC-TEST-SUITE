package org.firstinspires.ftc.robotcore.external.stream;

import android.graphics.Bitmap;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;

/** Something that can hand over camera frames (to the Driver Station or FTC Dashboard). */
public interface CameraStreamSource {
    void getFrameBitmap(Continuation<? extends Consumer<Bitmap>> continuation);
}
