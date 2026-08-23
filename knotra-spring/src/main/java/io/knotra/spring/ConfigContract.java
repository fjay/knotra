package io.knotra.spring;

import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import io.knotra.NoConfig;

/**
 * 模块的公开配置契约。
 *
 * <p>sealed 变体显式区分无配置模块和类型化配置模块，替代可空配置字段加布尔状态的组合。</p>
 */
sealed interface ConfigContract<C> {

    static NoConfigContract noConfig() {
        return new NoConfigContract();
    }

    static <C> TypedConfigContract<C> typed(Class<C> configType, String configBeanName) {
        return new TypedConfigContract<>(configType, configBeanName, Optional.empty());
    }

    Class<C> configType();

    boolean configured();

    Optional<String> configBeanName();

    Optional<UnaryOperator<C>> configNormalizer();

    record NoConfigContract() implements ConfigContract<NoConfig> {

        @Override
        public Class<NoConfig> configType() {
            return NoConfig.class;
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public Optional<String> configBeanName() {
            return Optional.empty();
        }

        @Override
        public Optional<UnaryOperator<NoConfig>> configNormalizer() {
            return Optional.empty();
        }
    }

    record TypedConfigContract<C>(
            Class<C> configType,
            String beanName,
            Optional<UnaryOperator<C>> configNormalizer) implements ConfigContract<C> {

        public TypedConfigContract {
            Objects.requireNonNull(configType, "configType");
            beanName = SpringDependency.requireBeanName(beanName);
            Objects.requireNonNull(configNormalizer, "configNormalizer");
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public Optional<String> configBeanName() {
            return Optional.of(beanName);
        }
        TypedConfigContract<C> withBeanName(String nextBeanName) {
            return new TypedConfigContract<>(
                    configType, nextBeanName, configNormalizer);
        }

        TypedConfigContract<C> withNormalizer(UnaryOperator<C> nextNormalizer) {
            return new TypedConfigContract<>(
                    configType, beanName,
                    Optional.of(Objects.requireNonNull(nextNormalizer, "configNormalizer")));
        }
    }
}
