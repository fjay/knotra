package io.knotra.beans.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单个 Bean 校验过程中的注解工具、类型工具与错误聚合器。 */
final class ValidationContext {

    private final Elements elements;
    private final Types types;
    private final Messager messager;
    private boolean valid = true;

    ValidationContext(Elements elements, Types types, Messager messager) {
        this.elements = elements;
        this.types = types;
        this.messager = messager;
    }

    Elements elements() {
        return elements;
    }

    Types types() {
        return types;
    }

    boolean isValid() {
        return valid;
    }

    void error(Element element, String message) {
        valid = false;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    AnnotationMirror mirror(Element element, Class<? extends java.lang.annotation.Annotation> type) {
        return mirror(element, type.getCanonicalName());
    }

    AnnotationMirror mirror(Element element, String annotationName) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().toString().equals(annotationName)) {
                return annotation;
            }
        }
        return null;
    }

    Map<String, AnnotationValue> values(AnnotationMirror annotation) {
        Map<String, AnnotationValue> result = new LinkedHashMap<>();
        if (annotation == null) {
            return result;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                annotation.getElementValues().entrySet()) {
            result.put(entry.getKey().getSimpleName().toString(), entry.getValue());
        }
        return result;
    }

    List<AnnotationValue> annotationList(AnnotationValue value) {
        if (value == null) {
            return List.of();
        }
        Object nested = value.getValue();
        if (!(nested instanceof List<?> list)) {
            return List.of();
        }
        List<AnnotationValue> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof AnnotationValue annotationValue) {
                result.add(annotationValue);
            }
        }
        return result;
    }

    String stringValue(AnnotationValue value, String fallback) {
        return value == null || value.getValue() == null
                ? fallback
                : String.valueOf(value.getValue());
    }

    String enumValue(AnnotationValue value, String fallback) {
        if (value == null || !(value.getValue() instanceof VariableElement variable)) {
            return fallback;
        }
        return variable.getSimpleName().toString();
    }

    TypeMirror typeValue(AnnotationValue value) {
        if (value == null || !(value.getValue() instanceof TypeMirror type)) {
            return elements.getTypeElement("java.lang.Void").asType();
        }
        return type;
    }

    @SuppressWarnings("unchecked")
    <T> T value(AnnotationValue annotationValue, Class<T> type, T fallback) {
        if (annotationValue == null || annotationValue.getValue() == null) {
            return fallback;
        }
        Object value = annotationValue.getValue();
        return type.isInstance(value) ? (T) value : fallback;
    }
}
