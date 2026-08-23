package io.knotra.beans.processor;

import io.knotra.beans.annotation.KnotraConfig;
import io.knotra.beans.annotation.KnotraDynamicProxy;
import io.knotra.beans.annotation.KnotraFixed;
import io.knotra.beans.annotation.KnotraFixedOptional;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.Map;
import java.util.Optional;

/** 分类并校验 @KnotraConstructor 构造器的单个参数。 */
final class ParameterAnalyzer {

    private final ValidationContext context;

    ParameterAnalyzer(ValidationContext context) {
        this.context = context;
    }

    ValidationContext context() {
        return context;
    }

    ParameterInfo analyze(
            TypeElement type,
            VariableElement parameter,
            TypeMirror configType,
            boolean noConfig) {
        AnnotationMirror fixedMirror = context.mirror(parameter, KnotraFixed.class);
        AnnotationMirror optionalMirror = context.mirror(parameter, KnotraFixedOptional.class);
        AnnotationMirror dynamicMirror = context.mirror(parameter, KnotraDynamicProxy.class);
        AnnotationMirror configMirror = context.mirror(parameter, KnotraConfig.class);

        int declarations = count(fixedMirror, optionalMirror, dynamicMirror, configMirror);
        if (declarations != 1) {
            context.error(parameter, "every constructor parameter must have exactly one of "
                    + "@KnotraFixed, @KnotraFixedOptional, @KnotraDynamicProxy, or @KnotraConfig");
            return null;
        }

        if (configMirror != null) {
            return analyzeConfigParameter(type, parameter, configType, noConfig);
        }
        return analyzeDependencyParameter(type, parameter, fixedMirror, optionalMirror, dynamicMirror);
    }

    private ParameterInfo analyzeConfigParameter(
            TypeElement type,
            VariableElement parameter,
            TypeMirror configType,
            boolean noConfig) {
        if (noConfig) {
            context.error(parameter, "NoConfig bean constructor must not have a @KnotraConfig parameter");
            return null;
        }
        if (configType.getKind().isPrimitive()) {
            context.error(parameter, "@KnotraConfig parameter type must not be primitive");
            return null;
        }
        if (!isSameErasedType(parameter.asType(), configType)) {
            context.error(parameter, "@KnotraConfig parameter type must match "
                    + "the @KnotraBean config type");
            return null;
        }
        if (isParameterizedOrGeneric(parameter.asType())) {
            context.error(parameter, "@KnotraConfig parameter type must not be a generic or parameterized type");
            return null;
        }
        return new ParameterInfo(ParameterKind.CONFIG, "config", parameter.asType(), true);
    }

    private ParameterInfo analyzeDependencyParameter(
            TypeElement type,
            VariableElement parameter,
            AnnotationMirror fixedMirror,
            AnnotationMirror optionalMirror,
            AnnotationMirror dynamicMirror) {
        AnnotationMirror dependencyMirror;
        ParameterKind kind;
        if (fixedMirror != null) {
            dependencyMirror = fixedMirror;
            kind = ParameterKind.FIXED;
        } else if (optionalMirror != null) {
            dependencyMirror = optionalMirror;
            kind = ParameterKind.OPTIONAL;
        } else {
            dependencyMirror = dynamicMirror;
            kind = ParameterKind.DYNAMIC;
        }

        Map<String, javax.lang.model.element.AnnotationValue> annotationValues =
                context.values(dependencyMirror);
        String name = context.stringValue(annotationValues.get("value"), "");
        boolean required = !Boolean.FALSE.equals(
                context.value(annotationValues.get("required"), Boolean.class, Boolean.TRUE));

        TypeMirror contract;
        if (kind == ParameterKind.OPTIONAL) {
            contract = optionalContract(parameter.asType()).orElseGet(parameter::asType);
        } else {
            contract = parameter.asType();
        }
        if (name.isBlank()) {
            context.error(parameter, kind.annotationName() + " name must not be blank");
            return null;
        }
        if (contract.getKind() == TypeKind.ERROR) {
            context.error(parameter, kind.annotationName() + " contract could not be resolved");
            return null;
        }
        if (contract.getKind().isPrimitive() || contract.getKind() == TypeKind.VOID) {
            context.error(parameter, kind.annotationName() + " contract must not be primitive or void");
            return null;
        }
        if (isParameterizedOrGeneric(contract)) {
            context.error(parameter, kind.annotationName()
                    + " contract must not be a generic or parameterized type");
            return null;
        }
        if (!isAccessibleFromGeneratedPackage(contract, type)) {
            context.error(parameter, kind.annotationName()
                    + " contract must be accessible to the generated factory");
            return null;
        }

        if (kind == ParameterKind.FIXED && !isValidFixedParameter(parameter, contract)) {
            return null;
        }
        if (kind == ParameterKind.OPTIONAL) {
            return optionalParameter(parameter, contract, name, required);
        }
        if (kind == ParameterKind.DYNAMIC && !isValidDynamicParameter(parameter, contract)) {
            return null;
        }
        return new ParameterInfo(kind, name, parameter.asType(), required);
    }

