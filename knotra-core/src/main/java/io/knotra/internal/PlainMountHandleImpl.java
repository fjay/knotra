package io.knotra.internal;

/** 无公开配置类型的普通挂载运行时句柄。 */
final class PlainMountHandleImpl extends MountHandleImpl {
    PlainMountHandleImpl(DefaultKnotraRuntime runtime, String id, Identity identity) {
        super(runtime, id, identity);
    }
}
