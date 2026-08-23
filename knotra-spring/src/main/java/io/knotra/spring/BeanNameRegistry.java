package io.knotra.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 模块内 Spring Bean 名称的所有权表。
 *
 * <p>配置 Bean、依赖 Bean 和具名输出共享同一套冲突规则，且校验不依赖 DSL 声明顺序。
 * 依赖与输出保持声明顺序，供手动 singleton 注册和诊断输出使用。</p>
 */
final class BeanNameRegistry {

    static final BeanNameRegistry EMPTY = new BeanNameRegistry(
            Optional.empty(), List.of(), List.of());

    private final Optional<String> configBeanName;
    private final List<SpringDependency<?>> dependencies;
    private final List<SpringOutput<?>> outputs;

    private BeanNameRegistry(
            Optional<String> configBeanName,
            List<SpringDependency<?>> dependencies,
            List<SpringOutput<?>> outputs) {
        this.configBeanName = Objects.requireNonNull(configBeanName);
        this.dependencies = List.copyOf(dependencies);
        this.outputs = List.copyOf(outputs);
    }

    BeanNameRegistry withConfigBeanName(String configBeanName) {
        String name = SpringDependency.requireBeanName(configBeanName);
        rejectIfDependency(name, "config bean name");
        rejectIfOutput(name);
        return new BeanNameRegistry(Optional.of(name), dependencies, outputs);
    }

    BeanNameRegistry withDependency(SpringDependency<?> dependency) {
        Objects.requireNonNull(dependency, "dependency");
        if (dependencies.stream().anyMatch(existing ->
                existing.beanName().equals(dependency.beanName()))) {
            throw new IllegalArgumentException("duplicate dependency bean name: "
                    + dependency.beanName());
        }
        if (dependencies.stream().anyMatch(existing ->
                existing.key().name().equals(dependency.key().name()))) {
            throw new IllegalArgumentException("duplicate dependency capability: "
                    + dependency.key().name());
        }
        configBeanName.filter(dependency.beanName()::equals).ifPresent(name -> {
            throw new IllegalArgumentException(
                    "config bean name is already used by a dependency: " + name);
        });
        if (outputs.stream().anyMatch(output ->
                output.beanName().filter(dependency.beanName()::equals).isPresent())) {
            throw new IllegalArgumentException("dependency bean name is already used by an output: "
                    + dependency.beanName());
        }
        List<SpringDependency<?>> next = new ArrayList<>(dependencies);
        next.add(dependency);
        return new BeanNameRegistry(configBeanName, next, outputs);
    }

    BeanNameRegistry withOutput(SpringOutput<?> output) {
        Objects.requireNonNull(output, "output");
        if (outputs.stream().anyMatch(existing ->
                existing.key().name().equals(output.key().name()))) {
            throw new IllegalArgumentException("duplicate output capability: "
                    + output.key().name());
        }
        output.beanName().ifPresent(name -> {
            rejectIfDependency(name, "output bean name");
            rejectIfOutput(name);
        });
        List<SpringOutput<?>> next = new ArrayList<>(outputs);
        next.add(output);
        return new BeanNameRegistry(configBeanName, dependencies, next);
    }

    List<SpringDependency<?>> dependencies() {
        return dependencies;
    }

    List<SpringOutput<?>> outputs() {
        return outputs;
    }

    private void rejectIfDependency(String beanName, String requestedUse) {
        if (dependencies.stream().anyMatch(dependency ->
                dependency.beanName().equals(beanName))) {
            throw new IllegalArgumentException(
                    requestedUse + " bean name is already used by a dependency: " + beanName);
        }
    }

    private void rejectIfOutput(String beanName) {
        if (outputs.stream().anyMatch(output ->
                output.beanName().filter(beanName::equals).isPresent())) {
            throw new IllegalArgumentException(
                    "config bean name is already used by an output: " + beanName);
        }
    }
}
