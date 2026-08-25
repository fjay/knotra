package io.knotra.beans.processor;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/** 将校验后的 Bean 模型渲染为统一调用 Beans Fluent DSL 的确定性工厂源码。 */
final class FactorySourceRenderer {

    private final ValidationContext context;

    FactorySourceRenderer(ValidationContext context) {
        this.context = context;
    }

    String render(BeanModel model, String packageName, String generatedSimpleName) {
        StringBuilder source = renderHeader(model, packageName, generatedSimpleName);
        renderDependencyConstants(source, model);
        renderConstructor(source, model, generatedSimpleName);
        renderAccessors(source, model, generatedSimpleName);
        return source.append("}\n").toString();
    }

    private StringBuilder renderHeader(
            BeanModel model,
            String packageName,
            String generatedSimpleName) {
        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("import io.knotra.CapabilityKey;\n");
        source.append("import io.knotra.beans.BeanDefinition;\n");
        source.append("import io.knotra.beans.BeanDependency;\n");
        source.append("import io.knotra.beans.Beans;\n");
        if (isConfigured(model)) {
            source.append("import io.knotra.beans.ConfiguredBeanDefinition;\n");
        }
        source.append("\npublic final class ").append(generatedSimpleName).append(" {\n\n");
        return source;
    }

    private void renderDependencyConstants(
            StringBuilder source,
            BeanModel model) {
        int keyIndex = 0;
        for (ParameterInfo parameter : model.parameters()) {
            if (parameter.kind() == ParameterKind.CONFIG) {
                continue;
            }
            source.append("    private static final CapabilityKey<")
                    .append(qualifiedTypeName(parameter.keyType()))
                    .append("> KEY_").append(keyIndex++)
                    .append(" = CapabilityKey.of(")
                    .append(stringLiteral(parameter.name())).append(",\n")
                    .append("            ")
                    .append(erasedClassLiteral(parameter.keyType())).append(");\n");
        }

        int outputIndex = 0;
        for (OutputInfo output : model.outputs()) {
            source.append("    private static final CapabilityKey<")
                    .append(qualifiedTypeName(output.contract()))
                    .append("> OUTPUT_").append(outputIndex++)
                    .append(" = CapabilityKey.of(")
                    .append(stringLiteral(output.name())).append(",\n")
                    .append("            ")
                    .append(erasedClassLiteral(output.contract())).append(");\n");
        }

        int depIndex = 0;
        boolean hasDependencies = false;
        for (ParameterInfo parameter : model.parameters()) {
            if (parameter.kind() == ParameterKind.CONFIG) {
                continue;
            }
            if (!hasDependencies) {
                source.append("\n");
                hasDependencies = true;
            }
            source.append("    private static final ");
            if (parameter.kind() == ParameterKind.FIXED) {
                source.append("BeanDependency<")
                        .append(qualifiedTypeName(parameter.keyType()))
                        .append("> DEP_").append(depIndex)
                        .append(" = Beans.fixed(KEY_").append(depIndex).append(");\n");
            } else if (parameter.kind() == ParameterKind.OPTIONAL) {
                source.append("BeanDependency<java.util.Optional<")
                        .append(qualifiedTypeName(parameter.keyType()))
                        .append(">> DEP_").append(depIndex)
                        .append(" = Beans.fixedOptional(KEY_").append(depIndex).append(");\n");
            } else if (parameter.required()) {
                source.append("BeanDependency<")
                        .append(qualifiedTypeName(parameter.keyType()))
                        .append("> DEP_").append(depIndex)
                        .append(" = Beans.dynamic(KEY_").append(depIndex).append(");\n");
            } else {
                source.append("BeanDependency<")
                        .append(qualifiedTypeName(parameter.keyType()))
                        .append("> DEP_").append(depIndex)
                        .append(" = Beans.dynamicOptional(KEY_").append(depIndex).append(");\n");
            }
            depIndex++;
        }
    }

    private void renderConstructor(
            StringBuilder source,
            BeanModel model,
            String generatedSimpleName) {
        TypeElement type = model.type();
        String beanName = type.getQualifiedName().toString();
        boolean configured = isConfigured(model);
        String configName = configured ? qualifiedTypeName(model.configType()) : null;
        String beanTypeName = qualifiedTypeName(type.asType());
        long depCount = model.parameters().stream()
                .filter(p -> p.kind() != ParameterKind.CONFIG)
                .count();

        source.append("\n    private final ");
        appendDefinitionType(source, model, configName, beanTypeName);
        source.append(" definition;\n\n");
        source.append("    public ").append(generatedSimpleName).append("() {\n");
        source.append("        this.definition = Beans.component(")
                .append(stringLiteral(model.id()));
        if (configured) {
            source.append(", ").append(configName).append(".class");
        }
        source.append(")\n");

        if (depCount > 0) {
            source.append("                .with(\n");
            boolean firstDep = true;
            for (int i = 0; i < depCount; i++) {
                if (!firstDep) {
                    source.append(",\n");
                }
                firstDep = false;
                source.append("                        DEP_").append(i);
            }
            source.append(")\n");
        }

        source.append("                .create(");
        if (configured) {
            if (depCount == 0) {
                if (model.parameters().isEmpty()) {
                    source.append("config -> new ").append(beanName).append("()");
                } else {
                    source.append("config -> new ").append(beanName).append("(config)");
                }
            } else {
                source.append("(config, deps) -> new ").append(beanName).append("(\n");
                boolean first = true;
                int dependencyIndex = 0;
                for (ParameterInfo parameter : model.parameters()) {
                    if (!first) {
                        source.append(",\n");
                    }
                    first = false;
                    if (parameter.kind() == ParameterKind.CONFIG) {
                        source.append("                        config");
                    } else {
                        source.append("                        deps.get(DEP_").append(dependencyIndex++).append(")");
                    }
                }
                source.append(")");
            }
        } else {
            if (depCount == 0) {
                source.append("() -> new ").append(beanName).append("()");
            } else {
                source.append("deps -> new ").append(beanName).append("(\n");
                boolean first = true;
                int dependencyIndex = 0;
                for (ParameterInfo parameter : model.parameters()) {
                    if (!first) {
                        source.append(",\n");
                    }
                    first = false;
                    source.append("                        deps.get(DEP_").append(dependencyIndex++).append(")");
                }
                source.append(")");
            }
        }
        source.append(")\n");

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
    }

    private void renderAccessors(
            StringBuilder source,
            BeanModel model,
            String generatedSimpleName) {
        TypeElement type = model.type();
        String configName = isConfigured(model) ? qualifiedTypeName(model.configType()) : null;
        String beanTypeName = qualifiedTypeName(type.asType());

        source.append("    public ");
        appendDefinitionType(source, model, configName, beanTypeName);
        source.append(" definition() {\n        return definition;\n    }\n\n");
        source.append("    public String factoryId() {\n")
                .append("        return definition.factoryId();\n    }\n\n");
    }

    private void appendDefinitionType(
            StringBuilder source,
            BeanModel model,
            String configName,
            String beanTypeName) {
        if (isConfigured(model)) {
            source.append("ConfiguredBeanDefinition<")
                    .append(configName).append(", ").append(beanTypeName).append(">");
        } else {
            source.append("BeanDefinition<").append(beanTypeName).append(">");
        }
    }

    private boolean isConfigured(BeanModel model) {
        TypeElement noConfig = context.elements().getTypeElement("io.knotra.NoConfig");
        return noConfig == null
                || !context.types().isSameType(model.configType(), noConfig.asType());
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
        Element element = context.types().asElement(context.types().erasure(type));
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
}
