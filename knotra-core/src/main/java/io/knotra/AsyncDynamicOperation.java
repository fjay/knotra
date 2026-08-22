package io.knotra;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncDynamicOperation<T, R> {

    CompletionStage<R> execute(T capability) throws Exception;
}
