package io.knotra;

import java.util.concurrent.CompletionStage;

public interface ComponentHandle<C> extends AutoCloseable {
    String handleId();

    String mountId();

    String componentId();

    String factoryId();

    String contextId();

    ComponentState state();

    ComponentGoal goal();

    long configRevision();

    CompletionStage<ComponentState> whenSettled();

    CompletionStage<ComponentState> reconfigure(C config);

    CompletionStage<ComponentState> retry();

    CompletionStage<ComponentState> dispose();

    @Override
    default void close() throws Exception {
        dispose().toCompletableFuture().get();
    }
}
