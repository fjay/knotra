package io.knotra.loader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;
import io.knotra.MountOptions;

/**
 * 面向宿主 classpath 已可达实现的工厂解析器。
 *
 * <p>每个 FactoryRef 绑定一个 Core ComponentFactory；注册时生成实现指纹
 * （工厂类名 + 实例身份哈希），因此把同一引用改绑到新的工厂实例会改变
 * FactoryIdentity，触发 Loader 的替换路径而不是重配置。同一引用重复注册
 * 会在构建前被拒绝。解析器不可变，构建完成后可以安全共享。
 */
public final class ClasspathComponentFactoryResolver implements ComponentFactoryResolver {

    private final Map<FactoryRef, ResolvedComponentDefinition> definitions;

    private ClasspathComponentFactoryResolver(
            Map<FactoryRef, ResolvedComponentDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    /** 创建构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 便捷构造：等价于逐项 {@code add} 后 {@code build}。 */
    @SafeVarargs
    public static ComponentFactoryResolver forFactories(
            Map.Entry<FactoryRef, ComponentFactory<?>>... entries) {
        Builder builder = builder();
        for (Map.Entry<FactoryRef, ComponentFactory<?>> entry : entries) {
            builder.add(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    /** 精确按引用查找；未注册的引用返回空 Optional。 */
    @Override
    public Optional<ResolvedComponentDefinition> resolve(FactoryRef ref) {
        return Optional.ofNullable(definitions.get(ref));
    }

    /** 逐项注册 classpath 工厂的构建器；注册顺序不影响查找结果。 */
    public static final class Builder {
        private final Map<FactoryRef, ResolvedComponentDefinition> definitions =
                new LinkedHashMap<>();

        /** 注册工厂，使用默认挂载选项；配置 schema 回退到工厂自带 schema。 */
        public <C> Builder add(FactoryRef ref, ComponentFactory<C> factory) {
            return add(ref, factory, null, MountOptions.DEFAULT);
        }

        /** 注册工厂与显式配置 schema，使用默认挂载选项。 */
        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                ConfigSchema<C> configSchema) {
            return add(ref, factory, configSchema, MountOptions.DEFAULT);
        }

        /** 注册工厂与显式挂载选项；配置 schema 回退到工厂自带 schema。 */
        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                MountOptions options) {
            return add(ref, factory, null, options);
        }

        /** 完整注册；同一引用重复注册抛出 IllegalArgumentException。 */
        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                ConfigSchema<C> configSchema,
                MountOptions options) {
            if (definitions.containsKey(ref)) {
                throw new IllegalArgumentException("duplicate factory reference: " + ref);
            }
            String fingerprint = factory.getClass().getName() + "#"
                    + Integer.toUnsignedString(System.identityHashCode(factory));
            definitions.put(ref, ResolvedComponentDefinition.of(
                    FactoryIdentity.fromRef(ref, fingerprint),
                    factory,
                    configSchema,
                    options));
            return this;
        }

        /** 构建不可变解析器。 */
        public ClasspathComponentFactoryResolver build() {
            return new ClasspathComponentFactoryResolver(definitions);
        }
    }
}
