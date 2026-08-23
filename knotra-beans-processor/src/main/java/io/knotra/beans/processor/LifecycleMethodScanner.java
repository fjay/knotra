package io.knotra.beans.processor;

import io.knotra.beans.annotation.KnotraDestroy;
import io.knotra.beans.annotation.KnotraInit;
import io.knotra.beans.annotation.KnotraNormalizeConfig;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.Map;
import java.util.Optional;

/** 扫描并校验 Bean 类上的初始化、销毁与配置归一化生命周期方法。 */
final class LifecycleMethodScanner {

    private final ValidationContext context;

    LifecycleMethodScanner(ValidationContext context) {
        this.context = context;
    }

    LifecycleMethods scan(
            TypeElement type,
            TypeMirror effectiveConfigType,
            boolean noConfig,
            boolean unmanaged) {
        ExecutableElement initializer = null;
        ExecutableElement disposer = null;
        boolean asyncDisposer = false;
        ExecutableElement normalizer = null;

        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;

            AnnotationMirror initMirror = context.mirror(method, KnotraInit.class);
            if (initMirror != null) {
                if (initializer != null) {
                    context.error(method, "@KnotraInit may appear at most once");
                } else if (isZeroArgumentInstanceMethod(method)) {
                    initializer = method;
                }
            }

            AnnotationMirror destroyMirror = context.mirror(method, KnotraDestroy.class);
            if (destroyMirror != null) {
                if (disposer != null) {
                    context.error(method, "@KnotraDestroy may appear at most once");
                } else {
                    asyncDisposer = scanDestroy(method, destroyMirror, unmanaged);
                    if (validDestroy(method, destroyMirror, unmanaged)) {
                        disposer = method;
                    }
                }
            }

            AnnotationMirror normalizerMirror =
                    context.mirror(method, KnotraNormalizeConfig.class);
            if (normalizerMirror != null) {
                if (normalizer != null) {
                    context.error(method, "@KnotraNormalizeConfig may appear at most once");
                } else if (isValidNormalizer(method, effectiveConfigType, noConfig)) {
                    normalizer = method;
                }
            }
        }
        return new LifecycleMethods(
                Optional.ofNullable(initializer),
                Optional.ofNullable(disposer),
                asyncDisposer,
                Optional.ofNullable(normalizer));
    }

    private boolean scanDestroy(
            ExecutableElement method,
            AnnotationMirror mirror,
            boolean unmanaged) {
        Map<String, AnnotationValue> values = context.values(mirror);
        boolean async = Boolean.TRUE.equals(
                context.value(values.get("async"), Boolean.class, Boolean.FALSE));
        if (async && !returnsCompletionStageVoid(method.getReturnType())) {
            context.error(method, "async @KnotraDestroy method must return CompletionStage<Void>");
        }
        if (unmanaged) {
            context.error(method,
                    "@KnotraDestroy cannot be combined with lifecycle = UNMANAGED");
        }
        return async;
    }

    private boolean validDestroy(
            ExecutableElement method,
            AnnotationMirror mirror,
            boolean unmanaged) {
        Map<String, AnnotationValue> values = context.values(mirror);
        boolean async = Boolean.TRUE.equals(
                context.value(values.get("async"), Boolean.class, Boolean.FALSE));
        return isZeroArgumentInstanceMethod(method)
                && (!async || returnsCompletionStageVoid(method.getReturnType()))
                && !unmanaged;
    }

    private boolean isZeroArgumentInstanceMethod(ExecutableElement method) {
        boolean valid = method.getParameters().isEmpty()
                && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
        if (!valid) {
            context.error(method, method.getSimpleName().toString()
                    + " must be a non-private zero-argument instance method");
        }
        return valid;
    }

    private boolean isValidNormalizer(
            ExecutableElement method,
            TypeMirror configType,
            boolean noConfig) {
        boolean valid = !noConfig
                && method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE)
                && method.getParameters().size() == 1
                && isSameErasedType(method.getParameters().getFirst().asType(), configType)
                && context.types().isAssignable(method.getReturnType(), configType);
        if (!valid) {
            context.error(method,
                    "@KnotraNormalizeConfig method must be a non-private static method with one "
                            + "config parameter and a config-compatible return type");
        }
        return valid;
    }

    private boolean returnsCompletionStageVoid(TypeMirror type) {
        var stageElement = context.elements()
                .getTypeElement("java.util.concurrent.CompletionStage");
        if (stageElement == null) {
            return false;
        }
        DeclaredType expected = context.types().getDeclaredType(
                stageElement, context.elements().getTypeElement("java.lang.Void").asType());
        return context.types().isAssignable(type, expected);
    }

    private boolean isSameErasedType(TypeMirror left, TypeMirror right) {
        return context.types().isSameType(
                context.types().erasure(left), context.types().erasure(right));
    }

    /** 已通过校验的生命周期方法集合。 */
    record LifecycleMethods(
            Optional<ExecutableElement> initializer,
            Optional<ExecutableElement> disposer,
            boolean asyncDisposer,
            Optional<ExecutableElement> normalizer) {
    }
}
