package io.knotra.loader;

import java.util.List;

/**
 * 期望组件树中的单个声明条目：路径、工厂引用、原始配置与嵌套子声明。
 *
 * <p>路径支持相对与绝对形式（统一以 {@code /} 为分隔符，反斜杠会被归一化）：
 * 单段相对路径会拼接在父声明路径之下，其他路径必须落在父路径之内，
 * {@code ..} 段一律拒绝。Loader 用归一化路径作为条目的稳定标识，
 * 等价路径复用同一挂载点，重复路径在准备阶段整批拒绝。
 *
 * @param path 相对或绝对的条目路径，非空白
 * @param factoryRef 期望使用的工厂引用
 * @param config 原始配置；null 在准备阶段折算为 {@code NoConfig.INSTANCE}，
 *               最终由解析出的定义归一化
 * @param children 嵌套子声明，路径相对本条目解析
 */
public record ComponentEntry(
        String path,
        FactoryRef factoryRef,
        Object config,
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

    /** 构造一个声明条目；相对路径由父声明拼接，children 为直接子声明。 */
    public static ComponentEntry of(
            String path,
            FactoryRef factoryRef,
            Object config,
            ComponentEntry... children) {
        return new ComponentEntry(path, factoryRef, config, List.of(children));
    }
}
