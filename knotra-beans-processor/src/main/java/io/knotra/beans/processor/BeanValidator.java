package io.knotra.beans.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.LinkedHashSet;
import java.util.List;

/** 组合各专项分析器并产出最终生成模型。 */
final class BeanValidator {

    private final Elements elements;
    private final Types types;
    private final Messager messager;

    BeanValidator(Elements elements, Types types, Messager messager) {
        this.elements = elements;
        this.types = types;
        this.messager = messager;
    }

    BeanModel validate(TypeElement type) {
        ValidationContext context = new ValidationContext(elements, types, messager);
        BeanAnnotation annotation = new BeanAnnotationReader(context).read(type);
        ParameterAnalyzer parameterAnalyzer = new ParameterAnalyzer(context);
        ConstructorAnalyzer.ConstructorAnalysis constructor =
                new ConstructorAnalyzer(parameterAnalyzer).analyze(type, annotation);
        LifecycleMethodScanner.LifecycleMethods lifecycle =
                new LifecycleMethodScanner(context).scan(
                        type,
                        constructor.effectiveConfigType(),
                        annotation.noConfig(),
                        annotation.unmanaged());
        validateCapabilityNames(
                context,
                type,
                constructor.constructor(),
                constructor.parameters(),
                annotation.outputs());

        if (!context.isValid()) {
            return null;
        }
        return new BeanModel(
                type,
                annotation.id(),
                constructor.effectiveConfigType(),
                annotation.unmanaged(),
                List.copyOf(constructor.parameters()),
                annotation.outputs(),
                lifecycle.initializer(),
                lifecycle.disposer(),
                lifecycle.asyncDisposer(),
                lifecycle.normalizer());
    }

    private void validateCapabilityNames(
            ValidationContext context,
            TypeElement type,
            ExecutableElement constructor,
            List<ParameterInfo> parameters,
            List<OutputInfo> outputs) {
        LinkedHashSet<String> capabilityNames = new LinkedHashSet<>();
        for (ParameterInfo parameter : parameters) {
            if (parameter.kind() == ParameterKind.CONFIG) {
                continue;
            }
            if (!capabilityNames.add(parameter.name())) {
                Element target = constructor != null ? constructor : type;
                context.error(target, "duplicate capability name '" + parameter.name() + "'");
            }
        }
        for (OutputInfo output : outputs) {
            if (!capabilityNames.add(output.name())) {
                context.error(type, "duplicate capability name '" + output.name() + "'");
            }
        }
    }
}
