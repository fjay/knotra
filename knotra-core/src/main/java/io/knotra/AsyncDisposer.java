package io.knotra;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncDisposer {
    CompletionStage<Void> dispose();
}
