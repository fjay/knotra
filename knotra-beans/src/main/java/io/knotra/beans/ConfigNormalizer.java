package io.knotra.beans;

/** 在挂载与 reconfigure 前归一化类型化配置；返回 null 或抛出异常会以 INVALID_CONFIG 拒绝。 */
@FunctionalInterface
public interface ConfigNormalizer<C> {
    C normalize(C config) throws Exception;
}
