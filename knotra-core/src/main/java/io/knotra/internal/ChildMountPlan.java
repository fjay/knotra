package io.knotra.internal;

/**
 * A child mount staged by component start code.
 *
 * <p>The plan is created by {@link ActivationContextImpl}. The parent activation publishes it only
 * after successful commit; a failed activation discards both the provisional handle and prepared
 * component.</p>
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
