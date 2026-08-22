package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an optional pinned dependency inferred from an exact Optional<T> parameter. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface KnotraOptional {
    String value();
}
