package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 指定 Bean 销毁方法。异步方法必须返回 CompletionStage<Void>。 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface KnotraDestroy {
    boolean async() default false;
}
