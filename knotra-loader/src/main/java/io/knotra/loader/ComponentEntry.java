package io.knotra.loader;

import java.util.List;

/**
 * 期望组件树中的单个声明条目：路径、工厂引用、原始配置与嵌套子声明。
 *
 * <p>{@link #of} 表示无配置声明；{@link #configured} 表示 resolver 边界持有的
 * raw 配置。Loader 会在任何结构修改之前通过 ResolvedFactory 的 decoder 把
 * raw 值转换为 Core 的类型化配置。</p>
 *
 * @param path 相对或绝对的条目路径，非空白
 * @param factoryRef 期望使用的工厂引用
 * @param rawConfig 原始配置；null 表示无配置
 * @param children 嵌套子声明，路径相对本条目解析
 */
public record ComponentEntry(
        String path,
        FactoryRef factoryRef,
        Object rawConfig,
        List<ComponentEntry> children) {

    public ComponentEntry {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("entry path must not be blank");
        }
        if (factoryRef == null) {
            throw new IllegalArgumentException("factoryRef must not be null");
        }
        children = List.copyOf(children == null ? List.of() : children);
    }

    /** 构造无配置声明；相对路径由父声明拼接。 */
    public static ComponentEntry of(
            String path,
            FactoryRef factoryRef,
            ComponentEntry... children) {
        return new ComponentEntry(path, factoryRef, null, List.of(children));
    }

    /** 构造 raw 配置声明；配置解释由 resolver 提供的 decoder 决定。 */
    public static ComponentEntry configured(
            String path,
            FactoryRef factoryRef,
            Object rawConfig,
            ComponentEntry... children) {
        if (rawConfig == null) {
            throw new IllegalArgumentException("rawConfig must not be null; use of(path, factoryRef, children)");
        }
        return new ComponentEntry(path, factoryRef, rawConfig, List.of(children));
    }
}
