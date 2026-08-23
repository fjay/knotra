package io.knotra.beans.processor;

import io.knotra.beans.annotation.KnotraBean;
import io.knotra.beans.annotation.KnotraOutput;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取并校验 Bean 类上的注解声明与类级约束。 */
final class BeanAnnotationReader {

    private static final String OUTPUT_ANNOTATION = KnotraOutput.class.getCanonicalName();
    private static final String OUTPUT_LIST_ANNOTATION =
            KnotraOutput.List.class.getCanonicalName().replace('$', '.');
    private static final String BEAN_ANNOTATION = KnotraBean.class.getCanonicalName();

    private final ValidationContext context;

    BeanAnnotationReader(ValidationContext context) {
        this.context = context;
    }

    BeanAnnotation read(TypeElement type) {
        validateClass(type);

        AnnotationMirror beanMirror = context.mirror(type, BEAN_ANNOTATION);
        Map<String, AnnotationValue> beanValues = context.values(beanMirror);
        String id = context.stringValue(beanValues.get("id"), "");
        if (id.isBlank()) {
            context.error(type, "@KnotraBean id is required and must not be blank");
        }

        TypeMirror declaredConfigType = declaredConfigType(beanValues.get("config"));
        boolean noConfig = isNoConfig(declaredConfigType);
        validateConfigType(type, declaredConfigType, noConfig);

        String lifecycleName = context.enumValue(beanValues.get("lifecycle"), "AUTO");
        boolean unmanaged = "UNMANAGED".equals(lifecycleName);
        if (!unmanaged && !"AUTO".equals(lifecycleName)) {
            context.error(type, "@KnotraBean lifecycle must be AUTO or UNMANAGED");
        }

        LinkedHashMap<String, OutputInfo> outputs = readOutputs(type, beanValues);
        validateOutputs(type, outputs);
        return new BeanAnnotation(id, declaredConfigType, noConfig, unmanaged,
                List.copyOf(outputs.values()));
    }

    private void validateClass(TypeElement type) {
        if (type.getKind() != ElementKind.CLASS) {
            context.error(type, "@KnotraBean is only supported on classes");
        }
        if (!(type.getEnclosingElement() instanceof PackageElement)) {
            context.error(type, "@KnotraBean is only supported on top-level classes");
        }
        if (type.getModifiers().contains(Modifier.PRIVATE)) {
            context.error(type, "@KnotraBean class must not be private");
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            context.error(type, "@KnotraBean class must not be abstract");
        }
        if (!type.getTypeParameters().isEmpty()) {
            context.error(type, "@KnotraBean class must not declare type parameters");
        }
    }

    private void validateConfigType(
            TypeElement owner,
            TypeMirror configType,
            boolean noConfig) {
        if (noConfig) {
            return;
        }
        if (configType.getKind() == TypeKind.ERROR) {
            context.error(owner, "@KnotraBean config type could not be resolved");
        } else if (configType.getKind().isPrimitive()) {
            context.error(owner, "@KnotraBean config type must not be primitive");
        } else if (!isAccessibleFromGeneratedPackage(configType, owner)) {
            context.error(owner, "@KnotraBean config type must be accessible to the generated factory");
        }
    }

    private LinkedHashMap<String, OutputInfo> readOutputs(
            TypeElement type,
            Map<String, AnnotationValue> beanValues) {
        LinkedHashMap<String, OutputInfo> outputs = new LinkedHashMap<>();
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            String annotationName = annotation.getAnnotationType().toString();
            if (annotationName.equals(OUTPUT_ANNOTATION)) {
                addOutput(type, outputs, annotation);
            } else if (annotationName.equals(OUTPUT_LIST_ANNOTATION)) {
                for (AnnotationValue nested :
                        context.annotationList(context.values(annotation).get("value"))) {
                    if (nested.getValue() instanceof AnnotationMirror nestedMirror) {
                        addOutput(type, outputs, nestedMirror);
                    }
                }
            }
        }
        for (AnnotationValue nested : context.annotationList(beanValues.get("outputs"))) {
            if (nested.getValue() instanceof AnnotationMirror nestedMirror) {
                addOutput(type, outputs, nestedMirror);
            }
        }
        return outputs;
    }

    private void addOutput(
            TypeElement type,
            LinkedHashMap<String, OutputInfo> outputs,
            AnnotationMirror annotation) {
        Map<String, AnnotationValue> annotationValues = context.values(annotation);
        String name = context.stringValue(annotationValues.get("name"), "");
        TypeMirror contract = context.typeValue(annotationValues.get("contract"));
        OutputInfo previous = outputs.putIfAbsent(name, new OutputInfo(name, contract));
        if (previous != null) {
            context.error(type, "duplicate output name '" + name + "'");
        }
    }

    private void validateOutputs(
            TypeElement type,
            LinkedHashMap<String, OutputInfo> outputs) {
        for (OutputInfo output : outputs.values()) {
            if (output.name().isBlank()) {
                context.error(type, "@KnotraOutput name must not be blank");
            }
            if (output.contract().getKind() == TypeKind.ERROR) {
                context.error(type, "@KnotraOutput contract could not be resolved");
            } else if (output.contract().getKind().isPrimitive()
                    || output.contract().getKind() == TypeKind.VOID) {
                context.error(type, "@KnotraOutput contract must not be primitive or void");
            } else if (isParameterizedOrGeneric(output.contract())) {
                context.error(type, "@KnotraOutput contract must not be a generic or parameterized type");
            } else if (!isAccessibleFromGeneratedPackage(output.contract(), type)) {
                context.error(type, "@KnotraOutput contract must be accessible to the generated factory");
            }
            if (!context.types().isAssignable(type.asType(), output.contract())) {
                context.error(type, "bean type " + type.asType()
                        + " is not assignable to output contract " + output.contract());
            }
        }
    }

    private TypeMirror declaredConfigType(AnnotationValue value) {
        if (value == null || !(value.getValue() instanceof TypeMirror type)) {
            return context.elements().getTypeElement("io.knotra.NoConfig").asType();
        }
        return type;
    }

    private boolean isNoConfig(TypeMirror type) {
        TypeElement noConfig = context.elements().getTypeElement("io.knotra.NoConfig");
        return noConfig != null && context.types().isSameType(type, noConfig.asType());
    }

    private boolean isParameterizedOrGeneric(TypeMirror type) {
        if (type instanceof ArrayType array) {
            return isParameterizedOrGeneric(array.getComponentType());
        }
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        if (!declared.getTypeArguments().isEmpty()) {
            return true;
        }
        return context.types().asElement(declared) instanceof TypeElement typeElement
                && !typeElement.getTypeParameters().isEmpty();
    }

    private boolean isAccessibleFromGeneratedPackage(TypeMirror type, Element owner) {
        if (type instanceof ArrayType array) {
            return isAccessibleFromGeneratedPackage(array.getComponentType(), owner);
        }
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return true;
        }
        Element current = context.types().asElement(context.types().erasure(type));
        if (!(current instanceof TypeElement)) {
            return false;
        }
        String generatedPackage = context.elements().getPackageOf(owner)
                .getQualifiedName().toString();
        while (current instanceof TypeElement typeElement) {
            if (typeElement.getModifiers().contains(Modifier.PRIVATE)) {
                return false;
            }
            String typePackage = context.elements().getPackageOf(typeElement)
                    .getQualifiedName().toString();
            boolean samePackage = generatedPackage.equals(typePackage);
            if (!typeElement.getModifiers().contains(Modifier.PUBLIC) && !samePackage) {
                return false;
            }
            current = typeElement.getEnclosingElement();
        }
        return current instanceof PackageElement;
    }
}
