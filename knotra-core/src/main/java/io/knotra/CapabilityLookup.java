package io.knotra;

import java.util.Optional;

/** 按类型化 CapabilityKey 查找能力的公共契约。 */
public interface CapabilityLookup {

    /** 获取必须存在的能力实例，缺失时抛出运行时异常。 */
    <T> T require(CapabilityKey<T> key);

    /** 查询可选能力实例。 */
    <T> Optional<T> find(CapabilityKey<T> key);

    /** 基于类型便捷获取必须存在的能力实例。 */
    default <T> T require(Class<T> type) {
        return require(CapabilityKey.of(type));
    }

    /** 基于类型便捷查询可选能力实例。 */
    default <T> Optional<T> find(Class<T> type) {
        return find(CapabilityKey.of(type));
    }
}
