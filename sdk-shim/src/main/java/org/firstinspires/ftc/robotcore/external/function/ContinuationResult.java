package org.firstinspires.ftc.robotcore.external.function;

/** What a Continuation runs. */
public interface ContinuationResult<T> {
    void handle(T value);
}
