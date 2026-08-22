package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one Capability output produced by the annotated bean instance.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(KnotraOutput.List.class)
public @interface KnotraOutput {
    String name();

    Class<?> contract();

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List {
        KnotraOutput[] value();
    }
}
