package org.firstinspires.ftc.robotcore.external.function;

/** Something to run later with a value (the SDK's callback holder). */
public class Continuation<T> {
    private final T target;

    protected Continuation(T target) {
        this.target = target;
    }

    public static <T> Continuation<T> createTrivial(T target) {
        return new Continuation<T>(target);
    }

    public void dispatch(ContinuationResult<? super T> result) {
        result.handle(target);
    }

    public T getTarget() {
        return target;
    }
}
