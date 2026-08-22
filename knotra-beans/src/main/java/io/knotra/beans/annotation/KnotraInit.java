package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 指定在清理钩子注册后、输出提交前执行的零参数实例初始化方法。 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface KnotraInit {
}
