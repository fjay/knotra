package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;

/**
 * 受控挂载策略：通过 Loader 分配的单次使用槽位完成一次结构挂载。
 *
 * <p>策略收到的是已完成 schema 归一化的配置，以及绑定了专属 Context 与
 * 挂载 ID 的 {@link ControlledMountContext}。策略返回的句柄必须落在同一个
 * 分配槽位上；Loader 会校验 contextId 与 mountId，越界句柄会被立即释放，
 * 并使整批期望树被拒绝。
 */
@FunctionalInterface
public interface ControlledMountStrategy {

    /**
     * 在分配的挂载点上执行挂载。
     *
     * @param context Loader 分配的单次使用挂载点
     * @param normalizedConfig 已由定义的配置 schema 归一化的配置
     * @return 绑定在分配槽位上的组件句柄
     */
    CompletionStage<ComponentHandle<?>> mount(
            ControlledMountContext context,
            Object normalizedConfig);
}
