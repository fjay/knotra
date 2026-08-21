package io.knotra.loader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

/**
 * 面向宿主 classpath 已可达实现的工厂解析器。
 *
 * <p>每个 FactoryRef 绑定一个 Core ComponentFactory；注册时生成实现指纹
 * （工厂类名 + 实例身份哈希），因此把同一引用改绑到新的工厂实例会改变
 * FactoryIdentity，触发 Loader 的替换路径而不是重配置。同一引用重复注册
 * 会在构建前被拒绝。解析器不可变，构建完成后可以安全共享。</p>
 */
public final class ClasspathFactoryResolver implements ComponentFactoryResolver {

    private final Map<FactoryRef, ResolvedFactory> factories;

    private ClasspathFactoryResolver(
            Map<FactoryRef, ResolvedFactory> factories) {
        this.factories = Map.copyOf(factories);
    }

    /** 创建构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 便捷构造：等价于逐项 {@code add} 后 {@code build}。 */
    @SafeVarargs
    public static ComponentFactoryResolver forFactories(
            Map.Entry<FactoryRef, ComponentFactory<NoConfig>>... entries) {
        Builder builder = builder();
        for (Map.Entry<FactoryRef, ComponentFactory<NoConfig>> entry : entries) {
            builder.add(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    /** 精确按引用查找；未注册的引用返回空 Optional。 */
    @Override
    public Optional<ResolvedFactory> resolve(FactoryRef ref) {
        return Optional.ofNullable(factories.get(ref));
    }

    /** 逐项注册 classpath 工厂的构建器；注册顺序不影响查找结果。 */
    public static final class Builder {
        private final Map<FactoryRef, ResolvedFactory> factories =
                new LinkedHashMap<>();

        /** 注册无配置工厂，使用默认挂载选项。 */
        public Builder add(
                FactoryRef ref,
                ComponentFactory<NoConfig> factory) {
            return add(ref, factory, MountOptions.DEFAULT);
        }

        /** 注册工厂与显式 raw 配置 decoder，使用默认挂载选项。 */
        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                ConfigDecoder<C> configDecoder) {
            return add(ref, factory, configDecoder, MountOptions.DEFAULT);
        }

        /** 注册无配置工厂与显式挂载选项。 */
        public Builder add(
                FactoryRef ref,
                ComponentFactory<NoConfig> factory,
                MountOptions options) {
            if (factories.containsKey(ref)) {
                throw new IllegalArgumentException("duplicate factory reference: " + ref);
            }
            factories.put(ref, ResolvedFactory.of(identity(ref, factory), factory, options));
            return this;
        }

        /** 完整注册；同一引用重复注册抛出 IllegalArgumentException。 */
        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                ConfigDecoder<C> configDecoder,
                MountOptions options) {
            if (factories.containsKey(ref)) {
                throw new IllegalArgumentException("duplicate factory reference: " + ref);
            }
            factories.put(ref, ResolvedFactory.of(
                    identity(ref, factory), factory, configDecoder, options));
            return this;
        }

        /** 构建不可变解析器。 */
        public ClasspathFactoryResolver build() {
            return new ClasspathFactoryResolver(factories);
        }

        private static FactoryIdentity identity(
                FactoryRef ref,
                ComponentFactory<?> factory) {
            String fingerprint = factory.getClass().getName() + "#"
                    + Integer.toUnsignedString(System.identityHashCode(factory));
            return FactoryIdentity.fromRef(ref, fingerprint);
        }
    }
}
