package io.knotra.loader;

import io.knotra.ContextHandle;
import io.knotra.MountHandle;

/** 一次成功受控挂载的产物，等待 {@link LoaderStateStore#register} 写入记账。 */
record MountAttempt(
        String path,
        String name,
        ContextHandle context,
        MountHandle handle,
        ResolvedFactory definition,
        Object config) {
}
