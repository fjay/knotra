package io.knotra.it;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RetainedGraphScannerTest {

    private static final String PLUGIN_PACKAGE =
            "io.knotra.it.RetainedGraphScannerTest.";
    private static final String PLUGIN_TYPE =
            "io.knotra.it.RetainedGraphScannerTest$IntegrationPlugin";
    record Holder(Map<Class<?>, Object> values) { }

    record CycleHolder(Map<Object, Object> values) { }

    private static final class Hidden { }

    private static final class IntegrationPlugin { }

    @Test
    void scansMapEntriesForRetainedClasses() {
        Map<Class<?>, Object> values = new LinkedHashMap<>();
        values.put(Hidden.class, "value");
        Holder holder = new Holder(values);

        AssertionError failure = assertThrows(AssertionError.class, () ->
                RetainedGraphScanner.denyingClasses(PLUGIN_PACKAGE).assertPure(holder));
        assertTrue(failure.getMessage().contains("Hidden"), failure::getMessage);
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

    @Test
    void atomicReferencesExposeTheirSemanticValue() {
        assertThrows(AssertionError.class, () -> RetainedGraphScanner
                .denyingClasses("com.example.absent.")
                .assertPure(new AtomicReference<>(new ClassLoader() { })));

        AssertionError pluginFailure = assertThrows(AssertionError.class, () ->
                RetainedGraphScanner.denyingClasses(PLUGIN_TYPE)
                        .assertPure(new AtomicReference<>(new IntegrationPlugin())));
        assertTrue(pluginFailure.getMessage().contains("IntegrationPlugin"),
                pluginFailure::getMessage);
    }

    @Test
    void anonymousClassCaptureFieldsAreScanned() {
        Object plugin = new IntegrationPlugin();
        Object captured = new Object() {
            @Override
            public String toString() {
                return plugin.toString();
            }
        };

        AssertionError failure = assertThrows(AssertionError.class, () ->
                RetainedGraphScanner.denyingClasses(PLUGIN_TYPE).assertPure(captured));
        assertTrue(failure.getMessage().contains("IntegrationPlugin"), failure::getMessage);
    }

    @Test
    void unknownJdkValuesFailClosedInsteadOfBeingTreatedAsOpaque() {
        Object unknownJdkValue = new java.util.Random(1L);

        AssertionError failure = assertThrows(AssertionError.class, () ->
                RetainedGraphScanner.denyingClasses("com.example.absent.")
                        .assertPure(unknownJdkValue));
        assertTrue(failure.getMessage().contains("java.util.Random"), failure::getMessage);
        assertTrue(failure.getMessage().contains("unsupported JDK value"),
                failure::getMessage);
    }

    @Test
    void cyclicMapsTerminate() {
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put(values, "self-key");
        values.put("self-value", values);

        assertDoesNotThrow(() -> RetainedGraphScanner
                .denyingClasses("com.example.absent.")
                .assertPure(new CycleHolder(values)));
    }
}
