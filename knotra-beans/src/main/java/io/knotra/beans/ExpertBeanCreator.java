package io.knotra.beans;

import io.knotra.ActivationContext;

/**
 * Expert creator：直接基于 {@link ActivationContext} 与归一化 config 创建 Bean。
 *
 * <p>供注解处理器、Spring bridge 等集成使用；普通业务代码应使用
 * {@code Beans.component(...).create(...)} 的类型安全 creator 重载。</p>
 */
@FunctionalInterface
public interface ExpertBeanCreator<C, T> {
    T create(ActivationContext context, C config) throws Exception;
}
