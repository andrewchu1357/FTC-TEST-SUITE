package com.bylazar.configurables.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Marks a class whose public static fields Panels can edit live. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Configurable {
}
