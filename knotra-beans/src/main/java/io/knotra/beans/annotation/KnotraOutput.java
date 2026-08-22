package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明被注解的 Bean 实例所生产的单个 Capability 输出能力。
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
