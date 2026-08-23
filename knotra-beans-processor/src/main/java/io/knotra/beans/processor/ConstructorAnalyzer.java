package io.knotra.beans.processor;

import io.knotra.beans.annotation.KnotraConfig;
import io.knotra.beans.annotation.KnotraConstructor;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;

/** 选择并校验唯一标记的构造器，同时组织参数分析结果。 */
final class ConstructorAnalyzer {

    private final ParameterAnalyzer parameterAnalyzer;

    ConstructorAnalyzer(ParameterAnalyzer parameterAnalyzer) {
        this.parameterAnalyzer = parameterAnalyzer;
    }

    ConstructorAnalysis analyze(TypeElement type, BeanAnnotation annotation) {
        ExecutableElement constructor = null;
        int constructorCount = 0;
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR
                    && enclosed.getAnnotationMirrors().stream().anyMatch(mirror ->
                    mirror.getAnnotationType().toString()
                            .equals(KnotraConstructor.class.getCanonicalName()))) {
                constructorCount++;
                constructor = (ExecutableElement) enclosed;
            }
        }
        if (constructorCount != 1) {
            Element errorTarget = constructor != null ? constructor : type;
            parameterAnalyzer.context().error(errorTarget,
                    "@KnotraBean class must have exactly one @KnotraConstructor constructor");
        }
        if (constructor != null) {
            validateConstructor(constructor);
        }

        List<ParameterInfo> parameters = new ArrayList<>();
        TypeMirror effectiveConfigType = annotation.configType();
        if (constructor != null) {
            effectiveConfigType = analyzeParameters(
                    type, constructor, annotation, parameters);
        }
        return new ConstructorAnalysis(constructor, parameters, effectiveConfigType);
    }

    private void validateConstructor(ExecutableElement constructor) {
        if (constructor.getModifiers().contains(Modifier.PRIVATE)) {
            parameterAnalyzer.context().error(constructor,
                    "@KnotraConstructor constructor must not be private");
        }
        if (!constructor.getTypeParameters().isEmpty()) {
            parameterAnalyzer.context().error(constructor,
                    "@KnotraConstructor constructor must not declare type parameters");
        }
    }

    private TypeMirror analyzeParameters(
            TypeElement type,
            ExecutableElement constructor,
            BeanAnnotation annotation,
            List<ParameterInfo> parameters) {
        ValidationContext context = parameterAnalyzer.context();
        int configParameters = 0;
        TypeMirror effectiveConfigType = annotation.configType();
        for (VariableElement parameter : constructor.getParameters()) {
            ParameterInfo parsed = parameterAnalyzer.analyze(
                    type, parameter, annotation.configType(), annotation.noConfig());
            if (parsed == null) {
                if (context.mirror(parameter, KnotraConfig.class) != null) {
                    configParameters++;
                }
                continue;
            }
            parameters.add(parsed);
            if (parsed.kind() == ParameterKind.CONFIG) {
                configParameters++;
                effectiveConfigType = parsed.keyType();
            }
        }
        validateConfigParameterCount(constructor, annotation, configParameters);
        return effectiveConfigType;
    }

    private void validateConfigParameterCount(
            ExecutableElement constructor,
            BeanAnnotation annotation,
            int configParameters) {
        ValidationContext context = parameterAnalyzer.context();
        if (configParameters > 1) {
            context.error(constructor,
                    "constructor must have at most one @KnotraConfig parameter");
        }
        if (!annotation.noConfig() && configParameters != 1) {
            context.error(constructor,
                    "configured bean constructor must have exactly one @KnotraConfig parameter");
        }
        if (annotation.noConfig() && configParameters != 0) {
            context.error(constructor,
                    "NoConfig bean constructor must not have a @KnotraConfig parameter");
        }
    }

    /** 已选构造器、完整参数列表与由参数确定的实际配置类型。 */
    record ConstructorAnalysis(
            ExecutableElement constructor,
            List<ParameterInfo> parameters,
            TypeMirror effectiveConfigType) {
    }
}
