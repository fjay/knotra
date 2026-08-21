package io.knotra;

@FunctionalInterface
public interface ConfigSchema<C> {
    C validate(Object raw) throws Exception;
}
