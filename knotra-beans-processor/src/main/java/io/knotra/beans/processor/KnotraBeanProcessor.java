package io.knotra.beans.processor;

import io.knotra.beans.annotation.KnotraBean;
import io.knotra.beans.annotation.KnotraConfig;
import io.knotra.beans.annotation.KnotraConstructor;
import io.knotra.beans.annotation.KnotraDestroy;
import io.knotra.beans.annotation.KnotraDynamicProxy;
import io.knotra.beans.annotation.KnotraInit;
import io.knotra.beans.annotation.KnotraNormalizeConfig;
import io.knotra.beans.annotation.KnotraOptional;
import io.knotra.beans.annotation.KnotraOutput;
import io.knotra.beans.annotation.KnotraRequire;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates an immutable {@link io.knotra.beans.BeanDefinition} factory for each
 * {@link KnotraBean}-annotated top-level class. Constructor dependency count follows the
 * declared constructor and is not limited by the hand-written Beans DSL arity.
 */
@SupportedAnnotationTypes("io.knotra.beans.annotation.KnotraBean")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class KnotraBeanProcessor extends AbstractProcessor implements Processor {

    private static final String BEAN_ANNOTATION = KnotraBean.class.getCanonicalName();
    private static final String OUTPUT_ANNOTATION = KnotraOutput.class.getCanonicalName();
    private static final String OUTPUT_LIST_ANNOTATION =
            KnotraOutput.List.class.getCanonicalName().replace('$', '.');

    private Messager messager;
    private Elements elements;
    private Types types;
    private final Set<String> processedBeans = new HashSet<>();
    private final Map<String, Element> plannedFactories = new LinkedHashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.messager = processingEnvironment.getMessager();
        this.elements = processingEnvironment.getElementUtils();
        this.types = processingEnvironment.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(KnotraBean.class)) {
            if (!(element instanceof TypeElement type)) {
                error(element, "@KnotraBean is only supported on classes");
                continue;
            }
            String qualifiedName = type.getQualifiedName().toString();
            if (!processedBeans.add(qualifiedName)) {
                continue;
            }
            processBean(type);
        }
        return false;
    }

    private String generatedName(TypeElement type) {
        return type.getQualifiedName() + "_KnotraFactory";
    }

    private void processBean(TypeElement type) {
        String factoryName = generatedName(type);
        Element existingFactory = elements.getTypeElement(factoryName);
        Element plannedOwner = plannedFactories.putIfAbsent(factoryName, type);
        if (existingFactory != null) {
            error(type, "cannot generate " + factoryName
                    + " because that fully-qualified type already exists");
        } else if (plannedOwner != null) {
            error(type, "cannot generate " + factoryName
                    + " because it is already planned for " + plannedOwner.getSimpleName());
        }

        Model model = validate(type);
        if (model == null || existingFactory != null || plannedOwner != null) {
            return;
        }
        writeFactory(model);
    }

    private Model validate(TypeElement type) {
        boolean valid = true;

        if (type.getKind() != ElementKind.CLASS) {
            error(type, "@KnotraBean is only supported on classes");
            valid = false;
        }
        if (!(type.getEnclosingElement() instanceof PackageElement)) {
            error(type, "@KnotraBean is only supported on top-level classes");
            valid = false;
        }
        if (type.getModifiers().contains(Modifier.PRIVATE)) {
            error(type, "@KnotraBean class must not be private");
            valid = false;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(type, "@KnotraBean class must not be abstract");
            valid = false;
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, "@KnotraBean class must not declare type parameters");
            valid = false;
        }

        AnnotationMirror beanMirror = mirror(type, BEAN_ANNOTATION);
        Map<String, AnnotationValue> beanValues = values(beanMirror);
        String id = stringValue(beanValues.get("id"), "");
        if (id.isBlank()) {
            error(type, "@KnotraBean id is required and must not be blank");
            valid = false;
        }

        TypeMirror declaredConfigType = declaredConfigType(beanValues.get("config"));
        boolean noConfig = isNoConfig(declaredConfigType);
        String lifecycleName = enumValue(beanValues.get("lifecycle"), "AUTO");
        boolean unmanaged = "UNMANAGED".equals(lifecycleName);
        if (!unmanaged && !"AUTO".equals(lifecycleName)) {
            error(type, "@KnotraBean lifecycle must be AUTO or UNMANAGED");
            valid = false;
        }
        if (!noConfig) {
            if (declaredConfigType.getKind() == TypeKind.ERROR) {
                valid = false;
            } else if (declaredConfigType.getKind().isPrimitive()) {
                error(type, "@KnotraBean config type must not be primitive");
                valid = false;
            } else if (!isAccessibleFromGeneratedPackage(declaredConfigType, type)) {
                error(type, "@KnotraBean config type must be accessible to the generated factory");
                valid = false;
            }
        }
        OutputValidation outputValidation = readOutputs(type, beanValues);
        List<OutputInfo> outputs = outputValidation.outputs();
        valid &= outputValidation.valid();
        for (OutputInfo output : outputs) {
            if (output.name().isBlank()) {
                error(type, "@KnotraOutput name must not be blank");
                valid = false;
            }
            if (output.contract().getKind() == TypeKind.ERROR) {
                valid = false;
            } else if (output.contract().getKind().isPrimitive()
                    || output.contract().getKind() == TypeKind.VOID) {
                error(type, "@KnotraOutput contract must not be primitive or void");
                valid = false;
            } else if (isParameterizedOrGeneric(output.contract())) {
                error(type, "@KnotraOutput contract must not be a generic or parameterized type");
                valid = false;
            } else if (!isAccessibleFromGeneratedPackage(output.contract(), type)) {
                error(type, "@KnotraOutput contract must be accessible to the generated factory");
                valid = false;
            }
        }

        ExecutableElement constructor = null;
        int constructorCount = 0;
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR
                    && mirror(enclosed, KnotraConstructor.class.getCanonicalName()) != null) {
                constructorCount++;
                constructor = (ExecutableElement) enclosed;
            }
        }
        if (constructorCount != 1) {
            error(type, "@KnotraBean class must have exactly one @KnotraConstructor constructor");
            valid = false;
        }
        if (constructor != null && constructor.getModifiers().contains(Modifier.PRIVATE)) {
            error(constructor, "@KnotraConstructor constructor must not be private");
            valid = false;
        }
        if (constructor != null && !constructor.getTypeParameters().isEmpty()) {
            error(constructor, "@KnotraConstructor constructor must not declare type parameters");
            valid = false;
        }
        List<ParameterInfo> parameters = new ArrayList<>();
        TypeMirror effectiveConfigType = declaredConfigType;
        if (constructor != null) {
            int configParameters = 0;
            for (VariableElement parameter : constructor.getParameters()) {
                ParameterInfo parsed = validateParameter(type, parameter, declaredConfigType, noConfig);
                if (parsed == null) {
                    valid = false;
                    if (mirror(parameter, KnotraConfig.class.getCanonicalName()) != null) {
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
            if (configParameters > 1) {
                error(constructor, "constructor must have at most one @KnotraConfig parameter");
                valid = false;
            }
            if (!noConfig && configParameters != 1) {
                error(constructor, "configured bean constructor must have exactly one @KnotraConfig parameter");
                valid = false;
            }
            if (noConfig && configParameters != 0) {
                error(constructor, "NoConfig bean constructor must not have a @KnotraConfig parameter");
                valid = false;
            }
        }

        ExecutableElement initializer = null;
        ExecutableElement disposer = null;
        boolean asyncDisposer = false;
        ExecutableElement normalizer = null;
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            AnnotationMirror initMirror = mirror(method, KnotraInit.class.getCanonicalName());
            if (initMirror != null) {
                if (initializer != null) {
                    error(method, "@KnotraInit may appear at most once");
                    valid = false;
                } else if (!isZeroArgumentInstanceMethod(method)) {
                    valid = false;
                } else {
                    initializer = method;
                }
            }

            AnnotationMirror destroyMirror = mirror(method, KnotraDestroy.class.getCanonicalName());
            if (destroyMirror != null) {
                Map<String, AnnotationValue> destroyValues = values(destroyMirror);
                boolean async = Boolean.TRUE.equals(
                        value(destroyValues.get("async"), Boolean.class, Boolean.FALSE));
                if (disposer != null) {
                    error(method, "@KnotraDestroy may appear at most once");
                    valid = false;
                } else if (!isZeroArgumentInstanceMethod(method)) {
                    valid = false;
                } else if (async && !returnsCompletionStageVoid(method.getReturnType())) {
                    error(method, "async @KnotraDestroy method must return CompletionStage<Void>");
                    valid = false;
                } else {
                    disposer = method;
                    asyncDisposer = async;
                }
                if (unmanaged) {
                    error(method, "@KnotraDestroy cannot be combined with lifecycle = UNMANAGED");
                    valid = false;
                }
            }

            AnnotationMirror normalizerMirror =
                    mirror(method, KnotraNormalizeConfig.class.getCanonicalName());
            if (normalizerMirror != null) {
                if (normalizer != null) {
                    error(method, "@KnotraNormalizeConfig may appear at most once");
                    valid = false;
                } else if (!isValidNormalizer(method, effectiveConfigType, noConfig)) {
                    valid = false;
                } else {
                    normalizer = method;
                }
            }
        }

        Set<String> capabilityNames = new LinkedHashSet<>();
        for (ParameterInfo parameter : parameters) {
            if (parameter.kind() == ParameterKind.CONFIG) {
                continue;
            }
            if (!capabilityNames.add(parameter.name())) {
                error(constructor != null ? constructor : type,
                        "duplicate capability name '" + parameter.name() + "'");
                valid = false;
            }
        }
        for (OutputInfo output : outputs) {
            if (!capabilityNames.add(output.name())) {
                error(type, "duplicate capability name '" + output.name() + "'");
                valid = false;
            }
            if (!types.isAssignable(type.asType(), output.contract())) {
                error(type, "bean type " + type.asType()
                        + " is not assignable to output contract " + output.contract());
                valid = false;
            }
        }

        if (!valid) {
            return null;
        }
        return new Model(
                type,
                id,
                effectiveConfigType,
                unmanaged,
                parameters,
                outputs,
                Optional.ofNullable(initializer),
                Optional.ofNullable(disposer),
                asyncDisposer,
                Optional.ofNullable(normalizer));
    }

    private ParameterInfo validateParameter(
            TypeElement type,
            VariableElement parameter,
            TypeMirror configType,
            boolean noConfig) {
        AnnotationMirror requireMirror = mirror(parameter, KnotraRequire.class.getCanonicalName());
        AnnotationMirror optionalMirror = mirror(parameter, KnotraOptional.class.getCanonicalName());
        AnnotationMirror dynamicMirror = mirror(parameter, KnotraDynamicProxy.class.getCanonicalName());
        AnnotationMirror configMirror = mirror(parameter, KnotraConfig.class.getCanonicalName());

        int declarations = 0;
        if (requireMirror != null) {
            declarations++;
        }
        if (optionalMirror != null) {
            declarations++;
        }
        if (dynamicMirror != null) {
            declarations++;
        }
        if (configMirror != null) {
            declarations++;
        }
        if (declarations != 1) {
            error(parameter, "every constructor parameter must have exactly one of "
                    + "@KnotraRequire, @KnotraOptional, @KnotraDynamicProxy, or @KnotraConfig");
            return null;
        }

        if (configMirror != null) {
            if (noConfig) {
                error(parameter, "NoConfig bean constructor must not have a @KnotraConfig parameter");
                return null;
            }
            if (configType.getKind().isPrimitive()) {
                error(parameter, "@KnotraConfig parameter type must not be primitive");
                return null;
            }
            if (!isSameErasedType(parameter.asType(), configType)) {
                error(parameter, "@KnotraConfig parameter type must match "
                        + "the @KnotraBean config type");
                return null;
            }
            if (isParameterizedOrGeneric(parameter.asType())) {
                error(parameter, "@KnotraConfig parameter type must not be a generic or parameterized type");
                return null;
            }
            return new ParameterInfo(ParameterKind.CONFIG, "config", parameter.asType(), true);
        }

        AnnotationMirror dependencyMirror;
        ParameterKind kind;
        if (requireMirror != null) {
            dependencyMirror = requireMirror;
            kind = ParameterKind.REQUIRED;
        } else if (optionalMirror != null) {
            dependencyMirror = optionalMirror;
            kind = ParameterKind.OPTIONAL;
        } else {
            dependencyMirror = dynamicMirror;
            kind = ParameterKind.DYNAMIC;
        }

        Map<String, AnnotationValue> annotationValues = values(dependencyMirror);
        String name = stringValue(annotationValues.get("value"), "");
        boolean required = !Boolean.FALSE.equals(
                value(annotationValues.get("required"), Boolean.class, Boolean.TRUE));

        TypeMirror contract;
        if (kind == ParameterKind.REQUIRED || kind == ParameterKind.DYNAMIC) {
            contract = parameter.asType();
        } else {
            contract = optionalContract(parameter.asType())
                    .orElseGet(parameter::asType);
        }
        if (name.isBlank()) {
            error(parameter, kind.annotationName() + " name must not be blank");
            return null;
        }
        if (contract.getKind() == TypeKind.ERROR) {
            return null;
        }
        if (contract.getKind().isPrimitive() || contract.getKind() == TypeKind.VOID) {
            error(parameter, kind.annotationName() + " contract must not be primitive or void");
            return null;
        }
        if (isParameterizedOrGeneric(contract)) {
            error(parameter, kind.annotationName() + " contract must not be a generic or parameterized type");
            return null;
        }
        if (!isAccessibleFromGeneratedPackage(contract, type)) {
            error(parameter, kind.annotationName() + " contract must be accessible to the generated factory");
            return null;
        }

        if (kind == ParameterKind.REQUIRED) {
            if (isParameterizedOrGeneric(parameter.asType())) {
                error(parameter, "@KnotraRequire parameter type must not be a generic or parameterized type");
                return null;
            }
            if (!isSameErasedType(parameter.asType(), contract)) {
                error(parameter, "@KnotraRequire parameter must have the exact contract type");
                return null;
            }
        }
        if (kind == ParameterKind.OPTIONAL) {
            Optional<TypeMirror> value = exactOptionalOf(parameter.asType(), contract);
            if (value.isEmpty()) {
                error(parameter, "@KnotraOptional parameter must be exactly Optional<contract>");
                return null;
            }
            return new ParameterInfo(kind, name, value.get(), required);
        }
        if (kind == ParameterKind.DYNAMIC) {
            Element contractElement = types.asElement(contract);
            if (!(contract instanceof DeclaredType)
                    || contractElement == null
                    || contractElement.getKind() != ElementKind.INTERFACE) {
                error(parameter, "@KnotraDynamicProxy parameter must be an exact non-generic interface type");
                return null;
            }
            if (!isSameErasedType(parameter.asType(), contract)) {
                error(parameter, "@KnotraDynamicProxy parameter must have the exact contract interface type");
                return null;
            }
        }
        return new ParameterInfo(kind, name, parameter.asType(), required);
    }

    private boolean isZeroArgumentInstanceMethod(ExecutableElement method) {
        boolean valid = method.getParameters().isEmpty()
                && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
        if (!valid) {
            error(method, method.getSimpleName().toString()
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
                && types.isAssignable(method.getReturnType(), configType);
        if (!valid) {
            error(method, "@KnotraNormalizeConfig method must be a non-private static method with one "
                    + "config parameter and a config-compatible return type");
        }
        return valid;
    }

    private boolean returnsCompletionStageVoid(TypeMirror type) {
        TypeElement stageElement = elements.getTypeElement("java.util.concurrent.CompletionStage");
        if (stageElement == null) {
            return false;
        }
        DeclaredType expected = types.getDeclaredType(
                stageElement, elements.getTypeElement("java.lang.Void").asType());
        return types.isAssignable(type, expected);
    }

    private boolean isSameErasedType(TypeMirror left, TypeMirror right) {
        return types.isSameType(types.erasure(left), types.erasure(right));
    }

    private Optional<TypeMirror> optionalContract(TypeMirror parameter) {
        if (!(parameter instanceof DeclaredType declared)
                || declared.getTypeArguments().size() != 1) {
            return Optional.empty();
        }
        Element element = types.asElement(declared);
        TypeMirror optionalType = elements.getTypeElement("java.util.Optional").asType();
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
        Element element = types.asElement(declared);
        TypeMirror optionalType = elements.getTypeElement("java.util.Optional").asType();
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
        if (type instanceof javax.lang.model.type.ArrayType array) {
            return isParameterizedOrGeneric(array.getComponentType());
        }
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        if (!declared.getTypeArguments().isEmpty()) {
            return true;
        }
        return types.asElement(declared) instanceof TypeElement typeElement
                && !typeElement.getTypeParameters().isEmpty();
    }

    private boolean isAccessibleFromGeneratedPackage(TypeMirror type, TypeElement owner) {
        if (type instanceof javax.lang.model.type.ArrayType array) {
            return isAccessibleFromGeneratedPackage(array.getComponentType(), owner);
        }
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return true;
        }
        Element current = types.asElement(types.erasure(type));
        if (!(current instanceof TypeElement)) {
            return false;
        }
        String generatedPackage = elements.getPackageOf(owner).getQualifiedName().toString();
        while (current instanceof TypeElement typeElement) {
            if (typeElement.getModifiers().contains(Modifier.PRIVATE)) {
                return false;
            }
            String typePackage = elements.getPackageOf(typeElement)
                    .getQualifiedName().toString();
            boolean samePackage = generatedPackage.equals(typePackage);
            if (!typeElement.getModifiers().contains(Modifier.PUBLIC) && !samePackage) {
                return false;
            }
            current = typeElement.getEnclosingElement();
        }
        return current instanceof PackageElement;
    }
    private TypeMirror declaredConfigType(AnnotationValue value) {
        if (value == null || !(value.getValue() instanceof TypeMirror type)) {
            TypeMirror noConfig = elements.getTypeElement("io.knotra.NoConfig").asType();
            return noConfig;
        }
        return type;
    }

    private boolean isNoConfig(TypeMirror type) {
        TypeElement noConfig = elements.getTypeElement("io.knotra.NoConfig");
        return noConfig != null && types.isSameType(type, noConfig.asType());
    }

    private OutputValidation readOutputs(
            TypeElement type,
            Map<String, AnnotationValue> beanValues) {
        LinkedHashMap<String, OutputInfo> outputs = new LinkedHashMap<>();
        boolean valid = true;
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            String annotationName = annotation.getAnnotationType().toString();
            if (annotationName.equals(OUTPUT_ANNOTATION)) {
                valid &= addOutput(type, outputs, annotation);
            } else if (annotationName.equals(OUTPUT_LIST_ANNOTATION)) {
                for (AnnotationValue nested : annotationList(values(annotation).get("value"))) {
                    if (nested.getValue() instanceof AnnotationMirror nestedMirror) {
                        valid &= addOutput(type, outputs, nestedMirror);
                    }
                }
            }
        }
        for (AnnotationValue nested : annotationList(beanValues.get("outputs"))) {
            if (nested.getValue() instanceof AnnotationMirror nestedMirror) {
                valid &= addOutput(type, outputs, nestedMirror);
            }
        }
        return new OutputValidation(List.copyOf(outputs.values()), valid);
    }

    private boolean addOutput(
            TypeElement type,
            Map<String, OutputInfo> outputs,
            AnnotationMirror annotation) {
        Map<String, AnnotationValue> annotationValues = values(annotation);
        String name = stringValue(annotationValues.get("name"), "");
        TypeMirror contract = typeValue(annotationValues.get("contract"));
        OutputInfo previous = outputs.putIfAbsent(name, new OutputInfo(name, contract));
        if (previous != null) {
            error(type, "duplicate output name '" + name + "'");
            return false;
        }
        return true;
    }

    private void writeFactory(Model model) {
        TypeElement type = model.type();
        String packageName = elements.getPackageOf(type).getQualifiedName().toString();
        String generatedSimpleName = type.getSimpleName() + "_KnotraFactory";
        String qualifiedName = packageName.isEmpty()
                ? generatedSimpleName
                : packageName + "." + generatedSimpleName;
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, type);
            try (Writer writer = file.openWriter()) {
                writer.write(generateSource(model, packageName, generatedSimpleName));
            }
        } catch (IOException error) {
            error(type, "failed to write " + generatedSimpleName + ": " + error.getMessage());
        }
    }

    private String generateSource(
            Model model,
            String packageName,
            String generatedSimpleName) {
        TypeElement type = model.type();
        String beanName = type.getQualifiedName().toString();
        boolean configured = !isNoConfig(model.configType());
        String configName = configured ? qualifiedTypeName(model.configType()) : null;
        String beanTypeName = qualifiedTypeName(type.asType());
        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("import io.knotra.CapabilityKey;\n");
        source.append("import io.knotra.beans.BeanDefinition;\n");
        source.append("import io.knotra.beans.BeanDependency;\n");
        source.append("import io.knotra.beans.Beans;\n");
        if (configured) {
            source.append("import io.knotra.beans.ConfiguredBeanDefinition;\n");
        }
        source.append("\nimport java.util.List;\n\n");
        source.append("@SuppressWarnings(\"unchecked\")\n");
        source.append("public final class ").append(generatedSimpleName).append(" {\n\n");

        int dependencyIndex = 0;
        for (ParameterInfo parameter : model.parameters()) {
            if (parameter.kind() == ParameterKind.CONFIG) {
                continue;
            }
            source.append("    private static final CapabilityKey<")
                    .append(qualifiedTypeName(parameter.keyType()))
                    .append("> KEY_").append(dependencyIndex++)
                    .append(" = CapabilityKey.of(")
                    .append(stringLiteral(parameter.name())).append(",\n")
                    .append("            ").append(generatedSimpleName)
                    .append(".<").append(qualifiedTypeName(parameter.keyType()))
                    .append(">contractClass(")
                    .append(erasedClassLiteral(parameter.keyType())).append("));\n");
        }

        int outputIndex = 0;
        for (OutputInfo output : model.outputs()) {
            source.append("    private static final CapabilityKey<")
                    .append(qualifiedTypeName(output.contract()))
                    .append("> OUTPUT_").append(outputIndex)
                    .append(" = CapabilityKey.of(")
                    .append(stringLiteral(output.name())).append(",\n")
                    .append("            ").append(generatedSimpleName)
                    .append(".<").append(qualifiedTypeName(output.contract()))
                    .append(">contractClass(")
                    .append(erasedClassLiteral(output.contract())).append("));\n");
            outputIndex++;
        }

        source.append("\n    private static final List<BeanDependency<?>> DEPENDENCIES = ");
        if (model.parameters().stream().noneMatch(parameter -> parameter.kind() != ParameterKind.CONFIG)) {
            source.append("List.of();\n");
        } else {
            source.append("List.of(\n");
            boolean first = true;
            for (ParameterInfo parameter : model.parameters()) {
                if (parameter.kind() == ParameterKind.CONFIG) {
                    continue;
                }
                if (!first) {
                    source.append(",\n");
                }
                first = false;
                source.append("            Beans.");
                if (parameter.kind() == ParameterKind.REQUIRED) {
                    source.append("required");
                } else if (parameter.kind() == ParameterKind.OPTIONAL) {
                    source.append("optional");
                } else if (parameter.required()) {
                    source.append("dynamic");
                } else {
                    source.append("dynamicOptional");
                }
                source.append("(KEY_").append(dependencyIndex(parameter, model.parameters())).append(')');
            }
            source.append(");\n");
        }

        source.append("\n    private final ");
        if (configured) {
            source.append("ConfiguredBeanDefinition<")
                    .append(configName).append(", ").append(beanTypeName).append(">");
        } else {
            source.append("BeanDefinition<").append(beanTypeName).append(">");
        }
        source.append(" definition;\n\n");
        source.append("    public ").append(generatedSimpleName).append("() {\n");
        source.append("        this.definition = ");
        if (configured) {
            source.append("Beans.expert(\n");
            source.append("                ").append(stringLiteral(model.id())).append(",\n");
            source.append("                ").append(configName).append(".class,\n");
            source.append("                DEPENDENCIES,\n");
            source.append("                (context, config) -> new ").append(beanName).append("(\n");
        } else {
            source.append("Beans.expert(\n");
            source.append("                ").append(stringLiteral(model.id())).append(",\n");
            source.append("                DEPENDENCIES,\n");
            source.append("                context -> new ").append(beanName).append("(\n");
        }

        boolean first = true;
        int dependencyIndexForArgs = 0;
        for (ParameterInfo parameter : model.parameters()) {
            if (!first) {
                source.append(",\n");
            }
            first = false;
            if (parameter.kind() == ParameterKind.CONFIG) {
                source.append("                        config");
            } else {
                int index = dependencyIndexForArgs++;
                source.append("                        ");
                if (parameter.kind() == ParameterKind.REQUIRED) {
                    source.append("context.require(KEY_").append(index).append(')');
                } else if (parameter.kind() == ParameterKind.OPTIONAL) {
                    source.append("context.find(KEY_").append(index).append(')');
                } else {
                    source.append("context.subscribe(KEY_").append(index).append(").proxy()");
                }
            }
        }
        source.append("))\n");

        for (int index = 0; index < model.outputs().size(); index++) {
            source.append("                .provideAs(OUTPUT_").append(index)
                    .append(", bean -> bean)\n");
        }
        if (model.initializer().isPresent()) {
            source.append("                .initializer(")
                    .append(beanName).append("::")
                    .append(model.initializer().get().getSimpleName()).append(")\n");
        }
        if (model.normalizer().isPresent()) {
            source.append("                .normalizeConfig(")
                    .append(beanName).append("::")
                    .append(model.normalizer().get().getSimpleName()).append(")\n");
        }
        if (model.unmanaged()) {
            source.append("                .unmanaged()\n");
        } else if (model.disposer().isPresent()) {
            source.append("                .");
            if (model.asyncDisposer()) {
                source.append("destroyAsyncWith(");
            } else {
                source.append("destroyWith(");
            }
            source.append(beanName).append("::")
                    .append(model.disposer().get().getSimpleName()).append(")\n");
        }
        source.append("                .build();\n");
        source.append("    }\n\n");
        source.append("    public ");
        if (configured) {
            source.append("ConfiguredBeanDefinition<")
                    .append(configName).append(", ").append(beanTypeName).append(">");
        } else {
            source.append("BeanDefinition<").append(beanTypeName).append(">");
        }
        source.append(" definition() {\n        return definition;\n    }\n\n");
        source.append("    public String factoryId() {\n")
                .append("        return definition.factoryId();\n    }\n\n");
        source.append("    private static <T> Class<T> contractClass(Class<?> actual) {\n")
                .append("        return (Class<T>) actual;\n    }\n");
        source.append("}\n");
        return source.toString();
    }

    private int dependencyIndex(ParameterInfo parameter, List<ParameterInfo> parameters) {
        int index = 0;
        for (ParameterInfo current : parameters) {
            if (current == parameter) {
                return index;
            }
            if (current.kind() != ParameterKind.CONFIG) {
                index++;
            }
        }
        throw new IllegalArgumentException("missing dependency");
    }

    private String qualifiedTypeName(TypeMirror type) {
        return type.toString();
    }

    private String erasedClassLiteral(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            throw new IllegalArgumentException("primitive capability contract");
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return type.toString() + ".class";
        }
        Element element = types.asElement(types.erasure(type));
        if (element instanceof TypeElement typeElement) {
            return typeElement.getQualifiedName() + ".class";
        }
        throw new IllegalArgumentException("unsupported capability contract: " + type);
    }

    private String stringLiteral(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private AnnotationMirror mirror(Element element, String annotationName) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().toString().equals(annotationName)) {
                return annotation;
            }
        }
        return null;
    }

    private Map<String, AnnotationValue> values(AnnotationMirror annotation) {
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

    private List<AnnotationValue> annotationList(AnnotationValue value) {
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

    private String stringValue(AnnotationValue value, String fallback) {
        return value == null || value.getValue() == null
                ? fallback
                : String.valueOf(value.getValue());
    }

    private String enumValue(AnnotationValue value, String fallback) {
        if (value == null || !(value.getValue() instanceof VariableElement variable)) {
            return fallback;
        }
        return variable.getSimpleName().toString();
    }

    private TypeMirror typeValue(AnnotationValue value) {
        if (value == null || !(value.getValue() instanceof TypeMirror type)) {
            return elements.getTypeElement("java.lang.Void").asType();
        }
        return type;
    }

    @SuppressWarnings("unchecked")
    private <T> T value(AnnotationValue annotationValue, Class<T> type, T fallback) {
        if (annotationValue == null || annotationValue.getValue() == null) {
            return fallback;
        }
        Object value = annotationValue.getValue();
        return type.isInstance(value) ? (T) value : fallback;
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record ParameterInfo(
            ParameterKind kind,
            String name,
            TypeMirror keyType,
            boolean required) {
    }

    private enum ParameterKind {
        REQUIRED("@KnotraRequire"),
        OPTIONAL("@KnotraOptional"),
        DYNAMIC("@KnotraDynamicProxy"),
        CONFIG("@KnotraConfig");

        private final String annotationName;

        ParameterKind(String annotationName) {
            this.annotationName = annotationName;
        }

        private String annotationName() {
            return annotationName;
        }
    }

    private record OutputValidation(List<OutputInfo> outputs, boolean valid) {
    }

    private record OutputInfo(String name, TypeMirror contract) {
    }

    private record Model(
            TypeElement type,
            String id,
            TypeMirror configType,
            boolean unmanaged,
            List<ParameterInfo> parameters,
            List<OutputInfo> outputs,
            Optional<ExecutableElement> initializer,
            Optional<ExecutableElement> disposer,
            boolean asyncDisposer,
            Optional<ExecutableElement> normalizer) {
    }
}
