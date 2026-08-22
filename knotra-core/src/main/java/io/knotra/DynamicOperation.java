package io.knotra;

@FunctionalInterface
public interface DynamicOperation<T, R> {

    R execute(T capability) throws Exception;
}
