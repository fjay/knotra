package io.knotra.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Spring 上下文的公共运行选项。
 *
 * <p>builder 与冻结后的 definition 共用该模型，避免在多个长参数列表之间传递可空字段。</p>
 */
record ContextOptions(
        List<Class<?>> annotatedClasses,
        List<Consumer<? super AnnotationConfigApplicationContext>> customizers,
        Optional<ClassLoader> classLoader,
        Optional<SpringContextCloser> closer) {

    static final ContextOptions EMPTY =
            new ContextOptions(List.of(), List.of(), Optional.empty(), Optional.empty());

    ContextOptions {
        Objects.requireNonNull(annotatedClasses, "annotatedClasses");
        Objects.requireNonNull(customizers, "customizers");
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(closer, "closer");
        annotatedClasses = List.copyOf(annotatedClasses);
        customizers = List.copyOf(customizers);
    }

    ContextOptions withAnnotatedClasses(Class<?>... classes) {
        Objects.requireNonNull(classes, "classes");
        List<Class<?>> next = new ArrayList<>(annotatedClasses);
        for (Class<?> type : classes) {
            next.add(Objects.requireNonNull(type, "annotated class"));
        }
        return new ContextOptions(next, customizers, classLoader, closer);
    }

    ContextOptions withCustomizer(
            Consumer<? super AnnotationConfigApplicationContext> customizer) {
        Objects.requireNonNull(customizer, "customizer");
        List<Consumer<? super AnnotationConfigApplicationContext>> next =
                new ArrayList<>(customizers);
        next.add(customizer);
        return new ContextOptions(annotatedClasses, next, classLoader, closer);
    }

    ContextOptions withClassLoader(ClassLoader classLoader) {
        return new ContextOptions(
                annotatedClasses,
                customizers,
                Optional.of(Objects.requireNonNull(classLoader, "classLoader")),
                closer);
    }

    ContextOptions withCloser(SpringContextCloser closer) {
        return new ContextOptions(
                annotatedClasses,
                customizers,
                classLoader,
                Optional.of(Objects.requireNonNull(closer, "closer")));
    }
}
