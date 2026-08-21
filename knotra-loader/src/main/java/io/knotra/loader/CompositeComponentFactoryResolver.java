package io.knotra.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 组合解析器：按声明顺序依次尝试子解析器，返回第一个命中的定义。
 *
 * <p>声明顺序即优先级顺序，不做结果合并；所有子解析器都未命中时返回空
 * Optional。可用于把 classpath 解析器与 artifact 桥接解析器叠加成单一入口。
 */
public final class CompositeComponentFactoryResolver implements ComponentFactoryResolver {

    private final List<ComponentFactoryResolver> resolvers;

    private CompositeComponentFactoryResolver(List<ComponentFactoryResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    /** 按参数顺序组合解析器，靠前的解析器优先。 */
    public static ComponentFactoryResolver of(ComponentFactoryResolver... resolvers) {
        return new CompositeComponentFactoryResolver(List.of(resolvers));
    }

    /** 按列表顺序组合解析器，靠前的解析器优先。 */
    public static ComponentFactoryResolver of(List<ComponentFactoryResolver> resolvers) {
        return new CompositeComponentFactoryResolver(resolvers);
    }

    /** 依次委托子解析器，第一个非空结果胜出。 */
    @Override
    public Optional<ResolvedComponentDefinition> resolve(FactoryRef ref) {
        for (ComponentFactoryResolver resolver : resolvers) {
            Optional<ResolvedComponentDefinition> definition = resolver.resolve(ref);
            if (definition.isPresent()) {
                return definition;
            }
        }
        return Optional.empty();
    }

    /** 返回子解析器列表副本，顺序即解析优先级。 */
    public List<ComponentFactoryResolver> resolvers() {
        return new ArrayList<>(resolvers);
    }
}
