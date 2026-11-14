package org.firstinspires.ftc.robotcore.external.function;

/** The SDK's own Consumer (Java 8 style, for Android versions without java.util.function). */
public interface Consumer<T> {
    void accept(T value);
}
