package io.knotra.internal;

/** Runtime handle for a mount whose configuration type is not public. */
final class PlainMountHandleImpl extends MountHandleImpl {
    PlainMountHandleImpl(DefaultKnotraRuntime runtime, String id, Identity identity) {
        super(runtime, id, identity);
    }
}
