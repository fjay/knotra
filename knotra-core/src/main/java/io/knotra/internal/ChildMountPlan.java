package io.knotra.internal;

/**
 * 组件启动代码暂存的子挂载计划。
 *
 * <p>该计划由 {@link ActivationContextImpl} 创建。父激活仅在成功提交后发布它；
 * 失败的激活会同时丢弃临时句柄与已准备好的组件。</p>
 */
final class ChildMountPlan {
    private final MountHandleImpl handle;
    private final String mountId;
    private final PreparedComponent<?> prepared;

    ChildMountPlan(MountHandleImpl handle, String mountId, PreparedComponent<?> prepared) {
        this.handle = handle;
        this.mountId = mountId;
        this.prepared = prepared;
    }

    MountHandleImpl handle() {
        return handle;
    }

    String mountId() {
        return mountId;
    }

    PreparedComponent<?> prepared() {
        return prepared;
    }
}
