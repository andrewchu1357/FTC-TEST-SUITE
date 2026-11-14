package com.bylazar.configurables.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Keeps a field out of Panels' live configuration. */
@Retention(RetentionPolicy.RUNTIME)
public @interface IgnoreConfigurable {
}
