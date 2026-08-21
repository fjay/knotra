package io.knotra;

import java.util.concurrent.CompletionStage;

public interface ContextHandle extends AutoCloseable {
    String contextId();
    ContextInfo contextInfo();
    RuntimeContext context();

    ContextState state();

    CompletionStage<Void> disposeAsync();

    @Override
    default void close() throws Exception {
        disposeAsync().toCompletableFuture().get();
    }
}
