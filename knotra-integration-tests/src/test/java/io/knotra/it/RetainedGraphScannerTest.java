package io.knotra.it;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
final class RetainedGraphScannerTest {

    record Holder(Map<Class<?>, Object> values) { }

    private static final class Hidden { }

    @Test
    void scansMapEntriesForRetainedClasses() {
        Map<Class<?>, Object> values = new LinkedHashMap<>();
        values.put(Hidden.class, "value");
        Holder holder = new Holder(values);

        AssertionError failure = assertThrows(AssertionError.class, () ->
                RetainedGraphScanner.denyingClasses("io.knotra.it.RetainedGraphScannerTest.")
                        .assertPure(holder));
        assertTrue(failure.getMessage().contains("Hidden"),
                failure::getMessage);
    }

    @Test
    void classPolicyCanAllowNonPluginClasses() {
        Map<Class<?>, Object> values = new LinkedHashMap<>();
        values.put(Hidden.class, "value");
        Holder holder = new Holder(values);

        assertDoesNotThrow(() -> RetainedGraphScanner
                .allowingNonPluginClasses("com.example.absent.")
                .assertPure(holder));
    }
}
