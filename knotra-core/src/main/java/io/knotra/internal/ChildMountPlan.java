package io.knotra.internal;

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
