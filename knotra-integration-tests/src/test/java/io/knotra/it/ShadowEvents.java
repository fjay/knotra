package io.knotra.it;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import io.knotra.events.EventDefinition;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class ShadowEvents {

    record ShadowEvent<T>(EventDefinition.Serial<T> definition, T value) { }

    private ShadowEvents() {
    }

    static ShadowEvent<?> load(Path source, String className, String argument)
            throws Exception {
        URL url = source.toUri().toURL();
        try (URLClassLoader independent = new URLClassLoader(new URL[]{url}, null)) {
            Class<?> shadow = Class.forName(className, false, independent);
            Object event = shadow.getDeclaredConstructor(String.class).newInstance(argument);
            return new ShadowEvent<>(definition(shadow), event);
        }
    }

    static Throwable failedStageCause(CompletionStage<?> stage) throws Exception {
        CompletableFuture<?> future = stage.toCompletableFuture();
        try {
            future.get(10, TimeUnit.SECONDS);
            return fail("stage completed normally, expected failure");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            assertTrue(cause != null, () -> String.valueOf(failure));
            return cause;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> EventDefinition.Serial<T> definition(Class<?> shadow) {
        return EventDefinition.serial((Class<T>) shadow);
    }
}
