package io.knotra.loader;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;

import static org.junit.jupiter.api.Assertions.*;

/** 恶意 / 循环 Throwable 不得阻塞协调器，也不得污染 Loader 诊断文本。 */
final class LoaderSafetyTest {

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void safeErrorBoundsCyclicCauseChains() {
        IllegalStateException first = new IllegalStateException("cycle-root");
        RuntimeException second = new RuntimeException("cycle-outer", first);
        first.initCause(second);
        Throwable selfCause = new Throwable("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String text = LoaderErrors.safe(second);
            assertTrue(text.length() <= 500, () -> text);
            assertTrue(text.contains("cycle-root"), text);
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertTrue(LoaderErrors.safe(selfCause).length() <= 500));
    }

    @Test
    void safeErrorContainsThrowingGetters() {
        Throwable throwingCause = new RuntimeException("visible-cause") {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("cause getter exploded");
            }
        };
        Throwable throwingMessage = new RuntimeException("hidden") {
            @Override
            public String getMessage() {
                throw new IllegalStateException("message getter exploded");
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String text = LoaderErrors.safe(throwingCause);
            assertTrue(text.contains("visible-cause"), text);
            assertTrue(text.length() <= 500, text);
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String text = LoaderErrors.safe(throwingMessage);
            assertTrue(text.length() <= 500, text);
        });
    }

    @Test
    void safeErrorBoundsDeepChainsAndHugeMessages() {
        Throwable deep = new RuntimeException("deep-root");
        for (int index = 0; index < 100; index++) {
            deep = new RuntimeException(deep);
        }
        final Throwable boundedDepth = deep;
        Throwable huge = new RuntimeException("huge".repeat(250_000));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertTrue(LoaderErrors.safe(boundedDepth).length() <= 500));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals(500, LoaderErrors.safe(huge).length()));
    }

    @Test
    void maliciousResolverFailureProducesBoundedDiagnostics() {
        FactoryRef ref = FactoryRef.of("evil-resolver");
        IllegalStateException first = new IllegalStateException("resolver-cycle");
        RuntimeException malicious = new RuntimeException("resolver-outer", first);
        first.initCause(malicious);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted -> {
            throw malicious;
        });
        try {
            ReconcileResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> loader.reconcile(ComponentTree.of(
                            LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.RESOLUTION_FAILED);
            assertTrue(result.diagnostics().stream()
                    .allMatch(diagnostic -> diagnostic.message().length() <= 500),
                    () -> result.diagnostics().toString());
        } finally {
            loader.close();
        }
    }

    @Test
    void maliciousMountFailureProducesBoundedDiagnostics() {
        FactoryRef ref = FactoryRef.of("evil-mount");
        IllegalStateException first = new IllegalStateException("mount-cycle");
        RuntimeException malicious = new RuntimeException("mount-outer", first);
        first.initCause(malicious);
        ResolvedFactory definition = new ResolvedFactory(
                FactoryIdentity.of("evil", "", "test"),
                ResolvedFactory.FactoryKind.PLAIN,
                null,
                (context, config) -> CompletableFuture.failedFuture(malicious),
                ReconfigureStrategy.unsupportedPlain());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                wanted -> Optional.of(definition));
        try {
            ReconcileResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> loader.reconcile(ComponentTree.of(
                            LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.STRUCTURE_REJECTED);
            assertTrue(result.diagnostics().stream()
                    .allMatch(diagnostic -> diagnostic.message().length() <= 500),
                    () -> result.diagnostics().toString());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        } finally {
            loader.close();
        }
    }
}
