package io.knotra.beans.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明从精确的 {@code Optional<T>} 参数推导的可选固定代际依赖。
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface KnotraFixedOptional {

    /**
     * 能力标识名称；为空时使用参数泛型实参类型的二进制名称。
     */
    String value() default "";
}
