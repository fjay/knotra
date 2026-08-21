package io.knotra;

import java.util.concurrent.CompletionStage;
public interface KnotraRuntime extends AutoCloseable {
    String runtimeId();

    ContextHandle rootContext();

    RuntimeContext context();

    RuntimeSnapshot snapshot();

    <R> MutationResult<R> mutate(java.util.function.Function<RuntimeMutation, R> action);

    CompletionStage<Void> closeAsync();

    static KnotraRuntime create(KnotraConfig config) {
        return new io.knotra.internal.DefaultKnotraRuntime(config);
    }

    static KnotraRuntime create() {
        return create(KnotraConfig.defaults());
    }

    @Override
    default void close() throws Exception {
        closeAsync().toCompletableFuture().get();
    }
}
