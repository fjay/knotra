package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a dynamic proxy dependency backed by a method-level provider lease. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface KnotraDynamicProxy {
    String value();

    boolean required() default true;
}
