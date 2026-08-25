package io.knotra.beans;

import io.knotra.ActivationContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link BeanDependencies} 的包内默认实现，基于对象标识拦截未声明的依赖解析请求。
 */
final class DefaultBeanDependencies implements BeanDependencies {

    private final String componentId;
    private final ActivationContext context;
    private final Set<BeanDependency<?>> declared;

    DefaultBeanDependencies(
            String componentId,
            ActivationContext context,
            List<BeanDependency<?>> declaredList) {
        this.componentId = Beans.requireComponentId(componentId);
        this.context = Objects.requireNonNull(context, "context");
        Set<BeanDependency<?>> identitySet = Collections.newSetFromMap(new IdentityHashMap<>());
        identitySet.addAll(Objects.requireNonNull(declaredList, "declaredList"));
        this.declared = identitySet;
    }

    @Override
    public <T> T get(BeanDependency<T> dependency) {
        Objects.requireNonNull(dependency, "dependency");
        if (!declared.contains(dependency)) {
            throw new IllegalArgumentException(
                    "dependency was not declared in .with(...) for component '"
                            + componentId + "': " + dependency.name());
        }
        return dependency.resolve(context);
    }
}
