package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a required pinned constructor dependency inferred from the parameter type. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface KnotraRequire {
    String value();
}
