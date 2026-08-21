package io.knotra;

import java.util.Objects;

/**
 * 组件工厂：为每个逻辑挂载创建一个组件实例，并归一化其类型化配置。
 *
 * <p>Core 只接受已经类型化的配置。Map、JSON tree 等外部形态由 Loader 或其他
 * 集成边界通过 {@link ConfigDecoder} 转换，避免在直接 mount API 中混淆 raw 值与 C。</p>
 */
public interface ComponentFactory<C> {
    /**
     * 返回用于诊断的实现标识。普通工厂默认使用类名；需要稳定跨版本身份时可显式覆盖。
     */
    default String factoryId() {
        return getClass().getName();
    }

    /** 创建组件实例；同一实例会跨该挂载的多次 Activation 复用。 */
    Component<C> create();

    /**
     * 校验并归一化类型化配置。返回 null 或抛出异常会以 INVALID_CONFIG 拒绝事务。
     */
    default C normalizeConfig(C config) throws Exception {
        return Objects.requireNonNull(config, "config");
    }
}
