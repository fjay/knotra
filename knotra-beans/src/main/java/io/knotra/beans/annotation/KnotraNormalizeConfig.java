package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Selects the static config normalizer: one config parameter and a config-compatible return type. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface KnotraNormalizeConfig {
}
