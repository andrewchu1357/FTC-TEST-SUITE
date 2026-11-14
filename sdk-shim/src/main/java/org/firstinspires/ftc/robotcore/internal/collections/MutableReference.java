package org.firstinspires.ftc.robotcore.internal.collections;

/** A box holding a value a method can set. */
public class MutableReference<T> {
    protected T value;

    public MutableReference() {
        this(null);
    }

    public MutableReference(T value) {
        this.value = value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "[" + value + "]";
    }
}
