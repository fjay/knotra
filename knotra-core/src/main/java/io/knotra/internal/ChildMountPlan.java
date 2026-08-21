package io.knotra.internal;


/**
 * 用户 {@code start()} 中提出的子挂载暂存计划。
 *
 * <p>计划由 {@link ActivationContextImpl} 创建，只包含临时 {@link ComponentHandleImpl}、挂载 ID 和
 * 已归一化的 {@link PreparedComponent}；父 Activation 提交成功后才由
 * {@link DefaultKnotraRuntime} 写入视图并创建子 {@link ComponentRuntime}，失败则连同临时句柄一起废弃。</p>
 */
final class ChildMountPlan<C> {
    private final ComponentHandleImpl<C> handle;
    private final String mountId;
    private final PreparedComponent<C> prepared;

    ChildMountPlan(
            ComponentHandleImpl<C> handle,
            String mountId,
            PreparedComponent<C> prepared) {
        this.handle = handle;
        this.mountId = mountId;
        this.prepared = prepared;
    }

    ComponentHandleImpl<C> handle() {
        return handle;
    }

    String mountId() {
        return mountId;
    }

    PreparedComponent<C> prepared() {
        return prepared;
    }
}
