package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a dynamic interface constructor dependency backed by a method-level lease proxy. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface KnotraDynamic {
    String name();

    Class<?> contract();

    boolean required() default true;
}
