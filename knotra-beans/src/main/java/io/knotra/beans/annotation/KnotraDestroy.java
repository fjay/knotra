package io.knotra.beans.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Selects the bean disposer. Async methods must return CompletionStage<Void>. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface KnotraDestroy {
    boolean async() default false;
}
