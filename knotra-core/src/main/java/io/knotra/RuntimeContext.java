package io.knotra;

import java.util.Optional;

/**
 * 指定 Context 的只读读取视图。
 *
 * <p>查找遵循 Context 层级可见性：可解析发布在自身及其祖先中的 Capability，
 * 子 Context 的注册遮蔽父 Context。实现线程安全，不暴露任何可变内部结构。
 */
public interface RuntimeContext {
    String contextId();

    /**
     * 解析 Capability，缺失时失败。
     *
     * @throws IllegalStateException Capability 在该 Context 中不可见，或注册值与合约类型不匹配
     */
    <T> T require(CapabilityKey<T> key);

    /** 解析 Capability；不可见或被遮蔽时返回空。 */
    <T> Optional<T> find(CapabilityKey<T> key);

    /** 返回该 Context 的元数据；Context 已释放时返回 DISPOSED 占位信息。 */
    ContextInfo info();
}
