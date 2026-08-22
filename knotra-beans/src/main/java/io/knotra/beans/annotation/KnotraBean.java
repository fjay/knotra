package io.knotra.beans.annotation;

import io.knotra.NoConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a top-level POJO as an Activation-owned Knotra bean.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface KnotraBean {
    String id();

    Class<?> config() default NoConfig.class;

    Lifecycle lifecycle() default Lifecycle.AUTO;

    KnotraOutput[] outputs() default {};

    enum Lifecycle {
        AUTO,
        UNMANAGED
    }
}
