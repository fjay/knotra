package io.knotra;

import java.util.concurrent.CompletionStage;

/** 可异步排空并关闭的资源。 */
public interface AsyncCloseable extends AutoCloseable {
    CompletionStage<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }
}
