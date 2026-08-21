package io.knotra;

import java.util.Optional;

/**
 * 组件工厂：创建组件实例并声明其配置契约。
 *
 * <p>{@code create()} 在挂载时恰好调用一次；创建的组件实例在同一挂载的多次 Activation
 * （重新激活）之间复用。可选的配置 schema 用于在挂载与重配置时归一化配置；
 * 无配置组件使用 {@link NoConfig#INSTANCE}，配置不得为 null。
 */
public interface ComponentFactory<C> {
    String factoryId();

    /** 创建组件实例；返回 null 会导致挂载事务被拒绝。 */
    Component<C> create();

    /** 返回配置 schema；为空表示该工厂不做配置归一化，配置原样传给组件。 */
    default Optional<ConfigSchema<C>> configSchema() {
        return Optional.empty();
    }
}
