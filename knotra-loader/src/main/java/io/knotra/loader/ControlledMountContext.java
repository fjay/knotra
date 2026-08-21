package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ContextHandle;
import io.knotra.MountOptions;

/**
 * Loader 为单个期望条目分配的唯一受控挂载点。
 *
 * <p>受控挂载策略只能通过该接口访问分配好的 Context、挂载 ID 与一次类型化
 * 挂载操作，无法触及 KnotraRuntime、RuntimeMutation、任意 Context 的处置
 * 或宿主的 Capability 发布，从而把实现来源的结构权限限制在这一个槽位上。
 */
public interface ControlledMountContext {

    /** 分配给该条目的 Context；策略只能在这里挂载，不能另行选择目标。 */
    ContextHandle context();

    /**
     * 分配给该条目的挂载 ID，等于条目的归一化路径。
     * Loader 会校验返回的句柄确实绑定在该挂载 ID 上。
     */
    String mountId();

    /**
     * 在分配的槽位上执行一次类型化挂载。
     *
     * <p>实现通常只允许成功调用一次：重复使用或 Context 已非 ACTIVE 会以
     * {@link ControlledMountException} 携带结构化诊断失败。
     */
    <C> CompletionStage<ComponentHandle<C>> mount(
            ComponentFactory<C> factory,
            C config,
            MountOptions options);
}
