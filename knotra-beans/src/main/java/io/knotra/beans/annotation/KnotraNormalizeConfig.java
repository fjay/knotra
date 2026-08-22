package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 指定静态配置标准化器：接收单个配置参数并返回配置兼容类型。 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface KnotraNormalizeConfig {
}
