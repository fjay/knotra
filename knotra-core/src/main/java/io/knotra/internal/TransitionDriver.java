package io.knotra.internal;

import java.util.concurrent.CompletableFuture;

import io.knotra.ComponentState;

/** 提交一个已预约组件过渡的实际状态机入口；实现不得在 coordinator 内调用。 */
@FunctionalInterface
interface TransitionDriver {
    void drive(ComponentRuntime component, CompletableFuture<ComponentState> future);
}
