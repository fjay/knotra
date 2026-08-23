package io.knotra.loader;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LoaderTimeoutsTest {

    @Test
    void defaultsCoverAllBoundedWaits() {
        LoaderTimeouts timeouts = LoaderTimeouts.DEFAULTS;

        assertEquals(Duration.ofSeconds(30), timeouts.settlement());
        assertEquals(Duration.ofSeconds(30), timeouts.recovery());
        assertEquals(Duration.ofSeconds(30), timeouts.runtimeDisposal());
        assertEquals(Duration.ofMillis(10), timeouts.contextPoll());
        assertEquals(3_000, timeouts.contextPollTicks());
    }

    @Test
    void mountInjectionKeepsRuntimeDisposalAndPollInterval() {
        LoaderTimeouts replaced = LoaderTimeouts.DEFAULTS
                .withMountTimeouts(Duration.ofMillis(50), Duration.ofMillis(80));

        assertEquals(Duration.ofMillis(50), replaced.settlement());
        assertEquals(Duration.ofMillis(80), replaced.recovery());
        assertEquals(LoaderTimeouts.DEFAULTS.runtimeDisposal(), replaced.runtimeDisposal());
        assertEquals(LoaderTimeouts.DEFAULTS.contextPoll(), replaced.contextPoll());
    }

    @Test
    void nonPositiveValuesAreRejected() {
        assertThrows(NullPointerException.class, () ->
                new LoaderTimeouts(null, Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new LoaderTimeouts(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new LoaderTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(-1),
                        Duration.ofSeconds(1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new LoaderTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ZERO, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new LoaderTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ZERO));
    }
}