    private boolean isValidFixedParameter(VariableElement parameter, TypeMirror contract) {
        if (isParameterizedOrGeneric(parameter.asType())) {
            context.error(parameter, "@KnotraFixed parameter type must not be a generic or parameterized type");
            return false;
        }
        if (!isSameErasedType(parameter.asType(), contract)) {
            context.error(parameter, "@KnotraFixed parameter must have the exact contract type");
            return false;
        }
        return true;
    }

    private ParameterInfo optionalParameter(
            VariableElement parameter,
            TypeMirror contract,
            String name,
            boolean required) {
        Optional<TypeMirror> value = exactOptionalOf(parameter.asType(), contract);
        if (value.isEmpty()) {
            context.error(parameter, "@KnotraFixedOptional parameter must be exactly Optional<contract>");
            return null;
        }
        return new ParameterInfo(ParameterKind.OPTIONAL, name, value.get(), required);
    }

    private boolean isValidDynamicParameter(VariableElement parameter, TypeMirror contract) {
        Element contractElement = context.types().asElement(contract);
        if (!(contract instanceof DeclaredType)
                || contractElement == null
                || contractElement.getKind() != ElementKind.INTERFACE) {
            context.error(parameter, "@KnotraDynamicProxy parameter must be an exact non-generic interface type");
            return false;
        }
        if (!isSameErasedType(parameter.asType(), contract)) {
            context.error(parameter,
                    "@KnotraDynamicProxy parameter must have the exact contract interface type");
            return false;
        }
        return true;
    }

    private int count(AnnotationMirror... mirrors) {
        int declarations = 0;
        for (AnnotationMirror mirror : mirrors) {
            if (mirror != null) {
                declarations++;
            }
        }
        return declarations;
    }

    private boolean isSameErasedType(TypeMirror left, TypeMirror right) {
        return context.types().isSameType(
                context.types().erasure(left), context.types().erasure(right));
    }

    private Optional<TypeMirror> optionalContract(TypeMirror parameter) {
        if (!(parameter instanceof DeclaredType declared)
                || declared.getTypeArguments().size() != 1) {
            return Optional.empty();
        }
        Element element = context.types().asElement(declared);
        TypeMirror optionalType = context.elements()
                .getTypeElement("java.util.Optional").asType();
        if (element == null || !isSameErasedType(declared, optionalType)) {
            return Optional.empty();
        }
        TypeMirror argument = declared.getTypeArguments().getFirst();
        if (argument.getKind() == TypeKind.WILDCARD
                || argument.getKind() == TypeKind.TYPEVAR
                || isParameterizedOrGeneric(argument)) {
            return Optional.empty();
        }
        return Optional.of(argument);
    }

    private Optional<TypeMirror> exactOptionalOf(TypeMirror parameter, TypeMirror contract) {
        if (!(parameter instanceof DeclaredType declared)
                || declared.getTypeArguments().size() != 1) {
            return Optional.empty();
        }
        Element element = context.types().asElement(declared);
        TypeMirror optionalType = context.elements()
                .getTypeElement("java.util.Optional").asType();
        if (element == null || !isSameErasedType(declared, optionalType)) {
            return Optional.empty();
        }
        TypeMirror argument = declared.getTypeArguments().getFirst();
        if (argument.getKind() == TypeKind.WILDCARD
                || argument.getKind() == TypeKind.TYPEVAR
                || isParameterizedOrGeneric(argument)
                || !isSameErasedType(argument, contract)) {
            return Optional.empty();
        }
        return Optional.of(argument);
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
            if (typeElement.getModifiers().contains(javax.lang.model.element.Modifier.PRIVATE)) {
                return false;
            }
            String typePackage = context.elements().getPackageOf(typeElement)
                    .getQualifiedName().toString();
            boolean samePackage = generatedPackage.equals(typePackage);
            if (!typeElement.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
                    && !samePackage) {
                return false;
            }
            current = typeElement.getEnclosingElement();
        }
        return current instanceof PackageElement;
    }
}
