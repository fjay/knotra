package io.knotra;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class FailureInfoTest {
    @Test
    void failureDetailPolicyBoundsCausesFramesAndText() {
        Throwable deep = chained(8);
        var policy = new KnotraConfig.FailureDetailPolicy(2, 2, 64, true);
        FailureInfo detail = FailureInfo.capture(
                deep,
                FailurePhase.ACTIVATION,
                policy,
                java.time.Instant.EPOCH);
        assertEquals(FailurePhase.ACTIVATION, detail.phase());
        assertEquals(2, detail.causes().size());
        assertEquals(2, detail.stackTrace().size());
        assertTrue(detail.stackTrace().stream().allMatch(frame -> frame.length() <= 64));
    }

    @Test
    void stackTracesAreDisabledByDefaultButExceptionTypeAndMessageAreRetained() {
        IllegalStateException error = new IllegalStateException("cannot start");
        FailureInfo detail = FailureInfo.capture(
                error,
                FailurePhase.ACTIVATION,
                KnotraConfig.defaults().failureDetailPolicy(),
                null);
        assertTrue(detail.stackTrace().isEmpty());
        assertEquals(IllegalStateException.class.getName(), detail.exceptionType());
        assertEquals("cannot start", detail.message());
        assertEquals(
                IllegalStateException.class.getName() + ": cannot start",
                detail.summary());
    }

    @Test
    void activationFailureDiagnosticsCarryStableFailureDetail() {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            AtomicBoolean once = new AtomicBoolean();
            MountHandle handle = TestKit.mount(runtime, runtime.root(), "fails", (context, config) -> {
                if (once.compareAndSet(false, true)) {
                    throw new IllegalArgumentException("bad input");
                }
            });
            try {
                handle.requireActive(java.time.Duration.ofSeconds(5));
            } catch (MountNotActiveException expected) {
                // The detail is asserted from the stable snapshot below.
            }
            RuntimeDiagnostic diagnostic = runtime.advanced().snapshot().diagnostics().stream()
                    .filter(item -> item.code() == DiagnosticCode.ACTIVATION_FAILED
                            && item.targetId().equals(handle.handleId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(IllegalArgumentException.class.getName(),
                    diagnostic.failure().exceptionType());
            assertEquals("bad input", diagnostic.failure().message());
        } finally {
            runtime.close();
        }
    }

    @Test
    void cleanupFailureDiagnosticsCarryStableFailureDetail() {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            MountHandle handle = TestKit.mount(runtime, runtime.root(), "bad-cleanup",
                    (context, config) -> context.lifecycle().onClose(
                            "bad", () -> {
                                throw new IllegalStateException("close failed");
                            }));
            handle.requireActive(java.time.Duration.ofSeconds(5));
            handle.disposeAsync().toCompletableFuture().join();
            RuntimeDiagnostic diagnostic = runtime.advanced().snapshot().diagnostics().stream()
                    .filter(item -> item.code() == DiagnosticCode.CLEANUP_FAILED
                            && item.targetId().equals(handle.handleId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(IllegalStateException.class.getName(),
                    diagnostic.failure().exceptionType());
            assertEquals("java.lang.IllegalStateException: close failed",
                    diagnostic.failure().message());
        } finally {
            try {
                runtime.close();
            } catch (RuntimeException expected) {
                // A failed root cleanup may already have completed close exceptionally.
            }
        }
    }

    @Test
    void captureToleratesMaliciousThrowablesAndCauseCycles() {
        MaliciousThrowable root = new MaliciousThrowable();
        MaliciousThrowable cause = new MaliciousThrowable(root);
        root.initCause(cause);

        FailureInfo detail = FailureInfo.capture(
                root,
                FailurePhase.ACTIVATION,
                new KnotraConfig.FailureDetailPolicy(5, 5, 500, true),
                java.time.Instant.EPOCH);

        assertEquals(MaliciousThrowable.class.getName(), detail.exceptionType());
        assertTrue(detail.causes().size() <= 5);
        assertEquals("<message unavailable>", detail.message());
    }

    @Test
    void maliciousActivationFailureReachesFailedWithoutHanging() throws Exception {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            MountHandle handle = TestKit.mount(runtime, runtime.root(), "malicious-start",
                    (context, config) -> {
                        throw new MaliciousThrowable();
                    });
            assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());
            RuntimeDiagnostic diagnostic = runtime.advanced().snapshot().diagnostics().stream()
                    .filter(item -> item.targetId().equals(handle.handleId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(MaliciousThrowable.class.getName(),
                    diagnostic.failure().exceptionType());
        } finally {
            runtime.close();
        }
    }

    @Test
    void maliciousCleanupFailureReachesFailedWithoutHanging() throws Exception {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            MountHandle handle = TestKit.mount(runtime, runtime.root(), "malicious-cleanup",
                    (context, config) -> context.lifecycle().onClose(
                            "bad", () -> {
                                throw new MaliciousThrowable();
                            }));
            handle.requireActive(java.time.Duration.ofSeconds(5));
            assertEquals(ComponentState.FAILED, handle.disposeAsync()
                    .toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS));
            RuntimeDiagnostic diagnostic = runtime.advanced().snapshot().diagnostics().stream()
                    .filter(item -> item.code() == DiagnosticCode.CLEANUP_FAILED
                            && item.targetId().equals(handle.handleId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(MaliciousThrowable.class.getName(),
                    diagnostic.failure().exceptionType());
        } finally {
            try {
                runtime.close();
            } catch (RuntimeException expected) {
                // Failed root cleanup is reported by the assertions above.
            }
        }
    }

    private static final class MaliciousThrowable extends RuntimeException {
        MaliciousThrowable() {
            super("unused");
        }

        MaliciousThrowable(Throwable cause) {
            super("unused", cause);
        }

        @Override
        public String getMessage() {
            throw new IllegalStateException("message getter failed");
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            StackTraceElement[] frames = new StackTraceElement[3];
            frames[1] = new StackTraceElement("Malicious", "frame", "Malicious.java", 1);
            return frames;
        }
    }
    private static Throwable chained(int depth) {
        Throwable current = new IllegalStateException("root-0");
        for (int index = 1; index < depth; index++) {
            current = new IllegalStateException("root-" + index, current);
        }
        return current;
    }
}
